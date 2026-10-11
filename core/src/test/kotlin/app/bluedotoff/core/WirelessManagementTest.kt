package app.bluedotoff.core

import org.junit.Assert.*
import org.junit.Test

class WirelessManagementTest {
    @Test fun everyEnableConditionIsRequiredIncludingWirelessOff(){
        for(mask in 0 until 128){
            fun b(n:Int)=mask and (1 shl n)!=0
            val f=WirelessFacts(b(0),b(1),b(2),b(3),b(4),b(5),b(6))
            assertEquals("mask=$mask",mask==63,WirelessManagementPolicy.mayEnable(f))
        }
    }
    @Test fun retainedPermissionAfterDataClearIsNotConsent(){assertFalse(WirelessManagementPolicy.mayEnable(WirelessFacts(true,true,true,false,true,true,false)))}
    @Test fun bootAndBackgroundCannotEnable(){assertFalse(WirelessManagementPolicy.mayEnable(WirelessFacts(false,true,true,true,true,true,false)))}
    @Test fun permissionLossFallsBackWithoutAutomaticWrite(){assertFalse(WirelessManagementPolicy.mayEnable(WirelessFacts(true,true,true,true,false,true,false)))}
    @Test fun exitAndSocketMustBothBeConfirmedBeforeOff(){
        assertTrue(WirelessManagementPolicy.mayDisable(true,true,true,true,true))
        assertFalse(WirelessManagementPolicy.mayDisable(true,true,false,true,true))
        assertFalse(WirelessManagementPolicy.mayDisable(true,true,true,false,true))
        assertFalse(WirelessManagementPolicy.mayDisable(true,true,true,true,false))
        assertFalse(WirelessManagementPolicy.mayDisable(false,true,true,true,true))
    }
    private fun row(i:Int)=DiscoveredApp("test.app$i","app$i",10000+i,hasLocationDeclaration=true)
    @Test fun selectAllCoversOffscreenRowsAndPreservesHiddenSelections(){
        val rows=(1..101).map(::row);val hidden=setOf("test.hidden")
        val selected=PickerSelection.toggle(rows,hidden)
        assertEquals(102,selected.size);assertEquals(SelectionState.ALL,PickerSelection.state(rows,selected))
        assertEquals(hidden,PickerSelection.toggle(rows,selected))
    }
    @Test fun blockedAndUnconfirmedNeverEnterSelectAll(){
        val rows=listOf(row(1),row(2).copy(block="shared"),row(3).copy(check=CandidateCheck.NEEDS_CHECK),row(4).copy(check=CandidateCheck.BLOCKED))
        assertEquals(setOf("test.app1"),PickerSelection.toggle(rows,emptySet()))
        assertEquals(SelectionState.NONE,PickerSelection.state(emptyList(),emptySet()))
    }
    @Test fun partialSelectionIsIndeterminate(){assertEquals(SelectionState.SOME,PickerSelection.state(listOf(row(1),row(2)),setOf("test.app1")))}
    @Test fun cacheIsInvalidAfterPackagesBuildTtlOrClockReset(){
        val stamp=ScanStamp("build",3,100)
        assertTrue(stamp.valid("build",3,1_800_099));assertFalse(stamp.valid("other",3,101))
        assertFalse(stamp.valid("build",4,101));assertFalse(stamp.valid("build",3,1_800_100));assertFalse(stamp.valid("build",3,99))
    }
    @Test fun futureSetupIsSeparateFromAbiVerification(){
        assertTrue(FirmwareSupport.setup("Samsung",37,"90000"));assertTrue(FirmwareSupport.setup("samsung",38,"91000"))
        assertFalse(FirmwareSupport.setup("samsung",36,"90000"));assertFalse(FirmwareSupport.setup("samsung",37,"80000"))
        assertFalse(FirmwareSupport.setup("samsung",37,"unknown"));assertFalse(FirmwareSupport.setup("other",38,"91000"))
    }
    @Test fun advancedNeedsConfirmedLocationRelevance(){
        val unrelated=row(1).copy(hasLocationDeclaration=null)
        val opsOnly=row(2).copy(hasLocationDeclaration=false,hasRelevantOps=true)
        assertEquals(listOf(opsOnly),PickerPolicy.list(listOf(unrelated,opsOnly),emptySet(),PickerView.ADVANCED,AppFilter.ALL,"",true))
    }
    @Test fun completedOfflineSetupIsOnlyReadyWithVerifiedPairedReceipt(){
        assertEquals(SetupStage.READY,SetupFlow.stage(SetupFacts(paired=true,wireless=false,verified=true,completedOffline=true)))
        assertEquals(SetupStage.WIRELESS_DEBUGGING_OFF,SetupFlow.stage(SetupFacts(paired=true,wireless=false,verified=true)))
        assertEquals(SetupStage.WIFI_NOT_CONNECTED,SetupFlow.stage(SetupFacts(paired=true,wireless=false,verified=true,completedOffline=true,wifiConnected=false)))
    }
    @Test fun wifiConnectionComesBeforeWirelessAndPairing(){
        assertEquals(SetupStage.WIFI_NOT_CONNECTED,SetupFlow.stage(SetupFacts(wifiConnected=false,wireless=false,pairAdvertisement=true)))
        assertEquals(SetupStage.DEVELOPER_OPTIONS_OFF,SetupFlow.stage(SetupFacts(developer=false,wifiConnected=false)))
    }
}
