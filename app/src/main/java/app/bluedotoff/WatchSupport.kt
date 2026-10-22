package app.bluedotoff

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.io.File

/* "백그라운드 감시" (1.1.0): the parts that do not involve the privileged helper - the user's switch and consent,
 * and the stop / USB-back notices. The watching itself is connected
 * separately; it reports through [BackgroundWatch.running] and [WatchNotification.stopped]. */

object BackgroundWatch {
    /** Shown before the first switch-on, in Settings and in setup step 9. */
    val RISKS=listOf("USB 디버깅을 항상 켜 둬야 해요.","일부 은행·결제 앱이 열리지 않을 수 있어요.","배터리를 조금 더 쓸 수 있어요.","USB·무선 디버깅이 모두 꺼지면 감시가 멈춰요.")
    private fun prefs(c:Context)=c.getSharedPreferences("ui-state",Context.MODE_PRIVATE)
    fun enabled(c:Context)=prefs(c).getBoolean("backgroundWatch",false)
    /** The risk notice was confirmed once (after its 5-second wait); later switch-ons do not ask again. */
    fun acknowledged(c:Context)=prefs(c).getBoolean("backgroundWatchAcknowledged",false)
    fun set(c:Context,on:Boolean){
        val edit=prefs(c).edit().putBoolean("backgroundWatch",on);if(on)edit.putBoolean("backgroundWatchAcknowledged",true) else edit.putBoolean("watchRunning",false);check(edit.commit())
    }
    /** Watching (re)started: the next stop (and USB coming back) may be announced again. */
    fun running(c:Context){prefs(c).edit().remove("watchStopNotified").remove("watchUsbBackNotified").putBoolean("watchRunning",true).apply();c.getSystemService(NotificationManager::class.java).cancel(4201)}
    /** Process-wide: when USB debugging comes back while watching is stopped, say how to start it again (once per stop). */
    fun observeUsb(c:Context){
        val ctx=c.applicationContext
        ctx.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),false,object:ContentObserver(Handler(Looper.getMainLooper())){
            override fun onChange(selfChange:Boolean){
                if(Settings.Global.getInt(ctx.contentResolver,Settings.Global.ADB_ENABLED,0)==1&&enabled(ctx)&&!app.bluedotoff.adb.WatchClient.available(ctx))WatchNotification.usbBack(ctx)
            }})
    }
}

object WatchNotification {
    const val CHANNEL="watch"
    private const val ID=4201
    enum class Reason(val message:String){
        BOOT("휴대폰이 다시 시작되어 백그라운드 감시가 멈췄어요."),
        USB_DEBUGGING_OFF("USB 디버깅이 꺼져서 백그라운드 감시가 멈췄어요."),
        STOPPED("백그라운드 감시가 멈췄어요.")
    }
    /** What the user does to start watching again. Automatic management turns wireless debugging on by itself;
     *  without it the user turns it on too. Pairing is not needed again. */
    fun advice(usbOn:Boolean,autoManaged:Boolean,pcOnly:Boolean=false)=when{
        pcOnly&&!usbOn->"USB 디버깅을 다시 켜고, PC에서 bluedotOFF 시작 명령을 다시 실행해주세요."
        pcOnly->"다시 켜려면 PC에서 bluedotOFF 시작 명령을 다시 실행해주세요."
        !usbOn&&autoManaged->"USB 디버깅을 다시 켜고, Wi-Fi에 연결된 상태에서 bluedotOFF를 열어주세요."
        !usbOn->"USB 디버깅을 다시 켜고, Wi-Fi에 연결해 무선 디버깅을 켠 뒤 bluedotOFF를 열어주세요."
        autoManaged->"다시 켜려면 Wi-Fi에 연결된 상태에서 bluedotOFF를 열어주세요."
        else->"다시 켜려면 Wi-Fi에 연결해 무선 디버깅을 켠 뒤 bluedotOFF를 열어주세요."
    }
    private fun autoManaged(c:Context)=app.bluedotoff.adb.WirelessControls(c).let{it.consent&&it.granted}
    private fun usbOn(c:Context)=Settings.Global.getInt(c.contentResolver,Settings.Global.ADB_ENABLED,0)==1
    /** Once per stop: later reports stay quiet until watching runs again ([BackgroundWatch.running]). */
    fun stopped(c:Context,reason:Reason){
        c.getSharedPreferences("ui-state",Context.MODE_PRIVATE).edit().putBoolean("watchRunning",false).apply()
        if(!BackgroundWatch.enabled(c))return
        // R-1: the after-restart job starts it again on Wi-Fi; the re-apply notice already says what to do if it cannot.
        if(reason==Reason.BOOT&&RestartRecoveryJob.eligible(c))return
        val prefs=c.getSharedPreferences("ui-state",Context.MODE_PRIVATE)
        if(prefs.getString("watchStopNotified",null)!=null)return
        prefs.edit().putString("watchStopNotified",reason.name).apply()
        WatchLog.event("state","watchStopNotice reason=${reason.name}")
        val usb=usbOn(c)
        post(c,"백그라운드 감시가 멈췄어요",UiText.text(c,reason.message)+" "+UiText.text(c,advice(usb,autoManaged(c),app.bluedotoff.adb.PcStart.chosen(c))),usbAction=!usb)
    }
    /** USB debugging is on again while watching is stopped (the app is not open; an open app starts it by itself). */
    fun usbBack(c:Context){
        if(ForegroundState.active)return
        val prefs=c.getSharedPreferences("ui-state",Context.MODE_PRIVATE)
        if(prefs.getBoolean("watchUsbBackNotified",false))return
        prefs.edit().putBoolean("watchUsbBackNotified",true).apply()
        post(c,"USB 디버깅이 켜졌어요",UiText.text(c,if(app.bluedotoff.adb.PcStart.chosen(c))"PC에서 bluedotOFF 시작 명령을 실행하면 백그라운드 감시를 다시 시작해요." else if(autoManaged(c))"Wi-Fi에 연결된 상태에서 bluedotOFF를 열면 백그라운드 감시를 다시 시작해요." else "Wi-Fi에 연결해 무선 디버깅을 켠 뒤 bluedotOFF를 열면 백그라운드 감시를 다시 시작해요."),usbAction=false)
    }
    private fun post(c:Context,title:String,text:String,usbAction:Boolean){
        if(android.os.Build.VERSION.SDK_INT>=33&&c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val manager=c.getSystemService(NotificationManager::class.java)
        if(!manager.areNotificationsEnabled())return
        manager.createNotificationChannel(NotificationChannel(CHANNEL,UiText.text(c,"백그라운드 감시"),NotificationManager.IMPORTANCE_DEFAULT).apply{description=UiText.text(c,"백그라운드 감시가 멈췄을 때 알려드려요.")})
        val open=PendingIntent.getActivity(c,ID,Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder=Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_dot).setContentTitle(UiText.text(c,title)).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true)
        // Straight to Developer options, where USB debugging is switched on.
        if(usbAction)builder.addAction(Notification.Action.Builder(null,UiText.text(c,"USB 디버깅 켜기"),
            PendingIntent.getActivity(c,ID+1,Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
        try{manager.notify(ID,builder.build())}catch(_:SecurityException){/* Notifications are optional. */}
    }
}

/** Watch diagnostics go to logcat only (I/bluedotOFF, no package names); nothing is stored or collected. */
internal object WatchLog { fun event(kind:String,message:String){android.util.Log.i("bluedotOFF","watch $kind: $message")} }

/** Earlier 1.1.0 test builds kept a local test log. On first start without it: let the reapply history import its automatic
 *  results (once), then delete the log, its state and its sampling alarm. */
internal object LegacyTestLog {
    fun cleanup(c:Context){
        if(!File(c.filesDir,"test-log.txt").exists())return
        Thread{runCatching{
            PendingIntent.getBroadcast(c,4301,Intent().setClassName(c,"app.bluedotoff.TestLogReceiver"),PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                ?.let{c.getSystemService(AlarmManager::class.java).cancel(it);it.cancel()}
            ReapplyHistoryStore(c).entries()
            File(c.filesDir,"test-log.txt").delete();c.deleteSharedPreferences("test-log")
        }}.start()
    }
}
