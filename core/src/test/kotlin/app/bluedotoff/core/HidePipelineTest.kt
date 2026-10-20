package app.bluedotoff.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class HidePipelineTest {
    // Limits under test; the expectations below scale with them instead of hard-coding 4 and 12.
    private val F=HideWindowOutcome.MAX_IN_FLIGHT
    private val W=BatchWindow.MAX_TARGETS
    internal class Rig(count:Int=HideWindowOutcome.MAX_IN_FLIGHT*2,off:Boolean=false,val readCost:Long=0,val commitCost:Long=0) {
        var clock=0L
        var active=true
        var disconnected=false
        var failStorage=false
        var corruptRead=false
        var throwCommit=false
        var onCommit:(Entry)->Unit={}
        var onPhase:(String,Snapshot,Boolean)->Unit={_,_,_->}
        var onRead:(String,Snapshot)->Snapshot={_,snapshot->snapshot}
        var afterWrite:(String)->Unit={}
        var onWait:(Long)->Unit={}
        val snapshots=linkedMapOf<String,Snapshot>()
        val records=linkedMapOf<String,Entry>()
        val writes=mutableListOf<Triple<String,Int,Long>>()
        val phases=mutableListOf<Triple<String,String,Long>>()
        var maximumPending=0
        val names get()=snapshots.keys.toList()
        val firmware=Firmware("samsung",37,"90000","firmware","id","model","verified")
        init { repeat(count){n->val pkg="test.location$n";snapshots[pkg]=Snapshot(
            Identity(pkg,0,10000+n,listOf("signer"),1,1,1),pkg,listOf(pkg),true,true,true,false,
            if(off)0x80201 else 0x80301,0x301,0,4,4,false)} }
        val journal=object:Journal {
            override fun entries():List<Entry>{if(corruptRead)throw IllegalStateException("corrupt journal");return records.values.toList()}
            override fun commit(entry:Entry):Boolean {
                clock+=commitCost
                onCommit(entry)
                if(throwCommit)throw IllegalStateException("fsync failed")
                if(failStorage)return false
                records[entry.identity.packageName]=entry
                maximumPending=maxOf(maximumPending,records.values.count{it.state in setOf(TargetState.PENDING_HIDE,TargetState.PENDING_RESTORE)})
                return true
            }
        }
        val port=object:TargetPort {
            override fun compatibleFirmware()=firmware
            override fun read(packageName:String):Snapshot {
                clock+=readCost
                if(disconnected)throw IllegalStateException("transport unavailable")
                return onRead(packageName,snapshots.getValue(packageName))
            }
            override fun writeFineBit(packageName:String,originalBit:Int) {
                assertFalse(disconnected)
                val pending=records.getValue(packageName)
                assertTrue(pending.state in listOf(TargetState.PENDING_HIDE,TargetState.PENDING_RESTORE))
                assertFalse(pending.settlingVerified)
                writes.add(Triple(packageName,originalBit,clock))
                val before=snapshots.getValue(packageName)
                snapshots[packageName]=before.copy(fineFlags=(before.fineFlags and 256.inv()) or originalBit)
                afterWrite(packageName)
            }
            override fun writeFineBitChecked(expected:Snapshot,wanted:Int) {
                val fresh=read(expected.identity.packageName)
                LocalWriteGuard.validate(fresh,LocalWriteGuard.identityDigest(expected.identity),expected.invariantDigest(),expected.fineFlags,wanted)
                writeFineBit(expected.identity.packageName,wanted)
            }
        }
        fun engine(wait:(Long)->Unit={clock+=it;onWait(it)})=SafetyEngine(port,journal,{clock},{"boot"},wait=wait,observe={phase,snapshot,wrote->
            phases.add(Triple(snapshot.identity.packageName,phase,clock));onPhase(phase,snapshot,wrote)
        })
        fun window(names:List<String> = this.names.take(BatchWindow.MAX_TARGETS),onResult:(BatchResult)->Unit={}):HideWindowOutcome =
            engine().userHideWindow(names,canStart={active},elapsed={clock},onResult=onResult)
    }
    @Test fun seventyFourAppsOverlapWaitsButKeepEveryCheckDurableOriginalAndWindowBound() {
        val fast=Rig(74,readCost=10,commitCost=3)
        var cursor=0;val fastDurations=mutableListOf<Long>()
        while(cursor<fast.names.size){val start=fast.clock
            val result=fast.window(fast.names.drop(cursor).take(W)){r->
                assertTrue(r.success);assertEquals(r.entry,fast.records[r.packageName]);assertTrue(r.entry!!.settlingVerified)
            }
            assertFalse(result.stopped);assertTrue(result.results.all{it.success});cursor+=result.started
            fastDurations.add(fast.clock-start)
        }
        val serial=Rig(74,readCost=10,commitCost=3)
        serial.names.forEach{assertEquals(TargetState.APPLIED,serial.engine().userHide(it).state)}
        assertEquals(74,fast.writes.size);assertEquals(serial.snapshots,fast.snapshots)
        assertTrue(fast.records.values.all{it.originalBit==256&&it.settlingVerified})
        assertTrue(fast.maximumPending<=F);assertEquals((74+W-1)/W,fastDurations.size)
        assertTrue(fastDurations.all{it<55000})
        fast.names.forEach{pkg->
            val checkpoints=fast.phases.filter{it.first==pkg}.associate{it.second to it.third}
            assertTrue(checkpoints.getValue("settled_500ms")-checkpoints.getValue("immediate")>=500)
            assertTrue(checkpoints.getValue("settled_1500ms")-checkpoints.getValue("settled_500ms")>=1000)
            assertEquals(listOf("before","preflight","immediate","settled_500ms","settled_1500ms"),fast.phases.filter{it.first==pkg}.map{it.second})
        }
        assertTrue(fast.clock<serial.clock/2)
        println("PIPELINE_MODEL_74 read=10ms commit=3ms serial=${serial.clock}ms overlapped=${fast.clock}ms maxPending=${fast.maximumPending}; not physical-device timing")
    }
    @Test fun alreadyOffSeventyFourAppsNeverInventOnBitsOrIssueSetters() {
        val rig=Rig(74,off=true);var cursor=0
        while(cursor<74){val result=rig.window(rig.names.drop(cursor).take(W));cursor+=result.started;assertTrue(result.results.all{it.success})}
        assertTrue(rig.writes.isEmpty());assertTrue(rig.records.values.all{it.originalBit==0&&!it.changedByUs&&it.settlingVerified})
        rig.names.forEach{rig.engine().userRestore(it)}
        assertTrue(rig.writes.isEmpty());assertTrue(rig.records.values.none{it.enabled})
    }
    @Test fun leavingAfterEveryAppStartedFinishesWithoutTheLeftScreenMessage() {
        val rig=Rig(4);rig.onWait={rig.active=false}
        val result=rig.window()
        assertEquals(4,result.started);assertFalse(result.stopped);assertEquals("",result.reason)
        assertTrue(result.results.all{it.success&&it.entry!!.settlingVerified})
    }
    @Test fun existingOnOriginalsSurviveNoopReapplyAndSerialRestore() {
        val rig=Rig(4);val baseline=rig.snapshots.toMap()
        rig.window();val writes=rig.writes.size
        rig.window();assertEquals(writes,rig.writes.size)
        assertTrue(rig.records.values.all{it.originalBit==256&&it.changedByUs})
        rig.names.forEach{rig.engine().userRestore(it)}
        assertEquals(baseline,rig.snapshots);assertEquals(listOf(0,0,0,0,256,256,256,256),rig.writes.map{it.second})
    }
    @Test fun firstFailureStopsAdmissionAndDrainsOtherStartedTargets() {
        val rig=Rig();val first=rig.names.first()
        rig.onPhase={phase,snapshot,_->if(phase=="immediate"&&snapshot.identity.packageName==first)rig.snapshots[first]=snapshot.copy(fineFlags=snapshot.fineFlags or 16)}
        val result=rig.window()
        assertTrue(result.stopped);assertEquals(F,result.started);assertEquals(rig.names.take(F),result.results.map{it.packageName})
        assertEquals(TargetState.FAILED_ROLLBACK,rig.records[first]!!.state)
        assertTrue(result.results.drop(1).all{it.success});assertTrue(rig.names.drop(F).none{it in rig.records})
        assertEquals(16,rig.snapshots[first]!!.fineFlags and 16)
    }
    @Test fun delayedResetAtFinalStageIsNotPublishedAsAppliedAndStopsTheNextWave() {
        val rig=Rig();val first=rig.names.first()
        rig.onPhase={phase,snapshot,_->if(phase=="settled_500ms"&&snapshot.identity.packageName==first)rig.snapshots[first]=snapshot.copy(fineFlags=snapshot.fineFlags or 256)}
        val result=rig.window()
        assertTrue(result.stopped);assertEquals(F,result.started);assertFalse(result.results.first().success)
        assertEquals(TargetState.FAILED_SAFE,rig.records[first]!!.state);assertFalse(rig.records[first]!!.settlingVerified)
        assertEquals(1,rig.writes.count{it.first==first})
    }
    @Test fun transientPeerAdditionNeverRollsBackEvenWhenPeerRevertsBeforeRecoveryRead() {
        val rig=Rig();val first=rig.names.first()
        rig.onPhase={phase,snapshot,_->if(phase=="immediate"&&snapshot.identity.packageName==first)rig.snapshots[first]=snapshot.copy(peers=listOf(first,"other.app"))}
        rig.onRead={pkg,snapshot->if(pkg==first&&snapshot.peers.size>1){rig.snapshots[first]=snapshot.copy(peers=listOf(first))};snapshot}
        val result=rig.window()
        assertTrue(result.stopped);assertEquals(TargetState.NEEDS_REVIEW,rig.records[first]!!.state)
        assertEquals(listOf(0),rig.writes.filter{it.first==first}.map{it.second});assertTrue(result.results.drop(1).all{it.success})
    }
    @Test fun installReplacementNeverGetsTheOldOriginalAndBlocksLaterAdmissions() {
        val rig=Rig();val first=rig.names.first()
        rig.onPhase={phase,snapshot,_->if(phase=="immediate"&&snapshot.identity.packageName==first)rig.snapshots[first]=snapshot.copy(identity=snapshot.identity.copy(firstInstallTime=99,signers=listOf("new-signer")))}
        val result=rig.window()
        assertTrue(result.stopped);assertEquals(TargetState.FAILED_ROLLBACK,rig.records[first]!!.state)
        assertEquals(listOf(0),rig.writes.filter{it.first==first}.map{it.second});assertEquals(listOf("new-signer"),rig.snapshots[first]!!.identity.signers)
    }
    @Test fun grantDriftCannotBypassTheFreshHelperGuardForRollbackOrChangeTheGrant() {
        val rig=Rig();val first=rig.names.first()
        rig.onPhase={phase,snapshot,_->if(phase=="immediate"&&snapshot.identity.packageName==first)rig.snapshots[first]=snapshot.copy(coarseGranted=false)}
        val result=rig.window()
        assertTrue(result.stopped);assertFalse(rig.snapshots[first]!!.coarseGranted)
        assertEquals(listOf(0),rig.writes.filter{it.first==first}.map{it.second});assertEquals(TargetState.FAILED_ROLLBACK,rig.records[first]!!.state)
    }
    @Test fun disconnectWithFourPendingWritesPreservesEveryOriginalAndNeverRetries() {
        val rig=Rig();rig.onWait={rig.disconnected=true}
        val result=rig.window()
        assertTrue(result.stopped);assertEquals(F,result.started);assertEquals(F,rig.writes.size)
        assertTrue(result.results.none{it.success});assertTrue(rig.records.values.all{it.originalBit==256&&it.state==TargetState.FAILED_ROLLBACK})
        rig.disconnected=false
        result.results.forEach{assertEquals(TargetState.FAILED_ROLLBACK,Reconcile.read(rig.records[it.packageName]!!,rig.snapshots[it.packageName],rig.firmware.key,"boot").state)}
        assertEquals(F,rig.writes.size)
    }
    @Test fun ambiguousSetterReplyOnSecondTargetRestoresOnlyThatTargetAndDrainsFirst() {
        val rig=Rig();val second=rig.names[1]
        rig.afterWrite={pkg->if(pkg==second&&rig.writes.count{it.first==pkg}==1)throw IllegalStateException("ambiguous reply")}
        val result=rig.window()
        assertTrue(result.stopped);assertEquals(2,result.started);assertTrue(result.results.first().success)
        assertEquals(TargetState.FAILED_SAFE,rig.records[second]!!.state)
        assertEquals(listOf(0,256),rig.writes.filter{it.first==second}.map{it.second});assertTrue(rig.names.drop(2).none{it in rig.records})
    }
    @Test fun initialJournalFailureCannotIssueAnySetter() {
        val rig=Rig();rig.failStorage=true
        val result=rig.window();assertTrue(result.stopped);assertEquals(1,result.started)
        assertTrue(rig.writes.isEmpty());assertTrue(rig.records.isEmpty())
    }
    @Test fun secondPendingJournalFailureKeepsFirstPendingAndStopsAllFurtherReadsAndWrites() {
        val rig=Rig();val second=rig.names[1]
        rig.onCommit={if(it.identity.packageName==second)rig.failStorage=true}
        val result=rig.window()
        assertTrue(result.stopped);assertEquals(2,result.started);assertTrue(result.results.none{it.success})
        assertEquals(1,rig.writes.size);assertEquals(TargetState.PENDING_HIDE,rig.records[rig.names[0]]!!.state)
        assertEquals(256,rig.records[rig.names[0]]!!.originalBit)
    }
    @Test fun failedFinalDurableCommitLeavesAllFourPendingAndNeverIssuesNewSetters() {
        val rig=Rig();rig.onCommit={if(it.state==TargetState.APPLIED)rig.failStorage=true}
        val result=rig.window()
        assertTrue(result.stopped);assertEquals(F,result.started);assertEquals(F,rig.writes.size)
        assertTrue(result.results.none{it.success});assertTrue(rig.records.values.all{it.state==TargetState.PENDING_HIDE&&it.originalBit==256&&!it.settlingVerified})
    }
    @Test fun failedRollbackCommitStopsDrainWritesAndLeavesDurablePendingEvidence() {
        val rig=Rig();val first=rig.names.first()
        rig.onPhase={phase,snapshot,_->if(phase=="immediate"&&snapshot.identity.packageName==first)rig.snapshots[first]=snapshot.copy(fineMode=1)}
        rig.onCommit={if(it.state==TargetState.FAILED_ROLLBACK)rig.failStorage=true}
        val result=rig.window();assertTrue(result.stopped)
        assertTrue(rig.records.values.all{it.state==TargetState.PENDING_HIDE})
        assertTrue(result.results.none{it.success});assertEquals(F,rig.writes.size) // denied mode blocks even rollback's fresh guard
    }
    @Test fun backgroundBeforeStartDoesNotCreateRecordsOrSetters() {
        val rig=Rig();rig.active=false
        val result=rig.window();assertTrue(result.stopped);assertEquals(0,result.started)
        assertTrue(rig.records.isEmpty());assertTrue(rig.writes.isEmpty())
    }
    @Test fun backgroundAfterThreeTargetsDrainsThemAndLeavesRestUntouched() {
        val rig=Rig();rig.onPhase={phase,snapshot,_->if(phase=="immediate"&&snapshot.identity.packageName==rig.names[2])rig.active=false}
        val result=rig.window();assertTrue(result.stopped);assertEquals(3,result.started)
        assertTrue(result.results.all{it.success});assertEquals(3,rig.writes.size)
        assertTrue(rig.names.drop(3).none{it in rig.records})
    }
    @Test fun foregroundLossDuringPendingCommitIsCheckedAgainBeforeSetter() {
        val rig=Rig();val second=rig.names[1]
        rig.onCommit={if(it.identity.packageName==second)rig.active=false}
        val result=rig.window();assertTrue(result.stopped);assertEquals(2,result.started)
        assertEquals(1,rig.writes.size);assertTrue(result.results.first().success);assertFalse(result.results[1].success)
        assertEquals(256,rig.snapshots[second]!!.bit)
    }
    @Test fun failedWaitSafelyResolvesEveryStartedOriginalWithoutNewTargets() {
        val rig=Rig();val result=rig.engine(wait={throw InterruptedException("cancelled")}).userHideWindow(rig.names,elapsed={rig.clock})
        assertTrue(result.stopped);assertEquals(F,result.started);assertTrue(result.results.none{it.success})
        assertTrue(rig.snapshots.values.all{it.bit==256});assertEquals(F*2,rig.writes.size)
    }
    @Test fun callbackFailureDoesNotAbandonStartedOperationsOrAllowNewAdmissions() {
        val rig=Rig();val result=rig.window{throw IllegalStateException("observer failed")}
        assertTrue(result.stopped);assertEquals(F,result.started);assertTrue(result.results.all{it.success})
        assertTrue(rig.records.values.all{it.settlingVerified});assertEquals(F,rig.writes.size)
    }
    @Test fun elapsedBudgetStopsAdmissionButAlwaysDrainsStartedChecks() {
        val rig=Rig(W,readCost=1000);val result=rig.window()
        assertFalse(result.stopped);assertTrue(result.started in 1 until W)
        assertEquals(result.started,result.results.size);assertTrue(result.results.all{it.success})
        assertTrue(rig.records.values.all{it.settlingVerified})
    }
    @Test fun sameUidAsAnInFlightTargetIsRejectedBeforeSecondPendingOrSetter() {
        val rig=Rig();val first=rig.names[0];val second=rig.names[1]
        rig.snapshots[second]=rig.snapshots[second]!!.copy(identity=rig.snapshots[second]!!.identity.copy(uid=rig.snapshots[first]!!.identity.uid))
        val result=rig.window();assertTrue(result.stopped);assertEquals(2,result.started)
        assertEquals(1,rig.writes.size);assertFalse(second in rig.records);assertTrue(result.results.first().success)
    }
    @Test fun duplicatesAndOversizedWindowsAreRejectedWithoutReadingOrWriting() {
        val rig=Rig(W+1)
        listOf(listOf(rig.names[0],rig.names[0]),rig.names).forEach{names->
            try{rig.window(names);fail("expected rejection")}catch(_:IllegalArgumentException){}
        }
        assertTrue(rig.records.isEmpty());assertTrue(rig.writes.isEmpty())
    }
    @Test fun globalOwnerBlocksASecondEngineThroughoutTheOverlapWindow() {
        val rig=Rig(5);val waiting=CountDownLatch(1);val release=CountDownLatch(1);val secondFinished=CountDownLatch(1)
        val errors=mutableListOf<Throwable>()
        val first=thread {try{rig.engine(wait={waiting.countDown();check(release.await(3,TimeUnit.SECONDS));rig.clock+=it}).userHideWindow(rig.names.take(4),elapsed={rig.clock})}catch(t:Throwable){synchronized(errors){errors+=t}}}
        assertTrue(waiting.await(3,TimeUnit.SECONDS))
        val second=thread {try{rig.engine().userHide(rig.names[4]);secondFinished.countDown()}catch(t:Throwable){synchronized(errors){errors+=t}}}
        assertFalse(secondFinished.await(100,TimeUnit.MILLISECONDS));assertEquals(4,rig.writes.size)
        release.countDown();first.join(3000);second.join(3000)
        assertFalse(first.isAlive);assertFalse(second.isAlive);assertTrue(errors.toString(),errors.isEmpty());assertEquals(5,rig.writes.size)
    }
    @Test fun serialBatchRunnerAlsoStopsOnJournalFaultInsteadOfSkippingPastIt() {
        val rig=Rig();rig.failStorage=true
        val results=TargetBatch.run(rig.names,rig.engine()::userHide)
        assertTrue(results.none{it.success});assertTrue(rig.writes.isEmpty());assertTrue(results[1].error.contains("앞선 작업"))
    }
    @Test fun corruptJournalReadWhileAnotherAppIsPendingStopsAllNewSetters() {
        val rig=Rig();rig.onPhase={phase,_,_->if(phase=="immediate")rig.corruptRead=true}
        val result=rig.window();assertTrue(result.stopped);assertEquals(2,result.started)
        assertEquals(1,rig.writes.size);assertEquals(TargetState.PENDING_HIDE,rig.records[rig.names.first()]!!.state)
        assertTrue(result.results.none{it.success})
    }
    @Test fun thrownFinalCommitIsTreatedLikeFailedFsyncWithoutFurtherMutation() {
        val rig=Rig();rig.onCommit={if(it.state==TargetState.APPLIED)rig.throwCommit=true}
        val result=rig.window();assertTrue(result.stopped);assertEquals(F,result.started)
        assertEquals(F,rig.writes.size);assertTrue(result.results.none{it.success})
        assertTrue(rig.records.values.all{it.state==TargetState.PENDING_HIDE})
    }
    @Test fun storageFaultDuringInterruptedDrainPreventsEveryRemainingRollbackSetter() {
        val rig=Rig();rig.onCommit={if(it.state==TargetState.FAILED_SAFE)rig.failStorage=true}
        val result=rig.engine(wait={throw InterruptedException("cancelled")}).userHideWindow(rig.names,elapsed={rig.clock})
        assertTrue(result.stopped);assertEquals(F,result.started);assertTrue(result.results.none{it.success})
        assertEquals(F+1,rig.writes.size);assertEquals(1,rig.writes.count{it.second==256})
        assertTrue(rig.records.values.all{it.state==TargetState.PENDING_HIDE})
    }
}
