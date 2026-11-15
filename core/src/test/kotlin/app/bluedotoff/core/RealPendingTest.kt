package app.bluedotoff.core

import org.junit.Assert.*
import org.junit.Test

class RealPendingTest {
    private val id = Identity("test.restart", 0, 10123, listOf("fixture"), 100, 1, 100)
    private val fw = Firmware("samsung", 37, "90000", "fixture", "b", "m", "abi")
    private val visible = Snapshot(id, "Test", listOf(id.packageName), true, true, true, false, 0x300, 0x300, 0, 0, 0, false)
    private var state = visible
    private var rejectWrite = false
    private val port = object : TargetPort {
        override fun compatibleFirmware() = fw
        override fun read(packageName: String) = state
        override fun writeFineBit(packageName: String, originalBit: Int) {
            check(!rejectWrite) { "fixture setter unavailable" }
            state = state.copy(fineFlags = (state.fineFlags and 256.inv()) or originalBit)
        }
    }
    private class ProcessEnded : Error()
    private var killAfterSave = false
    private var interruptedState=TargetState.PENDING_HIDE
    private var saved: Entry? = null
    private val journal = object : Journal {
        override fun entries() = listOfNotNull(saved)
        override fun commit(entry: Entry): Boolean {
            saved = entry
            if (killAfterSave && entry.state == interruptedState) throw ProcessEnded()
            return true
        }
    }
    private val safety = SafetyEngine(port, journal, { 1234 }, { "boot" }, wait = {})
    private fun watch() = WatchEngine(port, journal, { 1234 }, { "boot" }, wait = {})
    private fun runWatch() = watch().reapply(setOf(id.packageName), { true }, {}, {})

    @Test fun retryFromGenuineSafeRollbackShouldStillBeAutomaticAfterRestart() {
        assertTrue(safety.userHide(id.packageName).changedByUs)
        state = visible
        rejectWrite = true
        runWatch()
        val rollback = saved!!
        assertEquals(TargetState.FAILED_SAFE, rollback.state)
        assertTrue(WatchPolicy.retrying(rollback))
        assertFalse(rollback.changedByUs)
        rejectWrite = false
        killAfterSave = true
        val attempts = mutableMapOf<String, List<Long>>()
        val quota = WatchRetry(object : WatchRetry.Store {
            override fun load(pkg: String) = attempts[pkg].orEmpty()
            override fun save(pkg: String, times: List<Long>) { attempts[pkg] = times }
        })
        quota.tried(id.packageName, 700_000)
        quota.tried(id.packageName, 1_400_000)
        assertTrue(quota.allowed(rollback, 2_100_000))
        val third = WatchEngine(port, journal, { 2_100_000 }, { "boot" }, wait = {},
            beforeAttempt = { unit -> unit.filter(WatchPolicy::retrying).forEach { quota.tried(it.identity.packageName, 2_100_000) } })
        try { third.reapply(setOf(id.packageName), { true }, {}, {}); fail("did not reach the durable pending write") } catch (_: ProcessEnded) {}
        val pending = saved!!
        assertEquals(TargetState.PENDING_HIDE, pending.state)
        killAfterSave = false
        runWatch()
        val after = saved!!
        assertEquals(3, attempts[id.packageName]?.size)
        assertFalse(quota.allowed(after, 2_800_000))
        assertTrue(quota.allowed(after, 89_000_000))
        println("retryHidden rollback=${rollback.retryHide} pending=${pending.retryHide} changedByUs=${pending.changedByUs} after=${after.state} automatic=${WatchPolicy.retrying(after)} bit=${state.bit}")
        assertTrue("Previously hidden app must keep its automatic retry after the pending attempt was interrupted", WatchPolicy.retrying(after))
        assertEquals(256,state.bit)
        val allowedAfterExpiry=quota.allowed(after,89_000_000)
        WatchEngine(port,journal,{89_000_000},{"boot"},wait={}).reapply(
            setOf(id.packageName),{allowedAfterExpiry}, {}, {})
        assertEquals(saved!!.error,TargetState.APPLIED,saved!!.state)
        assertFalse(saved!!.retryHide)
        assertEquals(0,state.bit)
    }

    @Test fun firstEverHideMustNotTurnIntoUnrequestedAutomaticRetry() {
        killAfterSave = true
        try { safety.userHide(id.packageName); fail("missing pending write") } catch (_: ProcessEnded) {}
        killAfterSave = false
        runWatch()
        assertFalse(WatchPolicy.retrying(saved!!))
        assertEquals(256, state.bit)
    }

    @Test fun explicitRestoreRemainsExcluded() {
        assertTrue(safety.userHide(id.packageName).changedByUs)
        assertEquals(TargetState.NOT_APPLIED, safety.userRestore(id.packageName).state)
        runWatch()
        assertFalse(WatchPolicy.eligible(saved!!))
        assertEquals(256, state.bit)
    }

    @Test fun uncertainPendingCannotReviveTheRetryWhenItLaterLooksRestored() {
        assertTrue(safety.userHide(id.packageName).changedByUs)
        val record=saved!!.copy(state=TargetState.PENDING_HIDE,retryHide=true,changedByUs=false)
        val uncertain=Reconcile.read(record,visible.copy(coarseGranted=false),fw.key,"boot")
        assertEquals(TargetState.FAILED_ROLLBACK,uncertain.state)
        assertFalse(uncertain.retryHide)
        val restored=Reconcile.read(uncertain,visible,fw.key,"boot")
        assertEquals(TargetState.FAILED_SAFE,restored.state)
        assertFalse(WatchPolicy.retrying(restored))
    }

    @Test fun restoringARetryInterruptedBeforeTheSetterCannotRehide() {
        assertTrue(safety.userHide(id.packageName).changedByUs)
        saved=saved!!.copy(state=TargetState.FAILED_SAFE,retryHide=true,changedByUs=false)
        interruptedState=TargetState.PENDING_RESTORE
        killAfterSave=true
        try{safety.userRestore(id.packageName);fail("restore should be interrupted")}catch(_:ProcessEnded){}
        assertTrue(saved!!.restoreRequested)
        assertFalse(saved!!.retryHide)
        killAfterSave=false
        runWatch()
        assertFalse(WatchPolicy.eligible(saved!!))
    }

    private fun interruptManagedRetry(phase: String) {
        assertTrue(safety.userHide(id.packageName).changedByUs)
        state = visible
        rejectWrite = true
        runWatch()
        assertTrue(WatchPolicy.retrying(saved!!))
        assertFalse(saved!!.changedByUs)

        rejectWrite = false
        val interrupted = WatchEngine(port, journal, { 1234 }, { "boot" }, wait = {},
            observe = { at, _, _ -> if (at == phase) throw ProcessEnded() })
        assertThrows(ProcessEnded::class.java) {
            interrupted.reapply(setOf(id.packageName), { true }, {}, {})
        }
        assertEquals(TargetState.PENDING_HIDE, saved!!.state)
        assertTrue(saved!!.retryHide)
        assertEquals(0, state.bit)
    }

    private fun verifySecondFailure(phase: String) {
        interruptManagedRetry(phase)
        runWatch() // Read-only recovery of the interrupted write.
        assertTrue(WatchPolicy.retrying(saved!!))
        rejectWrite = true // Reaching APPLIED here requires a successful verification without a setter.
        runWatch()
        assertEquals(TargetState.APPLIED, saved!!.state)
        assertFalse(saved!!.retryHide)
        state = visible
        runWatch()
        assertEquals(TargetState.FAILED_SAFE, saved!!.state)
        assertTrue(WatchPolicy.retrying(saved!!))
        rejectWrite = false
        runWatch()
        assertEquals(TargetState.APPLIED, saved!!.state)
        assertEquals(0, state.bit)
        assertEquals(TargetState.NOT_APPLIED, safety.userRestore(id.packageName).state)
        runWatch()
        assertEquals(visible, state)
    }

    @Test fun anotherFailureAfterInterruptedSetterStillRetries() = verifySecondFailure("immediate")
    @Test fun anotherFailureAfterInterruptedLastCheckStillRetries() = verifySecondFailure("settled_1500ms")

    @Test fun originallyOffFirstRegistrationGetsNoManagedWriteHistory() {
        state = visible.copy(fineFlags = visible.fineFlags and SENSITIVITY_BIT.inv())
        val initial = safety.userHide(id.packageName)
        assertEquals(TargetState.APPLIED, initial.state)
        assertFalse(initial.changedByUs)
        assertFalse(initial.retryHide)
        assertEquals(0, safety.userRestore(id.packageName).originalBit)
        runWatch()
        assertEquals(0, state.bit)
        assertFalse(WatchPolicy.eligible(saved!!))
    }

    @Test fun manualPendingMismatchRemainsOutsideWatchAfterOriginalStateReturns() {
        interruptManagedRetry("immediate")
        state = visible.copy(coarseGranted = false)
        assertThrows(SafetyException::class.java) { safety.userHide(id.packageName) }
        assertEquals(TargetState.FAILED_ROLLBACK, saved!!.state)
        assertFalse(saved!!.retryHide)
        state = visible
        saved = Reconcile.read(saved!!, state, fw.key, "boot")
        assertEquals(TargetState.FAILED_SAFE, saved!!.state)
        val held = saved
        runWatch()
        assertFalse(WatchPolicy.eligible(saved!!))
        assertEquals(held, saved)
        assertEquals(visible, state)
        assertEquals(TargetState.APPLIED, safety.userHide(id.packageName).state)
    }

    @Test fun olderSafetyHoldWithStoredRetryBitAlsoRequiresManualAction() {
        interruptManagedRetry("immediate")
        val legacyHold = saved!!.copy(state = TargetState.FAILED_ROLLBACK, retryHide = true)
        state = visible
        saved = Reconcile.read(legacyHold, state, fw.key, "boot")
        assertEquals(TargetState.FAILED_SAFE, saved!!.state)
        assertFalse(WatchPolicy.retrying(saved!!))
        runWatch()
        assertEquals(visible, state)
    }

    @Test fun updateDuringFirstHideIsConsumedAtTheHoldAndTheNextUpdateCanReapply() {
        val current=id.copy(versionCode=2,lastUpdateTime=200,signers=listOf("fixture","rotated"))
        val changed=SafetyEngine(port,journal,{2L},{"boot"},wait={state=visible.copy(identity=current)})
        val locked=changed.userHide(id.packageName)
        assertEquals(TargetState.FAILED_ROLLBACK,locked.state)
        val held=Reconcile.read(locked,state,fw.key,"boot")
        assertEquals(TargetState.FAILED_SAFE,held.state)
        assertEquals(id.copy(versionCode=2,lastUpdateTime=200),held.identity)
        assertEquals(locked.originalBit,held.originalBit)
        assertEquals(locked.invariantDigest,held.invariantDigest)
        assertFalse(held.retryHide)
        assertEquals(held,Reconcile.read(held,state,fw.key,"boot"))
        assertEquals(held,Reconcile.observedUpdate(held,current))
        val next=state.copy(identity=current.copy(lastUpdateTime=300))
        assertTrue(Reconcile.read(held,next,fw.key,"boot").needsReapply)
        assertTrue(Reconcile.observedUpdate(held,next.identity).needsReapply)
    }

    @Test fun interruptedFirstHideConsumesAnAlreadyInstalledUpdateWhenHeld() {
        killAfterSave=true
        assertThrows(ProcessEnded::class.java){safety.userHide(id.packageName)}
        val updated=visible.copy(identity=id.copy(versionCode=2,lastUpdateTime=200))
        val held=Reconcile.read(saved!!,updated,fw.key,"boot")
        assertTrue(held.manualHold)
        assertEquals(updated.identity,held.identity)
        assertEquals(held,Reconcile.read(held,updated,fw.key,"boot"))
    }

    @Test fun oldScanCannotReopenAnAlreadyNewerHold() {
        val newer=visible.copy(identity=id.copy(versionCode=2,lastUpdateTime=200))
        val held=safety.userHide(id.packageName).copy(identity=newer.identity,state=TargetState.FAILED_SAFE,retryHide=false)
        assertEquals(held,Reconcile.read(held,visible,fw.key,"boot"){newer})
        assertEquals(held,Reconcile.read(held,visible,fw.key,"boot"){null})
        val newerAgain=newer.copy(identity=newer.identity.copy(lastUpdateTime=250))
        assertTrue(Reconcile.read(held,newerAgain,fw.key,"boot"){newerAgain}.needsReapply)
    }

    @Test fun scanTakenBeforeTheHoldCannotBecomeItsInstalledUpdateBaseline() {
        val newer=visible.copy(identity=id.copy(versionCode=2,lastUpdateTime=200))
        val locked=safety.userHide(id.packageName).copy(state=TargetState.FAILED_ROLLBACK)
        assertEquals(locked,Reconcile.read(locked,visible,fw.key,"boot"){null})
        val held=Reconcile.read(locked,visible,fw.key,"boot"){newer}
        assertTrue(held.manualHold)
        assertEquals(newer.identity,held.identity)
        assertFalse(Reconcile.observedUpdate(held,newer.identity).needsReapply)
    }

    @Test fun revisionChangeWhileVerifyingAnOriginallyOffAppCreatesOnlyAHold() {
        state=visible.copy(fineFlags=0x200)
        val updated=state.copy(identity=id.copy(versionCode=2,lastUpdateTime=200))
        rejectWrite=true
        val engine=SafetyEngine(port,journal,{2L},{"boot"},wait={state=updated})
        val held=engine.userHide(id.packageName)
        assertTrue(held.manualHold)
        assertEquals(0,held.originalBit)
        assertEquals(updated.identity,held.identity)
        assertEquals(held,Reconcile.observedUpdate(held,updated.identity))
    }

    @Test fun temporaryPermissionChangeDoesNotLetALateBroadcastReleaseAHold() {
        val held=safety.userHide(id.packageName).copy(state=TargetState.FAILED_SAFE,retryHide=false)
        val denied=visible.copy(fineGranted=false)
        val afterCheck=Reconcile.read(held,denied,fw.key,"boot")
        assertEquals(held,afterCheck)
        assertEquals(held,Reconcile.read(afterCheck,null,fw.key,"boot"))
        assertEquals(held,Reconcile.observedUpdate(afterCheck,id))
        assertEquals(held,Reconcile.read(afterCheck,visible,fw.key,"boot"))
        assertFalse(WatchPolicy.eligible(afterCheck))
    }
}
