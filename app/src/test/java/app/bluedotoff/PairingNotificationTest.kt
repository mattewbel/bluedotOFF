package app.bluedotoff

import android.Manifest
import android.app.*
import app.bluedotoff.adb.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,qualifiers="ko-rKR")
class PairingNotificationTest {
    @Test @Config(qualifiers="en-rUS") fun englishNotificationKeepsReplySchemaAndPrivacy(){
        PairingNotifications.code(context,11,"en-session")
        val n=shadowOf(manager).allNotifications.single()
        assertEquals("Enter the pairing code",n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Enter code",n.actions.single().title)
        assertEquals(PairingNotifications.REPLY,n.actions.single().remoteInputs.single().resultKey)
        assertEquals(Notification.VISIBILITY_SECRET,n.visibility)
    }
    private val context get()=RuntimeEnvironment.getApplication()
    private val manager get()=context.getSystemService(NotificationManager::class.java)
    @Test fun codeNoticeHasDirectReply(){
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        PairingNotifications.code(context,9,"session-a")
        val n=shadowOf(manager).allNotifications.single()
        assertEquals("페어링 코드를 입력하세요",n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("코드 입력",n.actions.single().title)
        assertEquals(PairingNotifications.REPLY,n.actions.single().remoteInputs.single().resultKey)
        val intent=shadowOf(n.actions.single().actionIntent).savedIntent
        assertEquals(9,intent.getLongExtra("generation",-1));assertEquals("session-a",intent.getStringExtra("sessionId"))
        assertEquals(PairingReplyReceiver::class.java.name,intent.component!!.className)
        assertFalse(n.actions.single().actionIntent.isImmutable)
        assertEquals(Notification.VISIBILITY_SECRET,n.visibility)
        // In use: a full-screen request (pinned heads-up), immutable and to the setup screen only.
        assertTrue(n.fullScreenIntent.isImmutable);assertEquals(LocalAdbSetupActivity::class.java.name,shadowOf(n.fullScreenIntent).savedIntent.component!!.className)
    }
    /** Screen off or locked: no full-screen request, so the setup screen is never opened over the lock screen. */
    @Test fun lockedOrOffPhoneGetsNoFullScreenRequest(){
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        PairingNotifications.code(context,4,"s");assertNull(shadowOf(manager).allNotifications.single().fullScreenIntent)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        shadowOf(context.getSystemService(android.os.PowerManager::class.java)).setIsInteractive(false)
        PairingNotifications.code(context,5,"s");assertNull(shadowOf(manager).allNotifications.single().fullScreenIntent)
    }
    @Test fun cancelRemovesNoticeWithoutAnySuccessNotification(){PairingNotifications.code(context,3,"s");PairingNotifications.cancel(context);assertTrue(shadowOf(manager).allNotifications.isEmpty())}
    @Test fun invalidCodeNoticeContainsOnlyGenericPrompt(){PairingNotifications.code(context,3,"s",true);val n=shadowOf(manager).allNotifications.single();assertEquals("6자리 숫자를 입력해주세요",n.extras.getString(Notification.EXTRA_TITLE));assertFalse(n.extras.toString().contains("123456"))}
    @Test fun notificationTapIsDirectActivityPendingIntent(){PairingNotifications.code(context,3,"s");val pi=shadowOf(manager).allNotifications.single().contentIntent;assertTrue(pi.isImmutable);assertEquals(LocalAdbSetupActivity::class.java.name,shadowOf(pi).savedIntent.component!!.className)}
    @Test fun channelIsHighWithNoForcedVibration(){PairingNotifications.code(context,3,"s");val channel=manager.getNotificationChannel(PairingNotifications.CHANNEL);assertEquals(NotificationManager.IMPORTANCE_HIGH,channel.importance);assertFalse(channel.shouldVibrate())}
}
