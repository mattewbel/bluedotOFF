package app.bluedotoff.core
import org.junit.Assert.*
import org.junit.Test

class RestorePipelineTest {
    private fun rig(count:Int=8,off:Boolean=false):HidePipelineTest.Rig {
        val r=HidePipelineTest.Rig(count,off,10,3)
        var cursor=0
        while(cursor<count)cursor+=r.window(r.names.drop(cursor).take(12)).started
        r.clock=0;r.writes.clear();r.phases.clear();r.maximumPending=0
        return r
    }
    private fun restore(r:HidePipelineTest.Rig,names:List<String> = r.names.take(12))=r.engine().userRestoreWindow(names,canStart={r.active},elapsed={r.clock})
    @Test fun seventyFourRestoresOverlapOnlyWaitsAndPreserveEveryCheckpoint(){
        val fast=rig(74);val serial=rig(74);var cursor=0
        while(cursor<74){val result=restore(fast,fast.names.drop(cursor).take(12));assertFalse(result.stopped);assertTrue(result.results.all{it.success});cursor+=result.started}
        serial.names.forEach{assertEquals(TargetState.NOT_APPLIED,serial.engine().userRestore(it).state)}
        assertEquals(serial.snapshots,fast.snapshots);assertEquals(74,fast.writes.size);assertTrue(fast.records.values.none{it.enabled});assertTrue(fast.maximumPending<=4)
        fast.names.forEach{pkg->val stages=fast.phases.filter{it.first==pkg}.associate{it.second to it.third};assertTrue(stages.getValue("settled_500ms")-stages.getValue("immediate")>=500);assertTrue(stages.getValue("settled_1500ms")-stages.getValue("settled_500ms")>=1000)}
        assertTrue(fast.clock<serial.clock/2)
        println("RESTORE_MODEL_74 serial=${serial.clock}ms overlapped=${fast.clock}ms; not device timing")
    }
    @Test fun originallyOffNeverInventsAnOnBit(){val r=rig(8,true);val result=restore(r);assertTrue(result.results.all{it.success});assertTrue(r.writes.isEmpty());assertTrue(r.records.values.all{it.originalBit==0&&!it.enabled})}
    @Test fun backgroundStopsAdmissionsAndDrainsStartedRestores(){val r=rig();r.onPhase={phase,s,_->if(phase=="immediate"&&s.identity.packageName==r.names[2])r.active=false};val result=restore(r);assertTrue(result.stopped);assertEquals(3,result.started);assertTrue(result.results.all{it.success});assertTrue(r.names.drop(3).all{r.snapshots[it]!!.bit==0&&r.records[it]!!.enabled})}
    @Test fun backgroundDuringDurableCommitPreventsThatSetter(){val r=rig();r.onCommit={if(it.identity.packageName==r.names[1])r.active=false};val result=restore(r);assertEquals(1,r.writes.size);assertTrue(result.stopped);assertFalse(result.results[1].success)}
    @Test fun disconnectedPendingRestoresNeverRetryOrLoseOriginals(){val r=rig();r.onWait={r.disconnected=true};val result=restore(r);assertEquals(4,r.writes.size);assertTrue(result.results.none{it.success});assertTrue(r.records.values.take(4).all{it.originalBit==256&&it.state==TargetState.FAILED_ROLLBACK})}
    @Test fun failedFinalCommitLeavesDurablePendingWithoutNewWrites(){val r=rig();r.onCommit={if(it.state==TargetState.NOT_APPLIED)r.failStorage=true};val result=restore(r);assertTrue(result.results.none{it.success});assertEquals(4,r.writes.size);assertTrue(r.records.values.take(4).all{it.state==TargetState.PENDING_RESTORE&&it.originalBit==256})}
    @Test fun peerChangeCannotBeRolledBackIntoAnotherOwner(){val r=rig();r.onPhase={phase,s,_->if(phase=="immediate"&&s.identity.packageName==r.names[0])r.snapshots[r.names[0]]=s.copy(peers=listOf(r.names[0],"new.peer"))};val result=restore(r);assertTrue(result.stopped);assertEquals(TargetState.NEEDS_REVIEW,r.records[r.names[0]]!!.state);assertEquals(1,r.writes.count{it.first==r.names[0]})}
    @Test fun singleOperationChecksForegroundAgainAfterCommit(){val r=rig(1);r.onCommit={r.active=false};val e=r.engine().userOne(r.names[0],false){r.active};assertEquals(TargetState.FAILED_SAFE,e.state);assertTrue(r.writes.isEmpty());assertEquals(0,r.snapshots[r.names[0]]!!.bit)}
}
