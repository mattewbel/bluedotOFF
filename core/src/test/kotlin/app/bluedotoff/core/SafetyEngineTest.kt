package app.bluedotoff.core

import org.junit.Assert.*
import org.junit.Test

class SafetyEngineTest {
    @Test fun explicitRetryAfterSafeRollbackClearsErrorOnlyAfterSettlingPasses(){
        val(s,p,e)=rig();p.failAfterWrite=true
        val failed=e.userHide(identity.packageName)
        assertEquals(TargetState.FAILED_SAFE,failed.state);assertFalse(failed.settlingVerified)
        assertTrue(failed.error.contains("원래 표시 상태"));assertEquals(baseline,p.current)
        p.failAfterWrite=false
        val applied=e.userHide(identity.packageName)
        assertEquals(TargetState.APPLIED,applied.state);assertTrue(applied.enabled);assertTrue(applied.settlingVerified)
        assertEquals("",applied.error);assertEquals(0,p.current.bit);assertEquals(256,s.data.last().originalBit)
    }
    @Test fun emptyJournalWithOffTargetRecordsCurrentBitAndNeverInventsOldOnBit(){
        val(store,port,engine)=rig();port.current=baseline.copy(fineFlags=baseline.fineFlags and 256.inv())
        val start=port.current;val entry=engine.userHide(identity.packageName)
        assertEquals(0,entry.originalBit);assertTrue(entry.settlingVerified);assertEquals(TargetState.APPLIED,entry.state)
        assertFalse(entry.changedByUs);assertTrue(port.writes.isEmpty())
        engine.userRestore(identity.packageName)
        assertEquals(start,port.current);assertTrue(port.writes.isEmpty());assertFalse(store.data.last().enabled)
    }
    @Test fun singletonHideAndOriginalRestoreUseSameNarrowTransaction(){val(s,p,e)=rig();p.current=baseline.copy(sharedUserId="test.shared",singletonVerified=true);val start=p.current;assertEquals(TargetState.APPLIED,e.userHide(identity.packageName).state);assertEquals("test.shared",s.data.last().sharedUserId);assertEquals(TargetState.NOT_APPLIED,e.userRestore(identity.packageName).state);assertEquals(start,p.current);assertEquals(listOf(0,256),p.writes)}
    @Test fun unverifiedSingletonNeverWrites(){val(_,p,e)=rig();p.current=baseline.copy(sharedUserId="test.shared");expectFailure{e.userHide(identity.packageName)};assertTrue(p.writes.isEmpty())}
    @Test fun cachedSinglePeerCannotPassFreshMultiPeerRead(){val(_,p,e)=rig();p.current=baseline.copy(sharedUserId="test.shared",singletonVerified=true,peers=listOf(identity.packageName,"second.app"));expectFailure{e.userHide(identity.packageName)};assertTrue(p.writes.isEmpty())}
    @Test fun peerChangesDuringDurableCommitStopBeforeSetter(){val s=Store();val p=Port(s);p.current=baseline.copy(sharedUserId="test.shared",singletonVerified=true);val journal=object:Journal{override fun entries()=s.entries();override fun commit(entry:Entry):Boolean{val ok=s.commit(entry);p.current=p.current.copy(peers=listOf(identity.packageName,"second.app"));return ok}};val e=SafetyEngine(p,journal,{200},{"boot:1"},wait={});assertEquals(TargetState.SHARED_UID_CHANGED,e.userHide(identity.packageName).state);assertTrue(p.writes.isEmpty())}
    @Test fun transientObservedPeerAdditionNeverAllowsRollback(){val(s,p,_)=rig();p.current=baseline.copy(sharedUserId="test.shared",singletonVerified=true);val e=SafetyEngine(p,s,{200},{"boot:1"},wait={},observe={phase,_,_->if(phase=="immediate")p.current=p.current.copy(peers=listOf(identity.packageName,"second.app"));if(phase=="settled_500ms")p.current=p.current.copy(peers=listOf(identity.packageName))});assertEquals(TargetState.NEEDS_REVIEW,e.userHide(identity.packageName).state);assertEquals(listOf(0),p.writes)}
    @Test fun finalFreshReadCatchesPeerAdditionBeforeSetter(){val(s,p,_)=rig();val start=baseline.copy(sharedUserId="test.shared",singletonVerified=true);p.current=start;val port=object:TargetPort{var reads=0;override fun compatibleFirmware()=firmware;override fun read(packageName:String):Snapshot{reads++;return if(reads==3)start.copy(peers=listOf(identity.packageName,"second.app")) else p.current};override fun writeFineBit(packageName:String,originalBit:Int)=p.writeFineBit(packageName,originalBit)};assertEquals(TargetState.NEEDS_REVIEW,SafetyEngine(port,s,{200},{"boot:1"},wait={}).userHide(identity.packageName).state);assertTrue(p.writes.isEmpty())}
    @Test fun noopPeerAdditionRequiresReviewAndNeverSets(){val(s,p,_)=rig();p.current=baseline.copy(sharedUserId="test.shared",singletonVerified=true,fineFlags=baseline.fineFlags and 256.inv());val e=SafetyEngine(p,s,{200},{"boot:1"},wait={p.current=p.current.copy(peers=listOf(identity.packageName,"second.app"))});assertEquals(TargetState.NEEDS_REVIEW,e.userHide(identity.packageName).state);assertTrue(p.writes.isEmpty())}
    @Test fun ownershipChangeExceptionStopsFollowingBatchTargets(){val called=mutableListOf<String>();val result=TargetBatch.run(listOf("a","b")){called.add(it);throw OwnershipChangedException()};assertEquals(listOf("a"),called);assertEquals(2,result.size);assertTrue(result.none{it.success})}
    @Test fun osSensitivityReclassificationIsNotCachedAppliedOrAnAppUpdate(){val(s,p,e)=rig();e.userHide(identity.packageName);val changed=Reconcile.read(s.data.last(),baseline,firmware.key,"boot:1");assertEquals(TargetState.NEEDS_REAPPLY_POLICY,changed.state);assertEquals(listOf(0),p.writes);assertEquals(256,changed.originalBit)}
    @Test fun postWritePeerChangeHaltsWithoutBroadRollback(){val s=Store();val p=Port(s);p.current=baseline.copy(sharedUserId="test.shared",singletonVerified=true);val e=SafetyEngine(p,s,{200},{"boot:1"},wait={if(it==500L)p.current=p.current.copy(peers=listOf(identity.packageName,"second.app"))});assertEquals(TargetState.NEEDS_REVIEW,e.userHide(identity.packageName).state);assertEquals(listOf(0),p.writes);expectFailure{e.userRestore(identity.packageName)};assertEquals(listOf(0),p.writes)}
    @Test fun singletonLifecyclePeerAdditionIsStickyReviewWithoutSetter(){val(s,p,e)=rig();p.current=baseline.copy(sharedUserId="test.shared",singletonVerified=true);e.userHide(identity.packageName);val changed=Reconcile.read(s.data.last(),p.current.copy(peers=listOf(identity.packageName,"second.app")),firmware.key,"boot:1");assertEquals(TargetState.SHARED_UID_CHANGED,changed.state);assertEquals(changed,Reconcile.update(changed));assertEquals(changed,Reconcile.boot(changed));assertEquals(changed,Reconcile.read(changed,p.current,firmware.key,"boot:1"));assertEquals(listOf(0),p.writes)}
    @Test fun singletonUpdateRetainsSameOriginalAndNamespace(){val(s,p,e)=rig();p.current=baseline.copy(sharedUserId="test.shared",singletonVerified=true);e.userHide(identity.packageName);p.current=p.current.copy(identity=identity.copy(versionCode=2,lastUpdateTime=900),fineFlags=baseline.fineFlags);e.userHide(identity.packageName);assertEquals(256,s.data.last().originalBit);assertEquals("test.shared",s.data.last().sharedUserId);assertEquals(listOf(0,0),p.writes)}
    @Test fun singletonFailureRollsBackOnlyOriginalBitWhenOwnershipStable(){val(_,p,e)=rig();p.current=baseline.copy(sharedUserId="test.shared",singletonVerified=true);val original=p.current;p.failAfterWrite=true;assertEquals(TargetState.FAILED_SAFE,e.userHide(identity.packageName).state);assertEquals(original,p.current);assertEquals(listOf(0,256),p.writes)}
    // "표시 켜기": an app left OFF without any record (e.g. bluedotOFF was reinstalled) is turned ON.
    private val off get()=baseline.copy(fineFlags=baseline.fineFlags and 256.inv())
    @Test fun showRecordsOffAndTargetOnBeforeWritingThenLeavesTheAppUnregistered(){
        val(s,p,e)=rig();p.current=off
        val journalBeforeWrite=mutableListOf<Entry>()
        val port=object:TargetPort{override fun compatibleFirmware()=firmware;override fun read(packageName:String)=p.read(packageName)
            override fun writeFineBit(packageName:String,originalBit:Int){journalBeforeWrite.add(s.data.last());p.writeFineBit(packageName,originalBit)}}
        val done=SafetyEngine(port,s,{200},{"boot:1"},wait={}).userShow(identity.packageName)
        assertEquals(TargetState.PENDING_SHOW,journalBeforeWrite.single().state);assertEquals(0,journalBeforeWrite.single().originalBit)
        assertEquals(listOf(256),p.writes);assertEquals(baseline,p.current)
        assertEquals(TargetState.NOT_APPLIED,done.state);assertFalse(done.enabled);assertTrue(done.settlingVerified);assertEquals(256,done.originalBit)
        // A later hide takes ON as the original, so restoring it turns the indicator back on.
        e.userHide(identity.packageName);assertEquals(0,p.current.bit);assertEquals(256,s.data.last().originalBit)
        e.userRestore(identity.packageName);assertEquals(baseline,p.current)
    }
    @Test fun showRefusesAnAppThatIsAlreadyOn(){val(_,p,e)=rig();expectFailure{e.userShow(identity.packageName)};assertTrue(p.writes.isEmpty())}
    @Test fun showRefusesAnAppWithARecordAndLeavesItToRestore(){
        val(s,p,e)=rig();p.current=off;e.userHide(identity.packageName)
        expectFailure{e.userShow(identity.packageName)};assertTrue(p.writes.isEmpty());assertTrue(s.data.last().enabled)
        // After that record is restored (it stays OFF, its original), the app can be shown.
        e.userRestore(identity.packageName);assertEquals(TargetState.NOT_APPLIED,e.userShow(identity.packageName).state);assertEquals(listOf(256),p.writes)
    }
    @Test fun showRefusesSharedOrUnsafeApps(){
        val(_,p,e)=rig();p.current=off.copy(sharedUserId="test.shared",peers=listOf(identity.packageName,"second.app"));expectFailure{e.userShow(identity.packageName)}
        p.current=off.copy(fineGranted=false);expectFailure{e.userShow(identity.packageName)};assertTrue(p.writes.isEmpty())
    }
    @Test fun verifiedSingletonSharedUserCanBeTurnedBackOn(){
        val(s,p,e)=rig();p.current=off.copy(sharedUserId="test.shared",singletonVerified=true)
        assertEquals(TargetState.NOT_APPLIED,e.userShow(identity.packageName).state);assertEquals(listOf(256),p.writes);assertEquals("test.shared",s.data.last().sharedUserId)
    }
    @Test fun failedShowRollsBackToOff(){
        val(s,p,e)=rig();p.current=off;p.failAfterWrite=true
        val entry=e.userShow(identity.packageName)
        assertEquals(TargetState.FAILED_SAFE,entry.state);assertEquals(off,p.current);assertEquals(listOf(256,0),p.writes);assertEquals(0,s.data.last().originalBit)
    }
    @Test fun interruptedShowIsRecognisedAfterRestart(){
        val(s,p,_)=rig();p.current=off
        val pending=Entry(identity,"Test",0,true,TargetState.PENDING_SHOW,100,firmware.key,"boot:1",false,invariantDigest=off.invariantDigest())
        // Closed after the write: ON is the target, so it is simply unregistered with ON.
        val on=Reconcile.read(pending,baseline,firmware.key,"boot:1")
        assertEquals(TargetState.NOT_APPLIED,on.state);assertFalse(on.enabled);assertEquals(256,on.originalBit)
        // Closed before the write: still OFF, nothing changed.
        val still=Reconcile.read(pending,off,firmware.key,"boot:1")
        assertEquals(TargetState.NOT_APPLIED,still.state);assertFalse(still.enabled);assertEquals(0,still.originalBit)
        // Something else changed meanwhile: blocked for review, as for an interrupted hide or restore.
        assertEquals(TargetState.FAILED_ROLLBACK,Reconcile.read(pending,off.copy(fineMode=1),firmware.key,"boot:1").state)
        // A boot or an app update never turns an unfinished show into a reapply.
        assertEquals(TargetState.PENDING_SHOW,Reconcile.boot(pending).state);assertEquals(TargetState.PENDING_SHOW,Reconcile.update(pending).state)
        assertTrue(p.writes.isEmpty());assertTrue(s.data.isEmpty())
    }
    @Test fun showWindowTurnsOnEveryAppAndChecksEachOne(){
        val(s,p,_)=rig();p.current=off;var clock=0L
        val out=SafetyEngine(p,s,{200},{"boot:1"},wait={clock+=it}).userShowWindow(listOf(identity.packageName),elapsed={clock})
        assertTrue(out.results.single().success);assertEquals(listOf(256),p.writes);assertFalse(s.data.last().enabled);assertEquals(256,s.data.last().originalBit)
    }
    private val identity = Identity("test.location.app", 0, 10792, listOf("certificate"), 100, 1, 110)
    private val baseline = Snapshot(identity,"Test",listOf(identity.packageName),true,true,true,false,0x80301,0x301,0,4,4,false)
    private val firmware = Firmware("samsung",37,"90000","fp","id","Galaxy","abi-verified")
    private class Store : Journal {
        val data = mutableListOf<Entry>(); var fail = false
        override fun entries() = data.toList()
        override fun commit(entry: Entry): Boolean { if (fail) return false; data.removeAll { it.identity.packageName == entry.identity.packageName }; data.add(entry); return true }
    }
    private inner class Port(private val store: Store) : TargetPort {
        var current=baseline; var fw=firmware; val writes=mutableListOf<Int>(); var failAfterWrite=false; var disconnect=false; var extraBit=false; var swapInstall=false; var modeChange=false; var reads=0; var beforeWriteMutation=false
        override fun compatibleFirmware()=fw
        override fun read(packageName: String): Snapshot {
            reads++
            if(disconnect&&writes.isNotEmpty())throw IllegalStateException("Shizuku disconnected")
            if(beforeWriteMutation&&reads==2)current=current.copy(coarseGranted=false)
            return current
        }
        override fun writeFineBit(packageName: String, originalBit: Int) {
            assertTrue(store.entries().last().state in listOf(TargetState.PENDING_HIDE,TargetState.PENDING_RESTORE,TargetState.PENDING_SHOW))
            writes.add(originalBit)
            current=current.copy(fineFlags=(current.fineFlags and 256.inv()) or originalBit)
            if(writes.size==1){
                if(extraBit)current=current.copy(fineFlags=current.fineFlags or 0x10)
                if(swapInstall)current=current.copy(identity=current.identity.copy(uid=10795))
                if(modeChange)current=current.copy(fineMode=1)
                if(failAfterWrite)throw IllegalStateException("reply.readException")
            }
        }
    }
    private fun rig(): Triple<Store,Port,SafetyEngine> { val s=Store(); val p=Port(s); return Triple(s,p,SafetyEngine(p,s,{200},{"boot:1"},wait={})) }
    private fun expectFailure(action: ()->Unit) { try { action(); fail("Expected safe rejection") } catch (_:SafetyException) {} }
    @Test fun originalOnHideRestorePreservesEveryOtherField() { val(s,p,e)=rig();assertEquals(TargetState.APPLIED,e.userHide(identity.packageName).state);assertEquals(0x80201,p.current.fineFlags);assertEquals(256,s.data.last().originalBit);assertEquals(TargetState.NOT_APPLIED,e.userRestore(identity.packageName).state);assertEquals(baseline,p.current);assertEquals(listOf(0,256),p.writes) }
    @Test fun originalOffAddRemoveNeverNormalizesToOn() {val(s,p,e)=rig();p.current=p.current.copy(fineFlags=0x80201);e.userHide(identity.packageName);assertEquals(0,s.data.last().originalBit);e.userRestore(identity.packageName);assertTrue(p.writes.isEmpty());assertEquals(0x80201,p.current.fineFlags)}
    @Test fun duplicateHideAndRestoreAreIdempotent() {val(_,p,e)=rig();e.userHide(identity.packageName);e.userHide(identity.packageName);e.userRestore(identity.packageName);e.userRestore(identity.packageName);assertEquals(listOf(0,256),p.writes)}
    @Test fun freshRegistrationAfterClosedRecordReadsNewOriginal() {val(s,p,e)=rig();e.userHide(identity.packageName);e.userRestore(identity.packageName);p.current=p.current.copy(fineFlags=0x80201);e.userHide(identity.packageName);assertEquals(0,s.data.last().originalBit)}
    @Test fun journalFailureForbidsWrite() {val(s,p,e)=rig();s.fail=true;expectFailure{e.userHide(identity.packageName)};assertTrue(p.writes.isEmpty());assertEquals(baseline,p.current)}
    @Test fun sharedUidForbidsWrite() {val(_,p,e)=rig();p.current=p.current.copy(peers=listOf(identity.packageName,"other.app"));expectFailure{e.userHide(identity.packageName)};assertTrue(p.writes.isEmpty())}
    @Test fun criticalSystemAppForbidsWrite() {val(_,p,e)=rig();p.current=p.current.copy(systemCritical=true);expectFailure{e.userHide(identity.packageName)};assertTrue(p.writes.isEmpty())}
    @Test fun fineDeniedForbidsWrite() {val(_,p,e)=rig();p.current=p.current.copy(fineGranted=false);expectFailure{e.userHide(identity.packageName)};assertTrue(p.writes.isEmpty())}
    @Test fun appOpsDeniedForbidsWrite() {val(_,p,e)=rig();p.current=p.current.copy(fineMode=1);expectFailure{e.userHide(identity.packageName)};assertTrue(p.writes.isEmpty())}
    @Test fun unsupportedOsBlocksBeforeWrite() {val(_,p,e)=rig();p.fw=p.fw.copy(oneUi="80000");expectFailure{e.userHide(identity.packageName)};assertTrue(p.writes.isEmpty())}
    @Test fun unknownAbiBlocksBeforeWrite() {val(_,p,e)=rig();p.fw=p.fw.copy(abiProfile="");expectFailure{e.userHide(identity.packageName)};assertTrue(p.writes.isEmpty())}
    @Test fun replyFailureAfterMutationRestoresOnlyOriginalBit() {val(_,p,e)=rig();p.failAfterWrite=true;assertEquals(TargetState.FAILED_SAFE,e.userHide(identity.packageName).state);assertEquals(listOf(0,256),p.writes);assertEquals(baseline,p.current)}
    @Test fun disconnectAfterMutationRetainsJournalAndBlocksFurtherWrites() {val(s,p,e)=rig();p.disconnect=true;assertEquals(TargetState.FAILED_ROLLBACK,e.userHide(identity.packageName).state);assertEquals(256,s.data.last().originalBit);p.disconnect=false;expectFailure{e.userHide(identity.packageName)};expectFailure{e.userRestore(identity.packageName)};assertEquals(listOf(0),p.writes)}
    @Test fun extraFlagIsNeverBroadlyOverwritten() {val(_,p,e)=rig();p.extraBit=true;assertEquals(TargetState.FAILED_ROLLBACK,e.userHide(identity.packageName).state);assertEquals(0x80311,p.current.fineFlags);assertEquals(listOf(0,256),p.writes)}
    @Test fun changedInstallDuringWriteNeverReceivesOldRollback() {val(_,p,e)=rig();p.swapInstall=true;assertEquals(TargetState.FAILED_ROLLBACK,e.userHide(identity.packageName).state);assertEquals(listOf(0),p.writes)}
    @Test fun appOpsChangeStopsEvenThoughMetadataWriteWasNarrow() {val(_,p,e)=rig();p.modeChange=true;assertEquals(TargetState.FAILED_ROLLBACK,e.userHide(identity.packageName).state);assertEquals(1,p.current.fineMode);assertEquals(listOf(0,256),p.writes)}
    @Test fun changedStateBetweenJournalAndWriteStopsWithoutMutation() {val(_,p,e)=rig();p.beforeWriteMutation=true;assertEquals(TargetState.FAILED_SAFE,e.userHide(identity.packageName).state);assertTrue(p.writes.isEmpty())}
    @Test fun reinstallUidChangeCannotRestoreOldJournal() {val(s,p,e)=rig();e.userHide(identity.packageName);p.current=baseline.copy(identity=identity.copy(uid=10795,firstInstallTime=500));expectFailure{e.userRestore(identity.packageName)};assertEquals(TargetState.INACTIVE_INSTALL,s.data.last().state);assertEquals(listOf(0),p.writes)}
    @Test fun signingIdentityChangeCannotApplyOldJournal() {val(_,p,e)=rig();e.userHide(identity.packageName);p.current=p.current.copy(identity=identity.copy(signers=listOf("other")));expectFailure{e.userHide(identity.packageName)};assertEquals(listOf(0),p.writes)}
    @Test fun firstInstallTimeDetectsUidReuse() {val(_,p,e)=rig();e.userHide(identity.packageName);p.current=baseline.copy(identity=identity.copy(firstInstallTime=900));expectFailure{e.userRestore(identity.packageName)};assertEquals(listOf(0),p.writes)}
    @Test fun updateReapplyPreservesOriginalJournal() {val(s,p,e)=rig();e.userHide(identity.packageName);p.current=baseline.copy(identity=identity.copy(versionCode=2,lastUpdateTime=900));e.userHide(identity.packageName);assertEquals(256,s.data.last().originalBit);assertEquals(listOf(0,0),p.writes)}
    @Test fun explicitNewInstallRegistrationUsesNewOriginal() {val(s,p,e)=rig();e.userHide(identity.packageName);p.current=baseline.copy(identity=identity.copy(uid=10795,firstInstallTime=700));expectFailure{e.userHide(identity.packageName)};e.userHide(identity.packageName);assertEquals(10795,s.data.last().identity.uid);assertEquals(256,s.data.last().originalBit)}
    @Test fun rebootMarkerNeverWritesOrPromisesStillApplied() {val(s,p,e)=rig();e.userHide(identity.packageName);val marked=Reconcile.boot(s.data.last());assertEquals(TargetState.NEEDS_REAPPLY_BOOT,Reconcile.read(marked,p.current,firmware.key,"boot:2").state);assertEquals(listOf(0),p.writes)}
    @Test fun updateMarkerNeverWrites() {val(s,p,e)=rig();e.userHide(identity.packageName);val marked=Reconcile.update(s.data.last());assertEquals(TargetState.NEEDS_REAPPLY_UPDATE,Reconcile.read(marked,p.current,firmware.key,"boot:1").state);assertEquals(listOf(0),p.writes)}
    @Test fun firmwareChangeMarksRecheckWithoutWrite() {val(s,p,e)=rig();e.userHide(identity.packageName);assertEquals(TargetState.NEEDS_RECHECK_FIRMWARE,Reconcile.read(s.data.last(),p.current,"new-fw","boot:1").state);assertEquals(listOf(0),p.writes)}
    @Test fun missingTargetLeavesOriginalRecordIntact() {val(s,p,e)=rig();e.userHide(identity.packageName);assertEquals(TargetState.MISSING_TARGET,Reconcile.read(s.data.last(),null,firmware.key,"boot:1").state);assertEquals(256,s.data.last().originalBit);assertEquals(listOf(0),p.writes)}
    @Test fun pendingHideRecoveryIsReadOnly() {val(s,p,e)=rig();e.userHide(identity.packageName);val pending=s.data.last().copy(state=TargetState.PENDING_HIDE);assertEquals(TargetState.FAILED_SAFE,Reconcile.read(pending,p.current,firmware.key,"boot:1").state);assertEquals(listOf(0),p.writes)}
    @Test fun asyncResetAt500msIsNeverApplied(){val s=Store();val p=Port(s);val e=SafetyEngine(p,s,{200},{"boot:1"},wait={if(it==500L)p.current=baseline});val result=e.userHide(identity.packageName);assertEquals(TargetState.FAILED_SAFE,result.state);assertFalse(result.settlingVerified);assertEquals(baseline,p.current);assertEquals(listOf(0),p.writes)}
    @Test fun asyncInvariantDriftAt1500msRestoresOnlyBitAndBlocks(){val s=Store();val p=Port(s);val e=SafetyEngine(p,s,{200},{"boot:1"},wait={if(it==1000L)p.current=p.current.copy(coarseGranted=false)});assertEquals(TargetState.FAILED_ROLLBACK,e.userHide(identity.packageName).state);assertEquals(listOf(0,256),p.writes);assertFalse(p.current.coarseGranted)}
    @Test fun legacyJournalIsNotFreshSuccess(){val(s,p,e)=rig();e.userHide(identity.packageName);assertEquals(TargetState.FAILED_SAFE,Reconcile.read(s.data.last().copy(settlingVerified=false),p.current,firmware.key,"boot:1").state)}
    @Test fun failedSafeCannotBecomeAppliedByOneRead(){val(s,p,e)=rig();e.userHide(identity.packageName);assertEquals(TargetState.FAILED_SAFE,Reconcile.read(s.data.last().copy(state=TargetState.FAILED_SAFE),p.current,firmware.key,"boot:1").state)}
    @Test fun noopRequiresSettlingWithoutSetter(){val s=Store();val p=Port(s);p.current=p.current.copy(fineFlags=0x80201);val delays=mutableListOf<Long>();val e=SafetyEngine(p,s,{200},{"boot:1"},wait={delays.add(it)});assertTrue(e.userHide(identity.packageName).settlingVerified);assertEquals(listOf(500L,1000L),delays);assertTrue(p.writes.isEmpty())}
    @Test fun historyIsNotPermissionInvariant(){assertTrue(baseline.invariantEquals(baseline.copy(lastLocationAccess=123),256));assertEquals(baseline.invariantDigest(),baseline.copy(lastLocationAccess=123).invariantDigest())}
    @Test fun recentOrderAndFallbackUseActualAccessOnly(){val a=baseline.copy(label="A",lastLocationAccess=null);val b=baseline.copy(label="B",lastLocationAccess=200);val c=baseline.copy(label="C",lastLocationAccess=100);assertEquals(listOf(b,c,a),CandidateOrder.sort(listOf(a,b,c),true));assertEquals(listOf(a,b,c),CandidateOrder.sort(listOf(c,b,a),false))}
    @Test fun pendingRestoreAlreadyOriginalIsClosedWithoutWrite() {val(s,p,e)=rig();e.userHide(identity.packageName);val pending=s.data.last().copy(state=TargetState.PENDING_RESTORE);val recovered=Reconcile.read(pending,baseline,firmware.key,"boot:1");assertEquals(TargetState.NOT_APPLIED,recovered.state);assertFalse(recovered.enabled);assertEquals(listOf(0),p.writes)}
    @Test fun interruptedWriteWithExtraFlagCannotBeReconciledAsSuccess(){val(s,p,e)=rig();e.userHide(identity.packageName);val pending=s.data.last().copy(state=TargetState.PENDING_HIDE);p.current=p.current.copy(fineFlags=p.current.fineFlags or 0x10);assertEquals(TargetState.FAILED_ROLLBACK,Reconcile.read(pending,p.current,firmware.key,"boot:1").state);s.commit(pending);expectFailure{e.userRestore(identity.packageName)};assertEquals(listOf(0),p.writes)}
    @Test fun eventMarkersNeverEraseInterruptedTransaction(){val(s,_,e)=rig();e.userHide(identity.packageName);val pending=s.data.last().copy(state=TargetState.PENDING_RESTORE);assertEquals(pending,Reconcile.boot(pending));assertEquals(pending,Reconcile.update(pending))}
    @Test fun permissionChangeRequiresManualReviewWithoutWrites(){val(s,p,e)=rig();e.userHide(identity.packageName);p.current=p.current.copy(backgroundGranted=true);assertEquals(TargetState.UNSUPPORTED_STATE,Reconcile.read(s.data.last(),p.current,firmware.key,"boot:1").state);assertEquals(listOf(0),p.writes)}
    @Test fun verificationDigestExcludesOnlySensitivityBit(){assertEquals(baseline.invariantDigest(),baseline.copy(fineFlags=baseline.fineFlags and 256.inv()).invariantDigest());assertNotEquals(baseline.invariantDigest(),baseline.copy(coarseFlags=baseline.coarseFlags xor 0x100).invariantDigest())}
}
