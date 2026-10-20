package app.bluedotoff

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import app.bluedotoff.adb.WatchClient
import app.bluedotoff.adb.WirelessControls
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** R-1 (1.2.0): after a restart, once Wi-Fi is up, start the background watch and re-hide what the
 *  restart unhid, without opening the app. Only for users who turned on background watch and automatic wireless debugging
 *  management (they already allowed both to run without the app open). Wireless debugging is on only while the watch
 *  starts, as when the app is opened; the re-hide runs through the watch's connection with the app-open checks and windows.
 *  If anything is missing (no Wi-Fi, Auto Blocker, USB debugging off) the restart notices stay and opening the app works. */
class RestartRecoveryJob:JobService() {
    companion object {
        const val ID=0x7b08
        private const val MAX_TRIES=3
        fun eligible(c:Context)=BackgroundWatch.enabled(c)&&WatchClient.usbEnabled(c)&&WirelessControls(c).let{it.consent&&it.granted}&&
            c.getSharedPreferences("ui-state",Context.MODE_PRIVATE).getBoolean("autoReapply",true)
        fun schedule(c:Context)=runCatching{
            c.getSharedPreferences("ui-state",Context.MODE_PRIVATE).edit().putInt("restartRecoveryTries",0).apply()
            val scheduler=c.getSystemService(JobScheduler::class.java)
            fun job(expedited:Boolean)=JobInfo.Builder(ID,ComponentName(c,RestartRecoveryJob::class.java)).setRequiredNetwork(wifi)
                .apply{if(expedited&&android.os.Build.VERSION.SDK_INT>=31)setExpedited(true)}.build()
            // Expedited: runs ahead of other apps' boot jobs (Fold7 reboot 8 waited 4 min in the queue); a regular job if refused.
            if(runCatching{scheduler.schedule(job(true))}.getOrDefault(JobScheduler.RESULT_FAILURE)!=JobScheduler.RESULT_SUCCESS)scheduler.schedule(job(false))
        }
        /** Any Wi-Fi, metered or not; a VPN on top of Wi-Fi (e.g. a DNS filter, as on the owner's Fold7) has no NOT_VPN. */
        internal val wifi get()=android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        /** What Home counts as open: apps still to re-apply plus registered apps that need the user's check (writes an
         *  interrupted run left unconfirmed, safe failures, locks). Only the first are re-applied here (Codex review). */
        internal fun left(entries:List<app.bluedotoff.core.Entry>)=UiModel(entries=entries).let{it.reapplyCount+it.otherCheckCount}
    }
    // One stop token per run: the system may stop a run and start the next one on the same service object (Fold7,
    // 2026-10-19). Start and stop arrive as separate JobParameters objects (Codex review), so the stop goes to the run
    // started last: the system never starts this job again before the running one was stopped.
    private val runs=java.util.concurrent.ConcurrentHashMap.newKeySet<java.util.concurrent.atomic.AtomicBoolean>()
    @Volatile private var current:java.util.concurrent.atomic.AtomicBoolean?=null
    override fun onStartJob(params:JobParameters):Boolean {
        val c=applicationContext
        if(!eligible(c))return false
        val stop=java.util.concurrent.atomic.AtomicBoolean().also{runs+=it;current=it}
        Thread{
            val retry=runCatching{recover(c){!stop.get()}}.getOrDefault(true)
            val prefs=c.getSharedPreferences("ui-state",Context.MODE_PRIVATE)
            // A stopped run or a moment without Wi-Fi is not a failed try.
            val tries=prefs.getInt("restartRecoveryTries",0)+(if(stop.get()||!WifiConnection.connected(c))0 else 1);prefs.edit().putInt("restartRecoveryTries",tries).apply()
            WatchLog.event("state","restartRecovery retry=$retry tries=$tries")
            runs.remove(stop)
            if(!stop.get())jobFinished(params,retry&&tries<MAX_TRIES&&eligible(c))
        }.start()
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {
        current?.set(true)
        WatchLog.event("state","restartRecovery systemStop reason=${if(android.os.Build.VERSION.SDK_INT>=31)params.stopReason else -1}")
        return true
    }
    /** False when done (or when an open screen does the same work itself); true to try again later. */
    private fun recover(c:Context,notStopped:()->Boolean):Boolean {
        // Settings turned off during a run (e.g. "앱을 열 때 자동 재적용") stop it before the next app, like a system stop (Codex R3).
        val canStart={notStopped()&&eligible(c)}
        if(ForegroundState.active)return false
        // The system starts this run while the stopped one is still starting the watch; let that finish (Fold7, 2026-10-19).
        val othersUntil=android.os.SystemClock.elapsedRealtime()+60_000
        while(runs.size>1&&canStart()&&android.os.SystemClock.elapsedRealtime()<othersUntil)Thread.sleep(200)
        if(!canStart())return true
        if(!WatchClient.ensureStarted(c,afterRestart=true))return true
        if(!canStart())return true // Stopped while the watch started: the next run re-applies.
        WatchLog.event("state","restartRecovery watchAvailable=true")
        // A stopped earlier run may still finish the apps it started; wait for it, then continue with what is left (Fold7, 2026-10-19).
        val waitUntil=android.os.SystemClock.elapsedRealtime()+9*60_000
        while(BatchRuntime.state.value.busy){
            if(!canStart()||android.os.SystemClock.elapsedRealtime()>waitUntil)return true
            Thread.sleep(500)
        }
        val entries=JournalStore(c).entries()
        val model=UiModel(entries=entries)
        val targets=model.reapplyTargetPackages
        if(targets.isEmpty()){
            val unresolved=left(entries)
            WatchLog.event("state","restartRecovery targets=0 left=$unresolved")
            // Only unconfirmed writes (e.g. power lost mid-write): never re-written here, but the user is told (Codex review).
            if(unresolved>0&&canStart())ReapplyNotification.show(c,null,checkInApp=true)
            return false
        }
        val groups=entries.map{it.groupIdentities.map{p->p.packageName}}.filter{it.isNotEmpty()}.distinct()
        val groupMap=groups.flatMap{g->g.map{it to g}}.toMap()
        val done=CountDownLatch(1)
        var succeeded=emptySet<String>()
        val started=BatchRuntime.start(targets.size,hide=true,auto=true){report->
            try{BatchPipeline.run(c,targets,groupMap,hide=true,show=false,auto=true,beforeUninstall=false,reapplying=emptySet(),autoSource=ReapplySource.RESTART,
                canStart=canStart,background=true,report=report).also{o->succeeded=o.results.filter{it.success}.map{it.packageName}.toSet()}}
            finally{done.countDown()}
        }
        if(!started)return false // A screen batch is already running.
        done.await(9,TimeUnit.MINUTES)
        val remaining=left(JournalStore(c).entries())
        WatchLog.event("state","restartRecovery targets=${targets.size} succeeded=${succeeded.size} left=$remaining")
        if(succeeded.isNotEmpty())ReapplyNotification.clear(c,succeeded,remaining==0)
        // A stopped run leaves the rest to the next run, which tells the user if anything is still left (Fold7 reboot 7).
        if(remaining>0&&canStart())ReapplyNotification.show(c,null,checkInApp=true)
        LifecycleEvents.changed()
        return false
    }
}
