package app.bluedotoff.core

/** Same engines/journal/settling checks as foreground work; fresh registration is authoritative on every event. */
class WatchEngine(private val port:TargetPort,private val journal:Journal,private val now:()->Long,private val boot:()->String,
    private val wait:(Long)->Unit={Thread.sleep(it)},private val observe:(String,Snapshot,Boolean)->Unit={_,_,_->}) {
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
                if(!WatchPolicy.mayReapply(latest,fresh,firmware,currentBoot)||!canStart())continue
                fresh.filter{it.bit==SENSITIVITY_BIT}.forEach{onUnhidden(it.identity.packageName)}
                val results=if(latest.size==1)listOf(SafetyEngine(port,journal,now,boot,wait,observe).userOne(names.single(),true,canStart))
                    else SharedGroupEngine(port,journal,now,boot,wait,observe).execute(names,true,canStart)
                results.forEach{e->onResult(BatchResult(e.identity.packageName,e,if(e.state==TargetState.APPLIED)"" else e.error.ifBlank{"WATCH_VERIFICATION_FAILED"}))}
                if(results.any{it.state!=TargetState.APPLIED})break
            }catch(e:Exception){names.forEach{onResult(BatchResult(it,error=e.message?:"WATCH_FAILED"))};break}
        }
    }
}
