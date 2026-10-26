package app.bluedotoff

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NotificationNavigationTest {
    private val context get()=ApplicationProvider.getApplicationContext<Application>()
    private fun notification(target:String?=null)=Intent(context,MainActivity::class.java).setAction("app.bluedotoff.REAPPLY").apply{if(target!=null)putExtra("target",target)}
    private fun model(activity:MainActivity)=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(activity) as UiModel
    private fun saved(page:Page)=Bundle().apply{putString("page",page.name);putString("selected","test.old")}
    private fun assertConsumed(activity:MainActivity){assertNull(activity.intent.action);assertFalse(activity.intent.hasExtra("target"))}
    @Before fun disableAutomaticReapply(){context.getSharedPreferences("ui-state",0).edit().putBoolean("autoReapply",false).commit()}

    @Test fun savedHomeWinsOverAnOldTargetNotification(){
        // Android can deliver the original launch Intent again on recreation, even after the Activity consumed its copy.
        val controller=Robolectric.buildActivity(MainActivity::class.java,notification("test.old")).create(saved(Page.HOME))
        try{assertEquals(Page.HOME,model(controller.get()).page)}finally{controller.destroy()}
    }
    @Test fun savedSettingsWinsOverAnOldRestartNotification(){
        val controller=Robolectric.buildActivity(MainActivity::class.java,notification()).create(saved(Page.SETTINGS))
        try{assertEquals(Page.SETTINGS,model(controller.get()).page)}finally{controller.destroy()}
    }
    @Test fun firstTargetNotificationStillOpensItsDetail(){
        val original=notification("test.first")
        val controller=Robolectric.buildActivity(MainActivity::class.java,original).create()
        try{
            assertEquals(Page.DETAIL,model(controller.get()).page);assertEquals("test.first",model(controller.get()).selected)
            assertConsumed(controller.get());assertEquals("app.bluedotoff.REAPPLY",original.action)
        }finally{controller.destroy()}
    }
    @Test fun firstRestartNotificationStillOpensReapply(){
        val controller=Robolectric.buildActivity(MainActivity::class.java,notification()).create()
        try{assertEquals(Page.REAPPLY,model(controller.get()).page);assertConsumed(controller.get())}finally{controller.destroy()}
    }
    @Test fun newNotificationsAreHandledButDoNotReplayAfterSavingHome(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).create(saved(Page.SETTINGS))
        val original=notification("test.new")
        val state=Bundle()
        try{
            controller.newIntent(original)
            assertEquals(Page.DETAIL,model(controller.get()).page);assertEquals("test.new",model(controller.get()).selected)
            assertConsumed(controller.get())
            MainActivity::class.java.getDeclaredMethod("navigate",Page::class.java).apply{isAccessible=true}.invoke(controller.get(),Page.HOME)
            // The same notification may be opened again deliberately; that is a fresh navigation request.
            controller.newIntent(notification("test.new"))
            assertEquals(Page.DETAIL,model(controller.get()).page)
            MainActivity::class.java.getDeclaredMethod("navigate",Page::class.java).apply{isAccessible=true}.invoke(controller.get(),Page.HOME)
            controller.saveInstanceState(state)
        }finally{controller.destroy()}
        val recreated=Robolectric.buildActivity(MainActivity::class.java,original).create(state)
        try{assertEquals(Page.HOME,model(recreated.get()).page);assertFalse(BatchRuntime.state.value.busy)}finally{recreated.destroy()}
    }
    @Test fun normalLaunchKeepsItsSavedPage(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).create(saved(Page.SETTINGS))
        try{assertEquals(Page.SETTINGS,model(controller.get()).page)}finally{controller.destroy()}
    }
}
