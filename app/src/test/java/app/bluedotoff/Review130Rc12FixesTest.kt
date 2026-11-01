package app.bluedotoff

import android.app.Application
import android.os.Looper
import android.provider.Settings
import android.os.Bundle
import app.bluedotoff.adb.WirelessControls
import app.bluedotoff.adb.WirelessWork
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** GPT rc12 review (2026-10-30): "앱 삭제 전 복원" ends automatic management, so coming back to the screen (unlock,
 *  another app) does not turn the 7-day expiry off again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class Review130Rc12FixesTest {
    @Before fun clearPreviousTestBatch(){
        val state=BatchRuntime::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(BatchRuntime) as kotlinx.coroutines.flow.MutableStateFlow<BatchUiState>
        state.value=BatchUiState()
    }
    @Test fun theRestoredExpiryStaysAfterTheScreenComesBack(){
        val c=RuntimeEnvironment.getApplication()
        shadowOf(c).grantPermissions(WirelessControls.PERMISSION)
        val controls=WirelessControls(c)
        Settings.Global.putString(c.contentResolver,WirelessControls.TIMEOUT,"604800000")
        controls.setConsent(true);controls.stopPairingExpiry()
        assertEquals("0",Settings.Global.getString(c.contentResolver,WirelessControls.TIMEOUT))
        val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try{
            MainActivity::class.java.getDeclaredMethod("beforeUninstall").apply{isAccessible=true}.invoke(controller.get())
            // Management ends after its wireless release on a background thread.
            val until=System.currentTimeMillis()+5_000
            while(controls.consent&&System.currentTimeMillis()<until){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(20)}
            assertFalse(controls.consent)
            assertEquals("604800000",Settings.Global.getString(c.contentResolver,WirelessControls.TIMEOUT))
            controller.pause().resume()
            assertEquals("604800000",Settings.Global.getString(c.contentResolver,WirelessControls.TIMEOUT))
        }finally{controls.setConsent(false);controller.pause().stop().destroy()}
    }
    /** GPT rc14 review: the cleanup waits for the wireless release (another session holds it); the "you can uninstall"
     *  result shows only after it, and a recreated screen (fold, rotation) does not cut it short. */
    @Test fun uninstallCleanupFinishesBeforeTheResultAndSurvivesRecreation(){exercise(true)}
    @Test fun aDelayedUninstallCleanupShowsTheResultWhenDone(){exercise(false)}
    private fun exercise(recreate:Boolean){
        val c=RuntimeEnvironment.getApplication()
        shadowOf(c).grantPermissions(WirelessControls.PERMISSION)
        val controls=WirelessControls(c)
        Settings.Global.putString(c.contentResolver,WirelessControls.TIMEOUT,"604800000")
        controls.setConsent(true);controls.stopPairingExpiry()
        var controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val lock=WirelessWork::class.java.getDeclaredField("lock").apply{isAccessible=true}.get(WirelessWork)
        val held=CountDownLatch(1);val release=CountDownLatch(1)
        val holder=Thread{synchronized(lock){held.countDown();release.await(10,TimeUnit.SECONDS)}}
        holder.start();assertTrue(held.await(2,TimeUnit.SECONDS))
        val getter=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}
        fun result()=(getter.invoke(controller.get()) as UiModel).uninstallCheck
        fun idleUntil(done:()->Boolean){val until=System.currentTimeMillis()+5_000;while(!done()&&System.currentTimeMillis()<until){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}}
        var destroyed=false
        try{
            MainActivity::class.java.getDeclaredMethod("beforeUninstall").apply{isAccessible=true}.invoke(controller.get())
            shadowOf(Looper.getMainLooper()).idle()
            assertNull("No result while the cleanup waits",result());assertTrue(controls.consent)
            val saved=Bundle()
            if(recreate){controller.pause().saveInstanceState(saved).stop().destroy();destroyed=true}
            release.countDown();holder.join(2_000)
            idleUntil{!controls.consent}
            assertFalse(controls.consent)
            assertEquals("604800000",Settings.Global.getString(c.contentResolver,WirelessControls.TIMEOUT))
            if(recreate){
                controller=Robolectric.buildActivity(MainActivity::class.java).create(saved).start().resume();destroyed=false
                shadowOf(Looper.getMainLooper()).idle();assertNull(result())
                // Pressed again: nothing left to clean, the result shows.
                MainActivity::class.java.getDeclaredMethod("beforeUninstall").apply{isAccessible=true}.invoke(controller.get())
            }
            idleUntil{result()!=null}
            assertEquals(0,result())
            assertEquals("604800000",Settings.Global.getString(c.contentResolver,WirelessControls.TIMEOUT))
        }finally{release.countDown();holder.join(2_000);controls.setConsent(false);if(!destroyed)controller.pause().stop().destroy()}
    }
    /** GPT rc15 review: the app lost its settings permission while the original expiry is saved. No "you can
     *  uninstall" then (the record would go with the app); once the owner turns the switch off, pressing again finishes. */
    @Test fun aSkippedExpiryRestoreIsNotReportedAsReady(){
        val c=RuntimeEnvironment.getApplication()
        shadowOf(c).grantPermissions(WirelessControls.PERMISSION)
        val controls=WirelessControls(c)
        Settings.Global.putString(c.contentResolver,WirelessControls.TIMEOUT,"604800000")
        controls.setConsent(true);controls.stopPairingExpiry()
        shadowOf(c).denyPermissions(WirelessControls.PERMISSION)
        val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val getter=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}
        fun model()=getter.invoke(controller.get()) as UiModel
        fun press(){MainActivity::class.java.getDeclaredMethod("beforeUninstall").apply{isAccessible=true}.invoke(controller.get())
            val until=System.currentTimeMillis()+5_000
            while((model().busy||model().notice.isEmpty()&&model().uninstallCheck==null)&&System.currentTimeMillis()<until){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}}
        try{
            press()
            assertNull(model().uninstallCheck);assertTrue(model().notice,model().notice.contains("adb 권한 타임아웃 해제"))
            assertTrue(controls.expiryPending);assertEquals("0",Settings.Global.getString(c.contentResolver,WirelessControls.TIMEOUT))
            // The owner turns 'adb 권한 타임아웃 해제' off: Android writes the default back.
            Settings.Global.putString(c.contentResolver,WirelessControls.TIMEOUT,"604800000")
            press()
            assertEquals(0,model().uninstallCheck);assertFalse(controls.expiryPending)
        }finally{shadowOf(c).grantPermissions(WirelessControls.PERMISSION);controls.setConsent(false);controller.pause().stop().destroy()}
    }
    /** GPT rc18 review: pressing restore cancels the automatic re-hide even when the restore cannot start (not connected). */
    @Test fun aRestoreThatCannotStartStillCancelsTheAutomaticReHide(){
        val c=RuntimeEnvironment.getApplication()
        val marked=app.bluedotoff.core.Entry(app.bluedotoff.core.Identity("test.retry",0,10123,listOf("cert"),1,1,1),"Retry",256,true,app.bluedotoff.core.TargetState.FAILED_SAFE,1,"fp","boot",false,retryHide=true)
        assertTrue(JournalStore(c).commit(marked))
        val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try{
            MainActivity::class.java.getDeclaredMethod("requestRestore",String::class.java).apply{isAccessible=true}.invoke(controller.get(),"test.retry")
            val getter=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}
            val until=System.currentTimeMillis()+5_000
            while((getter.invoke(controller.get()) as UiModel).busy&&System.currentTimeMillis()<until){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
            assertFalse(JournalStore(c).entries().single().retryHide)
            val model=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(controller.get()) as UiModel
            assertTrue(model.reapplyEntries.isEmpty())
        }finally{controller.pause().stop().destroy()}
    }
}


