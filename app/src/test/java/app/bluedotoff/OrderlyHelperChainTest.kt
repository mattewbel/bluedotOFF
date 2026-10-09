package app.bluedotoff

import app.bluedotoff.adb.OrderlyHelperChain
import org.junit.Assert.*
import org.junit.Test

class OrderlyHelperChainTest {
    @Test fun seventyFourTargetsUseSevenClosedStreamsWithoutClosingTransportBetweenTargets() {
        val chain=OrderlyHelperChain()
        val receipts=mutableSetOf<Any>()
        val verified=mutableListOf<Int>()
        (0 until 74).chunked(12).forEach { targets ->
            assertEquals(targets.size,chain.run { receipt ->
                assertTrue(receipts.add(receipt))
                verified.addAll(targets)
                receipt.helperExited=true;receipt.streamClosed=true
                assertFalse(receipt.socketClosed)
                targets.size
            })
        }
        assertEquals((0 until 74).toList(),verified)
        assertEquals(7,receipts.size)
    }
    @Test fun missingHelperExitOrStreamCloseStopsBeforeAnyNextTarget() {
        for(helper in listOf(false,true)) {
            val chain=OrderlyHelperChain();var touched=0
            assertTrue(runCatching { chain.run { it.helperExited=helper;it.streamClosed=!helper;it.socketClosed=true } }.isFailure)
            assertTrue(runCatching { chain.run { touched++;it.helperExited=true;it.streamClosed=true } }.isFailure)
            assertEquals(0,touched)
        }
    }
    @Test fun operationExceptionCannotBeRetriedEvenAfterOrderlyClose() {
        val chain=OrderlyHelperChain();var touched=0
        assertTrue(runCatching { chain.run { it.helperExited=true;it.streamClosed=true;error("uncertain target outcome") } }.isFailure)
        assertTrue(runCatching { chain.run { touched++ } }.isFailure)
        assertEquals(0,touched)
    }
    @Test fun previousGoodReceiptDoesNotAuthorizeNextFailedConnection() {
        val chain=OrderlyHelperChain()
        chain.run { it.helperExited=true;it.streamClosed=true }
        assertTrue(runCatching { chain.run { error("stale endpoint") } }.isFailure)
        assertFalse(chain.receipt.helperExited)
        assertFalse(chain.receipt.streamClosed)
        assertFalse(chain.receipt.socketClosed)
        assertTrue(runCatching { chain.run { fail("must not enter target block") } }.isFailure)
    }
}
