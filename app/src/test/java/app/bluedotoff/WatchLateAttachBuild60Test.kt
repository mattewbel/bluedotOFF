package app.bluedotoff

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.adb.WatchClient
import app.bluedotoff.adb.WatchProtocol
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Regression coverage for a helper handshake that finishes after the user or USB has stopped watching. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class WatchLateAttachBuild60Test {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("ui-state",0)
    private fun notice() = app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id==4201 }
    private fun usb(on:Boolean) { Settings.Global.putInt(app.contentResolver,Settings.Global.ADB_ENABLED,if(on) 1 else 0) }
    private fun start() {
        app.applicationInfo.sourceDir="/data/app/fixture/base.apk"
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        usb(true)
        BackgroundWatch.set(app,true)
    }
    private fun peer() = Binder().apply { attachInterface(null,WatchProtocol.DESCRIPTOR) }
    private fun attach(binder:IBinder,id:String="fixture-watch"):Boolean {
        val profile=JSONObject().put("pid",100).put("uid",2000).put("selinux","u:r:shell:s0")
            .put("abiVerified",true).put("historyAbiVerified",true).put("indicatorAbiVerified",true)
            .put("fingerprint",Build.FINGERPRINT).put("oneUi","90000").put("profileId","fixture")
            .put("watchProtocol",1).put("watchId",id)
            .put("appUpdatedAt",app.packageManager.getPackageInfo(app.packageName,0).lastUpdateTime)
        return WatchClient.attach(app,binder,profile,app.applicationInfo.sourceDir,BuildConfig.VERSION_CODE.toLong())
    }
    private fun worker()=WatchClient::class.java.getDeclaredField("worker").apply{isAccessible=true}.get(WatchClient) as ExecutorService
    private fun stopAsUsbOff() {
        usb(false)
        WatchClient.stop(app,WatchNotification.Reason.USB_DEBUGGING_OFF)
        worker().submit{}.get(8,TimeUnit.SECONDS)
        assertTrue("real USB loss should leave the stop notification visible",notice())
        assertEquals("USB_DEBUGGING_OFF",prefs.getString("watchStopNotified",null))
    }
    private data class PendingAttach(val result:java.util.concurrent.Future<Boolean>,val release:CountDownLatch,val pool:ExecutorService)
    private fun beginAttachBlockedAtDescriptor():PendingAttach {
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        val base=peer()
        val blocked=object:IBinder by base {
            override fun getInterfaceDescriptor():String {
                entered.countDown()
                check(release.await(10,TimeUnit.SECONDS))
                return WatchProtocol.DESCRIPTOR
            }
        }
        val pool=Executors.newSingleThreadExecutor()
        val result=pool.submit<Boolean>{attach(blocked,"late-candidate")}
        assertTrue("attach did not reach the controlled handshake point",entered.await(8,TimeUnit.SECONDS))
        return PendingAttach(result,release,pool)
    }

    @After fun cleanup() {
        BackgroundWatch.set(app,false)
        WatchClient.stopNow(app)
    }

    @Test fun genuineUsbLossKeepsItsNoticeAndRejectsANewHelper() {
        start()
        assertTrue(attach(peer()))

        stopAsUsbOff()

        assertFalse(WatchClient.available(app))
        assertFalse(prefs.getBoolean("watchRunning",true))
        assertFalse("a new helper cannot attach while USB debugging is off",attach(peer(),"after-usb-off"))
        assertTrue("a rejected helper must not clear the real USB stop notice",notice())
        assertEquals("USB_DEBUGGING_OFF",prefs.getString("watchStopNotified",null))
    }

    @Test fun lateHandshakeCannotUndoACompletedUsbStop() {
        start()
        val pending=beginAttachBlockedAtDescriptor()
        try {
            stopAsUsbOff()
            pending.release.countDown()

            assertFalse("an attach that began before USB stopped must be rejected at commit",pending.result.get(8,TimeUnit.SECONDS))
            assertFalse(WatchClient.available(app))
            assertFalse(prefs.getBoolean("watchRunning",true))
            assertEquals("USB_DEBUGGING_OFF",prefs.getString("watchStopNotified",null))
            assertTrue("the genuine stop notification must remain visible",notice())
        } finally {
            pending.release.countDown()
            pending.pool.shutdownNow()
        }
    }

    @Test fun lateHandshakeCannotUndoTheUsersWatchOffChoice() {
        start()
        val pending=beginAttachBlockedAtDescriptor()
        try {
            BackgroundWatch.set(app,false)
            pending.release.countDown()

            assertFalse("a stale handshake must not re-enable a user-disabled watch",pending.result.get(8,TimeUnit.SECONDS))
            assertFalse(BackgroundWatch.enabled(app))
            assertFalse(WatchClient.available(app))
            assertFalse(prefs.getBoolean("watchRunning",true))
            assertNull("manual watch-off clears its old stop reason",prefs.getString("watchStopNotified",null))
            assertFalse(notice())
        } finally {
            pending.release.countDown()
            pending.pool.shutdownNow()
        }
    }

    @Test fun anAttachmentCompletedBeforeUsbLossStillGetsTheNormalStopNotice() {
        start()
        assertTrue(attach(peer(),"already-attached"))
        assertTrue(WatchClient.available(app))

        stopAsUsbOff()

        assertFalse(WatchClient.available(app))
        assertFalse(prefs.getBoolean("watchRunning",true))
        assertTrue(notice())
        assertEquals("USB_DEBUGGING_OFF",prefs.getString("watchStopNotified",null))
    }
}
