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
    /** [afterRestart]: the after-restart job (R-1) is the only caller allowed without a visible screen. */
    fun enableWirelessDebugging(afterRestart:Boolean=false):Boolean {
        check(mayStart(app.bluedotoff.ForegroundState.active||afterRestart))
        val before=wireless
        val written=runCatching{Settings.Global.putInt(context.contentResolver,"adb_wifi_enabled",1)}.getOrDefault(false)
        val result=written&&wireless
        AutoBlocker.suspected=written&&!result
        audit("wireless_on",before,wireless,result);return result
    }
    fun disableWirelessDebugging():Boolean {
        check(consent&&granted)
        val before=wireless
        val result=runCatching{Settings.Global.putInt(context.contentResolver,"adb_wifi_enabled",0)&&!wireless}.getOrDefault(false)
        audit("wireless_off",before,wireless,result);return result
    }
    // Diagnostics only: a failed log write must not fail the switch, or wireless debugging stays on with no cleanup (Codex R2).
    private fun audit(operation:String,before:Boolean,after:Boolean,success:Boolean)=runCatching{
        val file=File(context.noBackupFilesDir,"system-setting-events.jsonl")
        if(file.length()>100000)file.writeText("")
        file.appendText(JSONObject().put("category","SYSTEM_SETTING").put("operation",operation)
            .put("key","adb_wifi_enabled").put("before",before).put("after",after).put("success",success)
            .put("uid",android.os.Process.myUid()).put("time",System.currentTimeMillis()).toString()+"\n")
    }
}

/** Samsung Auto Blocker (보안 위험 자동 차단) turns USB and Wi-Fi ADB off. Its own state (Secure "rampart_blocked_adb_cmd")
 *  is @hide and not readable by apps, even with WRITE_SECURE_SETTINGS (Fold7, 2026-10-10). Observed instead: while it is on,
 *  writing adb_wifi_enabled=1 succeeds but reads back 0 at once (off: stays 1). That refusal is what this remembers. */
object AutoBlocker {
    const val MESSAGE="휴대폰이 무선 디버깅을 막고 있어요. 설정 → 보안 및 개인정보 보호 → 보안 위험 자동 차단을 끈 뒤 다시 시도해주세요."
    @Volatile var suspected=false
}
