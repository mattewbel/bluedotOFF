package app.bluedotoff

import app.bluedotoff.core.*
import org.junit.Assert.*
import org.junit.Test

/** Which apps "표시 켜기" lists: location indicator OFF with no bluedotOFF record that could restore it. */
class UiModelShowTest {
    private val off=DiscoveredApp("demo.old","기존 위치 앱",10001,launcher=true,indicator=IndicatorAssessment(IndicatorEligibility.EXEMPT_SYSTEM_USAGE,true,0x100),hasLocationDeclaration=true)
    private val id=Identity("demo.old",0,10001,listOf("demo"),1,1,1)
    private val snapshot=Snapshot(id,"기존 위치 앱",listOf("demo.old"),true,true,true,false,0x80301,0x301,0,4,4,false)
    @Test fun anOffAppWithoutARecordIsLeftOffAndCanBeShownButNotPicked(){
        val m=UiModel(discovered=listOf(off))
        assertEquals(listOf("demo.old"),m.leftOffApps.map{it.packageName});assertTrue(m.canShow(off));assertFalse(m.canSelect(off))
    }
    @Test fun aRegisteredAppIsRestoredNotShown(){
        val m=UiModel(discovered=listOf(off),entries=listOf(Entry(id,"기존 위치 앱",0,true,TargetState.APPLIED,1,"demo","demo",false,settlingVerified=true)))
        assertTrue(m.leftOffApps.isEmpty())
    }
    @Test fun aFresherReadShowingOnRemovesItAtOnce(){
        assertTrue(UiModel(discovered=listOf(off),candidates=listOf(snapshot)).leftOffApps.isEmpty())
        assertEquals(1,UiModel(discovered=listOf(off),candidates=listOf(snapshot.copy(fineFlags=0x80201))).leftOffApps.size)
    }
    @Test fun sharedAppsAreListedButNotShownHere(){
        val shared=off.copy(uidPeers=listOf("demo.old","demo.other"))
        val m=UiModel(discovered=listOf(shared))
        assertEquals(1,m.leftOffApps.size);assertFalse(m.canShow(shared))
    }
    /** KakaoMap's kind: its own UID only, a shared user ID, verified as a singleton. Hiding allows it, so must turning back on. */
    @Test fun aVerifiedSingleAppWithASharedUserIdCanBeTurnedBackOn(){
        val kakao=off.copy(sharedUserId="daum.uid.shared",uidClass=UidClass.SINGLETON_SHARED_USER,uidPeers=listOf("demo.old"))
        val read=snapshot.copy(fineFlags=0x80201,sharedUserId="daum.uid.shared",singletonVerified=true)
        val m=UiModel(discovered=listOf(kakao),candidates=listOf(read))
        assertTrue(m.leftOff(kakao));assertTrue(m.canShow(kakao));assertFalse(m.sharesUid(kakao))
    }
    /** Restored to a recorded original OFF while the inventory still says ON: the latest read decides (GPT review). */
    @Test fun theLatestOffReadWinsOverAnOlderOnInventory(){
        val listedOn=off.copy(indicator=IndicatorAssessment(IndicatorEligibility.STRONG_CANDIDATE,false,0x100))
        val restored=Entry(id,"기존 위치 앱",0,false,TargetState.NOT_APPLIED,1,"demo","demo",false,settlingVerified=true)
        val m=UiModel(discovered=listOf(listedOn),entries=listOf(restored),candidates=listOf(snapshot.copy(fineFlags=0x80201)))
        assertTrue(m.leftOff(listedOn));assertTrue(m.canShow(listedOn));assertFalse(m.canSelect(listedOn))
        // And the other way round: an inventory that says OFF does not hide a latest ON read.
        assertFalse(UiModel(discovered=listOf(off),candidates=listOf(snapshot)).leftOff(off))
    }
    @Test fun homeCardFollowsTheFirstUseAnswer(){
        assertTrue(UiModel(gate=Gate.READY,discovered=listOf(off)).askUsedBefore);assertFalse(UiModel(gate=Gate.READY,discovered=listOf(off)).showLeftOffCard)
        assertTrue(UiModel(gate=Gate.READY,discovered=listOf(off),usedBefore=true).showLeftOffCard)
        assertFalse(UiModel(gate=Gate.READY,discovered=listOf(off),usedBefore=false).showLeftOffCard);assertFalse(UiModel(gate=Gate.READY,discovered=listOf(off),usedBefore=false).askUsedBefore)
        // Update users with records are not asked.
        val old=Entry(Identity("demo.other",0,10009,listOf("demo"),1,1,1),"다른 앱",256,false,TargetState.NOT_APPLIED,1,"demo","demo",false)
        assertFalse(UiModel(gate=Gate.READY,discovered=listOf(off),entries=listOf(old)).askUsedBefore);assertTrue(UiModel(gate=Gate.READY,discovered=listOf(off),entries=listOf(old)).showLeftOffCard)
        // Closed once: stays closed (Settings still has the way in).
        assertFalse(UiModel(gate=Gate.READY,discovered=listOf(off),usedBefore=true,leftOffCardClosed=true).showLeftOffCard)
        // Nothing to point to: no question either.
        assertFalse(UiModel(gate=Gate.READY).askUsedBefore)
    }
    @Test fun appsNeedingReviewAreNotOffered(){
        val m=UiModel(discovered=listOf(off),entries=listOf(Entry(id,"기존 위치 앱",0,false,TargetState.NEEDS_REVIEW,1,"demo","demo",false)))
        assertTrue(m.leftOffApps.isEmpty())
    }
}
