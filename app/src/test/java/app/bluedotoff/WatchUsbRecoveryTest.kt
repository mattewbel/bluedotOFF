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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowBinder
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class WatchUsbRecoveryTest {
    private val c get() = ApplicationProvider.getApplicationContext<Application>()
    private class Peer : Binder() {
        var stopped = false
        init { attachInterface(null, WatchProtocol.DESCRIPTOR) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(WatchProtocol.DESCRIPTOR)
            data.readString()
            if (code == WatchProtocol.STOP) stopped = true
            reply!!.writeNoException()
            reply.writeString("{}")
            return true
        }
    }
    private val peer = Peer()
    private fun profile() = JSONObject().put("pid", 100).put("uid", 2000)
        .put("selinux", "u:r:shell:s0").put("abiVerified", true)
        .put("historyAbiVerified", true).put("indicatorAbiVerified", true)
        .put("fingerprint", Build.FINGERPRINT).put("oneUi", "90000").put("profileId", "fixture")
        .put("watchProtocol", 1).put("watchId", "active-peer")
        .put("appUpdatedAt", c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime)
    @Before fun connect() {
        c.applicationInfo.sourceDir = "/data/app/~~fixture/bluedot/base.apk"
        shadowOf(c).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Settings.Global.putInt(c.contentResolver, Settings.Global.ADB_ENABLED, 1)
        BackgroundWatch.set(c, true)
        assertTrue(WatchClient.attach(c, peer, profile(), c.applicationInfo.sourceDir, BuildConfig.VERSION_CODE.toLong()))
    }
    @After fun close() {
        ShadowBinder.setCallingUid(android.os.Process.myUid())
        BackgroundWatch.set(c, false)
        WatchClient.stopNow(c)
    }
    private fun contact(b: Binder = peer): Bundle {
        val provider = WatchBridgeProvider().also {
            it.attachInfo(c, ProviderInfo().apply { authority = WatchProtocol.AUTHORITY })
        }
        val previous = Binder.getCallingUid()
        try {
            ShadowBinder.setCallingUid(2000)
            return provider.call("state", null, Bundle().apply {
                putBinder("helper", b)
                putString("profile", profile().toString())
                putString("apk", c.applicationInfo.sourceDir)
                putLong("version", BuildConfig.VERSION_CODE.toLong())
            })
        } finally { ShadowBinder.setCallingUid(previous) }
    }
    private fun idleWorker() {
        val worker = WatchClient::class.java.getDeclaredField("worker").apply { isAccessible = true }
            .get(WatchClient) as ExecutorService
        worker.submit {}.get(5, TimeUnit.SECONDS)
    }
    private fun notice() = c.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 4201 }
    private fun unreadable() { Settings.Global.putInt(c.contentResolver, Settings.Global.ADB_ENABLED, -1) }
    private fun step(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        idleWorker()
    }

    // A new app process has a remembered helper but no live connection to it.
    private fun forgetConnection() {
        WatchClient.stopNow(c)
        c.getSharedPreferences("ui-state",0).edit().putBoolean("watchRunning",true).apply()
        assertFalse(WatchClient.connected())
    }

    @Config(shadows=[WatchUsbReadSequence::class])
    @Test fun coldRefusalKeepsTheUnreadableUsbSampleAfterRecoveryOnlyOnce() {
        forgetConnection()
        repeat(2) {
            WatchUsbReadSequence.set(-1,-1)
            val reply=contact()
            assertFalse(reply.getBoolean("enabled"))
            assertFalse(reply.getBoolean("draining"))
            assertFalse(reply.getBoolean("retryContact"))
            assertTrue(WatchClient.usbEnabled(c))
        }
        assertEquals(1,WatchDiagnostics.report(c).lines().count { it.endsWith(WatchDiagnostics.Event.UNOWNED_USB_UNKNOWN.label) })
    }

    @Config(shadows=[WatchUsbReadSequence::class])
    @Test fun coldConfirmedOffIsNotRelabelledIfUsbBecomesUnreadableBeforeTheReply() {
        forgetConnection()
        try {
            WatchUsbReadSequence.set(0,-1,-1)
            val reply=contact()
            assertFalse(reply.getBoolean("enabled"))
            assertFalse(reply.getBoolean("draining"))
            assertFalse(reply.getBoolean("retryContact"))
            assertFalse(WatchDiagnostics.report(c).contains(WatchDiagnostics.Event.UNOWNED_USB_UNKNOWN.label))
        } finally { WatchUsbReadSequence.set() }
    }

    @Config(shadows=[WatchUsbReadSequence::class])
    @Test fun establishedContactGetsItsRetryForTheSameBriefReadGap() {
        WatchUsbReadSequence.set(-1,-1)
        val reply=contact()
        assertFalse(reply.getBoolean("enabled"))
        assertTrue(reply.getBoolean("retryContact"))
        assertTrue(contact().getBoolean("enabled"))
        assertFalse(WatchDiagnostics.report(c).contains(WatchDiagnostics.Event.UNOWNED_USB_UNKNOWN.label))
    }

    @Test fun aTemporaryUnreadableContactDeniesWorkWithoutAskingTheActivePeerToExit() {
        unreadable()
        val response = contact()
        assertFalse(response.getBoolean("enabled"))
        assertFalse(response.getBoolean("draining"))
        assertTrue("unknown USB state must request a bounded contact retry", response.getBoolean("retryContact"))
        assertTrue(WatchClient.connected())
        assertFalse(WatchClient.available(c))
        assertFalse(WatchDiagnostics.report(c).contains(WatchDiagnostics.Event.ACTIVE_REFUSED.label))
    }
    @Test fun aSecondReadGapAfterConfirmedRecoveryGetsItsOwnFullGraceWindow() {
        unreadable()
        assertTrue(contact().getBoolean("retryContact")) // first gap begins at t=0
        step(500)
        Settings.Global.putInt(c.contentResolver, Settings.Global.ADB_ENABLED, 1)
        assertTrue("the same authenticated owner must recover on confirmed ON", contact().getBoolean("enabled"))

        step(500)
        unreadable()
        assertTrue("a later gap for the same owner gets a fresh retry", contact().getBoolean("retryContact"))
        step(250) // first gap's old deadline; only 250ms into the second gap
        assertFalse("the old timer must not send a stale STOP", peer.stopped)
        assertTrue(WatchClient.connected())
        assertFalse(notice())
        assertNull(c.getSharedPreferences("ui-state", 0).getString("watchStopNotified", null))
        step(999)
        assertFalse("the second gap must retain its full 1,250ms grace", peer.stopped)
        step(1)
        assertTrue("persistent second gap stops at its own deadline", peer.stopped)
        assertTrue(notice())
        assertEquals("USB_STATE_UNKNOWN", c.getSharedPreferences("ui-state", 0).getString("watchStopNotified", null))
    }

    @Test fun anObserverReadGapRecoversWithoutStoppingTheKnownPeer() {
        WatchClient.initialize(c)
        unreadable()
        c.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), null)
        shadowOf(Looper.getMainLooper()).idle()
        idleWorker()
        assertFalse("the first unreadable sample must not send STOP", peer.stopped)
        assertFalse(notice())
        assertFalse(WatchClient.available(c))
        Settings.Global.putInt(c.contentResolver, Settings.Global.ADB_ENABLED, 1)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_250))
        idleWorker()
        assertTrue(WatchClient.available(c))
        assertFalse(peer.stopped)
        assertFalse(notice())
    }
    @Test fun actualOffStillRefusesTheActivePeerImmediately() {
        Settings.Global.putInt(c.contentResolver, Settings.Global.ADB_ENABLED, 0)
        val response = contact()
        assertFalse(response.getBoolean("enabled"))
        assertFalse(response.getBoolean("retryContact"))
        assertTrue(WatchDiagnostics.report(c).contains(WatchDiagnostics.Event.ACTIVE_REFUSED.label))
    }
    @Test fun aPersistentReadFailureStopsOnceAtTheOriginalDeadline() {
        unreadable()
        assertTrue(contact().getBoolean("retryContact"))
        step(800)
        assertTrue(contact().getBoolean("retryContact"))
        step(449)
        assertFalse(peer.stopped)
        assertFalse(notice())
        step(1)
        assertTrue(peer.stopped)
        assertFalse(WatchClient.connected())
        assertEquals("USB_STATE_UNKNOWN", c.getSharedPreferences("ui-state", 0).getString("watchStopNotified", null))
        assertTrue(notice())
        assertFalse(contact().getBoolean("retryContact"))
    }
    @Test fun aDifferentPeerCannotUseTheGracePeriod() {
        unreadable()
        val refused = contact(Peer())
        assertFalse(refused.getBoolean("enabled"))
        assertFalse(refused.getBoolean("retryContact"))
    }
    @Test fun switchingOffCancelsGraceAndRefusesTheOldContact() {
        unreadable()
        assertTrue(contact().getBoolean("retryContact"))
        BackgroundWatch.set(c, false)
        WatchClient.stop(c)
        idleWorker()
        assertTrue(peer.stopped)
        assertFalse(contact().getBoolean("retryContact"))
        step(1_250)
        assertFalse(WatchClient.connected())
        assertFalse(notice())
    }
    @Test fun aRealDeathDuringTheReadGapStillNotifiesImmediately() {
        unreadable()
        assertTrue(contact().getBoolean("retryContact"))
        Settings.Global.putInt(c.contentResolver, Settings.Global.ADB_ENABLED, 1)
        WatchClient::class.java.getDeclaredMethod("helperDied", android.content.Context::class.java, IBinder::class.java)
            .apply { isAccessible = true }.invoke(WatchClient, c, peer)
        assertTrue(notice())
        assertFalse(WatchClient.connected())
        assertEquals("STOPPED", c.getSharedPreferences("ui-state", 0).getString("watchStopNotified", null))
        step(1_250)
        assertTrue(notice())
    }
    @Test fun aRealOffDuringTheReadGapDoesNotWaitForTheTimer() {
        unreadable()
        assertTrue(contact().getBoolean("retryContact"))
        Settings.Global.putInt(c.contentResolver, Settings.Global.ADB_ENABLED, 0)
        val refused = contact()
        assertFalse(refused.getBoolean("retryContact"))
        WatchClient.stop(c, WatchNotification.Reason.USB_DEBUGGING_OFF)
        idleWorker()
        assertTrue(peer.stopped)
        assertEquals("USB_DEBUGGING_OFF", c.getSharedPreferences("ui-state", 0).getString("watchStopNotified", null))
        assertTrue(notice())
    }
    @Test fun aRecoveredContactAlsoResumesAlreadyDeferredWork() {
        unreadable()
        assertTrue(contact().getBoolean("retryContact"))
        WatchClient::class.java.getDeclaredMethod("availableForQueued", android.content.Context::class.java, Set::class.java)
            .apply { isAccessible = true }.invoke(WatchClient, c, setOf("test.deferred"))
        Settings.Global.putInt(c.contentResolver, Settings.Global.ADB_ENABLED, 1)
        assertTrue(contact().getBoolean("enabled"))
        step(1_250)
        @Suppress("UNCHECKED_CAST")
        val deferred = WatchClient::class.java.getDeclaredField("deferred").apply { isAccessible = true }
            .get(WatchClient) as Set<String>
        assertTrue(deferred.isEmpty())
        assertTrue(WatchClient.available(c))
        assertFalse(peer.stopped)
        assertFalse(notice())
    }
    @Test fun recoveringDuringTheContactMustNotTurnAReadFailureIntoAnExit() {
        val diagnostics = c.getSharedPreferences("watch-diagnostics", 0)
        var recoveries = 0
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            if (recoveries++ == 0) Settings.Global.putInt(c.contentResolver, Settings.Global.ADB_ENABLED, 1)
        }
        diagnostics.registerOnSharedPreferenceChangeListener(listener)
        try {
            unreadable()
            val response = contact()
            assertTrue("the test recovers during the rejected contact", recoveries > 0)
            assertEquals(1, Settings.Global.getInt(c.contentResolver, Settings.Global.ADB_ENABLED))
            assertFalse(response.getBoolean("enabled"))
            assertTrue(response.getBoolean("retryContact"))
            assertFalse(WatchDiagnostics.report(c).contains(WatchDiagnostics.Event.ACTIVE_REFUSED.label))
            step(1_250)
            assertTrue(WatchClient.available(c))
            assertFalse(peer.stopped)
        } finally { diagnostics.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    @Test fun aQueueReadGapAloneSchedulesRecoveryWithoutAnotherSignal() {
        unreadable()
        WatchClient::class.java.getDeclaredMethod("availableForQueued", android.content.Context::class.java, Set::class.java)
            .apply { isAccessible = true }.invoke(WatchClient, c, setOf("test.deferred"))
        Settings.Global.putInt(c.contentResolver, Settings.Global.ADB_ENABLED, 1)
        step(1_250)
        @Suppress("UNCHECKED_CAST")
        val deferred = WatchClient::class.java.getDeclaredField("deferred").apply { isAccessible = true }
            .get(WatchClient) as Set<String>
        assertTrue("a read gap must not leave work waiting for an unrelated signal", deferred.isEmpty())
        assertTrue(WatchClient.available(c))
        assertFalse(peer.stopped)
    }
    @Test fun anUnownedUnknownContactIsRefusedWithADistinguishableDiagnostic() {
        WatchClient::class.java.getDeclaredField("current").apply { isAccessible = true }.set(WatchClient, null)
        unreadable()
        val response = contact()
        assertFalse(response.getBoolean("enabled"))
        assertFalse(response.getBoolean("retryContact"))
        assertTrue(WatchDiagnostics.report(c).contains("Unowned helper contact refused while USB debugging state was unavailable"))
    }
    @Test fun theOldReadTimerCannotStopAReplacementConnection() {
        unreadable()
        assertTrue(contact().getBoolean("retryContact"))
        WatchClient.stopNow(c)
        Settings.Global.putInt(c.contentResolver, Settings.Global.ADB_ENABLED, 1)
        val replacement = Peer()
        assertTrue(WatchClient.attach(c, replacement, profile(), c.applicationInfo.sourceDir, BuildConfig.VERSION_CODE.toLong()))
        step(1_250)
        assertTrue(WatchClient.available(c))
        assertFalse(replacement.stopped)
        assertFalse(notice())
    }
    private fun markRecheckExpired() {
        val connection = WatchClient::class.java.getDeclaredField("current").apply { isAccessible = true }.get(WatchClient)
        connection.javaClass.getDeclaredField("usbRecheckExpired").apply { isAccessible = true }.setBoolean(connection, true)
    }
    @Test fun anExpiredReadGapCannotLeaveQueuedWorkSilentlyWaiting() {
        markRecheckExpired()
        unreadable()
        WatchClient::class.java.getDeclaredMethod("availableForQueued", android.content.Context::class.java, Set::class.java)
            .apply { isAccessible = true }.invoke(WatchClient, c, setOf("test.deferred"))
        idleWorker()
        assertTrue(peer.stopped)
        assertFalse(WatchClient.connected())
        assertTrue(notice())
    }
    @Test fun anExpiredStopWithdrawnOnConfirmedOnResumesDeferredWork() {
        markRecheckExpired()
        LocalAdbSession.exclusive {
            unreadable()
            WatchClient::class.java.getDeclaredMethod("availableForQueued", android.content.Context::class.java, Set::class.java)
                .apply { isAccessible = true }.invoke(WatchClient, c, setOf("test.deferred"))
            Settings.Global.putInt(c.contentResolver, Settings.Global.ADB_ENABLED, 1)
        }
        idleWorker()
        @Suppress("UNCHECKED_CAST")
        val deferred = WatchClient::class.java.getDeclaredField("deferred").apply { isAccessible = true }.get(WatchClient) as Set<String>
        assertTrue(deferred.isEmpty())
        assertTrue(WatchClient.available(c))
        assertFalse(peer.stopped)
        assertFalse(notice())
    }
}
