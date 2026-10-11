package app.bluedotoff.adb

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Build
import app.bluedotoff.ForegroundState
import app.bluedotoff.R

/** Keeps NSD delivery alive only during a user-started pairing lease. No ADB or setting writes. */
class PairingLifetimeService:Service() {
    companion object {
        internal const val LIMIT_MS=150000L // Below shortService's approximately three-minute OS limit.
        internal const val ID=701
        private const val CHANNEL="pairing-lifetime"
        private var requested=false
        internal val isRequested get()=requested
        private var epoch=0L
        internal fun ensure(context:Context):Boolean {
            if(requested)return true
            if(Build.VERSION.SDK_INT<34||!ForegroundState.active||SetupSession.current()==null)return false
            return runCatching {
                requested=true
                context.startForegroundService(Intent(context,PairingLifetimeService::class.java).putExtra("lease",++epoch))
                true
            }.getOrElse{requested=false;false}
        }
        internal fun stop(context:Context){requested=false;epoch++;context.stopService(Intent(context,PairingLifetimeService::class.java))}
    }
    private val handler=Handler(Looper.getMainLooper())
    private var armed=false
    private var lease=-1L
    private val timeout=Runnable{SetupSession.current()?.pairingLifetimeExpired();finish()}
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(Build.VERSION.SDK_INT<34){stopSelf(startId);return START_NOT_STICKY}
        val received=intent?.getLongExtra("lease",-1L)?:-1L
        if(!requested||received!=epoch||SetupSession.current()==null){stopSelf(startId);return START_NOT_STICKY}
        if(!armed){
            armed=true
            lease=received
            val manager=getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL,app.bluedotoff.UiText.text(this,"페어링 진행"),NotificationManager.IMPORTANCE_LOW))
            val open=PendingIntent.getActivity(this,ID,Intent(this,LocalAdbSetupActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
            val notice=Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_dot)
                .setContentTitle(app.bluedotoff.UiText.text(this,"bluedotOFF 연결 준비 중"))
                .setContentText(app.bluedotoff.UiText.text(this,"설정에서 페어링 코드를 입력하세요. 잠시 후 자동으로 사라져요."))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_SECRET).build()
            startForeground(ID,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            handler.postDelayed(timeout,LIMIT_MS)
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId:Int){timeout.run()}
    override fun onTimeout(startId:Int,fgsType:Int){timeout.run()}
    private fun finish(){if(lease==epoch)requested=false;handler.removeCallbacksAndMessages(null);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    override fun onDestroy(){if(lease==epoch)requested=false;handler.removeCallbacksAndMessages(null);stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()}
}
