package app.bluedotoff

import android.app.Application
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.*
import android.os.SystemClock
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.adb.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** A timed-out worker can remain blocked on WirelessWork.lock after its service is destroyed. A later tap may therefore
 *  create a new service instance while the process-wide running flag is still true. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class WidgetCheckLifecycleTest {
    private val c get()=ApplicationProvider.getApplicationContext<Application>()
    private val leaseLock get()=checkNotNull(WirelessWork::class.java.getDeclaredField("lock").apply{isAccessible=true}.get(WirelessWork))
    private val running get()=WidgetCheckService::class.java.getDeclaredField("running").apply{isAccessible=true}.get(null) as AtomicBoolean
    private var checkThread:Thread?=null

    @Before fun setup(){
        while(ForegroundState.active)ForegroundState.stopped()
        BackgroundWatch.set(c,false)
        shadowOf(c).grantPermissions(WirelessControls.PERMISSION)
        WirelessControls(c).setConsent(true)
        c.getSharedPreferences("local-adb",0).edit().putString("pairedGuid","fake-review-only").commit()
        File(c.noBackupFilesDir,"local-adb-identity.enc").writeText("fake placeholder, never decrypted")
        Settings.Global.putInt(c.contentResolver,Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,1)
        Settings.Global.putInt(c.contentResolver,"adb_wifi_enabled",0)
        val cm=c.getSystemService(ConnectivityManager::class.java);val network=checkNotNull(cm.activeNetwork);val caps=NetworkCapabilities()
        NetworkCapabilities::class.java.getMethod("addTransportType",Int::class.javaPrimitiveType).invoke(caps,NetworkCapabilities.TRANSPORT_WIFI)
        NetworkCapabilities::class.java.getMethod("addCapability",Int::class.javaPrimitiveType).invoke(caps,NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        shadowOf(cm).setNetworkCapabilities(network,caps);shadowOf(cm).setLinkProperties(network,LinkProperties().apply{interfaceName="wlan0"})
        running.set(false)
        c.getSharedPreferences("widget",0).edit().putLong("checkedAt",1000L).commit()
        assertTrue(WidgetCheckService.eligible(c));assertTrue(WifiConnection.connected(c))
    }

    private fun findBlockedWorker(){
        val until=System.nanoTime()+2_000_000_000L
        while(checkThread==null&&System.nanoTime()<until){
            checkThread=Thread.getAllStackTraces().entries.firstOrNull{(t,s)->t.state==Thread.State.BLOCKED&&s.any{it.className==WidgetCheckService::class.java.name&&it.methodName=="check"}}?.key
            if(checkThread==null)Thread.sleep(5)
        }
        assertNotNull("the first service worker must be waiting on WirelessWork.lock",checkThread)
    }

    private fun replacementAfterTimeout(stale:Boolean){
        StatusWidget.refresh(c)
        assertTrue(StatusWidget.state(c,false).refreshing)
        val intent=Intent(c,WidgetCheckService::class.java).putExtra("at",SystemClock.elapsedRealtime())
        val first=Robolectric.buildService(WidgetCheckService::class.java,intent).create()
        val replacement=Intent(intent).putExtra("at",SystemClock.elapsedRealtime()-if(stale)60_000 else 0)
        val second=Robolectric.buildService(WidgetCheckService::class.java,replacement).create()
        synchronized(leaseLock){
            first.startCommand(0,1)
            findBlockedWorker()
            assertTrue(running.get())

            // A duplicate start on the still-running instance must leave its live work alone.
            first.startCommand(0,2)
            assertFalse(shadowOf(first.get()).isStoppedBySelf)

            first.get().onTimeout(1,ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            assertTrue(shadowOf(first.get()).isStoppedBySelf)
            assertFalse(StatusWidget.state(c,false).refreshing)
            first.destroy()
            assertTrue("the timed-out worker is still blocked, as observed on device",running.get())

            // A separate service instance cannot own that old worker and must not remain foreground.
            StatusWidget.refresh(c)
            assertTrue(StatusWidget.state(c,false).refreshing)
            second.startCommand(0,3)
            assertTrue("the new instance must stop instead of leaving another foreground service behind",shadowOf(second.get()).isStoppedBySelf)
            assertFalse(StatusWidget.state(c,false).refreshing)
            assertEquals(1000L,c.getSharedPreferences("widget",0).getLong("checkedAt",0))
            assertEquals(0,Settings.Global.getInt(c.contentResolver,"adb_wifi_enabled",0))
            assertTrue(running.get())
        }
        second.destroy()
    }
    @Test fun aNewServiceAfterTimeoutStopsWhenTheOldWorkerStillOwnsRunning()=replacementAfterTimeout(false)
    @Test fun aStaleReplacementAlsoStopsWithoutReleasingTheOldWorkersGuard()=replacementAfterTimeout(true)

    @Test fun aStaleDuplicateDoesNotStopTheLiveOwningService(){
        val intent=Intent(c,WidgetCheckService::class.java).putExtra("at",SystemClock.elapsedRealtime())
        val first=Robolectric.buildService(WidgetCheckService::class.java,intent).create()
        synchronized(leaseLock){
            first.startCommand(0,1);findBlockedWorker()
            first.get().onStartCommand(Intent(intent).putExtra("at",SystemClock.elapsedRealtime()-60_000),0,2)
            assertFalse(shadowOf(first.get()).isStoppedBySelf)
            assertTrue(running.get())
            first.get().onTimeout(1,ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            first.destroy()
        }
    }

    @Test fun completionKeepsTheGuardUntilLifecycleCleanupAndCannotRestartTheEndedInstance(){
        val intent=Intent(c,WidgetCheckService::class.java).putExtra("at",SystemClock.elapsedRealtime())
        val first=Robolectric.buildService(WidgetCheckService::class.java,intent).create()
        synchronized(leaseLock){
            first.startCommand(0,1);findBlockedWorker()
            first.get().onTimeout(1,ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        }
        checkThread!!.join(3000)
        assertFalse(checkThread!!.isAlive)
        assertTrue("cleanup has not reached the lifecycle thread yet",running.get())
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(running.get())
        assertTrue(shadowOf(first.get()).isStoppedBySelf)
        // Android may still have a queued command for the old instance while it is being stopped.
        synchronized(leaseLock){
            first.get().onStartCommand(Intent(intent).putExtra("at",SystemClock.elapsedRealtime()),0,2)
            assertFalse("a cancelled instance must not acquire the guard again",running.get())
        }
        first.destroy()
    }

    @Test fun aFinishedServiceReleasesTheGuardForTheNextInstance(){
        // No connection is eligible: exercise normal completion without a real ADB attempt.
        WirelessControls(c).setConsent(false)
        repeat(2){
            val intent=Intent(c,WidgetCheckService::class.java).putExtra("at",SystemClock.elapsedRealtime())
            val controller=Robolectric.buildService(WidgetCheckService::class.java,intent).create().startCommand(0,1)
            assertTrue("each new instance is admitted before its queued completion",running.get())
            val until=System.nanoTime()+2_000_000_000L
            while(running.get()&&System.nanoTime()<until){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)}
            assertFalse(running.get())
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
            assertFalse(StatusWidget.state(c,false).refreshing)
            assertEquals(1000L,c.getSharedPreferences("widget",0).getLong("checkedAt",0))
            controller.destroy()
        }
    }

    @After fun cleanup(){
        checkThread?.interrupt();checkThread?.join(3000)
        shadowOf(Looper.getMainLooper()).idle()
        StatusWidget.finished(c,false)
        running.set(false)
        File(c.noBackupFilesDir,"local-adb-identity.enc").delete()
        LifecycleEvents.widget=null;LifecycleEvents.listener=null
    }
}
