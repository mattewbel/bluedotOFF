package app.bluedotoff.core

import org.junit.Assert.*
import org.junit.Test

class SetupFlowTest {
    @Test fun recommendationRequiresVerificationAndAnUnresolvedFirstRunChoice(){
        val f=SetupFacts(paired=true,managementChoiceResolved=false)
        assertEquals(SetupStage.CONNECTING,SetupFlow.stage(f))
        assertEquals(SetupStage.AUTOMATIC_MANAGEMENT,SetupFlow.stage(f.copy(verified=true)))
        assertEquals(SetupStage.ENABLING_MANAGEMENT,SetupFlow.stage(f.copy(verified=true,managing=true)))
        assertEquals(SetupStage.READY,SetupFlow.stage(f.copy(verified=true,managementChoiceResolved=true)))
        assertEquals(SetupStage.AUTOMATIC_MANAGEMENT,SetupFlow.stage(f.copy(verified=true,wireless=false,completedOffline=true)))
    }
    private fun stage(f:SetupFacts)=SetupFlow.stage(f)
    @Test fun autoBlockerExplainsTheGreyedOutWirelessSwitch(){
        val blocked=SetupFlow.presentation(SetupFacts(paired=true,wireless=false,autoBlocked=true))
        assertEquals(SetupStage.WIRELESS_DEBUGGING_OFF,blocked.stage);assertEquals(SetupAction.SECURITY_SETTINGS,blocked.action);assertTrue(blocked.title.contains("자동 차단"))
        // Without a refused write the app cannot know; the normal step still names Auto Blocker for a greyed-out switch.
        val normal=SetupFlow.presentation(SetupFacts(paired=true,wireless=false))
        assertEquals(SetupAction.WIRELESS_SETTINGS,normal.action);assertTrue(normal.body.contains("자동 차단"))
    }
    @Test fun firstUseHasOneNextAction(){val p=SetupFlow.presentation(SetupFacts(welcomed=false,notificationResolved=false,developer=false,wireless=false));assertEquals(SetupStage.WELCOME,p.stage);assertEquals(SetupAction.START,p.action)}
    @Test fun notificationIsExplainedBeforeSettings(){assertEquals(SetupStage.NOTIFICATION_PERMISSION,stage(SetupFacts(notificationResolved=false,developer=false)))}
    @Test fun grantedOrDeclinedNotificationsBothProceed(){assertEquals(SetupStage.DEVELOPER_OPTIONS_OFF,stage(SetupFacts(notificationResolved=true,developer=false)))}
    @Test fun developerResumeSkipsCompletedStep(){assertEquals(SetupStage.WIRELESS_DEBUGGING_OFF,stage(SetupFacts(wireless=false)))}
    @Test fun pairedWirelessOffNeverRequestsNewCode(){val p=SetupFlow.presentation(SetupFacts(paired=true,wireless=false));assertEquals(SetupAction.WIRELESS_SETTINGS,p.action);assertTrue(p.body.contains("다시 입력할 필요가 없어요"))}
    @Test fun pairedUserSkipsUnansweredNotificationOffer(){assertEquals(SetupStage.CONNECTING,stage(SetupFacts(paired=true,notificationResolved=false)))}
    @Test fun nearbyIsOnlyRequestedForNewPairing(){assertEquals(SetupStage.NEARBY_PERMISSION,stage(SetupFacts(nearbyResolved=false)));assertEquals(SetupStage.CONNECTING,stage(SetupFacts(paired=true,nearbyResolved=false)))}
    @Test fun noAdvertisementCannotOfferCodeReply(){val f=SetupFacts(sessionStarted=true);assertEquals(SetupStage.WAIT_FOR_PAIRING_ADVERTISEMENT,stage(f));assertEquals(PairNotice.GUIDE,PairNoticePolicy.desired(stage(f),true))}
    @Test fun advertisementReplacesGuideWithCode(){val f=SetupFacts(sessionStarted=true,pairAdvertisement=true);assertEquals(SetupStage.PAIRING_NOTIFICATION_READY,stage(f));assertEquals(PairNotice.CODE,PairNoticePolicy.desired(stage(f),true))}
    @Test fun disappearanceRemovesCodeAndOnlyKeepsGuide(){assertEquals(PairNotice.GUIDE,PairNoticePolicy.desired(stage(SetupFacts(sessionStarted=true,pairAdvertisement=false)),true))}
    @Test fun deniedNotificationNeverBlocksFacts(){val f=SetupFacts(sessionStarted=true,pairAdvertisement=true);assertEquals(SetupStage.PAIRING_NOTIFICATION_READY,stage(f));assertEquals(PairNotice.NONE,PairNoticePolicy.desired(stage(f),false))}
    @Test fun inFlightPairingDoesNotDependOnAdvertisement(){assertEquals(SetupStage.PAIRING,stage(SetupFacts(pairing=true,pairAdvertisement=false)))}
    @Test fun pairingAndSuccessLeaveNoNotification(){for(s in listOf(SetupStage.PAIRING,SetupStage.CONNECTING,SetupStage.VERIFYING,SetupStage.READY,SetupStage.SESSION_EXPIRED))assertEquals(PairNotice.NONE,PairNoticePolicy.desired(s,true))}
    @Test fun connectionMustBeFreshlyVerified(){assertEquals(SetupStage.CONNECTING,stage(SetupFacts(paired=true)));assertEquals(SetupStage.READY,stage(SetupFacts(paired=true,verified=true)))}
    @Test fun expiredSessionNeedsExplicitResume(){val p=SetupFlow.presentation(SetupFacts(paired=true,error=SetupStage.SESSION_EXPIRED));assertEquals(SetupAction.RESUME,p.action)}
    @Test fun lossOfWirelessOverridesCachedReady(){assertEquals(SetupStage.WIRELESS_DEBUGGING_OFF,stage(SetupFacts(paired=true,verified=true,wireless=false)))}
    @Test fun authorizationUncertaintyUsesConservativeWords(){val p=SetupFlow.presentation(SetupFacts(paired=true,error=SetupStage.CONNECTION_RECOVERY));assertEquals(SetupAction.RETRY_CONNECTION,p.action);assertTrue(p.body.contains("수 있어요"));assertFalse(p.body.contains("해제되었습니다"))}
    @Test fun wrongCodeHasPlainRetryAction(){val p=SetupFlow.presentation(SetupFacts(error=SetupStage.PAIRING_RETRY));assertEquals(SetupAction.RETRY_PAIRING,p.action);assertTrue(p.body.contains("새 코드"))}
    @Test fun explicitRepairDoesNotRequireDeletingExistingKey(){assertEquals(SetupStage.WAIT_FOR_PAIRING_ADVERTISEMENT,stage(SetupFacts(paired=true,forcePairing=true,sessionStarted=true)))}
    @Test fun readyCannotBeRestoredFromAUiStepNumber(){assertEquals(SetupStage.DEVELOPER_OPTIONS_OFF,stage(SetupFacts(paired=true,verified=true,developer=false)))}
    @Test fun allRecoverableErrorsProvideNextAction(){for(s in listOf(SetupStage.PAIRING_RETRY,SetupStage.CONNECTION_RECOVERY,SetupStage.DISCOVERY_RECOVERY,SetupStage.SESSION_EXPIRED))assertNotEquals(SetupAction.NONE,SetupFlow.presentation(SetupFacts(error=s)).action)}
    @Test fun noviceCopyContainsNoTransportDetails(){for(s in SetupStage.entries){val p=SetupFlow.presentation(SetupFacts(error=s));for(word in listOf("TLS","mDNS","Binder","SELinux","ABI","AppOps","host key"))assertFalse(p.title.contains(word)||p.body.contains(word))}}
    @Test fun nearbyPermissionPrecedesWirelessEvenWhenWirelessIsAlreadyOn(){
        for(wireless in listOf(false,true))assertEquals(SetupStage.NEARBY_PERMISSION,stage(SetupFacts(wireless=wireless,nearbyResolved=false)))
        assertEquals(SetupStage.WIRELESS_DEBUGGING_OFF,stage(SetupFacts(wireless=false,nearbyResolved=true)))
    }
    @Test fun finalCheckFollowsBothExplicitChoicesAndCannotBeSkippedByPairing(){
        val f=SetupFacts(paired=true,verified=true,managementChoiceResolved=false,applicationScopeResolved=false,finalCheckResolved=false)
        assertEquals(SetupStage.AUTOMATIC_MANAGEMENT,stage(f))
        assertEquals(SetupStage.APPLICATION_SCOPE,stage(f.copy(managementChoiceResolved=true)))
        assertEquals(SetupStage.FINAL_CHECK,stage(f.copy(managementChoiceResolved=true,applicationScopeResolved=true)))
        assertEquals(SetupStage.READY,stage(f.copy(managementChoiceResolved=true,applicationScopeResolved=true,finalCheckResolved=true)))
    }
    @Test fun skippedWifiHasActualCompletionButUnresolvedPermissionDoesNot(){
        val steps=SetupProgress.steps(SetupFacts(wireless=false,nearbyResolved=false))
        assertTrue(steps.single{it.number==4}.complete)
        assertTrue(steps.single{it.number==5}.current)
        assertFalse(steps.single{it.number==5}.complete)
        assertFalse(steps.single{it.number==6}.complete)
        assertEquals(11,steps.size)
    }
}
