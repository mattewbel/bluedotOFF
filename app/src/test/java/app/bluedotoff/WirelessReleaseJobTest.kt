package app.bluedotoff

import android.app.Application
import android.app.job.JobScheduler
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.adb.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** B-7: the release after leaving is also a system job, so a swipe-away that kills the process still turns OFF what the app held. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class WirelessReleaseJobTest {
    private val context get()=ApplicationProvider.getApplicationContext<Application>()
    private val wifiDebugging get()=Settings.Global.getInt(context.contentResolver,"adb_wifi_enabled",0)
    private fun held(on:Boolean,consent:Boolean=true){
        context.getSharedPreferences("wireless-management",0).edit().putBoolean("enabled",consent).putBoolean("heldOn",on).commit()
        shadowOf(context).grantPermissions(WirelessControls.PERMISSION)
        Settings.Global.putInt(context.contentResolver,"adb_wifi_enabled",1)
    }
    @After fun cleanup(){
        while(ForegroundState.active)ForegroundState.stopped()
        context.getSharedPreferences("wireless-management",0).edit().clear().commit()
        WirelessReleaseJob.cancel(context)
    }
    @Test fun leavingSchedulesADelayedJobAndComingBackCancelsIt(){
        WirelessReleaseJob.schedule(context)
        val job=context.getSystemService(JobScheduler::class.java).allPendingJobs.single()
        assertEquals(WirelessReleaseJob.ID,job.id)
        assertEquals(WirelessReleaseJob.DELAY_MS,job.minLatencyMillis)
        assertEquals(WirelessReleaseJob::class.java.name,job.service.className)
        assertFalse(job.isPersisted)
        WirelessReleaseJob.cancel(context)
        assertTrue(context.getSystemService(JobScheduler::class.java).allPendingJobs.isEmpty())
    }
    @Test fun jobTurnsOffOnlyWhatTheAppHeldAndOnlyWhenNoScreenIsVisible(){
        held(true);ForegroundState.started()
        WirelessWork.releaseIfHidden(context)
        assertEquals("a visible screen keeps it on",1,wifiDebugging);assertTrue(WirelessWork.held(context))
        ForegroundState.stopped()
        WirelessWork.releaseIfHidden(context)
        assertEquals(0,wifiDebugging);assertFalse(WirelessWork.held(context))
    }
    @Test fun jobLeavesAUserMadeConnectionOrOneWithoutConsentAlone(){
        held(false)
        WirelessWork.releaseIfHidden(context)
        assertEquals("not held by the app",1,wifiDebugging)
        held(true,consent=false)
        WirelessWork.releaseIfHidden(context)
        assertEquals("automatic management off",1,wifiDebugging)
    }
    @Test fun manifestBindsTheJobOnlyForTheSystem(){
        val info=context.packageManager.getServiceInfo(android.content.ComponentName(context,WirelessReleaseJob::class.java),0)
        assertFalse(info.exported);assertEquals("android.permission.BIND_JOB_SERVICE",info.permission)
    }
}
