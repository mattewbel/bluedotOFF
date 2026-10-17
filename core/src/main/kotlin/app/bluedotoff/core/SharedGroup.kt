package app.bluedotoff.core

/** An explicit whole-UID transaction. The individual write gate remains closed. */
object SharedGroupPolicy {
    /** No member may use precise location: the group never shows the blue indicator, so it is skipped silently. */
    const val NO_LOCATION="정확한 위치 권한이 허용된 앱이 묶음에 없습니다."
    const val MAX_MEMBERS=128
    const val STANDARD_MAX_MEMBERS=8
    fun validate(members:List<Snapshot>) {
        val scope=members.firstOrNull()?.applicationScope?:ApplicationScope.GROUPS
        if(scope==ApplicationScope.NORMAL)throw SafetyException("일반 적용에서는 공유 앱 묶음을 선택할 수 없어요.")
        require(members.size in 2..if(scope==ApplicationScope.ALL)MAX_MEMBERS else STANDARD_MAX_MEMBERS){"공유 앱 묶음의 크기를 확인할 수 없습니다."}
        val names=members.map{it.identity.packageName}.sorted()
        val first=members.first()
        if(names.distinct()!=names||first.sharedUserId.isNullOrBlank())throw OwnershipChangedException()
        for(s in members){
            if(!s.peersAuthoritative||s.peers.sorted()!=names||s.identity.uid!=first.identity.uid||s.sharedUserId!=first.sharedUserId)throw OwnershipChangedException()
            if(s.applicationScope!=scope||!ScopePolicy.includes(s,scope)||s.identity.signers.isEmpty()||s.identity.firstInstallTime<=0||s.identity.versionCode<=0)throw SafetyException("핵심 시스템 또는 설치 정보가 불확실한 앱이 묶음에 포함돼 변경할 수 없습니다.")
            if(s.fineFlags<0||s.coarseFlags<0||s.fineFlags!=first.fineFlags||s.coarseFlags!=first.coarseFlags)throw SafetyException("공유 앱의 위치 표시 설정이 서로 달라 변경하지 않았습니다.")
            // A companion that never declares location is retained in the invariant
            // and identity checks; inventing a grant for it would be incorrect.
            if(s.fineMode !in 0..4||s.coarseMode !in 0..4)throw SafetyException("공유 앱의 위치 사용 상태를 확인하지 못했습니다.")
            // In high scope, a deliberately inactive core companion is pinned,
            // not granted or allowed. One genuinely usable member is mandatory.
            if(scope!=ApplicationScope.ALL&&s.fineDeclared&&(!s.fineGranted||!s.coarseDeclared||!s.coarseGranted||s.fineMode !in listOf(0,4)||s.coarseMode !in listOf(0,4)))throw SafetyException("공유 앱의 위치 권한 상태를 안전하게 확인하지 못했습니다.")
        }
        if(members.none{it.fineDeclared&&it.fineGranted&&it.coarseDeclared&&it.coarseGranted&&it.fineMode in listOf(0,4)&&it.coarseMode in listOf(0,4)})throw SafetyException(NO_LOCATION)
    }
    fun representative(members:List<Snapshot>)=members.first{it.fineDeclared&&it.fineGranted&&it.coarseDeclared&&it.coarseGranted&&it.fineMode in listOf(0,4)&&it.coarseMode in listOf(0,4)}
    fun same(before:List<Snapshot>,after:List<Snapshot>,wanted:Int)=before.size==after.size&&before.zip(after).all{(a,b)->a.invariantEquals(b,wanted)}
    fun ownership(before:List<Snapshot>,after:List<Snapshot>)=before.size==after.size&&before.zip(after).all{(a,b)->a.identity==b.identity&&a.peers==b.peers&&a.sharedUserId==b.sharedUserId}
}

/** One fsynced group record per member, committed atomically before a single UID setter. */
class SharedGroupEngine(private val port:TargetPort,private val journal:Journal,private val now:()->Long,private val boot:()->String,
    private val wait:(Long)->Unit={Thread.sleep(it)},private val observe:(String,Snapshot,Boolean)->Unit={_,_,_->}) {
    private fun durable(entries:List<Entry>){val saved=try{journal.commitGroup(entries)}catch(_:Exception){false};if(!saved)throw JournalPersistenceException()}
    fun execute(packages:List<String>,hide:Boolean,canStart:()->Boolean):List<Entry> = synchronized(SafetyEngine.writeLock) {
        require(packages.distinct().size==packages.size)
        val fw=port.compatibleFirmware()
        if(!fw.osSupported||fw.abiProfile.isBlank())throw SafetyException("기기 호환성을 확인하지 못했습니다.")
        val names=packages.sorted();val before=names.map(port::read);SharedGroupPolicy.validate(before)
        before.forEach{observe("before",it,false)}
        val identities=before.map{it.identity}
        val all=journal.entries();val old=names.map{p->all.lastOrNull{it.identity.packageName==p&&it.identity.userId==0}
            // B-10: same rule as single apps (SafetyEngine.prepare): a restored, unregistered record or an old install's record
            // does not block registering the group again; the fresh read is the original.
            ?.takeUnless{!it.enabled&&(it.state==TargetState.INACTIVE_INSTALL||hide&&it.state==TargetState.NOT_APPLIED)}}
        if(old.any{it!=null}&&old.any{it==null})throw SafetyException("묶음 일부에만 등록 기록이 있어 변경하지 않았습니다.")
        if(!hide&&old.any{it==null})throw SafetyException("복원할 공유 앱 묶음 기록이 없습니다.")
        old.filterNotNull().forEachIndexed{i,e->
            if(e.groupIdentities.map{it.packageName}!=names||e.groupIdentities.size!=identities.size||e.groupIdentities.zip(identities).any{(a,b)->!a.sameInstall(b)}||!e.identity.sameInstall(identities[i])||e.sharedUserId!=before[i].sharedUserId)throw OwnershipChangedException()
            if(e.state in setOf(TargetState.FAILED_ROLLBACK,TargetState.NEEDS_REVIEW,TargetState.SHARED_UID_CHANGED,TargetState.PENDING_HIDE,TargetState.PENDING_RESTORE,TargetState.PENDING_SHOW))throw SafetyException("공유 앱 묶음의 복원 확인이 필요합니다.")
            if(e.firmware.substringAfterLast('|')!=fw.abiProfile)throw SafetyException("기기 업데이트 후 호환성 재확인이 필요합니다.")
        }
        val originals=old.filterNotNull().map{it.originalBit}.distinct()
        if(originals.size>1)throw SafetyException("공유 앱의 원래 표시 기록이 서로 달라 변경하지 않았습니다.")
        val original=originals.firstOrNull()?:before.first().bit
        val wanted=if(hide)0 else original
        val pending=before.mapIndexed{i,s->Entry(s.identity,s.label,original,true,if(hide)TargetState.PENDING_HIDE else TargetState.PENDING_RESTORE,now(),fw.key,boot(),old[i]?.changedByUs?:false,invariantDigest=s.invariantDigest(),sharedUserId=s.sharedUserId,groupIdentities=identities)}
        durable(pending)
        var attempted=false
        try {
            val preflight=names.map(port::read)
            preflight.forEach{observe("preflight",it,false)}
            if(!SharedGroupPolicy.ownership(before,preflight))throw OwnershipChangedException()
            if(!SharedGroupPolicy.same(before,preflight,before.first().bit))throw SafetyException("적용 직전 공유 앱 상태가 바뀌었습니다.")
            if(!canStart())throw SafetyException("화면을 떠나 공유 앱 묶음은 변경하지 않았습니다.")
            if(before.first().bit!=wanted){attempted=true;port.writeFineGroupChecked(preflight,wanted)}
            for((phase,ms) in listOf("immediate" to 0L,"settled_500ms" to 500L,"settled_1500ms" to 1000L)){
                if(ms>0)wait(ms)
                val current=names.map(port::read)
                current.forEach{observe(phase,it,attempted)}
                if(!SharedGroupPolicy.ownership(before,current))throw OwnershipChangedException()
                if(!SharedGroupPolicy.same(before,current,wanted))throw SafetyException("공유 앱 묶음의 지속성 확인에 실패했습니다.")
            }
            val finished=pending.map{it.copy(enabled=hide,state=if(hide)TargetState.APPLIED else TargetState.NOT_APPLIED,settlingVerified=true,changedByUs=hide&&(attempted||it.changedByUs),lastVerifiedTime=now())}
            durable(finished);finished
        }catch(e:Exception){
            if(e is JournalPersistenceException)throw e
            var changed=e is OwnershipChangedException
            val restored=if(!attempted)true else try{
                val fresh=names.map(port::read)
                changed=changed||!SharedGroupPolicy.ownership(before,fresh)
                if(changed)false else {
                    SharedGroupPolicy.validate(fresh)
                    if(!SharedGroupPolicy.same(before,fresh,fresh.first().bit))false
                    else {if(fresh.first().bit!=original)port.writeFineGroupChecked(fresh,original);SharedGroupPolicy.same(before,names.map(port::read),original)}
                }
            }catch(_:Exception){false}
            val state=if(changed)TargetState.NEEDS_REVIEW else if(restored)TargetState.FAILED_SAFE else TargetState.FAILED_ROLLBACK
            val failed=pending.map{it.copy(state=state,error=e.message?:"공유 앱 묶음을 확인해야 해요.")}
            durable(failed);failed
        }
    }
    /** B-1 (owner decision 2026-10-17): settle a group left PENDING by an interruption (app killed or the last journal
     *  save failed), only when the app is opened. Every member must still be the recorded install with the recorded
     *  ownership, firmware and other permission state, and the recorded originals must agree; otherwise it locks
     *  (NEEDS_REVIEW) without writing. The recorded original is written back with the usual checks. An interrupted hide
     *  stays registered as "reapply needed" (the reapply-on-open retries it); an interrupted restore finishes as restored. */
    fun recover(packages:List<String>):List<Entry> = synchronized(SafetyEngine.writeLock) {
        val names=packages.sorted()
        val records=names.map{p->journal.entries().lastOrNull{it.identity.packageName==p&&it.identity.userId==0}}
        val pendingStates=setOf(TargetState.PENDING_HIDE,TargetState.PENDING_RESTORE)
        if(records.any{it==null||it.state !in pendingStates})return records.filterNotNull()
        val pending=records.filterNotNull()
        val fw=port.compatibleFirmware()
        val current=names.map(port::read);current.forEach{observe("before",it,false)}
        val identities=pending.first().groupIdentities
        val originals=pending.map{it.originalBit}.distinct()
        val same=fw.osSupported&&pending.all{it.firmware.substringAfterLast('|')==fw.abiProfile}&&
            pending.all{it.groupIdentities==identities}&&identities.map{it.packageName}==names&&
            pending.zip(current).all{(e,s)->e.identity.sameInstall(s.identity)&&s.peers.sorted()==names&&s.sharedUserId==e.sharedUserId&&
                e.invariantDigest.isNotBlank()&&e.invariantDigest==s.invariantDigest()}&&
            originals.size==1&&current.map{it.fineFlags}.distinct().size==1
        if(!same){
            val locked=pending.map{it.copy(state=TargetState.NEEDS_REVIEW,error="중단된 공유 앱 묶음의 상태가 기록과 달라 변경하지 않았습니다.")}
            durable(locked);return locked
        }
        val original=originals.single();var wrote=false
        try{
            if(current.first().bit!=original){wrote=true;port.writeFineGroupChecked(current,original)}
            for(ms in listOf(0L,500L,1000L)){
                if(ms>0)wait(ms)
                val now=names.map(port::read);now.forEach{observe("recover",it,wrote)}
                if(!SharedGroupPolicy.ownership(current,now)||!SharedGroupPolicy.same(current,now,original))throw SafetyException("recover")
            }
        }catch(e:Exception){
            if(e is JournalPersistenceException)throw e
            val failed=pending.map{it.copy(state=if(wrote)TargetState.FAILED_ROLLBACK else TargetState.NEEDS_REVIEW,error="중단된 공유 앱 묶음을 원래대로 되돌렸는지 확인하지 못했습니다. 추가 변경을 차단했습니다.")}
            durable(failed);return failed
        }
        val hide=pending.first().state==TargetState.PENDING_HIDE
        // An app update before the app was opened changes version fields only (sameInstall above); the group record
        // must carry the same fresh identities or the journal refuses it (review round 2, 2026-10-17).
        val fresh=current.map{it.identity}
        val settled=pending.zip(current).map{(e,s)->e.copy(identity=s.identity,groupIdentities=fresh,enabled=hide,state=if(hide)TargetState.NEEDS_REAPPLY_POLICY else TargetState.NOT_APPLIED,
            settlingVerified=true,lastVerifiedTime=now(),error=if(hide)INTERRUPTED else "")}
        durable(settled);settled
    }
    companion object { const val INTERRUPTED="중간에 끊겨서 원래대로 돌려놨어요. 다시 적용해 주세요." }
}

object SharedGroupReconcile {
    fun read(e:Entry,all:Map<String,Snapshot?>,firmware:String,boot:String):Entry {
        // B-10: not registered (enabled=false; registered or interrupted records are enabled) and a member is gone or
        // reinstalled -> an old install's record, like a single app's INACTIVE_INSTALL. Registered groups keep every check.
        // If this record's own app is uninstalled it is MISSING_TARGET like a single app (not listed as "새 설치").
        if(!e.enabled&&e.state in setOf(TargetState.NOT_APPLIED,TargetState.INACTIVE_INSTALL,TargetState.NEEDS_REVIEW,TargetState.UNSUPPORTED_STATE,TargetState.MISSING_TARGET)&&
            e.groupIdentities.any{id->all[id.packageName]?.identity?.let{!id.sameInstall(it)}?:true})
            return e.copy(state=if(all[e.identity.packageName]==null)TargetState.MISSING_TARGET else TargetState.INACTIVE_INSTALL)
        if(e.state in setOf(TargetState.NEEDS_REVIEW,TargetState.FAILED_ROLLBACK,TargetState.SHARED_UID_CHANGED,TargetState.FAILED_SAFE))return e
        val members=e.groupIdentities.map{all[it.packageName]?:return e.copy(state=TargetState.NEEDS_REVIEW,error="공유 앱 묶음의 전체 설치 정보를 확인하지 못했습니다.")}
        if(e.groupIdentities.zip(members).any{(id,s)->!id.sameInstall(s.identity)})return e.copy(state=TargetState.NEEDS_REVIEW,error="공유 앱 묶음의 설치 정보가 바뀌었습니다.")
        if(members.any{it.peers.sorted()!=e.groupIdentities.map{p->p.packageName}||it.sharedUserId!=e.sharedUserId})return e.copy(state=TargetState.NEEDS_REVIEW,error=OwnershipChangedException().message!!)
        if(e.firmware!=firmware)return e.copy(state=TargetState.NEEDS_RECHECK_FIRMWARE)
        // B-1: an interrupted group stays PENDING (not changeable by the watch or a batch) until SharedGroupEngine.recover
        // settles it when the app is opened. Identity/ownership changes above still lock it.
        if(e.state in setOf(TargetState.PENDING_HIDE,TargetState.PENDING_RESTORE,TargetState.PENDING_SHOW))return e
        if(!e.enabled)return e
        if(e.groupIdentities.zip(members).any{(id,s)->id!=s.identity})return e.copy(state=TargetState.NEEDS_REAPPLY_UPDATE)
        try{SharedGroupPolicy.validate(members)}catch(_:Exception){return e.copy(state=TargetState.UNSUPPORTED_STATE)}
        val own=members.single{it.identity.packageName==e.identity.packageName}
        if(e.invariantDigest!=own.invariantDigest())return e.copy(state=TargetState.UNSUPPORTED_STATE)
        if(!e.settlingVerified)return e.copy(state=TargetState.FAILED_SAFE)
        if(e.bootSession!=boot||e.state==TargetState.NEEDS_REAPPLY_BOOT)return e.copy(state=TargetState.NEEDS_REAPPLY_BOOT)
        return e.copy(state=if(own.bit==0)TargetState.APPLIED else TargetState.NEEDS_REAPPLY_POLICY)
    }
}
