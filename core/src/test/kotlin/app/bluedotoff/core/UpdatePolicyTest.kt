package app.bluedotoff.core

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    @Test fun versionsCompareAsNumbers(){
        assertTrue(UpdatePolicy.newer("1.1.10","1.1.9"))
        assertTrue(UpdatePolicy.newer("1.2","1.1.9"))
        assertTrue(UpdatePolicy.newer("v1.1.3","1.1.2"))
        assertFalse(UpdatePolicy.newer("1.1.2","1.1.2"))
        assertFalse(UpdatePolicy.newer("1.1.2.0","1.1.2"))
        assertFalse(UpdatePolicy.newer("1.1.1","1.1.2"))
    }
    @Test fun preReleasesAndOddTagsAreNeverAnnounced(){
        assertNull(UpdatePolicy.parse("1.2.0-beta"))
        assertNull(UpdatePolicy.parse("latest"))
        assertNull(UpdatePolicy.parse(""))
        assertFalse(UpdatePolicy.newer("2.0.0-rc1","1.1.2"))
        assertFalse(UpdatePolicy.newer("9999999.0","1.1.2"))
    }
    @Test fun atMostOnceADay(){
        val day=UpdatePolicy.INTERVAL_MS
        assertTrue(UpdatePolicy.due(1000,0))
        assertFalse(UpdatePolicy.due(10*day-1,9*day))
        assertTrue(UpdatePolicy.due(10*day,9*day))
        assertTrue("clock moved back",UpdatePolicy.due(5*day,9*day))
    }
    @Test fun eachVersionIsAnnouncedOnceAndSkipIsKept(){
        assertTrue(UpdatePolicy.announce("1.1.3","1.1.2",null,null))
        assertFalse(UpdatePolicy.announce("1.1.3","1.1.2","1.1.3",null))
        assertFalse(UpdatePolicy.announce("1.1.3","1.1.2",null,"1.1.3"))
        assertTrue("a later version is new again",UpdatePolicy.announce("1.1.4","1.1.2","1.1.3","1.1.3"))
        assertFalse(UpdatePolicy.announce(null,"1.1.2",null,null))
        assertFalse(UpdatePolicy.announce("1.1.2","1.1.2",null,null))
    }
}
