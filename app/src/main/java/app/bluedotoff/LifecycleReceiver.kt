package app.bluedotoff

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.bluedotoff.core.*
import java.util.concurrent.Executors

/** Local journal + notification only. Deliberately has no dependency on BinderBackend/SafetyEngine. */
class LifecycleReceiver : BroadcastReceiver() {
    companion object { private val executor = Executors.newSingleThreadExecutor() }
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_PACKAGE_REPLACED, Intent.ACTION_PACKAGE_REMOVED,Intent.ACTION_PACKAGE_FULLY_REMOVED)) return
        if (action == Intent.ACTION_PACKAGE_REMOVED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
        val pending = goAsync()
        executor.execute {
            try {
                val store = JournalStore(context)
                val target = intent.data?.schemeSpecificPart
                val previous=store.entries()
                val currentBoot=if(action==Intent.ACTION_BOOT_COMPLETED)BootSession.current(context) else ""
                val relevant = previous.filter { it.enabled && (if(action == Intent.ACTION_BOOT_COMPLETED)BootEvents.fromBroadcast(it,currentBoot)!=it else it.identity.packageName == target) }
                if (relevant.isEmpty()) return@execute
                check(store.transform { e -> when {
                    !e.enabled || e.state in listOf(TargetState.FAILED_ROLLBACK,TargetState.SHARED_UID_CHANGED,TargetState.NEEDS_REVIEW) -> e
                    action == Intent.ACTION_BOOT_COMPLETED -> BootEvents.fromBroadcast(e,currentBoot)
                    e.identity.packageName != target -> e
                    action in listOf(Intent.ACTION_PACKAGE_REMOVED,Intent.ACTION_PACKAGE_FULLY_REMOVED) -> e.copy(state = TargetState.MISSING_TARGET)
                    else -> Reconcile.update(e)
                } })
                val changed=store.entries().filter{e->e.enabled&&e.state in listOf(TargetState.NEEDS_REAPPLY_BOOT,TargetState.NEEDS_REAPPLY_UPDATE)&&previous.firstOrNull{it.identity==e.identity}?.state!=e.state}
                LifecycleEvents.changed()
                if (changed.isNotEmpty() && action !in listOf(Intent.ACTION_PACKAGE_REMOVED,Intent.ACTION_PACKAGE_FULLY_REMOVED)) ReapplyNotification.show(context, if (action == Intent.ACTION_BOOT_COMPLETED) null else changed.first())
            } catch (_: Exception) {
                // Corrupt journal remains intact. Next foreground screen displays recovery failure.
            } finally { pending.finish() }
        }
    }
}

object ReapplyNotification {
    const val CHANNEL = "reapply"
    fun show(context: Context, target: Entry?) {
        if(ForegroundState.active)return
        if(!context.getSharedPreferences("ui-state",Context.MODE_PRIVATE).getBoolean("reapplyNotify",true))return
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if(!manager.areNotificationsEnabled())return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, app.bluedotoff.UiText.text(context,"재적용 안내"), NotificationManager.IMPORTANCE_DEFAULT).apply { description = app.bluedotoff.UiText.text(context,"휴대폰 재시작과 등록 앱 업데이트 때만 알려드립니다.") })
        val intent = Intent(context, MainActivity::class.java).setAction("app.bluedotoff.REAPPLY").putExtra("target", target?.identity?.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val id = target?.identity?.packageName?.hashCode() ?: 4101
        val content = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_dot).setContentTitle(app.bluedotoff.UiText.text(context,"Blue Dot 설정을 다시 확인해주세요")).setContentText(if (target == null) app.bluedotoff.UiText.text(context,"재부팅 후에는 무선 디버깅을 잠시 켜고 등록 앱을 확인해주세요.") else app.bluedotoff.UiText.text(context,"${target.label} 업데이트 후 설정을 다시 확인해주세요.")).setContentIntent(content).setAutoCancel(true).addAction(Notification.Action.Builder(null,app.bluedotoff.UiText.text(context,"설정 확인"),content).build()).build()
        try { manager.notify(id, notification) } catch (_: SecurityException) { /* Notifications optional; journal already durable. */ }
    }
}
