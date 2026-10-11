package app.bluedotoff.core

/** Display handoff only; never a substitute for SafetyEngine's fresh target reads. */
data class PreparedStartupScan(val firmware:Firmware,val boot:String,val entries:List<Entry>,val snapshots:List<Snapshot>,val discovery:DiscoveryResult)

object StartupScan {
    fun read(verify:()->Firmware,entries:()->List<Entry>,
             discover:((Snapshot)->Unit)->DiscoveryResult,read:(String)->Snapshot?,
             reconcile:(Map<String,Snapshot?>,Firmware)->List<Entry>,boot:String,
             current:()->Boolean):PreparedStartupScan {
        fun checkCurrent(){if(!current())throw java.util.concurrent.CancellationException("앱 확인이 중단됐어요. 다시 연결해주세요.")}
        checkCurrent()
        val firmware=verify()
        check(firmware.osSupported&&firmware.abiProfile.isNotBlank()){"지원 여부를 확인하지 못했어요."}
        checkCurrent()
        val registered=entries().map{it.identity.packageName}.toSet()
        val snapshots=linkedMapOf<String,Snapshot?>()
        val result=discover{snapshot->checkCurrent();if(snapshot.identity.packageName in registered)snapshots[snapshot.identity.packageName]=snapshot}
        checkCurrent()
        // Discovery already performs full reads for relevant apps. Read only missing
        // registered snapshots; absent/failed targets keep the existing failure path.
        for(pkg in registered){checkCurrent();if(pkg !in snapshots)snapshots[pkg]=read(pkg)}
        checkCurrent()
        val reconciled=reconcile(snapshots,firmware)
        checkCurrent()
        return PreparedStartupScan(firmware,boot,reconciled.toList(),snapshots.values.filterNotNull(),result)
    }
}

/** One process, one successful handoff, at most 60 seconds. Nothing is serialized. */
class StartupScanCache(private val elapsed:()->Long) {
    companion object {const val MAX_AGE_MS=60_000L}
    private var epoch=0L
    private data class Receipt(val epoch:Long,val finished:Long,val scan:PreparedStartupScan)
    private var receipt:Receipt?=null
    @Synchronized fun revision()=epoch
    @Synchronized fun invalidate(){epoch++;receipt=null}
    @Synchronized fun publish(expected:Long,scan:PreparedStartupScan):Boolean {
        if(expected!=epoch||!scan.firmware.osSupported||scan.firmware.abiProfile.isBlank())return false
        receipt=Receipt(epoch,elapsed(),scan);return true
    }
    @Synchronized fun take(fingerprint:String,boot:String,entries:List<Entry>):PreparedStartupScan? {
        val saved=receipt?:return null
        receipt=null
        val age=elapsed()-saved.finished
        return saved.scan.takeIf{saved.epoch==epoch&&age in 0 until MAX_AGE_MS&&it.firmware.fingerprint==fingerprint&&it.boot==boot&&it.entries==entries}
    }
}
