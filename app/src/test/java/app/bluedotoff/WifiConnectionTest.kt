package app.bluedotoff

import android.app.Application
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Owner 2026-10-21: one Wi-Fi connection sent several capability updates and Home checked the apps two or three times. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class WifiConnectionTest {
    @Test fun onlyRealConnectAndDisconnectAreReported(){
        val c=ApplicationProvider.getApplicationContext<Application>()
        val cm=c.getSystemService(ConnectivityManager::class.java);val network=checkNotNull(cm.activeNetwork)
        var changes=0
        WifiConnection.observe(c){changes++}.use{
            val callback=shadowOf(cm).networkCallbacks.single()
            val caps=NetworkCapabilities()
            NetworkCapabilities::class.java.getMethod("addTransportType",Int::class.javaPrimitiveType).invoke(caps,NetworkCapabilities.TRANSPORT_WIFI)
            NetworkCapabilities::class.java.getMethod("addCapability",Int::class.javaPrimitiveType).invoke(caps,NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            shadowOf(cm).setNetworkCapabilities(network,caps);shadowOf(cm).setLinkProperties(network,LinkProperties().apply{interfaceName="wlan0"})
            callback.onAvailable(network);repeat(3){callback.onCapabilitiesChanged(network,caps)}
            assertEquals("connecting is one change",1,changes)
            shadowOf(cm).setNetworkCapabilities(network,null)
            callback.onLost(network);callback.onLost(network)
            assertEquals("losing it is one more",2,changes)
        }
    }
    /** Codex review 2026-10-22: the interface name arrives after the capabilities; that connect is still reported once. */
    @Test fun linkPropertiesArrivingLastAreReported(){
        val c=ApplicationProvider.getApplicationContext<Application>()
        val cm=c.getSystemService(ConnectivityManager::class.java);val network=checkNotNull(cm.activeNetwork)
        var changes=0
        val caps=NetworkCapabilities()
        NetworkCapabilities::class.java.getMethod("addTransportType",Int::class.javaPrimitiveType).invoke(caps,NetworkCapabilities.TRANSPORT_WIFI)
        NetworkCapabilities::class.java.getMethod("addCapability",Int::class.javaPrimitiveType).invoke(caps,NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        shadowOf(cm).setNetworkCapabilities(network,caps);shadowOf(cm).setLinkProperties(network,null)
        WifiConnection.observe(c){changes++}.use{
            val callback=shadowOf(cm).networkCallbacks.single()
            callback.onAvailable(network);callback.onCapabilitiesChanged(network,caps)
            assertEquals(0,changes)
            val link=LinkProperties().apply{interfaceName="wlan0"};shadowOf(cm).setLinkProperties(network,link)
            callback.onLinkPropertiesChanged(network,link);callback.onLinkPropertiesChanged(network,link)
            assertEquals("connecting is one change",1,changes)
        }
    }
}
