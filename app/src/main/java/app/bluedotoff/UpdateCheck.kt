package app.bluedotoff

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import app.bluedotoff.core.UpdatePolicy
import org.json.JSONObject

/** Opt-in update check (1.1.2, owner request; off by default). The only internet use of the app: one GET of the public
 *  GitHub "latest release" (pre-releases and drafts are never "latest"), no identifiers (fixed User-Agent), 10 s timeouts.
 *  Never downloads or installs anything; a newer version only opens its release page. No internet or any failure -> silent. */
object UpdateCheck {
    const val API="https://api.github.com/repos/mattewbel/bluedotOFF/releases/latest"
    private const val PAGE="https://github.com/mattewbel/bluedotOFF/releases/tag/"
    private const val TIMEOUT_MS=10_000
    private const val MAX_BYTES=1 shl 20
    const val CHANNEL="update"
    private const val NOTIFICATION_ID=4401
    data class Release(val tag:String) { val page get()=PAGE+tag }
    private fun prefs(c:Context)=c.getSharedPreferences("update-check",Context.MODE_PRIVATE)
    fun enabled(c:Context)=prefs(c).getBoolean("enabled",false)
    /** The one-time question (default off) was answered. */
    fun asked(c:Context)=prefs(c).getBoolean("asked",false)
    fun current()=BuildConfig.VERSION_NAME
    fun set(c:Context,on:Boolean){
        check(prefs(c).edit().putBoolean("enabled",on).putBoolean("asked",true).commit())
        if(on)UpdateCheckJob.schedule(c) else UpdateCheckJob.cancel(c)
    }
    /** Last version seen on GitHub (for the Settings line), or null before the first successful check. */
    fun latest(c:Context)=prefs(c).getString("latestTag",null)?.takeIf{UpdatePolicy.parse(it)!=null}
    /** A newer version that was not shown, notified or skipped yet. */
    fun pending(c:Context):Release?{
        val p=prefs(c);val latest=latest(c)
        return if(enabled(c)&&UpdatePolicy.announce(latest,current(),p.getString("notifiedTag",null),p.getString("skippedTag",null)))Release(latest!!) else null
    }
    fun markNotified(c:Context,tag:String){prefs(c).edit().putString("notifiedTag",tag).apply()}
    fun skip(c:Context,tag:String){prefs(c).edit().putString("skippedTag",tag).putString("notifiedTag",tag).apply()}
    private fun online(c:Context):Boolean=runCatching{
        val m=c.getSystemService(ConnectivityManager::class.java)
        val caps=m.getNetworkCapabilities(m.activeNetwork?:return false)?:return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)
    /** Blocking; call off the main thread. Automatic checks run once a day at most ([force] = the user's "지금 확인").
     *  Returns the latest release, or null when the check is off, offline or failed (nothing is shown for that). */
    private val requestLock=Any()
    /** The GitHub request; replaced only by tests (no network in unit tests). */
    internal var fetcher:()->String?={fetch()}
    fun check(c:Context,force:Boolean=false):Release?{
        if(!enabled(c))return null
        val p=prefs(c);val asked=System.currentTimeMillis()
        // One request at a time (app open, the daily job and "지금 확인" can overlap). The automatic attempt is recorded
        // before the request, so a failure or timeout also waits a day; no internet records nothing (review 2026-10-17).
        synchronized(requestLock){
            // Re-read after waiting: the user may have switched the check off meanwhile (review round 2).
            if(!enabled(c))return null
            if(force&&p.getLong("lastCheck",0L)>=asked)return latest(c)?.let{Release(it)}
            if(!force&&!UpdatePolicy.due(System.currentTimeMillis(),p.getLong("lastAttempt",0L)))return latest(c)?.let{Release(it)}
            if(!online(c))return null
            p.edit().putLong("lastAttempt",System.currentTimeMillis()).commit()
            val tag=runCatching{fetcher()}.getOrNull()?:return null
            p.edit().putString("latestTag",tag).putLong("lastCheck",System.currentTimeMillis()).apply()
            return Release(tag)
        }
    }
    private fun fetch():String?{
        val connection=java.net.URL(API).openConnection() as javax.net.ssl.HttpsURLConnection
        try{
            connection.connectTimeout=TIMEOUT_MS;connection.readTimeout=TIMEOUT_MS
            connection.instanceFollowRedirects=false;connection.useCaches=false
            connection.setRequestProperty("Accept","application/vnd.github+json")
            // Replaces the default User-Agent, which names the phone model.
            connection.setRequestProperty("User-Agent","bluedotOFF")
            if(connection.responseCode!=200)return null
            val body=connection.inputStream.use{input->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true){val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n);if(out.size()>MAX_BYTES)return null}
                out.toString("UTF-8")
            }
            val j=JSONObject(body)
            if(j.optBoolean("draft")||j.optBoolean("prerelease"))return null
            return j.optString("tag_name").takeIf{UpdatePolicy.parse(it)!=null}
        }finally{connection.disconnect()}
    }
    /** System notification for background-watch users (once per version). False if notifications are off. */
    fun notify(c:Context,release:Release):Boolean{
        if(android.os.Build.VERSION.SDK_INT>=33&&c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return false
        val manager=c.getSystemService(NotificationManager::class.java)
        if(!manager.areNotificationsEnabled())return false
        manager.createNotificationChannel(NotificationChannel(CHANNEL,UiText.text(c,"업데이트 알림"),NotificationManager.IMPORTANCE_DEFAULT).apply{description=UiText.text(c,"새 버전이 나왔을 때 한 번만 알려드려요.")})
        if(manager.getNotificationChannel(CHANNEL)?.importance==NotificationManager.IMPORTANCE_NONE)return false
        val page=PendingIntent.getActivity(c,NOTIFICATION_ID,Intent(Intent.ACTION_VIEW,Uri.parse(release.page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text=UiText.text(c,"bluedotOFF ${release.tag}이 나왔어요. 눌러서 GitHub 출시 페이지를 열어요.")
        val notification=Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_dot).setContentTitle(UiText.text(c,"새 버전이 있어요"))
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(page).setAutoCancel(true).build()
        return try{manager.notify(NOTIFICATION_ID,notification);true}catch(_:SecurityException){false}
    }
    fun open(c:Context,release:Release)=runCatching{c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(release.page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}.isSuccess
}

/** Daily background check, only for background-watch (USB debugging) users, who may not open the app. It waits for any
 *  network; others are checked when they open bluedotOFF. */
class UpdateCheckJob:JobService(){
    companion object {
        const val ID=0x4401
        fun schedule(c:Context)=runCatching{
            val scheduler=c.getSystemService(JobScheduler::class.java)
            if(scheduler.getPendingJob(ID)==null)scheduler.schedule(JobInfo.Builder(ID,ComponentName(c,UpdateCheckJob::class.java))
                .setPeriodic(UpdatePolicy.INTERVAL_MS).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build())
        }
        fun cancel(c:Context)=runCatching{c.getSystemService(JobScheduler::class.java).cancel(ID)}
        /** One background round; returns true when a notification was posted. */
        fun runOnce(c:Context):Boolean{
            if(!UpdateCheck.enabled(c)){cancel(c);return false}
            if(!BackgroundWatch.enabled(c))return false
            UpdateCheck.check(c)
            val release=UpdateCheck.pending(c)?:return false
            // Not marked when notifications are off: the app shows it once when opened instead.
            if(!UpdateCheck.notify(c,release))return false
            UpdateCheck.markNotified(c,release.tag);return true
        }
    }
    override fun onStartJob(params:JobParameters):Boolean{
        Thread{runCatching{runOnce(applicationContext)};jobFinished(params,false)}.start()
        return true
    }
    override fun onStopJob(params:JobParameters)=false
}
