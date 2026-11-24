package app.bluedotoff

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.os.Binder
import android.os.Build
import android.provider.Settings
import app.bluedotoff.adb.WatchClient
import app.bluedotoff.adb.WatchProtocol
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class, shadows=[WatchUsbReadSequence::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ColdWatchNotice74Test {
    private val c get()=RuntimeEnvironment.getApplication()
    private val prefs get()=c.getSharedPreferences("ui-state",0)
    private val manager get()=c.getSystemService(NotificationManager::class.java)
    private fun step(ms:Long)=shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))

    private fun cold(savedWatch:Boolean=true) {
        c.applicationInfo.sourceDir="/data/app/cold74/base.apk"
        // Each scenario starts with a new Android process and resolver.
        WatchClient::class.java.getDeclaredField("initialized").apply { isAccessible=true }.setBoolean(WatchClient,false)
        shadowOf(c).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        BackgroundWatch.set(c,true)
        if(savedWatch) {
            prefs.edit().putString("watchId",WATCH_ID).commit()
            BackgroundWatch.running(c)
        }
    }

    private fun profile()=JSONObject().put("pid",100).put("uid",2000).put("selinux","u:r:shell:s0")
        .put("abiVerified",true).put("fingerprint",Build.FINGERPRINT).put("oneUi","90000")
        .put("profileId","fixture").put("watchProtocol",1).put("watchId",WATCH_ID)
        .put("appUpdatedAt",c.packageManager.getPackageInfo(c.packageName,0).lastUpdateTime)

    private fun peer()=Binder().apply { attachInterface(null,WatchProtocol.DESCRIPTOR) }
    private fun attach(binder:Binder):Boolean {
        Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1)
        return WatchClient.attach(c,binder,profile(),c.applicationInfo.sourceDir,BuildConfig.VERSION_CODE.toLong())
    }

    private fun notice()=manager.activeNotifications.any{it.id==4201}

    @Test fun aTransientReadFailureCanClearWithoutAnySettingsNotification() {
        Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1)
        WatchUsbReadSequence.set(*IntArray(128){-1})
        var recovered:Boolean?=null
        val scope=kotlinx.coroutines.MainScope()
        val waiting=scope.launch { recovered=WatchClient.awaitReadableUsb(c){true} }
        try {
            step(3_000)
            assertNull(recovered)
            WatchUsbReadSequence.set() // the underlying value has remained ON; no content observer fires
            step(1_000)
            assertEquals(true,recovered)
        } finally { waiting.cancel();scope.cancel() }
    }

    @After fun clean() {
        WatchUsbReadSequence.set()
        BackgroundWatch.set(c,false)
        WatchClient.stopNow(c)
    }

    @Test fun savedLiveStatusAndPersistentUnknownWaitForContactAndRepair() {
        cold()
        WatchUsbReadSequence.set(*IntArray(128){-1})
        WatchClient.initialize(c)
        // The read is still unavailable at initialization; restoring the setting alone is not helper contact.
        assertTrue(prefs.getBoolean("watchRunning",false))
        assertNull(prefs.getString("watchStopNotified",null))
        assertFalse(notice())

        step(75_000)
        assertFalse(notice())
        step(75_000)
        assertTrue(WatchNotification.unconfirmed(c))
        assertTrue(notice())
        WatchUsbReadSequence.set()
        Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1)
        assertTrue(attach(peer()))
        assertTrue(WatchClient.available(c))
        assertNull(prefs.getString("watchStopNotified",null))
        assertFalse(notice())
    }

    @Test fun observerUnknownAndStartupFailureDoNotSkipColdGraceButConfirmedOffDoes() {
        cold()
        WatchUsbReadSequence.set(*IntArray(128){-1})
        WatchClient.initialize(c)
        WatchClient.startupFailed(c)
        WatchClient.initFailed(c)
        c.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),null)
        step(0)
        assertNull(prefs.getString("watchStopNotified",null))
        assertFalse(notice())

        // A later explicit OFF is a real stop signal and is confirmed by the existing grace timer.
        WatchUsbReadSequence.set()
        Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,0)
        step(75_000)
        assertEquals("USB_DEBUGGING_OFF",prefs.getString("watchStopNotified",null))
        assertTrue(notice())
    }

    @Test fun aFirstOpenWithoutAnyWatchHistoryDoesNotAnnounceADeath() {
        cold(savedWatch=false)
        WatchUsbReadSequence.set(*IntArray(128){-1})
        WatchClient.initialize(c)
        step(150_000)
        assertNull(prefs.getString("watchStopNotified",null))
        assertFalse(notice())
    }

    @Test fun genuineDeathOfAuthenticatedHelperStillPostsStop() {
        cold(savedWatch=false)
        Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1)
        WatchClient.initialize(c)
        val binder=peer()
        assertTrue(attach(binder))
        val owner=WatchClient::class.java.getDeclaredField("current").apply{isAccessible=true}.get(null)
        val death=owner.javaClass.getDeclaredField("death").apply{isAccessible=true}.get(owner) as android.os.IBinder.DeathRecipient
        death.binderDied()
        assertEquals("STOPPED",prefs.getString("watchStopNotified",null))
        assertTrue(notice())
    }

    companion object { private const val WATCH_ID="saved-helper-74" }
}
