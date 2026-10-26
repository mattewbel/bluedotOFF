package app.bluedotoff

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Owner 2026-10-25: a new screen (fold, language, Wi-Fi on) flashed blue and drained like an apply although every app
 *  stayed applied. The saved records must be in the model before the first frame, not after refresh's background read. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class FirstFrameTintTest {
    @Test fun newScreenStartsWithoutBlueWhenEveryAppIsApplied(){
        val c=ApplicationProvider.getApplicationContext<Application>()
        val id=Identity("demo.location",0,10050,listOf("fixture"),1,1,1)
        assertTrue(JournalStore(c).commit(Entry(id,"Fixture",256,true,TargetState.APPLIED,1,"fp|key","count:1",true,invariantDigest="fixture",settlingVerified=true)))
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        try{
            val model=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(controller.get()) as UiModel
            assertEquals(0f,model.blueRemaining,0f)
        }finally{controller.destroy()}
    }
}
