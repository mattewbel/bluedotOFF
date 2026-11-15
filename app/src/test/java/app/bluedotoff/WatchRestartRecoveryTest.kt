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

/** One cold-process scenario per class: WatchClient's initialized/death state is process-wide. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class)
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
class WatchRestartRecoveryTest {
    private val c get() = ApplicationProvider.getApplicationContext<Application>().also {
        it.applicationInfo.sourceDir = "/data/app/~~fixture/bluedot/base.apk"
    }

    private class Server : Binder() {
        var calls = 0
        init { attachInterface(null, WatchProtocol.DESCRIPTOR) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(WatchProtocol.DESCRIPTOR)
            data.readString()
            calls++
            reply!!.writeNoException()
            reply.writeString(if (code == WatchProtocol.REQUEST) JSONObject().put("pid", 100).toString()
                else JSONObject().put("configured", true).toString())
            return true
        }
    }

    private fun profile() = JSONObject().put("pid", 100).put("uid", 2000).put("selinux", "u:r:shell:s0")
        .put("abiVerified", true).put("historyAbiVerified", true).put("indicatorAbiVerified", true)
        .put("fingerprint", Build.FINGERPRINT).put("oneUi", "90000").put("profileId", "fixture")
        .put("watchProtocol", 1).put("watchId", "fixture-id")
        .put("appUpdatedAt", c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime)

    private fun notice() = c.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 4201 }
    private fun step(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))

    @After fun clean() {
        LifecycleEvents.widget = null
        LifecycleEvents.listener = null
        BackgroundWatch.set(c, false)
        WatchClient.stopNow(c)
    }

    @Test fun coldStartWaitsForSameHelperThenOnlyRealDeathNotifiesImmediately() {
        shadowOf(c).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Settings.Global.putInt(c.contentResolver, Settings.Global.ADB_ENABLED, 1)
        BackgroundWatch.set(c, true)
        BackgroundWatch.running(c)
        WatchClient.initialize(c)
        step(0)

        // A transient provider startup failure during the 75-second recovery window is not a stop.
        val provider = WatchBridgeProvider().also {
            it.attachInfo(c, android.content.pm.ProviderInfo().apply { authority = WatchProtocol.AUTHORITY })
        }
        val failed = Bundle().apply {
            putBinder("helper", Server())
            putString("apk", c.applicationInfo.sourceDir)
            putLong("version", BuildConfig.VERSION_CODE.toLong())
            putString("profile", JSONObject().put("startupPhase", "PROCESS_CALLBACK")
                .put("startupError", "IllegalStateException").toString())
        }
        val caller = Binder.getCallingUid()
        try {
            org.robolectric.shadows.ShadowBinder.setCallingUid(2000)
            provider.call("failed", null, failed)
        } finally { org.robolectric.shadows.ShadowBinder.setCallingUid(caller) }
        assertTrue(c.getSharedPreferences("ui-state", 0).getBoolean("watchRunning", false))
        assertFalse(notice())
        step(74_999)
        assertFalse("the cold-start grace period must not show 4201", notice())
        step(1)
        assertFalse("one missed report is silent",notice())
        step(75_000)
        assertTrue("prolonged silence gets a contact advisory", notice())
        assertEquals("CONTACT_UNCONFIRMED", c.getSharedPreferences("ui-state", 0).getString("watchStopNotified", null))

        // A helper heartbeat arriving after the wait recovers stale stopped state and its notice.
        val helper = Server()
        assertTrue(WatchClient.attach(c, helper, profile(), c.applicationInfo.sourceDir, BuildConfig.VERSION_CODE.toLong()))
        WatchClient.reportState(c)
        assertTrue(WatchClient.available(c))
        assertTrue(c.getSharedPreferences("ui-state", 0).getBoolean("watchRunning", false))
        assertFalse("a live helper heartbeat must clear stale 4201", notice())

        // Even if a stale notice already exists, a report from the exact same owner repairs it.
        WatchNotification.stopped(c, WatchNotification.Reason.STOPPED)
        assertTrue(notice())
        assertTrue(WatchClient.attach(c, helper, profile(), c.applicationInfo.sourceDir, BuildConfig.VERSION_CODE.toLong()))
        assertFalse(notice())
        assertTrue(c.getSharedPreferences("ui-state", 0).getBoolean("watchRunning", false))

        // The current-version helper may have attached before the replacement broadcast arrives.
        LifecycleReceiver().onReceive(c, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertTrue("MY_PACKAGE_REPLACED must not mark a live helper stopped", WatchClient.available(c))
        assertTrue(c.getSharedPreferences("ui-state", 0).getBoolean("watchRunning", false))
        assertFalse("package replacement alone must not show 4201", notice())

        // Contrasts the recovered false alarm: actual Binder death is authoritative and immediate.
        val connection = WatchClient::class.java.getDeclaredField("current").apply { isAccessible = true }
            .get(WatchClient) as WatchClient.Connection
        connection.death.binderDied()
        assertFalse(WatchClient.available(c))
        assertFalse(c.getSharedPreferences("ui-state", 0).getBoolean("watchRunning", true))
        assertTrue("actual Binder death must notify without waiting for the cold-start grace", notice())
    }
}
