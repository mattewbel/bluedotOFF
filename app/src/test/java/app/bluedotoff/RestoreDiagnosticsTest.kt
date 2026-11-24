package app.bluedotoff

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import app.bluedotoff.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class)
class RestoreDiagnosticsTest {
    private val ids = listOf("private.alpha", "private.beta").map {
        Identity(it, 0, 10456, listOf("secret-signer"), 10, 2, 30)
    }
    private val names = ids.map { it.packageName }
    private val reads = ids.map { id ->
        Snapshot(id, "Private label", names, true, true, true, false, 512, 512, 0, 4, 4, false,
            sharedUserId="private.group", applicationScope=ApplicationScope.ALL)
    }
    private val records = ids.mapIndexed { index, id ->
        Entry(id, "Private label", 256, true, TargetState.APPLIED, 1, "fw", "boot", true,
            settlingVerified=true, sharedUserId="private.group", groupIdentities=ids,
            invariantDigest=reads[index].invariantDigest())
    }
    private fun lines(model: UiModel) = restoreDiagnosticLines(model) {
        UiText.diagnosticReason(RuntimeEnvironment.getApplication(), it)
    }

    @Test fun oneBlockedMemberExplainsWhyBothAreSkippedEvenIfTheHealthyOneIsNotInCheckEntries() {
        for (i in records.indices) {
            val members = records.toMutableList()
            members[i] = members[i].copy(state=TargetState.UNSUPPORTED_STATE, restoreRequested=true,
                error="개인 식별 정보 private.beta secret-signer")
            val model = UiModel(entries=members.reversed())
            assertEquals(listOf(members[i]), model.checkEntries)
            assertTrue(model.restorePlan(names).first.isEmpty())
            val line = lines(model).single()
            assertTrue(line.contains("skipped by saved state of #${i+1} (recorded conditions do not support restore)"))
            assertTrue(line.contains("#${i+1} UNSUPPORTED_STATE, restore requested"))
            assertTrue(line.contains("#${2-i} APPLIED"))
            for (privateText in names + listOf("Private label", "secret-signer", "private.group", "10456", "개인 식별"))
                assertFalse(line.contains(privateText))
        }
    }

    @Test fun aRealReconcileAfterOneMembersPermissionChangesExplainsTheSkippedRestore() {
        val observed = listOf(reads.first(), reads.last().copy(backgroundGranted=true))
        val byName = observed.associateBy { it.identity.packageName }
        val saved = records.map { SharedGroupReconcile.read(it.copy(restoreRequested=true), byName, "fw", "boot") }
        val model = UiModel(entries=saved, candidates=observed, freshPackages=names.toSet())
        assertEquals(listOf(TargetState.APPLIED, TargetState.UNSUPPORTED_STATE), saved.map { it.state })
        val planBefore = model.restorePlan(names)
        assertTrue(planBefore.first.isEmpty())
        val line = lines(model).single()
        assertTrue(line.contains("#1 APPLIED, restore requested | #2 UNSUPPORTED_STATE, restore requested, last read: recorded permission or group conditions differ"))
        assertTrue(line.contains("group restore skipped by saved state of #2"))
        assertEquals(planBefore, model.restorePlan(names))
    }

    @Test fun historicLockIsSeparateFromPartialLastReadAndInactiveOrMissingPeersAreIncluded() {
        val locked = records.first().copy(state=TargetState.NEEDS_REVIEW, restoreRequested=true,
            error=SharedGroupReconcile.NEW_INSTALL.last())
        val model = UiModel(entries=listOf(locked, records.last().copy(state=TargetState.MISSING_TARGET)),
            candidates=reads.map { it.copy(identity=it.identity.copy(firstInstallTime=44)) },
            freshPackages=setOf(names.last()))
        val line = lines(model).single()
        assertTrue(line.contains("#1 (safety review required), #2 (app recorded missing)"))
        assertTrue(line.contains("saved reason: An app in the group was removed or replaced."))
        assertTrue(line.contains("#2 MISSING_TARGET, last read: install identity differs (first-install time)"))
        assertTrue(line.contains("last reads: 1/2"))
        assertFalse(line.substringBefore("| #2 MISSING_TARGET").contains("last read:"))
        assertTrue(lines(model.copy(entries=listOf(locked))).single().contains("#2 no saved record"))
    }

    @Test fun unlockedRestoreRequestIsNotEvidenceOfALiveRestorePassing() {
        val saved = records.map { it.copy(state=TargetState.NEEDS_REAPPLY_UPDATE, restoreRequested=true) }
        val model = UiModel(entries=saved, candidates=reads.map { it.copy(fineGranted=false) },
            freshPackages=names.toSet())
        val line = lines(model).single()
        assertEquals(names, model.restorePlan(names).first)
        assertTrue(line.contains("no saved-state skip"))
        assertTrue(line.contains("last reads: 2/2, group check:"))
        assertTrue(line.contains("group check: No app in this group has precise location allowed."))
        val stale = lines(model.copy(freshPackages=emptySet())).single()
        assertTrue(stale.contains("last reads: 0/2"))
        assertFalse(stale.contains("group check:"))
        assertTrue(lines(UiModel(entries=records)).isEmpty())
    }

    @Test fun aPendingOperationOrConflictingOriginalValuesAreExplainedWithoutAReread() {
        val requested = records.map { it.copy(restoreRequested=true) }
        fun withOther(other: Entry) = lines(UiModel(entries=listOf(requested.first(), other))).single()
        assertTrue(withOther(requested.last().copy(state=TargetState.PENDING_RESTORE))
            .contains("previous group operation needs checking before restore"))
        assertTrue(withOther(requested.last().copy(originalBit=0)).contains("saved original display states disagree"))
    }

    @Test fun diagnosticsActionCopiesTheSameAnonymousGroupReportItPassesToMail() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            val attempt=java.util.UUID.randomUUID().toString()
            val helper=java.util.UUID.randomUUID().toString()
            WatchEvidence.died(activity,helper)
            WatchEvidence.hostFailure(activity,attempt,"HANDSHAKE_TIMEOUT")
            WatchEvidence.helperFailure(activity,attempt,"PACKAGE_CALLBACK","SECURITY")
            val model = UiModel(entries=listOf(records.first(), records.last().copy(state=TargetState.UNSUPPORTED_STATE)))
            MainActivity::class.java.getDeclaredMethod("setModel", UiModel::class.java)
                .apply { isAccessible=true }.invoke(activity, model)
            MainActivity::class.java.getDeclaredMethod("copyDiagnostics")
                .apply { isAccessible=true }.invoke(activity)
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
            val mail = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_SENDTO, mail.action)
            assertEquals(copied, mail.getStringExtra(Intent.EXTRA_TEXT))
            val reason=copied.lineSequence().single { it.startsWith("Start attempt:") }
            assertTrue(reason.contains("HANDSHAKE_TIMEOUT")&&reason.contains("PACKAGE_CALLBACK"))
            assertTrue(copied.contains("no helper exit reason received"))
            assertFalse(copied.contains(attempt)||copied.contains(helper))
            assertTrue(copied.lineSequence().filter { it.startsWith("Needs check:") }.single().contains("#1 APPLIED | #2 UNSUPPORTED_STATE"))
            assertFalse(names.any { copied.contains(it) })
        } finally {
            controller.destroy()
        }
    }
}
