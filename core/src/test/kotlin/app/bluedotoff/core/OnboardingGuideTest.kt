package app.bluedotoff.core

import org.junit.Test
import org.junit.Assert.*
class OnboardingGuideTest {
    @Test fun appOwnedChoicesAndAutomaticChecksHaveNoDestinationExample(){
        listOf(SetupStage.WELCOME,SetupStage.APPLICATION_SCOPE,SetupStage.AUTOMATIC_MANAGEMENT,SetupStage.VERIFYING,SetupStage.FINAL_CHECK).forEach{assertNull(OnboardingGuide.current(it))}
    }
    @Test fun currentActualStageSelectsExactlyOneImage(){assertEquals(GuideStep.DEVELOPER,OnboardingGuide.current(SetupFlow.stage(SetupFacts(developer=false)))?.step);assertEquals(GuideStep.WIRELESS,OnboardingGuide.current(SetupFlow.stage(SetupFacts(wireless=false)))?.step)}
    @Test fun completedStepsSkippedBySavedConnection(){assertNull(OnboardingGuide.current(SetupFlow.stage(SetupFacts(paired=true))));assertNull(OnboardingGuide.current(SetupStage.READY));assertNull(OnboardingGuide.current(SetupStage.PAIRING))}
    @Test fun codeScreenThenNotificationInput(){assertEquals(GuideStep.CODE_PAGE,OnboardingGuide.current(SetupStage.WAIT_FOR_PAIRING_ADVERTISEMENT)?.step);assertEquals(GuideStep.CODE_INPUT,OnboardingGuide.current(SetupStage.PAIRING_NOTIFICATION_READY)?.step)}
    @Test fun notificationDeniedUsesSplitScreenGuide(){assertTrue(OnboardingGuide.current(SetupStage.PAIRING_NOTIFICATION_READY,notifications=false)!!.manual);assertTrue(OnboardingGuide.current(SetupStage.WAIT_FOR_PAIRING_ADVERTISEMENT,manual=true)!!.manual)}
}
