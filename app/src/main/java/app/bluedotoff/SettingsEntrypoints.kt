package app.bluedotoff

import android.content.*
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.Bundle
import android.widget.Toast

/** Only publicly exported, permission-accessible activities; no fragment injection. */
object SettingsEntrypoints {
    fun softwareInformation(context:Context) {
        val candidates=listOf(
            // Fold7's exported FirmwareVersionActivity binds AOSP firmware_version.
            // Its SimpleBuildNumber is not Samsung's seven-tap developer controller.
            Intent("com.samsung.settings.SOFTWARE_INFO_SETTINGS"),
            Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),Intent(Settings.ACTION_SETTINGS))
        for(intent in candidates) {
            val direct=intent.action=="com.samsung.settings.SOFTWARE_INFO_SETTINGS"
            if(direct||intent.action==Settings.ACTION_DEVICE_INFO_SETTINGS){
                val key=if(direct)"build_number" else "software_info"
                intent.putExtra(":settings:fragment_args_key",key)
                    .putExtra(":settings:show_fragment_args",Bundle().apply{putString(":settings:fragment_args_key",key)})
            }
            intent.setPackage("com.android.settings")
            val info=context.packageManager.resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?:continue
            if(!info.exported||!info.enabled||!info.applicationInfo.enabled)continue
            if(info.permission!=null&&context.checkSelfPermission(info.permission)!=PackageManager.PERMISSION_GRANTED)continue
            if(runCatching{context.startActivity(intent)}.isSuccess){
                Toast.makeText(context,UiText.text(context,if(direct)"빌드번호를 7번 연속 누르세요." else "소프트웨어 정보 → 빌드번호 7번 누르기"),Toast.LENGTH_LONG).show()
                return
            }
        }
    }
    fun wifi(context:Context) {
        for(action in listOf(Settings.Panel.ACTION_WIFI,Settings.ACTION_WIFI_SETTINGS,Settings.ACTION_SETTINGS))
            if(runCatching{context.startActivity(Intent(action))}.isSuccess)return
    }
}
