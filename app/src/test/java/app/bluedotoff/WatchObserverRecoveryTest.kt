package app.bluedotoff

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.adb.WatchClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class WatchObserverRecoveryTest {
    @Test fun partialSetupCanRetryWithoutAnnouncingAStop() {
        val c=ApplicationProvider.getApplicationContext<Application>()
        shadowOf(c).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1)
        BackgroundWatch.set(c,true)
        BackgroundWatch.running(c)
        val observerUnavailable=object:ContextWrapper(c) {
            override fun getApplicationContext():Context=this
            override fun getSystemService(name:String):Any? {
                if(name==Context.CONNECTIVITY_SERVICE) throw IllegalStateException("QA injected observer setup failure")
                return super.getSystemService(name)
            }
        }
        val result=runCatching{ WatchClient.initialize(observerUnavailable) }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        // Same failure handoff as LocalAdbApplication.onCreate.
        WatchClient.initFailed(c)
        assertNull(c.getSharedPreferences("ui-state",0).getString("watchStopNotified",null))
        assertTrue(c.getSystemService(NotificationManager::class.java).activeNotifications.none{it.id==4201})
        WatchClient.initialize(c)
        val initialized=WatchClient::class.java.getDeclaredField("initialized").apply{isAccessible=true}
        assertTrue(initialized.getBoolean(WatchClient))
    }
}
