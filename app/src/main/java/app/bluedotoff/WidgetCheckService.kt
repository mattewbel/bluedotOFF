package app.bluedotoff

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import app.bluedotoff.adb.AdbHostIdentity
import app.bluedotoff.adb.WatchClient
import app.bluedotoff.adb.WirelessControls
import app.bluedotoff.adb.WirelessWork
import app.bluedotoff.core.Reconcile
import app.bluedotoff.core.Snapshot
import java.util.concurrent.atomic.AtomicBoolean

/** Widget ↻ without the background watch (owner 2026-11-06; step 1 = read only): the tap turns wireless debugging on as
 *  opening the app does, reads every registered app with Home's check, and the lease turns it off again (kept on only if a
 *  bluedotOFF screen opened meanwhile). Android lets a widget tap start this foreground service; it runs right after the
 *  tap or not at all (never later, e.g. once Wi-Fi comes back), and once at a time. A request is checked again once it
 *  holds the connection lock and before each app (it may have waited for another task), and the service's timeout or end
 *  cancels it (Codex review 2026-11-06). */
class WidgetCheckService:Service() {
    companion object {
        private const val ID=702
        private const val CHANNEL="widget-check"
        private const val MAX_DELAY_MS=10_000L
        private val running=AtomicBoolean()
        /** Automatic management (the app may turn wireless debugging on and off) and a pairing, without the background watch:
         *  the watch has its own refresh, and a stopped watch keeps showing "감시 꺼짐" so its user opens the app to restart it. */
        fun eligible(c:Context)=Build.VERSION.SDK_INT>=34&&!BackgroundWatch.enabled(c)&&!WatchClient.available(c)&&WirelessControls(c).let{it.consent&&it.granted}&&
            c.getSharedPreferences("local-adb",0).getString("pairedGuid",null)!=null&&AdbHostIdentity(c).exists
        /** False when Android refuses the start; the widget then only redraws. */
        fun start(c:Context)=runCatching{c.startForegroundService(Intent(c,WidgetCheckService::class.java).putExtra("at",SystemClock.elapsedRealtime()));true}.getOrDefault(false)
        /** Home's read and journal update of the registered apps. A cancel stops between apps and returns false without
         *  saving the partial reads: the helper still ends in order, so the lease closes and turns wireless debugging off
         *  under its usual rules (an exception would leave it on; Codex follow-up 2026-11-06). */
        internal fun readRegistered(c:Context,backend:BinderBackend,cancelled:()->Boolean):Boolean {
            val firmware=backend.verify();val boot=BootSession.current(c);val store=JournalStore(c)
            val current=LinkedHashMap<String,Snapshot?>()
            for(e in store.entries()){
                if(cancelled())return false
                current[e.identity.packageName]=try{backend.read(e.identity.packageName)}catch(_:PackageManager.NameNotFoundException){null}
            }
            if(cancelled())return false
            if(current.isNotEmpty())check(store.transform{e->if(e.identity.packageName in current)Reconcile.read(e,current[e.identity.packageName],firmware.key,boot,current){backend.read(e.identity.packageName)} else e}){"복원 기록을 저장하지 못해서 바꾸지 않았어요."}
            return true
        }
    }
    /** Set by the timeout or the end of this service: the running check stops at its next step. */
    private val cancelled=AtomicBoolean()
    private var ownsWork=false // main thread only; a replacement service never owns the old cancelled worker
    private val main=Handler(Looper.getMainLooper())
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val c=applicationContext
        val at=intent?.getLongExtra("at",0L)?:0L
        val fresh={SystemClock.elapsedRealtime()-at<MAX_DELAY_MS}
        if(!runCatching{foreground()}.isSuccess||!fresh()||cancelled.get()){stopUnlessWorking();return START_NOT_STICKY}
        // A second start while a check runs is dropped; stopping here would end the service under the running check.
        if(!running.compareAndSet(false,true)){stopUnlessWorking();return START_NOT_STICKY}
        ownsWork=true
        cancelled.set(false)
        Thread{
            var notice:String?=null // set only by a lease that really ran
            var failed=false
            val checked=runCatching{check(c,fresh){notice=it}}.onFailure{failed=true;android.util.Log.w("bluedotOFF","widget check failed: ${it.javaClass.simpleName}: ${it.message?.take(160)}")}.getOrDefault(false)
            // Finish on the lifecycle thread before admitting another request: an old completion must not clear a new
            // check's progress or stop its service. The guard remains held while cancelled work drains.
            main.post{
                try{
                    StatusWidget.finished(c,checked&&!cancelled.get(),notice)
                    // A failed check (Auto Blocker, an expired pairing, no connection) is not a silent tap; the reason is only logged, so it asks to check again in the app.
                    if(failed&&!cancelled.get()&&!ForegroundState.active)StatusWidget.toast(c,"확인하지 못했어요. bluedotOFF를 열어 다시 확인해주세요.")
                    cancelled.set(true);stop()
                }
                finally{running.set(false)}
            }
        }.start()
        return START_NOT_STICKY
    }
    /** True only when every registered app was read. An open bluedotOFF screen runs its own check, so this one stops. */
    /** [onCleanup]: the lease's "turn wireless debugging off yourself" notice, shown on the widget. */
    private fun check(c:Context,fresh:()->Boolean,onCleanup:(String)->Unit):Boolean {
        val wanted={!cancelled.get()&&!ForegroundState.active&&eligible(c)}
        if(!wanted()||!WifiConnection.connected(c))return false
        val started=SystemClock.elapsedRealtime()
        val read=WirelessWork.run(c,userAction=true,hold=true,noScreen=true,onCleanup=onCleanup,stillWanted={wanted()&&fresh()}){readRegistered(c,it,cancelled::get)}
        android.util.Log.i("bluedotOFF","widget check: ${SystemClock.elapsedRealtime()-started}ms read=$read")
        LifecycleEvents.changed()
        return read
    }
    private fun foreground() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,UiText.text(this,"위젯 확인"),NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,ID,Intent(this,MainActivity::class.java).setAction(Intent.ACTION_MAIN),PendingIntent.FLAG_IMMUTABLE)
        startForeground(ID,Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_dot).setContentTitle(UiText.text(this,"앱 확인 중…"))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
    }
    // shortService ends after about three minutes; the check normally takes seconds. The widget stops showing progress now;
    // the cancelled check ends at its next step and its lease closes the connection as usual.
    override fun onTimeout(startId:Int,fgsType:Int){cancelled.set(true);StatusWidget.finished(applicationContext,false);stop()}
    override fun onDestroy(){cancelled.set(true);super.onDestroy()}
    private fun stopUnlessWorking(){if(!ownsWork||cancelled.get()){StatusWidget.finished(applicationContext,false);stop()}}
    private fun stop(){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
}
