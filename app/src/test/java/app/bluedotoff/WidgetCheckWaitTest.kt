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
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/** Codex review 2026-11-06: a widget request that waited for the connection lock is checked again (age, cancel), and the
 *  service's timeout cancels its check and ends the widget progress. Fake settings and a fake pairing placeholder only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class WidgetCheckWaitTest {
    private val c get()=ApplicationProvider.getApplicationContext<Application>()
    private val leaseLock get()=checkNotNull(WirelessWork::class.java.getDeclaredField("lock").apply{isAccessible=true}.get(WirelessWork))
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
        assertTrue(WidgetCheckService.eligible(c));assertTrue(WifiConnection.connected(c))
    }
    private fun await(condition:()->Boolean):Boolean {
        val until=System.nanoTime()+2_000_000_000L
        while(!condition()&&System.nanoTime()<until)Thread.sleep(5)
        return condition()
    }
    private fun findBlockedWorker(){
        assertTrue("service worker must actually reach and wait on WirelessWork's lock",await{
            checkThread=Thread.getAllStackTraces().entries.firstOrNull{(t,s)->t.state==Thread.State.BLOCKED&&s.any{it.className==WidgetCheckService::class.java.name&&it.methodName=="check"}}?.key
            checkThread!=null
        })
    }
    private fun runQueued(timeout:Boolean,seconds:Long=if(timeout)181 else 20):Pair<Boolean,Boolean> {
        StatusWidget.refresh(c)
        assertTrue(StatusWidget.state(c,false).refreshing)
        val intent=Intent(c,WidgetCheckService::class.java).putExtra("at",SystemClock.elapsedRealtime())
        val controller=Robolectric.buildService(WidgetCheckService::class.java,intent).create()
        var stopped=false
        synchronized(leaseLock){
            controller.startCommand(0,1);findBlockedWorker()
            // A real foreground batch can retain this same lease after its screen is left.
            ShadowSystemClock.advanceBy(Duration.ofSeconds(seconds))
            if(timeout){controller.get().onTimeout(1,ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);stopped=shadowOf(controller.get()).isStoppedBySelf}
        }
        val turnedOn=await{Settings.Global.getInt(c.contentResolver,"adb_wifi_enabled",0)==1}
        val stillRefreshing=StatusWidget.state(c,false).refreshing
        if(timeout)assertTrue("the timeout did stop the Android service",stopped)
        controller.destroy()
        return turnedOn to stillRefreshing
    }
    @After fun cleanup(){
        checkThread?.interrupt();checkThread?.join(3000)
        shadowOf(Looper.getMainLooper()).idle()
        StatusWidget.finished(c,false)
        (WidgetCheckService::class.java.getDeclaredField("running").apply{isAccessible=true}.get(null) as AtomicBoolean).set(false)
        File(c.noBackupFilesDir,"local-adb-identity.enc").delete()
        LifecycleEvents.widget=null;LifecycleEvents.listener=null
    }
    @Test fun requestOlderThanTenSecondsAfterWaitingMustNotEnableDebugging(){
        val result=runQueued(false)
        assertFalse("request waited 20 seconds but still enabled wireless debugging",result.first)
    }
    /** Owner 2026-11-08: a check that could not run (here: it waited too long) tells the person instead of a silent tap. */
    @Test fun aCheckThatFailedSaysSo(){
        val controller=Robolectric.buildService(WidgetCheckService::class.java,Intent(c,WidgetCheckService::class.java).putExtra("at",SystemClock.elapsedRealtime())).create()
        synchronized(leaseLock){
            controller.startCommand(0,1);findBlockedWorker()
            ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
        }
        assertTrue("the expired check must end",await{checkThread?.isAlive==false})
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(UiText.text(c,"확인하지 못했어요. bluedotOFF를 열어 다시 확인해주세요."),org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertEquals(0,Settings.Global.getInt(c.contentResolver,"adb_wifi_enabled",0))
        controller.destroy()
    }
    @Test fun freshUncancelledRequestCanEnableDebugging(){
        assertTrue("control: an unexpired request may start",runQueued(false,1).first)
    }
    @Test fun timeoutMustCancelTheWorkerBeforeItCanEnableDebugging(){
        val result=runQueued(true)
        assertFalse("service already stopped at timeout, but its worker still enabled wireless debugging",result.first)
    }
    @Test fun timeoutMustFinishTheWidgetProgress(){
        val result=runQueued(true)
        assertFalse("timed-out check still shows refreshing",result.second)
    }
}
