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
    @Test fun offlineUninstallRestoreKeepsTheHelperUntilItsResultAndDoesNotHideFailure(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        val server=Server(context)
        try {
            attach(server)
            setter.invoke(controller.get(),UiModel(gate=Gate.READY,page=Page.SETTINGS,entries=listOf(entry()),connected=true,backgroundWatch=true))
            ForegroundState.started();ForegroundState.resumed()
            MainActivity::class.java.getDeclaredMethod("beforeUninstall").apply{isAccessible=true}.invoke(controller.get())
            await{server.stopped}
            assertTrue("Restore must use the resident session even with Wi-Fi OFF",server.restoreSessionWithLiveWatch)
            val model=getter.invoke(controller.get()) as UiModel
            assertEquals(1,model.uninstallCheck)
            assertFalse(BackgroundWatch.enabled(context));assertFalse(WatchClient.available(context))
            assertEquals(0,Settings.Global.getInt(context.contentResolver,"adb_wifi_enabled",0))
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
            assertEquals(0,(getter.invoke(controller.get()) as UiModel).uninstallCheck)
            assertFalse(BackgroundWatch.enabled(context));assertFalse(WatchClient.connected())
        }finally{controller.destroy()}
    }
}
