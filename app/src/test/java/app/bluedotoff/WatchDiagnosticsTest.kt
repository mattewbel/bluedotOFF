package app.bluedotoff
import android.app.Application

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class)
class WatchDiagnosticsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun clearLocalHistory() {
        context.getSharedPreferences("watch-diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ui-state", Context.MODE_PRIVATE).edit().remove("watchRunning").commit()
    }

    @Test fun ringKeepsOnlyTenWhitelistedEventsAndEnglishTimestamps() {
        repeat(12) { WatchDiagnostics.record(context, WatchDiagnostics.Event.USB_OFF) }

        val text = WatchDiagnostics.report(context)
        assertTrue(text.contains("latest 10 events"))
        assertTrue(text.contains("USB debugging reported off"))
        assertTrue(text.contains("T"))
        assertFalse(text.contains("latest 12 events"))
        assertFalse(text.contains("package"))
    }

    @Test fun latestWorkTotalsAreSeparateAndDoNotCreateEvents() {
        WatchDiagnostics.record(context, WatchDiagnostics.Event.ATTACH_NEW)
        WatchDiagnostics.watchWork(context, checked = 37, rewritten = 4)

        val text = WatchDiagnostics.report(context)
        assertTrue(text.contains("37 apps read; 4 successful writes"))
        assertTrue(text.contains("New helper connection confirmed"))
        assertTrue(text.contains("latest 10 events"))
    }

    @Test fun noWritePassKeepsLastSuccessfulWriteAndOpenWorkHasItsOwnLine() {
        WatchDiagnostics.watchWork(context, checked = 9, rewritten = 3)
        WatchDiagnostics.watchWork(context, checked = 12, rewritten = 0)
        WatchDiagnostics.openWork(context, checked = 5, rewritten = 2)
        val text = WatchDiagnostics.report(context)

        assertTrue(text.contains("12 apps read; 0 successful writes"))
        assertTrue(text.contains("Latest app-open automatic reapply"))
        assertTrue(text.contains("5 apps verified complete; 2 successful writes"))
        assertTrue(text.contains("Last successful write"))
        assertTrue(text.contains("2 apps"))
    }

    @Test fun uncertainContactIsReportedAsAnEventWithoutGuessingCurrentStatus() {
        WatchDiagnostics.record(context, WatchDiagnostics.Event.CONTACT_UNCONFIRMED)
        val text = WatchDiagnostics.report(context)
        assertTrue(text.contains("Helper contact could not be confirmed"))
        assertFalse(text.contains("Current watch status:"))
        WatchDiagnostics.record(context, WatchDiagnostics.Event.CONTACT_DELAYED)
        assertTrue(WatchDiagnostics.report(context).contains("Expected helper contact was delayed"))
    }

    @Test fun aPassSavedByBuild57KeepsItsOriginalMeaning() {
        context.getSharedPreferences("watch-diagnostics",0).edit()
            .putString("last_work","""{"at":1000,"checked":3,"rewritten":0}""").commit()
        assertTrue(WatchDiagnostics.report(context).contains("3 apps with verification snapshots"))
    }
}
