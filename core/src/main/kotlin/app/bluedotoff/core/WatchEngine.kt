package app.bluedotoff.core

/** Same engines/journal/settling checks as foreground work; fresh registration is authoritative on every event. */
class WatchEngine(private val port:TargetPort,private val journal:Journal,private val now:()->Long,private val boot:()->String,
    private val wait:(Long)->Unit={Thread.sleep(it)},private val observe:(String,Snapshot,Boolean)->Unit={_,_,_->},
    private val onSkip:(List<String>,String)->Unit={_,_->}) {
    fun reapply(packages:Set<String>,canStart:()->Boolean,onUnhidden:(String)->Unit,onResult:(BatchResult)->Unit)=synchronized(SafetyEngine.writeLock) {
        val firmware=port.compatibleFirmware()
        for(unit in WatchPolicy.units(journal.entries(),packages)) {
            if(!canStart())break
            // Do not trust the queued event's registration list, identity, version or flags.
            val names=unit.map{it.identity.packageName}
            val latest=WatchPolicy.units(journal.entries(),names.toSet()).firstOrNull{it.map{e->e.identity.packageName}==names}?:continue
            try {
                val fresh=latest.map{port.read(it.identity.packageName)}
                val currentBoot=boot()
                val snapshots=fresh.associateBy{it.identity.packageName}
                val reconciled=latest.map{Reconcile.read(it,snapshots[it.identity.packageName],firmware.key,currentBoot,snapshots)}
                if(reconciled!=latest){
                    val saved=if(reconciled.size==1)journal.commit(reconciled.single()) else journal.commitGroup(reconciled)
                    if(!saved)throw JournalPersistenceException()
                }
                val skip=WatchPolicy.skipReason(latest,fresh,firmware,currentBoot)
                if(skip!=null){onSkip(names,skip);continue}
                if(!canStart())continue
                fresh.filter{it.bit==SENSITIVITY_BIT}.forEach{onUnhidden(it.identity.packageName)}
                val results=hide(names,fresh,reconciled,firmware,canStart)
                results.forEach{e->onResult(BatchResult(e.identity.packageName,e,if(e.state==TargetState.APPLIED)"" else e.error.ifBlank{"WATCH_VERIFICATION_FAILED"}))}
                if(results.any{it.state!=TargetState.APPLIED})break
            }catch(e:Exception){names.forEach{onResult(BatchResult(it,error=e.message?:"WATCH_FAILED"))};break}
        }
    }
    /** Right after an app update Android can reset the flag again while the write is being verified (Toss on the Fold7,
     *  2026-10-18: two re-hides, the third rolled back and stayed locked). If every app is then exactly as it was before
     *  the write, nothing else changed: wait, re-check with the watch rules and try again, at most 3 more times.
     *  Only when the write itself went through and a later check failed (a setter error is not retried). Groups alike. */
    private fun hide(names:List<String>,before:List<Snapshot>,records:List<Entry>,firmware:Firmware,canStart:()->Boolean):List<Entry> {
        var checked=false
        val watched:(String,Snapshot,Boolean)->Unit={phase,s,wrote->if(wrote)checked=true;observe(phase,s,wrote)}
        val run={if(names.size==1)listOf(SafetyEngine(port,journal,now,boot,wait,watched).userOne(names.single(),true,canStart))
            else SharedGroupEngine(port,journal,now,boot,wait,watched).execute(names,true,canStart)}
        // The apps' location use may tick between reads; everything else must match the pre-write read.
        fun unchanged(current:List<Snapshot>)=current.zip(before).all{(s,b)->s.copy(lastLocationAccess=b.lastLocationAccess)==b}
        var results=run()
        repeat(3){
            if(!checked||results.any{it.state!=TargetState.FAILED_SAFE}||!unchanged(names.map(port::read)))return results
            wait(5_000)
            val again=names.map(port::read)
            if(!canStart()||!unchanged(again)||WatchPolicy.skipReason(records,again,firmware,boot())!=null)return results
            if(!(if(records.size==1)journal.commit(records.single()) else journal.commitGroup(records)))throw JournalPersistenceException()
            checked=false
            results=run()
        }
        return results
    }
}
