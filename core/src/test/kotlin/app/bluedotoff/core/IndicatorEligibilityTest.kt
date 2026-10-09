package app.bluedotoff.core

import org.junit.Test
import org.junit.Assert.*

class IndicatorEligibilityTest {
    private fun facts(flags:Int=0x300)=IndicatorFacts(true,true,true,true,flags,4)
    private fun app(pkg:String="test.app",flags:Int=0x300,launcher:Boolean=false,preinstalled:Boolean=false)=
        DiscoveredApp(pkg,pkg,10001,preinstalled,launcher,indicator=IndicatorClassifier.classify(facts(flags)),hasLocationDeclaration=true)
    @Test fun grantedAndDeniedUseDifferentBits(){
        assertFalse(IndicatorClassifier.logicalSystem(0x100,true));assertTrue(IndicatorClassifier.logicalSystem(0x100,false))
        assertTrue(IndicatorClassifier.logicalSystem(0x200,true));assertFalse(IndicatorClassifier.logicalSystem(0x200,false))
    }
    @Test fun nonsystemForegroundModePreflightIsCandidate(){assertEquals(IndicatorEligibility.STRONG_CANDIDATE,IndicatorClassifier.classify(facts()).eligibility)}
    @Test fun internalServiceGrantWithoutSensitivityIsExempt(){assertEquals(IndicatorEligibility.EXEMPT_SYSTEM_USAGE,IndicatorClassifier.classify(facts(0x20)).eligibility)}
    @Test fun deniedSensitiveStateCannotBeWriteCandidate(){val a=IndicatorClassifier.classify(facts().copy(fineGranted=false,rawFineMode=1));assertFalse(a.logicalSystemUsage!!);assertEquals(0x200,a.sensitivityBranch);assertEquals(IndicatorEligibility.INELIGIBLE_PERMISSION_STATE,a.eligibility)}
    @Test fun officialPackageExemptionWinsOverSensitivity(){assertEquals(IndicatorEligibility.EXEMPT_PACKAGE,IndicatorClassifier.classify(facts().copy(packageVisible=false)).eligibility)}
    @Test fun unknownProfileAndFailedReadsAreAdvancedOnly(){assertEquals(IndicatorEligibility.UNKNOWN,IndicatorClassifier.classify(facts().copy(profileSupported=false)).eligibility);assertEquals(IndicatorEligibility.UNKNOWN,IndicatorClassifier.classify(facts().copy(fineFlags=null)).eligibility)}
    @Test fun coarseOnlyIsNotThisFirmwareFineIndicatorCandidate(){assertEquals(IndicatorEligibility.INELIGIBLE_OP,IndicatorClassifier.classify(facts().copy(fineDeclared=false)).eligibility)}
    @Test fun noLauncherAndPreinstallationDoNotChangeCandidate(){val a=app(preinstalled=true);assertEquals(listOf(a),PickerPolicy.list(listOf(a),emptySet(),PickerView.CANDIDATES,AppFilter.ALL,"",true))}
    @Test fun dataClearDoesNotMakeGrantedUserFacingOffAppsDisappear(){
        val oldHidden=(1..74).map{app("test.hidden$it",0x200,launcher=true)}
        val stillVisible=listOf(app("test.new1"),app("test.new2"))
        assertEquals(76,PickerPolicy.list(oldHidden+stillVisible,emptySet(),PickerView.CANDIDATES,AppFilter.ALL,"",true).size)
        assertTrue(oldHidden.all{PickerPolicy.alreadyNotSensitive(it)})
        assertTrue(oldHidden.all{it.indicator.eligibility==IndicatorEligibility.EXEMPT_SYSTEM_USAGE})
    }
    @Test fun offInclusionCannotOverridePackageExemptionDeniedBranchOrOwnership(){
        val a=app(flags=0x200,launcher=true)
        val excluded=listOf(a.copy(internalService=true),a.copy(launcher=false),a.copy(uid=1000),a.copy(block="shared"),
            a.copy(indicator=IndicatorAssessment(IndicatorEligibility.EXEMPT_PACKAGE)),
            a.copy(indicator=IndicatorAssessment(IndicatorEligibility.EXEMPT_SYSTEM_USAGE,true,0x200)))
        assertTrue(PickerPolicy.list(excluded,emptySet(),PickerView.CANDIDATES,AppFilter.ALL,"",true).isEmpty())
    }
    @Test fun advancedRetainsExemptAndUnknown(){val a=app(flags=0x20);val b=app("test.unknown").copy(indicator=IndicatorAssessment());assertEquals(2,PickerPolicy.list(listOf(a,b),emptySet(),PickerView.ADVANCED,AppFilter.ALL,"",true).size)}
    @Test fun managedHiddenTargetSurvivesEvenRecentWithoutAccess(){val a=app(flags=0x200);assertEquals(listOf(a),PickerPolicy.list(listOf(a),setOf(a.packageName),PickerView.CANDIDATES,AppFilter.RECENT,"",true))}
    @Test fun noPackageNameBlacklist(){val a=app("com.samsung.android.mcfds");val b=app("test.other");assertEquals(a.indicator,b.indicator);assertEquals(2,PickerPolicy.list(listOf(a,b),emptySet(),PickerView.CANDIDATES,AppFilter.ALL,"",true).size)}
    @Test fun indicatorAndWriteOwnershipAreIndependent(){val core=app().copy(uid=1000,block="core");assertEquals(IndicatorEligibility.STRONG_CANDIDATE,core.indicator.eligibility);assertEquals(listOf(core),PickerPolicy.list(listOf(core),emptySet(),PickerView.CANDIDATES,AppFilter.ALL,"",true));assertFalse(core.canCheck);assertEquals(listOf(core),PickerPolicy.list(listOf(core),emptySet(),PickerView.ADVANCED,AppFilter.ALL,"",true))}
}
