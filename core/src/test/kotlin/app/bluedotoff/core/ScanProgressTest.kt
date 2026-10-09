package app.bluedotoff.core

import org.junit.Assert.*
import org.junit.Test

class ScanProgressTest {
    @Test fun appChecksNeverClaimFinalSuccessBeforeJournalAndConnectionFinish(){
        assertEquals(0,ScanProgress(0,778).percent)
        assertEquals(50,ScanProgress(389,778).percent)
        assertEquals(99,ScanProgress(778,778).percent)
        assertTrue(ScanProgress(778,778).finishing)
        assertFalse(ScanProgress(777,778).finishing)
        assertTrue(runCatching{ScanProgress(1,0)}.isFailure)
        assertTrue(runCatching{ScanProgress(779,778)}.isFailure)
    }
    @Test fun displayOptionsKeepStableWireValuesAndMergeOnlyStoredPreferences(){
        assertEquals(listOf(ApplicationScope.NORMAL,ApplicationScope.ALL),ApplicationScope.choices)
        assertEquals(ApplicationScope.GROUPS,ApplicationScope.fromLevel(2))
        assertEquals(ApplicationScope.ALL,ApplicationScope.fromStoredLevel(2))
        assertEquals(ApplicationScope.ALL,ApplicationScope.fromStoredLevel(3))
        assertEquals(3,ApplicationScope.ALL.level)
    }
}
