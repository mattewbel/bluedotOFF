package app.bluedotoff

import app.bluedotoff.adb.WatchDelivery
import app.bluedotoff.adb.WatchReceipt
import app.bluedotoff.adb.WatchReceipts
import org.junit.Assert.*
import org.junit.Test

class WatchDeliveryTest {
    private val target = "test.watch"
    private val targets = setOf(target)

    @Test fun aLostReplyOrAnAppProcessRestartCanOnlyRepeatAnUnconfirmedCheck() {
        val helper = WatchDelivery()
        var client = WatchReceipts()
        val first = helper.offer(targets, targets)
        assertEquals(targets, client.receive(first, targets))
        assertEquals(0, client.receipt(first, targets).checked.size)
        client = WatchReceipts()
        val replay = helper.offer(emptySet(), targets)
        assertEquals(first, replay)
        assertEquals(targets, client.receive(replay, targets))
        client.finish(client.starting(targets), targets)
        // Lose the first response with a receipt.
        assertTrue(client.receive(helper.offer(emptySet(), targets), targets).isEmpty())
        helper.acknowledge(replay, client.receipt(replay, targets))
        assertTrue(helper.offer(emptySet(), targets).isEmpty())
    }

    @Test fun aNewChangeWhileAnOlderCheckRunsStillNeedsItsOwnCheck() {
        val helper = WatchDelivery()
        val client = WatchReceipts()
        val old = helper.offer(targets, targets)
        client.receive(old, targets)
        val reading = client.starting(targets)
        val newer = helper.offer(targets, targets)
        client.receive(newer, targets)
        client.finish(reading, targets)
        assertTrue(client.receipt(newer, targets).checked.isEmpty())
        helper.acknowledge(old, WatchReceipt(checked=old.values.toLongArray()))
        assertEquals(newer, helper.offer(emptySet(), targets))
        client.finish(client.starting(targets), targets)
        helper.acknowledge(newer, client.receipt(newer, targets))
        assertTrue(helper.offer(emptySet(), targets).isEmpty())
    }

    @Test fun aWithdrawnTargetIsNotReenrolledByItsOldEvent() {
        val helper = WatchDelivery()
        val client = WatchReceipts()
        val event = helper.offer(targets, targets)
        assertTrue(client.receive(event, emptySet()).isEmpty())
        helper.acknowledge(event,client.receipt(event,emptySet()))
        assertTrue(helper.offer(emptySet(),targets).isEmpty())
        assertTrue(helper.offer(targets,emptySet()).isEmpty())
    }

    @Test fun unconfirmedLargeBurstsAreBoundedAndNoPackageStarves() {
        val all = (1..2000).map { "test." + "a".repeat(215) + it }.toSet()
        val helper = WatchDelivery()
        val seen = mutableSetOf<String>()
        repeat(20) {
            val packet = helper.offer(if(it==0) all else emptySet(), all)
            assertTrue(packet.keys.sumOf(String::length)<=32_768 && packet.size<=WatchDelivery.MAX_PACKET)
            seen+=packet.keys
        }
        assertEquals(all, seen)
    }

    @Test fun threeActualFailedChecksEndOneChangeAndLostContactsSpendNoAttempts() {
        val helper=WatchDelivery()
        val client=WatchReceipts()
        var packet=helper.offer(targets,targets)
        val ids=mutableSetOf<Long>()
        repeat(WatchDelivery.MAX_FAILED_CHECKS) {
            assertTrue(ids.add(packet.getValue(target)))
            repeat(8) { assertEquals(packet,helper.offer(emptySet(),targets)) }
            assertEquals(targets,client.receive(packet,targets))
            client.finish(client.starting(targets),emptySet(),targets)
            assertTrue(client.receive(packet,targets).isEmpty())
            val error=client.receipt(packet,targets)
            assertTrue(error.checked.isEmpty())
            assertArrayEquals(packet.values.toLongArray(),error.failed)
            helper.acknowledge(packet,error)
            helper.acknowledge(packet,error)
            packet=helper.offer(emptySet(),targets)
        }
        assertTrue(packet.isEmpty())
        assertEquals(targets,client.receive(helper.offer(targets,targets),targets))
    }

    @Test fun aFailedReadDoesNotKeepAnIndependentPackageBehindItOnTheNextContact() {
        val other="test.healthy"
        val both=linkedSetOf(target,other)
        val helper=WatchDelivery()
        val client=WatchReceipts()
        val packet=helper.offer(both,both)
        assertEquals(both,client.receive(packet,both))
        client.finish(client.starting(both),emptySet(),setOf(target))
        val retry=helper.offer(emptySet(),both)
        assertEquals(setOf(other),client.receive(retry,both))
        helper.acknowledge(retry,client.receipt(retry,both))
        client.finish(client.starting(setOf(other)),setOf(other))
        val followup=helper.offer(emptySet(),both)
        assertEquals(setOf(target),client.receive(followup,both))
        assertArrayEquals(longArrayOf(packet.getValue(other)),client.receipt(followup,both).checked)
    }
}
