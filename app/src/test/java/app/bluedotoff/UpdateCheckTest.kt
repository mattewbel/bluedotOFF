package app.bluedotoff

import android.app.Application
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Opt-in update check: off by default, once per version, only the release page is opened. No network in these tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class UpdateCheckTest {
    private val context get()=ApplicationProvider.getApplicationContext<Application>()
    private val prefs get()=context.getSharedPreferences("update-check",0)
    private val jobs get()=context.getSystemService(JobScheduler::class.java).allPendingJobs
    @After fun cleanup(){
        prefs.edit().clear().commit();context.getSharedPreferences("ui-state",0).edit().clear().commit()
        UpdateCheckJob.cancel(context)
    }
    private fun seen(tag:String){prefs.edit().putString("latestTag",tag).putLong("lastCheck",System.currentTimeMillis()).commit()}
    @Test fun offByDefaultAndNeverChecksWhenOff(){
        assertFalse(UpdateCheck.enabled(context));assertFalse(UpdateCheck.asked(context))
        assertNull(UpdateCheck.check(context,force=true))
        seen("99.0.0");assertNull("off: nothing is shown",UpdateCheck.pending(context))
        assertTrue(jobs.isEmpty())
    }
    @Test fun switchingOnSchedulesOneDailyJobThatNeedsANetworkAndOffCancelsIt(){
        UpdateCheck.set(context,true);UpdateCheck.set(context,true)
        val job=jobs.single()
        assertEquals(UpdateCheckJob.ID,job.id);assertTrue(job.isPeriodic);assertEquals(24L*60*60*1000,job.intervalMillis)
        assertEquals(JobInfo.NETWORK_TYPE_ANY,job.networkType);assertEquals(UpdateCheckJob::class.java.name,job.service.className)
        assertTrue(UpdateCheck.asked(context))
        UpdateCheck.set(context,false)
        assertTrue(jobs.isEmpty());assertTrue("the answer is remembered",UpdateCheck.asked(context))
    }
    @Test fun aNewerVersionIsShownOnceAndSkipIsKept(){
        UpdateCheck.set(context,true)
        seen(UpdateCheck.current());assertNull(UpdateCheck.pending(context))
        seen("99.0.0");assertEquals("99.0.0",UpdateCheck.pending(context)?.tag)
        UpdateCheck.markNotified(context,"99.0.0");assertNull(UpdateCheck.pending(context))
        seen("99.0.1");UpdateCheck.skip(context,"99.0.1");assertNull(UpdateCheck.pending(context))
        assertEquals("99.0.1",UpdateCheck.latest(context))
    }
    @Test fun aRecentCheckIsNotRepeated(){
        UpdateCheck.set(context,true);seen("99.0.0")
        assertEquals("within a day the stored result is used","99.0.0",UpdateCheck.check(context)?.tag)
    }
    @Test fun offlineIsSilent(){
        UpdateCheck.set(context,true)
        shadowOf(context.getSystemService(android.net.ConnectivityManager::class.java)).setDefaultNetworkActive(false)
        shadowOf(context.getSystemService(android.net.ConnectivityManager::class.java)).setActiveNetworkInfo(null)
        assertNull(UpdateCheck.check(context,force=true))
        assertEquals("no attempt is recorded",0L,prefs.getLong("lastCheck",0L))
    }
    @Test fun backgroundRoundIsOnlyForWatchUsers(){
        UpdateCheck.set(context,true);seen("99.0.0")
        assertFalse(UpdateCheckJob.runOnce(context))
        assertEquals("wireless users see it in the app instead","99.0.0",UpdateCheck.pending(context)?.tag)
        context.getSharedPreferences("ui-state",0).edit().putBoolean("backgroundWatch",true).commit()
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(UpdateCheckJob.runOnce(context))
        assertNull("notified once",UpdateCheck.pending(context))
        assertFalse(UpdateCheckJob.runOnce(context))
        val posted=shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        val intent=shadowOf(posted.contentIntent).savedIntent
        assertEquals(Intent.ACTION_VIEW,intent.action)
        assertEquals("https://github.com/mattewbel/bluedotOFF/releases/tag/99.0.0",intent.dataString)
    }
    @Test fun turningOffStopsTheBackgroundJob(){
        UpdateCheck.set(context,true);prefs.edit().putBoolean("enabled",false).commit()
        assertFalse(UpdateCheckJob.runOnce(context));assertTrue(jobs.isEmpty())
    }
    @Test fun manifestBindsTheJobOnlyForTheSystem(){
        val info=context.packageManager.getServiceInfo(android.content.ComponentName(context,UpdateCheckJob::class.java),0)
        assertFalse(info.exported);assertEquals("android.permission.BIND_JOB_SERVICE",info.permission)
    }
}
