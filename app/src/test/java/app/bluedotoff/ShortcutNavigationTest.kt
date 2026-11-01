package app.bluedotoff

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** 1.3.0 long-press shortcuts only navigate on the ready screens: the connection setup steps stay exactly as they are. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ShortcutNavigationTest {
    private val context get()=ApplicationProvider.getApplicationContext<Application>()
    private fun shortcut(page:String)=Intent(context,MainActivity::class.java).setAction("app.bluedotoff.SHORTCUT").putExtra("page",page)
    private fun model(a:MainActivity)=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(a) as UiModel
    private fun setModel(a:MainActivity,m:UiModel)=MainActivity::class.java.getDeclaredMethod("setModel",UiModel::class.java).apply{isAccessible=true}.invoke(a,m)
    private fun open(a:MainActivity,intent:Intent)=MainActivity::class.java.getDeclaredMethod("openShortcut",Intent::class.java).apply{isAccessible=true}.invoke(a,intent)
    private fun pending(a:MainActivity)=MainActivity::class.java.getDeclaredField("shortcutPage").apply{isAccessible=true}.get(a) as Page?

    private fun check(gate:Gate,page:String,expectedPage:Page,expectedPending:Page?){
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        try{
            val a=controller.get()
            setModel(a,model(a).copy(gate=gate,page=Page.HOME))
            val intent=shortcut(page);a.intent=intent
            open(a,intent)
            assertEquals(expectedPage,model(a).page);assertEquals(expectedPending,pending(a))
            assertNull(a.intent.action)
        }finally{controller.destroy()}
    }
    @Test fun readyScreenOpensThePage()=check(Gate.READY,"PICK",Page.PICK,null)
    @Test fun setupScreensIgnoreIt(){for(gate in listOf(Gate.WELCOME,Gate.NOT_RUNNING,Gate.UNSUPPORTED))check(gate,"HISTORY",Page.HOME,null)}
    @Test fun aCheckingScreenKeepsItForLater()=check(Gate.CHECKING,"HISTORY",Page.HOME,Page.HISTORY)
    @Test fun unknownPagesAreIgnored()=check(Gate.READY,"SETTINGS",Page.HOME,null)
}
