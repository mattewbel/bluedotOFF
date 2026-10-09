package app.bluedotoff

import app.bluedotoff.core.*
import org.junit.Assert.*
import org.junit.Test

class LocalEntryEventsTest {
    private val id=Identity("test.service",0,10500,listOf("cert"),1,2,3)
    private val snapshot=Snapshot(id,"Service",listOf(id.packageName),true,true,true,false,0x200,0x300,0,4,4,false)
    private val entry=Entry(id,"Service",256,true,TargetState.APPLIED,10,"fp|abi","boot",true,invariantDigest=snapshot.invariantDigest(),settlingVerified=true)

    @Test fun invisibleLocalPackagePreservesEveryExistingStateAndOriginal(){
        TargetState.entries.forEach{state->
            val e=entry.copy(state=state)
            assertEquals(e,LocalEntryEvents.read(e,"fp","boot"){null})
        }
    }
    @Test fun previousUnknownNeedsAuthoritativeReadThenSurvivesLocalVisibilityMiss(){
        val unknown=entry.copy(state=TargetState.SHIZUKU_REQUIRED)
        assertEquals(unknown,LocalEntryEvents.read(unknown,"fp","boot"){null})
        val verified=Reconcile.read(unknown,snapshot,"fp|abi","boot")
        assertEquals(TargetState.APPLIED,verified.state)
        repeat(3){assertEquals(verified,LocalEntryEvents.read(verified,"fp","boot"){null})}
        assertEquals(256,verified.originalBit)
    }
    @Test fun authoritativeOnStillRequiresReapply(){
        val changed=Reconcile.read(entry,snapshot.copy(fineFlags=0x300),"fp|abi","boot")
        assertEquals(TargetState.NEEDS_REAPPLY_POLICY,changed.state)
        assertEquals(changed,LocalEntryEvents.read(changed,"fp","boot"){null})
    }
    @Test fun invisibleCoreSharedCompanionUsesCompleteBackendGroup(){
        val ids=listOf("test.core","test.companion").sorted().map{id.copy(packageName=it,uid=1000)}
        val members=ids.mapIndexed{i,identity->snapshot.copy(identity=identity,peers=ids.map{it.packageName},fineDeclared=i==0,fineGranted=i==0,coarseDeclared=i==0,coarseGranted=i==0,fineMode=if(i==0)4 else 1,coarseMode=if(i==0)4 else 1,systemCritical=true,persistent=true,sharedUserId="shared",applicationScope=ApplicationScope.ALL)}
        val entries=members.map{s->entry.copy(identity=s.identity,state=TargetState.SHIZUKU_REQUIRED,sharedUserId="shared",groupIdentities=ids,invariantDigest=s.invariantDigest())}
        val all=members.associateBy{it.identity.packageName}
        entries.forEach{old->
            val checked=Reconcile.read(old,all[old.identity.packageName],"fp|abi","boot",all)
            assertEquals(TargetState.APPLIED,checked.state)
            assertEquals(checked,LocalEntryEvents.read(checked,"fp","boot"){null})
            assertEquals(ids,checked.groupIdentities)
        }
    }
    @Test fun onlyAuthoritativeMissingCanMarkMissing(){
        assertEquals(entry,LocalEntryEvents.read(entry,"fp","boot"){null})
        assertEquals(TargetState.MISSING_TARGET,Reconcile.read(entry,null,"fp|abi","boot").state)
    }
    @Test fun bootAndFirmwareMarkersDoNotDependOnLocalVisibility(){
        assertEquals(TargetState.NEEDS_REAPPLY_BOOT,LocalEntryEvents.read(entry,"fp","new-boot"){error("must not query")}.state)
        assertEquals(TargetState.NEEDS_RECHECK_FIRMWARE,LocalEntryEvents.read(entry,"new-fp","boot"){error("must not query")}.state)
    }
    @Test fun realReplacementAndUpdateRetainExistingProtection(){
        listOf(id.copy(uid=10501),id.copy(signers=listOf("new")),id.copy(firstInstallTime=4)).forEach{newId->
            val changed=LocalEntryEvents.read(entry,"fp","boot"){newId}
            assertFalse(changed.enabled);assertEquals(TargetState.INACTIVE_INSTALL,changed.state);assertEquals(256,changed.originalBit)
        }
        assertEquals(TargetState.NEEDS_REAPPLY_UPDATE,LocalEntryEvents.read(entry,"fp","boot"){id.copy(versionCode=3)}.state)
        assertEquals(TargetState.NEEDS_REAPPLY_UPDATE,LocalEntryEvents.read(entry,"fp","boot"){id.copy(lastUpdateTime=4)}.state)
    }
    @Test fun pendingAndReviewStatesCannotBeClearedByLocalLookup(){
        listOf(TargetState.FAILED_ROLLBACK,TargetState.SHARED_UID_CHANGED,TargetState.NEEDS_REVIEW,TargetState.PENDING_HIDE,TargetState.PENDING_RESTORE).forEach{state->
            val e=entry.copy(state=state)
            assertEquals(e,LocalEntryEvents.read(e,"new-fp","new-boot"){error("must not query")})
        }
    }
    @Test fun homeCountsSeparateReapplyFromUnverifiedAndFailedStates(){
        val states=listOf(TargetState.APPLIED,TargetState.NEEDS_REAPPLY_POLICY,TargetState.NEEDS_REAPPLY_UPDATE,TargetState.SHIZUKU_REQUIRED,TargetState.FAILED_ROLLBACK)
        val entries=states.mapIndexed{i,s->entry.copy(identity=id.copy(packageName="test.app$i"),state=s)}+entry.copy(identity=id.copy(packageName="test.unsettled"),settlingVerified=false)
        val m=UiModel(entries=entries)
        assertEquals(1,m.lastVerifiedAppliedCount);assertEquals(2,m.reapplyCount);assertEquals(3,m.otherCheckCount);assertFalse(m.allLastVerifiedApplied)
        assertEquals(6,m.visibleEntries.count{it.enabled})
    }
}
