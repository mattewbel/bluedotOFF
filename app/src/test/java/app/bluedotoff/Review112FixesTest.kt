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
import java.util.concurrent.atomic.AtomicInteger

/** Independent 1.1.2 review (2026-10-17, artifacts/review-112): the two reproductions (adapted) plus update-check limits.
 *  Fake settings/backend/network only; no phone, native ADB or system permission writes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class Review112FixesTest {
    private val context get()=ApplicationProvider.getApplicationContext<Application>()
    @After fun cleanup(){
        while(ForegroundState.active)ForegroundState.stopped()
        context.getSharedPreferences("wireless-management",0).edit().clear().commit()
        context.getSharedPreferences("update-check",0).edit().clear().commit()
        UpdateCheckJob.cancel(context)
    }

    // 2. A release waiting in closeHeld must not turn wireless debugging off once a screen opened meanwhile.
    @Test fun reopeningWhileReleaseWaitsKeepsWirelessDebuggingOn(){
        context.getSharedPreferences("wireless-management",0).edit().putBoolean("enabled",true).putBoolean("heldOn",true).commit()
        shadowOf(context).grantPermissions(WirelessControls.PERMISSION)
        Settings.Global.putInt(context.contentResolver,"adb_wifi_enabled",1)
        val sessionLock=LocalAdbSession::class.java.getDeclaredField("lock").apply{isAccessible=true}.get(LocalAdbSession)
        val release=Thread{WirelessWork.releaseIfHidden(context)}
        synchronized(sessionLock){
            release.start()
            val deadline=System.nanoTime()+3_000_000_000L
            while(release.state!=Thread.State.BLOCKED&&System.nanoTime()<deadline)Thread.sleep(1)
            assertTrue(release.stackTrace.any{it.className.endsWith("LocalAdbSession")&&it.methodName=="closeHeld"})
            ForegroundState.started();WirelessReleaseJob.cancel(context)
        }
        release.join(3000);assertFalse(release.isAlive)
        assertEquals(1,Settings.Global.getInt(context.contentResolver,"adb_wifi_enabled",0))
        assertTrue("still held for the next leave",WirelessWork.held(context))
        // Leaving for real afterwards still turns it off.
        ForegroundState.stopped();WirelessWork.releaseIfHidden(context)
        assertEquals(0,Settings.Global.getInt(context.contentResolver,"adb_wifi_enabled",0))
    }

    // 1. The notice after settling an interrupted shared group follows the real outcome.
    private fun recoverWith(pending:TargetState,flags:Int,original:Int=256):Pair<String?,List<Entry>>{
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        try{
            val activity=controller.get()
            val names=listOf("test.recovery.a","test.recovery.b")
            val base=names.map{p->Snapshot(Identity(p,0,10129,listOf("signer"),1,1,1),p,names,true,true,true,false,0x300,0x300,0,4,4,false,sharedUserId="test.shared")}
            val fw=Firmware("samsung",37,"90000","fw","id","model","abi")
            val journal=JournalStore(activity)
            assertTrue(journal.commitGroup(base.map{s->Entry(s.identity,s.label,original,true,pending,0,fw.key,"boot",false,invariantDigest=s.invariantDigest(),sharedUserId=s.sharedUserId,groupIdentities=base.map{it.identity})}))
            val current=base.associate{it.identity.packageName to it.copy(fineFlags=flags)}.toMutableMap()
            val fake=object:BinderBackend(activity){
                override fun liveShellServer()=true
                override fun verify()=fw
                override fun compatibleFirmware()=fw
                override fun read(packageName:String)=current.getValue(packageName)
                override fun recordVerification(phase:String,s:Snapshot,wrote:Boolean){}
                override fun writeFineGroupChecked(expected:List<Snapshot>,wanted:Int){current.replaceAll{_,s->s.copy(fineFlags=(s.fineFlags and 256.inv()) or wanted)}}
                override fun <T> transact(service:String,descriptor:String,method:String,input:(Parcel)->Unit,output:(Parcel)->T):T=error("Not used")
            }
            MainActivity::class.java.getDeclaredField("store").apply{isAccessible=true}.set(activity,journal)
            MainActivity::class.java.getDeclaredField("backend").apply{isAccessible=true}.set(activity,fake)
            val notice=MainActivity::class.java.getDeclaredMethod("recoverInterrupted").apply{isAccessible=true}.invoke(activity) as String?
            return notice to journal.entries()
        }finally{controller.destroy()}
    }
    @Test fun lockedGroupIsNotAnnouncedAsRestored(){
        // Another FINE flag changed meanwhile (0x210 instead of 0x200): locked, nothing written.
        val (notice,entries)=recoverWith(TargetState.PENDING_HIDE,0x210)
        assertTrue(entries.all{it.state==TargetState.NEEDS_REVIEW})
        assertNotEquals(SharedGroupEngine.INTERRUPTED,notice)
        assertEquals("중단된 공유 앱 묶음의 상태가 기록과 달라 변경하지 않았습니다.",notice)
    }
    @Test fun restoredInterruptedHideAsksToApplyAgain(){
        val (notice,entries)=recoverWith(TargetState.PENDING_HIDE,0x200)
        assertTrue(entries.all{it.state==TargetState.NEEDS_REAPPLY_POLICY})
        assertEquals(SharedGroupEngine.INTERRUPTED,notice)
    }
    @Test fun finishedInterruptedRestoreSaysRestored(){
        val (notice,entries)=recoverWith(TargetState.PENDING_RESTORE,0x200)
        assertTrue(entries.all{it.state==TargetState.NOT_APPLIED})
        assertEquals("2개 앱을 원래 표시 상태로 복원했어요.",notice)
    }

    // 3. "Once a day" also holds after a failed request and when checks overlap; "지금 확인" still works.
    private fun online(){
        val cm=context.getSystemService(ConnectivityManager::class.java)
        val caps=ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork,caps)
    }
    @Test fun failedAutomaticCheckWaitsADay(){
        UpdateCheck.set(context,true);online()
        val calls=AtomicInteger();val original=UpdateCheck.fetcher
        UpdateCheck.fetcher={calls.incrementAndGet();null}
        try{
            repeat(3){assertNull(UpdateCheck.check(context))}
            assertEquals("one request, then the day limit",1,calls.get())
            UpdateCheck.fetcher={calls.incrementAndGet();"99.0.0"}
            assertEquals("the user's 지금 확인 bypasses the day limit","99.0.0",UpdateCheck.check(context,force=true)?.tag)
            assertEquals(2,calls.get())
        }finally{UpdateCheck.fetcher=original}
    }
    @Test fun overlappingChecksSendOneRequest(){
        UpdateCheck.set(context,true);online()
        val calls=AtomicInteger();val original=UpdateCheck.fetcher
        UpdateCheck.fetcher={calls.incrementAndGet();Thread.sleep(300);"99.0.0"}
        try{
            val threads=(1..3).map{Thread{UpdateCheck.check(context)}}+Thread{UpdateCheck.check(context,force=true)}
            threads.forEach{it.start()};threads.forEach{it.join(5000)}
            assertEquals("overlapping checks share one request",1,calls.get())
            assertEquals("99.0.0",UpdateCheck.latest(context))
        }finally{UpdateCheck.fetcher=original}
    }
    @Test fun offlineRecordsNoAttempt(){
        UpdateCheck.set(context,true)
        val calls=AtomicInteger();val original=UpdateCheck.fetcher;UpdateCheck.fetcher={calls.incrementAndGet();"99.0.0"}
        try{
            shadowOf(context.getSystemService(ConnectivityManager::class.java)).setDefaultNetworkActive(false)
            shadowOf(context.getSystemService(ConnectivityManager::class.java)).setActiveNetworkInfo(null)
            assertNull(UpdateCheck.check(context));assertEquals(0,calls.get())
            assertEquals(0L,context.getSharedPreferences("update-check",0).getLong("lastAttempt",0L))
        }finally{UpdateCheck.fetcher=original}
    }
}
