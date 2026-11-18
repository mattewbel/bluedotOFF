package app.bluedotoff

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.*
import android.provider.Settings
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.adb.*
import app.bluedotoff.core.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** A USB read gap after dequeue but before Session.run must retain revisionless work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class WatchQueuedAdmissionRecoveryTest {
    private val app get()=ApplicationProvider.getApplicationContext<Application>().also {
        it.applicationInfo.sourceDir="/data/app/fixture/base.apk"
    }
    @get:Rule val testName=TestName()
    private val target get()="test.admission."+testName.methodName
    private val uid=10571
    private val signature=Signature(byteArrayOf(2,3,4,5,6))
    private val signer get()=BinderBackend.digest(signature.toByteArray())
    private fun usb(value:Int) { Settings.Global.putInt(app.contentResolver,Settings.Global.ADB_ENABLED,value) }
    private fun worker()=WatchClient::class.java.getDeclaredField("worker").apply{isAccessible=true}.get(WatchClient) as ExecutorService
    private fun drain() { repeat(2) { worker().submit{}.get(8,TimeUnit.SECONDS) } }
    private fun parcel(block:Parcel.()->Unit):String {
        val p=Parcel.obtain()
        return try { p.writeNoException();p.block();Base64.encodeToString(p.marshall(),Base64.NO_WRAP) }
        finally { p.recycle() }
    }
    private inner class Peer:Binder() {
        var reads=0
        var writes=0
        init { attachInterface(null,WatchProtocol.DESCRIPTOR) }
        private fun info()=PackageInfo().apply {
            packageName=target;versionCode=7;firstInstallTime=1_700_000_000_000L;lastUpdateTime=1_700_000_100_000L
            applicationInfo=ApplicationInfo().apply {
                packageName=target;uid=this@WatchQueuedAdmissionRecoveryTest.uid
                flags=ApplicationInfo.FLAG_INSTALLED;sourceDir="/data/app/$target/base.apk"
            }
            requestedPermissions=arrayOf(BinderBackend.FINE,BinderBackend.COARSE)
            requestedPermissionsFlags=intArrayOf(PackageInfo.REQUESTED_PERMISSION_GRANTED,PackageInfo.REQUESTED_PERMISSION_GRANTED)
            signingInfo=SigningInfo().also{shadowOf(it).setSignatures(arrayOf(signature))};signatures=arrayOf(signature)
        }
        private fun response(q:JSONObject):JSONObject {
            val method:String;val descriptor:String;val bytes:String
            when(val op=q.getString("op")) {
                "GET_PACKAGE_INFO"->{reads++;method="getPackageInfo";descriptor="android.content.pm.IPackageManager";bytes=parcel{writeTypedObject(info(),0)}}
                "GET_UID_PEERS"->{method="getPackagesForUid";descriptor="android.content.pm.IPackageManager";bytes=parcel{writeStringArray(arrayOf(target))}}
                "GET_PERMISSION_FLAGS"->{method="getPermissionFlags";descriptor="android.permission.IPermissionManager";bytes=parcel{writeInt(if(q.getString("permission")=="FINE")0x200 else 0x300)}}
                "GET_RAW_APP_OPS"->{method="checkOperationRaw";descriptor="com.android.internal.app.IAppOpsService";bytes=parcel{writeInt(4)}}
                "UPDATE_FINE_SENSITIVITY_BIT","UPDATE_SHARED_FINE_SENSITIVITY_BIT"->{writes++;error("read-only fixture rejected a setter")}
                else->error("unexpected helper request $op")
            }
            return JSONObject().put("method",method).put("descriptor",descriptor).put("parcel",bytes)
        }
        override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
            data.enforceInterface(WatchProtocol.DESCRIPTOR)
            val q=JSONObject(data.readString()!!)
            val result=when(code) {
                WatchProtocol.REQUEST->response(q)
                WatchProtocol.CONFIGURE->JSONObject().put("configured",true)
                WatchProtocol.STOP->JSONObject().put("stopped",true)
                else->error("unexpected protocol $code")
            }
            reply!!.writeNoException();reply.writeString(result.toString());return true
        }
    }
    private fun profile()=JSONObject().put("pid",100).put("uid",2000).put("selinux","u:r:shell:s0")
        .put("abiVerified",true).put("historyAbiVerified",true).put("indicatorAbiVerified",true)
        .put("fingerprint",Build.FINGERPRINT).put("oneUi","90000").put("profileId","fixture")
        .put("watchProtocol",1).put("watchId","admission-owner")
        .put("appUpdatedAt",app.packageManager.getPackageInfo(app.packageName,0).lastUpdateTime)
    private fun contact(peer:Peer) {
        assertTrue(WatchClient.attach(app,peer,profile(),app.applicationInfo.sourceDir,BuildConfig.VERSION_CODE.toLong()))
        drain()
    }
    private fun start(peer:Peer) {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        usb(1);BackgroundWatch.set(app,true);contact(peer)
    }
    private fun entry():Entry {
        val identity=Identity(target,0,uid,listOf(signer),1_700_000_000_000L,7,1_700_000_100_000L)
        val snapshot=Snapshot(identity,"Fixture",listOf(target),true,true,true,false,0x200,0x300,0,4,4,false,applicationScope=ApplicationScope.ALL)
        val firmware=Firmware(Build.MANUFACTURER,Build.VERSION.SDK_INT,"90000",Build.FINGERPRINT,Build.ID,Build.MODEL,profile().getString("profileId"))
        return Entry(identity,"Fixture",0x100,true,TargetState.APPLIED,System.currentTimeMillis(),firmware.key,
            BootSession.current(app),true,invariantDigest=snapshot.invariantDigest(),settlingVerified=true)
    }
    private fun save(e:Entry) { assertTrue(JournalStore(app).commit(e)) }

    private enum class Boundary { EXCLUSIVE, SESSION }
    /** Settings are simulated only in Robolectric. Toggle on the real admission call, and recover on
     * the first outer catch read after the session lock was released. No production hook is added. */
    private inner class ReadGap(private val boundary:Boundary):ContextWrapper(app) {
        var armed=true
        @Volatile var injected=false
        @Volatile var recovered=false
        override fun getApplicationContext():Context=this
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
            if(name=="ui-state"&&armed) {
                val stack=Thread.currentThread().stackTrace
                val session=stack.any{it.className=="app.bluedotoff.adb.WatchClient\$Session"&&it.methodName=="run"}
                val exclusive=stack.any{it.className==WatchClient::class.java.name&&it.methodName.startsWith("exclusive")}
                val atBoundary=LocalAdbSession.busy&&when(boundary) {
                    Boundary.EXCLUSIVE->exclusive&&!session
                    Boundary.SESSION->session
                }
                if(!injected&&atBoundary) { injected=true;usb(-1) }
                else if(injected&&!recovered&&!LocalAdbSession.busy) { recovered=true;usb(1) }
            }
            return super.getSharedPreferences(name,mode)
        }
    }
    @After fun cleanup() {
        usb(1);drain();BackgroundWatch.set(app,false);WatchClient.stopNow(app)
        LifecycleEvents.widget=null;LifecycleEvents.listener=null
    }
    private fun gapCase(boundary:Boundary,withRevision:Boolean) {
        val peer=Peer();start(peer);save(entry())
        val context=ReadGap(boundary)
        val packet=mapOf(target to 41L)
        if(withRevision) assertTrue(WatchClient.receiveEvents(context,peer,packet,packet.keys).checked.isEmpty())
        else assertTrue(WatchClient.signal(context,setOf(target)))
        drain()
        assertTrue("the intended admission check must see the injected gap",context.injected)
        assertTrue("USB must recover in the outer catch",context.recovered)
        assertEquals("no target read is allowed during admission failure",0,peer.reads)
        assertTrue(WatchClient.available(app))
        repeat(2) {
            contact(peer)
            if(withRevision) { WatchClient.receiveEvents(app,peer,packet,packet.keys);drain() }
        }
        assertTrue("an admitted but unstarted target must recover on the same helper's contact",peer.reads>0)
        assertEquals("the target is already hidden",0,peer.writes)
    }
    @Test fun ordinaryRevisionlessWorkReadsTheTarget() {
        val peer=Peer();start(peer);save(entry())
        assertTrue(WatchClient.signal(app,setOf(target)));drain()
        assertTrue(peer.reads>0);assertEquals(0,peer.writes)
    }
    @Test fun revisionlessWorkSurvivesExclusiveAdmissionReadGap()=gapCase(Boundary.EXCLUSIVE,false)
    @Test fun revisionlessWorkSurvivesSessionAdmissionReadGap()=gapCase(Boundary.SESSION,false)
    @Test fun helperOwnedRevisionReplaysAfterTheSameExclusiveReadGap()=gapCase(Boundary.EXCLUSIVE,true)
    @Test fun helperOwnedRevisionReplaysAfterTheSameSessionReadGap()=gapCase(Boundary.SESSION,true)

    private fun admissionGapDoesNotAnnounceUnrelatedWork(boundary:Boundary,unrelatedState:TargetState) {
        val peer=Peer();start(peer)
        val applied=entry()
        val unrelated=applied.copy(
            identity=applied.identity.copy(packageName="test.unrelated.admission",uid=uid+1),
            label="Unrelated pending app",state=unrelatedState
        )
        save(applied);save(unrelated)
        val manager=app.getSystemService(NotificationManager::class.java)
        fun reapplyNotices()=manager.activeNotifications.filter{it.notification.channelId==ReapplyNotification.CHANNEL}
        assertFalse("the background notification path must be enabled",ForegroundState.active)
        assertTrue(manager.areNotificationsEnabled())
        assertTrue("saving an unrelated unresolved record alone must not have posted a reminder",reapplyNotices().isEmpty())
        val context=ReadGap(boundary)
        assertTrue(WatchClient.signal(context,setOf(target)));drain()
        assertTrue(context.injected);assertTrue(context.recovered)
        assertEquals("admission failed before reading the APPLIED target",0,peer.reads)
        assertEquals(0,peer.writes)
        assertTrue("the same authenticated connection recovered",WatchClient.available(app))
        assertEquals(setOf(applied,unrelated),JournalStore(app).entries().toSet())
        assertTrue("a recovered USB admission gap must not post an unrelated reapply reminder",reapplyNotices().isEmpty())
        contact(peer)
        assertTrue("the deferred APPLIED target is still checked on contact",peer.reads>0)
        assertEquals(0,peer.writes)
        val resumed=JournalStore(app).entries().associate{it.identity.packageName to (it.state to it.error)}
        assertTrue("after contact: $resumed, notices=${reapplyNotices().map{it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)}}",reapplyNotices().isEmpty())
    }
    @Test fun anExclusiveUsbReadGapDoesNotAnnounceAnUnrelatedPendingUpdate()=
        admissionGapDoesNotAnnounceUnrelatedWork(Boundary.EXCLUSIVE,TargetState.NEEDS_REAPPLY_UPDATE)
    @Test fun aSessionUsbReadGapDoesNotAnnounceAnUnrelatedPendingPolicyReset()=
        admissionGapDoesNotAnnounceUnrelatedWork(Boundary.SESSION,TargetState.NEEDS_REAPPLY_POLICY)

    @Test fun aRealRetryTimerSurvivesAnAdmissionReadGapAndRecoversOnContact() {
        val peer=Peer();start(peer)
        val retry=entry().copy(state=TargetState.FAILED_SAFE,retryHide=true,lastVerifiedTime=System.currentTimeMillis())
        save(retry)
        val context=ReadGap(Boundary.EXCLUSIVE).apply{armed=false}
        assertTrue(WatchClient.signal(context,setOf(target)));drain()
        val timers=WatchClient::class.java.getDeclaredField("retryTimers").apply{isAccessible=true}.get(WatchClient) as Set<*>
        assertTrue("the production retry path must have scheduled the timer",target in timers)
        save(retry.copy(lastVerifiedTime=System.currentTimeMillis()-660_000L))
        context.armed=true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(11));drain()
        assertTrue(context.injected);assertTrue(context.recovered)
        assertEquals(0,peer.reads)
        repeat(2){contact(peer)}
        assertTrue("a fired retry timer must not be lost before Session.run",peer.reads>0)
        assertEquals(0,peer.writes)
    }
}
