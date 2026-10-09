package app.bluedotoff.core

import org.junit.Assert.*
import org.junit.Test

class DiscoveryTest {
    @Test fun positiveFineUserInstalledWithoutLauncherIsIncluded(){
        val a=app(launch=false,time=100).copy(indicatorHint=FineIndicatorHint.SENSITIVE)
        assertEquals(HistoryDisplayReason.USER_FACING_APP,HistoryPolicy.displayReason(a,emptySet()))
    }
    @Test fun nonPositiveConstructorValuesNeverFabricateAccess(){
        val rows=listOf(app(time=0),app(time=-100),app().copy(access=LocationAccess(coarse=0)))
        assertTrue(HistoryPolicy.list(rows,rows.map{it.packageName}.toSet(),HistoryView.ALL).isEmpty())
        assertTrue(HistoryPolicy.list(rows,rows.map{it.packageName}.toSet(),HistoryView.APPS).isEmpty())
        val coarse=app(time=-1).copy(access=LocationAccess(fine=-1,coarse=100))
        assertEquals(HistoryDisplayReason.NO_POSITIVE_FINE_ACCESS,HistoryPolicy.displayReason(coarse,emptySet()))
        assertEquals(listOf(coarse),HistoryPolicy.list(listOf(coarse),emptySet(),HistoryView.ALL))
    }
    @Test fun registeredOffHistoryAndAppliedAreIndependent(){
        val a=app(time=100).copy(indicatorHint=FineIndicatorHint.NON_SENSITIVE)
        assertEquals(HistoryDisplayReason.MANAGED_APP,HistoryPolicy.displayReason(a,setOf(a.packageName)))
        assertEquals(listOf(a),HistoryPolicy.list(listOf(a),setOf(a.packageName),HistoryView.APPS))
        assertEquals(listOf(a),HistoryPolicy.list(listOf(a),emptySet(),HistoryView.ALL))
    }
    @Test fun unknownInternalDisplayEligibilityStaysInRawOnly(){
        val a=app(system=true,launch=false,time=100)
        assertEquals(HistoryDisplayReason.PREINSTALLED_WITHOUT_LAUNCHER,HistoryPolicy.displayReason(a,emptySet()))
        assertEquals(listOf(a),HistoryPolicy.list(listOf(a),emptySet(),HistoryView.ALL))
    }
    @Test fun packageNamesCannotOverrideMetadataPolicy(){
        for(pkg in listOf("com.google.android.gms","com.sec.location.nsflp2","com.samsung.android.rubin.app","arbitrary.service")){
            val facing=app(pkg,time=100,system=true).copy(indicatorHint=FineIndicatorHint.SENSITIVE)
            assertTrue(HistoryPolicy.relevant(facing,emptySet()))
            assertFalse(HistoryPolicy.relevant(facing.copy(launcher=false),emptySet()))
        }
    }
    @Test fun displayReasonDoesNotMutateOwnershipOrSelectionSafety(){
        val a=app(time=100).copy(block=BlockReason.MULTI_PACKAGE_SHARED_UID.message)
        val before=a.copy()
        for(view in HistoryView.entries)HistoryPolicy.list(listOf(a),setOf(a.packageName),view)
        assertEquals(before,a);assertFalse(a.canCheck)
        assertTrue(HistoryPolicy.relevant(a,emptySet()))
    }
    @Test fun internalServicesDoNotDisplaceAppHistoryButRemainInAll(){
        val user=app("maps.app",time=100).copy(indicatorHint=FineIndicatorHint.SENSITIVE)
        val services=listOf(app("android",uid=1000,time=400),app("gms.service",launch=false,time=300,system=true),app("persistent.service",time=200).copy(internalService=true))
        val list=services+user
        assertEquals(listOf(user),HistoryPolicy.list(list,emptySet(),HistoryView.APPS))
        assertEquals(listOf("android","gms.service","persistent.service","maps.app"),HistoryPolicy.list(list,emptySet(),HistoryView.ALL).map{it.packageName})
    }
    @Test fun preinstalledLauncherIsNotExcludedAsSystemNoise(){assertTrue(HistoryPolicy.relevant(app(system=true,time=100).copy(indicatorHint=FineIndicatorHint.SENSITIVE),emptySet()))}
    @Test fun hiddenRegisteredAppsRemainManageableWithoutFabricatingHistory(){
        val hidden=app(time=100).copy(indicatorHint=FineIndicatorHint.NON_SENSITIVE)
        assertFalse(HistoryPolicy.relevant(hidden,emptySet()))
        assertTrue(HistoryPolicy.relevant(hidden,setOf(hidden.packageName)))
        assertFalse(HistoryPolicy.relevant(hidden.copy(access=LocationAccess()),setOf(hidden.packageName)))
    }
    @Test fun unknownHintDoesNotSilentlyLoseUserHistory(){assertTrue(HistoryPolicy.relevant(app(time=100),emptySet()));assertTrue(HistoryPolicy.relevant(app(launch=false,time=100),emptySet()))}
    @Test fun coarseOnlyIsAvailableInWholeHistoryWithoutClaimingFineIndicator(){val a=app().copy(access=LocationAccess(coarse=100));assertTrue(HistoryPolicy.list(listOf(a),emptySet(),HistoryView.APPS).isEmpty());assertEquals(listOf(a),HistoryPolicy.list(listOf(a),emptySet(),HistoryView.ALL))}
    @Test fun historyCurationCannotGrantSafetyToSharedUid(){val a=app(time=100).copy(block="공유 UID");assertTrue(HistoryPolicy.relevant(a,emptySet()));assertFalse(a.canCheck)}
    @Test fun sensitivityHintUsesOnlyTheReadBit(){assertEquals(FineIndicatorHint.SENSITIVE,HistoryPolicy.fineHint(0x80301));assertEquals(FineIndicatorHint.NON_SENSITIVE,HistoryPolicy.fineHint(0x80201));assertEquals(FineIndicatorHint.NON_SENSITIVE,HistoryPolicy.fineHint(0x200))}
    private fun app(pkg:String="test.app",uid:Int=10000,launch:Boolean=true,time:Long?=null,system:Boolean=false)=DiscoveredApp(pkg,pkg,uid,system,launch,LocationAccess(fine=time))
    @Test fun core9999AlwaysBlocked(){assertFalse(app(uid=9999).canCheck);assertTrue(DiscoveryPolicy.block("test.app",9999,listOf("test.app"),false,false).contains("핵심"))}
    @Test fun firstApplication10000Proceeds(){assertTrue(app().canCheck);assertEquals("",DiscoveryPolicy.block("test.app",10000,listOf("test.app"),false,false))}
    @Test fun multiUserUsesAppIdAndStillBlocksOtherProfile(){assertEquals(10000,UidPolicy.appId(1510000));assertEquals(15,UidPolicy.userId(1510000));assertFalse(app(uid=1510000).canCheck);assertEquals(9999,UidPolicy.appId(1509999))}
    @Test fun negativeUidIsNeverApplication(){assertFalse(UidPolicy.application(-1));assertEquals(-1,UidPolicy.appId(-1))}
    @Test fun independentUidMap(){assertEquals(listOf("test.app"),DiscoveryPolicy.uidMap(listOf("test.app" to 10000))[10000])}
    @Test fun twoPeersBlockBeforeSelection(){val map=DiscoveryPolicy.uidMap(listOf("a.app" to 10000,"b.app" to 10000));assertTrue(DiscoveryPolicy.block("a.app",10000,map[10000],false,false).contains("공유"))}
    @Test fun launcherWithoutHistoryVisible(){assertTrue(app().visibleCandidate)}
    @Test fun noLauncherWithHistoryVisible(){assertTrue(app(launch=false,time=100).visibleCandidate)}
    @Test fun noLauncherNoHistoryHidden(){assertFalse(app(launch=false).visibleCandidate)}
    @Test fun preinstalledClassificationDoesNotBlock(){assertTrue(app(system=true).canCheck)}
    @Test fun persistentAndCriticalIndependentBlocked(){assertTrue(DiscoveryPolicy.block("test.app",10000,listOf("test.app"),true,false).isNotEmpty());assertTrue(DiscoveryPolicy.block("test.app",10000,listOf("test.app"),false,true).isNotEmpty())}
    @Test fun largeScanGroupsAllPackages(){val names=(0..4999).map{"pkg.p$it"};val scan=CompletePackageScan.collect(names){10000+it.substringAfter(".p").toInt()%500};val map=DiscoveryPolicy.uidMap(scan);assertEquals(500,map.size);assertEquals(10,map[10000]!!.size)}
    @Test fun failedBinderScanNeverPublishesPartialUidMap(){var published=false;try{val scan=CompletePackageScan.collect(listOf("a.app","b.app")){if(it=="b.app")throw IllegalStateException("TransactionTooLargeException") else 10000};DiscoveryPolicy.uidMap(scan);published=true}catch(_:Exception){};assertFalse(published)}
    @Test fun packageDisappearingFailsWholeScan(){try{CompletePackageScan.collect(listOf("a.app","b.app")){if(it=="b.app")null else 10000};fail()}catch(_:SafetyException){}}
    @Test fun localVisibilityCannotEstablishIndependence(){assertTrue(DiscoveryPolicy.block("a.app",10000,null,false,false).isNotEmpty());assertFalse(app().copy(authoritative=false).canCheck)}
    @Test fun duplicateNamesRejected(){try{CompletePackageScan.collect(listOf("a.app","a.app")){10000};fail()}catch(_:IllegalArgumentException){}}
    @Test fun timestampAndTiesDeterministic(){val apps=listOf(app("b.app",time=100),app("c.app",time=200),app("a.app",time=100));assertEquals(listOf("c.app","a.app","b.app"),DiscoveryPolicy.sort(apps).map{it.packageName})}
    @Test fun deniedAndZeroEventsExcluded(){assertNull(LocationAccess.event(1,100,true).latest);assertNull(LocationAccess.event(1,0,false).latest)}
    @Test fun fineCoarseMergedAsRealLatest(){val h=LocationAccess.event(1,100,false).merge(LocationAccess.event(0,200,false));assertEquals(100L,h.fine);assertEquals(200L,h.coarse);assertEquals(200L,h.latest)}
    @Test fun noHistoryFallbackNameOrder(){assertEquals(listOf("a.app","z.app"),DiscoveryPolicy.sort(listOf(app("z.app",time=999),app("a.app")),false).map{it.packageName})}
    @Test fun searchFilterComposition(){val list=listOf(app("test.one",time=100),app("system.one",system=true,time=200),app("test.two"));assertEquals(listOf("system.one"),DiscoveryPolicy.filter(list,AppFilter.PREINSTALLED,"one",true).map{it.packageName});assertEquals(listOf("test.one"),DiscoveryPolicy.filter(list,AppFilter.RECENT,"test",true).map{it.packageName});assertEquals(2,DiscoveryPolicy.filter(list,AppFilter.INSTALLED,"",true).size)}
    @Test fun emptyHistoryAndFallback(){assertTrue(DiscoveryPolicy.filter(emptyList(),AppFilter.RECENT,"",true).isEmpty());assertEquals(1,DiscoveryPolicy.filter(listOf(app()),AppFilter.RECENT,"",false).size)}
    @Test fun apexIsNotAnApplicationUidPeer(){val r=listOf(CompletePackageScan.Kind("com.android.apex.cts.shim",null,true),CompletePackageScan.Kind("test.app",10000,false));assertEquals(setOf("test.app"),CompletePackageScan.applicationNames(r))}
    @Test fun unknownNormalPackageStillFailsWholeScan(){try{CompletePackageScan.applicationNames(listOf(CompletePackageScan.Kind("test.app",null,false)));fail()}catch(_:IllegalArgumentException){}}
    @Test fun authoritativeUserScopedNullIsUnavailable(){assertEquals(listOf("a.app" to 10000),CompletePackageScan.collectAvailable(listOf("a.app","other.app")){if(it=="a.app")10000 else null})}
    @Test fun unavailableSiblingCannotCreateFalseIndependentUid(){val peers=CompletePackageScan.confirmedUidMap(setOf("a.app","hidden.app"),listOf("a.app" to 10000)){listOf("hidden.app","a.app")};assertEquals(listOf("a.app","hidden.app"),peers[10000]);assertTrue(DiscoveryPolicy.block("a.app",10000,peers[10000],false,false).contains("공유"))}
    @Test fun confirmedSoleUidProceeds(){val peers=CompletePackageScan.confirmedUidMap(setOf("a.app","other.app"),listOf("a.app" to 10000)){listOf("a.app")};assertEquals("",DiscoveryPolicy.block("a.app",10000,peers[10000],false,false))}
    @Test fun missingKnownUidPeerAborts(){try{CompletePackageScan.confirmedUidMap(setOf("a.app","b.app"),listOf("a.app" to 10000,"b.app" to 10000)){listOf("a.app")};fail()}catch(_:IllegalArgumentException){}}
    @Test fun newPeerOutsideNamePassAborts(){try{CompletePackageScan.confirmedUidMap(setOf("a.app"),listOf("a.app" to 10000)){listOf("a.app","new.app")};fail()}catch(_:IllegalArgumentException){}}
    @Test fun duplicatePeerResponseAborts(){try{CompletePackageScan.confirmedUidMap(setOf("a.app"),listOf("a.app" to 10000)){listOf("a.app","a.app")};fail()}catch(_:IllegalArgumentException){}}
    @Test fun failedPeerGetterNeverPublishesMap(){var published=false;try{CompletePackageScan.confirmedUidMap(setOf("a.app","b.app"),listOf("a.app" to 10000,"b.app" to 10001)){if(it==10001)throw IllegalStateException("TransactionTooLargeException") else listOf("a.app")};published=true}catch(_:Exception){};assertFalse(published)}
}
