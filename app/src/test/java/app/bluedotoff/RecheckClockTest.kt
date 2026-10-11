package app.bluedotoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecheckClockTest {
    private val minute=60_000L
    private val clock=RecheckClock(10*minute).also{it.fullChecked(1_000L)}

    @Test fun nothingChangedWithinTenMinutesReadsNothing(){
        assertNull(clock.plan(1_000L+9*minute,emptySet()))
    }
    @Test fun aChangedAppIsReadAloneWithinTenMinutes(){
        assertEquals(setOf("viva.republica.toss"),clock.plan(1_000L+8*minute,setOf("viva.republica.toss")))
    }
    /** GPT review 2026-10-11: partial checks every 8 minutes used to postpone the full check forever. */
    @Test fun frequentPartialChecksDoNotPostponeTheFullCheck(){
        val toss=setOf("viva.republica.toss")
        assertEquals(toss,clock.plan(1_000L+8*minute,toss))
        // The partial check does not call fullChecked, so 16 minutes after the full check everything is read.
        assertEquals(emptySet<String>(),clock.plan(1_000L+16*minute,toss))
        clock.fullChecked(1_000L+16*minute)
        assertNull(clock.plan(1_000L+17*minute,emptySet()))
    }
}
