package app.bluedotoff.core

import org.junit.Test
import org.junit.Assert.*

class LocalWriteGuardTest {
    private val id=Identity("test.location.app",0,10792,listOf("cert"),100,1,110)
    private val safe=Snapshot(id,"Test",listOf(id.packageName),true,true,true,false,0x300,0x300,0,4,4,false)
    private fun validate(fresh:Snapshot=safe,wanted:Int=0)=LocalWriteGuard.validate(fresh,LocalWriteGuard.identityDigest(safe.identity),safe.invariantDigest(),safe.fineFlags,wanted)
    private fun blocked(block:()->Unit){try{block();fail("Must reject stale or unsafe helper request")}catch(_:Exception){}}
    @Test fun freshIndependentTargetCanUseEitherNarrowValue(){validate();validate(wanted=256)}
    @Test fun extraMaskAndValuesCannotExpandCapability(){listOf(-1,1,0x200,0x300,Int.MAX_VALUE).forEach{v->blocked{validate(wanted=v)}}}
    @Test fun replacementAndUpdateAreDifferentHelperIdentities(){listOf(id.copy(uid=10793),id.copy(signers=listOf("other")),id.copy(firstInstallTime=101),id.copy(versionCode=2),id.copy(lastUpdateTime=111)).forEach{changed->blocked{validate(safe.copy(identity=changed))}}}
    @Test fun differentBitCannotUseStalePreflight(){blocked{validate(safe.copy(fineFlags=0x200))}}
    @Test fun permissionAppOpsAndNonSensitivityFlagsMustStayIdentical(){listOf(safe.copy(fineGranted=false),safe.copy(coarseGranted=false),safe.copy(fineMode=1),safe.copy(coarseMode=1),safe.copy(coarseFlags=0x200),safe.copy(fineFlags=0x310),safe.copy(backgroundGranted=true)).forEach{changed->blocked{validate(changed)}}}
    @Test fun secondUidPeerCoreAndPersistentAreNeverAccepted(){listOf(safe.copy(peers=listOf(id.packageName,"second.app")),safe.copy(identity=id.copy(uid=1000)),safe.copy(persistent=true),safe.copy(criticalPackage=true),safe.copy(peersAuthoritative=false)).forEach{changed->blocked{validate(changed)}}}
    @Test fun singletonNeedsTheExistingFirmwareScopedProof(){val singleton=safe.copy(sharedUserId="test.shared",singletonVerified=true);LocalWriteGuard.validate(singleton,LocalWriteGuard.identityDigest(id),singleton.invariantDigest(),singleton.fineFlags,0);blocked{LocalWriteGuard.validate(singleton.copy(singletonVerified=false),LocalWriteGuard.identityDigest(id),singleton.invariantDigest(),singleton.fineFlags,0)}}
    @Test fun corruptOrTruncatedExpectedHashesAreRejected(){blocked{LocalWriteGuard.validate(safe,"a",safe.invariantDigest(),0x300,0)};blocked{LocalWriteGuard.validate(safe,LocalWriteGuard.identityDigest(id),"f".repeat(64),0x300,0)}}
}
