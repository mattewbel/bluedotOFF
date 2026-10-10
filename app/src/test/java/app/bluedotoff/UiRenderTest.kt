package app.bluedotoff

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import org.robolectric.Shadows.shadowOf
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import app.bluedotoff.core.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit
import androidx.test.espresso.IdlingPolicies

/** Real Compose renderer with simulated data on the JVM. No phone, Shizuku or metadata writes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="ko-rKR-w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiRenderTest {
    @Test fun interruptedBatchKeepsTopSummaryWithoutRepeatedAppErrorLists(){
        val base=model(applied=true)
        val error="화면을 떠나 남은 앱은 변경하지 않았습니다. 완료된 적용과 등록 기록은 유지됩니다."
        val results=listOf(BatchResult("demo.weather",base.entries.single()),BatchResult("demo.other",error="앞선 작업의 복원 또는 연결 확인이 필요해 변경하지 않았습니다."))
        val outcome=BatchOutcome(true,results,base.entries,emptyList(),error=error)
        for(page in listOf(Page.HOME,Page.PICK,Page.HISTORY,Page.REAPPLY)){
            render("26-interrupted-${page.name.lowercase()}",base.copy(page=page,batchResults=results,completion=outcome))
            compose.onNodeWithText(outcome.summary()).assertExists()
            compose.onNodeWithText(error).assertExists()
            compose.onNodeWithText("demo.other: 앞선 작업의 복원 또는 연결 확인이 필요해 변경하지 않았습니다.").assertDoesNotExist()
        }
    }
    @Test fun applyingExplainsIndicatorMayReturnButRestoreKeepsItsOwnInstructions(){
        val hint="일부 앱은 Android가 파란 위치 표시를 다시 켤 수 있어요. 적용 직후에도 풀릴 수 있으니, 필요하면 홈에서 다시 적용해주세요."
        render("26-apply-note",model().copy(busy=true,progress=BatchProgress(1,3,BatchPhase.CHECKING)))
        compose.onNodeWithText(hint).assertExists()
        render("26-restore-note",model(applied=true).copy(busy=true,operationHide=false,progress=BatchProgress(1,3,BatchPhase.CHECKING)))
        compose.onNodeWithText(hint).assertDoesNotExist()
    }
    @Test fun homeCountOpensOnlyNeededAppsAndExplicitActionUsesOnlyThoseTargets(){
        val base=model(applied=true).entries.single()
        val needed=base.copy(identity=base.identity.copy(packageName="demo.needs"),label="재적용 대상",state=TargetState.NEEDS_REAPPLY_POLICY)
        var called:List<String>?=null;var allCalled=false
        val m=model(applied=true).copy(entries=listOf(base,needed))
        controller.get().setContent{BlueDotApp(m,{}, {}, {}, {}, {allCalled=true}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},reapplyNeeded={called=it})}
        compose.onNodeWithText("1개 앱 · 재적용 필요").performClick()
        compose.onNodeWithText("재적용이 필요한 앱").assertExists()
        compose.onNode(hasText("재적용 대상") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText("날씨") and hasAnyAncestor(isDialog())).assertDoesNotExist()
        captureDialog("25-reapply-needed")
        org.junit.Assert.assertNull(called);org.junit.Assert.assertFalse(allCalled)
        compose.onNodeWithText("1개 앱 재적용").performClick()
        org.junit.Assert.assertEquals(listOf("demo.needs"),called)
        org.junit.Assert.assertFalse(allCalled)
        compose.onNodeWithText("재적용이 필요한 앱").assertDoesNotExist()
    }
    @Test fun reapplyDialogShowsSharedPeersAndCancelDoesNotApply(){
        val base=model(applied=true).entries.single();val identities=sharedMembers().map{it.identity}
        val entries=identities.mapIndexed{i,id->base.copy(identity=id,label=if(i==0)"전화" else "114 서비스",groupIdentities=identities,state=if(i==0)TargetState.NEEDS_REAPPLY_UPDATE else TargetState.APPLIED)}
        val m=model(applied=true).copy(entries=listOf(base)+entries)
        var called=false
        controller.get().setContent{BlueDotApp(m,{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},reapplyNeeded={called=true})}
        compose.onNodeWithText("1개 앱 · 재적용 필요").performClick()
        compose.onNode(hasText("전화") and hasAnyAncestor(isDialog())).assertExists();compose.onNode(hasText("114 서비스") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText("날씨") and hasAnyAncestor(isDialog())).assertDoesNotExist()
        compose.onNodeWithText("2개 앱 재적용").assertExists()
        compose.onNodeWithText("위치 표시 설정을 공유하는 앱은 묶음 전체를 함께 재적용해요.").assertExists()
        compose.onNodeWithText("취소").performClick();org.junit.Assert.assertFalse(called)
    }
    @Test @Config(qualifiers="en-rUS-w412dp-h915dp-night-xxhdpi") fun offlineReapplyDialogUsesConnectionActionAndSimpleEnglish(){
        var opened=false;var applied=false
        val m=model().copy(connected=false,automaticReady=false)
        controller.get().setContent{BlueDotApp(m,{}, {}, {}, {}, {}, {}, {opened=true}, {}, {}, {}, {}, {}, {}, {}, {},reapplyNeeded={applied=true})}
        compose.onNodeWithText("Reapply needed: 1").performClick()
        compose.onNodeWithText("Apps that need reapplying").assertExists()
        captureDialog("25-reapply-offline-en")
        compose.onNodeWithText("Reapply to 1 app").assertDoesNotExist()
        compose.onNode(hasText(UiText.text(controller.get(),"연결 설정 열기")) and hasAnyAncestor(isDialog())).performClick()
        org.junit.Assert.assertTrue(opened);org.junit.Assert.assertFalse(applied)
    }
    @Test fun reapplyPageShowsNeededAppsAboveAlreadyAppliedApps(){
        val base=model(applied=true).entries.single()
        val needed=base.copy(identity=base.identity.copy(packageName="demo.needs"),label="다시 적용할 앱",state=TargetState.NEEDS_REAPPLY_BOOT)
        render("25-reapply-order",model(page=Page.REAPPLY,applied=true).copy(entries=listOf(base,needed)))
        val first=compose.onNodeWithText("다시 적용할 앱").fetchSemanticsNode().boundsInRoot
        val applied=compose.onNodeWithText("날씨").fetchSemanticsNode().boundsInRoot
        assertTrue(first.bottom<=applied.top)
    }
    @Test fun finalEleventhStepShowsActualAppProgressAndClosingIsNotSuccess(){
        render("84-final-scan-progress",model(gate=Gate.CHECKING).copy(scopeResolved=true,scanProgress=ScanProgress(389,778)))
        compose.onNodeWithText("연결 준비 · 11 / 11").assertExists()
        compose.onNodeWithText("389 / 778개 앱 확인 · 50%").assertExists()
        render("84-final-scan-closing",model(gate=Gate.CHECKING).copy(scopeResolved=true,scanProgress=ScanProgress(778,778)))
        compose.onNodeWithText("778개 앱 확인 완료 · 마무리 중…").assertExists()
        compose.onNodeWithText("앱 확인을 마쳤어요. 기록과 연결 종료를 확인하고 있어요.").assertExists()
        compose.onNodeWithText("준비 완료").assertDoesNotExist()
    }
    @Test fun readOnlyScopePreviewKeepsHomeVisibleWithoutApplyAnimation(){
        render("84-scope-home",model(applied=true).copy(busy=true,scopePreview=true))
        compose.onNodeWithText("등록한 앱 · 1개").assertExists()
        compose.onNodeWithText("안전하게 적용을 확인하고 있습니다").assertDoesNotExist()
        compose.onNodeWithText("안전하게 복원을 확인하고 있습니다").assertDoesNotExist()
    }
    @Test fun restoreAllUsesCountedSharedStyleConfirmationAndExplicitAction(){
        // Restore moved from Settings to the Home "앱 복원" button.
        render("84-restore-settings",model(applied=true))
        compose.onNodeWithText("앱 복원").performScrollTo().performClick()
        compose.onNodeWithText("골라서 복원").assertExists()
        compose.onNodeWithText("모두 복원").performClick()
        compose.onNodeWithText("등록한 1개 앱을 원래 표시 상태로 복원할까요?").assertExists()
        compose.waitForIdle()
        val view=checkNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog().window).decorView
        val bmp=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        compose.runOnIdle{view.draw(Canvas(bmp))}
        File(checkNotNull(System.getProperty("screenshotDir")),"84-restore-all.png").outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)}
        bmp.recycle()
        compose.onNodeWithText("취소").performClick()
        compose.onNodeWithText("등록한 1개 앱을 원래 표시 상태로 복원할까요?").assertDoesNotExist()
    }
    @Test fun homeSeparatesReapplyAndUnknownFromVerifiedApps(){
        val base=model(applied=true).entries.single()
        val entries=(0 until 3).map{base.copy(identity=base.identity.copy(packageName="demo.app$it"),label="앱 $it")}
        render("83-home-attention",model(applied=true).copy(entries=listOf(entries[0],entries[1].copy(state=TargetState.NEEDS_REAPPLY_POLICY),entries[2].copy(state=TargetState.SHIZUKU_REQUIRED))))
        compose.onNodeWithText("일부 앱의\n재적용이 필요해요").assertExists()
        compose.onNodeWithText("1개 앱 bluedotOFF 적용중").assertExists()
        compose.onNodeWithText("1개 앱 · 재적용 필요").assertExists()
        compose.onNodeWithText("1개 앱 · 상태 확인 필요").assertExists()
    }
    @Test @Config(qualifiers="en-rUS-w412dp-h915dp-night-xxhdpi") fun homeAttentionUsesPlainEnglish(){
        val base=model(applied=true).entries.single()
        render("83-home-attention-en",model(applied=true).copy(entries=listOf(base.copy(state=TargetState.NEEDS_REAPPLY_POLICY))))
        compose.onNodeWithText("Some apps\nneed reapplying").assertExists()
        compose.onNodeWithText("Reapply needed: 1").assertExists()
        compose.onNodeWithText("Keep completed apps as they are. Check only the apps that need attention.").assertExists()
    }
    @Test fun offTargetsRemainPickableAfterJournalResetWithoutFalseAppliedStatus(){
        val hidden=DiscoveredApp("demo.old","기존 위치 앱",10001,launcher=true,indicator=IndicatorAssessment(IndicatorEligibility.EXEMPT_SYSTEM_USAGE,true,0x100),hasLocationDeclaration=true)
        val m=model(page=Page.PICK).copy(entries=emptyList(),discovered=listOf(hidden),historyAvailable=true)
        render("76-picker-cleared-journal",m)
        compose.onNodeWithText("기존 위치 앱").assertExists()
        compose.onNodeWithText("이미 적용된 앱").assertExists()
        compose.onNodeWithText("등록한 앱").assertDoesNotExist()
        compose.onNodeWithText("이미 적용된 앱은 위치 표시가 꺼져 있어요. 새로 등록하면 이 꺼짐 상태가 복원 기준이 돼요.").assertExists()
    }
    @Test fun welcomeAndConnectionUseTheSameElevenStepDisplay(){
        render("75-welcome",model(gate=Gate.WELCOME));compose.onNodeWithText("연결 준비 · 1 / 11").assertExists()
        render("75-connection",model(gate=Gate.NOT_RUNNING));compose.onNodeWithText("연결 준비 · 6 / 11").assertExists()
        render("75-verifying",model(gate=Gate.CHECKING));compose.onNodeWithText("연결 준비 · 11 / 11").assertExists()
    }
    @Test @Config(qualifiers="en-rUS-w412dp-h915dp-xxhdpi") fun englishStartupKeepsActionAndProgress(){
        render("75-welcome-en",model(gate=Gate.WELCOME))
        compose.onNodeWithText("Connection setup · 1 / 11").assertExists()
        compose.onNodeWithText(UiText.text(controller.get(),"시작하기")).assertIsEnabled()
    }
    @Test @Config(qualifiers="ko-rKR-w412dp-h915dp-night-xxhdpi") fun darkStartup(){render("75-welcome-dark",model(gate=Gate.WELCOME))}
    @Test @Config(fontScale=1.5f) fun startupWithLargeText(){render("75-welcome-large",model(gate=Gate.WELCOME));compose.onNodeWithText("시작하기").performScrollTo().assertIsDisplayed()}
    @Test @Config(qualifiers="ko-rKR-w960dp-h750dp-xhdpi") fun wideStartup(){render("75-welcome-wide",model(gate=Gate.WELCOME));compose.onNodeWithText("시작하기").assertIsDisplayed()}
    @Test @Config(qualifiers="en-rUS-w412dp-h915dp-xxhdpi") fun englishBatchShowsRealCountsAndReservesCompletionForCleanup(){
        render("74-progress-en",model().copy(busy=true,progress=BatchProgress(35,70,BatchPhase.CHECKING)))
        compose.onNodeWithText("35 / 70 apps checked · 50%").assertExists()
        compose.onNodeWithText("Applying and verifying safely").assertExists()
    }
    @Test fun finalClosingDoesNotShowOneHundredPercentEarly(){
        render("74-progress-closing",model().copy(busy=true,progress=BatchProgress(70,70,BatchPhase.CLOSING)))
        compose.onNodeWithText("70 / 70개 확인 · 마무리 중…").assertExists();compose.onNodeWithText("100%",substring=true).assertDoesNotExist()
    }
    @Test @Config(qualifiers="en-rUS-w412dp-h915dp-xxhdpi") fun completionAndOfflineHomeExplainLastCheck(){
        val base=model(applied=true)
        val outcome=BatchOutcome(true,listOf(BatchResult("demo.weather",base.entries.single())),base.entries,emptyList())
        render("74-completion-en",base.copy(connected=false,automaticReady=true,autoManagement=true,completion=outcome))
        compose.onNodeWithText(UiText.text(controller.get(),outcome.summary())).assertDoesNotExist()
        compose.onNodeWithText("bluedotOFF applied · Last checked").assertExists()
        compose.onNodeWithText("Registered apps · 1").assertExists()
    }
    @Test @Config(fontScale=1.5f) fun progressIsReadableWithLargeText(){render("74-progress-large",model().copy(busy=true,progress=BatchProgress(35,70,BatchPhase.CHECKING)))}
    @Test fun homeUsesCuratedHistoryEvenWhenAllViewWasSelected(){
        val user=DiscoveredApp("test.user","최근 사용자 앱",10001,launcher=true,access=LocationAccess(fine=100),indicatorHint=FineIndicatorHint.SENSITIVE)
        val internal=DiscoveredApp("test.internal","최근 내부 서비스",10002,preinstalled=true,access=LocationAccess(fine=200))
        val m=model().copy(entries=emptyList(),discovered=listOf(internal,user),historyView=HistoryView.ALL,historyAvailable=true)
        org.junit.Assert.assertEquals(internal,m.historyApps.first())
        org.junit.Assert.assertEquals(user,m.recentHistoryApp)
        render("38-home-curated",m)
        compose.onNodeWithText("최근 사용자 앱",substring=true).assertExists()
        compose.onNodeWithText("최근 내부 서비스",substring=true).assertDoesNotExist()
    }
    @Test fun historyTabsCannotChangeHideEligibilityOrJournalEntry(){
        val m=history(true)
        val a=m.discovered.single()
        val all=m.copy(historyView=HistoryView.ALL)
        org.junit.Assert.assertEquals(m.canAct(a),all.canAct(a))
        org.junit.Assert.assertEquals(m.entries,all.entries)
        render("39-history-state-separation",all)
        compose.onNodeWithText("bluedotOFF 적용중 ✓").assertExists()
    }
    @Test fun brandDotSharesTheHeaderAndHomeHasNoEmptyHeroIconRow(){
        render("79-home-light",model(applied=true))
        val icon=compose.onNodeWithContentDescription("bluedotOFF 아이콘").fetchSemanticsNode().boundsInRoot
        val brand=compose.onNodeWithText("bluedotOFF").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(icon.center.x>brand.center.x)
        org.junit.Assert.assertTrue(kotlin.math.abs(icon.center.y-brand.center.y)<30f)
        compose.onNodeWithContentDescription("Blue Dot").assertDoesNotExist()
    }
    @Test @Config(qualifiers="ko-rKR-w412dp-h915dp-night-xxhdpi") fun registeredListAndConnectionNoticeInDarkTheme(){render("79-home-dark-offline",model(applied=true).copy(connected=false,automaticReady=false))}
    @Test @Config(qualifiers="en-rUS-w412dp-h915dp-xxhdpi") fun simpleEnglishHomeAndHiddenPicker(){
        render("79-home-en",model(applied=true))
        val off=DiscoveredApp("demo.off","Example",10002,launcher=true,hasLocationDeclaration=true,indicator=IndicatorAssessment(IndicatorEligibility.EXEMPT_SYSTEM_USAGE,true,0x100))
        render("79-picker-en",model(page=Page.PICK).copy(entries=emptyList(),discovered=listOf(off)))
        compose.onNodeWithText("Already hidden").assertExists()
    }
    @Test fun waterProgressKeepsRealCountsAndHidesThePageBehindIt(){
        render("79-water-half",model().copy(busy=true,progress=BatchProgress(35,70,BatchPhase.CHECKING)))
        compose.onNodeWithText("35 / 70개 확인 · 50%").assertExists()
        compose.onNodeWithText("앱 추가").assertDoesNotExist()
    }
    @Test fun permissionRemovalIsAvailableOnlyAfterOpeningAdvancedSettings(){
        render("79-settings",model(page=Page.SETTINGS).copy(autoPermission=true))
        compose.onNodeWithText("자동 관리 권한 제거").assertDoesNotExist()
        compose.onNodeWithText("고급 설정").performClick()
        compose.onNodeWithText("자동 관리 권한 제거").performScrollTo().assertExists()
    }
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    @Before fun create() { IdlingPolicies.setMasterPolicyTimeout(3,TimeUnit.SECONDS);controller=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible() }
    @After fun destroy() {
        val content=controller.get().findViewById<ViewGroup>(android.R.id.content)
        for(i in 0 until content.childCount)(content.getChildAt(i) as? ComposeView)?.disposeComposition()
        content.removeAllViews();controller.pause().stop().destroy();shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun restoreProgressHasItsOwnLabelAndBlueBackLayer(){render("80-restore-half",model().copy(busy=true,operationHide=false,progress=BatchProgress(35,70,BatchPhase.CHECKING)));compose.onNodeWithText("안전하게 복원을 확인하고 있습니다").assertExists()}
    @Test @Config(qualifiers="ko-rKR-w412dp-h915dp-night-xxhdpi") fun vividBlueHideWaterInDarkTheme(){render("80-hide-half-dark",model().copy(busy=true,progress=BatchProgress(35,70,BatchPhase.CHECKING)))}
    @Test fun successfulRestoreBannerExpiresButErrorsRemain(){
        compose.mainClock.autoAdvance=false
        val base=model();val outcome=BatchOutcome(false,listOf(BatchResult("demo.weather",base.entries.single())),base.entries,emptyList())
        val current=androidx.compose.runtime.mutableStateOf(base.copy(completion=outcome))
        controller.get().setContent{BlueDotApp(current.value,{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})}
        compose.mainClock.advanceTimeBy(1000);compose.onNodeWithText("1개 앱을 원래 표시 상태로 복원했습니다.").assertExists()
        compose.mainClock.advanceTimeBy(4100);compose.onNodeWithText("1개 앱을 원래 표시 상태로 복원했습니다.").assertDoesNotExist()
        compose.mainClock.autoAdvance=true
        compose.runOnIdle{current.value=base.copy(completion=outcome.copy(cleanupWarning="종료 확인 필요"),wirelessWarning="종료 확인 필요")}
        compose.waitForIdle();compose.mainClock.advanceTimeBy(6000);compose.onNodeWithText("종료 확인 필요").assertExists()
        compose.mainClock.autoAdvance=true
    }
    private fun sharedMembers():List<Snapshot>{val names=listOf("demo.phone","demo.provider");return names.mapIndexed{i,p->Snapshot(Identity(p,0,10129,listOf("signer"),1,1,1),if(i==0)"전화" else "114 서비스",names,i==0,i==0,i==0,false,0x300,0x300,0,4,4,false,sharedUserId="shared",coarseDeclared=i==0)}}
    @Test fun groupConsentListsEveryMemberBeforeSelection(){var accepted=false;val m=model(page=Page.PICK).copy(sharedOffer=SharedOffer(sharedMembers()));controller.get().setContent{BlueDotApp(m,{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},sharedAccept={accepted=true})};compose.onNodeWithText("demo.phone").assertExists();compose.onNodeWithText("demo.provider").assertExists();org.junit.Assert.assertFalse(accepted);compose.onNodeWithText("묶음 전체 선택").performClick();org.junit.Assert.assertTrue(accepted)}
    @Test fun failedGroupValidationHasNoSelectionAction(){render("80-shared-blocked",model().copy(sharedOffer=SharedOffer(sharedMembers(),error="공유 앱 묶음 확인이 필요합니다.")));compose.onNodeWithText("묶음 전체 선택").assertDoesNotExist();compose.onNodeWithText("demo.provider").assertExists()}
    @Test @Config(qualifiers="en-rUS-w412dp-h915dp-xxhdpi") fun sharedEnglishExplainsWholeGroup(){render("80-shared-en",model().copy(sharedOffer=SharedOffer(sharedMembers())));compose.onNodeWithText("Select the whole group").assertExists();compose.onNodeWithText("Apps sharing a location setting").assertExists()}
    @Test fun usModelShowsWarningOnlyWhenFlagged(){render("84-home-us-model",model(applied=true).copy(usModel=true));compose.onNodeWithText("미국 판매 모델 안내").assertExists()}
    @Test fun nonUsModelHasNoRegionWarning(){render("84-home-default",model(applied=true));compose.onNodeWithText("미국 판매 모델 안내").assertDoesNotExist()}
    @Test @Config(qualifiers="en-rUS-w412dp-h915dp-xxhdpi") fun usModelWarningIsEnglishOutsideKorean(){render("84-home-us-model-en",model(applied=true).copy(usModel=true));compose.onNodeWithText("US model notice").assertExists()}
    @Test fun welcomeWarnsSomeAppsMayLoseHiding(){render("01-welcome-notice",model(gate=Gate.WELCOME));compose.onNodeWithText("미리 알아두세요").assertExists();compose.onNodeWithText("일부 앱은 적용 후 몇 분 만에 숨김이 풀릴 수 있어요.").assertExists()}
    @Test @Config(qualifiers="en-rUS-w412dp-h915dp-xxhdpi") fun welcomeNoticeIsEnglishOutsideKorean(){render("01-welcome-notice-en",model(gate=Gate.WELCOME));compose.onNodeWithText("Good to know").assertExists()}
    @Test fun homeHasNoWelcomeNotice(){render("01-home-no-notice",model(applied=true));compose.onNodeWithText("미리 알아두세요").assertDoesNotExist()}
    @Test @Config(qualifiers="ko-rKR-w412dp-h915dp-night-xxhdpi") fun emptyBlueHomeKeepsNavigationOnPageBackground(){render("86-home-empty-blue-dark",model().copy(entries=emptyList()));compose.onNodeWithText("설정").assertExists()}
    @Test fun uninstalledAppShowsAsNoActionNeeded(){
        val base=model(applied=true);val gone=base.entries.single().let{it.copy(identity=it.identity.copy(packageName="demo.gone"),label="삭제앱",state=TargetState.MISSING_TARGET,settlingVerified=false)}
        render("87-home-uninstalled",base.copy(entries=base.entries+gone))
        compose.onNodeWithText("삭제앱").assertDoesNotExist();compose.onNodeWithText("등록한 앱 · 1개").assertExists();compose.onNodeWithText("1개 앱 bluedotOFF 적용중").assertExists();compose.onNodeWithText("상태 확인 필요",substring=true).assertDoesNotExist()
    }
    @Test fun advancedSettingsListsOpenSourceLicenses(){
        render("88-settings",model(page=Page.SETTINGS))
        compose.onNodeWithText("고급 설정").performScrollTo().performClick()
        compose.onNodeWithText("오픈소스 라이선스").performScrollTo().performClick()
        compose.onNodeWithText("BoringSSL · Apache License 2.0").assertExists()
        compose.onNodeWithText("AOSP ADB (pairing) · Apache License 2.0").performClick()
        compose.onNodeWithText("The Android Open Source Project",substring=true).assertExists()
    }
    private fun model(applied:Boolean=false,gate:Gate=Gate.READY,page:Page=Page.HOME):UiModel {
        val id=Identity("demo.weather",0,10792,listOf("demo"),1,1,1)
        val entry=Entry(id,"날씨",256,true,if(applied)TargetState.APPLIED else TargetState.NEEDS_REAPPLY_BOOT,1728000000000,"demo","demo",true,settlingVerified=applied)
        return UiModel(gate=gate,page=page,entries=listOf(entry),connected=true,freshPackages=setOf(id.packageName),notifications=true,compatibility="읽기 검사 완료")
    }
    private fun render(name:String,m:UiModel,hide:(String)->Unit={}) {
        controller.get().setContent { BlueDotApp(m,{}, {}, hide, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
        compose.waitForIdle()
        val view=if(m.sharedOffer!=null)checkNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog().window).decorView else controller.get().window.decorView
        val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        compose.runOnIdle { view.draw(Canvas(image)) }
        check(image.width>100&&image.height>100)
        val dir=File(checkNotNull(System.getProperty("screenshotDir")));dir.mkdirs()
        File(dir,"$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG,100,it)) }
    }
    @Test fun welcome(){render("01-welcome",model(gate=Gate.WELCOME));compose.onNodeWithText("시작하기").assertExists()}
    @Test fun missingBackend(){render("02-backend-missing",model(gate=Gate.MISSING));compose.onNodeWithText("연결 설정 열기").assertExists()}
    @Test fun stoppedBackend(){render("03-backend-not-running",model(gate=Gate.NOT_RUNNING));compose.onNodeWithText("연결 설정 열기").assertExists()}
    @Test fun registeredOnlyReadDoesNotClaimToScanAllInstalledApps(){
        render("85-registered-only",model(gate=Gate.CHECKING).copy(checkingRegistered=true,scanProgress=ScanProgress(10,52)))
        compose.onNodeWithText("등록한 앱의 상태를 확인하고 있어요").assertExists()
        compose.onNodeWithText("10 / 52개 앱 확인 · 19%").assertExists()
    }
    @Test fun compatibilityChecking(){render("04-compatibility",model(gate=Gate.CHECKING));compose.onNodeWithText("기기를 확인하고 있습니다").assertExists()}
    @Test fun blueHome(){render("05-blue-home",model());compose.onNodeWithText("앱 추가").assertExists()}
    @Test fun scopeChoiceDoesNotRewindProgressDuringFinalRead(){render("81-scope-final-read",model(gate=Gate.CHECKING).copy(scopeResolved=true));compose.onNodeWithText("연결 준비 · 11 / 11").assertExists();compose.onNodeWithText("연결 준비 · 10 / 11").assertDoesNotExist()}
    @Test fun applying(){render("06-applying",model().copy(busy=true));compose.onNodeWithText("안전하게 적용을 확인하고 있습니다").assertExists()}
    @Test fun appliedHome(){render("07-applied-system-theme",model(applied=true));compose.onNodeWithText("bluedotOFF\n적용완료").assertExists()}
    @Test fun successfulHideUsesHomeSummaryAndRetainsClosureWarnings(){
        val base=model(applied=true)
        val done=BatchOutcome(true,listOf(BatchResult("demo.weather",base.entries.single())),base.entries,emptyList())
        render("24-home-applied",base.copy(completion=done))
        compose.onNodeWithText(done.summary()).assertDoesNotExist()
        compose.onNodeWithText("1개 앱 bluedotOFF 적용중").assertExists()
        compose.onNodeWithText("설정 변경 연결").assertDoesNotExist()
        render("24-home-closure-warning",base.copy(completion=done.copy(cleanupWarning="연결 종료 확인 필요"),wirelessWarning="연결 종료 확인 필요"))
        compose.onNodeWithText("연결 종료 확인 필요").assertExists()
    }
    private fun captureDialog(name:String){
        compose.waitForIdle()
        val view=checkNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog().window).decorView
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        compose.runOnIdle{view.draw(Canvas(bitmap))}
        File(checkNotNull(System.getProperty("screenshotDir")),"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    @Test fun settingsNotificationStatusSharesTheTitleRowAndRecoveryLivesInAdvanced(){
        render("24-settings",model(page=Page.SETTINGS).copy(notifications=true,autoManagement=true,automaticReady=true))
        // Reapply reminders are a switch that shows its own state; the connection status row is gone.
        compose.onNode(androidx.compose.ui.test.isToggleable()and androidx.compose.ui.test.hasText("재적용 알림")).assertIsOn()
        compose.onNodeWithText("연결 관리").assertDoesNotExist();compose.onNodeWithText("앱 삭제 전 모두 복원").assertDoesNotExist()
        compose.onNodeWithText("현재 적용 범위").assertExists()
        compose.onNodeWithText("읽기 전용으로 다시 확인").assertDoesNotExist()
        compose.onNodeWithText("고급 설정").performClick()
        compose.onNodeWithText("읽기 전용으로 다시 확인").performScrollTo().assertExists()
    }
    @Test fun reapply(){render("08-reapply",model(page=Page.REAPPLY));compose.onNodeWithText("모두 재적용").assertExists()}
    @Test @Config(qualifiers="ko-rKR-w412dp-h915dp-night-xxhdpi") fun dark(){render("09-dark",model(applied=true))}
    @Test @Config(qualifiers="ko-rKR-w960dp-h750dp-xhdpi") fun fold(){render("10-fold-wide",model(applied=true))}
    @Test fun autoBlockerOpensACenteredGuideInsteadOfASnackbar(){
        controller.get().setContent { BlueDotApp(model(applied=true).copy(notice=app.bluedotoff.adb.AutoBlocker.MESSAGE),{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
        compose.waitForIdle()
        compose.onNodeWithText("보안 위험 자동 차단을 꺼주세요").assertExists();compose.onNodeWithText("보안 설정 열기").assertExists();compose.onNodeWithText("확인").assertExists()
        compose.onNodeWithText("자동 차단 끄기",substring=true).assertExists()
        val view=checkNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog().window).decorView
        val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);compose.runOnIdle{view.draw(Canvas(image))}
        File(checkNotNull(System.getProperty("screenshotDir")).also{File(it).mkdirs()},"87-auto-blocker-dialog.png").outputStream().use{check(image.compress(Bitmap.CompressFormat.PNG,100,it))}
    }
    @Test fun notificationDenied(){render("11-notifications-denied",model(page=Page.SETTINGS).copy(notifications=false));compose.onNode(androidx.compose.ui.test.isToggleable()and androidx.compose.ui.test.hasText("재적용 알림")).assertIsOff()}
    @Test fun unsupported(){render("12-unsupported",model(gate=Gate.UNSUPPORTED));compose.onNodeWithText("이 기기는 지원하지 않습니다").assertExists();compose.onNodeWithText("bluedotOFF 적용").assertDoesNotExist()}
    @Test fun compatibilityPass(){render("13-ready",model().copy(entries=emptyList()));compose.onNodeWithText("준비 완료").assertExists()}
    @Test fun pickerAndSharedUid(){val e=model().entries.single();val s=Snapshot(e.identity,"날씨",listOf(e.identity.packageName,"shared.peer"),true,true,true,false,0x80301,0x301,0,4,4,false);render("14-picker-shared",model(page=Page.PICK).copy(candidates=listOf(s)));compose.onNodeWithText("다른 앱과 묶여 있어요 · 함께 선택돼요").assertExists();compose.onNodeWithText("현재 적용할 수 없는 앱",substring=true).assertDoesNotExist();compose.onNodeWithText("0개 추가").assertIsNotEnabled()}
    @Test fun cachedAppliedIsNotDisplayedAsVerified(){render("17-cached-stale",model(applied=true).copy(connected=false,freshPackages=emptySet()));compose.onNodeWithText("Blue Dot\nbluedotOFF 적용중").assertDoesNotExist();compose.onNodeWithText("bluedotOFF 적용중 · 마지막 확인 기준").assertExists()}
    @Test fun multiSelectionSurvivesQueryAndReturn(){val m=model(page=Page.PICK);val id=m.entries.single().identity;val a=Snapshot(id,"날씨",listOf(id.packageName),true,true,true,false,0x80301,0x301,0,4,4,false);render("18-multiselect",m.copy(candidates=listOf(a),picked=setOf(id.packageName),query="no match",historyAvailable=true));compose.onNodeWithText("1개 추가").assertIsEnabled();compose.onNodeWithText("1개 선택됨").assertExists();compose.onNodeWithText("선택한 앱을 등록하고 위치 표시 숨김을 적용합니다.").assertExists()}
    @Test fun safeDetail(){val e=model().entries.single();val s=Snapshot(e.identity,"날씨",listOf(e.identity.packageName),true,true,true,false,0x80301,0x301,0,4,4,false);render("15-safe-detail",model(page=Page.DETAIL).copy(selected=e.identity.packageName,candidates=listOf(s)));compose.onNodeWithText("bluedotOFF 재적용").assertIsEnabled()}
    @Test fun sharedUidDetailBlocksHide(){val e=model().entries.single();val s=Snapshot(e.identity,"날씨",listOf(e.identity.packageName,"shared.peer"),true,true,true,false,0x80301,0x301,0,4,4,false);render("16-blocked-detail",model(page=Page.DETAIL).copy(selected=e.identity.packageName,candidates=listOf(s)));compose.onNodeWithText("bluedotOFF 재적용").assertIsNotEnabled()}
    @Test fun singletonUnverifiedShowsExactReason(){val e=model().entries.single();val s=Snapshot(e.identity,"카카오맵",listOf(e.identity.packageName),true,true,true,false,0x300,0x300,0,4,4,false,sharedUserId="daum.uid.shared");render("31-singleton-unverified",model(page=Page.DETAIL).copy(selected=e.identity.packageName,candidates=listOf(s)));compose.onNodeWithText(BlockReason.SINGLETON_UNVERIFIED.message).assertExists();compose.onNodeWithText("bluedotOFF 재적용").assertIsNotEnabled()}
    @Test fun coreUidShowsSpecificReason(){val m=history().copy(entries=emptyList(),historyView=HistoryView.ALL);render("32-core-reason",m.copy(discovered=m.discovered.map{it.copy(uid=1000,block=BlockReason.CORE_UID.message)}));compose.onNodeWithText(BlockReason.CORE_UID.message).assertExists();compose.onNodeWithText("숨기기").assertDoesNotExist()}
    @Test fun persistentShowsSpecificReason(){val m=history().copy(entries=emptyList(),historyView=HistoryView.ALL);render("33-persistent-reason",m.copy(discovered=m.discovered.map{it.copy(block=BlockReason.PERSISTENT_CORE.message)}));compose.onNodeWithText(BlockReason.PERSISTENT_CORE.message).assertExists();compose.onNodeWithText("숨기기").assertDoesNotExist()}
    @Test fun verifiedSingletonHistoryInlineAction(){var called="";val m=history().copy(entries=emptyList());render("34-singleton-inline",m.copy(discovered=m.discovered.map{it.copy(label="카카오맵",sharedUserId="daum.uid.shared",uidClass=UidClass.SINGLETON_SHARED_USER,indicatorHint=FineIndicatorHint.SENSITIVE)})){called=it};compose.onNodeWithText("숨기기").performClick();assert(called=="demo.weather");compose.onNodeWithText("최근 위치 사용").assertExists()}
    @Test fun reviewStateNeverOffersHideOrRestore(){val m=model(page=Page.DETAIL);val e=m.entries.single().copy(state=TargetState.NEEDS_REVIEW,sharedUserId="test.shared",error=OwnershipChangedException().message!!);val s=Snapshot(e.identity,"날씨",listOf(e.identity.packageName),true,true,true,false,0x200,0x300,0,4,4,false,sharedUserId="test.shared",singletonVerified=true);render("35-peer-change-review",m.copy(selected=e.identity.packageName,entries=listOf(e),candidates=listOf(s)));compose.onNodeWithText(e.error).assertExists();compose.onNodeWithText("bluedotOFF 재적용").assertDoesNotExist();compose.onNodeWithText("숨김 해제 · 원래 상태로 복원").assertDoesNotExist()}
    @Test fun peerReviewHistoryHasNoInlineWriteAction(){val m=history().let{it.copy(entries=it.entries.map{e->e.copy(state=TargetState.NEEDS_REVIEW)})};render("36-history-peer-review",m);compose.onNodeWithText("앱 묶음 변경 · 확인 필요").assertExists();compose.onNodeWithText("숨기기").assertDoesNotExist()}
    @Test fun osSensitivityResetHasAccurateReapplyAction(){val m=history().copy(entries=history().entries.map{it.copy(state=TargetState.NEEDS_REAPPLY_POLICY)});render("37-os-policy-reapply",m);compose.onNodeWithText("표시 설정 변경 · 재적용 필요").assertExists();compose.onNodeWithText("재적용").assertIsEnabled();compose.onNodeWithText("bluedotOFF 적용중 ✓").assertDoesNotExist()}
    private fun history(applied:Boolean=false)=model(applied=applied,page=Page.HISTORY).let{m->m.copy(discovered=listOf(DiscoveredApp(m.entries.single().identity.packageName,"날씨",10792,launcher=true,access=LocationAccess(fine=System.currentTimeMillis()))),historyAvailable=true)}
    @Test fun hiddenHistoryShowsVerifiedCheck(){render("19-history-hidden",history(true));compose.onNodeWithText("bluedotOFF 적용중 ✓").assertExists();compose.onNodeWithText("숨기기").assertDoesNotExist()}
    @Test fun unhiddenHistoryDirectActionStaysInline(){var called="";val m=history().copy(entries=emptyList());render("20-history-action",m){called=it};compose.onNodeWithText("숨기기").performClick();assert(called=="demo.weather");compose.onNodeWithText("최근 위치 사용").assertExists()}
    @Test fun allHistoryUnsupportedStillVisibleWithoutAction(){val m=history().copy(entries=emptyList());render("21-history-core",m.copy(historyView=HistoryView.ALL,discovered=m.discovered.map{it.copy(uid=1000,block="지원되지 않는 시스템 구성요소")}));compose.onNodeWithText("지원되지 않는 시스템 구성요소").assertExists();compose.onNodeWithText("숨기기").assertDoesNotExist()}
    @Test fun historyUpdateOffersReapply(){val m=history();render("22-history-update",m.copy(entries=m.entries.map{it.copy(state=TargetState.NEEDS_REAPPLY_UPDATE)}));compose.onNodeWithText("업데이트 · 재적용 필요").assertExists();compose.onNodeWithText("재적용").assertIsEnabled()}
    @Test fun historyFailureUsesTopSummaryAndNotSuccess(){val results=listOf(BatchResult("demo.weather",error="권한 상태 확인 필요"));val m=history().copy(entries=emptyList(),batchResults=results,completion=BatchOutcome(true,results,emptyList(),emptyList(),error="권한 상태 확인 필요"));render("26-history-failure",m);compose.onNodeWithText("권한 상태 확인 필요").assertExists();compose.onNodeWithText("날씨: 권한 상태 확인 필요").assertDoesNotExist();compose.onNodeWithText("bluedotOFF 적용중 ✓").assertDoesNotExist()}
    @Test fun emptyHistory(){render("24-history-empty",model(page=Page.HISTORY).copy(entries=emptyList(),historyAvailable=true));compose.onNodeWithText("최근 위치 사용 기록이 없습니다.").assertExists()}
    @Test fun homeBatchRestoreSelection(){val m=model().copy(editing=true,homePicked=setOf("demo.weather"));render("25-home-batch-restore",m);compose.onNodeWithText("선택한 1개 숨김 해제").assertIsEnabled()}
    private fun densePicker()=model(page=Page.PICK).copy(entries=emptyList(),pickerView=PickerView.ADVANCED,historyAvailable=true,discovered=listOf("날씨","Google","날씨날씨","미세미세","카카오맵","지도","당근","위치 정보를 사용하는 이름이 긴 앱","기본 지도","대기 환경").mapIndexed{i,label->DiscoveredApp("demo.location.$i",label,11000+i,launcher=true,hasLocationDeclaration=true,indicator=IndicatorAssessment(IndicatorEligibility.STRONG_CANDIDATE))})
    @Test fun pickerClassificationAndNoDaemon(){val apps=listOf(DiscoveredApp("user.app","사용자 앱",10000,launcher=true,indicator=IndicatorAssessment(IndicatorEligibility.STRONG_CANDIDATE)),DiscoveredApp("system.app","기본 지도",10001,true,true,indicator=IndicatorAssessment(IndicatorEligibility.STRONG_CANDIDATE)),DiscoveredApp("daemon.app","보이지 않을 서비스",10002));render("26-picker-filters",model(page=Page.PICK).copy(discovered=apps,filter=AppFilter.PREINSTALLED));compose.onNodeWithText("기본 지도").assertExists();compose.onNodeWithText("사용자 앱").assertDoesNotExist();compose.onNodeWithText("보이지 않을 서비스").assertDoesNotExist();render("73-picker-compact",densePicker())}
    @Test fun scopeLevelControlsInvisibleSystemAppsAndSettingsShortcut(){
        val regular=DiscoveredApp("test.map","기본 지도",10001,true,true,hasLocationDeclaration=true)
        val core=DiscoveredApp("test.core","내부 서비스",1000,uidClass=UidClass.CORE_UID,block="정확한 위치 권한이 허용된 앱만 적용할 수 있습니다.")
        val system=DiscoveredApp("test.system","시스템 위치 도우미",10002,true,false,check=CandidateCheck.AVAILABLE)
        render("81-picker-normal",model(page=Page.PICK).copy(entries=emptyList(),applicationScope=ApplicationScope.NORMAL,discovered=listOf(regular,core,system)))
        compose.onNodeWithText("기본 지도").assertExists();compose.onNodeWithText("내부 서비스").assertDoesNotExist()
        render("81-picker-all",model(page=Page.PICK).copy(entries=emptyList(),applicationScope=ApplicationScope.ALL,discovered=listOf(regular,core,system)))
        // The whole scope lists apps without a launcher icon; apps that cannot be changed are no longer listed.
        compose.onNodeWithText("시스템 위치 도우미").assertExists();compose.onNodeWithText("내부 서비스").assertDoesNotExist()
        compose.onNodeWithText("신뢰하는 앱 선택").assertDoesNotExist()
        var called=false
        controller.get().setContent{BlueDotApp(model(page=Page.SETTINGS),{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},scopeChange={called=true})}
        compose.onNodeWithText("적용 범위 변경").performScrollTo().performClick();org.junit.Assert.assertTrue(called)
    }
    @Test fun settingsShowsScopeAndCurrentLevelOnTheSameLine(){
        render("82-scope-settings",model(page=Page.SETTINGS).copy(applicationScope=ApplicationScope.ALL))
        val heading=compose.onNodeWithText("현재 적용 범위").fetchSemanticsNode().boundsInRoot
        val level=compose.onNodeWithText("전체 적용").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(kotlin.math.abs(heading.center.y-level.center.y)<2f)
        org.junit.Assert.assertTrue(heading.right<=level.left)
    }
    @Test fun settingsExitShortcutInvokesCallbackWithoutLosingResult(){var opened=false;val m=model(applied=true,page=Page.SETTINGS);controller.get().setContent{BlueDotApp(m,{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},wirelessExit={opened=true})};compose.onNodeWithText("무선 디버깅 끄기").performScrollTo().performClick();org.junit.Assert.assertTrue(opened);org.junit.Assert.assertTrue(m.entries.single().settlingVerified)}
    @Test fun automaticManagementRequiresConfirmationAndCancelDoesNotGrant(){
        val requests=mutableListOf<Boolean>();val m=model(page=Page.SETTINGS)
        controller.get().setContent{BlueDotApp(m,{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},managementChanged={requests+=it})}
        compose.onNodeWithText("무선 디버깅 자동 관리").performClick();org.junit.Assert.assertTrue(requests.isEmpty())
        compose.onNodeWithText("취소").performClick();org.junit.Assert.assertTrue(requests.isEmpty())
        compose.onNodeWithText("무선 디버깅 자동 관리").performClick();compose.onNodeWithText("자동 관리 사용").performClick()
        org.junit.Assert.assertEquals(listOf(true),requests)
    }
    @Test @Config(qualifiers="ko-rKR-w960dp-h750dp-xhdpi") fun automaticSettingsFoldWide(){render("73-settings-wide",model(page=Page.SETTINGS).copy(autoManagement=true,autoPermission=true));render("73-picker-compact-wide",densePicker())}
    @Test @Config(qualifiers="ko-rKR-w412dp-h915dp-xxhdpi",fontScale=1.5f) fun automaticSettingsLargeFont(){render("73-settings-large",model(page=Page.SETTINGS));render("73-picker-compact-large",densePicker())}
    @Test @Config(qualifiers="ko-rKR-w412dp-h915dp-night-xxhdpi") fun historyDark(){render("27-history-dark",history(true))}
    @Test @Config(qualifiers="ko-rKR-w960dp-h750dp-xhdpi") fun historyWide(){render("28-history-wide",history())}
    private fun noisyHistory()=history().copy(entries=emptyList()).let{m->m.copy(discovered=m.discovered+listOf(
        DiscoveredApp("android","Android 시스템",1000,true,false,LocationAccess(fine=System.currentTimeMillis()+1000),block="핵심 시스템 UID · 적용 불가"),
        DiscoveredApp("gms.service","Google Play 서비스",10002,true,false,LocationAccess(fine=System.currentTimeMillis()+2000),indicatorHint=FineIndicatorHint.NON_SENSITIVE)))}
    @Test fun defaultHistoryHidesServiceNoise(){render("29-history-apps",noisyHistory());compose.onNodeWithText("날씨").assertExists();compose.onNodeWithText("Google Play 서비스").assertDoesNotExist();compose.onNodeWithText("Android 시스템").assertDoesNotExist();compose.onNodeWithText("전체 위치 사용").assertExists()}
    @Test fun allHistoryPreservesRecordsWithoutOfferingInapplicableHide(){render("30-history-all",noisyHistory().copy(historyView=HistoryView.ALL));compose.onNodeWithText("Google Play 서비스").assertExists();compose.onNodeWithText("Android 시스템").assertExists();compose.onAllNodesWithText("숨기기").assertCountEquals(1)}
    @Test fun historyViewSwitchChangesActualRows(){
        val state=androidx.compose.runtime.mutableStateOf(noisyHistory())
        controller.get().setContent{BlueDotApp(state.value,{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},historyViewChanged={state.value=state.value.copy(historyView=it)})}
        compose.onNodeWithText("Android 시스템").assertDoesNotExist()
        compose.onNodeWithText("전체 위치 사용").performClick()
        compose.onNodeWithText("Android 시스템").assertExists()
        compose.onNodeWithText("앱 기록").performClick()
        compose.onNodeWithText("Android 시스템").assertDoesNotExist()
    }
}
