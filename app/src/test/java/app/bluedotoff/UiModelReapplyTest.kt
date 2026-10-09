package app.bluedotoff

import app.bluedotoff.core.*
import org.junit.Assert.*
import org.junit.Test

class UiModelReapplyTest {
    private fun entry(pkg:String,state:TargetState,enabled:Boolean=true)=Entry(Identity(pkg,0,10001,listOf("demo"),1,1,1),pkg,256,enabled,state,1,"demo","demo",true,settlingVerified=state==TargetState.APPLIED)
    @Test fun neededStatesMoveFirstStablyWithoutChangingOriginalRecords(){
        val entries=listOf(entry("applied",TargetState.APPLIED),entry("policy",TargetState.NEEDS_REAPPLY_POLICY),entry("unknown",TargetState.SHIZUKU_REQUIRED),entry("boot",TargetState.NEEDS_REAPPLY_BOOT),entry("update",TargetState.NEEDS_REAPPLY_UPDATE),entry("inactive",TargetState.NEEDS_REAPPLY_POLICY,false),entry("blocked",TargetState.FAILED_ROLLBACK))
        val model=UiModel(entries=entries)
        assertEquals(listOf("policy","boot","update"),model.reapplyEntries.map{it.identity.packageName})
        assertEquals(listOf("policy","boot","update","applied","unknown","blocked"),model.registeredDisplayEntries.map{it.identity.packageName})
        assertEquals(entries,model.entries);assertEquals(3,model.reapplyCount)
    }
    @Test fun focusedTargetsIncludeSharedUnitOnlyOnceAndExcludeOtherRegistrations(){
        val a=entry("a",TargetState.NEEDS_REAPPLY_POLICY);val b=entry("b",TargetState.NEEDS_REAPPLY_UPDATE)
        val group=listOf(a.identity,b.identity)
        val model=UiModel(entries=listOf(entry("unrelated",TargetState.APPLIED),a.copy(groupIdentities=group),b.copy(groupIdentities=group)))
        assertEquals(listOf("a","b"),model.reapplyTargetPackages)
        assertEquals(2,model.reapplyCount)
    }
    @Test fun uninstalledAppNeedsNoAttentionAndDoesNotBlockApplied(){
        val model=UiModel(entries=listOf(entry("applied",TargetState.APPLIED),entry("gone",TargetState.MISSING_TARGET)))
        assertTrue(model.allLastVerifiedApplied)
        assertEquals(1,model.lastVerifiedAppliedCount);assertEquals(0,model.otherCheckCount)
        assertEquals(listOf("applied"),model.visibleEntries.map{it.identity.packageName});assertEquals(2,model.entries.size)
    }
    @Test fun onlyUninstalledAppsIsNotApplied(){
        val model=UiModel(entries=listOf(entry("gone",TargetState.MISSING_TARGET)))
        assertFalse(model.allLastVerifiedApplied);assertEquals(0,model.otherCheckCount);assertTrue(model.visibleEntries.isEmpty())
    }
    @Test fun multiRestoreSkipsAppsThatCannotBeRestoredNow(){
        val entries=listOf(entry("ok",TargetState.APPLIED),entry("gone",TargetState.MISSING_TARGET),entry("reinstalled",TargetState.INACTIVE_INSTALL,false),
            entry("denied",TargetState.UNSUPPORTED_STATE),entry("review",TargetState.FAILED_ROLLBACK),entry("restored",TargetState.NOT_APPLIED,false),entry("boot",TargetState.NEEDS_REAPPLY_BOOT))
        val (packages,needsCheck)=UiModel(entries=entries).restorePlan(entries.map{it.identity.packageName})
        assertEquals(listOf("ok","boot"),packages);assertEquals(3,needsCheck)
    }
    @Test fun singleRestoreKeepsTheExactReasonExceptForUninstalledApps(){
        val model=UiModel(entries=listOf(entry("denied",TargetState.UNSUPPORTED_STATE),entry("gone",TargetState.MISSING_TARGET)))
        assertEquals(listOf("denied") to 0,model.restorePlan(listOf("denied")))
        assertEquals(emptyList<String>() to 0,model.restorePlan(listOf("gone")))
    }
    @Test fun blockedSharedMemberSkipsTheWholeGroup(){
        val a=entry("a",TargetState.APPLIED);val b=entry("b",TargetState.NEEDS_REVIEW);val group=listOf(a.identity,b.identity)
        val model=UiModel(entries=listOf(a.copy(groupIdentities=group),b.copy(groupIdentities=group),entry("solo",TargetState.APPLIED)))
        assertEquals(listOf("solo") to 1,model.restorePlan(listOf("a","b","solo")))
    }
    @Test fun appliedAppIsNotSelectableButReapplyNeededIs(){
        val model=UiModel(entries=listOf(entry("applied",TargetState.APPLIED),entry("policy",TargetState.NEEDS_REAPPLY_POLICY)))
        assertTrue(model.isApplied("applied"));assertFalse(model.isApplied("policy"));assertFalse(model.isApplied("other"))
        val app=DiscoveredApp("applied","applied",10001,false,true,check=CandidateCheck.AVAILABLE)
        assertEquals(model.canAct(app.copy(packageName="other")),model.canAct(app));assertFalse(model.canSelect(app))
    }
    @Test fun sharedAppInWholeScopeIsPickableUntilChosenOrRejected(){
        val app=DiscoveredApp("a","a",10001,false,true,check=CandidateCheck.AVAILABLE,uidPeers=listOf("a","b"))
        val whole=UiModel(applicationScope=ApplicationScope.ALL)
        assertTrue(whole.canPickGroup(app))
        assertFalse(UiModel(applicationScope=ApplicationScope.NORMAL).canPickGroup(app))
        assertFalse(whole.copy(approvedGroups=listOf(listOf("a","b"))).canPickGroup(app))
        assertFalse(whole.copy(rejectedGroups=setOf("a")).canPickGroup(app))
        assertEquals(listOf("a","b"),whole.copy(approvedGroups=listOf(listOf("a","b"))).selectionGroup("a"))
    }
}
