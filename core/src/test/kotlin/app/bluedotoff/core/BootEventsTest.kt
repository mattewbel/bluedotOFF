package app.bluedotoff.core

import org.junit.Assert.*
import org.junit.Test

class BootEventsTest {
    private val entry=Entry(Identity("test.location",0,10792,listOf("cert"),1,1,1),"Test",256,true,TargetState.APPLIED,1,"fp","count:54",true)
    @Test fun forceStopRelaunchInSameBootDoesNotRequireReapply(){assertEquals(entry,BootEvents.fromBroadcast(entry,"count:54"))}
    @Test fun realRebootPreservesOriginalBitAndMarksReapply(){val changed=BootEvents.fromBroadcast(entry,"count:55");assertEquals(TargetState.NEEDS_REAPPLY_BOOT,changed.state);assertEquals(256,changed.originalBit);assertEquals(entry.identity,changed.identity)}
    @Test fun uncertainFallbackDoesNotSuppressBootMarker(){assertEquals(TargetState.NEEDS_REAPPLY_BOOT,BootEvents.fromBroadcast(entry,"estimate:123").state);assertEquals(TargetState.NEEDS_REAPPLY_BOOT,BootEvents.fromBroadcast(entry.copy(bootSession="estimate:123"),"estimate:123").state)}
    @Test fun pendingJournalIsNeverPromotedByBootBroadcast(){val e=entry.copy(state=TargetState.PENDING_HIDE);assertEquals(e,BootEvents.fromBroadcast(e,"count:55"))}
    @Test fun reviewAndDisabledEntriesKeepTheirState(){val review=entry.copy(state=TargetState.NEEDS_REVIEW);val disabled=entry.copy(enabled=false,state=TargetState.NOT_APPLIED);assertEquals(review,BootEvents.fromBroadcast(review,"count:55"));assertEquals(disabled,BootEvents.fromBroadcast(disabled,"count:55"))}
}
