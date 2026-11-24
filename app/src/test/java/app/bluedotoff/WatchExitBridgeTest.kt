package app.bluedotoff

import android.app.Application
import android.content.Context
import android.content.pm.ProviderInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.adb.WatchBridgeProvider
import app.bluedotoff.adb.WatchClient
import app.bluedotoff.adb.WatchExitReason
import app.bluedotoff.adb.WatchExitReport
import app.bluedotoff.adb.WatchProtocol
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WatchExitBridgeTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>().also {
        it.applicationInfo.sourceDir = "/data/app/~~fixture/bluedot/base.apk"
    }

    private val attempt = "11111111-1111-4111-8111-111111111111"
    private val previous = "22222222-2222-4222-8222-222222222222"
    private val current = "33333333-3333-4333-8333-333333333333"

    private class Peer : Binder() {
        init { attachInterface(null, WatchProtocol.DESCRIPTOR) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(WatchProtocol.DESCRIPTOR)
            data.readString()
            reply!!.writeNoException()
            reply.writeString(JSONObject().put("configured", true).toString())
            return true
        }
    }

    @Before fun prepare() {
        context.getSharedPreferences("watch-evidence", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("watch-diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
        Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1)
        BackgroundWatch.set(context, true)
    }

    @After fun clean() {
        BackgroundWatch.set(context, false)
        WatchClient.stopNow(context)
    }

    private fun profile(watchId: String, startAttempt: String = attempt) = JSONObject()
        .put("pid", 100).put("uid", 2000).put("selinux", "u:r:shell:s0")
        .put("abiVerified", true).put("historyAbiVerified", true).put("indicatorAbiVerified", true)
        .put("fingerprint", Build.FINGERPRINT).put("oneUi", "90000").put("profileId", "fixture")
        .put("watchProtocol", 1).put("watchId", watchId).put("startAttempt", startAttempt)
        .put("appUpdatedAt", context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime)

    private fun provider() = WatchBridgeProvider().also {
        it.attachInfo(context, ProviderInfo().apply { authority = WatchProtocol.AUTHORITY })
    }

    private fun asShell(block: () -> Unit) {
        val caller = Binder.getCallingUid()
        try {
            ShadowBinder.setCallingUid(2000)
            block()
        } finally {
            ShadowBinder.setCallingUid(caller)
        }
    }

    private fun failedBundle(id: String, stage: String = "PACKAGE_CALLBACK", error: String = "SecurityException") = Bundle().apply {
        putBinder("helper", Peer())
        putString("apk", context.applicationInfo.sourceDir)
        putLong("version", BuildConfig.VERSION_CODE.toLong())
        putString("profile", JSONObject().put("startAttempt", id)
            .put("startupPhase", stage).put("startupError", error).toString())
    }

    private fun attach(peer: Peer, watchId: String, id: String = attempt): Boolean = WatchClient.attach(
        context, peer, profile(watchId, id), context.applicationInfo.sourceDir, BuildConfig.VERSION_CODE.toLong()
    )

    private fun die(peer: IBinder) {
        WatchClient::class.java.getDeclaredMethod("helperDied", Context::class.java, IBinder::class.java)
            .apply { isAccessible = true }.invoke(WatchClient, context, peer)
    }

    @Test fun providerRequiresShellCallerAndMalformedFailureCannotAddEvidence() {
        val p = provider()
        try {
            p.call("failed", null, failedBundle(attempt))
            throw AssertionError("ordinary app caller was accepted")
        } catch (_: IllegalStateException) { }
        assertTrue(WatchEvidence.report(context).isBlank())

        asShell {
            try {
                p.call("failed", null, failedBundle("bad-id", "NOT_A_STAGE"))
                throw AssertionError("invalid startup stage was accepted")
            } catch (_: IllegalArgumentException) { }
        }
        val report = WatchEvidence.report(context)
        assertTrue(report.isBlank())
        assertFalse(report.contains("bad-id"))
    }

    @Test fun authenticatedHelperExitReasonAndItsBinderDeathMergeUnderTheSameOwner() {
        val peer = Peer()
        val p = provider()
        lateinit var receiver: IBinder
        asShell {
            val extras = failedBundle(attempt).apply {
                putBinder("helper", peer)
                putString("profile", profile(previous).toString())
            }
            val reply = p.call("attach", null, extras)
            assertTrue(reply.getBoolean("enabled"))
            receiver = requireNotNull(reply.getBinder(WatchExitReport.EXTRA))
        }
        WatchExitReport.send(receiver, WatchExitReason.INSTALL_CHECK_FAILED)
        assertFalse(WatchEvidence.report(context).contains("INSTALL_CHECK_FAILED"))
        asShell { WatchExitReport.send(receiver, WatchExitReason.INSTALL_LOOKUP_UNAVAILABLE) }
        die(peer)

        val exits = WatchEvidence.report(context).lines().filter { it.startsWith("Helper exit:") }
        assertEquals(1, exits.size)
        assertTrue(exits.single().contains("active Binder died"))
        assertTrue(exits.single().contains("INSTALL_LOOKUP_UNAVAILABLE"))
        assertFalse(exits.single().contains(previous))
    }

    @Test fun oldHelperReceiverCannotSupplyReasonForNewHelpersDeath() {
        val old = Peer()
        assertTrue(attach(old, previous))
        val staleReceiver = WatchClient.exitReceiver(context, old)!!
        die(old)

        val newer = Peer()
        assertTrue(attach(newer, current, attempt))
        asShell { WatchExitReport.send(staleReceiver, WatchExitReason.INSTALL_LOOKUP_UNAVAILABLE) }
        die(newer)

        val exits = WatchEvidence.report(context).lines().filter { it.startsWith("Helper exit:") }
        assertEquals(2, exits.size)
        assertTrue(exits.any { it.contains("INSTALL_LOOKUP_UNAVAILABLE") && it.contains("active Binder died") })
        assertTrue(exits.any { it.contains("active Binder died") && it.contains("no helper exit reason received") })
    }

    @Test fun duplicateProviderStartupFailuresMergeAndDoNotStopAnExistingLiveOwner() {
        val active = Peer()
        assertTrue(attach(active, previous))
        val p = provider()
        asShell {
            p.call("failed", null, failedBundle(attempt))
            p.call("failed", null, failedBundle(attempt))
        }

        assertTrue("a delayed callback must not displace the authenticated live owner", WatchClient.available(context))
        assertTrue(context.getSharedPreferences("ui-state", 0).getBoolean("watchRunning", false))
        assertEquals(1, WatchEvidence.report(context).lines().count { it.startsWith("Start attempt:") })
        assertTrue(WatchEvidence.report(context).contains("PACKAGE_CALLBACK"))
        assertFalse(WatchEvidence.report(context).contains("STARTUP_EXCEPTION"))
    }

    @Test fun failureAfterProviderAcceptanceExplainsTheSameHelpersDeathAndStartOnlyOnce() {
        val active=Peer()
        assertTrue(attach(active,previous))
        val p=provider()
        val extras=failedBundle(attempt,"CLIENT_HANDSHAKE").apply { putBinder("helper",active) }
        asShell { p.call("failed",null,extras) }
        WatchEvidence.hostFailure(context,attempt,"HANDSHAKE_UNAVAILABLE")
        die(active)
        val lines=WatchDiagnostics.report(context).lines()
        val start=lines.single { it.startsWith("Start attempt:") }
        val exit=lines.single { it.startsWith("Helper exit:") }
        assertTrue(start.contains("#1")&&start.contains("CLIENT_HANDSHAKE")&&start.contains("HANDSHAKE_UNAVAILABLE"))
        assertTrue(exit.contains("start #1")&&exit.contains("active Binder died")&&exit.contains("STARTUP_EXCEPTION"))
        assertFalse(exit.contains("no helper exit reason received"))
    }

    @Test fun installFailureCategoriesReachMailWithoutChangingPreviousWireCodes() {
        val oldWireOrder=listOf("INSTALL_LOOKUP_UNAVAILABLE","INSTALL_CHECK_FAILED","APP_REQUESTED_STOP","CONTACT_REFUSED",
            "APP_REPLACED","APP_REMOVED","WATCH_LOOP_EXCEPTION","WATCH_LOOP_ENDED","STARTUP_EXCEPTION","RUNTIME_LINKAGE_ERROR")
        assertEquals(oldWireOrder,WatchExitReason.entries.take(oldWireOrder.size).map { it.name })
        for(reason in listOf(WatchExitReason.INSTALL_LOOKUP_DENIED,WatchExitReason.INSTALL_UID_PACKAGES_UNAVAILABLE,
                WatchExitReason.INSTALL_IDENTITY_MISMATCH,WatchExitReason.INSTALL_CHECK_FAILED)){
            val peer=Peer()
            val helperId=java.util.UUID.randomUUID().toString()
            val launchId=java.util.UUID.randomUUID().toString()
            assertTrue(attach(peer,helperId,launchId))
            val receiver=requireNotNull(WatchClient.exitReceiver(context,peer))
            asShell { WatchExitReport.send(receiver,reason) }
            die(peer)
            val report=WatchDiagnostics.report(context)
            assertEquals(1,report.lines().count { it.startsWith("Helper exit:") && it.endsWith("helper reported ${reason.name}") &&
                it.contains("active Binder died") })
            assertFalse(report.contains(helperId))
            assertFalse(report.contains(launchId))
        }
    }

    @Test fun onlyAnInitialRefusalIsReportedAsAStartAndDoesNotChangeTheWatchSwitch() {
        BackgroundWatch.set(context,false)
        val p=provider()
        val extras=failedBundle(attempt).apply {
            putString("profile",profile(previous).toString())
        }
        asShell {
            assertFalse(p.call("attach",null,extras).getBoolean("enabled"))
            assertTrue(WatchEvidence.report(context).isBlank())
            extras.putBoolean("initialContact",true)
            assertFalse(p.call("attach",null,extras).getBoolean("enabled"))
        }
        assertTrue(WatchEvidence.report(context).contains("app declined initial contact"))
        assertFalse(BackgroundWatch.enabled(context))
    }

    @Test fun unreadableEvidenceDoesNotPreventAWatchFromConnectingOrPostingAGenuineStop() {
        val prefs=context.getSharedPreferences("watch-evidence",0)
        prefs.edit().putInt("starts",7).putInt("exits",8).commit()
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val peer=Peer()
        assertTrue(attach(peer,previous))
        assertTrue(WatchClient.available(context))
        assertFalse(context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any { it.id==4201 })
        die(peer)
        assertFalse(WatchClient.available(context))
        assertTrue(context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any { it.id==4201 })
    }

    @Test fun autoManagementOffAndManualWirelessOffReportTheStageFromTheRealRestartPath() {
        val manager=context.getSystemService(android.net.ConnectivityManager::class.java)
        val capabilities=android.net.NetworkCapabilities()
        val type=android.net.NetworkCapabilities::class.java
        type.getMethod("addTransportType",Int::class.javaPrimitiveType).invoke(capabilities,android.net.NetworkCapabilities.TRANSPORT_WIFI)
        type.getMethod("addCapability",Int::class.javaPrimitiveType).invoke(capabilities,android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        org.robolectric.Shadows.shadowOf(manager).setNetworkCapabilities(manager.activeNetwork,capabilities)
        org.robolectric.Shadows.shadowOf(manager).setLinkProperties(manager.activeNetwork,android.net.LinkProperties().apply { interfaceName="wlan0" })
        Settings.Global.putInt(context.contentResolver,"adb_wifi_enabled",0)
        ForegroundState.started()
        try {
            assertFalse(WatchClient.ensureStarted(context,true))
            val rows=WatchDiagnostics.report(context).lines().filter { it.startsWith("Start attempt:") }
            assertEquals(1,rows.size)
            assertTrue(rows.single().contains("WIRELESS_REQUIRED"))
            assertEquals(0,Settings.Global.getInt(context.contentResolver,"adb_wifi_enabled"))
        } finally { ForegroundState.stopped() }
    }
}
