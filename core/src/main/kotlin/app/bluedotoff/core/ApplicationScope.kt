package app.bluedotoff.core

/** User-selected admission policy; never part of a permission or installation invariant. */
enum class ApplicationScope(val level:Int,val title:String,val description:String) {
    NORMAL(1,"일반 적용","위치 설정을 따로 쓰는 앱. 조건을 통과한 기본 설치 앱도 포함해요."),
    // Legacy wire value remains valid for old helper requests and interrupted restores.
    GROUPS(2,"전체 적용","공유 앱과 시스템 앱까지 선택할 수 있어요."),
    ALL(3,"전체 적용","공유 앱과 시스템 앱까지 선택할 수 있어요.");
    companion object {
        val choices get()=listOf(NORMAL,ALL)
        fun fromLevel(level:Int)=entries.single{it.level==level}
        fun fromStoredLevel(level:Int)=fromLevel(level).let{if(it==GROUPS)ALL else it}
    }
}

object ScopePolicy {
    fun ownershipBlock(o:UidOwnership,singletonVerified:Boolean,scope:ApplicationScope):BlockReason? {
        if(!o.authoritative||o.peers.isNullOrEmpty()||o.peers.distinct().size!=o.peers.size||o.packageName !in o.peers)return BlockReason.UNKNOWN_UID_OWNERSHIP
        if(UidPolicy.userId(o.uid)!=0)return BlockReason.OTHER_USER
        if(UidPolicy.appId(o.uid) !in 0..19999)return BlockReason.SPECIAL_UID
        // A shared UID can only enter through a whole-group transaction, at any level.
        if(scope==ApplicationScope.ALL)return if(o.peers.size>1)BlockReason.MULTI_PACKAGE_SHARED_UID else null
        return o.rejection(singletonVerified)
    }
    fun includes(s:Snapshot,scope:ApplicationScope):Boolean {
        if(s.identity.userId!=0||!s.peersAuthoritative||s.peers.isEmpty()||s.identity.packageName !in s.peers||s.peers.distinct().size!=s.peers.size)return false
        if(UidPolicy.appId(s.identity.uid) !in 0..19999)return false
        if(scope==ApplicationScope.ALL)return true
        if(s.systemCritical||s.persistent||s.criticalPackage||!UidPolicy.application(s.identity.uid))return false
        return scope==ApplicationScope.GROUPS||s.peers.size==1
    }
    fun list(apps:List<DiscoveredApp>,scope:ApplicationScope,filter:AppFilter,query:String,history:Boolean):List<DiscoveredApp> = DiscoveryPolicy.sort(apps.filter{a->
        val admitted=when(scope){
            ApplicationScope.ALL->true
            ApplicationScope.GROUPS->UidPolicy.application(a.uid)&&!a.internalService&&a.uidClass !in setOf(UidClass.PERSISTENT_CORE,UidClass.CRITICAL_PACKAGE)&& (a.visibleCandidate||a.hasLocationDeclaration==true||a.hasRelevantOps)
            ApplicationScope.NORMAL->UidPolicy.application(a.uid)&&!a.internalService&&a.uidClass in setOf(UidClass.NORMAL,UidClass.SINGLETON_SHARED_USER)&&a.uidPeers.size<=1&&a.visibleCandidate
        }
        admitted && when(filter){AppFilter.RECENT->!history||a.access.latest!=null;AppFilter.INSTALLED->!a.preinstalled;AppFilter.PREINSTALLED->a.preinstalled;AppFilter.ALL->true} && (a.label.contains(query,true)||a.packageName.contains(query,true))
    },history)
}
