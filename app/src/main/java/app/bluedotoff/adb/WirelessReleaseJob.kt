package app.bluedotoff.adb

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** B-7: swiping bluedotOFF away from recents within the 2 s grace kills the process before the in-app release runs, which
 *  left wireless debugging on (Fold7, 2026-10-17). A system job survives the process; any bluedotOFF screen start cancels it.
 *  It runs the same release as leaving normally: only a hold this app made, never while a screen is visible or a lease runs. */
class WirelessReleaseJob:JobService() {
    companion object {
        const val ID=0x7b07
        const val DELAY_MS=2000L
        fun schedule(context:Context)=runCatching{
            context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(ID,ComponentName(context,WirelessReleaseJob::class.java))
                .setMinimumLatency(DELAY_MS).build())
        }
        fun cancel(context:Context)=runCatching{context.getSystemService(JobScheduler::class.java).cancel(ID)}
    }
    override fun onStartJob(params:JobParameters):Boolean {
        Thread{runCatching{WirelessWork.releaseIfHidden(applicationContext)};jobFinished(params,false)}.start()
        return true
    }
    override fun onStopJob(params:JobParameters)=false
}
