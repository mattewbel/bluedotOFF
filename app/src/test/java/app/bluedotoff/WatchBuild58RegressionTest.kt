package app.bluedotoff

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.adb.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class WatchBuild58RegressionTest {
    private val c get() = ApplicationProvider.getApplicationContext<Application>()

    private fun start() {
        c.applicationInfo.sourceDir="/data/app/~~fixture/bluedot/base.apk"
        shadowOf(c).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1)
        BackgroundWatch.set(c,true)
    }

    private fun attach(binder:Binder,id:String):Boolean {
        val profile=JSONObject().put("pid",100).put("uid",2000).put("selinux","u:r:shell:s0")
            .put("abiVerified",true).put("historyAbiVerified",true).put("indicatorAbiVerified",true)
            .put("fingerprint",Build.FINGERPRINT).put("oneUi","90000").put("profileId","fixture")
            .put("watchProtocol",1).put("watchId",id)
            .put("appUpdatedAt",c.packageManager.getPackageInfo(c.packageName,0).lastUpdateTime)
        return WatchClient.attach(c,binder,profile,c.applicationInfo.sourceDir,BuildConfig.VERSION_CODE.toLong())
    }

    private open class Peer:Binder(){init{attachInterface(null,WatchProtocol.DESCRIPTOR)}}
    private class CountingPeer:Peer(){
        var configurations=0
        var stops=0
        override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean{
            data.enforceInterface(WatchProtocol.DESCRIPTOR)
            data.readString()
            if(code==WatchProtocol.CONFIGURE)configurations++
            if(code==WatchProtocol.STOP)stops++
            reply!!.writeNoException()
            reply.writeString("{}")
            return true
        }
    }
    private fun prefs()=c.getSharedPreferences("ui-state",0)
    private fun stopReason()=prefs().getString("watchStopNotified",null)
    private fun notice()=c.getSystemService(NotificationManager::class.java).activeNotifications.any{it.id==4201}
    private fun step(ms:Long)=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun worker()=WatchClient::class.java.getDeclaredField("worker").apply{isAccessible=true}.get(WatchClient) as ExecutorService

    @After fun cleanup(){
        BackgroundWatch.set(c,false)
        WatchClient.stopNow(c)
    }

    @Test fun coldInitializationWithUsbAlreadyOffReplacesAnOldUnconfirmedAdvisory(){
        start()
        BackgroundWatch.running(c)
        WatchNotification.contactMissing(c)
        assertEquals("CONTACT_UNCONFIRMED",stopReason())
        assertTrue(notice())

        Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,0)
        WatchClient.initialize(c)
        step(75_000)

        assertFalse(WatchClient.available(c))
        assertEquals(WatchNotification.Reason.USB_DEBUGGING_OFF.name,stopReason())
        assertFalse(WatchNotification.awaitingContact(c,false))
    }

    @Test fun quickUsbBounceRetainsAcceptedQueuedWork(){
        start()
        val peer=Peer()
        assertTrue(attach(peer,"still-running"))
        worker().submit{}.get(5,TimeUnit.SECONDS)
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        worker().execute{entered.countDown();release.await(8,TimeUnit.SECONDS)}
        try{
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            val pending=WatchClient::class.java.getDeclaredField("pending").apply{isAccessible=true}
            assertTrue(WatchClient.signal(c,setOf("build58.queued")))
            assertTrue((pending.get(WatchClient) as Set<*>).contains("build58.queued"))

            Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,0)
            WatchClient.stop(c,WatchNotification.Reason.USB_DEBUGGING_OFF)
            Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1)

            assertTrue("accepted target must remain queued or move to deferred work",
                (pending.get(WatchClient) as Set<*>).contains("build58.queued") ||
                    (WatchClient::class.java.getDeclaredField("deferred").apply{isAccessible=true}.get(WatchClient) as Set<*>).contains("build58.queued"))
        }finally{release.countDown()}
        worker().submit{}.get(5,TimeUnit.SECONDS)
    }

    @Test fun lateBootBroadcastDoesNotStopAnAlreadyLiveHelper(){
        start()
        val live=Peer()
        assertTrue(attach(live,"booted"))
        LifecycleReceiver().onReceive(c,Intent(Intent.ACTION_BOOT_COMPLETED))

        assertTrue(WatchClient.available(c))
        assertNull(stopReason())
        assertFalse(notice())
    }

    @Test fun workDequeuedWhileUsbIsOffIsReadAfterTheStopIsWithdrawn(){
        start()
        val peer=CountingPeer()
        assertTrue(attach(peer,"usb-bounce"))
        val worker=worker()
        worker.submit{}.get(5,TimeUnit.SECONDS)
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        worker.execute{entered.countDown();release.await(8,TimeUnit.SECONDS)}
        assertTrue(entered.await(5,TimeUnit.SECONDS))
        val before=peer.configurations
        try{
            assertTrue(WatchClient.signal(c,setOf("build58.queued")))
            Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,0)
        }finally{release.countDown()}
        worker.submit{}.get(5,TimeUnit.SECONDS)
        assertEquals(before,peer.configurations)
        // A foreground batch may also finish before the temporary USB loss ends.
        WatchClient.foregroundIdle()
        Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1)
        WatchClient.stop(c,WatchNotification.Reason.USB_DEBUGGING_OFF)
        worker.submit{}.get(5,TimeUnit.SECONDS)
        worker.submit{}.get(5,TimeUnit.SECONDS)
        assertEquals(before+1,peer.configurations)
        assertEquals(0,peer.stops)
        assertTrue(WatchClient.available(c))
    }

    @Test fun manuallyTurningWatchOffClearsTheAdvisory(){
        start()
        BackgroundWatch.running(c)
        WatchNotification.contactMissing(c)
        assertTrue(notice())
        assertEquals("CONTACT_UNCONFIRMED",stopReason())

        BackgroundWatch.set(c,false)
        WatchClient.stop(c)
        worker().submit{}.get(5,TimeUnit.SECONDS)

        assertFalse(BackgroundWatch.enabled(c))
        assertFalse(notice())
        assertNull(stopReason())
    }
}
