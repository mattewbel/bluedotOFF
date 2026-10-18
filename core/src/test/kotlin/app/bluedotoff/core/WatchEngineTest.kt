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
        val store=Store(initial);var snapshot=base;val writes=mutableListOf<Int>();val phases=mutableListOf<String>();val waits=mutableListOf<Long>();val detected=mutableListOf<String>();val results=mutableListOf<BatchResult>();val skips=mutableListOf<String>()
        var resets=0;var failAfterWrite=false;var cancel=false
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
            WatchEngine(port,store,{300},{boot},wait={ms->waits+=ms;if(resets>0&&ms<5_000){snapshot=snapshot.copy(fineFlags=base.fineFlags,lastLocationAccess=ms);resets--}},observe={phase,_,_->phases+=phase},onSkip={_,reason->skips+=reason})
                .reapply(names,canStart,detected::add,results::add)
    }
    @Test fun realResetUsesDurableRecordAndExistingThreeChecks(){val r=Rig();r.run();assertEquals(listOf(0),r.writes);assertEquals(listOf(500L,1000L),r.waits);assertTrue(r.phases.containsAll(listOf("preflight","immediate","settled_500ms","settled_1500ms")));assertEquals(256,r.store.values.single().originalBit);assertTrue(r.results.single().success)}
    @Test fun restoredOrUnregisteredAppsAreNeverHidden(){for(entries in listOf(emptyList(),listOf(entry().copy(enabled=false,state=TargetState.NOT_APPLIED)))){val r=Rig(entries);r.run();assertTrue(r.writes.isEmpty());assertTrue(r.detected.isEmpty())}}
    @Test fun alreadyOffDoesNotWriteOrLogANewReset(){val r=Rig();r.snapshot=base.copy(fineFlags=0x200);r.run();assertTrue(r.writes.isEmpty());assertTrue(r.results.isEmpty());assertTrue(r.detected.isEmpty())}
    @Test fun unrelatedPackageEventDoesNotEnrollAnApp(){val r=Rig();r.run(setOf("other.app"));assertTrue(r.writes.isEmpty());assertEquals(1,r.store.values.size)}
    @Test fun queuedEventRechecksRegistrationAtExecution(){val r=Rig();r.store.values=emptyList();r.run();assertTrue(r.writes.isEmpty())}
    /** 2026-10-17 (Google Play services on the Fold7): an update that kept the hide is settled by the watch without a write,
     *  so "재적용 필요" and the update notice do not wait for the app to be opened. */
    @Test fun updateThatKeptTheHideIsSettledWithoutAWrite(){
        for(marked in listOf(true,false)){
            val r=Rig(listOf(entry().let{if(marked)it.copy(state=TargetState.NEEDS_REAPPLY_UPDATE) else it}))
            r.snapshot=base.copy(identity=id.copy(versionCode=2,lastUpdateTime=900),fineFlags=0x200)
            r.run()
            assertTrue(r.writes.isEmpty());assertTrue("not a new reset",r.detected.isEmpty())
            assertTrue(r.results.single().success)
            val saved=r.store.values.single()
            assertEquals(TargetState.APPLIED,saved.state);assertEquals(2,saved.identity.versionCode);assertEquals(256,saved.originalBit);assertTrue(saved.settlingVerified)
        }
    }
    @Test fun skippedUnitsSayWhy(){
        val r=Rig();r.snapshot=base.copy(fineFlags=0x200);r.run();assertEquals(listOf("stillHidden"),r.skips)
        val o=Rig();o.snapshot=base.copy(coarseFlags=0x200);o.run();assertEquals(listOf("otherPermissionState"),o.skips)
        val b=Rig();b.run(boot="other");assertTrue(b.skips.isNotEmpty());assertTrue(b.writes.isEmpty())
    }
    @Test fun updateOfSameInstallPreservesOriginal(){val r=Rig();r.snapshot=base.copy(identity=id.copy(versionCode=2,lastUpdateTime=900));r.run();assertTrue(r.results.single().success);assertEquals(2,r.store.values.single().identity.versionCode);assertEquals(256,r.store.values.single().originalBit)}
    @Test fun reinstallUidOrSignerChangeNeverWrites(){for(identity in listOf(id.copy(firstInstallTime=200),id.copy(uid=10124),id.copy(signers=listOf("other")))){val r=Rig();r.snapshot=base.copy(identity=identity);r.run();assertTrue(r.writes.isEmpty())}}
    @Test fun pendingReviewFailureAndFirmwareStatesAreExcluded(){for(state in listOf(TargetState.PENDING_HIDE,TargetState.PENDING_RESTORE,TargetState.PENDING_SHOW,TargetState.FAILED_SAFE,TargetState.FAILED_ROLLBACK,TargetState.NEEDS_REVIEW,TargetState.SHARED_UID_CHANGED,TargetState.NEEDS_RECHECK_FIRMWARE,TargetState.MISSING_TARGET,TargetState.INACTIVE_INSTALL)){val r=Rig(listOf(entry().copy(state=state)));r.run();assertTrue(state.name,r.writes.isEmpty())}}
    @Test fun unverifiedRegistrationAndBootChangeNeverWrite(){val r=Rig(listOf(entry().copy(settlingVerified=false)));r.run();assertTrue(r.writes.isEmpty());val boot=Rig();boot.run(boot="new-boot");assertTrue(boot.writes.isEmpty())}
    @Test fun currentScopeAndPermissionAreRequired(){for(s in listOf(base.copy(fineGranted=false),base.copy(fineDeclared=false),base.copy(persistent=true,systemCritical=true,applicationScope=ApplicationScope.NORMAL))){val r=Rig();r.snapshot=s;r.run();assertTrue(r.writes.isEmpty())}}
    @Test fun otherPermissionChangesRequireReviewWithoutWriting(){val r=Rig();r.snapshot=base.copy(coarseFlags=0x200);r.run();assertTrue(r.writes.isEmpty());assertEquals(TargetState.UNSUPPORTED_STATE,r.store.values.single().state)}
    @Test fun failedDurableCommitCannotReachSetter(){val r=Rig();r.store.durable=false;r.run();assertTrue(r.writes.isEmpty());assertFalse(r.results.single().success)}
    @Test fun verificationFailureRollsBackAndDoesNotReportSuccess(){val r=Rig();r.resets=4;r.run();assertFalse(r.results.single().success);assertEquals(base.fineFlags,r.snapshot.fineFlags);assertEquals(TargetState.FAILED_SAFE,r.store.values.single().state);assertEquals(listOf(0,0,0,0),r.writes)}
    /** Toss, 2026-10-18: Android reset the flag once more during the checks right after an update. Retried, not locked. */
    @Test fun androidResetDuringChecksIsRetried(){val r=Rig();r.resets=1;r.run();assertEquals(listOf(0,0),r.writes);assertTrue(r.waits.contains(5_000L));assertTrue(r.results.single().success);assertEquals(TargetState.APPLIED,r.store.values.single().state);assertEquals(256,r.store.values.single().originalBit)}
    @Test fun otherChangeDuringChecksStaysLocked(){val r=Rig();var once=true
        WatchEngine(r.port,r.store,{300},{"boot"},wait={if(once){r.snapshot=r.snapshot.copy(fineFlags=base.fineFlags,coarseFlags=0x200);once=false}}).reapply(setOf(id.packageName),{true},{},r.results::add)
        assertEquals(listOf(0),r.writes);assertFalse(r.results.single().success);assertNotEquals(TargetState.APPLIED,r.store.values.single().state)}
    @Test fun partialSetterFailureExecutesNarrowRollback(){val r=Rig();r.failAfterWrite=true;r.run();assertFalse(r.results.single().success);assertEquals(listOf(0,256),r.writes);assertEquals(base.fineFlags,r.snapshot.fineFlags)}
    @Test fun switchOffAfterSetterStillFinishesTheSafetyChecks(){val r=Rig();r.run(canStart={if(r.writes.isNotEmpty())false else true});assertEquals(listOf(0),r.writes);assertTrue(r.results.single().success);assertTrue(r.phases.contains("settled_1500ms"))}
    @Test fun switchOffBeforeSetterStopsWriting(){val r=Rig();var calls=0;r.run(canStart={++calls<3});assertTrue(r.writes.isEmpty())}
    @Test fun partialSharedRegistrationNeverExpandsTheMutation(){val second=id.copy(packageName="test.peer");val ids=listOf(id,second);val e=entry().copy(groupIdentities=ids);assertTrue(WatchPolicy.units(listOf(e),setOf(id.packageName)).isEmpty());assertTrue(WatchPolicy.units(listOf(e,e.copy(identity=second,enabled=false)),setOf(id.packageName)).isEmpty())}
    private class Group(val current:MutableMap<String,Snapshot>,val store:Store){var writes=0;val results=mutableListOf<BatchResult>()}
    /** Registered two-app group; [member] shapes each fresh read (default: identical, both use location). */
    private fun group(member:(Snapshot)->Snapshot={it},fresh:(Snapshot)->Snapshot={it}):Group{
        val ids=listOf(id,id.copy(packageName="test.peer"));val names=ids.map{it.packageName}
        val registered=ids.associate{it.packageName to member(base.copy(identity=it,peers=names,sharedUserId="test.shared"))}
        val g=Group(registered.mapValues{(_,s)->fresh(s)}.toMutableMap(),Store(ids.map{identity->entry().copy(identity=identity,sharedUserId="test.shared",groupIdentities=ids,invariantDigest=registered.getValue(identity.packageName).invariantDigest())}))
        val port=object:TargetPort{
            override fun compatibleFirmware()=fw
            override fun read(packageName:String)=g.current.getValue(packageName)
            override fun writeFineBit(packageName:String,originalBit:Int):Unit=error("must be a group write")
            override fun writeFineGroupChecked(expected:List<Snapshot>,wanted:Int){assertEquals(0,wanted);g.writes++;g.current.replaceAll{_,s->s.copy(fineFlags=s.fineFlags and 256.inv())}}
        }
        WatchEngine(port,g.store,{300},{"boot"},wait={}).reapply(setOf(id.packageName),{true},{},g.results::add)
        return g
    }
    private val companion:(Snapshot)->Snapshot={s->if(s.identity.packageName=="test.peer")s.copy(fineDeclared=false,fineGranted=false,coarseDeclared=false,coarseGranted=false,fineFlags=0x100,coarseFlags=0x100) else s.copy(fineFlags=0x100,coarseFlags=0x100)}
    @Test fun groupWithNonLocationCompanionIsReappliedLikeTheManualWrite(){
        val g=group(companion)
        assertEquals(1,g.writes);assertEquals(2,g.results.size);assertTrue(g.results.all{it.success})
        assertTrue(g.store.values.all{it.state==TargetState.APPLIED&&it.originalBit==256})
        assertFalse(g.current.getValue("test.peer").fineGranted)
    }
    @Test fun groupWithoutAnyUsableMemberIsNeverReapplied(){
        val g=group(companion){s->s.copy(fineGranted=false)}
        assertEquals(0,g.writes);assertTrue(g.results.isEmpty())
    }
    @Test fun groupCompanionIdentityOrPermissionChangeIsNeverReapplied(){
        for(change in listOf<(Snapshot)->Snapshot>({it.copy(identity=it.identity.copy(signers=listOf("other")))},{it.copy(identity=it.identity.copy(firstInstallTime=999))},{it.copy(fineDeclared=true,fineGranted=true)})){
            val g=group(companion){s->if(s.identity.packageName=="test.peer")change(s) else s}
            assertEquals(0,g.writes)
        }
    }
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
    /** Group whose shared flag Android resets [resets] times during the checks (all members back exactly as before). */
    private fun groupResetDuringChecks(resets:Int):Pair<Int,List<BatchResult>>{
        val ids=listOf(id,id.copy(packageName="test.peer"));val names=ids.map{it.packageName}
        val start=ids.associate{it.packageName to base.copy(identity=it,peers=names,sharedUserId="test.shared")}
        val current=start.toMutableMap();var left=resets;var writes=0;val results=mutableListOf<BatchResult>()
        val store=Store(ids.map{identity->entry().copy(identity=identity,sharedUserId="test.shared",groupIdentities=ids,invariantDigest=start.getValue(identity.packageName).invariantDigest())})
        val port=object:TargetPort{
            override fun compatibleFirmware()=fw
            override fun read(packageName:String)=current.getValue(packageName)
            override fun writeFineBit(packageName:String,originalBit:Int):Unit=error("must be a group write")
            override fun writeFineGroupChecked(expected:List<Snapshot>,wanted:Int){writes++;current.replaceAll{_,s->s.copy(fineFlags=(s.fineFlags and 256.inv()) or wanted)}}
        }
        WatchEngine(port,store,{300},{"boot"},wait={ms->if(left>0&&ms<5_000){current.putAll(start);left--}}).reapply(setOf(id.packageName),{true},{},results::add)
        return writes to results
    }
    @Test fun groupResetDuringChecksIsRetriedToo(){
        val (writes,results)=groupResetDuringChecks(1)
        assertEquals("first try + one retry, no rollback write needed",2,writes);assertEquals(2,results.size);assertTrue(results.all{it.success})
        val (always,failed)=groupResetDuringChecks(9)
        assertEquals("bounded: first try + 3 retries",4,always);assertTrue(failed.none{it.success})
    }
    /** D-2a: a lone event keeps its timing; a steady stream is sent at the cap; every app is still taken at the end. */
    @Test fun eventWindowCapsAStreamWithoutDroppingTheLastCheck(){
        val lone=EventWindow(750,3_000);assertTrue(lone.add("a",0).isEmpty());assertEquals(setOf("a"),lone.take());assertTrue(lone.take().isEmpty())
        val w=EventWindow(750,3_000);val sent=mutableListOf<Pair<Long,Set<String>>>()
        assertTrue(w.add("a",0).isEmpty())
        for(t in 500L..10_000L step 500)w.add("b",t).takeIf{it.isNotEmpty()}?.let{sent+=t to it}
        assertEquals(listOf(3_000L,6_000L,9_000L),sent.map{it.first})
        assertTrue("a stays checked after its early send","a" in sent.first().second)
        assertEquals("the end of the window still has both",setOf("a","b"),w.take())
    }
    /** QA 2026-10-18: a lock is lowered only when the app is verifiably at its original; never otherwise, never a write. */
    @Test fun lockOnAnAppBackAtItsOriginalDropsToCheckNeeded(){
        val locked=entry().copy(state=TargetState.FAILED_ROLLBACK,changedByUs=true,error="x")
        val back=Reconcile.read(locked,base,fw.key,"boot")
        assertEquals(TargetState.FAILED_SAFE,back.state);assertFalse(back.changedByUs);assertTrue(back.enabled);assertFalse(WatchPolicy.eligible(back))
        for(s in listOf(base.copy(fineFlags=0x200),base.copy(coarseFlags=0x200),base.copy(identity=id.copy(firstInstallTime=999)),base.copy(peers=listOf(id.packageName,"other"))))
            assertEquals(TargetState.FAILED_ROLLBACK,Reconcile.read(locked,s,fw.key,"boot").state)
        assertEquals(TargetState.FAILED_ROLLBACK,Reconcile.read(locked,null,fw.key,"boot").state)
        assertEquals(TargetState.FAILED_ROLLBACK,Reconcile.read(locked,base,"other-firmware","boot").state)
        assertEquals(TargetState.NEEDS_REVIEW,Reconcile.read(locked.copy(state=TargetState.NEEDS_REVIEW),base,fw.key,"boot").state)
    }
    @Test fun watchCooldownPreventsTightRetriesButDoesNotThrottleSuccess(){val c=WatchCooldown();assertTrue(c.allowed("p",1));c.result("p",100,false);assertFalse(c.allowed("p",29_999));assertTrue(c.allowed("p",30_100));c.result("p",30_101,true);assertTrue(c.allowed("p",30_102))}
}
