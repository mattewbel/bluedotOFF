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

/** Regression coverage for an observer OFF queued behind work, then owner recovery and a new read gap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class QueuedUsbOffRecoveryRegressionTest {
    private val c get() = ApplicationProvider.getApplicationContext<Application>().also {
        it.applicationInfo.sourceDir = "/data/app/field-audit/base.apk"
    }

    private class Transport : Binder() {
        @Volatile var stops = 0
        init { attachInterface(null, WatchProtocol.DESCRIPTOR) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(WatchProtocol.DESCRIPTOR)
            data.readString()
            if (code == WatchProtocol.STOP) stops++
            reply!!.writeNoException()
            reply.writeString("{}")
            return true
        }
    }
    private class Peer(val id: String, private val transport: Transport = Transport()) : IBinder by transport {
        @Volatile var alive = true
        val recipients = mutableListOf<IBinder.DeathRecipient>()
        val stops get() = transport.stops
        override fun isBinderAlive() = alive
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) { recipients += recipient }
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = recipients.remove(recipient)
    }

    private fun worker() = WatchClient::class.java.getDeclaredField("worker").apply { isAccessible = true }
        .get(WatchClient) as ExecutorService
    private fun drain() { repeat(2) { worker().submit {}.get(8, TimeUnit.SECONDS) } }
    private fun step(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        drain()
    }
    private fun usb(value: Int) { Settings.Global.putInt(c.contentResolver, Settings.Global.ADB_ENABLED, value) }
    private fun notice() = c.getSystemService(NotificationManager::class.java).activeNotifications.count { it.id == 4201 }
    private fun reason() = c.getSharedPreferences("ui-state", 0).getString("watchStopNotified", null)
    private fun profile(peer: Peer) = JSONObject().put("pid", 100).put("uid", 2000)
        .put("selinux", "u:r:shell:s0").put("abiVerified", true).put("historyAbiVerified", true)
        .put("indicatorAbiVerified", true).put("fingerprint", Build.FINGERPRINT).put("oneUi", "90000")
        .put("profileId", "fixture").put("watchProtocol", 1).put("watchId", peer.id)
        .put("appUpdatedAt", c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime)
    private fun contact(peer: Peer): Bundle {
        val provider = WatchBridgeProvider().also {
            it.attachInfo(c, ProviderInfo().apply { authority = WatchProtocol.AUTHORITY })
        }
        val previous = Binder.getCallingUid()
        return try {
            ShadowBinder.setCallingUid(2000)
            provider.call("state", null, Bundle().apply {
                putBinder("helper", peer)
                putString("profile", profile(peer).toString())
                putString("apk", c.applicationInfo.sourceDir)
                putLong("version", BuildConfig.VERSION_CODE.toLong())
            })
        } finally { ShadowBinder.setCallingUid(previous) }
    }
    private fun start(peer: Peer = Peer("owner")): Peer {
        shadowOf(c).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        usb(1)
        BackgroundWatch.set(c, true)
        assertTrue(contact(peer).getBoolean("enabled"))
        drain()
        // Robolectric can replace the application resolver between cases while this singleton survives.
        WatchClient::class.java.getDeclaredField("initialized").apply { isAccessible = true }.setBoolean(WatchClient, false)
        WatchClient.initialize(c)
        return peer
    }
    private fun blockWorker(): Pair<CountDownLatch, CountDownLatch> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker().execute { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
        assertTrue("serial worker entered its blocking task", entered.await(5, TimeUnit.SECONDS))
        return entered to release
    }
    private fun observerSawOff(): Boolean {
        val state = WatchClient::class.java.getDeclaredField("states").apply { isAccessible = true }
            .get(WatchClient) as String
        return state.contains("usbDebugging=0") || state.contains("usb=0")
    }
    @After fun cleanup() { BackgroundWatch.set(c, false); WatchClient.stopNow(c); drain(); WatchUsbReadSequence.set() }

    @Config(shadows=[WatchUsbReadSequence::class])
    @Test fun queuedOffThenOwnerOnThenFreshUnknownGetsFullGraceAndCanRecover() {
        val owner = start()
        val (_, release) = blockWorker()
        try {
            usb(0)
            c.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), null)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("registered production observer records OFF before queue dequeue", observerSawOff())

            usb(1)
            assertTrue("same authenticated owner confirms ON", contact(owner).getBoolean("enabled"))
            // The authenticated contact and the queued observer work both encounter UNKNOWN.
            // Keep the shadow reads unavailable through queue drain; the backing setting stays ON.
            WatchUsbReadSequence.set(*IntArray(32) { -1 })
            val gapStarted = SystemClock.elapsedRealtime()
            val unknown = contact(owner)
            assertFalse(unknown.getBoolean("enabled"))
            assertTrue("the fresh UNKNOWN starts its own bounded recheck", unknown.getBoolean("retryContact"))

            release.countDown()
            drain()
            val queuedOffElapsed = SystemClock.elapsedRealtime() - gapStarted
            assertTrue("old queued OFF must not stop the owner during the new UNKNOWN grace (${queuedOffElapsed}ms)", queuedOffElapsed < 1_250)
            assertEquals(0, owner.stops)
            assertTrue(WatchClient.connected())
            assertNull(reason())
            assertEquals(0, notice())

            step(1_249)
            assertEquals("fresh UNKNOWN still has its complete 1,250ms window", 0, owner.stops)
            assertTrue(WatchClient.connected())
            // Ensure no shadow read is left to make the recovery contact UNKNOWN.
            WatchUsbReadSequence.set()
            usb(1)
            assertTrue("owner recovery before its new deadline is accepted", contact(owner).getBoolean("enabled"))
            step(1)
            assertEquals("stale timer and stale OFF cannot stop after confirmed recovery", 0, owner.stops)
            assertTrue(WatchClient.connected())
            assertNull(reason())
            assertEquals(0, notice())
        } finally { release.countDown(); drain() }
    }

    @Test fun currentConfirmedOffStillStopsImmediately() {
        val owner = start()
        usb(0)
        val refused = contact(owner)
        assertFalse(refused.getBoolean("enabled"))
        assertFalse("confirmed OFF must not receive UNKNOWN grace", refused.getBoolean("retryContact"))
        WatchClient.stop(c, WatchNotification.Reason.USB_DEBUGGING_OFF)
        drain()
        assertEquals(1, owner.stops)
        assertFalse(WatchClient.connected())
        assertEquals("USB_DEBUGGING_OFF", reason())
        assertEquals(1, notice())
    }

    @Test fun offThatRemainsCurrentCannotBeOverriddenByOwnerOnWithoutFreshConfirmedOn() {
        val owner = start()
        val (_, release) = blockWorker()
        try {
            usb(0)
            c.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), null)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(observerSawOff())
            // A contact while the setting remains OFF is not recovery evidence.
            assertFalse(contact(owner).getBoolean("enabled"))
            release.countDown()
            drain()
            assertEquals(1, owner.stops)
            assertFalse(WatchClient.connected())
            assertEquals("USB_DEBUGGING_OFF", reason())
            assertEquals(1, notice())
        } finally { release.countDown(); drain() }
    }

    @Config(shadows=[WatchUsbReadSequence::class])
    @Test fun recoveredOwnerFreshPersistentUnknownStopsAtItsOwn1250msDeadline() {
        val owner = start()
        val (_, release) = blockWorker()
        try {
            usb(0)
            c.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), null)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("registered observer records OFF", observerSawOff())

            usb(1)
            assertTrue("same authenticated owner confirms ON", contact(owner).getBoolean("enabled"))
            WatchUsbReadSequence.set(*IntArray(32) { -1 })
            val unknown = contact(owner)
            assertFalse(unknown.getBoolean("enabled"))
            assertTrue("fresh UNKNOWN begins a new grace interval", unknown.getBoolean("retryContact"))
            val unknownAt = SystemClock.elapsedRealtime()

            release.countDown()
            drain()
            assertTrue("queued stale OFF cannot stop early", SystemClock.elapsedRealtime() - unknownAt < 1_250)
            assertEquals(0, owner.stops)
            assertTrue(WatchClient.connected())

            step(1_249)
            assertEquals("persistent UNKNOWN must not stop before the fresh deadline", 0, owner.stops)
            assertTrue(WatchClient.connected())
            step(1)
            assertEquals("persistent UNKNOWN stops when its own 1,250ms grace expires", 1, owner.stops)
            assertFalse(WatchClient.connected())
            assertEquals("USB_STATE_UNKNOWN", reason())
            assertEquals(1, notice())
        } finally { release.countDown(); drain(); WatchUsbReadSequence.set() }
    }

    @Config(shadows=[WatchUsbReadSequence::class])
    @Test fun unrelatedPeerCannotWithdrawOwnersQueuedOff() {
        val owner = start()
        val (_, release) = blockWorker()
        try {
            usb(0)
            c.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), null)
            shadowOf(Looper.getMainLooper()).idle()
            usb(1)
            assertFalse("a distinct Binder must not be accepted as the owner's recovery", contact(Peer("other")).getBoolean("enabled"))
            // No same-owner ON contact occurred after OFF. The old OFF remains authoritative;
            // simulate an UNKNOWN read when the queued action finally dequeues.
            WatchUsbReadSequence.set(*IntArray(32) { -1 })
            release.countDown()
            drain()
            assertEquals(1, owner.stops)
            assertFalse(WatchClient.connected())
            assertEquals("USB_STATE_UNKNOWN", reason())
            assertEquals(1, notice())
        } finally { release.countDown(); drain() }
    }

    @Config(shadows=[WatchUsbReadSequence::class])
    @Test fun confirmedOffAfterOwnerRecoveryWinsOverFreshUnknown() {
        val owner = start()
        val (_, release) = blockWorker()
        try {
            usb(0)
            c.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), null)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("registered observer records the first OFF", observerSawOff())

            usb(1)
            assertTrue("same owner confirms recovery", contact(owner).getBoolean("enabled"))
            WatchUsbReadSequence.set(*IntArray(32) { -1 })
            val unknown = contact(owner)
            assertFalse(unknown.getBoolean("enabled"))
            assertTrue(unknown.getBoolean("retryContact"))
            release.countDown()
            drain()
            assertEquals("old OFF is withdrawn after owner recovery", 0, owner.stops)
            assertTrue(WatchClient.connected())

            // Read sequence must be cleared before the explicit ON recovery contact.
            WatchUsbReadSequence.set()
            usb(1)
            assertTrue(contact(owner).getBoolean("enabled"))

            // A subsequent real OFF is a new authoritative stop, even after recovery.
            usb(0)
            val off = contact(owner)
            assertFalse(off.getBoolean("enabled"))
            assertFalse("confirmed OFF bypasses UNKNOWN grace", off.getBoolean("retryContact"))
            WatchClient.stop(c, WatchNotification.Reason.USB_DEBUGGING_OFF)
            drain()
            assertEquals(1, owner.stops)
            assertFalse(WatchClient.connected())
            assertEquals("USB_DEBUGGING_OFF", reason())
            assertEquals(1, notice())
        } finally { release.countDown(); drain(); WatchUsbReadSequence.set() }
    }
}
