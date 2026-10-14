package app.bluedotoff.core

import org.junit.Assert.*
import org.junit.Test

class WatchEngineTest {
    private val fw=Firmware("samsung",37,"90000","fixture","b","m","abi")
    private val id=Identity("test.map",0,10123,listOf("signer"),100,1,100)
    private val base=Snapshot(id,"Map",listOf(id.packageName),true,true,true,false,0x300,0x300,0,0,0,false)
    private fun entry(original:Int=256)=Entry(id,"Map",original,true,TargetState.APPLIED,200,fw.key,"boot",true,invariantDigest=base.invariantDigest(),settlingVerified=true)
    private class Store(var values:List<Entry>):Journal {
        var durable=true
        override fun entries()=values
        override fun commit(entry:Entry):Boolean{if(!durable)return false;values=values.filterNot{it.identity.packageName==entry.identity.packageName}+entry;return true}
        override fun commitGroup(entries:List<Entry>):Boolean{if(!durable)return false;values=values.filterNot{e->entries.any{it.identity.packageName==e.identity.packageName}}+entries;return true}
    }
    private inner class Rig(initial:List<Entry> = listOf(entry())) {
        val store=Store(initial);var snapshot=base;val writes=mutableListOf<Int>();val phases=mutableListOf<String>();val waits=mutableListOf<Long>();val detected=mutableListOf<String>();val results=mutableListOf<BatchResult>()
        var failSettling=false;var failAfterWrite=false;var cancel=false
        val port=object:TargetPort {
            override fun compatibleFirmware()=fw
            override fun read(packageName:String)=snapshot
            override fun writeFineBit(packageName:String,originalBit:Int){
                assertEquals(TargetState.PENDING_HIDE,store.values.single().state.takeIf{writes.isEmpty()}?:TargetState.PENDING_HIDE)
                writes+=originalBit;snapshot=snapshot.copy(fineFlags=(snapshot.fineFlags and 256.inv()) or originalBit)
                if(failAfterWrite&&writes.size==1)throw SafetyException("fixture after-write failure")
            }
        }
        fun run(names:Set<String> = setOf(id.packageName),boot:String="boot",canStart:()->Boolean={!cancel})=
            WatchEngine(port,store,{300},{boot},wait={ms->waits+=ms;if(failSettling){snapshot=snapshot.copy(fineFlags=base.fineFlags);failSettling=false}},observe={phase,_,_->phases+=phase})
                .reapply(names,canStart,detected::add,results::add)
    }
    @Test fun realResetUsesDurableRecordAndExistingThreeChecks(){val r=Rig();r.run();assertEquals(listOf(0),r.writes);assertEquals(listOf(500L,1000L),r.waits);assertTrue(r.phases.containsAll(listOf("preflight","immediate","settled_500ms","settled_1500ms")));assertEquals(256,r.store.values.single().originalBit);assertTrue(r.results.single().success)}
    @Test fun restoredOrUnregisteredAppsAreNeverHidden(){for(entries in listOf(emptyList(),listOf(entry().copy(enabled=false,state=TargetState.NOT_APPLIED)))){val r=Rig(entries);r.run();assertTrue(r.writes.isEmpty());assertTrue(r.detected.isEmpty())}}
    @Test fun alreadyOffDoesNotWriteOrLogANewReset(){val r=Rig();r.snapshot=base.copy(fineFlags=0x200);r.run();assertTrue(r.writes.isEmpty());assertTrue(r.results.isEmpty());assertTrue(r.detected.isEmpty())}
    @Test fun unrelatedPackageEventDoesNotEnrollAnApp(){val r=Rig();r.run(setOf("other.app"));assertTrue(r.writes.isEmpty());assertEquals(1,r.store.values.size)}
    @Test fun queuedEventRechecksRegistrationAtExecution(){val r=Rig();r.store.values=emptyList();r.run();assertTrue(r.writes.isEmpty())}
    @Test fun updateOfSameInstallPreservesOriginal(){val r=Rig();r.snapshot=base.copy(identity=id.copy(versionCode=2,lastUpdateTime=900));r.run();assertTrue(r.results.single().success);assertEquals(2,r.store.values.single().identity.versionCode);assertEquals(256,r.store.values.single().originalBit)}
    @Test fun reinstallUidOrSignerChangeNeverWrites(){for(identity in listOf(id.copy(firstInstallTime=200),id.copy(uid=10124),id.copy(signers=listOf("other")))){val r=Rig();r.snapshot=base.copy(identity=identity);r.run();assertTrue(r.writes.isEmpty())}}
    @Test fun pendingReviewFailureAndFirmwareStatesAreExcluded(){for(state in listOf(TargetState.PENDING_HIDE,TargetState.PENDING_RESTORE,TargetState.PENDING_SHOW,TargetState.FAILED_SAFE,TargetState.FAILED_ROLLBACK,TargetState.NEEDS_REVIEW,TargetState.SHARED_UID_CHANGED,TargetState.NEEDS_RECHECK_FIRMWARE,TargetState.MISSING_TARGET,TargetState.INACTIVE_INSTALL)){val r=Rig(listOf(entry().copy(state=state)));r.run();assertTrue(state.name,r.writes.isEmpty())}}
    @Test fun unverifiedRegistrationAndBootChangeNeverWrite(){val r=Rig(listOf(entry().copy(settlingVerified=false)));r.run();assertTrue(r.writes.isEmpty());val boot=Rig();boot.run(boot="new-boot");assertTrue(boot.writes.isEmpty())}
    @Test fun currentScopeAndPermissionAreRequired(){for(s in listOf(base.copy(fineGranted=false),base.copy(fineDeclared=false),base.copy(persistent=true,systemCritical=true,applicationScope=ApplicationScope.NORMAL))){val r=Rig();r.snapshot=s;r.run();assertTrue(r.writes.isEmpty())}}
    @Test fun otherPermissionChangesRequireReviewWithoutWriting(){val r=Rig();r.snapshot=base.copy(coarseFlags=0x200);r.run();assertTrue(r.writes.isEmpty());assertEquals(TargetState.UNSUPPORTED_STATE,r.store.values.single().state)}
    @Test fun failedDurableCommitCannotReachSetter(){val r=Rig();r.store.durable=false;r.run();assertTrue(r.writes.isEmpty());assertFalse(r.results.single().success)}
    @Test fun verificationFailureRollsBackAndDoesNotReportSuccess(){val r=Rig();r.failSettling=true;r.run();assertFalse(r.results.single().success);assertEquals(base.fineFlags,r.snapshot.fineFlags);assertEquals(TargetState.FAILED_SAFE,r.store.values.single().state)}
    @Test fun partialSetterFailureExecutesNarrowRollback(){val r=Rig();r.failAfterWrite=true;r.run();assertFalse(r.results.single().success);assertEquals(listOf(0,256),r.writes);assertEquals(base.fineFlags,r.snapshot.fineFlags)}
    @Test fun switchOffAfterSetterStillFinishesTheSafetyChecks(){val r=Rig();r.run(canStart={if(r.writes.isNotEmpty())false else true});assertEquals(listOf(0),r.writes);assertTrue(r.results.single().success);assertTrue(r.phases.contains("settled_1500ms"))}
    @Test fun switchOffBeforeSetterStopsWriting(){val r=Rig();var calls=0;r.run(canStart={++calls<3});assertTrue(r.writes.isEmpty())}
    @Test fun partialSharedRegistrationNeverExpandsTheMutation(){val second=id.copy(packageName="test.peer");val ids=listOf(id,second);val e=entry().copy(groupIdentities=ids);assertTrue(WatchPolicy.units(listOf(e),setOf(id.packageName)).isEmpty());assertTrue(WatchPolicy.units(listOf(e,e.copy(identity=second,enabled=false)),setOf(id.packageName)).isEmpty())}
    @Test fun completeRegisteredSharedGroupUsesExactlyOneUidWrite(){
        val ids=listOf(id,id.copy(packageName="test.peer"));val names=ids.map{it.packageName}
        val current=ids.associate{it.packageName to base.copy(identity=it,peers=names,sharedUserId="test.shared")}.toMutableMap()
        val store=Store(ids.map{identity->entry().copy(identity=identity,sharedUserId="test.shared",groupIdentities=ids,invariantDigest=current.getValue(identity.packageName).invariantDigest())})
        var writes=0;val results=mutableListOf<BatchResult>()
        val port=object:TargetPort{
            override fun compatibleFirmware()=fw
            override fun read(packageName:String)=current.getValue(packageName)
            override fun writeFineBit(packageName:String,originalBit:Int):Unit=error("must be a group write")
            override fun writeFineGroupChecked(expected:List<Snapshot>,wanted:Int){assertEquals(0,wanted);assertTrue(store.values.all{it.state==TargetState.PENDING_HIDE});writes++;current.replaceAll{_,s->s.copy(fineFlags=s.fineFlags and 256.inv())}}
        }
        WatchEngine(port,store,{300},{"boot"},wait={}).reapply(setOf(id.packageName),{true},{},results::add)
        assertEquals(1,writes);assertEquals(2,results.size);assertTrue(results.all{it.success});assertTrue(store.values.all{it.originalBit==256})
    }
    @Test fun watchCooldownPreventsTightRetriesButDoesNotThrottleSuccess(){val c=WatchCooldown();assertTrue(c.allowed("p",1));c.result("p",100,false);assertFalse(c.allowed("p",29_999));assertTrue(c.allowed("p",30_100));c.result("p",30_101,true);assertTrue(c.allowed("p",30_102))}
}
