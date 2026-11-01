package app.bluedotoff.core

import org.junit.Assert.*
import org.junit.Test

class LocationRetirementTest {
    private val id=Identity("test.map",0,10123,listOf("signer"),100,1,100)
    private val before=Snapshot(id,"Map",listOf(id.packageName),true,true,true,false,0x300,0x300,0,0,0,false)
    private val entry=Entry(id,"Map",256,true,TargetState.APPLIED,200,"fw","boot",true,invariantDigest=before.invariantDigest(),settlingVerified=true)
    private val removed=before.copy(identity=id.copy(versionCode=2,lastUpdateTime=300),fineDeclared=false,coarseDeclared=false,fineGranted=false,coarseGranted=false,fineFlags=0,coarseFlags=0)
    private fun read(e:Entry=entry,s:Snapshot?=removed,fw:String="fw")=Reconcile.read(e,s,fw,"boot"){s}
    @Test fun cachedRemovalAloneCannotRetireAndConfirmationIsOnlyRequestedForCandidates(){
        assertFalse(Reconcile.read(entry,removed,"fw","boot").locationRetired)
        Reconcile.read(entry,before,"fw","boot"){error("unnecessary second read")}
        var reads=0
        val retired=Reconcile.read(entry,removed,"fw","boot"){reads++;removed.copy(lastLocationAccess=900)}
        assertTrue(retired.locationRetired);assertEquals(1,reads)
    }
    @Test fun changedMissingOrFailedConfirmationCannotRetire(){
        for(confirmed in listOf(null,before.copy(identity=removed.identity),removed.copy(identity=removed.identity.copy(versionCode=3)),
            removed.copy(coarseDeclared=true),removed.copy(peersAuthoritative=false),removed.copy(peers=listOf(id.packageName,"test.peer")),removed.copy(fineFlags=256))){
            assertFalse(Reconcile.read(entry,removed,"fw","boot"){confirmed}.locationRetired)
        }
        try{Reconcile.read(entry,removed,"fw","boot"){throw IllegalStateException("connection lost")};fail()}catch(_:IllegalStateException){}
    }
    @Test fun updateWithoutLocationRetiresOnlyManagementAndKeepsOriginalEvidence(){
        val e=read()
        assertTrue(e.locationRetired);assertFalse(e.enabled);assertFalse(e.retryHide)
        assertEquals(entry.identity,e.identity);assertEquals(entry.originalBit,e.originalBit)
        assertEquals(entry.invariantDigest,e.invariantDigest);assertEquals(entry.changedByUs,e.changedByUs)
        assertFalse(WatchPolicy.eligible(e));assertEquals(e,read(e));assertEquals(e,Reconcile.boot(e));assertEquals(e,Reconcile.update(e))
        assertEquals(e,read(e,fw="later-firmware"));assertEquals(e,read(e,s=null))
    }
    @Test fun deniedPermissionCoarseOnlyMissingUnknownPeersOrChangedIdentityAreNotRetired(){
        val cases=listOf(before.copy(identity=removed.identity,fineGranted=false),removed.copy(coarseDeclared=true),removed.copy(peersAuthoritative=false),
            removed.copy(sharedUserId="shared"),removed.copy(peers=listOf(id.packageName,"test.other")),removed.copy(identity=removed.identity.copy(uid=10124)),
            removed.copy(identity=removed.identity.copy(signers=listOf("other"))),removed.copy(identity=id),null)
        cases.forEach{assertFalse(read(s=it).locationRetired)}
        assertFalse(read(fw="new-firmware").locationRetired)
    }
    @Test fun pendingAndUncertainRollbackAndSharedOwnersKeepTheirSafetyBlocks(){
        listOf(TargetState.PENDING_HIDE,TargetState.PENDING_SHOW,TargetState.PENDING_RESTORE,TargetState.FAILED_ROLLBACK,TargetState.SHARED_UID_CHANGED,TargetState.NEEDS_REVIEW).forEach{
            assertFalse(read(entry.copy(state=it)).locationRetired)
        }
        assertFalse(read(entry.copy(sharedUserId="shared")).locationRetired)
        assertFalse(read(entry.copy(invariantDigest="")).locationRetired)
        assertFalse(read(entry.copy(enabled=false)).locationRetired)
    }
    @Test fun laterExplicitRegistrationUsesTheNewStateAndClearsTheArchiveMarker(){
        val fw=Firmware("samsung",37,"90000","fixture","b","m","abi")
        var saved=read().copy(originalBit=0)
        var current=before.copy(identity=id.copy(versionCode=3,lastUpdateTime=400))
        val store=object:Journal {
            override fun entries()=listOf(saved)
            override fun commit(entry:Entry):Boolean{saved=entry;return true}
        }
        val port=object:TargetPort {
            override fun compatibleFirmware()=fw
            override fun read(packageName:String)=current
            override fun writeFineBit(packageName:String,originalBit:Int){current=current.copy(fineFlags=(current.fineFlags and SENSITIVITY_BIT.inv()) or originalBit)}
        }
        val applied=SafetyEngine(port,store,{500},{"boot"},wait={}).userHide(id.packageName)
        assertTrue(applied.enabled);assertFalse(applied.locationRetired);assertEquals(256,applied.originalBit)
        assertEquals(TargetState.APPLIED,applied.state)
    }
}
