package app.bluedotoff

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkCapabilities
import android.os.*
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.adb.*
import app.bluedotoff.core.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

/** R-1 after the Codex reviews (2026-10-19; the boot, stop and watch tests follow Codex's probes). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class RestartRecoveryJobTest {
    private val c get()=ApplicationProvider.getApplicationContext<Application>().also{it.applicationInfo.sourceDir="/data/app/~~fixture/bluedot/base.apk"}
    private val manager get()=c.getSystemService(NotificationManager::class.java)
    private fun pending(state:TargetState=TargetState.PENDING_HIDE)=Entry(Identity("demo.pending",0,10050,listOf("fixture"),1,1,1),"Pending app",256,true,state,1,"firmware","count:1",true,invariantDigest="fixture",settlingVerified=true)
    private fun eligible(){
        Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1);BackgroundWatch.set(c,true);WirelessControls(c).setConsent(true)
        shadowOf(c).grantPermissions(WirelessControls.PERMISSION,Manifest.permission.POST_NOTIFICATIONS);assertTrue(RestartRecoveryJob.eligible(c))
    }
    private class Server:Binder(){
        var requests=0
        init{attachInterface(null,WatchProtocol.DESCRIPTOR)}
        override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean{data.enforceInterface(WatchProtocol.DESCRIPTOR);data.readString();requests++;reply!!.writeNoException();reply.writeString(JSONObject().put("configured",true).toString());return true}
    }
    private fun attach(server:Server){
        val p=JSONObject().put("pid",100).put("uid",2000).put("selinux","u:r:shell:s0").put("abiVerified",true)
            .put("fingerprint",Build.FINGERPRINT).put("oneUi","90000").put("profileId","fixture").put("watchProtocol",1)
            .put("watchId","fixture-id").put("appUpdatedAt",c.packageManager.getPackageInfo(c.packageName,0).lastUpdateTime)
        assertTrue(WatchClient.attach(c,server,p,c.applicationInfo.sourceDir,BuildConfig.VERSION_CODE.toLong()))
    }
    private fun service()=Robolectric.buildService(RestartRecoveryJob::class.java).create().get()
    private fun recover(canStart:()->Boolean)=RestartRecoveryJob::class.java.declaredMethods.single{it.name=="recover"}.apply{isAccessible=true}.invoke(service(),c,canStart) as Boolean
    @After fun clean(){BackgroundWatch.set(c,false);WatchClient.stopNow(c);ForegroundState.stopped()}

    @Test fun waitsForAnyWifiIncludingMeteredAndUnderAVpn(){
        RestartRecoveryJob.schedule(c).getOrThrow()
        val job=c.getSystemService(JobScheduler::class.java).getPendingJob(RestartRecoveryJob.ID)!!
        assertTrue(job.isExpedited)
        val network=job.requiredNetwork!!
        assertTrue(network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        assertFalse(network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        assertFalse(network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
    }
    @Test fun aWriteAnInterruptedRunLeftUnconfirmedKeepsTheRestartNotice(){
        assertEquals(0,UiModel(entries=listOf(pending())).reapplyCount)
        for(state in listOf(TargetState.PENDING_HIDE,TargetState.PENDING_RESTORE,TargetState.PENDING_SHOW))assertEquals(1,RestartRecoveryJob.left(listOf(pending(state))))
        assertEquals(0,RestartRecoveryJob.left(listOf(pending().copy(state=TargetState.APPLIED))))
        assertEquals(0,RestartRecoveryJob.left(listOf(pending().copy(enabled=false))))
    }
    /** A safe failure (rolled back) or a lock next to a success still needs the user: the restart notice stays (Codex rc8). */
    @Test fun aSafeFailureOrALockNextToASuccessKeepsTheRestartNotice(){
        fun record(pkg:String,state:TargetState,enabled:Boolean=true)=Entry(Identity(pkg,0,10050,listOf("fixture"),1,1,1),pkg,256,enabled,state,1,"firmware","count:2",true,settlingVerified=state==TargetState.APPLIED)
        val done=record("demo.done",TargetState.APPLIED)
        for(state in listOf(TargetState.FAILED_SAFE,TargetState.FAILED_ROLLBACK,TargetState.NEEDS_REVIEW,TargetState.SHARED_UID_CHANGED))
            assertEquals(state.name,1,RestartRecoveryJob.left(listOf(done,record("demo.failed",state))))
        assertEquals(0,RestartRecoveryJob.left(listOf(done,record("demo.restored",TargetState.NOT_APPLIED,enabled=false),record("demo.gone",TargetState.MISSING_TARGET))))
    }
    /** Start and stop arrive as separate JobParameters objects: the stop must still reach the running run. */
    @Test fun aParcelledStopReachesTheRunningRun(){
        val constructor=JobParameters::class.java.declaredConstructors.filter{!it.parameterTypes.contains(Parcel::class.java)}.maxBy{it.parameterCount}.apply{isAccessible=true}
        val args=constructor.parameterTypes.map{type->when(type){
            java.lang.Integer.TYPE->RestartRecoveryJob.ID
            java.lang.Boolean.TYPE->false
            java.lang.Long.TYPE->0L
            PersistableBundle::class.java->PersistableBundle()
            Bundle::class.java->Bundle()
            else->null
        }}.toTypedArray()
        val started=constructor.newInstance(*args) as JobParameters
        val parcel=Parcel.obtain()
        val stopped=try{started.writeToParcel(parcel,0);parcel.setDataPosition(0);JobParameters.CREATOR.createFromParcel(parcel)}finally{parcel.recycle()}
        assertNotEquals(started,stopped)
        val service=service()
        val token=AtomicBoolean(false)
        RestartRecoveryJob::class.java.getDeclaredField("current").apply{isAccessible=true}.set(service,token)
        service.onStopJob(stopped)
        assertTrue(token.get())
    }
    @Test fun anAlreadyStoppedRunDoesNotStartTheWatch(){
        eligible();ForegroundState.stopped()
        val last=WatchClient::class.java.getDeclaredField("lastStart").apply{isAccessible=true};last.setLong(WatchClient,-1000L)
        assertTrue(recover{false})
        assertEquals(-1000L,last.getLong(WatchClient))
    }
    /** Only unconfirmed writes after a new boot (e.g. power lost mid-write): kept as they are, and the user is told. */
    @Test fun unconfirmedWritesOnlyOnANewBootStayAndNotifyTheUser(){
        eligible();ForegroundState.stopped();manager.cancelAll();Settings.Global.putInt(c.contentResolver,Settings.Global.BOOT_COUNT,2)
        assertTrue(JournalStore(c).commit(pending()))
        val receiver=LifecycleReceiver();c.registerReceiver(receiver,IntentFilter(Intent.ACTION_BOOT_COMPLETED),android.content.Context.RECEIVER_NOT_EXPORTED)
        try {
            c.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(c.packageName));shadowOf(Looper.getMainLooper()).idle()
            val until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
            while(c.getSystemService(JobScheduler::class.java).getPendingJob(RestartRecoveryJob.ID)==null&&System.nanoTime()<until)Thread.sleep(10)
            assertNotNull(c.getSystemService(JobScheduler::class.java).getPendingJob(RestartRecoveryJob.ID))
            val server=Server();attach(server)
            assertFalse(recover{true})
            assertEquals(TargetState.PENDING_HIDE,JournalStore(c).entries().single().state)
            assertEquals(0,server.requests)
            val shown=shadowOf(manager).allNotifications.single()
            assertEquals("Some apps need checking. Open bluedotOFF to check them.",shown.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        } finally{c.unregisterReceiver(receiver)}
    }
}
