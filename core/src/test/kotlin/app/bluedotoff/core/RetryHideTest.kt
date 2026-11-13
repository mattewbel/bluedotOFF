package app.bluedotoff.core

import org.junit.Assert.*
import org.junit.Test

/** Automatic re-hide of a failed hide (owner 2026-10-31) and the GPT rc18 review cases (its probes, same expectations;
 *  the spacing/budget ones use the stored-tries API). */
class RetryHideTest {
    private val fw=Firmware("samsung",37,"90000","fixture","b","m","abi")
    private val id=Identity("test.retry",0,10123,listOf("signer"),100,1,100)
    private val base=Snapshot(id,"Retry",listOf(id.packageName),true,true,true,false,0x300,0x300,0,0,0,false)
    private val failed=Entry(id,"Retry",256,true,TargetState.FAILED_SAFE,200,fw.key,"boot",false,invariantDigest=base.invariantDigest(),retryHide=true)
    private class Store(var entry:Entry):Journal {
        override fun entries()=listOf(entry)
        override fun commit(entry:Entry):Boolean{this.entry=entry;return true}
    }
    private class Tries:WatchRetry.Store {
        val map=HashMap<String,List<Long>>()
        override fun load(pkg:String)=map[pkg]?:emptyList()
        override fun save(pkg:String,times:List<Long>){map[pkg]=times}
    }
    @Test fun rejectedRestoreMustNotBeFollowedByAnAutomaticHide(){
        val store=Store(failed)
        var snapshot=base.copy(fineGranted=false)
        val writes=mutableListOf<Int>()
        val port=object:TargetPort {
            override fun compatibleFirmware()=fw
            override fun read(packageName:String)=snapshot
            override fun writeFineBit(packageName:String,originalBit:Int){writes+=originalBit;snapshot=snapshot.copy(fineFlags=(snapshot.fineFlags and 256.inv()) or originalBit)}
        }
        try{SafetyEngine(port,store,{300},{"boot"},wait={}).userRestore(id.packageName);fail("fixture restore should be rejected")}
        catch(_:SafetyException){}
        assertTrue(writes.isEmpty());assertFalse(store.entry.retryHide)
        snapshot=base
        WatchEngine(port,store,{400},{"boot"},wait={}).reapply(setOf(id.packageName),{true},{},{})
        assertEquals("a rejected Restore must revoke automatic re-hide intent",emptyList<Int>(),writes)
    }
    @Test fun firstFailureMustWaitTenMinutesEvenWhenAnotherEventArrives(){
        val retry=WatchRetry(Tries());val at=failed.copy(lastVerifiedTime=1_000_000)
        assertEquals(600_000L,retry.wait(at,1_000_000))
        assertFalse(retry.allowed(at,1_030_001));assertTrue(retry.allowed(at,1_600_000))
        // A hide the watch just settled after an interruption skips the wait, not the daily limit.
        assertTrue(retry.allowed(at,1_030_001,noGap=true))
        assertFalse(WatchRetry(object:WatchRetry.Store{override fun load(pkg:String)=listOf(1L,2L,3L);override fun save(pkg:String,times:List<Long>){}}).allowed(at,1_030_001,noGap=true))
    }
    @Test fun dailyRetryBudgetMustSurviveClientProcessRecreation(){
        val tries=Tries();val retry=WatchRetry(tries);val e=failed.copy(lastVerifiedTime=0)
        listOf(600_000L,1_200_000L,1_800_000L).forEach{retry.tried(id.packageName,it)}
        assertFalse(retry.allowed(e,2_400_000L));assertNull(retry.wait(e,2_400_000L))
        assertFalse("same day must not gain a fresh budget merely because the app process restarted",WatchRetry(tries).allowed(e,2_400_000L))
        assertTrue("a day later",WatchRetry(tries).allowed(e,86_400_000L+600_000L))
    }
    @Test fun aSuccessEndsTheRunAndAClockSetBackDoesNotStallIt(){
        val tries=Tries();val retry=WatchRetry(tries);val e=failed.copy(lastVerifiedTime=0)
        listOf(600_000L,1_200_000L,1_800_000L).forEach{retry.tried(id.packageName,it)}
        retry.succeeded(id.packageName);assertTrue(retry.allowed(e,2_400_000L))
        assertTrue(retry.allowed(failed.copy(lastVerifiedTime=5_000_000),1_000_000))
    }
    @Test fun restoreIntentSurvivesEventsAndOnlyAnExplicitHideResumesIt(){
        val store=Store(failed)
        SafetyEngine.requestRestore(store,setOf(id.packageName))
        assertTrue(store.entry.restoreRequested);assertFalse(store.entry.retryHide)
        assertFalse(WatchPolicy.eligible(Reconcile.update(store.entry)))
        assertFalse(WatchPolicy.eligible(Reconcile.boot(store.entry)))
        var snapshot=base
        val port=object:TargetPort{
            override fun compatibleFirmware()=fw
            override fun read(packageName:String)=snapshot
            override fun writeFineBit(packageName:String,originalBit:Int){snapshot=snapshot.copy(fineFlags=(snapshot.fineFlags and 256.inv()) or originalBit)}
        }
        SafetyEngine(port,store,{400},{"boot"},wait={}).userHide(id.packageName)
        assertEquals(TargetState.APPLIED,store.entry.state)
        assertFalse(store.entry.restoreRequested);assertTrue(WatchPolicy.eligible(store.entry))
        assertEquals(256,store.entry.originalBit)
    }
    @Test fun restoreIntentStorageFailurePreventsAnyPermissionReadOrWrite(){
        val journal=object:Journal{
            override fun entries()=listOf(failed)
            override fun commit(entry:Entry)=false
        }
        val port=object:TargetPort{
            override fun compatibleFirmware():Firmware=error("Must not start")
            override fun read(packageName:String):Snapshot=error("Must not read")
            override fun writeFineBit(packageName:String,originalBit:Int)=error("Must not write")
        }
        try{SafetyEngine(port,journal,{400},{"boot"},wait={}).userRestore(id.packageName);fail()}
        catch(_:JournalPersistenceException){}
        assertTrue(journal.entries().single().retryHide)
    }
}
