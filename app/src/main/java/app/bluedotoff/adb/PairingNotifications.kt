package app.bluedotoff.adb

import android.app.*
import android.content.Context
import android.content.Intent
import app.bluedotoff.R

/** Same notification id is replaced only after a pairing advertisement; no keyboard injection. */
object PairingNotifications {
    const val CHANNEL="local-adb-pairing"
    const val ID=700
    const val REPLY="pair-code"
    const val HINT_ID=701
    fun cancel(context:Context){context.getSystemService(NotificationManager::class.java).cancel(ID)}
    /** Setup step 3 (owner 2026-10-22): what to tap, at the top where it does not cover the highlighted row (the old toast
     *  did); without the notification permission only the step screen explains it. Gone when the user comes back. */
    fun developerHint(context:Context){
        val text=app.bluedotoff.UiText.text(context,"‘소프트웨어 정보’를 누른 뒤 ‘빌드번호’를 7번 연속 누르세요.")
        runCatching{context.getSystemService(NotificationManager::class.java).notify(HINT_ID,builder(context).setContentTitle(app.bluedotoff.UiText.text(context,"개발자 옵션을 켜주세요"))
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).build())}
    }
    fun cancelHint(context:Context){context.getSystemService(NotificationManager::class.java).cancel(HINT_ID)}
    fun prepare(context:Context) {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,app.bluedotoff.UiText.text(context,"기기 연결 준비"),NotificationManager.IMPORTANCE_HIGH).apply{
            description=app.bluedotoff.UiText.text(context,"연결 준비 중 설정 화면에서 할 일을 알려줄 때만 써요.")
            enableVibration(false)
        })
    }
    private fun builder(context:Context):Notification.Builder {
        prepare(context)
        val content=PendingIntent.getActivity(context,ID,Intent(context,LocalAdbSetupActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_dot).setVisibility(Notification.VISIBILITY_SECRET)
            .setContentIntent(content).setTimeoutAfter(300000).setAllowSystemGeneratedContextualActions(false)
    }
    private fun post(context:Context,notification:Notification){
        val requested=android.os.SystemClock.elapsedRealtimeNanos()
        val manager=context.getSystemService(NotificationManager::class.java)
        val ok=runCatching{manager.notify(ID,notification)}.isSuccess
        if(app.bluedotoff.BuildConfig.DEBUG){
            val f=java.io.File(context.noBackupFilesDir,"pairing-notification-timing.jsonl")
            if(f.length()>100000)f.writeText("")
            f.appendText(org.json.JSONObject().put("requestedNanos",requested).put("returnedNanos",android.os.SystemClock.elapsedRealtimeNanos())
                .put("posted",ok&&runCatching{manager.activeNotifications.any{it.id==ID}}.getOrDefault(false)).toString()+"\n")
        }
    }
    /** Owner 2026-10-21: the prompt stays down until the user acts. A full-screen request is shown as a pinned heads-up
     *  while the phone is in use (AOSP HeadsUpManagerImpl.isSticky); only then, so it never opens a screen by itself. */
    private fun pinned(context:Context,b:Notification.Builder):Notification.Builder {
        val inUse=context.getSystemService(android.os.PowerManager::class.java).isInteractive&&!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if(inUse)b.setFullScreenIntent(PendingIntent.getActivity(context,ID,Intent(context,LocalAdbSetupActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),true)
        return b
    }
    /** Posted once wireless debugging is on (not before, so it never covers the switch at the top of that screen), with
     *  the reply already there (owner 2026-10-21): no swap when the phone announces its pairing port. */
    fun code(context:Context,generation:Long,sessionId:String,invalid:Boolean=false){
        val intent=Intent(context,PairingReplyReceiver::class.java).putExtra("generation",generation).putExtra("sessionId",sessionId)
        val reply=PendingIntent.getBroadcast(context,(generation and 0x7fffffff).toInt(),intent,PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE)
        val action=Notification.Action.Builder(null,app.bluedotoff.UiText.text(context,"코드 입력"),reply).addRemoteInput(RemoteInput.Builder(REPLY).setLabel(app.bluedotoff.UiText.text(context,"6자리 페어링 코드")).build()).setAllowGeneratedReplies(false).build()
        val b=builder(context).setContentTitle(if(invalid)app.bluedotoff.UiText.text(context,"6자리 숫자를 입력해주세요") else app.bluedotoff.UiText.text(context,"페어링 코드를 입력하세요"))
            .setContentText(app.bluedotoff.UiText.text(context,"무선 디버깅에서 ‘페어링 코드로 기기 페어링’을 누르세요.")).setOnlyAlertOnce(invalid).addAction(action)
        post(context,pinned(context,b).build())
    }
}

/** No Activity trampoline, target operation, shell input, or secret diagnostics. */
class PairingReplyReceiver:android.content.BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        val session=SetupSession.current()
        if(session==null){PairingNotifications.cancel(context);intent.clipData=null;return}
        if(intent.getStringExtra("sessionId")!=session.sessionId){intent.clipData=null;return}
        val text=RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairingNotifications.REPLY)
        if(text!=null)session.submit(text,intent.getLongExtra("generation",-1))
        intent.clipData=null
    }
}
