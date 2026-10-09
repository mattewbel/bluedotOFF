package app.bluedotoff.core
import org.junit.Assert.*
import org.junit.Test

class EligibilityTest {
    private val pkg="net.daum.android.map"
    private val baseline=Snapshot(Identity(pkg,0,10502,listOf("cert"),1,1,1),"카카오맵",listOf(pkg),true,true,true,false,0x300,0x300,0,4,4,false)
    @Test fun normalPeerOneUsesNormalPath(){assertEquals(UidClass.NORMAL,baseline.ownership.uidClass);assertEquals(EligibilityResult.Allowed,baseline.eligibility)}
    @Test fun declaredSharedUserIsSeparateSingletonState(){val s=baseline.copy(sharedUserId="daum.uid.shared");assertEquals(UidClass.SINGLETON_SHARED_USER,s.ownership.uidClass);assertFalse(s.safe());assertEquals(BlockReason.SINGLETON_UNVERIFIED,(s.eligibility as EligibilityResult.Blocked).reason)}
    @Test fun provedKakaoLikeSingletonUsesExperimentalPath(){val s=baseline.copy(sharedUserId="daum.uid.shared",singletonVerified=true);assertTrue(s.safe());assertEquals(EligibilityResult.SingletonSharedUserExperimental,s.eligibility)}
    @Test fun proofNeverAllowsTwoPeers(){val s=baseline.copy(sharedUserId="daum.uid.shared",singletonVerified=true,peers=listOf(pkg,"another.app"));assertEquals(UidClass.MULTI_PACKAGE_SHARED_UID,s.ownership.uidClass);assertFalse(s.safe())}
    @Test fun missingOrMalformedOwnershipIsNeverSingle(){for(peers in listOf(emptyList(),listOf("wrong.app"),listOf(pkg,pkg))){assertFalse(baseline.copy(peers=peers).safe())};assertFalse(baseline.copy(peersAuthoritative=false).safe())}
    @Test fun coreAndPersistentHaveDistinctReasons(){assertEquals(BlockReason.CORE_UID,(baseline.copy(identity=baseline.identity.copy(uid=1000)).eligibility as EligibilityResult.Blocked).reason);assertEquals(BlockReason.PERSISTENT_CORE,(baseline.copy(persistent=true).eligibility as EligibilityResult.Blocked).reason)}
    @Test fun specialAndOtherUserRemainBlocked(){assertFalse(baseline.copy(identity=baseline.identity.copy(uid=99001)).safe());assertFalse(baseline.copy(identity=baseline.identity.copy(uid=110502,userId=1)).safe())}
    @Test fun missingSignerRemainsBlocked(){assertEquals(BlockReason.IDENTITY_UNKNOWN,(baseline.copy(identity=baseline.identity.copy(signers=emptyList())).eligibility as EligibilityResult.Blocked).reason)}
    @Test fun deniedOrUnsupportedMetadataCannotBorrowSingletonProof(){val s=baseline.copy(sharedUserId="daum.uid.shared",singletonVerified=true);assertFalse(s.copy(fineGranted=false).safe());assertFalse(s.copy(fineDeclared=false).safe());assertFalse(s.copy(fineMode=1).safe());assertFalse(s.copy(coarseDeclared=false).safe())}
    @Test fun sharedNamespaceBelongsToTransactionInvariant(){val a=baseline.copy(sharedUserId="daum.uid.shared",singletonVerified=true);val b=a.copy(sharedUserId="other.shared");assertFalse(a.invariantEquals(b,a.bit));assertNotEquals(a.invariantDigest(),b.invariantDigest())}
}
