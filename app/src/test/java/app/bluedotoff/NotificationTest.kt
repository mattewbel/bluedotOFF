package app.bluedotoff

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.Notification
import android.app.NotificationChannel
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
    private fun entry(pkg:String="test.app",state:TargetState=TargetState.NEEDS_REAPPLY_UPDATE)=Entry(Identity(pkg,0,10792,listOf("cert"),1,1,1),pkg,256,true,state,1,"fp","boot",true,settlingVerified=true)
    private fun save(vararg entries:Entry){entries.forEach{assertTrue(JournalStore(context).commit(it))}}
    private fun dismissShown():android.content.Intent {
        val n=shadowOf(manager).allNotifications.single()
        assertTrue(n.deleteIntent.isImmutable)
        val intent=shadowOf(n.deleteIntent).savedIntent
        assertEquals(LifecycleReceiver::class.java.name,intent.component!!.className)
        manager.cancelAll();LifecycleReceiver().onReceive(context,intent)
        return intent
    }
    @Test fun dismissedFailureStaysQuietAcrossRetriesButNewAppNotifies(){
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val a=entry("test.a",TargetState.FAILED_SAFE);save(a);ReapplyNotification.show(context,a)
        dismissShown()
        repeat(4){ReapplyNotification.watchFinished(context,listOf(a.identity.packageName));ReapplyNotification.show(context,null,true)}
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        val b=entry("test.b");save(b);ReapplyNotification.watchFinished(context,listOf(b.identity.packageName))
        assertTrue(shadowOf(manager).allNotifications.single().extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("2"))
        save(b.copy(state=TargetState.APPLIED));ReapplyNotification.watchFinished(context,listOf(b.identity.packageName))
        assertTrue("only the previously dismissed issue remains",shadowOf(manager).allNotifications.isEmpty())
    }
    @Test fun resolvedAppCanNotifyAgainWhileAnotherDismissedAppRemains(){
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val a=entry("test.a");val b=entry("test.b");save(a,b);ReapplyNotification.show(context,a)
        val oldDelete=dismissShown()
        save(a.copy(state=TargetState.APPLIED));ReapplyNotification.clear(context)
        save(a);ReapplyNotification.watchFinished(context,listOf(a.identity.packageName))
        assertEquals(1,shadowOf(manager).allNotifications.size)
        LifecycleReceiver().onReceive(context,oldDelete)
        assertEquals("late dismissal must not cancel the new episode",1,shadowOf(manager).allNotifications.size)
        dismissShown();ReapplyNotification.watchFinished(context,listOf(a.identity.packageName));assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
    @Test fun lateDeleteOfOldSummaryCannotSilenceANewApp(){
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val a=entry("test.a");save(a);ReapplyNotification.show(context,a)
        val oldDelete=shadowOf(shadowOf(manager).allNotifications.single().deleteIntent).savedIntent
        save(entry("test.b"));ReapplyNotification.show(context,null,true)
        LifecycleReceiver().onReceive(context,oldDelete)
        assertEquals(1,shadowOf(manager).allNotifications.size)
    }
    @Test fun resolvedOrReinstalledAppIsANewIssue(){
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val a=entry();save(a);ReapplyNotification.show(context,a);dismissShown()
        save(a.copy(identity=a.identity.copy(firstInstallTime=2)));ReapplyNotification.show(context,null,true)
        assertEquals(1,shadowOf(manager).allNotifications.size);dismissShown()
        save(a.copy(enabled=false,state=TargetState.NOT_APPLIED));ReapplyNotification.clear(context)
        save(a);ReapplyNotification.show(context,a);assertEquals(1,shadowOf(manager).allNotifications.size)
    }
    @Test fun deniedNotificationsCreateNoNotificationAndPreserveJournal(){
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val store=JournalStore(context)
        val e=Entry(Identity("test.app",0,10792,listOf("cert"),1,1,1),"Test",256,true,TargetState.NEEDS_REAPPLY_BOOT,1,"fp","boot",true)
        assertTrue(store.commit(e));ReapplyNotification.show(context,null)
        assertTrue(shadowOf(manager).allNotifications.isEmpty());assertEquals(listOf(e),store.entries())
    }
    @Test fun bootNotificationTapOnlyNavigatesToReapply(){
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        save(entry(state=TargetState.NEEDS_REAPPLY_BOOT))
        ReapplyNotification.show(context,null)
        val n=shadowOf(manager).allNotifications.single();val intent=shadowOf(n.contentIntent).savedIntent
        assertTrue(n.contentIntent.isImmutable);assertEquals("app.bluedotoff.REAPPLY",intent.action)
        assertNull(intent.getStringExtra("target"));assertEquals(MainActivity::class.java.name,intent.component!!.className)
        assertTrue(intent.getBooleanExtra("check",false))
        assertEquals(n.contentIntent,n.actions.single().actionIntent)
    }
    @Test fun updateNotificationOpensHomeForAllAppsWithoutChangingTheirRecords(){
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val e=Entry(Identity("test.app",0,10792,listOf("cert"),1,1,1),"Test",256,true,TargetState.NEEDS_REAPPLY_UPDATE,1,"fp","boot",true)
        save(e);ReapplyNotification.show(context,e)
        val n=shadowOf(manager).allNotifications.single();assertNull(shadowOf(n.contentIntent).savedIntent.getStringExtra("target"));assertTrue(n.contentIntent.isImmutable)
        assertTrue(shadowOf(n.contentIntent).savedIntent.getBooleanExtra("check",false));assertEquals(listOf(e),JournalStore(context).entries())
    }
    @Test fun foregroundStateSuppressesDuplicateNotification(){
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        save(entry())
        ForegroundState.started()
        try{ReapplyNotification.show(context,null);assertTrue(shadowOf(manager).allNotifications.isEmpty())}finally{ForegroundState.stopped()}
    }
    @Test fun burstOfUpdatesAndRestartUseOneQuietReminder(){
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val entries=(1..3).map{entry("test.app$it")};entries.forEach{save(it);ReapplyNotification.show(context,it)}
        ReapplyNotification.show(context,null)
        val n=shadowOf(manager).allNotifications.single()
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("3"))
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }
    @Test fun successfulAppDoesNotDismissAnotherAppsFailedOrUnsupportedReminder(){
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val a=entry("test.ok");val b=entry("test.failed",TargetState.FAILED_SAFE);val d=entry("test.permission",TargetState.UNSUPPORTED_STATE)
        save(a,b,d);ReapplyNotification.show(context,a)
        save(a.copy(state=TargetState.APPLIED));ReapplyNotification.clear(context)
        var n=shadowOf(manager).allNotifications.single()
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("2"))
        save(b.copy(state=TargetState.APPLIED),d.copy(state=TargetState.NOT_APPLIED,enabled=false))
        ReapplyNotification.clear(context)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
    @Test fun watchSuccessIsSilentButUnresolvedChecksCreateOneReminder(){
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val ok=entry("test.ok",TargetState.APPLIED);val failed=entry("test.failed",TargetState.FAILED_SAFE)
        save(ok,failed)
        ReapplyNotification.watchFinished(context,listOf(ok.identity.packageName))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        ReapplyNotification.watchFinished(context,listOf(failed.identity.packageName))
        assertEquals(1,shadowOf(manager).allNotifications.size)
        manager.cancelAll()
        ReapplyNotification.watchFinished(context,listOf(ok.identity.packageName))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
    @Test fun consolidationRemovesLegacyAppRemindersButNotOtherChannels(){
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.createNotificationChannel(NotificationChannel(ReapplyNotification.CHANNEL,"Reapply",NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel("other","Other",NotificationManager.IMPORTANCE_DEFAULT))
        for(i in 1..3)manager.notify("test.old$i".hashCode(),Notification.Builder(context,ReapplyNotification.CHANNEL).setSmallIcon(R.drawable.ic_dot).build())
        manager.notify(999,Notification.Builder(context,"other").setSmallIcon(R.drawable.ic_dot).build())
        val e=entry();save(e);ReapplyNotification.show(context,e)
        assertEquals(2,shadowOf(manager).allNotifications.size)
        assertEquals(1,manager.activeNotifications.count{it.notification.channelId==ReapplyNotification.CHANNEL})
    }
    @Test fun disabledRemindersRemainDisabledForWatchFailures(){
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        context.getSharedPreferences("ui-state",0).edit().putBoolean("reapplyNotify",false).commit()
        val e=entry(state=TargetState.FAILED_SAFE);save(e);ReapplyNotification.watchFinished(context,listOf(e.identity.packageName))
        assertTrue(shadowOf(manager).allNotifications.isEmpty());assertEquals(listOf(e),JournalStore(context).entries())
    }
    /** Owner 2026-10-31: the failure reminder goes away by itself once the automatic re-hide succeeds. */
    @Test fun aRetryThatSucceedsWithdrawsTheFailureReminder(){
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val failed=entry("test.retry",TargetState.FAILED_SAFE).copy(retryHide=true,settlingVerified=false)
        save(failed);ReapplyNotification.watchFinished(context,listOf(failed.identity.packageName))
        assertEquals(1,shadowOf(manager).allNotifications.size)
        save(failed.copy(state=TargetState.APPLIED,retryHide=false,settlingVerified=true))
        ReapplyNotification.watchFinished(context,listOf(failed.identity.packageName))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
