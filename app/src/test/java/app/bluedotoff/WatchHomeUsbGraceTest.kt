package app.bluedotoff

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.*
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Home/initialization and late lifecycle broadcasts during an authenticated owner's USB read grace.
 * The helper lifetime and settings are simulated; no wireless startup or permission writer is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class WatchHomeUsbGraceTest {
    private val app get()=ApplicationProvider.getApplicationContext<Application>().also {
        it.applicationInfo.sourceDir="/data/app/fixture/base.apk"
    }
    private class Transport:Binder() {
        var stops=0
        init { attachInterface(null,WatchProtocol.DESCRIPTOR) }
        override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
            data.enforceInterface(WatchProtocol.DESCRIPTOR);data.readString()
            if(code==WatchProtocol.STOP)stops++
            reply!!.writeNoException();reply.writeString("{}");return true
        }
    }
    private class Peer(private val transport:Transport=Transport()):IBinder by transport {
        private var alive=true
        private val recipients=mutableListOf<IBinder.DeathRecipient>()
        val stops get()=transport.stops
        override fun isBinderAlive()=alive
        override fun linkToDeath(recipient:IBinder.DeathRecipient,flags:Int) { recipients+=recipient }
        override fun unlinkToDeath(recipient:IBinder.DeathRecipient,flags:Int)=recipients.remove(recipient)
        fun die() {
            check(alive);check(recipients.isNotEmpty());alive=false
            recipients.toList().forEach{it.binderDied()}
        }
    }
    private fun worker()=WatchClient::class.java.getDeclaredField("worker").apply{isAccessible=true}.get(WatchClient) as ExecutorService
    private fun drain() { repeat(2){worker().submit{}.get(8,TimeUnit.SECONDS)} }
    private fun usb(value:Int) { Settings.Global.putInt(app.contentResolver,Settings.Global.ADB_ENABLED,value) }
    private fun prefs()=app.getSharedPreferences("ui-state",0)
    private fun reason()=prefs().getString("watchStopNotified",null)
    private fun notice()=app.getSystemService(NotificationManager::class.java).activeNotifications.count{it.id==4201}
    private fun profile()=JSONObject().put("pid",100).put("uid",2000).put("selinux","u:r:shell:s0")
        .put("abiVerified",true).put("historyAbiVerified",true).put("indicatorAbiVerified",true)
        .put("fingerprint",Build.FINGERPRINT).put("oneUi","90000").put("profileId","fixture")
        .put("watchProtocol",1).put("watchId","home-grace-owner")
        .put("appUpdatedAt",app.packageManager.getPackageInfo(app.packageName,0).lastUpdateTime)
    private fun start(peer:Peer,initialize:Boolean=true) {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        usb(1);BackgroundWatch.set(app,true)
        if(initialize)WatchClient.initialize(app)
        assertTrue(WatchClient.attach(app,peer,profile(),app.applicationInfo.sourceDir,BuildConfig.VERSION_CODE.toLong()))
        drain()
    }
    private fun assertWaiting(peer:Peer) {
        assertTrue("the authenticated Binder is still alive",WatchClient.connected())
        assertFalse("unknown USB must not authorize new work",WatchClient.available(app))
        assertTrue("read grace must retain the saved running state",prefs().getBoolean("watchRunning",false))
        assertNull("a read grace is not a confirmed stop",reason())
        assertEquals(0,notice());assertEquals(0,peer.stops)
    }
    private fun recover(peer:Peer) {
        usb(1);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_250));drain()
        assertTrue(WatchClient.available(app));assertTrue(prefs().getBoolean("watchRunning",false))
        assertNull(reason());assertEquals(0,notice());assertEquals(0,peer.stops)
    }
    @After fun cleanup() {
        usb(1);drain();BackgroundWatch.set(app,false);WatchClient.stopNow(app)
        LifecycleEvents.widget=null;LifecycleEvents.listener=null
    }

    @Test fun homeEnsureStartedRetainsAnEstablishedOwnerDuringAReadGap() {
        val peer=Peer();start(peer)
        usb(-1)
        assertFalse(WatchClient.ensureStarted(app))
        assertWaiting(peer)
        recover(peer)
    }
    @Test fun initializationWithAnExistingOwnerAlsoUsesTheReadGrace() {
        val peer=Peer();start(peer,initialize=false)
        usb(-1)
        WatchClient.initialize(app)
        assertWaiting(peer)
        recover(peer)
    }
    @Test fun aLateSelfUpdateBroadcastCannotMarkTheKnownOwnerStoppedDuringReadGrace() {
        val peer=Peer();start(peer)
        usb(-1)
        assertFalse(WatchClient.ensureStarted(app));assertWaiting(peer)
        // The provider can establish the current installation's helper before MY_PACKAGE_REPLACED arrives.
        LifecycleReceiver().onReceive(app,Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertWaiting(peer)
        recover(peer)
    }
    @Test fun aLateBootCallbackCannotMarkTheKnownOwnerStoppedDuringReadGrace() {
        val peer=Peer();start(peer)
        usb(-1)
        assertFalse(WatchClient.ensureStarted(app));assertWaiting(peer)
        // This is LifecycleReceiver's synchronous watch callback, without its unrelated async journal job.
        WatchClient.bootCompleted(app)
        assertWaiting(peer)
        recover(peer)
    }
    @Test fun homeStillReportsConfirmedUsbOffImmediately() {
        val peer=Peer();start(peer)
        usb(0)
        assertFalse(WatchClient.ensureStarted(app))
        assertFalse(WatchClient.available(app));assertFalse(prefs().getBoolean("watchRunning",true))
        assertEquals("USB_DEBUGGING_OFF",reason());assertEquals(1,notice())
    }
    @Test fun realOwnerDeathDuringHomeReadGraceIsStillReportedImmediately() {
        val peer=Peer();start(peer)
        usb(-1)
        assertFalse(WatchClient.ensureStarted(app));assertWaiting(peer)
        peer.die()
        assertFalse(WatchClient.connected());assertFalse(prefs().getBoolean("watchRunning",true))
        assertNotNull(reason());assertEquals(1,notice())
        assertTrue(WatchDiagnostics.report(app).contains(WatchDiagnostics.Event.BINDER_DIED.label))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_250));drain()
        assertFalse(WatchClient.connected());assertEquals(1,notice());assertEquals(0,peer.stops)
    }
}
