package app.bluedotoff.core

enum class UidClass { NORMAL, CORE_UID, OTHER_USER, SPECIAL_UID, PERSISTENT_CORE, CRITICAL_PACKAGE, MULTI_PACKAGE_SHARED_UID, SINGLETON_SHARED_USER, UNKNOWN_UID_OWNERSHIP }
enum class BlockReason(val message:String) {
    CORE_UID("Android 핵심 시스템 구성 요소는 지원하지 않습니다."),
    OTHER_USER("사용자 0의 앱만 지원합니다."),
    SPECIAL_UID("지원하지 않는 특수 시스템 앱입니다."),
    PERSISTENT_CORE("상시 실행되는 핵심 시스템 구성 요소는 지원하지 않습니다."),
    CRITICAL_PACKAGE("핵심 OS 패키지는 지원하지 않습니다."),
    MULTI_PACKAGE_SHARED_UID("이 앱은 다른 앱과 위치 설정을 공유하므로 따로 적용할 수 없습니다."),
    UNKNOWN_UID_OWNERSHIP("이 앱과 묶인 앱을 모두 확인하지 못했습니다."),
    SINGLETON_UNVERIFIED("이 앱은 오래된 방식으로 다른 앱과 묶여 있습니다. 이 기기에서는 아직 안전성이 확인되지 않았습니다."),
    IDENTITY_UNKNOWN("앱 설치 정보 또는 서명을 확인하지 못했습니다."),
    FINE_PERMISSION("정확한 위치 권한이 허용된 앱만 적용할 수 있습니다."),
    LOCATION_STATE("현재 위치 사용 상태를 안전하게 확인할 수 없습니다.")
}
data class UidOwnership(val packageName:String,val uid:Int,val peers:List<String>?,val sharedUserId:String?=null,val persistent:Boolean=false,val criticalPackage:Boolean=false,val authoritative:Boolean=true) {
    val uidClass:UidClass get()=when {
        UidPolicy.userId(uid)!=0 -> UidClass.OTHER_USER
        UidPolicy.appId(uid)<UidPolicy.FIRST_APPLICATION_UID -> UidClass.CORE_UID
        !UidPolicy.application(uid) -> UidClass.SPECIAL_UID
        persistent -> UidClass.PERSISTENT_CORE
        criticalPackage -> UidClass.CRITICAL_PACKAGE
        !authoritative || peers==null || peers.isEmpty() || peers.distinct().size!=peers.size || packageName !in peers -> UidClass.UNKNOWN_UID_OWNERSHIP
        peers.size>1 -> UidClass.MULTI_PACKAGE_SHARED_UID
        sharedUserId!=null -> UidClass.SINGLETON_SHARED_USER
        else -> UidClass.NORMAL
    }
    fun rejection(singletonVerified:Boolean):BlockReason?=when(uidClass) {
        UidClass.NORMAL -> null
        UidClass.SINGLETON_SHARED_USER -> if(singletonVerified)null else BlockReason.SINGLETON_UNVERIFIED
        else -> BlockReason.valueOf(uidClass.name)
    }
}
sealed interface EligibilityResult {
    data object Allowed:EligibilityResult
    data object SingletonSharedUserExperimental:EligibilityResult
    data class Blocked(val reason:BlockReason):EligibilityResult
}
/** Installed flags are deliberately absent: preinstallation is a display classification. */
object EligibilityPolicy {
    fun evaluate(s:Snapshot):EligibilityResult {
        ScopePolicy.ownershipBlock(s.ownership,s.singletonVerified,s.applicationScope)?.let{return EligibilityResult.Blocked(it)}
        if(s.identity.signers.isEmpty() || s.identity.firstInstallTime<=0 || s.identity.versionCode<=0 || s.identity.userId!=0)return EligibilityResult.Blocked(BlockReason.IDENTITY_UNKNOWN)
        if(!s.fineDeclared || !s.fineGranted)return EligibilityResult.Blocked(BlockReason.FINE_PERMISSION)
        if(!s.coarseDeclared || !s.coarseGranted || s.fineMode !in listOf(0,4) || s.coarseMode !in listOf(0,4) || s.fineFlags<0 || s.coarseFlags<0)return EligibilityResult.Blocked(BlockReason.LOCATION_STATE)
        return if(s.ownership.uidClass==UidClass.SINGLETON_SHARED_USER)EligibilityResult.SingletonSharedUserExperimental else EligibilityResult.Allowed
    }
}
