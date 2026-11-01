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
        if(action==ReapplyNotification.DISMISSED){runCatching{ReapplyNotification.dismiss(context,intent.getStringArrayExtra("issues").orEmpty().toSet())};return}
        if (action !in listOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_PACKAGE_REPLACED, Intent.ACTION_PACKAGE_REMOVED,Intent.ACTION_PACKAGE_FULLY_REMOVED)) return
        if (action == Intent.ACTION_PACKAGE_REMOVED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
        // A restart always stops background watching; it is started again from the app (wireless debugging).
        if (action == Intent.ACTION_BOOT_COMPLETED) { WatchNotification.stopped(context,WatchNotification.Reason.BOOT) }
        if(action==Intent.ACTION_MY_PACKAGE_REPLACED){WatchNotification.stopped(context,WatchNotification.Reason.STOPPED);return}
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
                if (changed.isNotEmpty() && action !in listOf(Intent.ACTION_PACKAGE_REMOVED,Intent.ACTION_PACKAGE_FULLY_REMOVED)) {
                    val queued=action==Intent.ACTION_PACKAGE_REPLACED && app.bluedotoff.adb.WatchClient.signal(context,changed.map{it.identity.packageName}.toSet())
                    if(!queued)ReapplyNotification.show(context, if (action == Intent.ACTION_BOOT_COMPLETED) null else changed.first())
                }
            } catch (_: Exception) {
                // Corrupt journal remains intact. Next foreground screen displays recovery failure.
            } finally {
                // R-1: only after the restart is recorded in the journal, so the job sees what the restart unhid.
                if (action == Intent.ACTION_BOOT_COMPLETED && RestartRecoveryJob.eligible(context)) RestartRecoveryJob.schedule(context)
                pending.finish()
            }
        }
    }
}

object ReapplyNotification {
    const val CHANNEL = "reapply"
    private const val ID = 4101
    const val DISMISSED="app.bluedotoff.REAPPLY_DISMISSED"
    private fun noticePrefs(c:Context)=c.getSharedPreferences("reapply-notices",Context.MODE_PRIVATE)
    private fun digest(value:String)=java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
    /** Keep one token per unresolved episode, not per retry. A resolved or replaced install starts a new episode. */
    private fun episodes(c:Context,entries:List<Entry>):Set<String>? {
        val prefs=noticePrefs(c)
        val old=prefs.all.filterKeys{it.startsWith("issue:")}.mapValues{it.value as String}
        val current=entries.associate{e->val i=e.identity;val key="issue:"+digest(listOf(i.packageName,i.userId,i.uid,i.signers,i.firstInstallTime).joinToString("|"));key to (old[key]?:java.util.UUID.randomUUID().toString())}
        val dismissed=prefs.getStringSet("dismissed",emptySet()).orEmpty()
        val retained=dismissed.intersect(current.values.toSet())
        if(old!=current||dismissed!=retained){
            val edit=prefs.edit();(old.keys-current.keys).forEach{edit.remove(it)};current.forEach{(k,v)->edit.putString(k,v)}
            if(!edit.putStringSet("dismissed",retained).commit())return null
        }
        return current.values.toSet()
    }
    @Synchronized fun dismiss(c:Context,issues:Set<String>){
        val prefs=noticePrefs(c)
        val current=prefs.all.filterKeys{it.startsWith("issue:")}.values.filterIsInstance<String>().toSet()
        val dismissed=prefs.getStringSet("dismissed",emptySet()).orEmpty()+issues.intersect(current)
        if(!prefs.edit().putStringSet("dismissed",dismissed).commit())return
        // A delayed deleteIntent for an older summary must not dismiss a newly failed app.
        val manager=c.getSystemService(NotificationManager::class.java)
        val active=manager.activeNotifications.firstOrNull{it.id==ID&&it.notification.channelId==CHANNEL}
        val shown=active?.notification?.extras?.getStringArray("issues")?.toSet()
        if(shown!=null&&shown.all{it in dismissed})manager.cancel(ID)
    }
    private fun remaining(context:Context)=UiModel(entries=JournalStore(context).entries()).let{it.reapplyEntries+it.checkEntries}
    /** Keep one reminder, including unresolved failures when another app succeeds. Remove legacy per-app notices too. */
    @Synchronized fun clear(context: Context) {
        val entries=remaining(context)
        if(episodes(context,entries)==null)return
        val manager = context.getSystemService(NotificationManager::class.java)
        val notices=manager.activeNotifications.filter{it.notification.channelId==CHANNEL}
        if(entries.isEmpty())notices.forEach{manager.cancel(it.tag,it.id)}
        else if(notices.isNotEmpty())show(context,null,checkInApp=true)
    }
    /** Report remaining work after watch processing or interruption; success alone never revives a dismissed reminder. */
    @Synchronized fun watchFinished(context:Context,packages:Collection<String>) {
        val unresolved=remaining(context)
        if(unresolved.any{it.identity.packageName in packages})show(context,unresolved.first(),checkInApp=true)
        else clear(context)
    }
    /** Opening bluedotOFF re-applies by itself (setting on and automatic management ready). */
    private fun autoReapply(context: Context) = context.getSharedPreferences("ui-state",Context.MODE_PRIVATE).getBoolean("autoReapply",true) &&
        app.bluedotoff.adb.WirelessControls(context).let { it.consent && it.granted }
    /** [checkInApp]: some apps still need attention after processing, interruption or a partial success. */
    @Synchronized fun show(context: Context, target: Entry?, checkInApp: Boolean = false) {
        val entries=remaining(context)
        val issues=episodes(context,entries)?:return
        val manager = context.getSystemService(NotificationManager::class.java)
        // Only our reapply channel is consolidated; pairing and stopped-watch notices are independent.
        manager.activeNotifications.filter{it.notification.channelId==CHANNEL&&it.id!=ID}.forEach{manager.cancel(it.tag,it.id)}
        if(entries.isEmpty()){manager.cancel(ID);return}
        if(issues.all{it in noticePrefs(context).getStringSet("dismissed",emptySet()).orEmpty()}){manager.cancel(ID);return}
        if(ForegroundState.active)return
        if(!context.getSharedPreferences("ui-state",Context.MODE_PRIVATE).getBoolean("reapplyNotify",true))return
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if(!manager.areNotificationsEnabled())return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, app.bluedotoff.UiText.text(context,"재적용 안내"), NotificationManager.IMPORTANCE_DEFAULT).apply { description = app.bluedotoff.UiText.text(context,"숨김을 다시 적용하거나 확인해야 할 때 알려드려요.") })
        val intent = Intent(context, MainActivity::class.java).setAction("app.bluedotoff.REAPPLY").putExtra("check",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val content = PendingIntent.getActivity(context, ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val deleted=Intent(context,LifecycleReceiver::class.java).setAction(DISMISSED).setData(android.net.Uri.parse("bluedotoff:notice/"+digest(issues.sorted().joinToString("|")))).putExtra("issues",issues.toTypedArray())
        val delete=PendingIntent.getBroadcast(context,ID,deleted,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body=if(checkInApp) "숨김 설정을 확인해야 하는 앱이 남았어요. 앱을 열어 이유를 확인해 주세요."
            else if(target!=null) "앱 업데이트 후 숨김 설정을 다시 확인해야 해요. bluedotOFF를 열어 확인해 주세요."
            else if(RestartRecoveryJob.eligible(context)) "Wi-Fi에 연결되면 숨김을 자동으로 다시 적용해요. 그래도 남아 있으면 bluedotOFF를 열어주세요."
            else if(autoReapply(context)) "bluedotOFF를 열면 숨김을 자동으로 다시 적용해요."
            else "재부팅 후에는 무선 디버깅을 잠시 켜고 등록 앱을 확인해주세요."
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_dot).setContentTitle(UiText.text(context,"파란 위치 표시 · 확인할 앱 ${entries.size}개")).setContentText(UiText.text(context,body)).setStyle(Notification.BigTextStyle().bigText(UiText.text(context,body))).setContentIntent(content).setDeleteIntent(delete).addExtras(android.os.Bundle().apply{putStringArray("issues",issues.toTypedArray())}).setAutoCancel(true).setOnlyAlertOnce(true).addAction(Notification.Action.Builder(null,UiText.text(context,"설정 확인"),content).build()).build()
        try { manager.notify(ID, notification) } catch (_: SecurityException) { /* Notifications optional; journal already durable. */ }
    }
}
