package app.bluedotoff

import android.app.Application
import android.os.Looper
import android.provider.Settings
import app.bluedotoff.adb.WirelessControls
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Keep the connection usable when restoration/cleanup is incomplete; exercise the Activity's real cleanup caller. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class UninstallDebuggingTest {
    private fun settleStartup(activity:MainActivity){
        val fields=listOf("refreshJob","reapplyHistoryJob").map{MainActivity::class.java.getDeclaredField(it).apply{isAccessible=true}}
        fun reading()=fields.any{(it.get(activity) as? kotlinx.coroutines.Job)?.isActive==true}
        val deadline=System.currentTimeMillis()+5_000
        while(reading()&&System.currentTimeMillis()<deadline){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
        assertFalse("Set up corruption only after startup has finished reading/recovering the journal",reading())
    }
    @Before fun isolatePreviousCleanup(){
        val scope=MainActivity::class.java.getDeclaredField("cleanup").apply{isAccessible=true}.get(null) as kotlinx.coroutines.CoroutineScope
        val job=scope.coroutineContext[kotlinx.coroutines.Job]!!
        val deadline=System.currentTimeMillis()+5_000
        while(job.children.any{it.isActive}&&System.currentTimeMillis()<deadline){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
        assertFalse("A previous test's cleanup must finish first",job.children.any{it.isActive})
        val state=BatchRuntime::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(BatchRuntime) as kotlinx.coroutines.flow.MutableStateFlow<BatchUiState>
        state.value=BatchUiState()
        java.io.File(RuntimeEnvironment.getApplication().noBackupFilesDir,"original-bit-journal.json").delete()
    }
    private fun checkCleanup(allBack:Boolean,pendingExpiry:Boolean=false,expectOff:Boolean) {
        val c=RuntimeEnvironment.getApplication()
        c.getSharedPreferences("wireless-management",0).edit().clear().commit()
        c.getSharedPreferences("ui-state",0).edit().clear().commit()
        BackgroundWatch.set(c,false)
        val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        settleStartup(controller.get())
        val controls=WirelessControls(c)
        val model=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}
        fun current()=model.invoke(controller.get()) as UiModel
        try {
            Settings.Global.putInt(c.contentResolver,"adb_wifi_enabled",1)
            Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1)
            if(pendingExpiry){
                Settings.Global.putString(c.contentResolver,WirelessControls.TIMEOUT,"0")
                c.getSharedPreferences("wireless-management",0).edit().putString("timeoutBefore","604800000").commit()
                shadowOf(c).denyPermissions(WirelessControls.PERMISSION)
            }else shadowOf(c).grantPermissions(WirelessControls.PERMISSION)
            var ready=false
            MainActivity::class.java.declaredMethods.single{it.name=="stopWatchForUninstall"}.apply{isAccessible=true}
                .invoke(controller.get(),allBack,{ready=true})
            val deadline=System.currentTimeMillis()+5_000
            while(current().busy&&System.currentTimeMillis()<deadline){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
            assertFalse("Cleanup did not finish",current().busy)
            assertEquals(if(expectOff)0 else 1,Settings.Global.getInt(c.contentResolver,"adb_wifi_enabled"))
            assertEquals(if(expectOff)0 else 1,Settings.Global.getInt(c.contentResolver,Settings.Global.ADB_ENABLED))
            assertEquals(!pendingExpiry,ready)
            if(pendingExpiry){assertTrue(controls.expiryPending);assertTrue(current().notice.contains("adb 권한 타임아웃 해제"))}
            if(expectOff){assertFalse(current().usbDebugging);assertFalse(current().connected)}
        }finally {
            shadowOf(c).grantPermissions(WirelessControls.PERMISSION)
            controls.restorePairingExpiry()
            controller.pause().stop().destroy()
        }
    }
    @Test fun unfinishedRestorationKeepsBothDebuggingConnections()=checkCleanup(allBack=false,expectOff=false)
    @Test fun completedRestorationTurnsBothOffAndRefreshesTheScreenFacts()=checkCleanup(allBack=true,expectOff=true)
    @Test fun pendingExpiryKeepsDebuggingOnAndCannotReportReady()=checkCleanup(allBack=true,pendingExpiry=true,expectOff=false)
    private fun blockedJournal(knownProblem:Boolean) {
        val c=RuntimeEnvironment.getApplication()
        c.getSharedPreferences("wireless-management",0).edit().clear().commit()
        c.getSharedPreferences("ui-state",0).edit().clear().commit()
        BackgroundWatch.set(c,false)
        val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        settleStartup(controller.get())
        val get=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}
        val set=MainActivity::class.java.getDeclaredMethod("setModel",UiModel::class.java).apply{isAccessible=true}
        fun current()=get.invoke(controller.get()) as UiModel
        try {
            if(!knownProblem){
                val store=MainActivity::class.java.getDeclaredField("store").apply{isAccessible=true}.get(controller.get()) as JournalStore
                val file=JournalStore::class.java.getDeclaredField("base").apply{isAccessible=true}.get(store) as java.io.File
                file.writeText("{\"schema\":1,\"payload\":\"[]\",\"checksum\":\"invalid\"}")
                assertTrue("Test fixture must corrupt the Activity's actual journal",runCatching{store.entries()}.isFailure)
            }
            set.invoke(controller.get(),current().copy(entries=emptyList(),journalProblem=knownProblem,busy=false,uninstallCheck=null))
            shadowOf(c).grantPermissions(WirelessControls.PERMISSION)
            Settings.Global.putInt(c.contentResolver,"adb_wifi_enabled",1)
            Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1)
            MainActivity::class.java.getDeclaredMethod("beforeUninstall").apply{isAccessible=true}.invoke(controller.get())
            val deadline=System.currentTimeMillis()+5_000
            while(current().busy&&System.currentTimeMillis()<deadline){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
            assertEquals("Unreadable records must not be treated as no apps",1,Settings.Global.getInt(c.contentResolver,Settings.Global.ADB_ENABLED))
            assertEquals(1,Settings.Global.getInt(c.contentResolver,"adb_wifi_enabled"))
            assertNull("Never approve uninstall without trusted records",current().uninstallCheck)
            assertTrue(current().journalProblem)
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun unreadableJournalDoesNotApproveUninstallOrDisableDebugging()=blockedJournal(false)
    @Test fun knownJournalProblemCannotUseTheEmptyModelShortcut()=blockedJournal(true)
}
