package app.bluedotoff.adb

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import app.bluedotoff.WifiConnection
import app.bluedotoff.core.*
import org.json.JSONObject
import java.io.File

/** The entire settings write surface: two fixed methods, no external key/value input. */
class WirelessControls(private val context:Context) {
    companion object {const val PERMISSION="android.permission.WRITE_SECURE_SETTINGS"}
    private val prefs=context.getSharedPreferences("wireless-management",0)
    val consent get()=prefs.getBoolean("enabled",false)
    val granted get()=context.checkSelfPermission(PERMISSION)==PackageManager.PERMISSION_GRANTED
    val wireless get()=Settings.Global.getInt(context.contentResolver,"adb_wifi_enabled",0)==1
    fun setConsent(enabled:Boolean){check(prefs.edit().putBoolean("enabled",enabled).commit())}
    fun mayStart(userAction:Boolean)=WirelessManagementPolicy.mayEnable(WirelessFacts(userAction,
        Settings.Global.getInt(context.contentResolver,Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,0)==1,
        WifiConnection.connected(context),consent,granted,
        context.getSharedPreferences("local-adb",0).getString("pairedGuid",null)!=null&&AdbHostIdentity(context).exists,wireless))
    fun enableWirelessDebugging():Boolean {
        check(mayStart(app.bluedotoff.ForegroundState.active))
        val before=wireless
        val result=runCatching{Settings.Global.putInt(context.contentResolver,"adb_wifi_enabled",1)&&wireless}.getOrDefault(false)
        audit("wireless_on",before,wireless,result);return result
    }
    fun disableWirelessDebugging():Boolean {
        check(consent&&granted)
        val before=wireless
        val result=runCatching{Settings.Global.putInt(context.contentResolver,"adb_wifi_enabled",0)&&!wireless}.getOrDefault(false)
        audit("wireless_off",before,wireless,result);return result
    }
    private fun audit(operation:String,before:Boolean,after:Boolean,success:Boolean){
        val file=File(context.noBackupFilesDir,"system-setting-events.jsonl")
        if(file.length()>100000)file.writeText("")
        file.appendText(JSONObject().put("category","SYSTEM_SETTING").put("operation",operation)
            .put("key","adb_wifi_enabled").put("before",before).put("after",after).put("success",success)
            .put("uid",android.os.Process.myUid()).put("time",System.currentTimeMillis()).toString()+"\n")
    }
}
