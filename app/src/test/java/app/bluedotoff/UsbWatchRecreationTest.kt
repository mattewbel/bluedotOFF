package app.bluedotoff

import android.app.Application
import android.os.Bundle
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Codex review 2026-10-22: the pending USB watch request survives an Activity recreated in Developer options. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class UsbWatchRecreationTest {
    @Test fun confirmedUsbWatchChoiceSurvivesActivityRecreationInSettings(){
        val c=RuntimeEnvironment.getApplication()
        Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,0)
        var controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try{
            MainActivity::class.java.getDeclaredMethod("watchAfterUsb").apply{isAccessible=true}.invoke(controller.get())
            assertFalse(BackgroundWatch.enabled(c))
            val saved=Bundle()
            controller.pause().saveInstanceState(saved).stop().destroy()
            Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1)
            controller=Robolectric.buildActivity(MainActivity::class.java).create(saved).start().resume()
            assertTrue("The confirmed request was lost while Settings was open; USB is ON but watch stays OFF",BackgroundWatch.enabled(c))
        }finally{BackgroundWatch.set(c,false);controller.pause().stop().destroy()}
    }
}
