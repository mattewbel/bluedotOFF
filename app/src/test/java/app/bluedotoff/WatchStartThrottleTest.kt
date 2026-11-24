package app.bluedotoff

import android.app.Application
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.adb.WatchClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadow.api.Shadow
import java.time.Duration

/** Owner 2026-10-26: Home said "open bluedotOFF on Wi-Fi", the owner turned Wi-Fi on 15 s after opening, and the watch
 *  did not start: the start blocked by missing Wi-Fi had counted as a try for the 30-second wait. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class WatchStartThrottleTest {
    @Test fun usbOffAndUnreadableStillBlockBeforeAnyActualLaunch(){
        val c=ApplicationProvider.getApplicationContext<Application>()
        BackgroundWatch.set(c,true)
        try{
            for((value,reason) in listOf(0 to "USB_OFF",-1 to "USB_UNREADABLE")){
                Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,value)
                assertEquals(if(value==0) WatchClient.UsbState.OFF else WatchClient.UsbState.UNKNOWN,WatchClient.usbState(c))
                assertFalse(WatchClient.usbEnabled(c))
                assertFalse(WatchClient.ensureStarted(c,true))
                val report=WatchDiagnostics.report(c)
                assertTrue(report,report.contains(" | $reason"))
                assertFalse(report.contains("Start attempt:"))
            }
        }finally{BackgroundWatch.set(c,false)}
    }
    @Test fun aStartBlockedWithoutWifiDoesNotDelayTheNextStart(){
        val c=ApplicationProvider.getApplicationContext<Application>()
        Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1);BackgroundWatch.set(c,true);ForegroundState.started()
        val last=WatchClient::class.java.getDeclaredField("lastStart").apply{isAccessible=true}
        try{
            assertFalse(WifiConnection.connected(c))
            assertFalse(WatchClient.ensureStarted(c))
            assertEquals(0L,last.getLong(WatchClient))
            val report=WatchDiagnostics.report(c)
            assertTrue(report.contains("WIFI_DISCONNECTED"))
            assertFalse(report.contains("Start attempt:"))
        }finally{ForegroundState.stopped();BackgroundWatch.set(c,false)}
    }

    /** Manual wireless setup has no automatic-management consent: after the user enables it in Settings,
     * returning to Home three seconds later must make a fresh discovery attempt. */
    @Test fun manualWirelessEnabledWithinThreeSecondsReachesDiscoveryOnHome(){
        val c=ApplicationProvider.getApplicationContext<Application>()
        setConnectedWifi(c)
        Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1)
        Settings.Global.putInt(c.contentResolver,"adb_wifi_enabled",0)
        c.getSharedPreferences("wireless-management",0).edit().putBoolean("enabled",false).commit()
        BackgroundWatch.set(c,true)
        ForegroundState.started()
        val last=WatchClient::class.java.getDeclaredField("lastStart").apply{isAccessible=true}
        last.setLong(WatchClient,0L)
        try{
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertFalse(WatchClient.ensureStarted(c))
            assertTrue(WatchDiagnostics.report(c).contains("app: WIRELESS_REQUIRED"))
            ForegroundState.stopped()
            Settings.Global.putInt(c.contentResolver,"adb_wifi_enabled",1) // user enables it in Settings
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            ForegroundState.started() // returns to Home within the 30-second window
            assertFalse(WatchClient.ensureStarted(c)) // no endpoint exists; reaching discovery is the expected result
            val report=WatchDiagnostics.report(c)
            assertEquals(2,report.lines().count{it.startsWith("Start attempt:")})
            assertTrue(report,report.contains("app: ADB_DISCOVERY_FAILED"))
        }finally{
            ForegroundState.stopped();BackgroundWatch.set(c,false);last.setLong(WatchClient,0L)
        }
    }

    @Test fun wirelessLeftOffDoesNotCauseRepeatedStartAttempts(){
        val c=ApplicationProvider.getApplicationContext<Application>()
        setConnectedWifi(c)
        Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1)
        Settings.Global.putInt(c.contentResolver,"adb_wifi_enabled",0)
        c.getSharedPreferences("wireless-management",0).edit().putBoolean("enabled",false).commit()
        BackgroundWatch.set(c,true);ForegroundState.started()
        val last=WatchClient::class.java.getDeclaredField("lastStart").apply{isAccessible=true}
        last.setLong(WatchClient,0L)
        try{
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertFalse(WatchClient.ensureStarted(c))
            assertFalse(WatchClient.ensureStarted(c))
            val report=WatchDiagnostics.report(c)
            assertEquals(1,report.lines().count{it.startsWith("Start attempt:")})
            assertTrue(report,report.contains("app: WIRELESS_REQUIRED"))
            assertEquals(0,Settings.Global.getInt(c.contentResolver,"adb_wifi_enabled",0))
        }finally{ForegroundState.stopped();BackgroundWatch.set(c,false);last.setLong(WatchClient,0L)}
    }

    @Test fun discoveryFailureKeepsThirtySecondThrottleButExplicitRetryBypassesIt(){
        val c=ApplicationProvider.getApplicationContext<Application>()
        setConnectedWifi(c)
        Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1)
        Settings.Global.putInt(c.contentResolver,"adb_wifi_enabled",1)
        c.getSharedPreferences("wireless-management",0).edit().putBoolean("enabled",false).commit()
        BackgroundWatch.set(c,true);ForegroundState.started()
        val last=WatchClient::class.java.getDeclaredField("lastStart").apply{isAccessible=true}
        last.setLong(WatchClient,0L)
        try{
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertFalse(WatchClient.ensureStarted(c))
            assertTrue(WatchDiagnostics.report(c).contains("app: ADB_DISCOVERY_FAILED"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertFalse(WatchClient.ensureStarted(c))
            assertEquals(1,WatchDiagnostics.report(c).lines().count{it.startsWith("Start attempt:")})
            // The explicit Home retry is allowed to make another real attempt inside the throttle window.
            assertFalse(WatchClient.ensureStarted(c,userAction=true))
            val report=WatchDiagnostics.report(c)
            assertEquals(2,report.lines().count{it.startsWith("Start attempt:")})
            assertTrue(report,report.contains("app: ADB_DISCOVERY_FAILED"))
        }finally{ForegroundState.stopped();BackgroundWatch.set(c,false);last.setLong(WatchClient,0L)}
    }

    private fun setConnectedWifi(c:Application){
        val manager=c.getSystemService(ConnectivityManager::class.java)
        val network=Shadow.extract<ShadowConnectivityManager>(manager)
        val caps=NetworkCapabilities()
        caps.javaClass.getMethod("addTransportType",Int::class.javaPrimitiveType).invoke(caps,NetworkCapabilities.TRANSPORT_WIFI)
        caps.javaClass.getMethod("addCapability",Int::class.javaPrimitiveType).invoke(caps,NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        network.setNetworkCapabilities(manager.activeNetwork,caps)
        network.setLinkProperties(manager.activeNetwork,LinkProperties().apply{interfaceName="wlan0"})
    }
}
