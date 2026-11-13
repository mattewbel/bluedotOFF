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
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
/** A restarted app process waits LOST_AFTER_MS for the helper it had before "감시가 멈췄어요" (1.3.2; cold-start fixture
 *  from the Codex v53 review). One test: WatchClient initializes once per process (forkEvery=1). */
class WatchStopGraceTest {
    private val c get()=ApplicationProvider.getApplicationContext<Application>().also{it.applicationInfo.sourceDir="/data/app/~~fixture/bluedot/base.apk"}
    private class Server:Binder(){var calls=0;var stopped=false;init{attachInterface(null,WatchProtocol.DESCRIPTOR)}
        override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean{data.enforceInterface(WatchProtocol.DESCRIPTOR);val q=JSONObject(data.readString()!!);calls++;if(code==WatchProtocol.STOP)stopped=true
            reply!!.writeNoException();reply.writeString(if(code==WatchProtocol.REQUEST)JSONObject().put("pid",100).toString() else JSONObject().put("configured",true).toString());return true}}
    private fun profile()=JSONObject().put("pid",100).put("uid",2000).put("selinux","u:r:shell:s0").put("abiVerified",true).put("historyAbiVerified",true).put("indicatorAbiVerified",true).put("fingerprint",Build.FINGERPRINT).put("oneUi","90000").put("profileId","fixture").put("watchProtocol",1).put("watchId","fixture-id").put("appUpdatedAt",c.packageManager.getPackageInfo(c.packageName,0).lastUpdateTime)
    private fun attach(s:Server,p:JSONObject=profile()):Boolean{Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1);BackgroundWatch.set(c,true);return WatchClient.attach(c,s,p,c.applicationInfo.sourceDir,BuildConfig.VERSION_CODE.toLong())}
    @After fun clean(){LifecycleEvents.widget=null;LifecycleEvents.listener=null;BackgroundWatch.set(c,false);WatchClient.stopNow(c)}
    private val manager get()=c.getSystemService(NotificationManager::class.java)
    private fun notice()=manager.activeNotifications.any{it.id==4201}
    private fun step(ms:Long)=shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))
    private fun cold(){
        Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1)
        shadowOf(c).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        BackgroundWatch.set(c,true);BackgroundWatch.running(c);WatchClient.initialize(c);step(0)
    }
    @Test fun aFailedStartOnOpeningTheAppIsNotAStopWhileTheOldHelperMayStillReport(){
        cold();step(1000);assertFalse(notice())
        ForegroundState.started();ForegroundState.resumed()
        try{
            assertFalse(WatchClient.ensureStarted(c,true));assertFalse(notice())
            assertTrue(c.getSharedPreferences("ui-state",0).getBoolean("watchRunning",false))
            step(75_000);assertTrue("no helper after the wait is a real stop",notice())
            assertTrue(attach(Server()));assertFalse(notice())
        }finally{ForegroundState.paused();ForegroundState.stopped()}
    }
}
