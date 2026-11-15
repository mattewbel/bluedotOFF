package app.bluedotoff.adb

internal class WatchReceipt(
    val checked: LongArray = longArrayOf(),
    val failed: LongArray = longArrayOf()
)

/** Owned by the helper's single callback thread. A receipt identifies one change, even if a
 * newer change for the same package arrives before a previous check finishes. */
internal class WatchDelivery {
    private val outstanding = linkedMapOf<String, Long>()
    private val failures = mutableMapOf<String, Int>()
    private var serial = 0L

    fun offer(changed: Set<String>, targets: Set<String>): Map<String, Long> {
        outstanding.keys.retainAll(targets)
        failures.keys.retainAll(targets)
        for (name in changed) if (name in targets) {
            outstanding[name] = ++serial
            failures.remove(name)
        }
        val packet = linkedMapOf<String, Long>()
        var chars = 0
        for ((name, revision) in outstanding) {
            if (packet.size >= MAX_PACKET || chars + name.length > 32_768) break
            packet[name] = revision
            chars += name.length
        }
        // Rotate each offer, so a busy or repeatedly failing target cannot starve later names.
        for ((name, revision) in packet) {
            outstanding.remove(name)
            outstanding[name] = revision
        }
        return packet
    }

    fun acknowledge(packet: Map<String, Long>, receipt: WatchReceipt) {
        val checked = receipt.checked.toSet()
        val failed = receipt.failed.toSet()
        for ((name, revision) in packet) {
            if (outstanding[name] != revision) continue
            if (revision in checked) {
                outstanding.remove(name)
                failures.remove(name)
            } else if (revision in failed) {
                val tries = (failures[name] ?: 0) + 1
                if (tries >= MAX_FAILED_CHECKS) {
                    outstanding.remove(name)
                    failures.remove(name)
                } else {
                    // A new ID distinguishes the next real attempt from a duplicate failure reply.
                    failures[name] = tries
                    outstanding[name] = ++serial
                }
            }
        }
    }

    companion object {
        const val MAX_PACKET = 512
        const val MAX_FAILED_CHECKS = 3
    }
}

/** One authenticated helper at a time, always accessed under WatchClient's queue lock.
 * Losing the app process loses receipts, so the surviving helper offers the changes again. */
internal class WatchReceipts {
    private val latest = mutableMapOf<String, Long>()
    private val finished = mutableMapOf<String, Long>()
    private val failed = mutableMapOf<String, Long>()

    fun receive(packet: Map<String, Long>, eligible: Set<String>): Set<String> {
        latest.keys.retainAll(eligible)
        finished.keys.retainAll(eligible)
        failed.keys.retainAll(eligible)
        val work = mutableSetOf<String>()
        for ((name, revision) in packet) {
            if (name !in eligible || revision <= (finished[name] ?: 0) || revision == failed[name]) continue
            latest[name] = maxOf(revision, latest[name] ?: 0)
            work += name
        }
        return work
    }

    fun receipt(packet: Map<String, Long>, eligible: Set<String>) = WatchReceipt(
        packet.filter { (name, revision) -> name !in eligible || revision <= (finished[name] ?: 0) }.values.toLongArray(),
        packet.filter { (name, revision) -> name in eligible && failed[name] == revision }.values.toLongArray()
    )

    fun starting(names: Set<String>) = names.mapNotNull { name -> latest[name]?.let { name to it } }.toMap()

    fun finish(started: Map<String, Long>, handled: Set<String>, errors: Set<String> = emptySet()) {
        for ((name, revision) in started) {
            if (latest[name] != revision) continue
            when (name) {
                in handled -> {
                    finished[name] = revision
                    failed.remove(name)
                }
                in errors -> failed[name] = revision
                else -> continue
            }
            latest.remove(name)
        }
    }
}
