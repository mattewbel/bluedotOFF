package app.bluedotoff.core
import org.junit.Assert.*
import org.junit.Test

/** 1.1.4: consecutive shared groups overlap their waits; every group keeps its own record, setter and checks. */
class SharedGroupWindowTest {
    private class Rig(count:Int) {
        var clock=0L;var commits=0;var failAt=0;var active=true
        var onWait:()->Unit={}
        val groups=(0 until count).map{g->listOf("test.g$g.a","test.g$g.b")}
        val snapshots=groups.flatMapIndexed{g,names->names.mapIndexed{i,p->Snapshot(Identity(p,0,10200+g,listOf("signer"),1,1,1),p,names,i==0,i==0,i==0,false,0x300,0x300,0,4,4,false,sharedUserId="test.shared$g",coarseDeclared=i==0)}}.associateBy{it.identity.packageName}.toMutableMap()
        val records=linkedMapOf<String,Entry>();val writes=mutableListOf<Pair<String,Int>>();val reads=mutableListOf<Pair<String,Long>>()
        val journal=object:Journal{
            override fun entries()=records.values.toList()
            override fun commit(entry:Entry)=error("Must commit the whole group")
            override fun commitGroup(entries:List<Entry>):Boolean{commits++;if(commits==failAt)return false;entries.forEach{records[it.identity.packageName]=it};return true}
        }
        val port=object:TargetPort{
            override fun compatibleFirmware()=Firmware("samsung",37,"90000","fw","id","model","abi")
            override fun read(packageName:String):Snapshot{reads+=packageName to clock;return snapshots.getValue(packageName)}
            override fun writeFineBit(packageName:String,originalBit:Int)=error("Individual writes must stay blocked")
            override fun writeFineGroupChecked(expected:List<Snapshot>,wanted:Int){
                val names=expected.map{it.identity.packageName}
                assertTrue("PENDING before the setter",names.all{records[it]?.state==TargetState.PENDING_HIDE||records[it]?.state==TargetState.PENDING_RESTORE})
                writes+=names.first() to wanted;names.forEach{snapshots[it]=snapshots.getValue(it).let{s->s.copy(fineFlags=(s.fineFlags and 256.inv()) or wanted)}}
            }
        }
        val results=mutableListOf<Pair<List<String>,Result<List<Entry>>>>()
        fun run(hide:Boolean=true){SharedGroupEngine(port,journal,{clock},{"boot"},wait={clock+=it;onWait()}).executeWindow(groups,hide,{active},{clock}){g,r->results+=g to r}}
    }
    @Test fun threeGroupsShareOneWaitAndEachGetsAllThreeChecks(){
        val r=Rig(3);r.run()
        assertEquals("waits overlap: one 1.5 s for all three (sequential was 4.5 s)",1500L,r.clock)
        assertEquals(r.groups,r.results.map{it.first})
        assertTrue(r.results.all{it.second.getOrThrow().all{e->e.state==TargetState.APPLIED&&e.settlingVerified}})
        assertEquals(3,r.writes.size)
        // Per member: before, preflight, immediate, 500 ms, 1500 ms.
        r.groups.flatten().forEach{p->assertEquals(listOf(0L,0L,0L,500L,1500L),r.reads.filter{it.first==p}.map{it.second})}
        r.results.clear();r.writes.clear();r.run(false)
        assertTrue(r.results.all{it.second.getOrThrow().all{e->!e.enabled&&e.state==TargetState.NOT_APPLIED}});assertTrue(r.snapshots.values.all{it.bit==256})
    }
    @Test fun aFailedGroupStopsNewStartsButStartedGroupsFinish(){
        val r=Rig(3)
        // Group 1 is ineligible at its first read: groups 0 still finishes, group 2 never starts.
        r.snapshots.replaceAll{p,s->if(p.startsWith("test.g1."))s.copy(persistent=true) else s}
        r.run()
        val byGroup=r.results.toMap()
        assertEquals(setOf(r.groups[0],r.groups[1]),byGroup.keys);assertEquals(2,r.results.size)
        assertTrue(byGroup.getValue(r.groups[0]).getOrThrow().all{it.state==TargetState.APPLIED})
        assertTrue(byGroup.getValue(r.groups[1]).isFailure)
        assertEquals(listOf("test.g0.a" to 0),r.writes);assertTrue(r.records.keys.none{it.startsWith("test.g2.")})
    }
    /** Isolation only: a non-0x100 drift makes the rollback impossible, so that group locks and the other still finishes. */
    @Test fun driftInOneGroupDuringTheSharedWaitLocksOnlyThatGroup(){
        val r=Rig(2);var once=true
        r.onWait={if(once){once=false;r.snapshots.replaceAll{p,s->if(p.startsWith("test.g1."))s.copy(fineFlags=s.fineFlags or 16) else s}}}
        r.run()
        val byGroup=r.results.toMap()
        assertTrue(byGroup.getValue(r.groups[0]).getOrThrow().all{it.state==TargetState.APPLIED})
        assertTrue(byGroup.getValue(r.groups[1]).getOrThrow().all{it.state==TargetState.FAILED_ROLLBACK})
    }
    @Test fun leavingTheScreenStopsNewGroupsBeforeTheirRecord(){
        val r=Rig(3);r.onWait={r.active=false}
        // The first wait comes after every group started (12 may be in flight), so all three finish their checks.
        r.run();assertEquals(3,r.results.size)
        val s=Rig(3);var starts=0
        SharedGroupEngine(s.port,s.journal,{s.clock},{"boot"},wait={s.clock+=it}).executeWindow(s.groups,true,{starts++<1},{s.clock}){g,res->s.results+=g to res}
        assertEquals(1,s.results.size);assertTrue(s.records.keys.none{it.startsWith("test.g1.")||it.startsWith("test.g2.")})
    }
    /** Review 2026-10-18 (Codex): the wait is interrupted, the first group's rollback is written but its record cannot be
     *  saved; the second group must get no rollback setter and stay PENDING for the recovery on app open. */
    @Test fun interruptedWaitStopsRollbackSettersAfterAFailedSave(){
        val r=Rig(2);r.failAt=3;r.onWait={throw InterruptedException()}
        r.run()
        assertEquals(listOf("test.g0.a" to 0,"test.g1.a" to 0,"test.g0.a" to 256),r.writes)
        assertTrue(r.results.all{it.second.exceptionOrNull() is JournalPersistenceException});assertEquals(2,r.results.size)
        assertTrue(r.records.values.all{it.state==TargetState.PENDING_HIDE})
    }
    @Test fun brokenStorageLeavesWrittenGroupsPendingWithoutRollback(){
        val r=Rig(2);r.failAt=3 // group 0 PENDING, group 1 PENDING, group 0 final commit fails
        r.run()
        assertTrue(r.results.all{it.second.exceptionOrNull() is JournalPersistenceException})
        assertEquals(2,r.writes.size);assertTrue(r.records.values.all{it.state==TargetState.PENDING_HIDE})
    }
}
