package app.bluedotoff

import android.app.Application
import android.content.Intent
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

/** Owner 2026-10-22: the Settings watch switch with USB debugging off goes to that switch after its explanation,
 *  and the watch turns on only when the user comes back with USB debugging on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class WatchSwitchUsbTest {
    private fun case(usbOnReturn:Boolean){
        val c=RuntimeEnvironment.getApplication()
        Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,0)
        val controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try{
            val a=controller.get()
            while(shadowOf(a).nextStartedActivity!=null){}
            MainActivity::class.java.getDeclaredMethod("watchAfterUsb").apply{isAccessible=true}.invoke(a)
            val opened:Intent=checkNotNull(shadowOf(a).nextStartedActivity)
            assertEquals(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,opened.action)
            assertEquals("enable_adb",opened.getStringExtra(":settings:fragment_args_key"))
            assertFalse("not on before USB debugging is",BackgroundWatch.enabled(c))
            controller.pause().stop()
            Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,if(usbOnReturn)1 else 0)
            controller.restart().start().resume()
            assertEquals(usbOnReturn,BackgroundWatch.enabled(c))
        }finally{BackgroundWatch.set(c,false);controller.pause().stop().destroy()}
    }
    @Test fun backWithUsbDebuggingOnTurnsTheWatchOn()=case(true)
    @Test fun backWithUsbDebuggingStillOffLeavesItOff()=case(false)
}
