package app.bluedotoff

import android.app.Application
import android.provider.Settings
import app.bluedotoff.adb.WirelessControls
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Preference and gate models only; this does not prove Samsung provider authorization. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class WirelessControlsTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Before fun reset(){
        context.getSharedPreferences("wireless-management",0).edit().clear().commit()
        shadowOf(context).denyPermissions(WirelessControls.PERMISSION)
        Settings.Global.putInt(context.contentResolver,"adb_wifi_enabled",1)
    }
    @Test fun retainedPermissionAfterPreferenceClearIsNotConsent(){
        val c=WirelessControls(context);shadowOf(context).grantPermissions(WirelessControls.PERMISSION)
        c.setConsent(true);context.getSharedPreferences("wireless-management",0).edit().clear().commit()
        assertTrue(c.granted);assertFalse(c.consent);assertFalse(c.mayStart(true))
        try{c.disableWirelessDebugging();fail("No settings write without consent")}catch(_:IllegalStateException){}
        assertTrue(c.wireless)
    }
    @Test fun permissionLossPreventsOffDespiteConsent(){
        val c=WirelessControls(context);c.setConsent(true)
        try{c.disableWirelessDebugging();fail("No settings write without actual permission")}catch(_:IllegalStateException){}
        assertTrue(c.wireless)
    }
    @Test fun fixedOffTouchesOnlyWirelessSettingAndRecordsOrdinaryUid(){
        val c=WirelessControls(context);c.setConsent(true);shadowOf(context).grantPermissions(WirelessControls.PERMISSION)
        Settings.Global.putInt(context.contentResolver,Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,1)
        Settings.Global.putInt(context.contentResolver,Settings.Global.ADB_ENABLED,1)
        assertTrue(c.disableWirelessDebugging());assertFalse(c.wireless)
        assertEquals(1,Settings.Global.getInt(context.contentResolver,Settings.Global.DEVELOPMENT_SETTINGS_ENABLED))
        assertEquals(1,Settings.Global.getInt(context.contentResolver,Settings.Global.ADB_ENABLED))
        val event=org.json.JSONObject(java.io.File(context.noBackupFilesDir,"system-setting-events.jsonl").readLines().last())
        assertEquals("SYSTEM_SETTING",event.getString("category"));assertEquals(android.os.Process.myUid(),event.getInt("uid"))
    }
}
