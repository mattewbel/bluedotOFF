package app.bluedotoff

import android.app.Application
import android.net.*
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** GPT release review 2026-10-26 (`artifacts/review-122-release-20261026/`): a Wi-Fi connect with the app open starts a
 *  stopped watch like reopening the app, also when the getters still lag the callback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class WifiResumeTest {
    private fun checkConnect(staleGetters:Boolean,watch:Boolean){
        val c=RuntimeEnvironment.getApplication()
        val cm=c.getSystemService(ConnectivityManager::class.java);val network=checkNotNull(cm.activeNetwork)
        shadowOf(cm).setNetworkCapabilities(network,null);shadowOf(cm).setLinkProperties(network,null)
        Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1)
        BackgroundWatch.set(c,watch)
        val previous=shadowOf(cm).networkCallbacks.toSet()
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        val pending=MainActivity::class.java.getDeclaredField("checkOnResume").apply{isAccessible=true}
        pending.setBoolean(controller.get(),true)
        ForegroundState.started()
        try{
            val callback=(shadowOf(cm).networkCallbacks.toSet()-previous).single()
            val caps=NetworkCapabilities()
            NetworkCapabilities::class.java.getMethod("addTransportType",Int::class.javaPrimitiveType).invoke(caps,NetworkCapabilities.TRANSPORT_WIFI)
            NetworkCapabilities::class.java.getMethod("addCapability",Int::class.javaPrimitiveType).invoke(caps,NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            val link=LinkProperties().apply{interfaceName="wlan0"}
            if(!staleGetters){shadowOf(cm).setNetworkCapabilities(network,caps);shadowOf(cm).setLinkProperties(network,link)}
            callback.onAvailable(network);callback.onCapabilitiesChanged(network,caps);callback.onLinkPropertiesChanged(network,link)
            // The getters catch up without a new connection transition; duplicate capability callbacks must not be required.
            if(staleGetters){shadowOf(cm).setNetworkCapabilities(network,caps);shadowOf(cm).setLinkProperties(network,link);callback.onCapabilitiesChanged(network,caps)}
            assertTrue(WifiConnection.connected(c))
            assertEquals("Wi-Fi connect should take the same resumeCheck path as reopening, only when watch is enabled",!watch,pending.getBoolean(controller.get()))
        }finally{ForegroundState.stopped();controller.destroy();BackgroundWatch.set(c,false)}
    }
    @Test fun connectedWatchUsesResumePathWithFreshGetters()=checkConnect(false,true)
    @Test fun connectedWatchUsesResumePathEvenWhenGettersLagCallbacks()=checkConnect(true,true)
    @Test fun disabledWatchDoesNotRequestResume()=checkConnect(false,false)
}
