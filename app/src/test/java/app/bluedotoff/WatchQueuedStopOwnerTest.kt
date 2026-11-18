package app.bluedotoff

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.pm.ProviderInfo
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
import org.robolectric.shadows.ShadowBinder
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Queue an expired USB stop, then deliver the registered death callback and replace its owner.
 * Binder life/death and settings are simulated; the provider, timers and stop worker are production code. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class WatchQueuedStopOwnerTest {
    private val app get()=ApplicationProvider.getApplicationContext<Application>().also {
        it.applicationInfo.sourceDir="/data/app/fixture/base.apk"
    }
    private class Transport:Binder() {
        @Volatile var stops=0
        init { attachInterface(null,WatchProtocol.DESCRIPTOR) }
        override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
            data.enforceInterface(WatchProtocol.DESCRIPTOR);data.readString()
            if(code==WatchProtocol.STOP)stops++
            reply!!.writeNoException();reply.writeString("{}");return true
        }
    }
    private class Peer(val id:String,private val transport:Transport=Transport()):IBinder by transport {
        @Volatile private var alive=true
        private val recipients=mutableListOf<IBinder.DeathRecipient>()
        val stops get()=transport.stops
        override fun isBinderAlive()=alive
        override fun linkToDeath(recipient:IBinder.DeathRecipient,flags:Int) { recipients+=recipient }
        override fun unlinkToDeath(recipient:IBinder.DeathRecipient,flags:Int)=recipients.remove(recipient)
        fun die() {
            check(alive);alive=false
            check(recipients.isNotEmpty()) { "the real attachment must have registered its death recipient" }
            recipients.toList().forEach{it.binderDied()}
        }
    }
    private fun worker()=WatchClient::class.java.getDeclaredField("worker").apply{isAccessible=true}.get(WatchClient) as ExecutorService
    private fun drain() { repeat(2){worker().submit{}.get(8,TimeUnit.SECONDS)} }
    private fun usb(value:Int) { Settings.Global.putInt(app.contentResolver,Settings.Global.ADB_ENABLED,value) }
    private fun prefs()=app.getSharedPreferences("ui-state",0)
    private fun reason()=prefs().getString("watchStopNotified",null)
    private fun notice()=app.getSystemService(NotificationManager::class.java).activeNotifications.count{it.id==4201}
    private fun profile(peer:Peer)=JSONObject().put("pid",100).put("uid",2000).put("selinux","u:r:shell:s0")
        .put("abiVerified",true).put("historyAbiVerified",true).put("indicatorAbiVerified",true)
        .put("fingerprint",Build.FINGERPRINT).put("oneUi","90000").put("profileId","fixture")
        .put("watchProtocol",1).put("watchId",peer.id)
        .put("appUpdatedAt",app.packageManager.getPackageInfo(app.packageName,0).lastUpdateTime)
    private fun attach(peer:Peer) {
        assertTrue(WatchClient.attach(app,peer,profile(peer),app.applicationInfo.sourceDir,BuildConfig.VERSION_CODE.toLong()))
    }
    private fun contact(peer:Peer):Bundle {
        val provider=WatchBridgeProvider().also {
            it.attachInfo(app,ProviderInfo().apply{authority=WatchProtocol.AUTHORITY})
        }
        val caller=Binder.getCallingUid()
        return try {
            ShadowBinder.setCallingUid(2000)
            provider.call("state",null,Bundle().apply {
                putBinder("helper",peer);putString("profile",profile(peer).toString())
                putString("apk",app.applicationInfo.sourceDir);putLong("version",BuildConfig.VERSION_CODE.toLong())
            })
        } finally { ShadowBinder.setCallingUid(caller) }
    }
    private fun start(peer:Peer) {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        usb(1);BackgroundWatch.set(app,true);attach(peer);drain()
    }
    private fun step(ms:Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
    private fun queueExpiredStop(peer:Peer):CountDownLatch {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        worker().execute{entered.countDown();check(release.await(15,TimeUnit.SECONDS))}
        assertTrue(entered.await(5,TimeUnit.SECONDS))
        try {
            usb(-1)
            assertTrue(contact(peer).getBoolean("retryContact"))
            step(1_250)
            assertEquals("STOP must still be waiting behind the worker gate",0,peer.stops)
            assertEquals(0,notice())
            return release
        } catch(e:Throwable) { release.countDown();throw e }
    }
    @After fun cleanup() {
        usb(1);drain();BackgroundWatch.set(app,false);WatchClient.stopNow(app)
        ShadowBinder.setCallingUid(Process.myUid())
        LifecycleEvents.widget=null;LifecycleEvents.listener=null
    }

    @Test fun anExpiredOldOwnersQueuedStopCannotConsumeTheReplacementOwnersGrace() {
        val old=Peer("old");start(old)
        val release=queueExpiredStop(old)
        try {
            old.die()
            assertFalse(WatchClient.connected())
            assertEquals("a real death is reported even during a USB read gap",1,notice())
            val replacement=Peer("replacement")
            usb(1);attach(replacement)
            assertTrue(WatchClient.available(app));assertNull(reason());assertEquals(0,notice())
            usb(-1)
            assertTrue("the replacement has its own fresh grace window",contact(replacement).getBoolean("retryContact"))
            release.countDown();drain()
            assertEquals("the old owner's queued STOP must not be sent to the replacement",0,replacement.stops)
            assertTrue(WatchClient.connected());assertNull(reason());assertEquals(0,notice())
            step(1_249);drain()
            assertEquals(0,replacement.stops);assertEquals(0,notice())
            step(1);drain()
            assertEquals("its own persistent read failure still stops the replacement once",1,replacement.stops)
            assertFalse(WatchClient.connected());assertEquals("USB_STATE_UNKNOWN",reason());assertEquals(1,notice())
            assertEquals("the already dead old peer receives no STOP",0,old.stops)
        } finally { release.countDown();drain() }
    }
    @Test fun anExpiredCurrentOwnersQueuedStopStillStopsPersistentFailureOnce() {
        val peer=Peer("current");start(peer)
        val release=queueExpiredStop(peer)
        try {
            release.countDown();drain()
            assertEquals(1,peer.stops)
            assertFalse(WatchClient.connected());assertEquals("USB_STATE_UNKNOWN",reason());assertEquals(1,notice())
            step(2_500);drain()
            assertEquals(1,peer.stops);assertEquals(1,notice())
        } finally { release.countDown();drain() }
    }
    @Test fun confirmedContactBetweenTwoReadGapsGivesTheSameOwnerAFreshWindow() {
        val peer=Peer("same");start(peer)
        val release=queueExpiredStop(peer)
        try {
            usb(1);contact(peer)
            assertTrue(WatchClient.available(app))
            usb(-1);assertTrue(contact(peer).getBoolean("retryContact"))
            release.countDown();drain()
            assertEquals("the first gap ended before the second started",0,peer.stops)
            step(1_249);drain();assertTrue(WatchClient.connected());assertNull(reason())
            step(1);drain();assertEquals(1,peer.stops);assertEquals("USB_STATE_UNKNOWN",reason())
        } finally { release.countDown();drain() }
    }
    @Test fun anExpiredOldStopLeavesAHealthyReplacementRunning() {
        val old=Peer("old");start(old)
        val release=queueExpiredStop(old)
        try {
            old.die();usb(1)
            val replacement=Peer("healthy");attach(replacement)
            release.countDown();drain();step(2_500);drain()
            assertTrue(WatchClient.available(app));assertNull(reason());assertEquals(0,notice())
            assertEquals(0,replacement.stops);assertEquals(0,old.stops)
        } finally { release.countDown();drain() }
    }
}
