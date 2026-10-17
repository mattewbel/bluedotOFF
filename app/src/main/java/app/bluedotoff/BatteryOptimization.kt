package app.bluedotoff

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast

/** User-mediated battery exemption only. No helper, AppOps setter or allowlist mutation. */
object BatteryOptimization {
    const val TITLE="배터리 사용 제한 해제"
    const val DESCRIPTION="배터리 설정을 ‘제한 없음’으로 바꿔요. 백그라운드 감시가 늦게 동작할 때 도움이 될 수 있어요. 배터리 사용량이 늘 수 있어요."
    /** Samsung's unrestricted state combines power allowlisting and unrestricted background use.
     *  A failed read is unknown, never an inferred approval. */
    fun unrestricted(context:Context):Boolean?=runCatching {
        val power=context.getSystemService(PowerManager::class.java)?:return@runCatching null
        val activity=context.getSystemService(ActivityManager::class.java)?:return@runCatching null
        power.isIgnoringBatteryOptimizations(context.packageName)&&!activity.isBackgroundRestricted
    }.getOrNull()
    fun requestIntent(context:Context)=Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.fromParts("package",context.packageName,null))
    fun settingsIntent(context:Context)=Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package",context.packageName,null))
    /** The Android dialog owns approval. Returning from it does not mean permission was granted. */
    fun open(context:Context,enable:Boolean=true):Boolean {
        // The system dialog exits immediately for an allowlisted app, even if its
        // background use was restricted separately. App info can resolve that case.
        val ignored=runCatching{context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName)}.getOrNull()
        if(enable&&ignored==false&&launch(context,requestIntent(context)))return true
        if(launch(context,settingsIntent(context))){
            Toast.makeText(context,UiText.text(context,if(enable)"배터리 → 제한 없음에서 설정을 바꿀 수 있어요." else "앱 정보 → 배터리에서 ‘최적화’를 선택해주세요."),Toast.LENGTH_LONG).show()
            return true
        }
        Toast.makeText(context,UiText.text(context,"설정 화면을 열지 못했어요. 휴대폰 설정의 앱 정보 → 배터리에서 바꿔주세요."),Toast.LENGTH_LONG).show()
        return false
    }
    private fun launch(context:Context,intent:Intent):Boolean=runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));true
    }.getOrDefault(false)
}
