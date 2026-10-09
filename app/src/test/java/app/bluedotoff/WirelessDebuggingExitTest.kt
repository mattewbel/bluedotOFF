package app.bluedotoff

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import android.content.pm.ResolveInfo
import android.content.pm.ActivityInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class WirelessDebuggingExitTest {
    @Test fun directSettingsWhenAvailable(){val c=ApplicationProvider.getApplicationContext<android.content.Context>();val intent=Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS");shadowOf(c.packageManager).addResolveInfoForIntent(intent,ResolveInfo().apply{activityInfo=ActivityInfo().apply{packageName="com.android.settings";name="Wireless"}});assertEquals(intent.action,WirelessDebuggingExit.intent(c).action)}
    @Test fun unsupportedIntentFallsBackToHighlightedDeveloperPage(){val c=ApplicationProvider.getApplicationContext<android.content.Context>();val i=WirelessDebuggingExit.intent(c);assertEquals(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,i.action);assertEquals("toggle_adb_wireless",i.getStringExtra(":settings:fragment_args_key"))}
}
