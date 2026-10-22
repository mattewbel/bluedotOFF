package app.bluedotoff

import android.app.Application
import android.net.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Codex review 2026-10-22: onLost counts even while the getters still return the old network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class WifiLostCallbackTest {
    @Test fun lostCallbackMustNotBeDiscardedBecauseSynchronousGettersAreLate(){
        val c=RuntimeEnvironment.getApplication();val cm=c.getSystemService(ConnectivityManager::class.java)
        val n=checkNotNull(cm.activeNetwork);val cap=NetworkCapabilities()
        NetworkCapabilities::class.java.getMethod("addTransportType",Int::class.javaPrimitiveType).invoke(cap,NetworkCapabilities.TRANSPORT_WIFI)
        NetworkCapabilities::class.java.getMethod("addCapability",Int::class.javaPrimitiveType).invoke(cap,NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        shadowOf(cm).setNetworkCapabilities(n,cap);shadowOf(cm).setLinkProperties(n,LinkProperties().apply{interfaceName="wlan0"})
        assertTrue(WifiConnection.connected(c));var changes=0
        WifiConnection.observe(c){changes++}.use{
            val callback=shadowOf(cm).networkCallbacks.single()
            // Android documents that getters inside callbacks can still return old values.
            callback.onLost(n)
            shadowOf(cm).setNetworkCapabilities(n,null)
            assertFalse(WifiConnection.connected(c))
            assertEquals("The ordered disconnect event was ignored and no later event need follow",1,changes)
        }
    }
}
