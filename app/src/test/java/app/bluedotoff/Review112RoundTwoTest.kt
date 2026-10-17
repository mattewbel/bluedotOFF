package app.bluedotoff

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Parcel
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.adb.*
import app.bluedotoff.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Independent review round 2 (2026-10-17, artifacts/archive/reviews-and-reports/review-112-round2), kept as tests. Independent exceptional-path audit. Fake network/settings/target backend; no native ADB or real phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class Review112RoundTwoTest {
    private val context get()=ApplicationProvider.getApplicationContext<Application>()
    private val originalFetcher=UpdateCheck.fetcher
    @After fun clean(){
        UpdateCheck.fetcher=originalFetcher
        while(ForegroundState.active)ForegroundState.stopped()
        LocalAdbSession.closeHeld()
        context.getSharedPreferences("wireless-management",0).edit().clear().commit()
        context.getSharedPreferences("update-check",0).edit().clear().commit()
        UpdateCheckJob.cancel(context)
    }
    private fun online(){
        val cm=context.getSystemService(ConnectivityManager::class.java)
        val caps=ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork,caps)
    }
    private fun hold(){
        context.getSharedPreferences("wireless-management",0).edit().putBoolean("enabled",true).putBoolean("heldOn",true).commit()
        shadowOf(context).grantPermissions(WirelessControls.PERMISSION)
        Settings.Global.putInt(context.contentResolver,"adb_wifi_enabled",1)
    }
    @Test fun reopenDuringTransportCloseKeepsRadioOnAndCanReleaseOnNextExit(){
        hold()
        val closing=CountDownLatch(1);val proceed=CountDownLatch(1)
        val wire=object:AdbWire{
            override fun write(bytes:ByteArray){}
            override fun read(length:Int):ByteArray=error("Not used")
            override fun startTls(){}
            override fun close(){closing.countDown();check(proceed.await(3,TimeUnit.SECONDS))}
        }
        LocalAdbSession::class.java.getDeclaredField("held").apply{isAccessible=true}.set(LocalAdbSession,LocalAdbConnection(wire))
        val release=Thread{WirelessWork.releaseIfHidden(context)}
        try{
            release.start();assertTrue(closing.await(3,TimeUnit.SECONDS))
            ForegroundState.started();WirelessReleaseJob.cancel(context);proceed.countDown()
            release.join(3000);assertFalse(release.isAlive)
            assertEquals(1,Settings.Global.getInt(context.contentResolver,"adb_wifi_enabled",0));assertTrue(WirelessWork.held(context))
            assertFalse(LocalAdbSession.holdsConnection)
            ForegroundState.stopped();WirelessWork.releaseIfHidden(context)
            assertEquals(0,Settings.Global.getInt(context.contentResolver,"adb_wifi_enabled",0));assertFalse(WirelessWork.held(context))
        }finally{proceed.countDown();release.join(3000)}
    }
    @Test fun explicitReleaseNowStillWorksWhileVisible(){
        hold();ForegroundState.started();WirelessWork.releaseNow(context)
        assertEquals(0,Settings.Global.getInt(context.contentResolver,"adb_wifi_enabled",0));assertFalse(WirelessWork.held(context))
    }
    @Test fun queuedManualCheckMustNotSendAfterUserTurnsOff(){
        UpdateCheck.set(context,true);online()
        val entered=CountDownLatch(1);val proceed=CountDownLatch(1);val calls=AtomicInteger()
        UpdateCheck.fetcher={if(calls.incrementAndGet()==1){entered.countDown();check(proceed.await(3,TimeUnit.SECONDS))};null}
        val first=Thread{UpdateCheck.check(context)}
        val queued=Thread{UpdateCheck.check(context,force=true)}
        try{
            first.start();assertTrue(entered.await(3,TimeUnit.SECONDS));queued.start()
            val deadline=System.nanoTime()+3_000_000_000L
            while(queued.state!=Thread.State.BLOCKED&&System.nanoTime()<deadline)Thread.sleep(1)
            assertEquals(Thread.State.BLOCKED,queued.state)
            UpdateCheck.set(context,false);proceed.countDown();first.join(3000);queued.join(3000)
            assertFalse(first.isAlive);assertFalse(queued.isAlive)
            assertEquals("only the request started before opt-out is permitted",1,calls.get())
        }finally{proceed.countDown();first.join(3000);if(queued.state!=Thread.State.NEW)queued.join(3000)}
    }
    @Test fun alreadyStartedCheckMayFinishAfterOffButMustNotAnnounce(){
        UpdateCheck.set(context,true);online()
        val entered=CountDownLatch(1);val proceed=CountDownLatch(1)
        UpdateCheck.fetcher={entered.countDown();check(proceed.await(3,TimeUnit.SECONDS));"99.0.0"}
        val first=Thread{UpdateCheck.check(context)}
        try{
            first.start();assertTrue(entered.await(3,TimeUnit.SECONDS));UpdateCheck.set(context,false);proceed.countDown();first.join(3000)
            assertFalse(first.isAlive);assertFalse(UpdateCheck.enabled(context));assertNull(UpdateCheck.pending(context))
            assertTrue(context.getSystemService(android.app.job.JobScheduler::class.java).allPendingJobs.isEmpty())
        }finally{proceed.countDown();first.join(3000)}
    }
    @Test fun overlappingAutomaticFailureStaysBounded(){
        UpdateCheck.set(context,true);online()
        val entered=CountDownLatch(1);val proceed=CountDownLatch(1);val calls=AtomicInteger()
        UpdateCheck.fetcher={calls.incrementAndGet();entered.countDown();check(proceed.await(3,TimeUnit.SECONDS));throw java.io.IOException("fake timeout")}
        val first=Thread{UpdateCheck.check(context)}
        val next=List(3){Thread{UpdateCheck.check(context)}}
        try{
            first.start();assertTrue(entered.await(3,TimeUnit.SECONDS));next.forEach{it.start()};proceed.countDown()
            first.join(3000);next.forEach{it.join(3000)}
            assertEquals(1,calls.get());assertTrue(context.getSharedPreferences("update-check",0).getLong("lastAttempt",0)>0)
        }finally{proceed.countDown();first.join(3000);next.filter{it.state!=Thread.State.NEW}.forEach{it.join(3000)}}
    }
    private data class Spec(val state:TargetState,val original:Int=256)
    private class Fixture {
        val current=linkedMapOf<String,Snapshot>();val writes=mutableListOf<Int>();var failAfterWrite=false
    }
    private fun recovery(specs:List<Spec>,change:(Fixture)->Unit={},verify:(String?,List<Entry>,Fixture)->Unit){
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        try{
            val a=controller.get();val journal=JournalStore(a);val f=Fixture()
            val fw=Firmware("samsung",37,"90000","fw","id","model","abi")
            for((i,spec)in specs.withIndex()){
                val names=listOf("test.edge.g${i}a","test.edge.g${i}b")
                val base=names.map{p->Snapshot(Identity(p,0,10129+i,listOf("signer"),1,1,1),p,names,true,true,true,false,0x300,0x300,0,4,4,false,sharedUserId="test.shared$i")}
                assertTrue(journal.commitGroup(base.map{s->Entry(s.identity,s.label,spec.original,true,spec.state,0,fw.key,"boot",false,invariantDigest=s.invariantDigest(),sharedUserId=s.sharedUserId,groupIdentities=base.map{it.identity})}))
                base.forEach{f.current[it.identity.packageName]=it.copy(fineFlags=0x200)}
            }
            change(f)
            val fake=object:BinderBackend(a){
                override fun liveShellServer()=true
                override fun verify()=fw
                override fun compatibleFirmware()=fw
                override fun read(packageName:String):Snapshot {if(f.failAfterWrite&&f.writes.isNotEmpty())throw java.io.IOException("fake helper death");return f.current.getValue(packageName)}
                override fun recordVerification(phase:String,s:Snapshot,wrote:Boolean){}
                override fun writeFineGroupChecked(expected:List<Snapshot>,wanted:Int){
                    assertTrue(SharedGroupPolicy.same(expected,expected.map{read(it.identity.packageName)},expected.first().bit))
                    f.writes+=wanted;expected.forEach{s->f.current[s.identity.packageName]=f.current.getValue(s.identity.packageName).copy(fineFlags=(s.fineFlags and 256.inv())or wanted)}
                }
                override fun <T> transact(service:String,descriptor:String,method:String,input:(Parcel)->Unit,output:(Parcel)->T):T=error("Not used")
            }
            MainActivity::class.java.getDeclaredField("store").apply{isAccessible=true}.set(a,journal)
            MainActivity::class.java.getDeclaredField("backend").apply{isAccessible=true}.set(a,fake)
            val notice=try{MainActivity::class.java.getDeclaredMethod("recoverInterrupted").apply{isAccessible=true}.invoke(a) as String?}
                catch(e:java.lang.reflect.InvocationTargetException){throw AssertionError("Recovery failed: writes=${f.writes}, bits=${f.current.values.map{it.bit}}, states=${journal.entries().map{it.state}}, actual=${e.cause?.javaClass?.simpleName}",e.cause)}
            verify(notice,journal.entries(),f)
        }finally{controller.destroy()}
    }
    @Test fun interruptedHideCanRecoverAfterTargetAppUpdate(){
        recovery(listOf(Spec(TargetState.PENDING_HIDE)),{f->f.current.replaceAll{p,s->if(p.endsWith("g0a"))s.copy(identity=s.identity.copy(versionCode=2,lastUpdateTime=2)) else s}}){notice,entries,f->
            assertEquals(SharedGroupEngine.INTERRUPTED,notice)
            assertTrue(entries.all{it.state==TargetState.NEEDS_REAPPLY_POLICY})
            assertEquals(1,entries.count{it.identity.versionCode==2L})
            assertTrue(f.current.values.all{it.bit==256})
        }
    }
    @Test fun interruptedRestoreCanRecoverAfterTargetAppUpdate(){
        recovery(listOf(Spec(TargetState.PENDING_RESTORE)),{f->f.current.replaceAll{_,s->s.copy(identity=s.identity.copy(versionCode=2,lastUpdateTime=2))}}){notice,entries,f->
            assertEquals("2개 앱을 원래 표시 상태로 복원했어요.",notice)
            assertTrue(entries.all{it.state==TargetState.NOT_APPLIED&&!it.enabled&&it.identity.versionCode==2L})
            assertTrue(f.current.values.all{it.bit==256})
        }
    }
    @Test fun mixedSuccessAndLockedGroupDoesNotClaimOverallSuccess(){
        recovery(listOf(Spec(TargetState.PENDING_HIDE),Spec(TargetState.PENDING_RESTORE)),{f->f.current.replaceAll{p,s->if(p.contains("g0"))s.copy(fineFlags=0x210) else s}}){notice,entries,_->
            assertEquals("중단된 공유 앱 묶음의 상태가 기록과 달라 변경하지 않았습니다.",notice)
            assertEquals(2,entries.count{it.state==TargetState.NEEDS_REVIEW});assertEquals(2,entries.count{it.state==TargetState.NOT_APPLIED})
        }
    }
    @Test fun helperDeathDuringRecoveryVerificationDoesNotClaimSuccess(){
        recovery(listOf(Spec(TargetState.PENDING_HIDE)),{it.failAfterWrite=true}){notice,entries,_->
            assertTrue(entries.all{it.state==TargetState.FAILED_ROLLBACK&&it.enabled})
            assertNotEquals(SharedGroupEngine.INTERRUPTED,notice)
            assertEquals("중단된 공유 앱 묶음을 원래대로 되돌렸는지 확인하지 못했습니다. 추가 변경을 차단했습니다.",notice)
        }
    }
    @Test fun originalOffRecoveryNeverTurnsItOn(){
        recovery(listOf(Spec(TargetState.PENDING_HIDE,0))){notice,entries,f->
            assertEquals(SharedGroupEngine.INTERRUPTED,notice);assertTrue(f.writes.isEmpty())
            assertTrue(entries.all{it.originalBit==0&&it.state==TargetState.NEEDS_REAPPLY_POLICY});assertTrue(f.current.values.all{it.bit==0})
        }
    }
}
