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
    @Test fun appsNeedingReviewAreNotOffered(){
        val m=UiModel(discovered=listOf(off),entries=listOf(Entry(id,"기존 위치 앱",0,false,TargetState.NEEDS_REVIEW,1,"demo","demo",false)))
        assertTrue(m.leftOffApps.isEmpty())
    }
}
