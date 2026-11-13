package app.bluedotoff

import android.app.Application
import android.app.NotificationManager
import android.Manifest
import android.os.*
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.adb.*
import app.bluedotoff.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class WatchClientTest {
    private val c get()=ApplicationProvider.getApplicationContext<Application>().also{it.applicationInfo.sourceDir="/data/app/~~fixture/bluedot/base.apk"}
    private class Server:Binder(){var calls=0;var stopped=false;init{attachInterface(null,WatchProtocol.DESCRIPTOR)}
        override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean{data.enforceInterface(WatchProtocol.DESCRIPTOR);val q=JSONObject(data.readString()!!);calls++;if(code==WatchProtocol.STOP)stopped=true
            reply!!.writeNoException();reply.writeString(if(code==WatchProtocol.REQUEST)JSONObject().put("pid",100).toString() else JSONObject().put("configured",true).toString());return true}}
    private fun profile()=JSONObject().put("pid",100).put("uid",2000).put("selinux","u:r:shell:s0").put("abiVerified",true).put("historyAbiVerified",true).put("indicatorAbiVerified",true).put("fingerprint",Build.FINGERPRINT).put("oneUi","90000").put("profileId","fixture").put("watchProtocol",1).put("watchId","fixture-id").put("appUpdatedAt",c.packageManager.getPackageInfo(c.packageName,0).lastUpdateTime)
    private fun attach(s:Server,p:JSONObject=profile()):Boolean{Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1);BackgroundWatch.set(c,true);return WatchClient.attach(c,s,p,c.applicationInfo.sourceDir,BuildConfig.VERSION_CODE.toLong())}
    @After fun clean(){LifecycleEvents.widget=null;LifecycleEvents.listener=null;BackgroundWatch.set(c,false);WatchClient.stopNow(c)}
    @Test fun lateStartFailureCannotMarkAnAlreadyAttachedHelperStopped(){
        shadowOf(c).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Settings.Global.putInt(c.contentResolver,"wifi_on",0)
        attach(Server())
        var widgetChanges=0;var screenChanges=0
        shadowOf(android.os.Looper.getMainLooper()).idle()
        LifecycleEvents.widget={widgetChanges++};LifecycleEvents.listener={screenChanges++}
        WatchClient::class.java.getDeclaredMethod("lost",android.content.Context::class.java).apply{isAccessible=true}.invoke(WatchClient,c)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1,widgetChanges);assertEquals(1,screenChanges)
        assertTrue(WatchClient.available(c))
        assertTrue(c.getSharedPreferences("ui-state",0).getBoolean("watchRunning",false))
        assertNull(c.getSharedPreferences("ui-state",0).getString("watchStopNotified",null))
        assertTrue(c.getSystemService(NotificationManager::class.java).activeNotifications.none{it.id==4201})
    }
    @Test fun realUsbLossStillMarksTheWatchStopped(){
        shadowOf(c).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);attach(Server())
        Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,0)
        WatchClient::class.java.getDeclaredMethod("lost",android.content.Context::class.java).apply{isAccessible=true}.invoke(WatchClient,c)
        assertFalse(WatchClient.available(c))
        assertFalse(c.getSharedPreferences("ui-state",0).getBoolean("watchRunning",true))
        assertEquals("USB_DEBUGGING_OFF",c.getSharedPreferences("ui-state",0).getString("watchStopNotified",null))
        assertTrue(c.getSystemService(NotificationManager::class.java).activeNotifications.any{it.id==4201})
    }
    @Test fun skippedDueRetryKeepsItsQuotaAndDoesNotPollAfterThirtySeconds(){
        val server=Server();attach(server)
        val pkg="test.read.failure"
        val failed=updatedEntry(pkg).copy(state=TargetState.FAILED_SAFE,retryHide=true,lastVerifiedTime=System.currentTimeMillis()-700_000)
        assertTrue(JournalStore(c).commit(failed));assertTrue(WatchClient.signal(c,setOf(pkg)))
        val worker=WatchClient::class.java.getDeclaredField("worker").apply{isAccessible=true}.get(WatchClient) as java.util.concurrent.ExecutorService
        worker.submit{}.get(3,java.util.concurrent.TimeUnit.SECONDS)
        // Server's deliberately incomplete package response fails before the hide attempt.
        val calls=server.calls
        assertFalse(c.getSharedPreferences("watch-retry",0).contains(pkg))
        @Suppress("UNCHECKED_CAST") val timers=WatchClient::class.java.getDeclaredField("retryTimers").apply{isAccessible=true}.get(WatchClient) as Set<String>
        assertTrue(pkg in timers)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(31))
        worker.submit{}.get(3,java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(calls,server.calls);assertFalse(c.getSharedPreferences("watch-retry",0).contains(pkg))
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(569))
        worker.submit{}.get(3,java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("a skipped check still retries after ten minutes",server.calls>calls)
    }
    @Test fun usbOnDoesNotRequireWifiOrWirelessForAnExistingHelper(){val s=Server();Settings.Global.putInt(c.contentResolver,"wifi_on",0);Settings.Global.putInt(c.contentResolver,"adb_wifi_enabled",0);assertTrue(attach(s));assertTrue(WatchClient.available(c));WatchClient.exclusive(c){session->session.run{assertEquals("fixture",it.verify().abiProfile);assertTrue(it.liveShellServer())}};assertTrue(s.calls>0)}
    @Test fun switchOffBlocksNewWorkWithoutBreakingAnInflightRollback(){val s=Server();attach(s);WatchClient.exclusive(c){session->session.run{backend->BackgroundWatch.set(c,false);assertFalse(WatchClient.available(c));assertTrue(backend.liveShellServer())}};WatchClient.stopNow(c);assertTrue(s.stopped);assertFalse(WatchClient.connected())}
    @Test fun usbOffMakesHelperUnavailableEvenWhenBinderIsStillAlive(){val s=Server();attach(s);Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,0);assertFalse(WatchClient.available(c))}
    @Test fun wrongInstallOrProfileCannotBeAdopted(){val s=Server();BackgroundWatch.set(c,true);Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1);try{WatchClient.attach(c,s,profile(),"/data/app/other/base.apk",BuildConfig.VERSION_CODE.toLong());fail()}catch(_:IllegalArgumentException){};assertFalse(WatchClient.available(c));try{attach(s,profile().put("uid",10000));fail()}catch(_:Exception){};assertFalse(WatchClient.available(c))}
    @Test fun oldVersionCannotAttachAndDisabledSwitchRefusesHandshake(){val s=Server();BackgroundWatch.set(c,true);Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1);try{WatchClient.attach(c,s,profile(),c.applicationInfo.sourceDir,BuildConfig.VERSION_CODE.toLong()-1);fail()}catch(_:IllegalArgumentException){};BackgroundWatch.set(c,false);assertFalse(WatchClient.attach(c,s,profile(),c.applicationInfo.sourceDir,BuildConfig.VERSION_CODE.toLong()))}
    /** 1.3.2: a revived app process finding the same helper re-reads only apps with work left; a new helper reads all. */
    @Test fun theSameHelperDoesNotRereadAppsThatAreStillApplied(){
        fun e(pkg:String,state:TargetState,retry:Boolean=false)=Entry(Identity(pkg,0,10501,listOf("cert"),1,1,1),pkg,256,true,state,1,"fp","boot",true,settlingVerified=true,retryHide=retry)
        val entries=listOf(e("a.applied",TargetState.APPLIED),e("b.policy",TargetState.NEEDS_REAPPLY_POLICY),e("c.update",TargetState.NEEDS_REAPPLY_UPDATE),e("d.retry",TargetState.FAILED_SAFE,true),e("e.locked",TargetState.FAILED_ROLLBACK))
        assertEquals(setOf("a.applied","b.policy","c.update","d.retry"),WatchClient.attachTargets(entries,false))
        assertEquals(setOf("b.policy","c.update","d.retry"),WatchClient.attachTargets(entries,true))
    }
    @Test fun providerDoesNotAcceptOrdinaryAppCalls(){try{WatchBridgeProvider().call("attach",null,Bundle());fail()}catch(_:IllegalStateException){}}
    @Test fun launchHasFixedEntrypointAndRejectsShellInjection(){val command=WatchProtocol.launch("/data/app/~~fixture/bluedot/base.apk");assertTrue(command.contains("nohup setsid app_process"));assertTrue(command.contains("ResidentWatchHelper"));try{WatchProtocol.launch("/data/app/test/base.apk;id");fail()}catch(_:IllegalArgumentException){}}
    @Test fun foregroundRoutingUsesResidentHelperWithoutOpeningWireless(){val s=Server();attach(s);Settings.Global.putInt(c.contentResolver,"adb_wifi_enabled",0);Settings.Global.putInt(c.contentResolver,"wifi_on",0);WirelessWork.run(c,true){assertTrue(it.liveShellServer());assertEquals("fixture",it.verify().abiProfile)};assertFalse(WirelessWork.leasing);assertFalse(WirelessControls(c).wireless)}
    @Test fun updateWithTheSameVersionAndPathCannotKeepTheOldHelper(){val s=Server();try{attach(s,profile().put("appUpdatedAt",profile().getLong("appUpdatedAt")+1));fail()}catch(_:IllegalArgumentException){};assertFalse(WatchClient.available(c))}
    @Test fun stopWaitsForAnInflightOperationBeforeClosingHelper(){
        val s=Server();attach(s)
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1);val stopped=java.util.concurrent.CountDownLatch(1)
        val owner=Thread{WatchClient.exclusive(c){session->session.run{entered.countDown();release.await(2,java.util.concurrent.TimeUnit.SECONDS);assertTrue(it.liveShellServer())}}}
        owner.start();assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));BackgroundWatch.set(c,false)
        val stopper=Thread{WatchClient.stopNow(c);stopped.countDown()};stopper.start()
        try{assertFalse(stopped.await(100,java.util.concurrent.TimeUnit.MILLISECONDS));assertFalse(s.stopped)}finally{release.countDown()}
        assertTrue(stopped.await(2,java.util.concurrent.TimeUnit.SECONDS));owner.join(2000);stopper.join(2000);assertTrue(s.stopped)
    }
    @Test fun unavailableWatchRejectsTheQueueSoUpdateReceiverCanNotify(){
        BackgroundWatch.set(c,false)
        assertFalse(WatchClient.signal(c,setOf("test.updated")))
    }
    @Test fun acceptedUpdateStillNotifiesWhenUsbTurnsOffBeforeTheCheck(){
        shadowOf(c).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        attach(Server())
        val e=Entry(Identity("test.updated",0,10500,listOf("cert"),1,1,1),"Updated",256,true,TargetState.NEEDS_REAPPLY_UPDATE,1,"fp","boot",true,settlingVerified=true)
        LocalAdbSession.exclusive{
            assertTrue(JournalStore(c).commit(e))
            assertTrue(WatchClient.signal(c,setOf(e.identity.packageName)))
            Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,0)
        }
        val manager=c.getSystemService(NotificationManager::class.java)
        val until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
        while(manager.activeNotifications.none{it.notification.channelId==ReapplyNotification.CHANNEL}&&System.nanoTime()<until)Thread.sleep(10)
        assertEquals(1,manager.activeNotifications.count{it.notification.channelId==ReapplyNotification.CHANNEL})
        assertEquals(listOf(e),JournalStore(c).entries())
    }
    /** Claude review of the update reminder (2026-10-31): an accepted update the watch passes over without a result or an
     *  exception (an old record it cannot take, the 30-second retry delay; a check cut short without an error ends the same way) still gets the reminder. */
    private fun reminders()=c.getSystemService(NotificationManager::class.java).activeNotifications.count{it.notification.channelId==ReapplyNotification.CHANNEL}
    private fun updatedEntry(pkg:String,settled:Boolean=true)=Entry(Identity(pkg,0,10501,listOf("cert"),1,1,1),"Updated",256,true,TargetState.NEEDS_REAPPLY_UPDATE,1,"fp","boot",true,settlingVerified=settled)
    private fun signalAndWait(e:Entry){
        assertTrue(JournalStore(c).commit(e))
        assertTrue(WatchClient.signal(c,setOf(e.identity.packageName)))
        val until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
        while(reminders()==0&&System.nanoTime()<until)Thread.sleep(10)
    }
    @Test fun anUpdateTheWatchCannotTakeStillNotifies(){
        shadowOf(c).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);attach(Server())
        signalAndWait(updatedEntry("test.unsettled",settled=false))
        assertEquals(1,reminders())
    }
    @Test fun anUpdateWaitingOutTheRetryDelayStillNotifies(){
        shadowOf(c).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);attach(Server())
        val cooldown=WatchClient::class.java.getDeclaredField("cooldown").apply{isAccessible=true}.get(WatchClient) as WatchCooldown
        cooldown.result("test.cooling",SystemClock.elapsedRealtime(),false)
        signalAndWait(updatedEntry("test.cooling"))
        assertEquals(1,reminders())
    }
}

