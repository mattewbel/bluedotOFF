package app.bluedotoff

import app.bluedotoff.core.*

enum class Gate { CHECKING, WELCOME, UNSUPPORTED, MISSING, NOT_RUNNING, AUTHORIZATION, WRONG_MODE, ABI_FAILED, READY }
enum class Page { HOME, HISTORY, PICK, DETAIL, REAPPLY, SETTINGS, HELP }
data class SharedOffer(val members:List<Snapshot>,val hide:Boolean=true,val loading:Boolean=false,val error:String="",val direct:Boolean=false)
/** States SafetyEngine.prepare refuses to restore; skipped in multi-app restores (mirrors batchHide). */
val RESTORE_SKIP=listOf(TargetState.INACTIVE_INSTALL,TargetState.FAILED_ROLLBACK,TargetState.SHARED_UID_BLOCKED,TargetState.SHARED_UID_CHANGED,TargetState.NEEDS_REVIEW,TargetState.UNSUPPORTED_STATE)

data class UiModel(val gate: Gate = Gate.CHECKING, val page: Page = Page.HOME, val entries: List<Entry> = emptyList(), val candidates: List<Snapshot> = emptyList(), val selected: String? = null, val connected: Boolean = false, val busy: Boolean = false, val loadingApps: Boolean = false, val notice: String = "", val compatibility: String = "기기 확인 대기", val notifications: Boolean = false, val notificationOffer: Boolean = false, val successToken: Int = 0, val freshPackages:Set<String> = emptySet(), val picked:Set<String> = emptySet(), val query:String = "", val historyAvailable:Boolean = false, val discovered:List<DiscoveredApp> = emptyList(), val filter:AppFilter = AppFilter.ALL, val homePicked:Set<String> = emptySet(), val editing:Boolean = false, val batchResults:List<BatchResult> = emptyList(), val metrics:ScanMetrics = ScanMetrics(), val loadingDetail:Boolean = false, val pickerRows:List<DiscoveredApp>? = null, val historyView:HistoryView = HistoryView.APPS, val pickerView:PickerView = PickerView.CANDIDATES,val automaticReady:Boolean=false,val autoManagement:Boolean=false,val autoPermission:Boolean=false,val progress:BatchProgress?=null,val completion:BatchOutcome?=null,val wirelessWarning:String="",val operationHide:Boolean=true,val sharedOffer:SharedOffer?=null,val approvedGroups:List<List<String>> = emptyList(),val applicationScope:ApplicationScope=ApplicationScope.GROUPS,val scopePending:Boolean=false,val scopeResolved:Boolean=false,val scopePreview:Boolean=false,val scanProgress:ScanProgress?=null,val checkingRegistered:Boolean=false,val usModel:Boolean=false,val rejectedGroups:Set<String> = emptySet(),val reapplyNotify:Boolean=true) {
    // Drawing progress follows checked targets, never drives actual work or success.
    val blueRemaining get()=when {
        busy && !scopePreview -> 1f-(progress?.percent?:0)/100f
        completion!=null || allLastVerifiedApplied -> 0f
        else -> 1f
    }
    val canOperate get()=connected||automaticReady
    val displayApps get() = if(discovered.isNotEmpty()) discovered else candidates.map{s->DiscoveredApp(s.identity.packageName,s.label,s.identity.uid,launcher=true,access=LocationAccess(fine=s.lastLocationAccess),block=s.copy(applicationScope=applicationScope).rejectionReason(),uidClass=s.ownership.uidClass,sharedUserId=s.sharedUserId,uidPeers=s.peers)}
    val managedPackages get() = entries.filter{it.enabled}.map{it.identity.packageName}.toSet()
    val appHistory get() = HistoryPolicy.list(displayApps,managedPackages,HistoryView.APPS)
    // Home always summarizes curated history, independently of the selected History tab.
    val recentHistoryApp get() = appHistory.firstOrNull()
    val historyApps get() = HistoryPolicy.list(displayApps,managedPackages,historyView)
    val pickerApps get() = pickerRows ?: ScopePolicy.list(displayApps,applicationScope,filter,query,historyAvailable)
    fun groupChosen(pkg:String)=approvedGroups.any{pkg in it}||entries.any{it.enabled&&it.identity.packageName==pkg&&it.groupIdentities.isNotEmpty()}
    fun selectionGroup(pkg:String)=approvedGroups.firstOrNull{pkg in it}?:entries.firstOrNull{it.identity.packageName==pkg&&it.groupIdentities.isNotEmpty()}?.groupIdentities?.map{it.packageName}?:listOf(pkg)
    fun canAct(a:DiscoveredApp)=(a.canCheck||(applicationScope==ApplicationScope.ALL&&a.check==CandidateCheck.AVAILABLE&&a.block.isEmpty())||((applicationScope!=ApplicationScope.NORMAL&&a.uidPeers.size>1)&&groupChosen(a.packageName))) && entries.none{it.identity.packageName==a.packageName&&it.state in listOf(TargetState.FAILED_ROLLBACK,TargetState.SHARED_UID_CHANGED,TargetState.NEEDS_REVIEW)} && (candidates.firstOrNull{it.identity.packageName==a.packageName}?.let{ScopePolicy.includes(it,applicationScope)&&(it.copy(applicationScope=applicationScope).safe()||groupChosen(a.packageName))}!=false)
    /** Already hidden and verified: selecting it again would only re-check, so the picker leaves it out. */
    fun isApplied(pkg:String)=entries.any{it.identity.packageName==pkg&&it.enabled&&it.state==TargetState.APPLIED&&it.settlingVerified}
    /** Selectable in the picker: actionable and not already hidden. Applied apps stay in the main list as "적용중". */
    fun canSelect(a:DiscoveredApp)=canAct(a)&&!isApplied(a.packageName)
    /** A shared-UID app in the whole scope that is not chosen yet: picking it checks and selects its whole group. */
    fun canPickGroup(a:DiscoveredApp)=canInspectGroup(a)&&!groupChosen(a.packageName)&&a.check!=CandidateCheck.NEEDS_CHECK&&a.packageName !in rejectedGroups
    fun canInspectGroup(a:DiscoveredApp)=applicationScope!=ApplicationScope.NORMAL&&a.uidPeers.size>1
    fun canActFromHistory(a:DiscoveredApp)=canSelect(a)&&HistoryPolicy.relevant(a,managedPackages)
    fun historyStatus(a:DiscoveredApp):String {
        val e=entries.lastOrNull{it.identity.packageName==a.packageName&&it.enabled}
        if(e!=null) return status(e)
        if(a.block.isNotEmpty())return a.block
        val s=candidates.firstOrNull{it.identity.packageName==a.packageName}
        if(s!=null&&!s.safe())return "지원 안 됨 · ${s.rejectionReason()}"
        if(!HistoryPolicy.relevant(a,emptySet()))return "위치 접근 기록 · Blue Dot 대상 확인 안 됨"
        return if(connected && a.access.fine!=null && s?.safe()==true && s.bit!=0 && a.packageName in freshPackages) "Blue Dot 표시 대상" else "앱 상태 확인 필요"
    }
    // Uninstalled apps take their permission state with them: nothing to show, restore or act on.
    // Their journal records stay intact for safety; they are only hidden from the screens.
    val visibleEntries get() = entries.filter { it.state!=TargetState.MISSING_TARGET && (it.enabled || it.state in listOf(TargetState.FAILED_ROLLBACK, TargetState.INACTIVE_INSTALL)) }
    private val activeEntries get()=visibleEntries.filter{it.enabled}
    val allLastVerifiedApplied get()=activeEntries.isNotEmpty()&&activeEntries.all{it.state==TargetState.APPLIED&&it.settlingVerified}
    val lastVerifiedAppliedCount get()=visibleEntries.count{it.enabled&&it.state==TargetState.APPLIED&&it.settlingVerified}
    fun needsReapply(e:Entry)=e.enabled&&e.state in listOf(TargetState.NEEDS_REAPPLY_POLICY,TargetState.NEEDS_REAPPLY_BOOT,TargetState.NEEDS_REAPPLY_UPDATE)
    val reapplyEntries get()=visibleEntries.filter(::needsReapply)
    // Presentation order only; keep journal order and all original records intact.
    val registeredDisplayEntries get()=visibleEntries.sortedBy{!needsReapply(it)}
    val reapplyCount get()=reapplyEntries.size
    // Mirror the existing batch's indivisible shared-UID units in the confirmation.
    val reapplyTargetPackages get()=reapplyEntries.flatMap{selectionGroup(it.identity.packageName)}.distinct()
    val otherCheckCount get()=activeEntries.size-lastVerifiedAppliedCount-reapplyCount
    /** A batch stops at the first app it cannot change, leaving every later app untouched. Uninstalled apps have
     * nothing to restore and the RESTORE_SKIP states are refused by SafetyEngine.prepare, so a multi-app restore
     * skips them up front (batchHide already excludes the same states). A single restore keeps the exact reason.
     * Returns the packages to restore and how many requested apps were skipped because they need checking. */
    fun restorePlan(requested:List<String>):Pair<List<String>,Int> {
        val multi=requested.distinct().size>1
        val blocked=entries.filter{it.state==TargetState.MISSING_TARGET||(multi&&(!it.enabled||it.state in RESTORE_SKIP))}.map{it.identity.packageName}.toSet()
        // A shared group is written as one unit, so one blocked member skips the whole group.
        val skipped=blocked+entries.filter{e->e.groupIdentities.any{it.packageName in blocked}}.flatMap{e->e.groupIdentities.map{it.packageName}}
        val needsCheck=requested.distinct().count{p->p in skipped&&entries.any{it.identity.packageName==p&&it.state in RESTORE_SKIP}}
        return requested.filterNot{it in skipped} to needsCheck
    }
    fun status(e:Entry) = if(e.state==TargetState.APPLIED) when {
        !e.settlingVerified -> "적용 확인이 필요합니다"
        connected&&e.identity.packageName in freshPackages -> "bluedotOFF 적용중"
        else -> "bluedotOFF 적용중 · 마지막 확인 기준"
    } else e.state.label()
}

