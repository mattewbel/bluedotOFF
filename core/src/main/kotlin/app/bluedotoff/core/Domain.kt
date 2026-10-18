package app.bluedotoff.core

import java.security.MessageDigest

const val SENSITIVITY_BIT = 0x100
// Stored by name in journals: append only. PENDING_SHOW is an unfinished "표시 켜기" (value before OFF, target ON).
enum class TargetState { NOT_APPLIED, PENDING_HIDE, PENDING_RESTORE, APPLIED, NEEDS_REAPPLY_BOOT, NEEDS_REAPPLY_UPDATE, NEEDS_RECHECK_FIRMWARE, UNSUPPORTED_STATE, MISSING_TARGET, SHARED_UID_BLOCKED, SHIZUKU_REQUIRED, FAILED_SAFE, FAILED_ROLLBACK, INACTIVE_INSTALL, SHARED_UID_CHANGED, NEEDS_REVIEW, NEEDS_REAPPLY_POLICY, PENDING_SHOW }
enum class Compatibility { SUPPORTED_VERIFIED, UNVERIFIED, UNSUPPORTED, NEEDS_RECHECK }
data class Identity(val packageName: String, val userId: Int, val uid: Int, val signers: List<String>, val firstInstallTime: Long, val versionCode: Long, val lastUpdateTime: Long) {
    fun sameInstall(other: Identity) = packageName == other.packageName && userId == other.userId && uid == other.uid && signers == other.signers && firstInstallTime == other.firstInstallTime
}
data class Snapshot(val identity: Identity, val label: String, val peers: List<String>, val fineDeclared: Boolean, val fineGranted: Boolean, val coarseGranted: Boolean, val backgroundGranted: Boolean, val fineFlags: Int, val coarseFlags: Int, val backgroundFlags: Int, val fineMode: Int, val coarseMode: Int, val systemCritical: Boolean, val lastLocationAccess: Long? = null, val sharedUserId:String?=null, val persistent:Boolean=false, val criticalPackage:Boolean=false, val peersAuthoritative:Boolean=true, val singletonVerified:Boolean=false, val coarseDeclared:Boolean=true, val applicationScope:ApplicationScope=ApplicationScope.GROUPS) {
    val bit get() = fineFlags and SENSITIVITY_BIT
    val ownership get()=UidOwnership(identity.packageName,identity.uid,peers,sharedUserId,persistent,criticalPackage || (systemCritical && !persistent && UidPolicy.appId(identity.uid)>=10000),peersAuthoritative)
    val eligibility get()=EligibilityPolicy.evaluate(this)
    fun invariantDigest(): String = MessageDigest.getInstance("SHA-256").digest(listOf(peers.joinToString(","),fineDeclared,fineGranted,coarseGranted,backgroundGranted,fineFlags and SENSITIVITY_BIT.inv(),coarseFlags,backgroundFlags,fineMode,coarseMode,systemCritical).joinToString("|").let{base->if(sharedUserId==null&&coarseDeclared&&!persistent&&!criticalPackage)base else "$base|shared=$sharedUserId|persistent=$persistent|critical=$criticalPackage|coarseDeclared=$coarseDeclared"}.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun safe():Boolean=eligibility !is EligibilityResult.Blocked
    fun rejectionReason()=(eligibility as? EligibilityResult.Blocked)?.reason?.message?:""
    fun invariantEquals(after: Snapshot, wanted: Int): Boolean = identity == after.identity && peers == after.peers && fineDeclared == after.fineDeclared && fineGranted == after.fineGranted && coarseGranted == after.coarseGranted && backgroundGranted == after.backgroundGranted && fineMode == after.fineMode && coarseMode == after.coarseMode && coarseFlags == after.coarseFlags && backgroundFlags == after.backgroundFlags && systemCritical == after.systemCritical && sharedUserId==after.sharedUserId && persistent==after.persistent && criticalPackage==after.criticalPackage && peersAuthoritative==after.peersAuthoritative && singletonVerified==after.singletonVerified && coarseDeclared==after.coarseDeclared && after.fineFlags == ((fineFlags and SENSITIVITY_BIT.inv()) or wanted)
}
data class Entry(val identity: Identity, val label: String, val originalBit: Int, val enabled: Boolean, val state: TargetState, val lastVerifiedTime: Long, val firmware: String, val bootSession: String, val changedByUs: Boolean, val error: String = "", val invariantDigest: String = "", val settlingVerified: Boolean = false, val sharedUserId:String?=null,val groupIdentities:List<Identity> = emptyList()) {
    init { require(originalBit == 0 || originalBit == SENSITIVITY_BIT) }
}
data class Firmware(val manufacturer: String, val sdk: Int, val oneUi: String, val fingerprint: String, val buildId: String, val model: String, val abiProfile: String) {
    val osSupported get() = FirmwareSupport.setup(manufacturer,sdk,oneUi)
    val key get() = listOf(fingerprint, buildId, oneUi, sdk.toString(), model, abiProfile).joinToString("|")
}
interface TargetPort {
    fun read(packageName:String):Snapshot
    fun writeFineBit(packageName:String,originalBit:Int)
    fun compatibleFirmware():Firmware
    fun writeFineGroupChecked(expected:List<Snapshot>,wanted:Int):Unit=throw SafetyException("공유 앱 묶음 쓰기를 지원하지 않는 연결입니다.")
    fun writeFineBitChecked(expected:Snapshot,wanted:Int) {
        val fresh=read(expected.identity.packageName)
        if(expected.peers!=fresh.peers || expected.sharedUserId!=fresh.sharedUserId)throw OwnershipChangedException()
        if(ScopePolicy.ownershipBlock(expected.ownership,expected.singletonVerified,expected.applicationScope)!=null || expected.identity.signers.isEmpty() || !expected.invariantEquals(fresh,expected.bit))throw SafetyException("적용 직전 앱 상태가 바뀌어 변경을 멈췄습니다.")
        writeFineBit(expected.identity.packageName,wanted)
    }
}
interface Journal { fun entries(): List<Entry>; fun commit(entry: Entry): Boolean; fun commitGroup(entries:List<Entry>):Boolean=false }
open class SafetyException(message: String) : Exception(message)
internal class JournalPersistenceException : SafetyException("복원 기록 저장을 확인하지 못했습니다. 추가 변경을 중지했습니다.")
class OwnershipChangedException : SafetyException("이 앱과 묶인 앱 구성이 바뀌어 변경을 멈췄습니다.")

/** All target writes use this durable preflight/settling/rollback path (user actions or explicit opt-in WatchEngine). */
class SafetyEngine(private val port: TargetPort, private val journal: Journal, private val now: () -> Long, private val boot: () -> String, private val wait: (Long) -> Unit = { Thread.sleep(it) }, private val observe: (String, Snapshot, Boolean) -> Unit = { _, _, _ -> }) {
    companion object { internal val writeLock = Any() }
    fun userHide(packageName: String): Entry = synchronized(writeLock) { execute(packageName, hide = true) }
    fun userRestore(packageName: String): Entry = synchronized(writeLock) { execute(packageName, hide = false) }
    fun userOne(packageName:String,hide:Boolean,canStart:()->Boolean):Entry = synchronized(writeLock){execute(packageName,hide,canStart)}
    /** "표시 켜기": an app that is OFF with no bluedotOFF record is turned ON. Not a restore: the record keeps OFF as the
     *  value before and ON as the target (PENDING_SHOW), a failure rolls back to OFF, and success leaves it unregistered. */
    fun userShow(packageName:String,canStart:()->Boolean={true}):Entry = synchronized(writeLock){execute(packageName,false,canStart,show=true)}
    private fun durable(entry: Entry): Entry {
        val saved=try{journal.commit(entry)}catch(_:Exception){false}
        if(!saved)throw JournalPersistenceException()
        return entry
    }
    private fun records():List<Entry> = try{journal.entries()}catch(_:Exception){throw JournalPersistenceException()}
    private fun prepare(pkg: String, hide: Boolean, canStart: () -> Boolean = { true }, inFlightUids: Set<Int> = emptySet(), show: Boolean = false): Started {
        val firmware = port.compatibleFirmware()
        if (!firmware.osSupported || firmware.abiProfile.isBlank()) throw SafetyException("이 기기에서는 안전한 적용을 확인할 수 없습니다. 변경된 내용은 없습니다.")
        val before = port.read(pkg)
        observe("before", before, false)
        if (before.identity.uid in inFlightUids) throw OwnershipChangedException()
        val old = records().lastOrNull { it.identity.packageName == pkg && it.identity.userId == 0 }?.takeUnless { !it.enabled && (it.state == TargetState.INACTIVE_INSTALL || ((hide || show) && it.state == TargetState.NOT_APPLIED)) }
        if(old?.groupIdentities?.isNotEmpty()==true)throw SafetyException("이 앱은 공유 앱 묶음으로 함께 변경해야 합니다.")
        if(show&&old!=null)throw SafetyException("bluedotOFF 기록이 있는 앱이에요. 숨김 풀기 대신 원래대로 복원을 써주세요.")
        if (old?.state in listOf(TargetState.FAILED_ROLLBACK,TargetState.SHARED_UID_CHANGED,TargetState.NEEDS_REVIEW)) throw SafetyException("복원 상태를 확인할 수 없어 추가 변경을 차단했습니다.")
        if (old != null && !old.identity.sameInstall(before.identity)) {
            durable(old.copy(state = TargetState.INACTIVE_INSTALL, enabled = false, error = "새 설치입니다. 과거 기록은 적용하지 않습니다."))
            throw SafetyException("앱 설치 정보가 바뀌었습니다. 상세 화면에서 새로 등록해 주세요.")
        }
        if(old?.sharedUserId!=null && (before.peers!=listOf(pkg)||before.sharedUserId!=old.sharedUserId)) {
            durable(old.copy(state=TargetState.SHARED_UID_CHANGED,error=OwnershipChangedException().message!!))
            throw OwnershipChangedException()
        }
        if (old?.state in listOf(TargetState.PENDING_HIDE,TargetState.PENDING_RESTORE,TargetState.PENDING_SHOW) && (old!!.invariantDigest.isBlank() || old.invariantDigest != before.invariantDigest())) {
            durable(old.copy(state=TargetState.FAILED_ROLLBACK,error="중단된 작업의 안전성을 확인하지 못했습니다. 추가 변경을 차단했습니다."))
            throw SafetyException("중단된 작업의 복원 확인이 필요합니다.")
        }
        if (old != null && old.firmware != firmware.key && old.firmware.substringAfterLast('|') != firmware.abiProfile) throw SafetyException("기기 업데이트 후 호환성 재확인이 필요합니다.")
        if (!hide && old != null && !old.enabled && old.state == TargetState.NOT_APPLIED && before.safe()) return Started.Finished(old)
        if ((hide || show) && !before.safe()) throw SafetyException(before.rejectionReason()+" 변경된 내용은 없습니다.")
        // Restore also refuses unsafe identities, UID peers or revoked/unusual permission states.
        if (!hide && !before.safe()) throw SafetyException("현재 앱 권한 상태에서 안전한 복원을 확인할 수 없습니다. 변경하지 않았습니다.")
        if (!hide && !show && old == null) throw SafetyException("복원할 등록 기록이 없습니다.")
        if (show && before.bit != 0) throw SafetyException("이미 위치 표시가 켜져 있어요. 변경하지 않았어요.")
        val original = old?.originalBit ?: before.bit
        val wanted = if (hide) 0 else if (show) SENSITIVITY_BIT else original
        val pending = Entry(before.identity, before.label, original, true, if (hide) TargetState.PENDING_HIDE else if (show) TargetState.PENDING_SHOW else TargetState.PENDING_RESTORE, now(), firmware.key, boot(), old?.changedByUs ?: false, error = "", invariantDigest=before.invariantDigest(),sharedUserId=before.sharedUserId)
        durable(pending) // Mandatory before the first possible system mutation.
        val operation = Verification(before, pending, wanted, hide, before.bit != wanted, show)
        if (!operation.wrote) return Started.Checking(operation)
        // Close the window opened by durable IO; never write against an outdated preflight.
        val immediate = port.read(pkg)
        observe("preflight", immediate, false)
        if(before.peers!=immediate.peers || before.sharedUserId!=immediate.sharedUserId)return Started.Finished(durable(pending.copy(state=TargetState.SHARED_UID_CHANGED,error=OwnershipChangedException().message!!)))
        if (!before.invariantEquals(immediate, before.bit)) return Started.Finished(durable(pending.copy(state = TargetState.FAILED_SAFE, error = "적용 직전 앱 상태가 바뀌어 변경하지 않았습니다.")))
        if (!canStart()) return Started.Finished(durable(pending.copy(state=TargetState.FAILED_SAFE,error="화면을 떠나 남은 앱은 변경하지 않았습니다. 완료된 적용과 등록 기록은 유지됩니다.")))
        return try {
            port.writeFineBitChecked(immediate,wanted)
            Started.Checking(operation)
        } catch (failure: Exception) { Started.Finished(failed(operation,failure)) }
    }
    private data class Verification(val before:Snapshot,val pending:Entry,val wanted:Int,val hide:Boolean,val wrote:Boolean,val show:Boolean=false)
    private sealed interface Started {
        data class Finished(val entry:Entry):Started
        data class Checking(val operation:Verification):Started
    }
    private fun execute(pkg:String,hide:Boolean,canStart:()->Boolean={true},show:Boolean=false):Entry = when(val started=prepare(pkg,hide,canStart,show=show)) {
        is Started.Finished -> started.entry
        is Started.Checking -> try {
            val operation=started.operation
            verifySettled(operation.before,operation.wanted,operation.wrote)
            finished(operation)
        } catch(failure:Exception) { failed(started.operation,failure) }
    }
    // A verified "표시 켜기" leaves the app unregistered; ON, its actual state now, is what a later hide restores to.
    private fun finished(operation:Verification):Entry = durable(if(operation.show)operation.pending.copy(enabled=false,state=TargetState.NOT_APPLIED,
        originalBit=SENSITIVITY_BIT,changedByUs=false,lastVerifiedTime=now(),settlingVerified=true) else operation.pending.copy(
        enabled=operation.hide,state=if(operation.hide)TargetState.APPLIED else TargetState.NOT_APPLIED,
        changedByUs=operation.hide && (operation.wrote || operation.pending.changedByUs),lastVerifiedTime=now(),settlingVerified=true))
    private fun failed(operation:Verification,failure:Exception):Entry {
        // A storage fault leaves durable PENDING records intact. No additional setter
        // or optimistic success is allowed when a final journal commit fails.
        if(failure is JournalPersistenceException)throw failure
        val before=operation.before;val pending=operation.pending;val original=pending.originalBit
        if(!operation.wrote)return durable(pending.copy(state=if(failure is OwnershipChangedException)TargetState.NEEDS_REVIEW else TargetState.FAILED_SAFE,
            error=if(failure is OwnershipChangedException)failure.message!! else "표시 상태가 유지되는지 확인하지 못했어요. 다시 확인해 주세요."))
        // Never use an old install's bit on a replaced package. Rollback touches only 0x100.
        var ownershipChanged=failure is OwnershipChangedException
        val restored = try {
            val current = port.read(before.identity.packageName)
            ownershipChanged=ownershipChanged || current.peers!=before.peers || current.sharedUserId!=before.sharedUserId
            if (ownershipChanged || current.identity != before.identity) false
            else {
                if (current.bit != original) port.writeFineBitChecked(current,original)
                before.invariantEquals(port.read(before.identity.packageName), original)
            }
        } catch (rollbackFailure: Exception) { if(rollbackFailure is OwnershipChangedException)ownershipChanged=true;false }
        return durable(pending.copy(state=if(ownershipChanged)TargetState.NEEDS_REVIEW else if(restored)TargetState.FAILED_SAFE else TargetState.FAILED_ROLLBACK,
            error=if(ownershipChanged)"이 앱과 묶인 앱 구성이 바뀌어 변경을 멈췄습니다." else if(restored)"변경 후 상태를 확인하지 못해 원래 표시 상태로 되돌렸어요. 다시 확인해 주세요." else "복원을 확인하지 못했습니다. 추가 변경을 차단했습니다.",changedByUs=!restored))
    }
    private fun checkSettled(before:Snapshot,wanted:Int,wrote:Boolean,phase:String) {
        val current=port.read(before.identity.packageName)
        observe(phase,current,wrote)
        if(before.peers!=current.peers || before.sharedUserId!=current.sharedUserId)throw OwnershipChangedException()
        if(!before.invariantEquals(current,wanted) || (wrote && (before.fineFlags xor current.fineFlags)!=SENSITIVITY_BIT))throw SafetyException("표시 설정의 지속성과 안전성 확인 실패")
    }
    /** One worker, one global owner, serialized fresh getters/setters. Only waits overlap.
     * A bounded window drains every started record before its helper can close. */
    fun userHideWindow(packages:List<String>,canStart:()->Boolean={true},elapsed:()->Long={System.nanoTime()/1_000_000},
        onResult:(BatchResult)->Unit={}):HideWindowOutcome = userWindow(packages,true,canStart,elapsed,onResult)
    fun userShowWindow(packages:List<String>,canStart:()->Boolean={true},elapsed:()->Long={System.nanoTime()/1_000_000},onResult:(BatchResult)->Unit={}):HideWindowOutcome = userWindow(packages,false,canStart,elapsed,onResult,show=true)
    fun userRestoreWindow(packages:List<String>,canStart:()->Boolean={true},elapsed:()->Long={System.nanoTime()/1_000_000},onResult:(BatchResult)->Unit={}):HideWindowOutcome = userWindow(packages,false,canStart,elapsed,onResult)
    private fun userWindow(packages:List<String>,hide:Boolean,canStart:()->Boolean,elapsed:()->Long,onResult:(BatchResult)->Unit,show:Boolean=false):HideWindowOutcome = synchronized(writeLock) {
        require(packages.isNotEmpty() && packages.size<=BatchWindow.MAX_TARGETS && packages.distinct()==packages)
        data class Scheduled(val operation:Verification,var phase:Int=1,var due:Long)
        val waiting=mutableListOf<Scheduled>()
        val results=linkedMapOf<String,BatchResult>()
        val start=elapsed();var cursor=0;var stopped=false;var reason="";var storageFailed=false;var callbackFailed=false
        fun publish(result:BatchResult) {
            results[result.packageName]=result
            if(!result.success){stopped=true;if(reason.isBlank())reason=result.error}
            if(!callbackFailed)try{onResult(result)}catch(_:Exception){callbackFailed=true;stopped=true;reason="작업 결과를 확인하지 못했습니다. 등록 기록을 유지했습니다."}
        }
        fun result(pkg:String,entry:Entry)=BatchResult(pkg,entry,if(entry.state in listOf(TargetState.APPLIED,TargetState.NOT_APPLIED))"" else entry.error.ifBlank{"현재 상태 확인 필요"})
        fun failure(operation:Verification,error:Exception) {
            try { publish(result(operation.before.identity.packageName,failed(operation,error))) }
            catch(storage:JournalPersistenceException){storageFailed=true;publish(BatchResult(operation.before.identity.packageName,operation.pending,storage.message!!))}
        }
        while(cursor<packages.size || waiting.isNotEmpty()) {
            if(!stopped && !canStart()){stopped=true;reason="화면을 떠나 남은 앱은 변경하지 않았습니다. 완료된 적용과 등록 기록은 유지됩니다."}
            if(storageFailed){
                waiting.toList().forEach{publish(BatchResult(it.operation.before.identity.packageName,it.operation.pending,JournalPersistenceException().message!!))}
                waiting.clear();break
            }
            val due=waiting.minByOrNull{it.due}
            if(due!=null && due.due<=elapsed()) {
                try {
                    val operation=due.operation
                    checkSettled(operation.before,operation.wanted,operation.wrote,if(due.phase==1)"settled_500ms" else "settled_1500ms")
                    if(due.phase==1){due.phase=2;due.due=elapsed()+1000}
                    else {waiting.remove(due);publish(result(operation.before.identity.packageName,finished(operation)))}
                }catch(error:Exception){waiting.remove(due);failure(due.operation,error)}
                continue
            }
            if(!stopped && cursor<packages.size && waiting.size<HideWindowOutcome.MAX_IN_FLIGHT && BatchWindow.next(cursor,elapsed()-start)) {
                val pkg=packages[cursor++]
                try {
                    when(val prepared=prepare(pkg,hide,canStart,waiting.map{it.operation.before.identity.uid}.toSet(),show)) {
                        is Started.Finished -> publish(result(pkg,prepared.entry))
                        is Started.Checking -> {
                            val operation=prepared.operation
                            try {
                                checkSettled(operation.before,operation.wanted,operation.wrote,"immediate")
                                waiting.add(Scheduled(operation,due=elapsed()+500))
                            }catch(error:Exception){failure(operation,error)}
                        }
                    }
                }catch(error:Exception){if(error is JournalPersistenceException)storageFailed=true;publish(BatchResult(pkg,error=error.message?:"연결 또는 현재 상태 확인 필요"))}
                continue
            }
            if(waiting.isEmpty())break
            try{wait((waiting.minOf{it.due}-elapsed()).coerceAtLeast(1))}
            catch(error:Exception){
                stopped=true;reason="작업 결과를 확인하지 못했습니다. 등록 기록을 유지했습니다."
                waiting.toList().forEach{
                    if(storageFailed)publish(BatchResult(it.operation.before.identity.packageName,it.operation.pending,JournalPersistenceException().message!!))
                    else failure(it.operation,error)
                }
                waiting.clear()
            }
        }
        HideWindowOutcome(packages.take(cursor).map{results.getValue(it)},cursor,stopped,reason)
    }
    private fun verifySettled(before: Snapshot, wanted: Int, wrote: Boolean) {
        for ((phase, delayMs) in listOf("immediate" to 0L, "settled_500ms" to 500L, "settled_1500ms" to 1000L)) {
            if (delayMs != 0L) wait(delayMs)
            checkSettled(before,wanted,wrote,phase)
        }
    }
}

object Reconcile {
    private fun review(entry:Entry)=entry.state in listOf(TargetState.FAILED_ROLLBACK,TargetState.SHARED_UID_CHANGED,TargetState.NEEDS_REVIEW)
    private fun pending(entry: Entry) = entry.state in listOf(TargetState.PENDING_HIDE,TargetState.PENDING_RESTORE,TargetState.PENDING_SHOW)
    fun boot(entry: Entry) = if (entry.enabled && !review(entry) && !pending(entry)) entry.copy(state = TargetState.NEEDS_REAPPLY_BOOT) else entry
    fun update(entry: Entry) = if (entry.enabled && !review(entry) && !pending(entry)) entry.copy(state = TargetState.NEEDS_REAPPLY_UPDATE) else entry
    /** A lock whose app is verifiably back at its original (same install, owner, firmware and other permission state):
     *  nothing is uncertain any more. It drops to "다시 확인 필요" without a write; from there the usual rules apply
     *  (an update since then makes it "재적용 필요", as after any failed check).
     *  Otherwise the lock had no way out in the app (QA, 2026-10-18: an update landed during the checks). */
    private fun original(entry:Entry,s:Snapshot?,firmware:String)=entry.state==TargetState.FAILED_ROLLBACK&&s!=null&&entry.firmware==firmware&&
        entry.identity.sameInstall(s.identity)&&entry.sharedUserId==s.sharedUserId&&s.peers==listOf(s.identity.packageName)&&
        s.bit==entry.originalBit&&entry.invariantDigest.isNotBlank()&&entry.invariantDigest==s.invariantDigest()
    fun read(entry: Entry, snapshot: Snapshot?, firmware: String, bootSession: String,all:Map<String,Snapshot?> = emptyMap()): Entry {
        if(entry.groupIdentities.isNotEmpty())return SharedGroupReconcile.read(entry,all,firmware,bootSession)
        if (review(entry)) return if(original(entry,snapshot,firmware))entry.copy(state=TargetState.FAILED_SAFE,changedByUs=false,error="변경 후 상태를 확인하지 못해 원래 표시 상태로 되돌렸어요. 다시 확인해 주세요.") else entry
        if (entry.firmware != firmware) return if(pending(entry)) entry.copy(error="기기 업데이트 후 중단된 작업의 안전성을 다시 확인해야 합니다.") else entry.copy(state = TargetState.NEEDS_RECHECK_FIRMWARE)
        if (snapshot == null) return entry.copy(state = TargetState.MISSING_TARGET)
        if (!entry.identity.sameInstall(snapshot.identity)) return entry.copy(state = TargetState.INACTIVE_INSTALL, enabled = false)
        if(entry.sharedUserId!=null && (snapshot.peers!=listOf(snapshot.identity.packageName)||entry.sharedUserId!=snapshot.sharedUserId))return entry.copy(state=TargetState.SHARED_UID_CHANGED,error="이 앱과 묶인 앱 구성이 바뀌어 변경을 멈췄습니다.")
        if (pending(entry) && (entry.invariantDigest.isBlank() || entry.invariantDigest != snapshot.invariantDigest())) return entry.copy(state=TargetState.FAILED_ROLLBACK,error="중단된 작업의 안전성을 확인하지 못했습니다. 추가 변경을 차단했습니다.")
        if (snapshot.peers != listOf(snapshot.identity.packageName)) return entry.copy(state = TargetState.SHARED_UID_BLOCKED)
        if (!snapshot.safe()) return entry.copy(state = TargetState.UNSUPPORTED_STATE)
        if (!entry.enabled) return entry
        if (!pending(entry) && (entry.identity.versionCode != snapshot.identity.versionCode || entry.identity.lastUpdateTime != snapshot.identity.lastUpdateTime || entry.state == TargetState.NEEDS_REAPPLY_UPDATE)) return entry.copy(state = TargetState.NEEDS_REAPPLY_UPDATE)
        if (entry.invariantDigest.isBlank() || entry.invariantDigest != snapshot.invariantDigest()) return entry.copy(state=TargetState.UNSUPPORTED_STATE,error="권한 상태와 이전 안전성 기록이 일치하지 않습니다. 직접 확인해 주세요.")
        // An interrupted "표시 켜기" (app closed mid-way): the invariants still match, so whether the app is ON (target
        // reached) or still OFF, that is its real state. It is unregistered with that state as its value from now on.
        if (entry.state == TargetState.PENDING_SHOW) return entry.copy(state = TargetState.NOT_APPLIED, enabled = false, changedByUs = false, originalBit = snapshot.bit)
        if (entry.state == TargetState.PENDING_RESTORE && snapshot.bit == entry.originalBit) return entry.copy(state = TargetState.NOT_APPLIED, enabled = false, changedByUs = false)
        if (entry.state == TargetState.PENDING_HIDE) return entry.copy(state = TargetState.FAILED_SAFE, error = "중단된 적용의 지속성을 확인하지 못했습니다. 직접 다시 확인해 주세요.")
        if (entry.state == TargetState.FAILED_SAFE) return entry
        if (!entry.settlingVerified) return entry.copy(state=TargetState.FAILED_SAFE,error="숨김 유지 여부를 다시 확인해야 해요. 재적용을 눌러주세요.")
        // An event marker stays conservative even if early boot/update temporarily still reads OFF.
        if (entry.bootSession != bootSession || entry.state == TargetState.NEEDS_REAPPLY_BOOT) return entry.copy(state = TargetState.NEEDS_REAPPLY_BOOT)
        if (entry.identity.versionCode != snapshot.identity.versionCode || entry.identity.lastUpdateTime != snapshot.identity.lastUpdateTime || entry.state == TargetState.NEEDS_REAPPLY_UPDATE) return entry.copy(state = TargetState.NEEDS_REAPPLY_UPDATE)
        return entry.copy(state = if (snapshot.bit == 0) TargetState.APPLIED else TargetState.NEEDS_REAPPLY_POLICY)
    }
}

/** Access timestamps are display data, never permission invariants or synthetic recency. */
object CandidateOrder {
    fun sort(items: List<Snapshot>, historyAvailable: Boolean): List<Snapshot> = items.sortedWith(
        if (historyAvailable) compareByDescending<Snapshot> { it.lastLocationAccess ?: Long.MIN_VALUE }.thenBy { it.label.lowercase() }.thenBy { it.identity.packageName }
        else compareBy<Snapshot> { it.label.lowercase() }.thenBy { it.identity.packageName }
    )
}
