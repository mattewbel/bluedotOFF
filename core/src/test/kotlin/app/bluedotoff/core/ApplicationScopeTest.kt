package app.bluedotoff.core

import org.junit.Assert.*
import org.junit.Test

class ApplicationScopeTest {
    private fun member(p:String,peers:List<String>,scope:ApplicationScope,granted:Boolean=true,uid:Int=10048)=Snapshot(
        Identity(p,0,uid,listOf("signer"),1,1,1),p,peers,true,granted,granted,false,0x330,0x330,0,
        if(granted)4 else 1,if(granted)4 else 1,true,sharedUserId="android.uid.systemui",persistent=true,criticalPackage=true,applicationScope=scope)
    @Test fun allAllowsACompleteSystemGroupWithDeniedCompanionsButLowerLevelsRefuseIt(){
        val names=listOf("test.routines","test.systemui")
        val group=names.mapIndexed{i,p->member(p,names,ApplicationScope.ALL,i==0)}
        SharedGroupPolicy.validate(group)
        for(scope in listOf(ApplicationScope.NORMAL,ApplicationScope.GROUPS))assertTrue(runCatching{SharedGroupPolicy.validate(group.map{it.copy(applicationScope=scope)})}.isFailure)
        assertTrue(group.all{!it.safe()}) // Individual calls remain closed for the same UID.
        assertTrue(runCatching{SharedGroupPolicy.validate(group.map{it.copy(fineGranted=false,coarseGranted=false)})}.isFailure)
    }
    @Test fun highScopeNeverAdmitsPartialPeersOtherUsersOrUnknownOwnership(){
        val names=listOf("test.routines","test.systemui")
        val group=names.map{member(it,names,ApplicationScope.ALL)}
        for(bad in listOf(group.take(1),group.map{it.copy(peers=names+"test.extra")},group.map{it.copy(peersAuthoritative=false)},group.map{it.copy(identity=it.identity.copy(userId=1,uid=110048))},group.map{it.copy(identity=it.identity.copy(signers=emptyList()))}))assertTrue(runCatching{SharedGroupPolicy.validate(bad)}.isFailure)
    }
    @Test fun highCoreCompanionWithGrantButIgnoredAppOpsDoesNotBecomeTheSetterTarget(){
        val names=listOf("test.systemui","test.routines")
        val group=listOf(member(names[0],names,ApplicationScope.ALL).copy(fineMode=1,coarseMode=1),member(names[1],names,ApplicationScope.ALL))
        SharedGroupPolicy.validate(group)
        assertEquals("test.routines",SharedGroupPolicy.representative(group).identity.packageName)
        assertEquals(1,group.first().fineMode)
        assertTrue(runCatching{SharedGroupPolicy.validate(group.map{it.copy(fineMode=1,coarseMode=1)})}.isFailure)
        assertTrue(runCatching{SharedGroupPolicy.validate(group.map{it.copy(applicationScope=ApplicationScope.GROUPS,systemCritical=false,persistent=false,criticalPackage=false)})}.isFailure)
        assertTrue(runCatching{SharedGroupPolicy.validate(group.map{it.copy(fineMode=-1)})}.isFailure)
    }
    @Test fun independentCoreRequiresHighScopeAndStillRequiresGrantsRawModesAndIdentity(){
        val s=member("test.core",listOf("test.core"),ApplicationScope.ALL,uid=1000)
        assertTrue(s.safe());LocalWriteGuard.validate(s,LocalWriteGuard.identityDigest(s.identity),s.invariantDigest(),s.fineFlags,0)
        for(bad in listOf(s.copy(applicationScope=ApplicationScope.GROUPS),s.copy(fineGranted=false),s.copy(fineMode=1),s.copy(peersAuthoritative=false),s.copy(identity=s.identity.copy(uid=99001))))assertFalse(bad.safe())
        assertEquals(s.invariantDigest(),s.copy(applicationScope=ApplicationScope.NORMAL).invariantDigest())
    }
    @Test fun coreGroupOf93HasOneSetterAndFullDurableOriginalsAndRestore(){
        val names=(0 until 93).map{"test.core"+it.toString().padStart(3,'0')}
        var current=names.mapIndexed{i,p->member(p,names,ApplicationScope.ALL,granted=i==0,uid=1000)}
        val originals=current.toList();var records=emptyList<Entry>();val writes=mutableListOf<Int>()
        val journal=object:Journal{
            override fun entries()=records
            override fun commit(entry:Entry)=false
            override fun commitGroup(entries:List<Entry>):Boolean {assertEquals(names,entries.map{it.identity.packageName});records=entries;return true}
        }
        val fw=Firmware("samsung",37,"90000","fw","build","model","abi")
        val port=object:TargetPort{
            override fun compatibleFirmware()=fw
            override fun read(packageName:String)=current.single{it.identity.packageName==packageName}
            override fun writeFineBit(packageName:String,originalBit:Int)=error("Never individual")
            override fun writeFineGroupChecked(expected:List<Snapshot>,wanted:Int){
                SharedGroupPolicy.validate(expected);assertTrue(records.all{it.originalBit==256&&it.groupIdentities.size==93&&it.state in setOf(TargetState.PENDING_HIDE,TargetState.PENDING_RESTORE)})
                writes+=wanted;current=current.map{it.copy(fineFlags=(it.fineFlags and 256.inv())or wanted)}
            }
        }
        val engine=SharedGroupEngine(port,journal,{1},{"boot"},wait={})
        assertTrue(engine.execute(names,true){true}.all{it.enabled&&it.settlingVerified})
        assertEquals(listOf(0),writes);assertTrue(current.drop(1).all{!it.fineGranted&&it.fineMode==1})
        assertTrue(engine.execute(names,false){true}.all{!it.enabled&&it.state==TargetState.NOT_APPLIED})
        assertEquals(listOf(0,256),writes);assertEquals(originals,current)
        assertTrue(runCatching{SharedGroupPolicy.validate(current.map{it.copy(applicationScope=ApplicationScope.GROUPS)})}.isFailure)
    }
    @Test fun completeAllListIncludesInvisibleAndLocationlessSystemApps(){
        val apps=listOf(DiscoveredApp("test.user","User",10100,launcher=true,uidPeers=listOf("test.user")),DiscoveredApp("test.core","Core",1000,preinstalled=true,uidClass=UidClass.CORE_UID,uidPeers=listOf("test.core")))
        assertEquals(2,ScopePolicy.list(apps,ApplicationScope.ALL,AppFilter.ALL,"",true).size)
        assertEquals(listOf("test.user"),ScopePolicy.list(apps,ApplicationScope.NORMAL,AppFilter.ALL,"",true).map{it.packageName})
    }
    @Test fun finalChoiceFollowsAutomaticManagementAndDoesNotGateConnectionEarlier(){
        val f=SetupFacts(paired=true,verified=true,applicationScopeResolved=false,managementChoiceResolved=false)
        assertEquals(SetupStage.AUTOMATIC_MANAGEMENT,SetupFlow.stage(f))
        val next=f.copy(managementChoiceResolved=true)
        assertEquals(SetupStage.APPLICATION_SCOPE,SetupFlow.stage(next));assertEquals(10,SetupFlow.presentation(next).progress)
        assertEquals(SetupStage.READY,SetupFlow.stage(next.copy(applicationScopeResolved=true)))
        assertEquals(SetupStage.CONNECTING,SetupFlow.stage(next.copy(verified=false)))
    }
}
