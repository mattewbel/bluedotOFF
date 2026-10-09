package app.bluedotoff.core
import org.junit.Assert.*
import org.junit.Test

class SharedGroupTest {
    private class Rig {
        var clock=0L;var commits=0;var failAt=0;var throwAt=0;var active=true;var disconnected=false
        var onWrite:()->Unit={};var onWait:()->Unit={};var onCommit:()->Unit={}
        val names=listOf("test.dialer","test.provider")
        val snapshots=names.mapIndexed{i,p->Snapshot(Identity(p,0,10129,listOf("signer$i"),1,1,1),p,names,i==0,i==0,i==0,false,0x300,0x300,0,4,4,false,sharedUserId="test.shared",coarseDeclared=i==0)}.associateBy{it.identity.packageName}.toMutableMap()
        val records=linkedMapOf<String,Entry>();val writes=mutableListOf<Int>()
        val fw=Firmware("samsung",37,"90000","fw","id","model","abi")
        val journal=object:Journal{
            override fun entries()=records.values.toList()
            override fun commit(entry:Entry)=error("Must commit the whole group")
            override fun commitGroup(entries:List<Entry>):Boolean {commits++;onCommit();if(commits==throwAt)throw IllegalStateException("fsync");if(commits==failAt)return false;assertEquals(names,entries.map{it.identity.packageName});entries.forEach{records[it.identity.packageName]=it};return true}
        }
        val port=object:TargetPort{
            override fun compatibleFirmware()=fw
            override fun read(packageName:String):Snapshot {check(!disconnected);return snapshots.getValue(packageName)}
            override fun writeFineBit(packageName:String,originalBit:Int)=error("Individual writes must stay blocked")
            override fun writeFineGroupChecked(expected:List<Snapshot>,wanted:Int){SharedGroupPolicy.validate(expected);assertTrue(records.values.all{it.state in setOf(TargetState.PENDING_HIDE,TargetState.PENDING_RESTORE)&&it.groupIdentities.size==2});assertTrue(SharedGroupPolicy.same(expected,expected.map{read(it.identity.packageName)},expected.first().bit));writes+=wanted;snapshots.replaceAll{_,s->s.copy(fineFlags=(s.fineFlags and 256.inv())or wanted)};onWrite()}
        }
        fun engine()=SharedGroupEngine(port,journal,{clock},{"boot"},wait={clock+=it;onWait()})
        fun run(hide:Boolean=true)=engine().execute(names,hide){active}
    }
    @Test fun companionWithoutLocationDeclarationIsPinnedButNeverGetsAGrant(){val r=Rig();val baseline=r.snapshots.toMap();val hidden=r.run();assertEquals(listOf(0),r.writes);assertTrue(hidden.all{it.originalBit==256&&it.settlingVerified&&it.groupIdentities.size==2});assertFalse(r.snapshots[r.names[1]]!!.fineGranted);assertEquals(1500L,r.clock);r.run(false);assertEquals(listOf(0,256),r.writes);assertEquals(baseline,r.snapshots);assertTrue(r.records.values.none{it.enabled})}
    @Test fun originalOffRemainsOffAfterRestore(){val r=Rig();r.snapshots.replaceAll{_,s->s.copy(fineFlags=0x200)};r.run();r.run(false);assertTrue(r.writes.isEmpty());assertTrue(r.records.values.all{it.originalBit==0&&!it.enabled})}
    @Test fun incompleteConsentCannotChangeAnUnlistedPeer(){val r=Rig();try{r.engine().execute(listOf(r.names[0]),true){true};fail()}catch(_:IllegalArgumentException){};assertTrue(r.writes.isEmpty());assertTrue(r.records.isEmpty())}
    @Test fun criticalOrPersistentPeerBlocksTheEntireGroup(){for(change in listOf<(Snapshot)->Snapshot>({it.copy(persistent=true)},{it.copy(criticalPackage=true)},{it.copy(identity=it.identity.copy(uid=1000))},{it.copy(identity=it.identity.copy(signers=emptyList()))})){val r=Rig();val p=r.names[1];r.snapshots[p]=change(r.snapshots[p]!!);assertTrue(runCatching{r.run()}.isFailure);assertTrue(r.records.isEmpty());assertTrue(r.writes.isEmpty())}}
    @Test fun firstDurableFailureIssuesNoSetter(){val r=Rig();r.failAt=1;assertTrue(runCatching{r.run()}.exceptionOrNull() is JournalPersistenceException);assertTrue(r.writes.isEmpty());assertTrue(r.records.isEmpty())}
    @Test fun finalCommitFailureKeepsBothPendingAndNeverRollsBackWithBrokenStorage(){for(thrown in listOf(false,true)){val r=Rig();if(thrown)r.throwAt=2 else r.failAt=2;assertTrue(runCatching{r.run()}.exceptionOrNull() is JournalPersistenceException);assertEquals(listOf(0),r.writes);assertTrue(r.records.values.all{it.state==TargetState.PENDING_HIDE&&it.originalBit==256})}}
    @Test fun changedPeerAtFirstWaitBlocksEveryRollback(){val r=Rig();r.onWait={r.snapshots.replaceAll{_,s->s.copy(peers=r.names+"test.new")}};val failed=r.run();assertTrue(failed.all{it.state==TargetState.NEEDS_REVIEW});assertEquals(listOf(0),r.writes)}
    @Test fun disconnectedGroupNeverRetries(){val r=Rig();r.onWait={r.disconnected=true};val failed=r.run();assertTrue(failed.all{it.state==TargetState.FAILED_ROLLBACK});assertEquals(listOf(0),r.writes);assertTrue(r.records.values.all{it.originalBit==256})}
    @Test fun foregroundLostDuringFsyncStopsTheWholeGroupBeforeWrite(){val r=Rig();r.onCommit={r.active=false};val failed=r.run();assertTrue(failed.all{it.state==TargetState.FAILED_SAFE});assertTrue(r.writes.isEmpty());assertTrue(r.snapshots.values.all{it.bit==256})}
    @Test fun individualGuardStillRejectsBothPeers(){val r=Rig();r.snapshots.values.forEach{s->assertTrue(runCatching{LocalWriteGuard.validate(s,LocalWriteGuard.identityDigest(s.identity),s.invariantDigest(),s.fineFlags,0)}.exceptionOrNull() is OwnershipChangedException)}}
    @Test fun noPartialOrConflictingOriginalRecordIsAccepted(){val r=Rig();r.run();val saved=r.records.toMap();r.records.remove(r.names[1]);assertTrue(runCatching{r.run(false)}.isFailure);assertEquals(listOf(0),r.writes);r.records.putAll(saved);r.records[r.names[1]]=r.records[r.names[1]]!!.copy(originalBit=0);assertTrue(runCatching{r.run(false)}.isFailure);assertEquals(listOf(0),r.writes)}
    @Test fun driftOfNonSensitivityBitNeverTriggersBroadRollback(){val r=Rig();r.onWait={r.snapshots.replaceAll{_,s->s.copy(fineFlags=s.fineFlags or 16)}};val failed=r.run();assertTrue(failed.all{it.state==TargetState.FAILED_ROLLBACK});assertEquals(listOf(0),r.writes);assertTrue(r.snapshots.values.all{it.fineFlags and 16==16})}
    @Test fun restoreDetectsAReplacedCompanionBeforeAnySetter(){val r=Rig();r.run();val p=r.names[1];r.snapshots[p]=r.snapshots[p]!!.copy(identity=r.snapshots[p]!!.identity.copy(firstInstallTime=99));assertTrue(runCatching{r.run(false)}.exceptionOrNull() is OwnershipChangedException);assertEquals(listOf(0),r.writes)}
    @Test fun groupRecoveryNeverWritesAfterInterruptedPending(){val r=Rig();r.run();val e=r.records.values.first().copy(state=TargetState.PENDING_HIDE);val next=Reconcile.read(e,r.snapshots[e.identity.packageName],r.fw.key,"boot",r.snapshots);assertEquals(TargetState.NEEDS_REVIEW,next.state);assertEquals(listOf(0),r.writes)}
    @Test fun groupUpdateAndPeerChangeCannotLookApplied(){val r=Rig();r.run();val e=r.records.values.first();val p=r.names[1];r.snapshots[p]=r.snapshots[p]!!.copy(identity=r.snapshots[p]!!.identity.copy(versionCode=2));assertEquals(TargetState.NEEDS_REAPPLY_UPDATE,Reconcile.read(e,r.snapshots[e.identity.packageName],r.fw.key,"boot",r.snapshots).state);r.snapshots[p]=r.snapshots[p]!!.copy(peers=listOf(p));assertEquals(TargetState.NEEDS_REVIEW,Reconcile.read(e,r.snapshots[e.identity.packageName],r.fw.key,"boot",r.snapshots).state)}
}
