package app.bluedotoff.adb

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import app.bluedotoff.WifiConnection
import app.bluedotoff.core.*
import org.json.JSONObject
import java.io.File

/** The entire settings write surface: fixed methods for fixed keys, no external key/value input. */
class WirelessControls(private val context:Context) {
    companion object {const val PERMISSION="android.permission.WRITE_SECURE_SETTINGS";const val TIMEOUT="adb_allowed_connection_time"}
    private val prefs=context.getSharedPreferences("wireless-management",0)
    val consent get()=prefs.getBoolean("enabled",false)
    val granted get()=context.checkSelfPermission(PERMISSION)==PackageManager.PERMISSION_GRANTED
    /** An original expiry is saved and not yet put back. */
    val expiryPending get()=prefs.contains("timeoutBefore")
    val wireless get()=Settings.Global.getInt(context.contentResolver,"adb_wifi_enabled",0)==1
    fun setConsent(enabled:Boolean){if(!enabled)restorePairingExpiry();check(prefs.edit().putBoolean("enabled",enabled).commit())}
    /** Android forgets a wireless pairing 7 days after it was made, even while it is used every day (AdbDebuggingManager
     *  keeps the pairing time as its last connection; Fold7 2026-10-29: the watch could not start again after an update).
     *  With automatic management the app turns that expiry off. It applies to every authorised computer too; owner decision
     *  2026-10-29: using one needs that computer and this phone. The old value is kept and put back by [setConsent] false
     *  and the uninstall restore. */
    fun stopPairingExpiry(){
        if(!consent||!granted)return
        val now=Settings.Global.getString(context.contentResolver,TIMEOUT)
        if(now=="0")return
        // The latest value wins: one set by another tool since (e.g. 7 days -> 1 day) is the one to put back (GPT rc11 review).
        check(prefs.edit().putString("timeoutBefore",now.orEmpty()).commit())
        val result=runCatching{Settings.Global.putString(context.contentResolver,TIMEOUT,"0")}.getOrDefault(false)
        audit("pairing_timeout_off",TIMEOUT,now,Settings.Global.getString(context.contentResolver,TIMEOUT),result)
    }
    /** Puts the expiry back, unless someone changed it since (then theirs stays; this needs no permission, e.g. the
     *  owner turned the Developer options switch off after the app lost its permission, GPT rc15 review). */
    fun restorePairingExpiry(){
        val before=prefs.getString("timeoutBefore",null)?:return
        val now=Settings.Global.getString(context.contentResolver,TIMEOUT)
        if(now=="0"&&!granted)return
        val result=now!="0"||runCatching{Settings.Global.putString(context.contentResolver,TIMEOUT,before.ifEmpty{null})}.getOrDefault(false)
        audit("pairing_timeout_restore",TIMEOUT,now,Settings.Global.getString(context.contentResolver,TIMEOUT),result)
        if(result)prefs.edit().remove("timeoutBefore").commit()
    }
    fun mayStart(userAction:Boolean)=WirelessManagementPolicy.mayEnable(WirelessFacts(userAction,
        Settings.Global.getInt(context.contentResolver,Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,0)==1,
        WifiConnection.connected(context),consent,granted,
        context.getSharedPreferences("local-adb",0).getString("pairedGuid",null)!=null&&AdbHostIdentity(context).exists,wireless))
    /** [noScreen]: the after-restart job (R-1) and the widget's refresh tap are the only callers allowed without a visible screen. */
    fun enableWirelessDebugging(noScreen:Boolean=false):Boolean = requestWirelessDebugging(noScreen)==EnableResult.ON
    internal enum class EnableResult { ON, REFUSED, FAILED }
    internal fun requestWirelessDebugging(noScreen:Boolean):EnableResult {
        check(mayStart(app.bluedotoff.ForegroundState.active||noScreen))
        runCatching{stopPairingExpiry()}
        val before=wireless
        val written=runCatching{Settings.Global.putInt(context.contentResolver,"adb_wifi_enabled",1)}.getOrDefault(false)
        val after=runCatching{Settings.Global.getInt(context.contentResolver,"adb_wifi_enabled",-1)}.getOrDefault(-1)
        val result=when { !written->EnableResult.FAILED; after==1->EnableResult.ON; after==0->EnableResult.REFUSED; else->EnableResult.FAILED }
        AutoBlocker.suspected=result==EnableResult.REFUSED
        audit("wireless_on","adb_wifi_enabled",before,after.takeIf{it>=0}?.let{it==1},result==EnableResult.ON)
        return result
    }
    fun disableWirelessDebugging():Boolean {
        check(consent&&granted)
        val before=wireless
        val result=runCatching{Settings.Global.putInt(context.contentResolver,"adb_wifi_enabled",0)&&!wireless}.getOrDefault(false)
        audit("wireless_off","adb_wifi_enabled",before,wireless,result);return result
    }
    /** Before uninstalling, once every app is back (owner 2026-11-01): wireless and USB debugging both off. USB goes last,
     *  as it ends every ADB connection. Needs the app's settings permission; without it the user turns them off. */
    fun disableDebugging(){
        if(!granted)return
        for(key in listOf("adb_wifi_enabled",Settings.Global.ADB_ENABLED)){
            val before=Settings.Global.getInt(context.contentResolver,key,0)
            if(before==0)continue
            val result=runCatching{Settings.Global.putInt(context.contentResolver,key,0)}.getOrDefault(false)
            audit("debugging_off",key,before,Settings.Global.getInt(context.contentResolver,key,0),result)
        }
    }
    // Diagnostics only: a failed log write must not fail the switch, or wireless debugging stays on with no cleanup (Codex R2).
    private fun audit(operation:String,key:String,before:Any?,after:Any?,success:Boolean)=runCatching{
        val file=File(context.noBackupFilesDir,"system-setting-events.jsonl")
        if(file.length()>100000)file.writeText("")
        file.appendText(JSONObject().put("category","SYSTEM_SETTING").put("operation",operation)
            .put("key",key).put("before",before?:JSONObject.NULL).put("after",after?:JSONObject.NULL).put("success",success)
            .put("uid",android.os.Process.myUid()).put("time",System.currentTimeMillis()).toString()+"\n")
    }
}

/** Samsung Auto Blocker (보안 위험 자동 차단) turns USB and Wi-Fi ADB off. Its own state (Secure "rampart_blocked_adb_cmd")
 *  is @hide and not readable by apps, even with WRITE_SECURE_SETTINGS (Fold7, 2026-10-10). Observed instead: while it is on,
 *  writing adb_wifi_enabled=1 succeeds but reads back 0 at once (off: stays 1). That refusal is what this remembers. */
object AutoBlocker {
    const val SETUP_VISIT="securitySettingsVisitedDuringSetup"
    const val TITLE=SetupFlow.AUTO_BLOCKER_TITLE
    const val MESSAGE=SetupFlow.AUTO_BLOCKER_HINT
    const val FINISHED="확인이 끝났어요. 보안 위험 자동 차단을 껐다면 다시 켜도 돼요."
    const val CONNECTION_HINT="무선 디버깅에 연결할 수 없어요. 이 Wi-Fi에서 무선 디버깅을 허용했는지, 보안 위험 자동 차단이 연결을 막고 있는지 확인해 주세요."
    class EnableRefused:IllegalStateException(MESSAGE)
    class EnableFailed:IllegalStateException("무선 디버깅을 켜지 못했어요. 설정에서 직접 켜주세요.")
    fun connectionGuide(error:Throwable):String?=when{
        error is EnableRefused -> MESSAGE
        error is EnableFailed || error is WirelessWork.ServiceNotFound || error is LocalAdbStageException&&
            error.state in setOf(LocalAdbState.ADB_CONNECTION_FAILED,LocalAdbState.ADB_DISCOVERY_FAILED) -> CONNECTION_HINT
        else -> null
    }
    fun isGuide(message:String)=message==MESSAGE||message==CONNECTION_HINT
    @Volatile var suspected=false
}
