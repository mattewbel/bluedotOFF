package app.bluedotoff.core

enum class UidClass { NORMAL, CORE_UID, OTHER_USER, SPECIAL_UID, PERSISTENT_CORE, CRITICAL_PACKAGE, MULTI_PACKAGE_SHARED_UID, SINGLETON_SHARED_USER, UNKNOWN_UID_OWNERSHIP }
enum class BlockReason(val message:String) {
    CORE_UID("Android 핵심 시스템 앱이라 숨길 수 없어요."),
    OTHER_USER("기본 사용자 앱만 숨길 수 있어요. (보안 폴더·두 번째 사용자 앱 제외)"),
    SPECIAL_UID("특수 시스템 앱이라 숨길 수 없어요."),
    PERSISTENT_CORE("항상 실행되는 핵심 시스템 앱이라 숨길 수 없어요."),
    CRITICAL_PACKAGE("Android 핵심 시스템 앱이라 숨길 수 없어요."),
    MULTI_PACKAGE_SHARED_UID("다른 앱과 위치 설정을 함께 써서 이 앱만 숨길 수 없어요."),
    UNKNOWN_UID_OWNERSHIP("이 앱과 묶인 앱을 모두 확인하지 못했어요."),
    SINGLETON_UNVERIFIED("다른 앱과 오래된 방식으로 묶여 있어서, 이 휴대폰에서 안전하게 바꿀 수 있는지 아직 확인되지 않았어요."),
    IDENTITY_UNKNOWN("앱의 설치 정보를 확인하지 못했어요."),
    FINE_PERMISSION("‘정확한 위치’를 허용한 앱만 숨길 수 있어요."),
    LOCATION_STATE("이 앱의 위치 사용 상태를 확인하지 못했어요.")
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
