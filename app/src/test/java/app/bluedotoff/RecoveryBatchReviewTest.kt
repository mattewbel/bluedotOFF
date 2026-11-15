package app.bluedotoff

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Binder
import android.os.Build
import android.os.Parcel
import android.provider.Settings
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.adb.WatchClient
import app.bluedotoff.adb.WatchDelivery
import app.bluedotoff.adb.WatchProtocol
import app.bluedotoff.adb.LocalAdbSession
import app.bluedotoff.adb.WirelessControls
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** End-to-end app-side receipt tests: authenticated Binder event -> worker -> mocked Android backend -> revision ACK. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class RecoveryBatchReviewTest {
    private val app get()=ApplicationProvider.getApplicationContext<Application>().also{it.applicationInfo.sourceDir="/data/app/fixture/base.apk"}
    @get:Rule val testName=TestName()
    // Each case models its own installation, with an independent 30-second failure cooldown.
    private val packageName get()="test.delivery."+testName.methodName
    private val uid=10551
    private val signature=Signature(byteArrayOf(9,8,7,6,5))
    private val signer get()=BinderBackend.digest(signature.toByteArray())

    private fun parcel(block:Parcel.()->Unit):String {
        val p=Parcel.obtain()
        return try { p.writeNoException();p.block();Base64.encodeToString(p.marshall(),Base64.NO_WRAP) }
        finally { p.recycle() }
    }

    private inner class Helper(private val context:Application,private val targets:List<String>,private val signer:Signature):Binder() {
        val packageReads=mutableMapOf<String,Int>()
        val readFailures=mutableSetOf<String>()
        var writeRequests=0
        var reportedFine=0x200
        init { attachInterface(null,WatchProtocol.DESCRIPTOR) }
        private fun targetUid(target:String)=uid+targets.indexOf(target)
        private fun packageInfo(target:String):PackageInfo {
            val signed=SigningInfo().also{shadowOf(it).setSignatures(arrayOf(signer))}
            return PackageInfo().apply {
                packageName=target;versionCode=7;firstInstallTime=1_700_000_000_000L;lastUpdateTime=1_700_000_100_000L
                applicationInfo=ApplicationInfo().apply{this.packageName=target;uid=targetUid(target);flags=ApplicationInfo.FLAG_INSTALLED;sourceDir="/data/app/$target/base.apk"}
                requestedPermissions=arrayOf(BinderBackend.FINE,BinderBackend.COARSE)
                requestedPermissionsFlags=intArrayOf(PackageInfo.REQUESTED_PERMISSION_GRANTED,PackageInfo.REQUESTED_PERMISSION_GRANTED)
                signingInfo=signed;signatures=arrayOf(signer)
            }
        }
        private fun response(q:JSONObject):JSONObject {
            val op=q.getString("op")
            val method:String
            val descriptor:String
            val bytes:String
            when(op){
                "GET_PACKAGE_INFO"->{
                    val target=q.getString("package")
                    packageReads[target]=(packageReads[target]?:0)+1
                    method="getPackageInfo";descriptor="android.content.pm.IPackageManager"
                    if(target in readFailures) bytes=parcel{writeException(IllegalStateException("fixture read failure"))}
                    else bytes=parcel{writeTypedObject(packageInfo(target),0)}
                }
                "GET_UID_PEERS"->{val index=q.getInt("uid")-uid;require(index in targets.indices);val target=targets[index];method="getPackagesForUid";descriptor="android.content.pm.IPackageManager";bytes=parcel{writeStringArray(arrayOf(target))}}
                "GET_PERMISSION_FLAGS"->{
                    method="getPermissionFlags";descriptor="android.permission.IPermissionManager"
                    bytes=parcel{writeInt(if(q.getString("permission")=="FINE")reportedFine else 0x300)}
                }
                "GET_RAW_APP_OPS"->{method="checkOperationRaw";descriptor="com.android.internal.app.IAppOpsService";bytes=parcel{writeInt(4)}
                }
                "UPDATE_FINE_SENSITIVITY_BIT","UPDATE_SHARED_FINE_SENSITIVITY_BIT"->{writeRequests++;error("read-only fixture rejected a setter")}
                else->error("unexpected helper request $op")
            }
            return JSONObject().put("method",method).put("descriptor",descriptor).put("parcel",bytes)
        }
        override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
            data.enforceInterface(WatchProtocol.DESCRIPTOR)
            val q=JSONObject(data.readString()!!)
            check(data.dataAvail()==0)
            val result=when(code){
                WatchProtocol.REQUEST->response(q)
                WatchProtocol.CONFIGURE->JSONObject().put("configured",true)
                WatchProtocol.STOP->JSONObject().put("stopped",true)
                else->error("unexpected protocol code $code")
            }
            reply!!.writeNoException();reply.writeString(result.toString());return true
        }
    }

    private fun profile(id:String)=JSONObject().put("pid",100).put("uid",2000).put("selinux","u:r:shell:s0")
        .put("abiVerified",true).put("historyAbiVerified",true).put("indicatorAbiVerified",true)
        .put("fingerprint",Build.FINGERPRINT).put("oneUi","90000").put("profileId","fixture")
        .put("watchProtocol",1).put("watchId",id)
        .put("appUpdatedAt",app.packageManager.getPackageInfo(app.packageName,0).lastUpdateTime)

    private fun start(helper:Helper,id:String="watch-owner") {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Settings.Global.putInt(app.contentResolver,Settings.Global.ADB_ENABLED,1)
        BackgroundWatch.set(app,true)
        assertTrue(WatchClient.attach(app,helper,profile(id),app.applicationInfo.sourceDir,BuildConfig.VERSION_CODE.toLong()))
        worker().submit{}.get(5,TimeUnit.SECONDS)
    }
    private fun worker()=WatchClient::class.java.getDeclaredField("worker").apply{isAccessible=true}.get(WatchClient) as ExecutorService
    private fun blockWorker():Pair<CountDownLatch,CountDownLatch> {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        worker().execute{entered.countDown();check(release.await(10,TimeUnit.SECONDS))}
        assertTrue("worker did not enter gate",entered.await(5,TimeUnit.SECONDS))
        return entered to release
    }
    private fun targetEntry(target:String=packageName,targetUid:Int=uid):Pair<Entry,Snapshot> {
        val identity=Identity(target,0,targetUid,listOf(signer),1_700_000_000_000L,7,1_700_000_100_000L)
        val snapshot=Snapshot(identity,"Fixture",listOf(target),true,true,true,false,0x200,0x300,0,4,4,false,
            applicationScope=ApplicationScope.ALL)
        val firmware=Firmware(Build.MANUFACTURER,Build.VERSION.SDK_INT,"90000",Build.FINGERPRINT,Build.ID,Build.MODEL,"fixture-profile")
        return Entry(identity,"Fixture",0x100,true,TargetState.APPLIED,System.currentTimeMillis(),firmware.key,
            BootSession.current(app),true,invariantDigest=snapshot.invariantDigest(),settlingVerified=true) to snapshot
    }
    private fun addEntry(entry:Entry) { assertTrue(JournalStore(app).commit(entry)) }
    private fun journalFile()=File(app.noBackupFilesDir,"original-bit-journal.json")
    private fun receive(helper:Helper,revision:Long=41L)=WatchClient.receiveEvents(app,helper,mapOf(packageName to revision),setOf(packageName))
    private fun receive(helper:Helper,packet:Map<String,Long>,eligible:Set<String> = packet.keys)=WatchClient.receiveEvents(app,helper,packet,eligible)
    private fun drain() { worker().submit{}.get(8,TimeUnit.SECONDS) }

    @After fun cleanup() {
        drain()
        BackgroundWatch.set(app,false)
        WatchClient.stopNow(app)
        LifecycleEvents.widget=null;LifecycleEvents.listener=null
    }

    private fun reconnectBatch(fault:Boolean,restoreSecond:Boolean=false) {
        val second="$packageName.second"
        val helper=Helper(app,listOf(packageName,second),signature)
        addEntry(targetEntry(packageName,uid).first.copy(state=TargetState.NEEDS_REAPPLY_UPDATE))
        addEntry(targetEntry(second,uid+1).first.copy(state=TargetState.NEEDS_REAPPLY_UPDATE))
        app.getSharedPreferences("ui-state",0).edit().putString("watchId","watch-owner").putBoolean("watchRunning",true).commit()
        if(fault)helper.readFailures+=packageName
        start(helper)
        val firstReads=helper.packageReads[packageName]?:0
        val initialSecond=helper.packageReads[second]?:0
        assertTrue(firstReads>0)
        if(fault)assertEquals(0,initialSecond)
        if(restoreSecond)SafetyEngine.requestRestore(JournalStore(app),setOf(second))
        helper.readFailures.clear()
        repeat(5) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(1))
            assertTrue(WatchClient.attach(app,helper,profile("watch-owner"),app.applicationInfo.sourceDir,BuildConfig.VERSION_CODE.toLong()))
            WatchClient.receiveEvents(app,helper,emptyMap(),setOf(packageName,second))
            drain()
        }
        val after=helper.packageReads[second]?:0
        if(restoreSecond){
            assertEquals("a restore request cancels deferred automatic work",0,after)
            assertTrue(JournalStore(app).entries().single{it.identity.packageName==second}.restoreRequested)
            assertEquals(0,helper.writeRequests)
            return
        }
        assertEquals("a failed earlier read is not repeated on every contact",firstReads,helper.packageReads[packageName]?:0)
        // Positive control on the same live connection: an independent new signal reaches the second app.
        receive(helper,mapOf(second to 903L))
        drain()
        println("firstReadFailed=$fault firstReads=$firstReads initialSecond=$initialSecond afterFiveContacts=$after afterNewChange=${helper.packageReads[second]}")
        if(!fault)assertTrue("healthy startup reaches the second target immediately",initialSecond>0)
        assertTrue("second registered app never got its missed startup check after the first app recovered",after>0)
    }
    @Test fun normalStartupBatchReachesSecond()=reconnectBatch(false)
    @Test fun startupBatchRecoversAnUnstartedSecondTarget()=reconnectBatch(true)
    @Test fun aRestoreBeforeTheNextContactCancelsTheUnstartedWork()=reconnectBatch(true,true)

    private fun batch(targets:List<String>, source:ReapplySource=ReapplySource.APP_OPEN, expected:Map<String,Identity>) =
        BatchPipeline.run(app,targets,emptyMap(),true,false,true,false,targets.toSet(),source,{true},source==ReapplySource.RESTART,{},expected)

    private fun <T> supportedPhone(block:()->T):T {
        val sdk=Build.VERSION.SDK_INT;val maker=Build.MANUFACTURER
        org.robolectric.util.ReflectionHelpers.setStaticField(Build.VERSION::class.java,"SDK_INT",37)
        org.robolectric.shadows.ShadowBuild.setManufacturer("samsung");ForegroundState.started()
        return try{block()}finally{ForegroundState.stopped();org.robolectric.util.ReflectionHelpers.setStaticField(Build.VERSION::class.java,"SDK_INT",sdk);org.robolectric.shadows.ShadowBuild.setManufacturer(maker)}
    }
    private fun reinstallQueued(source:ReapplySource,recognized:Boolean)=supportedPhone {
        val helper=Helper(app,listOf(packageName),signature);start(helper)
        val(snap,fw)=WatchClient.exclusive(app){s->s.run{it.read(packageName) to it.compatibleFirmware()}}
        val current=targetEntry().first.copy(identity=snap.identity,firmware=fw.key,invariantDigest=snap.invariantDigest())
        val old=current.copy(identity=current.identity.copy(firstInstallTime=current.identity.firstInstallTime-1000),state=TargetState.NEEDS_REAPPLY_UPDATE);addEntry(old)
        val queued=UiModel(entries=JournalStore(app).entries()).reapplyTargetPackages;assertEquals(listOf(packageName),queued)
        if(recognized){val seen=Reconcile.read(old,snap,old.firmware,old.bootSession);assertEquals(TargetState.INACTIVE_INSTALL,seen.state);addEntry(seen)}
        val result=batch(queued,source,mapOf(packageName to old.identity));val saved=JournalStore(app).entries().single()
        assertTrue(result.results.all{!it.success});assertEquals(0,helper.writeRequests);assertEquals(TargetState.INACTIVE_INSTALL,saved.state);assertFalse(saved.enabled)
    }
    @Test fun automaticOpenKeepsQueuedInstallIdentity()=reinstallQueued(ReapplySource.APP_OPEN,true)
    @Test fun automaticRestartKeepsQueuedInstallIdentity()=reinstallQueued(ReapplySource.RESTART,false)

    @Test fun confirmedHoldBeforeBootIsNotRestartWork()=supportedPhone {
        Settings.Global.putInt(app.contentResolver,Settings.Global.BOOT_COUNT,7)
        val helper=Helper(app,listOf(packageName),signature).apply{reportedFine=0x300};start(helper)
        val(snap,fw)=WatchClient.exclusive(app){s->s.run{it.read(packageName) to it.compatibleFirmware()}}
        val prior=targetEntry().first.copy(identity=snap.identity,firmware=fw.key,bootSession="earlier-boot",originalBit=0x100,state=TargetState.FAILED_ROLLBACK,settlingVerified=false,retryHide=false,invariantDigest=snap.invariantDigest())
        val held=Reconcile.read(prior,snap,fw.key,BootSession.current(app));assertEquals(TargetState.FAILED_SAFE,held.state);assertFalse(WatchPolicy.retrying(held));addEntry(held)
        assertTrue(JournalStore(app).transform{LocalEntryEvents.read(it,Build.FINGERPRINT,BootSession.current(app)){snap.identity}})
        assertTrue(UiModel(entries=JournalStore(app).entries()).reapplyTargetPackages.isEmpty());assertEquals(0,helper.writeRequests)
        assertEquals(held,BootEvents.fromBroadcast(held,BootSession.current(app)))
        assertEquals(TargetState.NEEDS_REAPPLY_BOOT,BootEvents.fromBroadcast(held,"count:8").state)
        assertEquals(TargetState.NEEDS_REAPPLY_UPDATE,Reconcile.update(held).state)
    }

    @Test fun permissionMismatchTimerPreservesThreeAttemptQuota()=supportedPhone {
        Settings.Global.putInt(app.contentResolver,Settings.Global.BOOT_COUNT,7)
        val helper=Helper(app,listOf(packageName),signature);start(helper)
        val(snap,fw)=WatchClient.exclusive(app){s->s.run{it.read(packageName) to it.compatibleFirmware()}}
        val now=System.currentTimeMillis();val old=targetEntry().first.copy(identity=snap.identity,firmware=fw.key,bootSession=BootSession.current(app),lastVerifiedTime=now-660_000,state=TargetState.FAILED_SAFE,settlingVerified=false,retryHide=true,changedByUs=false,invariantDigest=snap.invariantDigest())
        val policy=WatchClient::class.java.getDeclaredMethod("retry",Context::class.java).apply{isAccessible=true}.invoke(WatchClient,app) as WatchRetry
        addEntry(old);policy.tried(packageName,now-1_400_000);policy.tried(packageName,now-700_000)
        WatchClient::class.java.getDeclaredMethod("retryLater",Context::class.java,Set::class.java).apply{isAccessible=true}.invoke(WatchClient,app,setOf(packageName));policy.tried(packageName,now)
        val denied=Reconcile.read(old,snap.copy(fineGranted=false),fw.key,old.bootSession);assertEquals(TargetState.UNSUPPORTED_STATE,denied.state);addEntry(denied);assertFalse(policy.allowed(old,now,true))
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(11));drain()
        val recovered=Reconcile.read(denied,snap,fw.key,old.bootSession)
        assertTrue(WatchPolicy.retrying(recovered))
        addEntry(recovered)
        helper.reportedFine=0x300
        receive(helper);drain()
        assertFalse(policy.allowed(recovered,System.currentTimeMillis()))
        assertEquals(0,helper.writeRequests)
    }

    @Test fun healthyAutomaticBatchStillChecksTarget()=supportedPhone {
        val helper=Helper(app,listOf(packageName),signature);start(helper)
        val(snap,fw)=WatchClient.exclusive(app){s->s.run{it.read(packageName) to it.compatibleFirmware()}}
        val entry=targetEntry().first.copy(identity=snap.identity,firmware=fw.key,invariantDigest=snap.invariantDigest(),state=TargetState.NEEDS_REAPPLY_UPDATE);addEntry(entry)
        val quota=app.getSharedPreferences("watch-retry",0)
        val now=System.currentTimeMillis()
        assertTrue(quota.edit().putString(packageName,listOf(now-3,now-2,now-1).joinToString(",")).commit())
        val result=batch(listOf(packageName),ReapplySource.APP_OPEN,mapOf(packageName to entry.identity))
        assertTrue(result.results.single().success)
        assertEquals(TargetState.APPLIED,JournalStore(app).entries().single().state)
        assertTrue(JournalStore(app).entries().single().settlingVerified)
        assertEquals(0,helper.writeRequests)
        assertFalse("A completed foreground verification ends the failed retry episode",quota.contains(packageName))
    }

    @Test fun queuedRequestDoesNotTouchIndependentlyRegisteredReplacement()=supportedPhone {
        val helper=Helper(app,listOf(packageName),signature).apply{reportedFine=0x300}
        start(helper)
        val (before,fw)=WatchClient.exclusive(app){s->s.run{it.read(packageName) to it.compatibleFirmware()}}
        val active=targetEntry().first.copy(identity=before.identity,firmware=fw.key,state=TargetState.NEEDS_REAPPLY_UPDATE,invariantDigest=before.invariantDigest())
        val earlier=active.identity.copy(firstInstallTime=active.identity.firstInstallTime-1)
        addEntry(active)
        val result=batch(listOf(packageName),expected=mapOf(packageName to earlier))
        assertFalse(result.results.single().success)
        assertEquals(active,JournalStore(app).entries().single())
        assertEquals(0,helper.writeRequests)
    }

    @Test fun actualRestartJobRechecksInstallAfterWaitingForAdbLock()=supportedPhone {
        val helper=Helper(app,listOf(packageName),signature).apply{reportedFine=0x200};start(helper)
        val(snap,fw)=WatchClient.exclusive(app){s->s.run{it.read(packageName) to it.compatibleFirmware()}}
        val old=targetEntry().first.copy(identity=snap.identity.copy(firstInstallTime=snap.identity.firstInstallTime-1000),firmware=fw.key,state=TargetState.NEEDS_REAPPLY_UPDATE,invariantDigest=snap.invariantDigest());addEntry(old)
        shadowOf(app).grantPermissions(WirelessControls.PERMISSION)
        app.getSharedPreferences("wireless-management",0).edit().putBoolean("enabled",true).commit();assertTrue(RestartRecoveryJob.eligible(app));ForegroundState.stopped()
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val task=LocalAdbSession.exclusive {
                val submitted=executor.submit {
                    val job=RestartRecoveryJob();val recover=RestartRecoveryJob::class.java.getDeclaredMethod("recover",Context::class.java,kotlin.jvm.functions.Function0::class.java).apply{isAccessible=true}
                    recover.invoke(job,app,{true})
                }
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
                while(!BatchRuntime.state.value.busy&&System.nanoTime()<deadline&&!submitted.isDone)Thread.sleep(10)
                assertTrue("restart recovery never queued its batch",BatchRuntime.state.value.busy)
                assertTrue(JournalStore(app).transform{LocalEntryEvents.read(it,Build.FINGERPRINT,old.bootSession){snap.identity}})
                assertEquals(TargetState.INACTIVE_INSTALL,JournalStore(app).entries().single().state)
                submitted
            }
            task.get(12,TimeUnit.SECONDS)
            val saved=JournalStore(app).entries().single()
            assertFalse(saved.enabled);assertEquals(TargetState.INACTIVE_INSTALL,saved.state);assertEquals(0,helper.writeRequests)
        } finally {executor.shutdownNow()}
    }

}
