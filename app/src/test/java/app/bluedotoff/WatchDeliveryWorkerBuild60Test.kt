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
class WatchDeliveryWorkerBuild60Test {
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

    @Test fun anEventGetsNoAckUntilTheRealWorkerReadsAndFinishesThatRevision() {
        val helper=Helper(app,listOf(packageName),signature)
        start(helper)
        val (entry,_)=targetEntry();addEntry(entry)
        val (_,release)=blockWorker()
        try {
            assertArrayEquals("receipt before the worker reads must not be acknowledged",longArrayOf(),receive(helper).checked)
        } finally { release.countDown() }
        drain()

        assertTrue("the production backend should have read the target",(helper.packageReads[packageName]?:0)>0)
        assertEquals("a still-hidden target needs no permission write",0,helper.writeRequests)
        val receipt=receive(helper)
        assertArrayEquals("only the completed event revision is acknowledged",longArrayOf(41L),receipt.checked)
        assertTrue(receipt.failed.isEmpty())
    }

    @Test fun restoreIntentCreatedBeforeWorkerReadIsRespectedAndThenAcknowledgedAsHandled() {
        val helper=Helper(app,listOf(packageName),signature)
        start(helper)
        val (entry,_)=targetEntry();addEntry(entry)
        val (_,release)=blockWorker()
        try { assertArrayEquals(longArrayOf(),receive(helper).checked) } finally {
            try { assertTrue(JournalStore(app).commit(entry.copy(restoreRequested=true))) }
            finally { release.countDown() }
        }
        drain()

        assertTrue("restore intent should stop this event before touching Android package state",helper.packageReads.isEmpty())
        assertEquals(0,helper.writeRequests)
        assertArrayEquals(longArrayOf(41L),receive(helper).checked)
        assertTrue(JournalStore(app).entries().single().restoreRequested)
    }

    @Test fun unregisteringBeforeWorkerReadDoesNotReapplyAndDoesNotLeaveAnUnacknowledgeableEvent() {
        val helper=Helper(app,listOf(packageName),signature)
        start(helper)
        val (entry,_)=targetEntry();addEntry(entry)
        val (_,release)=blockWorker()
        try { assertArrayEquals(longArrayOf(),receive(helper).checked) } finally {
            try { assertTrue(JournalStore(app).commit(entry.copy(enabled=false,state=TargetState.NOT_APPLIED,settlingVerified=false))) }
            finally { release.countDown() }
        }
        drain()

        assertTrue(helper.packageReads.isEmpty())
        assertEquals(0,helper.writeRequests)
        assertArrayEquals(longArrayOf(41L),receive(helper).checked)
        assertFalse(JournalStore(app).entries().single().enabled)
    }

    @Test fun failedAndroidReadReturnsFailureAndRetriesOnlyAfterANewRevision() {
        val helper=Helper(app,listOf(packageName),signature).apply{readFailures+=packageName}
        start(helper)
        val (entry,_)=targetEntry();addEntry(entry)
        assertArrayEquals(longArrayOf(),receive(helper).checked)
        drain()

        assertTrue((helper.packageReads[packageName]?:0)>0)
        assertEquals(0,helper.writeRequests)
        val failed=receive(helper)
        assertArrayEquals("a failed pre-read must not be reported as checked",longArrayOf(),failed.checked)
        assertArrayEquals("the helper should receive an explicit failed receipt",longArrayOf(41L),failed.failed)
        val reads=helper.packageReads[packageName]?:0
        val duplicate=receive(helper)
        assertArrayEquals(longArrayOf(),duplicate.checked)
        assertArrayEquals(longArrayOf(41L),duplicate.failed)
        drain()
        assertEquals("the same failed revision must not trigger another device read",reads,helper.packageReads[packageName]?:0)

        assertArrayEquals(longArrayOf(),receive(helper,42L).checked)
        drain()
        assertEquals("the new revision still honors the existing 30 s failure cooldown",reads,helper.packageReads[packageName]?:0)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(60))
        assertTrue(receive(helper,42L).failed.isEmpty())
        drain()
        assertEquals(reads+1,helper.packageReads[packageName]?:0)
        assertArrayEquals(longArrayOf(42L),receive(helper,42L).failed)
    }

    @Test fun aFormerBinderOwnerCannotGetAnAckAfterAReplacementAttaches() {
        val old=Helper(app,listOf(packageName),signature)
        start(old,"old-owner")
        val (entry,_)=targetEntry();addEntry(entry)
        val new=Helper(app,listOf(packageName),signature)
        WatchClient.stopNow(app)
        assertTrue(WatchClient.attach(app,new,profile("new-owner"),app.applicationInfo.sourceDir,BuildConfig.VERSION_CODE.toLong()))
        drain()

        assertArrayEquals("receipt from the replaced binder is rejected without ACK",longArrayOf(),receive(old).checked)
        assertArrayEquals("a newly submitted revision is not acknowledged before its worker pass",longArrayOf(),receive(new,42L).checked)
        drain()
        assertTrue((new.packageReads[packageName]?:0)>0)
        assertArrayEquals(longArrayOf(42L),receive(new,42L).checked)
    }

    @Test fun aPermanentEarlierReadFailureDoesNotStarveTheHealthyPackageOnTheSamePacket() {
        val healthy="$packageName.healthy"
        val helper=Helper(app,listOf(packageName,healthy),signature).apply{readFailures+=packageName}
        start(helper)
        val (a,_)=targetEntry(packageName,uid)
        val (b,_)=targetEntry(healthy,uid+1)
        addEntry(a);addEntry(b) // Deliberately put the failing package first in journal order.
        val packet=linkedMapOf(packageName to 51L,healthy to 52L)
        val eligible=setOf(packageName,healthy)

        val initial=receive(helper,packet,eligible)
        assertTrue(initial.checked.isEmpty());assertTrue(initial.failed.isEmpty())
        drain()
        assertEquals(1,helper.packageReads[packageName]?:0)
        assertEquals("the first fail-fast pass is allowed to stop at A",0,helper.packageReads[healthy]?:0)

        val retry=receive(helper,packet,eligible)
        assertTrue("same revision A is reported failed, not checked",51L in retry.failed)
        drain()
        val completed=receive(helper,packet,eligible)
        assertTrue(51L in completed.failed)
        assertTrue("healthy B must be checked despite A's previously reported failure",52L in completed.checked)
        assertEquals("A's same revision must not be read again",1,helper.packageReads[packageName]?:0)
        assertTrue("the production Android backend read B",(helper.packageReads[healthy]?:0)>0)
        assertEquals(0,helper.writeRequests)
    }

    @Test fun aTransientJournalFailureBeforeAnyTargetReadDoesNotConsumeTheSameRevision() {
        val helper=Helper(app,listOf(packageName),signature)
        start(helper)
        val (entry,_)=targetEntry();addEntry(entry)
        val file=journalFile()
        val original=file.readBytes()
        val (_,release)=blockWorker()
        val revision=71L
        try {
            assertTrue(receive(helper,revision).checked.isEmpty())
            assertTrue(receive(helper,revision).failed.isEmpty())
            file.writeText("transient journal parse failure")
        } finally { release.countDown() }
        drain()
        assertEquals("journal admission failed before the device backend was consulted",0,helper.packageReads[packageName]?:0)

        file.writeBytes(original)
        val resumed=receive(helper,revision)
        assertTrue("a pre-read worker failure must leave this exact event available",resumed.checked.isEmpty()&&resumed.failed.isEmpty())
        drain()
        val checked=receive(helper,revision)
        assertArrayEquals("the restored fixture lets the same owner and revision finish",longArrayOf(revision),checked.checked)
        assertTrue(checked.failed.isEmpty())
        assertTrue((helper.packageReads[packageName]?:0)>0)
        assertEquals("the already-hidden target must not be written",0,helper.writeRequests)
    }

    @Test fun aFollowupJournalReadErrorDoesNotTurnACompletedTargetCheckIntoFailure() {
        val helper=Helper(app,listOf(packageName),signature)
        start(helper)
        val (entry,_)=targetEntry();addEntry(entry)
        val file=journalFile()
        val original=file.readBytes()
        // The watch records its pass totals after the engine has returned. Introduce the fault
        // there so that the target's own reconciliation and durable journal writes stay healthy.
        val fault=java.util.concurrent.atomic.AtomicBoolean()
        val workerContext=object:ContextWrapper(app){
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
                if(name=="watch-diagnostics"&&fault.compareAndSet(false,true))file.writeText("post-check journal error")
                return super.getSharedPreferences(name,mode)
            }
        }
        val revision=81L
        try {
            assertTrue(WatchClient.receiveEvents(workerContext,helper,mapOf(packageName to revision),setOf(packageName)).checked.isEmpty())
            drain()
            assertTrue("the worker reached its post-check step",fault.get())
        } finally { file.writeBytes(original) }

        assertTrue("the mock Android package read completed",(helper.packageReads[packageName]?:0)>0)
        assertEquals(0,helper.writeRequests)
        val reads=helper.packageReads[packageName]?:0
        val receipt=receive(helper,revision)
        assertArrayEquals("a later journal error must preserve the completed check",longArrayOf(revision),receipt.checked)
        assertTrue(receipt.failed.isEmpty())
        assertEquals("replaying a completed receipt must not read again",reads,helper.packageReads[packageName]?:0)
    }

    private fun delayedRetry(withReadFailure:Boolean, withRevision:Boolean=true) {
        val helper=Helper(app,listOf(packageName),signature)
        start(helper)
        val (entry,_)=targetEntry()
        addEntry(entry.copy(state=TargetState.FAILED_SAFE,retryHide=true,lastVerifiedTime=System.currentTimeMillis()))
        val damaged=File(app.cacheDir,"timer-read").apply { mkdirs() }
        File(damaged,"original-bit-journal.json").writeText("incomplete read")
        val injected=java.util.concurrent.atomic.AtomicBoolean()
        val ctx=object:ContextWrapper(app){
            override fun getApplicationContext():Context=this
            override fun getNoBackupFilesDir():File {
                if(withReadFailure&&Thread.currentThread().stackTrace.any{it.className==WatchClient::class.java.name&&it.methodName=="retryLater"}&&injected.compareAndSet(false,true))return damaged
                return super.getNoBackupFilesDir()
            }
        }
        val delivery=WatchDelivery()
        val eligible=setOf(packageName)
        val opened=delivery.offer(if(withRevision)eligible else emptySet(),eligible)
        val revision=opened[packageName]
        if(withRevision){
            val first=WatchClient.receiveEvents(ctx,helper,opened,eligible)
            delivery.acknowledge(opened,first)
            assertTrue(first.checked.isEmpty())
        }else{
            assertTrue(WatchClient.signal(ctx,eligible))
        }
        drain()
        assertEquals(withReadFailure,injected.get())
        assertEquals(0,helper.writeRequests)
        val manager=app.getSystemService(android.app.NotificationManager::class.java)
        val stopAfterFault=manager.activeNotifications.any{it.id==4201}
        assertTrue("the same authenticated watch is still usable after the fault",WatchClient.owns(app,helper))
        assertFalse("this storage fault does not post a stopped notice",stopAfterFault)
        val replay=delivery.offer(emptySet(),eligible)
        val next=WatchClient.receiveEvents(app,helper,replay,eligible)
        delivery.acknowledge(replay,next)
        val timerSet=WatchClient::class.java.getDeclaredField("retryTimers").also{it.isAccessible=true}.get(WatchClient) as Set<*>
        val scheduled=packageName in timerSet
        val recorded=JournalStore(app).entries().single()
        assertTrue(WatchPolicy.retrying(recorded))
        // Mark the isolated journal due, independent of host wall clock, then drive the real Android timer queue.
        addEntry(recorded.copy(lastVerifiedTime=System.currentTimeMillis()-660_000L))
        val startReads=helper.packageReads[packageName]?:0
        repeat(11){
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(1))
            assertTrue(WatchClient.attach(app,helper,profile("watch-owner"),app.applicationInfo.sourceDir,BuildConfig.VERSION_CODE.toLong()))
            val packet=delivery.offer(emptySet(),eligible)
            delivery.acknowledge(packet,WatchClient.receiveEvents(app,helper,packet,eligible))
            drain()
        }
        val readsDuring=(helper.packageReads[packageName]?:0)-startReads
        val newChange=delivery.offer(eligible,eligible)
        WatchClient.receiveEvents(app,helper,newChange,eligible)
        drain()
        val newEventRead=helper.packageReads[packageName]?:0
        if(!withReadFailure&&revision!=null){
            assertArrayEquals(longArrayOf(revision),next.checked)
            assertTrue(scheduled)
        }
        assertTrue("same-owner heartbeats without a new revision recover the failed timer pass",readsDuring>0)
        assertTrue("a genuinely new change reaches the target",newEventRead>0)
        assertTrue("a delayed re-hide must retain the event or have scheduled its timer",revision==null||revision !in next.checked||scheduled)
    }

    @Test fun normalDelayTransfersTheEventToAScheduledTimer()=delayedRetry(false)
    @Test fun aSingleTimerJournalErrorCannotDiscardBothTheEventAndItsWakeup()=delayedRetry(true)
    @Test fun aTimerReadErrorWithoutAnEventRevisionRecoversAtTheNextContact()=delayedRetry(true,false)

    private fun checkThirdAttempt(interrupted:Boolean) {
        val sdk=Build.VERSION.SDK_INT
        val maker=Build.MANUFACTURER
        org.robolectric.util.ReflectionHelpers.setStaticField(Build.VERSION::class.java,"SDK_INT",37)
        org.robolectric.shadows.ShadowBuild.setManufacturer("samsung")
        try {
            // Robolectric creates a new Application between cases. Bind the production store to this case's app.
            WatchClient::class.java.getDeclaredField("retryPolicy").also{it.isAccessible=true}.set(WatchClient,null)
            val helper=Helper(app,listOf(packageName),signature).apply{reportedFine=0x300}
            start(helper)
            val (snap,firmware)=WatchClient.exclusive(app){s->s.run{it.read(packageName) to it.compatibleFirmware()}}
            assertTrue(snap.safe()&&firmware.osSupported&&snap.bit==0x100)
            val now=System.currentTimeMillis()
            val attempts=(3 downTo 1).map{now-it*660_000L}
            val store=app.getSharedPreferences("watch-retry",Context.MODE_PRIVATE)
            val policy=WatchClient::class.java.getDeclaredMethod("retry",Context::class.java).also{it.isAccessible=true}.invoke(WatchClient,app) as WatchRetry
            attempts.forEach{policy.tried(packageName,it)}
            assertEquals(attempts,store.getString(packageName,"").orEmpty().split(',').map{it.toLong()})
            val (base,_)=targetEntry()
            assertFalse("the production store starts with the daily budget exhausted",policy.allowed(base,now,true))
            addEntry(base.copy(identity=snap.identity,firmware=firmware.key,invariantDigest=snap.invariantDigest(),lastVerifiedTime=attempts.last(),
                state=if(interrupted)TargetState.PENDING_HIDE else TargetState.FAILED_SAFE,retryHide=!interrupted,
                settlingVerified=!interrupted,changedByUs=true))
            receive(helper,109L);drain();drain()
            val after=store.getString(packageName,"").orEmpty().split(',').mapNotNull{it.toLongOrNull()}
            val kept=attempts.count{it in after}
            val newCharges=after.count{it !in attempts}
            println("interrupted=$interrupted attemptsBefore=3 previousChargesRetained=$kept newCharges=$newCharges mockWriteRequests=${helper.writeRequests} state=${JournalStore(app).entries().single().state}")
            assertEquals("a previously interrupted third try does not permit a fourth",0,helper.writeRequests)
            assertEquals("none of the three previous tries was a success",3,kept)
            assertEquals(0,newCharges)
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(Build.VERSION::class.java,"SDK_INT",sdk)
            org.robolectric.shadows.ShadowBuild.setManufacturer(maker)
        }
    }

    @Test fun ordinaryFailedHideWithThreeChargesStaysBelowTheDailyLimit()=checkThirdAttempt(false)
    @Test fun interruptedThirdTryMustPreserveAllThreeCharges()=checkThirdAttempt(true)

    private fun coldHandoff(withReadFailure:Boolean) {
        val helper=Helper(app,listOf(packageName),signature)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Settings.Global.putInt(app.contentResolver,Settings.Global.ADB_ENABLED,1)
        BackgroundWatch.set(app,true)
        val id="previously-running-helper"
        // Cold app process, same physical helper. Targets are already configured in that helper.
        assertTrue(app.getSharedPreferences("ui-state",0).edit().putString("watchId",id).putBoolean("watchRunning",true).commit())
        val (e,_)=targetEntry()
        addEntry(e.copy(state=TargetState.FAILED_SAFE,retryHide=true,lastVerifiedTime=System.currentTimeMillis()))
        assertFalse("this represents a fresh ordinary app process",WatchClient.connected())
        val file=journalFile();val bytes=file.readBytes()
        val first=try {
            if(withReadFailure)file.writeText("one-time incomplete read in isolated app")
            runCatching{WatchClient.attach(app,helper,profile(id),app.applicationInfo.sourceDir,BuildConfig.VERSION_CODE.toLong())}
        }finally{if(withReadFailure)file.writeBytes(bytes)}
        println("coldFixture wantedFault=$withReadFailure exception=${first.exceptionOrNull()?.javaClass?.simpleName} accepted=${first.getOrNull()} sameOwner=${WatchClient.owns(app,helper)}")
        assertEquals(withReadFailure,first.isFailure);assertTrue(WatchClient.owns(app,helper))
        repeat(11){
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(1))
            assertTrue(WatchClient.attach(app,helper,profile(id),app.applicationInfo.sourceDir,BuildConfig.VERSION_CODE.toLong()))
            drain()
        }
        val timers=WatchClient::class.java.getDeclaredField("retryTimers").also{it.isAccessible=true}.get(WatchClient) as Set<*>
        val reads=helper.packageReads[packageName]?:0
        val scheduled=packageName in timers
        println("coldSameHelper=true readFaultOnce=${first.isFailure} contacts=11 targetReads=$reads timer=$scheduled live=${WatchClient.owns(app,helper)}")
        assertTrue("after a recovered read, cold journal work must be checked or rescheduled",reads>0||scheduled)
    }

    @Test fun aTransientReadOnSameHelpersFirstReattachDoesNotAbandonTheColdRetry()=coldHandoff(true)
    @Test fun normallyTheFirstReattachPicksUpTheColdRetry()=coldHandoff(false)
}
