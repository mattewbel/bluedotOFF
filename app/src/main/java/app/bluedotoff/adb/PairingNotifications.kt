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
    fun cancel(context:Context){context.getSystemService(NotificationManager::class.java).cancel(ID)}
    fun prepare(context:Context) {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,app.bluedotoff.UiText.text(context,"기기 연결 준비"),NotificationManager.IMPORTANCE_HIGH).apply{
            description=app.bluedotoff.UiText.text(context,"설정 화면에서 페어링 코드를 입력할 때만 써요.")
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
    fun guide(context:Context){
        val guide=builder(context).setContentTitle(app.bluedotoff.UiText.text(context,"페어링 코드 화면을 열어주세요"))
            .setContentText(app.bluedotoff.UiText.text(context,"무선 디버깅에서 ‘페어링 코드로 기기 페어링’을 누르세요.")).setOnlyAlertOnce(true).build()
        val n=androidx.core.app.NotificationCompat.Builder(context,guide).setSilent(true).build()
        post(context,n)
    }
    fun code(context:Context,generation:Long,sessionId:String,invalid:Boolean=false){
        val intent=Intent(context,PairingReplyReceiver::class.java).putExtra("generation",generation).putExtra("sessionId",sessionId)
        val reply=PendingIntent.getBroadcast(context,(generation and 0x7fffffff).toInt(),intent,PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE)
        val action=Notification.Action.Builder(null,app.bluedotoff.UiText.text(context,"코드 입력"),reply).addRemoteInput(RemoteInput.Builder(REPLY).setLabel(app.bluedotoff.UiText.text(context,"6자리 페어링 코드")).build()).setAllowGeneratedReplies(false).build()
        val n=builder(context).setContentTitle(if(invalid)app.bluedotoff.UiText.text(context,"6자리 숫자를 입력해주세요") else app.bluedotoff.UiText.text(context,"페어링 코드를 입력하세요"))
            .setContentText(app.bluedotoff.UiText.text(context,"화면의 6자리 코드를 입력하고 전송하면 자동으로 연결해요.")).setOnlyAlertOnce(invalid).addAction(action).build()
        post(context,n)
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
