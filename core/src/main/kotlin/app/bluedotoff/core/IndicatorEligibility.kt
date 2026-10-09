package app.bluedotoff.core

/** Potential future FINE indicator usage on the verified firmware; never write authorization. */
enum class IndicatorEligibility(val label:String) {
    STRONG_CANDIDATE("위치 표시 후보"), EXEMPT_SYSTEM_USAGE("표시 대상이 아닌 위치 사용"),
    EXEMPT_PACKAGE("플랫폼 표시 제외"), INELIGIBLE_OP("정확한 위치 권한 없음"),
    INELIGIBLE_PERMISSION_STATE("위치 권한 상태 확인 필요"), UNKNOWN("표시 대상 확인 안 됨")
}
data class IndicatorAssessment(val eligibility:IndicatorEligibility=IndicatorEligibility.UNKNOWN,
    val logicalSystemUsage:Boolean?=null,val sensitivityBranch:Int?=null)
data class IndicatorFacts(val profileSupported:Boolean=false,val packageVisible:Boolean?=null,
    val fineDeclared:Boolean?=null,val fineGranted:Boolean?=null,val fineFlags:Int?=null,
    val rawFineMode:Int?=null)
object IndicatorClassifier {
    /** Exact SystemUI bit selection, including the denied branch. */
    fun logicalSystem(flags:Int,preflightGranted:Boolean):Boolean {
        require(flags>=0)
        return flags and (if(preflightGranted)0x100 else 0x200)==0
    }
    fun classify(f:IndicatorFacts):IndicatorAssessment {
        if(!f.profileSupported||f.packageVisible==null)return IndicatorAssessment()
        if(!f.packageVisible)return IndicatorAssessment(IndicatorEligibility.EXEMPT_PACKAGE)
        if(f.fineDeclared==false)return IndicatorAssessment(IndicatorEligibility.INELIGIBLE_OP)
        val flags=f.fineFlags
        if(f.fineDeclared!=true||f.fineGranted==null||flags==null||flags<0||f.rawFineMode==null)return IndicatorAssessment()
        if(f.rawFineMode !in listOf(0,1,2,4))return IndicatorAssessment()
        // Grant + raw mode 0/4 reproduces normal single-source preflight on this verified profile.
        // Other modes are not inferred to be a granted preflight.
        val preflight=f.fineGranted&&f.rawFineMode in listOf(0,4)
        val system=logicalSystem(flags,preflight)
        val branch=if(preflight)0x100 else 0x200
        if(system)return IndicatorAssessment(IndicatorEligibility.EXEMPT_SYSTEM_USAGE,true,branch)
        if(!preflight)return IndicatorAssessment(IndicatorEligibility.INELIGIBLE_PERMISSION_STATE,false,branch)
        return IndicatorAssessment(IndicatorEligibility.STRONG_CANDIDATE,false,branch)
    }
}
enum class PickerView { CANDIDATES, ADVANCED }
object PickerPolicy {
    /** A cleared journal does not restore the OS sensitivity bit. Keep safe, user-facing
     * granted apps discoverable without claiming that their original bit is known. */
    fun alreadyNotSensitive(a:DiscoveredApp)=a.canCheck && a.launcher && !a.internalService &&
        a.hasLocationDeclaration==true && a.indicator.eligibility==IndicatorEligibility.EXEMPT_SYSTEM_USAGE &&
        a.indicator.logicalSystemUsage==true && a.indicator.sensitivityBranch==0x100
    fun list(apps:List<DiscoveredApp>,managed:Set<String>,view:PickerView,filter:AppFilter,query:String,history:Boolean):List<DiscoveredApp> =
        DiscoveryPolicy.sort(apps.filter { a ->
            val retained=a.packageName in managed
            val include=if(view==PickerView.CANDIDATES)retained||a.indicator.eligibility==IndicatorEligibility.STRONG_CANDIDATE||alreadyNotSensitive(a)
                else retained||a.hasLocationDeclaration==true||a.hasRelevantOps||a.access.latest!=null
            include && (retained || when(filter){AppFilter.RECENT->!history||a.access.latest!=null;AppFilter.INSTALLED->!a.preinstalled;AppFilter.PREINSTALLED->a.preinstalled;AppFilter.ALL->true}) &&
                (a.label.contains(query,true)||a.packageName.contains(query,true))
        },history)
}
