package app.bluedotoff

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WatchEvidenceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val first = "11111111-1111-4111-8111-111111111111"
    private val second = "22222222-2222-4222-8222-222222222222"
    private val oldHelper = "33333333-3333-4333-8333-333333333333"
    private val liveHelper = "44444444-4444-4444-8444-444444444444"

    @Before fun clearEvidenceAndLegacyHistory() {
        context.getSharedPreferences("watch-evidence", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("watch-diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun callbacksMayArriveBeforeTheirHostFailureAndStillCorrelateById() {
        WatchEvidence.helperFailure(context, first, "PACKAGE_CALLBACK", "SECURITY")
        WatchEvidence.exit(context, first, "INSTALL_LOOKUP_UNAVAILABLE")
        WatchEvidence.hostFailure(context, first, "HANDSHAKE_TIMEOUT")

        val report = WatchEvidence.report(context)
        assertEquals(1, report.lines().count { it.startsWith("Start attempt:") })
        assertTrue(report.contains("HANDSHAKE_TIMEOUT"))
        assertTrue(report.contains("PACKAGE_CALLBACK"))
        assertTrue(report.contains("SECURITY"))
        assertTrue(report.contains("INSTALL_LOOKUP_UNAVAILABLE"))
        assertFalse(report.contains(first))
    }

    @Test fun rapidIndependentAttemptsKeepTheirFailureEvidenceSeparate() {
        WatchEvidence.hostFailure(context, first, "HANDSHAKE_TIMEOUT")
        WatchEvidence.hostFailure(context, second, "ADB_AUTHENTICATION_FAILED")

        val report = WatchEvidence.report(context)
        val attempts = report.lines().filter { it.startsWith("Start attempt:") }
        assertEquals(2, attempts.size)
        assertTrue(attempts.any { it.contains("HANDSHAKE_TIMEOUT") })
        assertTrue(attempts.any { it.contains("ADB_AUTHENTICATION_FAILED") })
        assertFalse(report.contains(first))
        assertFalse(report.contains(second))
    }

    @Test fun lateCallbackFromPreviousHelperCannotExplainDifferentHelpersDeath() {
        WatchEvidence.helperFailure(context, oldHelper, "PACKAGE_CALLBACK", "SECURITY")
        WatchEvidence.died(context, liveHelper)

        val report = WatchEvidence.report(context)
        assertTrue(report.contains("Helper exit:"))
        assertTrue(report.contains("no helper exit reason received"))
        assertFalse(report.contains("INSTALL_LOOKUP_UNAVAILABLE"))
        assertFalse(report.contains(oldHelper))
        assertFalse(report.contains(liveHelper))
    }

    @Test fun hostFailureForRestartGetsItsOwnEntryAcrossSeparateReportReads() {
        WatchEvidence.helperFailure(context, oldHelper, "PACKAGE_CALLBACK", "SECURITY")
        val beforeRestart = WatchEvidence.report(context)
        assertTrue(beforeRestart.contains("PACKAGE_CALLBACK"))

        WatchEvidence.hostFailure(context, second, "ADB_AUTHENTICATION_FAILED")
        val afterRestart = WatchEvidence.report(context)
        assertEquals(2, afterRestart.lines().count { it.startsWith("Start attempt:") })
        assertTrue(afterRestart.contains("ADB_AUTHENTICATION_FAILED"))
        assertFalse(afterRestart.contains(second))
    }

    @Test fun malformedIdsAndUntrustedTokensAreIgnoredWithoutLeakingInput() {
        val hostile = "bad-id-PRIVATE-PACKAGE-123"
        WatchEvidence.hostFailure(context, hostile, "HANDSHAKE_TIMEOUT")
        WatchEvidence.helperFailure(context, first, "PACKAGE_CALLBACK\n$hostile", "SECURITY\n$hostile")
        WatchEvidence.exit(context, first, "INSTALL_LOOKUP_UNAVAILABLE\n$hostile")
        WatchEvidence.died(context, hostile)

        val report = WatchEvidence.report(context)
        assertFalse(report.contains(hostile))
        assertFalse(report.contains("PRIVATE-PACKAGE"))
        assertFalse(report.contains("SECURITY\n"))
    }

    @Test fun build69RingAndReadStatisticsRemainInTheCombinedReport() {
        repeat(12) { WatchDiagnostics.record(context, WatchDiagnostics.Event.USB_OFF) }
        WatchDiagnostics.watchWork(context, checked = 37, rewritten = 4)
        WatchEvidence.hostFailure(context, first, "HANDSHAKE_TIMEOUT")

        val report = WatchDiagnostics.report(context)
        assertTrue(report.contains("latest 10 events"))
        assertTrue(report.contains("USB debugging reported off"))
        assertTrue(report.contains("37 apps read; 4 successful writes"))
        assertEquals(1, report.lines().count { it.startsWith("Start attempt:") })
    }

    @Test fun manyRestartsCannotEvictTheLastUnexplainedDeathAndCorruptLegacyDataCannotHideIt() {
        WatchEvidence.died(context, oldHelper)
        repeat(16) { WatchEvidence.hostFailure(context, java.util.UUID.randomUUID().toString(), "HANDSHAKE_TIMEOUT") }
        context.getSharedPreferences("watch-diagnostics", 0).edit().putInt("events", 42).commit()
        val report=WatchDiagnostics.report(context)
        assertEquals(12, report.lines().count { it.startsWith("Start attempt:") })
        assertTrue(report.contains("no helper exit reason received"))
    }

    @Test fun interruptedAttemptRetainsLastEnteredStageAndStorageFailureIsBestEffort() {
        WatchEvidence.entered(context, first, "RESIDENT_LAUNCH")
        assertTrue(WatchDiagnostics.report(context).contains("entered RESIDENT_LAUNCH | outcome not recorded"))
        val prefs=context.getSharedPreferences("watch-evidence",0)
        prefs.edit().putInt("starts", 1).commit()
        WatchEvidence.hostFailure(context, first, "RESIDENT_LAUNCH")
        WatchDiagnostics.watchWork(context, 8, 1)
        assertTrue(WatchDiagnostics.report(context).contains("8 apps read; 1 successful writes"))
    }

    @Test fun deviceManagedAndExternalStartsKeepTheirStageAndBoundedStopHistory() {
        WatchEvidence.entered(context,first,"WIRELESS_PREPARATION")
        WatchEvidence.hostFailure(context,first,"ADB_AUTHENTICATION_FAILED")
        WatchEvidence.helperFailure(context,second,"PROCESS_CALLBACK","sensitive info",true)
        WatchEvidence.died(context,oldHelper,first)
        repeat(17) { WatchEvidence.stopRequested(context,java.util.UUID.randomUUID().toString(),WatchNotification.Reason.STOPPED,"") }
        val lines=WatchEvidence.report(context).lines()
        assertEquals(12,lines.count { it.startsWith("Helper exit:") })
        assertTrue(lines.single { it.contains("active Binder died") }.contains("start #1"))
        assertTrue(lines.single { it.contains("ADB_AUTHENTICATION_FAILED") }.contains("entered WIRELESS_PREPARATION"))
        val external=lines.single { it.contains("PROCESS_CALLBACK") }
        assertTrue(external.contains("external launch")&&external.contains("OTHER"))
        assertFalse(external.contains("sensitive info"))
    }
}
