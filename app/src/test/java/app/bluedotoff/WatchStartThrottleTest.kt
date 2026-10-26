package app.bluedotoff

import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.adb.WatchClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Owner 2026-10-26: Home said "open bluedotOFF on Wi-Fi", the owner turned Wi-Fi on 15 s after opening, and the watch
 *  did not start: the start blocked by missing Wi-Fi had counted as a try for the 30-second wait. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class WatchStartThrottleTest {
    @Test fun aStartBlockedWithoutWifiDoesNotDelayTheNextStart(){
        val c=ApplicationProvider.getApplicationContext<Application>()
        Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1);BackgroundWatch.set(c,true);ForegroundState.started()
        val last=WatchClient::class.java.getDeclaredField("lastStart").apply{isAccessible=true}
        try{
            assertFalse(WifiConnection.connected(c))
            assertFalse(WatchClient.ensureStarted(c))
            assertEquals(0L,last.getLong(WatchClient))
        }finally{ForegroundState.stopped();BackgroundWatch.set(c,false)}
    }
}
