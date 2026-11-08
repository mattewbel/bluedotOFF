package app.bluedotoff

import android.app.Application
import android.os.*
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.adb.*
import app.bluedotoff.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BeforeUninstallWatchTest {
    private val context get()=ApplicationProvider.getApplicationContext<Application>()
    private val getter get()=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}
    private val setter get()=MainActivity::class.java.getDeclaredMethod("setModel",UiModel::class.java).apply{isAccessible=true}
    @Suppress("UNCHECKED_CAST")
    private val batches get()=BatchRuntime::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(BatchRuntime) as MutableStateFlow<BatchUiState>
    private fun entry()=Entry(Identity("test.map",0,12345,listOf("signer"),1,1,1),"지도",256,true,TargetState.APPLIED,1,"firmware","boot",true)
    private class Server(private val context:Application):Binder(){
        @Volatile var restoreSessionWithLiveWatch=false
        @Volatile var stopped=false
        init{attachInterface(null,WatchProtocol.DESCRIPTOR)}
        override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
            data.enforceInterface(WatchProtocol.DESCRIPTOR)
            val request=JSONObject(data.readString()!!)
            if(code==WatchProtocol.STOP)stopped=true
            if(code==WatchProtocol.CONFIGURE)restoreSessionWithLiveWatch=BackgroundWatch.enabled(context)&&!stopped
            if(code==WatchProtocol.REQUEST){
                // Deliberate restore failure: remaining apps must not be declared safe to uninstall.
                reply!!.writeException(SecurityException("fixture read rejected"))
            }else{reply!!.writeNoException();reply.writeString(JSONObject().put("configured",true).toString())}
            return true
        }
    }
    private fun attach(server:Server){
        context.applicationInfo.sourceDir="/data/app/~~fixture/bluedot/base.apk"
        Settings.Global.putInt(context.contentResolver,Settings.Global.ADB_ENABLED,1)
        Settings.Global.putInt(context.contentResolver,"wifi_on",0)
        Settings.Global.putInt(context.contentResolver,"adb_wifi_enabled",0)
        BackgroundWatch.set(context,true)
        val profile=JSONObject().put("pid",100).put("uid",2000).put("selinux","u:r:shell:s0").put("abiVerified",true)
            .put("historyAbiVerified",true).put("indicatorAbiVerified",true).put("fingerprint",Build.FINGERPRINT).put("oneUi","90000")
            .put("profileId","fixture").put("watchProtocol",1).put("watchId","fixture-id")
            .put("appUpdatedAt",context.packageManager.getPackageInfo(context.packageName,0).lastUpdateTime)
        assertTrue(WatchClient.attach(context,server,profile,context.applicationInfo.sourceDir,BuildConfig.VERSION_CODE.toLong()))
    }
    private fun await(condition:()->Boolean){
        val until=System.nanoTime()+5_000_000_000L
        while(!condition()&&System.nanoTime()<until){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
        assertTrue("Asynchronous restore/stop did not finish",condition())
    }
    @Before fun reset(){batches.value=BatchUiState()}
    @After fun clean(){BackgroundWatch.set(context,false);WatchClient.stopNow(context);batches.value=BatchUiState()}
    @Test fun completedRestoreClearsDismissalEpisodeWithoutAnActivity(){
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val server=Server(context);attach(server)
        val issue=entry().copy(state=TargetState.UNSUPPORTED_STATE)
        val journal=JournalStore(context);assertTrue(journal.commit(issue))
        ReapplyNotification.show(context,issue,true)
        val manager=context.getSystemService(android.app.NotificationManager::class.java)
        val deleted=shadowOf(shadowOf(manager).allNotifications.single().deleteIntent).savedIntent
        manager.cancelAll();LifecycleReceiver().onReceive(context,deleted)
        // The durable result is restored; run the real pipeline's completion path without a UI or new writes.
        assertTrue(journal.commit(issue.copy(enabled=false,state=TargetState.NOT_APPLIED)))
        BatchPipeline.run(context,emptyList(),emptyMap(),false,false,false,false,emptySet(),ReapplySource.MANUAL,{true},true,{})
        assertTrue(journal.commit(issue));ReapplyNotification.show(context,issue,true)
        assertEquals(1,shadowOf(manager).allNotifications.size)
        assertFalse(server.restoreSessionWithLiveWatch)
    }
    @Test fun queuedAutomaticBatchCannotEnrollAnAppThatWasRetiredWhileWaiting(){
        val server=Server(context);attach(server)
        val retired=entry().copy(enabled=false,state=TargetState.NOT_APPLIED,locationRetired=true)
        assertTrue(JournalStore(context).commit(retired))
        val outcome=BatchPipeline.run(context,listOf("test.map"),emptyMap(),true,false,true,false,setOf("test.map"),ReapplySource.APP_OPEN,{true},true,{})
        assertEquals("작업을 멈춰서 이 앱은 바꾸지 않았어요.",outcome.error)
        assertEquals(1,outcome.results.size);assertFalse(outcome.results.single().success);assertEquals(retired,JournalStore(context).entries().single())
        assertFalse(server.restoreSessionWithLiveWatch)
    }
    @Test fun offlineUninstallRestoreKeepsTheHelperUntilItsResultAndDoesNotHideFailure(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        val server=Server(context)
        try {
            assertTrue(JournalStore(context).commit(entry()))
            attach(server)
            setter.invoke(controller.get(),UiModel(gate=Gate.READY,page=Page.SETTINGS,entries=listOf(entry()),connected=true,backgroundWatch=true))
            ForegroundState.started();ForegroundState.resumed()
            MainActivity::class.java.getDeclaredMethod("beforeUninstall").apply{isAccessible=true}.invoke(controller.get())
            await{server.stopped}
            assertTrue("Restore must use the resident session even with Wi-Fi OFF",server.restoreSessionWithLiveWatch)
            // The result follows the cleanup (GPT rc14 review).
            await{(getter.invoke(controller.get()) as UiModel).uninstallCheck!=null}
            val model=getter.invoke(controller.get()) as UiModel
            assertEquals(1,model.uninstallCheck)
            assertFalse(BackgroundWatch.enabled(context));assertFalse(WatchClient.available(context))
            assertEquals(0,Settings.Global.getInt(context.contentResolver,"adb_wifi_enabled",0))
        }finally{ForegroundState.paused();ForegroundState.stopped();controller.destroy()}
    }
    /** Full review 2026-11-07: pressed again when every app left needs a check (nothing restorable now), the uninstall
     *  result still names them and says not to uninstall, as after the first press, instead of only a notice. */
    @Test fun uninstallRestoreWithOnlyAppsToCheckStillWarnsNotToUninstall(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        val server=Server(context)
        try {
            val first=entry().copy(state=TargetState.FAILED_ROLLBACK)
            val second=entry().copy(identity=Identity("test.bank",0,12346,listOf("signer"),1,1,1),label="은행",state=TargetState.NEEDS_REVIEW)
            val journal=JournalStore(context);assertTrue(journal.commit(first));assertTrue(journal.commit(second))
            attach(server)
            setter.invoke(controller.get(),UiModel(gate=Gate.READY,page=Page.SETTINGS,entries=journal.entries(),connected=true,backgroundWatch=true))
            ForegroundState.started();ForegroundState.resumed()
            MainActivity::class.java.getDeclaredMethod("beforeUninstall").apply{isAccessible=true}.invoke(controller.get())
            await{(getter.invoke(controller.get()) as UiModel).uninstallCheck!=null}
            assertEquals(2,(getter.invoke(controller.get()) as UiModel).uninstallCheck)
            assertTrue(server.stopped)
            assertEquals(2,journal.entries().count{it.enabled})
        }finally{ForegroundState.paused();ForegroundState.stopped();controller.destroy()}
    }
    @Test fun verifiedUninstallRestoreCompletionThenStopsWatchingAndAllowsUninstall(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        val server=Server(context)
        try {
            attach(server)
            setter.invoke(controller.get(),UiModel(gate=Gate.READY,page=Page.SETTINGS,entries=listOf(entry()),connected=true,backgroundWatch=true))
            assertFalse(server.stopped);assertTrue(BackgroundWatch.enabled(context))
            // Inject a verified completion to exercise the real Activity outcome/stop ordering, without a system setter.
            val restored=entry().copy(enabled=false,state=TargetState.NOT_APPLIED)
            val outcome=BatchOutcome(false,listOf(BatchResult("test.map",restored)),listOf(restored),emptyList(),beforeUninstall=true)
            batches.value=BatchUiState(100,BatchProgress(1,1,BatchPhase.FINISHED),outcome,hide=false)
            await{server.stopped}
            await{(getter.invoke(controller.get()) as UiModel).uninstallCheck!=null}
            assertEquals(0,(getter.invoke(controller.get()) as UiModel).uninstallCheck)
            assertFalse(BackgroundWatch.enabled(context));assertFalse(WatchClient.connected())
        }finally{controller.destroy()}
    }
    /** Claude review 2026-10-31: a re-hide that failed in the app gets the watch's 10-minute retry timer. */
    @Test fun aReHideThatFailedInTheAppIsHandedToTheWatch(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        val server=Server(context)
        try {
            attach(server)
            val failed=entry().copy(state=TargetState.FAILED_SAFE,retryHide=true,settlingVerified=false,lastVerifiedTime=System.currentTimeMillis())
            assertTrue(JournalStore(context).commit(failed))
            val outcome=BatchOutcome(true,listOf(BatchResult("test.map",failed,"WATCH_FAILED")),listOf(failed),emptyList())
            batches.value=BatchUiState(101,BatchProgress(1,1,BatchPhase.FINISHED),outcome,hide=true)
            @Suppress("UNCHECKED_CAST") val timers=WatchClient::class.java.getDeclaredField("retryTimers").apply{isAccessible=true}.get(WatchClient) as Set<String>
            await{"test.map" in timers}
        }finally{controller.destroy()}
    }
}

