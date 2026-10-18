package app.bluedotoff.core
import org.junit.Assert.*
import org.junit.Test

/** One set of failure cases, run against both overlapping windows: single apps (SafetyEngine.userHideWindow) and shared
 *  groups (SharedGroupEngine.executeWindow). Added in 1.1.4 after the group window missed a storage check the single-app
 *  window had (Codex review): a safety rule must not be fixed in one window and forgotten in the other. */
class WindowSafetyContractTest {
    private enum class Kind { SINGLE, GROUP }
    private class World(val kind:Kind,count:Int=3) {
        var clock=0L;var commits=0;var failAt=0;var active=true
        var onWait:()->Unit={};var afterWrite:(Int)->Unit={}
        /** Each unit: one app, or the two members of one shared group. */
        val units=(0 until count).map{u->if(kind==Kind.SINGLE)listOf("test.s$u") else listOf("test.g$u.a","test.g$u.b")}
        val snapshots=units.flatMapIndexed{u,names->names.mapIndexed{i,p->
            if(kind==Kind.SINGLE)Snapshot(Identity(p,0,10300+u,listOf("signer"),1,1,1),p,listOf(p),true,true,true,false,0x80301,0x301,0,4,4,false)
            else Snapshot(Identity(p,0,10300+u,listOf("signer"),1,1,1),p,names,i==0,i==0,i==0,false,0x300,0x300,0,4,4,false,sharedUserId="test.shared$u",coarseDeclared=i==0)
        }}.associateBy{it.identity.packageName}.toMutableMap()
        val records=linkedMapOf<String,Entry>()
        /** (unit, wanted bit) per setter call. */
        val writes=mutableListOf<Pair<Int,Int>>()
        val outcomes=linkedMapOf<Int,Boolean>()
        private fun unitOf(pkg:String)=units.indexOfFirst{pkg in it}
        private fun saved(entries:List<Entry>):Boolean{commits++;if(commits==failAt)return false;entries.forEach{records[it.identity.packageName]=it};return true}
        val journal=object:Journal{
            override fun entries()=records.values.toList()
            override fun commit(entry:Entry)=saved(listOf(entry))
            override fun commitGroup(entries:List<Entry>)=saved(entries)
        }
        private fun write(names:List<String>,wanted:Int){
            assertTrue("$kind: a durable PENDING record exists before every setter",names.all{records[it]?.state==TargetState.PENDING_HIDE})
            val u=unitOf(names.first());writes+=u to wanted
            names.forEach{p->snapshots[p]=snapshots.getValue(p).let{s->s.copy(fineFlags=(s.fineFlags and 256.inv()) or wanted)}}
            afterWrite(u)
        }
        val port=object:TargetPort{
            override fun compatibleFirmware()=Firmware("samsung",37,"90000","fw","id","model","abi")
            override fun read(packageName:String)=snapshots.getValue(packageName)
            override fun writeFineBit(packageName:String,originalBit:Int)=write(listOf(packageName),originalBit)
            override fun writeFineGroupChecked(expected:List<Snapshot>,wanted:Int)=write(expected.map{it.identity.packageName},wanted)
        }
        fun run(){
            val wait:(Long)->Unit={clock+=it;onWait()}
            if(kind==Kind.SINGLE)SafetyEngine(port,journal,{clock},{"boot"},wait=wait).userHideWindow(units.flatten(),{active},{clock}){r->outcomes[unitOf(r.packageName)]=r.success}
            else SharedGroupEngine(port,journal,{clock},{"boot"},wait=wait).executeWindow(units,true,{active},{clock}){g,r->outcomes[unitOf(g.first())]=r.getOrNull()?.all{it.state in BatchResult.DONE}==true}
        }
        fun states(u:Int)=units[u].map{records[it]?.state}.toSet()
        fun bit(u:Int)=units[u].map{snapshots.getValue(it).bit}.toSet()
    }
    private fun both(test:(World)->Unit)=Kind.values().forEach{test(World(it))}

    @Test fun everyUnitGetsItsChecksInOneSharedWait()=both{w->
        w.run()
        assertEquals("${w.kind}: one 1.5 s wait for all units",1500L,w.clock)
        assertEquals(mapOf(0 to true,1 to true,2 to true),w.outcomes)
        assertEquals(listOf(0 to 0,1 to 0,2 to 0),w.writes)
        (0..2).forEach{assertEquals(setOf(TargetState.APPLIED),w.states(it))}
    }
    @Test fun androidTurningTheDotBackOnFailsOnlyThatUnitSafely()=both{w->
        var once=true
        w.onWait={if(once){once=false;w.units[1].forEach{p->w.snapshots[p]=w.snapshots.getValue(p).let{s->s.copy(fineFlags=s.fineFlags or 256)}}}}
        w.run()
        assertEquals("${w.kind}",mapOf(1 to false,0 to true,2 to true),w.outcomes)
        assertEquals(setOf(TargetState.FAILED_SAFE),w.states(1));assertEquals(setOf(256),w.bit(1))
        assertEquals(setOf(TargetState.APPLIED),w.states(0));assertEquals(setOf(TargetState.APPLIED),w.states(2))
    }
    @Test fun ownershipChangeDuringTheWaitLocksThatUnitWithoutAnyRollbackSetter()=both{w->
        var once=true
        w.onWait={if(once){once=false;w.units[1].forEach{p->w.snapshots[p]=w.snapshots.getValue(p).let{s->s.copy(peers=s.peers+"test.new")}}}}
        w.run()
        assertEquals("${w.kind}",listOf(0 to 0,1 to 0,2 to 0),w.writes)
        assertEquals(setOf(TargetState.NEEDS_REVIEW),w.states(1));assertEquals(false,w.outcomes[1])
        assertEquals(setOf(TargetState.APPLIED),w.states(0));assertEquals(setOf(TargetState.APPLIED),w.states(2))
    }
    @Test fun leavingTheScreenAfterTheFirstSetterStartsNothingNew()=both{w->
        w.afterWrite={w.active=false}
        w.run()
        assertEquals("${w.kind}",listOf(0 to 0),w.writes)
        assertEquals(setOf(TargetState.APPLIED),w.states(0))
        assertTrue("${w.kind}: no record for units that never started",w.units.drop(1).flatten().none{it in w.records})
    }
    @Test fun aFailedFinalSaveStopsEverySetterAndLeavesWrittenUnitsPending()=both{w->
        w.failAt=4 // three PENDING saves succeed, the first final save fails
        w.run()
        assertEquals("${w.kind}: no rollback setter with broken storage",listOf(0 to 0,1 to 0,2 to 0),w.writes)
        (0..2).forEach{assertEquals(setOf(TargetState.PENDING_HIDE),w.states(it));assertEquals(false,w.outcomes[it])}
    }
    @Test fun anInterruptedWaitStopsRollbackSettersOnceASaveFailed()=both{w->
        w.failAt=4;w.onWait={throw InterruptedException()} // the first rollback is written, its record cannot be saved
        w.run()
        assertEquals("${w.kind}",listOf(0 to 0,1 to 0,2 to 0,0 to 256),w.writes)
        (0..2).forEach{assertEquals(setOf(TargetState.PENDING_HIDE),w.states(it));assertEquals(false,w.outcomes[it])}
    }
}
