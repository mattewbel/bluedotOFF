package app.bluedotoff

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import app.bluedotoff.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class NotificationTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val manager get()=context.getSystemService(NotificationManager::class.java)
    @Test fun deniedNotificationsCreateNoNotificationAndPreserveJournal(){
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val store=JournalStore(context)
        val e=Entry(Identity("test.app",0,10792,listOf("cert"),1,1,1),"Test",256,true,TargetState.NEEDS_REAPPLY_BOOT,1,"fp","boot",true)
        assertTrue(store.commit(e));ReapplyNotification.show(context,null)
        assertTrue(shadowOf(manager).allNotifications.isEmpty());assertEquals(listOf(e),store.entries())
    }
    @Test fun bootNotificationTapOnlyNavigatesToReapply(){
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ReapplyNotification.show(context,null)
        val n=shadowOf(manager).allNotifications.single();val intent=shadowOf(n.contentIntent).savedIntent
        assertTrue(n.contentIntent.isImmutable);assertEquals("app.bluedotoff.REAPPLY",intent.action)
        assertNull(intent.getStringExtra("target"));assertEquals(MainActivity::class.java.name,intent.component!!.className)
        assertEquals(n.contentIntent,n.actions.single().actionIntent)
    }
    @Test fun updateNotificationCarriesOnlyTargetNavigation(){
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val e=Entry(Identity("test.app",0,10792,listOf("cert"),1,1,1),"Test",256,true,TargetState.NEEDS_REAPPLY_UPDATE,1,"fp","boot",true)
        ReapplyNotification.show(context,e)
        val n=shadowOf(manager).allNotifications.single();assertEquals("test.app",shadowOf(n.contentIntent).savedIntent.getStringExtra("target"));assertTrue(n.contentIntent.isImmutable)
    }
    @Test fun foregroundStateSuppressesDuplicateNotification(){
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ForegroundState.started()
        try{ReapplyNotification.show(context,null);assertTrue(shadowOf(manager).allNotifications.isEmpty())}finally{ForegroundState.stopped()}
    }
    @Test fun reappliedAppsClearTheirRemindersAndTheRestartReminderWhenNothingIsLeft(){
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val e=Entry(Identity("test.app",0,10792,listOf("cert"),1,1,1),"Test",256,true,TargetState.NEEDS_REAPPLY_UPDATE,1,"fp","boot",true)
        ReapplyNotification.show(context,null);ReapplyNotification.show(context,e)
        assertEquals(2,shadowOf(manager).allNotifications.size)
        ReapplyNotification.clear(context,listOf("test.app"),nothingLeft=false)
        assertEquals(1,shadowOf(manager).allNotifications.size)
        ReapplyNotification.clear(context,emptyList(),nothingLeft=true)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
