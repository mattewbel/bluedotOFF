package app.bluedotoff

import app.bluedotoff.core.*
import org.junit.Assert.*
import org.junit.Test

/** 1.1.4: the picker's per-package lookups are built once per UiModel. A model that already used them and is then
 *  changed with copy() must answer from the new data (Codex review, 2026-10-18). */
class UiModelLookupTest {
    private val app=DiscoveredApp("demo.map","지도",10050,launcher=true)
    private val id=Identity("demo.map",0,10050,listOf("demo"),1,1,1)
    private val on=Snapshot(id,"지도",listOf("demo.map"),true,true,true,false,0x80301,0x301,0,4,4,false)
    private fun entry(state:TargetState,enabled:Boolean=true)=Entry(id,"지도",256,enabled,state,1,"fw","boot",true,settlingVerified=true)
    @Test fun recordsChangedAfterTheLookupsWereUsedAreSeen(){
        val m=UiModel(discovered=listOf(app),candidates=listOf(on))
        assertTrue(m.canSelect(app));assertFalse(m.isApplied(app.packageName));assertTrue(m.managedPackages.isEmpty())
        val applied=m.copy(entries=listOf(entry(TargetState.APPLIED)))
        assertTrue(applied.isApplied(app.packageName));assertFalse(applied.canSelect(app));assertEquals(setOf("demo.map"),applied.managedPackages)
        val locked=applied.copy(entries=listOf(entry(TargetState.NEEDS_REVIEW)))
        assertFalse(locked.canAct(app))
        val restored=locked.copy(entries=listOf(entry(TargetState.NOT_APPLIED,enabled=false)))
        assertTrue(restored.canSelect(app));assertTrue(restored.managedPackages.isEmpty())
    }
    @Test fun aFresherReadAfterTheLookupsWereUsedIsSeen(){
        val m=UiModel(discovered=listOf(app),candidates=listOf(on))
        assertFalse(m.leftOff(app))
        assertTrue(m.copy(candidates=listOf(on.copy(fineFlags=0x80201))).leftOff(app))
    }
    @Test fun aGroupChosenAfterTheLookupsWereUsedIsSeen(){
        val peers=listOf("demo.a","demo.b")
        val a=DiscoveredApp("demo.a","A",10060,launcher=true,uidPeers=peers);val b=a.copy(packageName="demo.b",label="B")
        val m=UiModel(discovered=listOf(a,b),applicationScope=ApplicationScope.ALL)
        assertFalse(m.groupChosen("demo.a"));assertEquals(listOf("demo.a"),m.selectionGroup("demo.a"))
        val chosen=m.copy(approvedGroups=listOf(peers))
        assertTrue(chosen.groupChosen("demo.a"));assertEquals(peers,chosen.selectionGroup("demo.b"));assertFalse(chosen.canPickGroup(a))
    }
}
