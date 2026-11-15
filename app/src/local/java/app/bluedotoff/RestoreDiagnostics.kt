package app.bluedotoff

import app.bluedotoff.core.Entry
import app.bluedotoff.core.SharedGroupPolicy
import app.bluedotoff.core.Snapshot
import app.bluedotoff.core.TargetState

/** Format the existing screen model. Producing a support email never opens a session. */
internal fun restoreDiagnosticLines(model: UiModel, reason: (String) -> String): List<String> {
    val entries = model.entries.associateBy { it.identity.packageName }
    val lastReads = model.candidates.filter { it.identity.packageName in model.freshPackages }
        .associateBy { it.identity.packageName }
    val seenGroups = mutableSetOf<List<String>>()
    return buildList {
        for (entry in model.checkEntries) {
            if (entry.groupIdentities.isEmpty()) {
                add("Needs check: single app, " + recordedStatus(entry, reason))
                continue
            }
            val names = entry.groupIdentities.map { it.packageName }.sorted()
            if (!seenGroups.add(names)) continue
            val rows = names.map { entries[it] }
            val reads = names.mapNotNull { lastReads[it] }
            val blockers = rows.mapIndexedNotNull { index, row ->
                row?.savedBlocker()?.let { "#${index + 1} ($it)" }
            }
            val plan = when {
                blockers.isNotEmpty() -> "group restore skipped by saved state of ${blockers.joinToString()}"
                model.restorePlan(names).first.size != names.size -> "group restore skipped by linked record"
                rows.any { it == null } -> "incomplete group record"
                rows.filterNotNull().any { it.groupIdentities.map { id -> id.packageName } != names } -> "group membership differs between saved records"
                rows.filterNotNull().any { it.state in setOf(TargetState.PENDING_HIDE, TargetState.PENDING_RESTORE, TargetState.PENDING_SHOW) } -> "previous group operation needs checking before restore"
                rows.filterNotNull().map { it.originalBit }.distinct().size > 1 -> "saved original display states disagree"
                else -> "no saved-state skip; live restore checks still required"
            }
            add(buildString {
                append("Needs check: group of ${names.size} | $plan")
                rows.forEachIndexed { i, row ->
                    append(" | #${i + 1} ")
                    if (row == null) append("no saved record") else {
                        append(recordedStatus(row, reason))
                        val read = lastReads[names[i]]
                        if (read != null) appendLastRead(row, read)
                    }
                }
                append(" | last reads: ${reads.size}/${names.size}")
                if (reads.size == names.size) {
                    try {
                        SharedGroupPolicy.validate(reads)
                    } catch (e: Exception) {
                        append(", group check: " + reason(e.message.orEmpty()))
                    }
                }
            })
        }
    }
}

private fun Entry.savedBlocker(): String? = when {
    state == TargetState.MISSING_TARGET -> "app recorded missing"
    !enabled -> "inactive record"
    state !in RESTORE_SKIP -> null
    state == TargetState.UNSUPPORTED_STATE -> "recorded conditions do not support restore"
    state == TargetState.NEEDS_REVIEW -> "safety review required"
    state == TargetState.FAILED_ROLLBACK -> "previous rollback unconfirmed"
    state == TargetState.INACTIVE_INSTALL -> "old installation record"
    state == TargetState.SHARED_UID_BLOCKED || state == TargetState.SHARED_UID_CHANGED -> "shared ownership blocked or changed"
    else -> "excluded by saved state"
}

private fun recordedStatus(entry: Entry, reason: (String) -> String) = buildString {
    append(entry.state.name)
    if (!entry.enabled) append(", inactive")
    if (entry.restoreRequested) append(", restore requested")
    if (entry.error.isNotBlank()) append(", saved reason: " + reason(entry.error))
}

private fun StringBuilder.appendLastRead(entry: Entry, read: Snapshot) {
    if (!entry.identity.sameInstall(read.identity)) {
        val saved = entry.identity
        val last = read.identity
        val fields = buildList {
            if (saved.userId != last.userId || saved.uid != last.uid) add("user or UID")
            if (saved.firstInstallTime != last.firstInstallTime) add("first-install time")
            if (saved.signers.isEmpty() || !last.signers.containsAll(saved.signers)) add("signing history")
        }
        append(", last read: install identity differs")
        if (fields.isNotEmpty()) append(" (" + fields.joinToString() + ")")
    }
    if (!read.peersAuthoritative) append(", last read: group membership unverified")
    else if (read.peers.toSet() != entry.groupIdentities.map { it.packageName }.toSet() || read.sharedUserId != entry.sharedUserId)
        append(", last read: group membership differs")
    if (entry.invariantDigest.isNotBlank() && entry.invariantDigest != read.invariantDigest())
        append(", last read: recorded permission or group conditions differ")
}
