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
    /** Owner 2026-11-01: before uninstalling, wireless and USB debugging both go off, but only with the permission. */
    @Test fun debuggingGoesOffBeforeUninstallOnlyWithThePermission(){
        val c=WirelessControls(context)
        Settings.Global.putInt(context.contentResolver,Settings.Global.ADB_ENABLED,1)
        c.disableDebugging()
        assertTrue(c.wireless);assertEquals(1,Settings.Global.getInt(context.contentResolver,Settings.Global.ADB_ENABLED))
        shadowOf(context).grantPermissions(WirelessControls.PERMISSION)
        c.disableDebugging()
        assertFalse(c.wireless);assertEquals(0,Settings.Global.getInt(context.contentResolver,Settings.Global.ADB_ENABLED))
    }
    private fun timeout()=Settings.Global.getString(context.contentResolver,WirelessControls.TIMEOUT)
    /** Owner 2026-10-29: with automatic management the 7-day pairing expiry is turned off, and put back when it ends. */
    @Test fun pairingExpiryIsTurnedOffOnlyWithManagementAndPutBack(){
        val c=WirelessControls(context)
        Settings.Global.putString(context.contentResolver,WirelessControls.TIMEOUT,"604800000")
        c.stopPairingExpiry();assertEquals("604800000",timeout()) // no consent, no permission
        c.setConsent(true);c.stopPairingExpiry();assertEquals("604800000",timeout()) // no permission
        shadowOf(context).grantPermissions(WirelessControls.PERMISSION)
        c.stopPairingExpiry();assertEquals("0",timeout())
        c.stopPairingExpiry();assertEquals("0",timeout())
        c.setConsent(false);assertEquals("604800000",timeout())
        val event=org.json.JSONObject(java.io.File(context.noBackupFilesDir,"system-setting-events.jsonl").readLines().last())
        assertEquals(WirelessControls.TIMEOUT,event.getString("key"));assertEquals("pairing_timeout_restore",event.getString("operation"))
    }
    @Test fun aValueChangedSinceStaysAndAnUnsetOneIsUnsetAgain(){
        val c=WirelessControls(context);shadowOf(context).grantPermissions(WirelessControls.PERMISSION);c.setConsent(true)
        Settings.Global.putString(context.contentResolver,WirelessControls.TIMEOUT,null)
        c.stopPairingExpiry();assertEquals("0",timeout())
        c.restorePairingExpiry();assertNull(timeout())
        c.stopPairingExpiry();Settings.Global.putString(context.contentResolver,WirelessControls.TIMEOUT,"86400000")
        c.restorePairingExpiry();assertEquals("86400000",timeout())
        c.stopPairingExpiry();assertEquals("0",timeout())
        c.restorePairingExpiry();assertEquals("86400000",timeout())
    }
    /** GPT rc11 review: 7 days -> 0, another tool sets 1 day, the app is opened again -> turning management off puts back 1 day. */
    @Test fun theLatestValueIsTheOneRestored(){
        val c=WirelessControls(context);shadowOf(context).grantPermissions(WirelessControls.PERMISSION);c.setConsent(true)
        Settings.Global.putString(context.contentResolver,WirelessControls.TIMEOUT,"604800000")
        c.stopPairingExpiry();assertEquals("0",timeout())
        Settings.Global.putString(context.contentResolver,WirelessControls.TIMEOUT,"86400000")
        c.stopPairingExpiry();assertEquals("0",timeout())
        c.setConsent(false);assertEquals("86400000",timeout())
    }
}
