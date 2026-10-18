package app.bluedotoff.core

/** Android UserHandle.getAppId semantics; shared by the JVM tests and Android adapter. */
object UidPolicy {
    const val PER_USER_RANGE = 100000
    const val FIRST_APPLICATION_UID = 10000
    fun appId(uid: Int) = if (uid < 0) -1 else uid % PER_USER_RANGE
    fun userId(uid: Int) = if (uid < 0) -1 else uid / PER_USER_RANGE
    fun application(uid: Int) = appId(uid) in FIRST_APPLICATION_UID..19999
}

data class LocationAccess(val fine: Long? = null, val coarse: Long? = null) {
    val latest get() = listOfNotNull(fine, coarse).filter { it > 0 }.maxOrNull()
    fun merge(other: LocationAccess) = LocationAccess(maximum(fine, other.fine), maximum(coarse, other.coarse))
    companion object {
        fun maximum(a: Long?, b: Long?) = listOfNotNull(a, b).filter { it > 0 }.maxOrNull()
        fun event(op: Int, time: Long, denied: Boolean) = if (denied || time <= 0) LocationAccess() else when (op) { 1 -> LocationAccess(fine=time); 0 -> LocationAccess(coarse=time); else -> LocationAccess() }
    }
}

enum class AppFilter(val label: String) { RECENT("최근 위치 사용 앱"), INSTALLED("내가 설치한 앱"), PREINSTALLED("기본 앱"), ALL("전체") }
enum class HistoryView(val label:String) { APPS("앱 기록"), ALL("전체 위치 사용"), REAPPLY("재적용 기록") }
/** Read-only hint about granted FINE sensitivity, not proof of a displayed dot or a write gate. */
enum class FineIndicatorHint { UNKNOWN, SENSITIVE, NON_SENSITIVE }
data class DiscoveredApp(val packageName: String, val label: String, val uid: Int, val preinstalled: Boolean = false, val launcher: Boolean = false, val access: LocationAccess = LocationAccess(), val block: String = "", val authoritative: Boolean = true, val indicatorHint:FineIndicatorHint=FineIndicatorHint.UNKNOWN, val internalService:Boolean=false, val uidClass:UidClass=UidClass.NORMAL, val sharedUserId:String?=null, val indicator:IndicatorAssessment=IndicatorAssessment(), val hasLocationDeclaration:Boolean?=null,val hasRelevantOps:Boolean=false,val check:CandidateCheck=CandidateCheck.AVAILABLE,val uidPeers:List<String> = emptyList()) {
    val canCheck get() = check==CandidateCheck.AVAILABLE && authoritative && block.isEmpty() && UidPolicy.userId(uid)==0 && UidPolicy.application(uid)
    val visibleCandidate get() = launcher || access.latest != null
}

/** Display reasons only. Neither inclusion nor a raw access event establishes Hide safety or APPLIED. */
enum class HistoryDisplayReason {
    NO_POSITIVE_ACCESS, MANAGED_APP, OTHER_USER_OR_CORE_UID, INTERNAL_COMPONENT,
    PREINSTALLED_WITHOUT_LAUNCHER, NO_POSITIVE_FINE_ACCESS, NON_SENSITIVE,
    USER_FACING_APP;
    val included get() = this == MANAGED_APP || this == USER_FACING_APP
}

/** AppOps curation, not Samsung PrivacyDashboard's private database or its package list. */
object HistoryPolicy {
    fun fineHint(flags:Int):FineIndicatorHint {require(flags>=0);return if(flags and SENSITIVITY_BIT!=0)FineIndicatorHint.SENSITIVE else FineIndicatorHint.NON_SENSITIVE}
    fun displayReason(app:DiscoveredApp, managed:Set<String>):HistoryDisplayReason = when {
        app.access.latest == null -> HistoryDisplayReason.NO_POSITIVE_ACCESS
        // Preserve registered history even when hiding has cleared the sensitivity bit.
        app.packageName in managed -> HistoryDisplayReason.MANAGED_APP
        UidPolicy.userId(app.uid)!=0 || !UidPolicy.application(app.uid) -> HistoryDisplayReason.OTHER_USER_OR_CORE_UID
        app.internalService -> HistoryDisplayReason.INTERNAL_COMPONENT
        app.preinstalled && !app.launcher -> HistoryDisplayReason.PREINSTALLED_WITHOUT_LAUNCHER
        (app.access.fine ?: 0) <= 0 -> HistoryDisplayReason.NO_POSITIVE_FINE_ACCESS
        app.indicatorHint == FineIndicatorHint.NON_SENSITIVE -> HistoryDisplayReason.NON_SENSITIVE
        // UNKNOWN cannot establish a dot, but does not discard an otherwise user-facing app.
        else -> HistoryDisplayReason.USER_FACING_APP
    }
    fun relevant(app:DiscoveredApp, managed:Set<String>) = displayReason(app,managed).included
    fun list(apps:List<DiscoveredApp>, managed:Set<String>, view:HistoryView)=if(view==HistoryView.REAPPLY)emptyList() else DiscoveryPolicy.sort(apps.filter{it.access.latest!=null&&(view==HistoryView.ALL||relevant(it,managed))})
}
data class ScanMetrics(val installed: Int = 0, val candidates: Int = 0, val historyEntries: Int = 0, val lightweightMs: Long = 0, val uidMapMs: Long = 0, val historyMs: Long = 0, val sortMs: Long = 0, val firstUsableMs: Long? = null, val targetPreflightMs: Long? = null)
data class DiscoveryResult(val apps: List<DiscoveredApp>, val historyAvailable: Boolean, val metrics: ScanMetrics)

/** Publication is all-or-nothing. A failed/partial scan never establishes sole UID ownership. */
object CompletePackageScan {
    /** A successful authoritative user-scoped lookup may report an unavailable package.
     * Exceptions still abort the whole pass. Independence then requires confirmedUidMap.
     */
    fun <T:Any> collectAvailable(names:List<String>, load:(String)->T?):List<Pair<String,T>> {
        require(names.size<=25000 && names.distinct().size==names.size) { "앱 목록 크기 또는 형식 확인 실패" }
        return names.mapNotNull{pkg->load(pkg)?.let{pkg to it}}
    }
    /** Never infer sole ownership from a filtered PackageInfo list. Query each UID using
     * the authoritative peer getter, retaining peers unavailable to the scoped lookup.
     */
    fun confirmedUidMap(names:Set<String>, items:List<Pair<String,Int>>, load:(Int)->List<String>):Map<Int,List<String>> {
        require(items.map{it.first}.distinct().size==items.size && items.all{it.first in names && it.second>=0})
        return DiscoveryPolicy.uidMap(items).mapValues{(uid,known)->
            val peers=load(uid)
            require(peers.isNotEmpty() && peers.size<=25000 && peers.distinct().size==peers.size && peers.containsAll(known) && peers.all{it in names}) { "앱 목록이 바뀌었습니다. 다시 확인해 주세요." }
            peers.sorted()
        }
    }
    data class Kind(val packageName:String,val uid:Int?,val apex:Boolean)
    fun applicationNames(records:List<Kind>):Set<String> {
        require(records.map{it.packageName}.distinct().size==records.size)
        return records.filterNot{it.apex}.map{require(it.uid!=null&&it.uid>=0){"앱 정보 확인 불가"};it.packageName}.toSet()
    }
    fun <T> collect(names:List<String>, load:(String)->T?):List<Pair<String,T>> {
        require(names.size<=25000 && names.distinct().size==names.size) { "앱 목록 크기 또는 형식 확인 실패" }
        return names.map{pkg->pkg to (load(pkg)?:throw SafetyException("앱 목록이 변경되었습니다. 다시 확인해 주세요."))}
    }
}

object DiscoveryPolicy {
    fun uidMap(items: List<Pair<String, Int>>): Map<Int,List<String>> = items.groupBy({it.second},{it.first}).mapValues { it.value.distinct().sorted() }
    fun block(pkg:String,uid:Int,peers:List<String>?,persistent:Boolean,critical:Boolean,sharedUserId:String?=null,singletonVerified:Boolean=false):String=UidOwnership(pkg,uid,peers,sharedUserId,persistent,critical).rejection(singletonVerified)?.message?:""
    fun sort(items: List<DiscoveredApp>, history: Boolean = true) = items.sortedWith(if(history) compareByDescending<DiscoveredApp>{it.access.latest?:Long.MIN_VALUE}.thenBy{it.label.lowercase()}.thenBy{it.packageName} else compareBy<DiscoveredApp>{it.label.lowercase()}.thenBy{it.packageName})
    fun filter(items: List<DiscoveredApp>, filter: AppFilter, query: String, historyAvailable: Boolean) = sort(items.filter { a ->
        a.visibleCandidate && (when(filter) { AppFilter.RECENT -> if(historyAvailable) a.access.latest!=null else true; AppFilter.INSTALLED -> !a.preinstalled; AppFilter.PREINSTALLED -> a.preinstalled; AppFilter.ALL -> true }) && (a.label.contains(query,true)||a.packageName.contains(query,true))
    },historyAvailable)
}

data class BatchResult(val packageName: String, val entry: Entry? = null, val error: String = "") {
    val success get() = error.isEmpty() && entry != null
    companion object {
        /** The only states a finished operation may end in without an error. */
        val DONE=setOf(TargetState.APPLIED,TargetState.NOT_APPLIED)
        fun of(pkg:String,entry:Entry)=BatchResult(pkg,entry,if(entry.state in DONE)"" else entry.error.ifBlank{"현재 상태 확인 필요"})
    }
}
/** Each target has its own durable transaction. No batch rollback exists. */
object TargetBatch {
    /** One stop latch spans all helper connections of the same user operation. */
    class Runner {
        var stopped=false; private set
        fun run(pkg:String,action:(String)->Entry):BatchResult =
            if(stopped)BatchResult(pkg,error="앞선 작업의 복원 또는 연결 확인이 필요해 변경하지 않았습니다.")
            else try {
                val e=action(pkg)
                if(e.state in listOf(TargetState.FAILED_ROLLBACK,TargetState.SHARED_UID_CHANGED,TargetState.NEEDS_REVIEW))stopped=true
                BatchResult.of(pkg,e)
            }catch(e:OwnershipChangedException){stopped=true;BatchResult(pkg,error=e.message?:"앱 묶음 상태 확인 필요")}
            catch(e:JournalPersistenceException){stopped=true;BatchResult(pkg,error=e.message?:"복원 기록 저장 확인 필요")}
            catch(e:SafetyException){BatchResult(pkg,error=e.message?:"현재 상태 확인 필요")}
            catch(e:Exception){stopped=true;BatchResult(pkg,error=e.message?:"연결 또는 현재 상태 확인 필요")}
    }
    fun run(packages: List<String>, action: (String)->Entry): List<BatchResult> {
        val runner=Runner()
        return packages.distinct().map { runner.run(it,action) }
    }
}
