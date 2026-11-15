package app.bluedotoff

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.os.*
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.adb.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class WatchContactRecoveryTest {
    val c get() = ApplicationProvider.getApplicationContext<Application>()
    private fun start() {
        c.applicationInfo.sourceDir="/data/app/~~fixture/bluedot/base.apk"
        shadowOf(c).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Settings.Global.putInt(c.contentResolver, Settings.Global.ADB_ENABLED, 1)
        BackgroundWatch.set(c,true)
    }
    private fun attach(b:Binder,id:String):Boolean {
        val p=JSONObject().put("pid",100).put("uid",2000).put("selinux","u:r:shell:s0")
            .put("abiVerified",true).put("historyAbiVerified",true).put("indicatorAbiVerified",true)
            .put("fingerprint",Build.FINGERPRINT).put("oneUi","90000").put("profileId","fixture")
            .put("watchProtocol",1).put("watchId",id).put("appUpdatedAt",c.packageManager.getPackageInfo(c.packageName,0).lastUpdateTime)
        return WatchClient.attach(c,b,p,c.applicationInfo.sourceDir,BuildConfig.VERSION_CODE.toLong())
    }
    private open class Peer : Binder() { init {attachInterface(null,WatchProtocol.DESCRIPTOR)} }
    private fun stopReason() = c.getSharedPreferences("ui-state",0).getString("watchStopNotified",null)
    private fun notice() = c.getSystemService(NotificationManager::class.java).activeNotifications.any {it.id==4201}
    @org.junit.After fun disconnectTestPeer() {
        BackgroundWatch.set(c,false)
        WatchClient.stopNow(c)
    }

    @Test fun sleepDoesNotShortenTheWindowForTheSameHelper() {
        start()
        val peer=Peer()
        val ui=c.getSharedPreferences("ui-state",0)
        ui.edit().putString("watchId","original").commit()
        BackgroundWatch.running(c)
        WatchClient.initialize(c)
        val uptime=SystemClock.uptimeMillis()
        val elapsed=SystemClock.elapsedRealtime()
        assertFalse(WatchClient.available(c))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        WatchClient.startupFailed(c)
        assertFalse(notice())
        ShadowSystemClock.simulateDeepSleep(Duration.ofMinutes(5))
        assertTrue(SystemClock.uptimeMillis()-uptime<2000)
        assertTrue(SystemClock.elapsedRealtime()-elapsed>=300000)
        assertFalse(notice())
        WatchClient.startupFailed(c)
        assertNull(stopReason())
        assertFalse(notice())
        assertTrue(peer.isBinderAlive)
        assertTrue(attach(peer,"original"))
        assertFalse(notice())
    }

    @Test fun oldStopCompletionLeavesReplacementRunning() {
        start()
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        val old=object:Peer() {
            override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
                if(code!=WatchProtocol.STOP) return super.onTransact(code,data,reply,flags)
                entered.countDown()
                check(release.await(6,TimeUnit.SECONDS))
                reply!!.writeNoException();reply.writeString("{}");return true
            }
        }
        assertTrue(attach(old,"old"))
        Settings.Global.putInt(c.contentResolver, Settings.Global.ADB_ENABLED, 0)
        WatchClient.stop(c,WatchNotification.Reason.USB_DEBUGGING_OFF)
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            Settings.Global.putInt(c.contentResolver, Settings.Global.ADB_ENABLED, 1)
            val newer=Peer()
            assertTrue(attach(newer,"new"))
            assertFalse(notice())
            release.countDown()
            val worker=WatchClient::class.java.getDeclaredField("worker").apply{isAccessible=true}.get(WatchClient) as ExecutorService
            worker.submit{}.get(5,TimeUnit.SECONDS)
            assertTrue(WatchClient.available(c))
            assertFalse(notice())
            assertNull(stopReason())
            assertTrue(attach(newer,"new"))
            assertFalse(notice())
        } finally {release.countDown()}
    }
}
