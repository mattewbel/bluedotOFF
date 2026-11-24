package app.bluedotoff

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Build
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.provider.Settings
import app.bluedotoff.adb.WatchClient
import app.bluedotoff.adb.WatchProtocol
import app.bluedotoff.adb.WirelessControls
import app.bluedotoff.adb.WirelessWork
import app.bluedotoff.core.ApplicationScope
import kotlinx.coroutines.Job
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real Home lifecycle and real LOCAL journal refresh overlap. Binder contacts and wireless discovery
 * are fixtures: there is no actual ADB endpoint, target app or permission setter in these tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class WatchHomeResumeRecovery73Test {
    private val app get() = RuntimeEnvironment.getApplication()
    private val originalSdk = Build.VERSION.SDK_INT
    private val originalMaker = Build.MANUFACTURER
    private val originalAbis = Build.SUPPORTED_ABIS
    private val key get() = File(app.noBackupFilesDir, "local-adb-identity.enc")
    private val lastStart = WatchClient::class.java.getDeclaredField("lastStart").apply { isAccessible = true }

    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun model(activity: MainActivity) = MainActivity::class.java.getDeclaredMethod("getModel")
        .apply { isAccessible = true }.invoke(activity) as UiModel
    private fun refresh(activity: MainActivity) = field("refreshJob").get(activity) as? Job
    private fun attempts() = WatchDiagnostics.report(app).lines().count { it.startsWith("Start attempt:") }
    private fun step(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun pumpUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(24)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("$message\n${WatchDiagnostics.report(app)}", condition())
    }
    private fun settle(activity: MainActivity) {
        shadowOf(Looper.getMainLooper()).idle()
        pumpUntil("Home's real refresh and history read must complete") {
            refresh(activity)?.isActive != true &&
                (field("reapplyHistoryJob").get(activity) as? Job)?.isActive != true
        }
    }
    private fun assertLifecycle(started: Int, resumed: Int) {
        fun count(name: String) = (ForegroundState::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(ForegroundState) as AtomicInteger).get()
        assertEquals("started Activity count", started, count("activities"))
        assertEquals("resumed Activity count", resumed, count("focused"))
    }

    private class Peer : Binder() {
        val requests = AtomicInteger()
        init { attachInterface(null, WatchProtocol.DESCRIPTOR) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(WatchProtocol.DESCRIPTOR)
            data.readString()
            requests.incrementAndGet()
            reply!!.writeNoException()
            reply.writeString("{}")
            return true
        }
    }
    private fun attach(peer: Peer, id: String) {
        val profile = JSONObject().put("pid", 100).put("uid", 2000).put("selinux", "u:r:shell:s0")
            .put("abiVerified", true).put("fingerprint", Build.FINGERPRINT).put("oneUi", "90000")
            .put("profileId", "fixture").put("watchProtocol", 1).put("watchId", id)
            .put("appUpdatedAt", app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime)
        assertTrue(WatchClient.attach(app, peer, profile, app.applicationInfo.sourceDir, BuildConfig.VERSION_CODE.toLong()))
    }
    private fun loseOwner(peer: Peer) {
        val owner = WatchClient::class.java.getDeclaredField("current").apply { isAccessible = true }
            .get(WatchClient) as WatchClient.Connection
        assertSame(peer, owner.binder)
        owner.death.binderDied()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(WatchClient.available(app))
        assertEquals("STOPPED", app.getSharedPreferences("ui-state", 0).getString("watchStopNotified", null))
    }

    /** Own the actual journal read lock, so the production lifecycle listener creates a LOCAL refresh
     * which onResume must encounter. No synthetic busy model or replacement coroutine is involved. */
    private class HeldJournal : AutoCloseable {
        private val release = CountDownLatch(1)
        private val entered = CountDownLatch(1)
        private val thread: Thread
        init {
            val lock = checkNotNull(JournalStore::class.java.getDeclaredField("lock")
                .apply { isAccessible = true }.get(null))
            thread = Thread {
                synchronized(lock) {
                    entered.countDown()
                    check(release.await(30, TimeUnit.SECONDS))
                }
            }.also { it.start() }
            assertTrue("fixture must own JournalStore's lock", entered.await(3, TimeUnit.SECONDS))
        }
        override fun close() {
            release.countDown()
            thread.join(3_000)
            assertFalse("journal lock holder must exit", thread.isAlive)
        }
    }
    private fun overlap(activity: MainActivity): HeldJournal = HeldJournal().also {
        checkNotNull(LifecycleEvents.listener).invoke()
        assertTrue("production listener must start a real LOCAL refresh", refresh(activity)?.isActive == true)
        assertFalse("refresh overlap is independent of model.busy", model(activity).busy)
    }

    private fun setup() {
        app.getSharedPreferences("ui-state", 0).edit().putBoolean("welcomed", true)
            .putBoolean("autoReapply", false).commit()
        app.getSharedPreferences("local-adb", 0).edit().putString("pairedGuid", "fixture").commit()
        key.writeText("fixture existence marker; never used for a connection")
        ScopePreferences(app).select(ApplicationScope.ALL)
        WirelessControls(app).setConsent(false)
        Settings.Global.putInt(app.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
        Settings.Global.putInt(app.contentResolver, Settings.Global.ADB_ENABLED, 1)
        Settings.Global.putInt(app.contentResolver, "adb_wifi_enabled", 1)
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val capabilities = NetworkCapabilities()
        NetworkCapabilities::class.java.getMethod("addTransportType", Int::class.javaPrimitiveType)
            .invoke(capabilities, NetworkCapabilities.TRANSPORT_WIFI)
        NetworkCapabilities::class.java.getMethod("addCapability", Int::class.javaPrimitiveType)
            .invoke(capabilities, NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, capabilities)
        shadowOf(manager).setLinkProperties(manager.activeNetwork, LinkProperties().apply { interfaceName = "wlan0" })
        shadowOf(app).removeSystemService(Context.NSD_SERVICE)
        BackgroundWatch.set(app, true)
        // Robolectric replaces the resolver between cases; re-establish this simulated process's USB observer.
        WatchClient::class.java.getDeclaredField("initialized").apply { isAccessible=true }.setBoolean(WatchClient,false)
        WatchClient.initialize(app)
        lastStart.setLong(WatchClient, 0)
        step(2_000)
    }

    private inline fun home(block: (ActivityController<MainActivity>, MainActivity, Peer) -> Unit) {
        setup()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val activity = controller.get()
        try {
            settle(activity) // Create the Android 14 framework before changing only support-gate inputs.
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 37)
            ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "samsung")
            ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
            MainActivity::class.java.getDeclaredMethod("setModel", UiModel::class.java)
                .apply { isAccessible = true }.invoke(activity,
                    UiModel(gate = Gate.READY, scopeResolved = true, paired = true, backgroundWatch = true))
            (field("clock").get(activity) as RecheckClock).fullChecked(SystemClock.elapsedRealtime())
            val first = Peer()
            attach(first, "home73-initial")
            settle(activity)
            assertLifecycle(1, 1)
            assertTrue(DeviceSupport.basic())
            assertTrue(WifiConnection.connected(app))
            assertTrue(WatchClient.available(app))
            assertEquals(0, attempts())
            assertTrue(JournalStore(app).entries().isEmpty())
            block(controller, activity, first)
            assertTrue("no target records or write work was introduced", JournalStore(app).entries().isEmpty())
            assertEquals("manual wireless remains ON", 1,
                Settings.Global.getInt(app.contentResolver, "adb_wifi_enabled"))
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", originalSdk)
            controller.pause().stop().destroy()
        }
    }
    private fun returnAfterStop(controller: ActivityController<MainActivity>, peer: Peer) {
        controller.pause().stop()
        assertLifecycle(0, 0)
        assertFalse(ForegroundState.active)
        loseOwner(peer)
        // Robolectric restart() includes onStart; start() here would count it twice.
        controller.restart()
        assertLifecycle(1, 0)
    }
    private fun expectOneAttempt(activity: MainActivity) {
        pumpUntil("eligible foreground Home must make exactly one watch startup attempt") { attempts() == 1 }
        settle(activity)
        pumpUntil("fixture startup must complete at discovery") {
            !model(activity).watchStarting && WatchDiagnostics.report(app).contains("app: ADB_DISCOVERY_FAILED")
        }
        step(60_000)
        settle(activity)
        assertEquals("one Home visit must not become a repeated startup loop", 1, attempts())
    }

    @After fun cleanup() {
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", originalSdk)
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", originalMaker)
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", originalAbis)
        BackgroundWatch.set(app, false)
        WatchClient.stopNow(app)
        lastStart.setLong(WatchClient, 0)
        key.delete()
        while (ForegroundState.active) ForegroundState.stopped()
        ForegroundState.paused()
        LifecycleEvents.listener = null
    }

    @Test fun aFullyStoppedIdleHomeReturnStillStartsTheWatch() = home { controller, activity, first ->
        returnAfterStop(controller, first)
        controller.resume()
        expectOneAttempt(activity)
    }

    @Test fun aFullyStoppedHomeReturnWaitsForTheRealLocalRefreshThenStartsOnce() = home { controller, activity, first ->
        returnAfterStop(controller, first)
        overlap(activity).use {
            val running = refresh(activity)
            controller.resume()
            assertSame("Home returned during the existing refresh", running, refresh(activity))
            assertEquals(0, attempts())
        }
        expectOneAttempt(activity)
    }

    @Test fun leavingBeforeTheOverlappingRefreshCompletesCancelsTheVisit() = home { controller, activity, first ->
        returnAfterStop(controller, first)
        overlap(activity).use {
            controller.resume()
            controller.pause().stop()
            assertLifecycle(0, 0)
        }
        settle(activity)
        step(60_000)
        settle(activity)
        assertEquals("a background Activity must not repair an expired Home visit", 0, attempts())
    }

    @Test fun disablingWatchBeforeTheOverlappingRefreshCompletesCancelsTheVisit() = home { controller, activity, first ->
        returnAfterStop(controller, first)
        overlap(activity).use {
            controller.resume()
            BackgroundWatch.set(app, false)
        }
        settle(activity)
        step(60_000)
        settle(activity)
        assertEquals("a disabled watch must not be restarted by refresh completion", 0, attempts())
    }

    @Test fun aRecentStartWaitsForThirtySecondsThenRepairsThisHomeVisitOnlyOnce() = home { controller, activity, first ->
        returnAfterStop(controller, first)
        // Models a real recent failed discovery. Seeding its timestamp avoids an unrelated network wait.
        val startedAt = SystemClock.elapsedRealtime()
        lastStart.setLong(WatchClient, startedAt)
        controller.resume()
        settle(activity)
        val remaining = 30_000L - (SystemClock.elapsedRealtime() - startedAt)
        assertTrue("fixture must still be inside the start cooldown", remaining > 1)
        step(remaining - 1)
        settle(activity)
        assertEquals("deferred Home repair must honor the complete 30-second cooldown", 0, attempts())
        step(1)
        expectOneAttempt(activity)
    }

    @Test fun leavingHomeDuringTheStartCooldownCancelsItsDeferredRepair() = home { controller, activity, first ->
        returnAfterStop(controller, first)
        lastStart.setLong(WatchClient, SystemClock.elapsedRealtime())
        controller.resume()
        settle(activity)
        assertEquals(0, attempts())
        controller.pause().stop()
        step(60_000)
        settle(activity)
        assertEquals("a delayed repair belongs to the resumed Home visit", 0, attempts())
    }

    @Test fun anAlreadyRecoveredOwnerMakesAnotherHomeStartUnnecessary() = home { controller, activity, first ->
        returnAfterStop(controller, first)
        val recovered = Peer()
        attach(recovered, "home73-recovered-before-resume")
        settle(activity)
        overlap(activity).use {
            controller.resume()
        }
        settle(activity)
        step(60_000)
        settle(activity)
        assertTrue(WatchClient.available(app))
        assertTrue(model(activity).watchRunning)
        assertEquals("the fresh contact satisfies the pending Home visit", 0, attempts())
        assertEquals("an empty LOCAL refresh must not send target RPCs", 0, recovered.requests.get())
        assertNull(app.getSharedPreferences("ui-state", 0).getString("watchStopNotified", null))
    }

    /** Keep the real startup queue busy until the first visit is canceled and a second visit is open. */
    private fun pendingOldStart(controller: ActivityController<MainActivity>, activity: MainActivity, first: Peer) {
        returnAfterStop(controller, first)
        val wirelessLock = checkNotNull(WirelessWork::class.java.getDeclaredField("lock")
            .apply { isAccessible = true }.get(null))
        synchronized(wirelessLock) {
            overlap(activity).use { controller.resume() }
            pumpUntil("the old visit's actual startup reaches the serialized wireless queue") {
                attempts() == 1 && model(activity).watchStarting
            }
            assertTrue(WatchClient.usbEnabled(app))
            assertTrue(WifiConnection.connected(app))
            controller.pause().stop()
            controller.restart().resume()
            settle(activity)
            assertLifecycle(1, 1)
            assertEquals("the new visit waits for the old in-flight startup", 1, attempts())
        }
    }

    @Test fun aNewHomeVisitWaitsForTheOldInFlightStartThenStartsExactlyOnce() = home { controller, activity, first ->
        pendingOldStart(controller, activity, first)
        pumpUntil("the old visit is withdrawn and the new one reaches discovery") {
            attempts() == 2 && !model(activity).watchStarting &&
                WatchDiagnostics.report(app).contains("app: ADB_DISCOVERY_FAILED")
        }
        step(180_000)
        settle(activity)
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_500)
        while (System.nanoTime() < until) { step(0); Thread.sleep(10) }
        assertNull("startup must finish after discovery failure",
            WatchClient::class.java.getDeclaredField("activeStart").apply { isAccessible = true }.get(WatchClient))
        assertEquals("ordinary discovery failure does not loop on an open Home", 2, attempts())
    }

    @Test fun leavingTheNewVisitOrConfirmingUsbOffCancelsTheOldStartHandoff() = home { controller, activity, first ->
        pendingOldStart(controller, activity, first)
        controller.pause().stop()
        assertLifecycle(0, 0)
        step(180_000)
        settle(activity)
        assertEquals("leaving cancels the queued handoff", 1, attempts())

        // Reopen while the old startup has finished; explicit OFF must also prevent recovery.
        Settings.Global.putInt(app.contentResolver, Settings.Global.ADB_ENABLED, 0)
        app.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), null)
        controller.restart().resume()
        step(0)
        settle(activity)
        step(180_000)
        settle(activity)
        assertEquals("confirmed USB OFF never starts a new helper", 1, attempts())
    }

    @Test fun unknownUsbThenConfirmedOnRecoversOnceWhileTheSameHomeVisitStaysOpen() = home { controller, activity, first ->
        returnAfterStop(controller, first)
        Settings.Global.putInt(app.contentResolver, Settings.Global.ADB_ENABLED, -1)
        controller.resume()
        settle(activity)
        pumpUntil("resume must observe the unreadable USB setting") {
            WatchDiagnostics.report(app).contains(" | USB_UNREADABLE")
        }
        assertEquals(0, attempts())
        Settings.Global.putInt(app.contentResolver, Settings.Global.ADB_ENABLED, 1)
        app.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), null)
        pumpUntil("the still-open visit retries once after confirmed USB ON") { attempts() == 1 }
        pumpUntil("the recovery attempt completes at ordinary discovery") {
            !model(activity).watchStarting && WatchDiagnostics.report(app).contains("app: ADB_DISCOVERY_FAILED")
        }
        step(60_000)
        settle(activity)
        assertEquals("USB readability recovery is one-shot", 1, attempts())
    }
}
