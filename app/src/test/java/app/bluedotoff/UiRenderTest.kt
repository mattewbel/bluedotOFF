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
import org.junit.Assert.assertFalse
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
    @Test fun batterySettingIsAvailableForWirelessUsers(){batterySetting(false)}
    @Test fun batterySettingIsAvailableForUsbUsers(){batterySetting(true)}
    private fun batterySetting(watching:Boolean){
        var requested:Boolean?=null
        val current=androidx.compose.runtime.mutableStateOf(model(page=Page.SETTINGS).copy(backgroundWatch=watching,batteryUnrestricted=watching))
        controller.get().setContent{BlueDotApp(current.value,{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},openBattery={requested=it})}
        val row=compose.onNode(isToggleable() and hasText(BatteryOptimization.TITLE)).performScrollTo()
        if(watching)row.assertIsOn()else row.assertIsOff()
        row.performClick();org.junit.Assert.assertEquals(!watching,requested)
        // Opening a system screen is not consent; keep the previous real state until it is read back.
        if(watching)row.assertIsOn()else row.assertIsOff()
        compose.runOnIdle{current.value=current.value.copy(batteryUnrestricted=!watching)}
        if(watching)row.assertIsOff()else row.assertIsOn()
    }
    @Test fun unknownBatteryStateCannotPretendToToggleButCanOpenSettings(){
        var requested:Boolean?=null
        controller.get().setContent{BlueDotApp(model(page=Page.SETTINGS).copy(batteryUnrestricted=null),{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},openBattery={requested=it})}
        compose.onNode(isToggleable() and hasText(BatteryOptimization.TITLE)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("배터리 설정 상태를 확인할 수 없어요. 설정 열기").performScrollTo().performClick()
        org.junit.Assert.assertEquals(false,requested)
    }
    @Test fun rejectedGroupsAreListedWithReasonsUntilConfirmed(){
        controller.get().setContent { BlueDotApp(model(page=Page.PICK).copy(rejectedGroupList=listOf(RejectedGroup("삼성 계정",6,"공유 앱의 위치 표시 설정이 서로 달라 변경하지 않았습니다."))),{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
        compose.waitForIdle()
        compose.onNodeWithText("선택하지 않은 앱 묶음").assertExists();compose.onNodeWithText("삼성 계정 외 5개 앱").assertExists()
        compose.onNodeWithText("공유 앱의 위치 표시 설정이 서로 달라 변경하지 않았습니다.").assertExists();compose.onNodeWithText("확인").assertExists()
    }
    @Test fun largeGroupAsksBeforeSelecting(){
        controller.get().setContent { BlueDotApp(model(page=Page.PICK).copy(bigGroups=listOf(BigGroup("디바이스 찾기",(1..93).map{"demo.sys$it"},true))),{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
        compose.waitForIdle()
        compose.onNodeWithText("큰 앱 묶음을 함께 선택할까요?").assertExists();compose.onNodeWithText("디바이스 찾기 외 92개 앱").assertExists()
        compose.onNodeWithText("Android 시스템 앱 포함").assertExists();compose.onNodeWithText("함께 선택").assertExists();compose.onNodeWithText("선택 안 함").assertExists()
    }
    @Test fun updateQuestionIsAskedWithOffAsTheSafeAnswer(){
        var answer:Boolean?=null
        controller.get().setContent{BlueDotApp(model(applied=true).copy(updateOffer=true),{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},updateOfferAnswer={answer=it})}
        compose.onNodeWithText("새 버전이 나오면 알려드릴까요?").assertExists()
        compose.onNodeWithText("끄면 bluedotOFF는 인터넷을 쓰지 않아요",substring=true).assertExists()
        compose.onNodeWithText("끄기").performClick();org.junit.Assert.assertEquals(false,answer)
    }
    @Test fun updateQuestionWaitsForTheNotificationQuestion(){
        controller.get().setContent{BlueDotApp(model(applied=true).copy(updateOffer=true,notificationOffer=true),{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})}
        compose.onNodeWithText("새 버전이 나오면 알려드릴까요?").assertDoesNotExist()
    }
    @Test fun newVersionCardOpensThePageOrSkips(){
        var opened:String?=null;var skipped:String?=null
        controller.get().setContent{BlueDotApp(model(applied=true).copy(updateCard="9.9.9"),{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},openUpdate={opened=it},skipUpdate={skipped=it})}
        compose.onNodeWithText("새 버전이 있어요").assertExists()
        compose.onNodeWithText("bluedotOFF 9.9.9이 나왔어요. GitHub 출시 페이지에서 받을 수 있어요.").assertExists()
        compose.onNodeWithText("출시 페이지 열기").performClick();org.junit.Assert.assertEquals("9.9.9",opened)
        compose.onNodeWithText("이 버전 건너뛰기").performClick();org.junit.Assert.assertEquals("9.9.9",skipped)
    }
    @Test fun updateSwitchIsOffByDefaultAndShowsVersionsWhenOn(){
        var changed:Boolean?=null;var checked=false;var opened:String?=null
        val current=androidx.compose.runtime.mutableStateOf(model(page=Page.SETTINGS))
        controller.get().setContent{BlueDotApp(current.value,{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},updateCheckChanged={changed=it},checkUpdateNow={checked=true},openUpdate={opened=it})}
        val row=compose.onNode(isToggleable() and hasText("업데이트 확인")).performScrollTo()
        row.assertIsOff();compose.onNodeWithText("지금 확인").assertDoesNotExist()
        row.performClick();org.junit.Assert.assertEquals(true,changed)
        compose.runOnIdle{current.value=current.value.copy(updateCheck=true)}
        compose.onNodeWithText("현재 버전 ${BuildConfig.VERSION_NAME} · 최신 버전은 아직 확인 전").assertExists()
        compose.onNodeWithText("지금 확인").performScrollTo().performClick();assertTrue(checked)
        compose.onNodeWithText("출시 페이지 열기").assertDoesNotExist()
        compose.runOnIdle{current.value=current.value.copy(latestVersion="9.9.9",updateStatus="새 버전 9.9.9이 있어요.")}
        compose.onNodeWithText("현재 버전 ${BuildConfig.VERSION_NAME} · 최신 버전 9.9.9").assertExists()
        compose.onNodeWithText("새 버전 9.9.9이 있어요.").assertExists()
        compose.onNodeWithText("출시 페이지 열기").performScrollTo().performClick();org.junit.Assert.assertEquals("9.9.9",opened)
        compose.runOnIdle{current.value=current.value.copy(updateChecking=true)}
        compose.onNodeWithText("확인하는 중…").assertIsNotEnabled()
    }
    @Test fun homeShowsLastCheckAndRefreshWithoutTakingOverTheScreen(){
        val base=model(applied=true).copy(lastCheckedAt=System.currentTimeMillis()-3*60_000L)
        render("90-home-last-checked",base)
        compose.onNodeWithText("마지막 확인 3분 전").assertExists();compose.onNodeWithContentDescription("다시 확인").assertExists()
        // A recheck for a set-up user keeps Home and shows a small progress line instead of the setup-style screen.
        render("90-home-rechecking",base.copy(rechecking=true,scanProgress=ScanProgress(12,40)))
        compose.onNodeWithText("앱 상태 확인 중 · 12 / 40").assertExists();compose.onNodeWithText("앱 추가").assertExists()
    }
    @Test fun failedBatchShowsTheSameErrorOnCardAndSnackbar(){
        // Screenshot for the owner: a partly failed apply shows its error on the result card and as a snackbar.
        val base=model(applied=true);val error="화면을 떠나서 남은 앱은 바꾸지 않았어요. 이미 끝난 적용과 등록 기록은 그대로예요."
        val results=listOf(BatchResult("demo.weather",base.entries.single()),BatchResult("demo.other",error=error))
        render("91-duplicate-error",base.copy(completion=BatchOutcome(true,results,base.entries,emptyList(),error=error),notice=error))
    }
    @Test fun interruptedBatchKeepsTopSummaryWithoutRepeatedAppErrorLists(){
        val base=model(applied=true)
        val error="화면을 떠나서 남은 앱은 바꾸지 않았어요. 이미 끝난 적용과 등록 기록은 그대로예요."
        val results=listOf(BatchResult("demo.weather",base.entries.single()),BatchResult("demo.other",error="앞선 작업의 복원이나 연결을 먼저 확인해야 해서 바꾸지 않았어요."))
        val outcome=BatchOutcome(true,results,base.entries,emptyList(),error=error)
        for(page in listOf(Page.HOME,Page.PICK,Page.HISTORY,Page.REAPPLY)){
            render("26-interrupted-${page.name.lowercase()}",base.copy(page=page,batchResults=results,completion=outcome))
            compose.onNodeWithText(outcome.summary()).assertExists()
            compose.onNodeWithText(error).assertExists()
            compose.onNodeWithText("demo.other: 앞선 작업의 복원이나 연결을 먼저 확인해야 해서 바꾸지 않았어요.").assertDoesNotExist()
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
        compose.onNodeWithText("적용하고 안전하게 확인하고 있어요").assertDoesNotExist()
        compose.onNodeWithText("복원하고 안전하게 확인하고 있어요").assertDoesNotExist()
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
        compose.onNodeWithText("1개 앱 적용 중").assertExists()
        compose.onNodeWithText("1개 앱 · 재적용 필요").assertExists()
        compose.onNodeWithText("1개 앱 · 상태 확인 필요").assertExists()
    }
    /** Picker: an already applied app shows "적용 중" with a plain check mark, never a checked box next to "0개 선택됨". */
    @Test fun appliedAppInPickerIsNotShownAsASelection(){
        val applied=DiscoveredApp("demo.weather","날씨",10792,launcher=true,hasLocationDeclaration=true,indicator=IndicatorAssessment(IndicatorEligibility.STRONG_CANDIDATE))
        val other=DiscoveredApp("demo.map","지도",10793,launcher=true,hasLocationDeclaration=true,indicator=IndicatorAssessment(IndicatorEligibility.STRONG_CANDIDATE))
        val m=model(applied=true,page=Page.PICK).copy(discovered=listOf(applied,other),historyAvailable=true)
        render("92-picker-applied-check",m)
        compose.onNodeWithText("0개 선택됨").assertExists();compose.onNodeWithText("적용 중").assertExists()
        compose.onAllNodes(isToggleable() and isOn()).assertCountEquals(0)
    }
    /** Home: "상태 확인 필요" lists exactly those apps, and a row opens that app. */
    @Test fun checkNeededCountOpensTheAppsBehindIt(){
        var opened:String?=null
        val base=model(applied=true).entries.single()
        val failed=base.copy(identity=base.identity.copy(packageName="demo.map"),label="지도",state=TargetState.FAILED_SAFE,error="확인 실패")
        val reapply=base.copy(identity=base.identity.copy(packageName="demo.bus"),label="버스",state=TargetState.NEEDS_REAPPLY_POLICY)
        controller.get().setContent{BlueDotApp(model(applied=true).copy(entries=listOf(base,failed,reapply)),{}, {pkg->opened=pkg}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})}
        compose.onNodeWithText("1개 앱 · 상태 확인 필요").performClick()
        compose.onNodeWithText("상태 확인이 필요한 앱").assertExists()
        compose.onNode(hasText("버스") and hasAnyAncestor(isDialog())).assertDoesNotExist()
        compose.onNode(hasText("지도") and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle();org.junit.Assert.assertEquals("demo.map",opened)
    }
    /** Owner (1.1.2): no watch state line in Settings; Home's card and the notification report a stopped watch. */
    @Test fun watchStateIsOnHomeNotInSettings(){
        val current=androidx.compose.runtime.mutableStateOf(model(page=Page.SETTINGS).copy(backgroundWatch=true,usbDebugging=true,watchRunning=false))
        controller.get().setContent{BlueDotApp(current.value,{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})}
        compose.onNodeWithText("백그라운드 감시").performScrollTo().assertExists()
        compose.onNodeWithText("멈춤").assertDoesNotExist();compose.onNodeWithText("감시 중").assertDoesNotExist()
        compose.onNodeWithText("다시 켜려면 Wi-Fi에 연결해 무선 디버깅을 켠 뒤 bluedotOFF를 열어주세요.").assertDoesNotExist()
        compose.runOnIdle{current.value=current.value.copy(page=Page.HOME)}
        compose.onNodeWithText("백그라운드 감시가 멈춰 있어요").assertExists()
        compose.onNodeWithText("다시 켜려면 Wi-Fi에 연결해 무선 디버깅을 켠 뒤 bluedotOFF를 열어주세요.").assertExists()
        compose.runOnIdle{current.value=current.value.copy(watchRunning=true)}
        compose.onNodeWithText("백그라운드 감시가 멈춰 있어요").assertDoesNotExist()
    }
    /** No connection: restore buttons lead to setup instead of sitting grey. */
    @Test fun restoreWithoutConnectionLeadsToSetup(){
        var opened=false
        val m=model(applied=true,page=Page.RESTORE).copy(connected=false,automaticReady=false,homePicked=setOf("demo.weather"))
        controller.get().setContent{BlueDotApp(m,{}, {}, {}, {}, {}, {}, {opened=true}, {}, {}, {}, {}, {}, {}, {}, {})}
        compose.onNodeWithText("연결 후 1개 복원").assertIsEnabled().performClick()
        compose.waitForIdle();assertTrue(opened)
    }
    /** While searching, a matching app that cannot be hidden is listed with its reason; it cannot be selected. */
    @Test fun searchShowsAppsThatCannotBeHiddenWithReason(){
        val blocked=DiscoveredApp("demo.blocked","차단 지도",10795,launcher=true,hasLocationDeclaration=true,block="정확한 위치 권한이 허용된 앱만 적용할 수 있습니다.",indicator=IndicatorAssessment(IndicatorEligibility.STRONG_CANDIDATE))
        val m=model(page=Page.PICK).copy(entries=emptyList(),discovered=listOf(blocked),historyAvailable=true,query="차단",filter=AppFilter.ALL)
        render("93-picker-search-unavailable",m)
        compose.onNodeWithText("숨길 수 없는 앱").assertExists()
        compose.onNodeWithText("정확한 위치 권한이 허용된 앱만 적용할 수 있습니다.").assertExists()
        compose.onAllNodes(isToggleable() and isEnabled() and hasAnyAncestor(hasScrollAction())).assertCountEquals(0)
        render("93b-picker-no-search",m.copy(query=""))
        compose.onNodeWithText("숨길 수 없는 앱").assertDoesNotExist()
    }
    /** A shared group the user already picked is not listed under "숨길 수 없는 앱" while searching (found on the phone). */
    @Test fun pickedGroupIsNotShownAsUnavailableWhileSearching(){
        val names=listOf("demo.group.map","demo.group.helper")
        val apps=names.mapIndexed{i,p->DiscoveredApp(p,if(i==0)"묶음 지도" else "묶음 도우미",10900,launcher=i==0,hasLocationDeclaration=i==0,uidClass=UidClass.MULTI_PACKAGE_SHARED_UID,sharedUserId="demo.shared",uidPeers=names,indicator=IndicatorAssessment(IndicatorEligibility.STRONG_CANDIDATE))}
        // As on the phone: an old record of a previous install of this group is in a locked state.
        val stale=model().entries.single().let{e->names.map{p->e.copy(identity=e.identity.copy(packageName=p,uid=10900),enabled=false,state=TargetState.NEEDS_REVIEW,groupIdentities=names.map{n->e.identity.copy(packageName=n,uid=10900)})}}
        val m=model(page=Page.PICK).copy(entries=stale,discovered=apps,historyAvailable=true,query="묶음",filter=AppFilter.ALL,picked=names.toSet(),approvedGroups=listOf(names),applicationScope=ApplicationScope.ALL)
        render("93c-picker-search-picked-group",m)
        compose.onNodeWithText("숨길 수 없는 앱").assertDoesNotExist()
    }
    /** A restore that skipped apps keeps a visible line and a way to see them. */
    @Test fun skippedRestoreStaysOnTheResultCard(){
        val base=model(applied=true).entries.single()
        val locked=base.copy(identity=base.identity.copy(packageName="demo.map"),label="지도",state=TargetState.NEEDS_REVIEW,error="확인 필요")
        val done=BatchOutcome(false,listOf(BatchResult("demo.weather",base.copy(enabled=false,state=TargetState.NOT_APPLIED))),listOf(base,locked),emptyList(),completedAt=System.nanoTime()/1_000_000)
        render("95-restore-skipped",model(applied=true).copy(entries=listOf(base,locked),completion=done,restoreSkipped=1))
        compose.onNodeWithText("확인이 필요한 1개 앱은 건너뛰었어요.").assertExists()
        compose.onNodeWithText("남은 앱 보기").performClick()
        compose.onNode(hasText("지도") and hasAnyAncestor(isDialog())).assertExists()
    }
    /** Home does not say "멈춰 있어요" while a stopped watch is being started. */
    @Test fun watchStartingIsNotShownAsStopped(){
        controller.get().setContent{BlueDotApp(model(page=Page.HOME).copy(backgroundWatch=true,usbDebugging=true,watchRunning=false,watchStarting=true),{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})}
        compose.onNodeWithText("백그라운드 감시가 멈춰 있어요").assertDoesNotExist()
    }
    /** After a phone update the structure check fails: Home says so and offers no "연결 준비" (re-pairing will not help). */
    @Test fun osChangedIsNotShownAsAConnectionProblem(){
        render("96-home-os-changed",model(applied=true).copy(connected=false,automaticReady=true,compatibility=SetupFlow.OS_CHANGED))
        compose.onNodeWithText("휴대폰 업데이트로 내부 구조가 바뀌었어요").assertExists()
        compose.onNodeWithText("연결 준비").assertDoesNotExist()
    }
    /** A locked app tells the user not to clear data (its original value is only in the restore record). */
    @Test fun lockedAppWarnsNotToClearData(){
        val base=model(applied=true).entries.single().copy(state=TargetState.NEEDS_REVIEW,error="중단된 공유 앱 묶음은 자동으로 변경하지 않습니다.")
        render("97-detail-locked",model(applied=true,page=Page.DETAIL).copy(entries=listOf(base),selected="demo.weather"))
        compose.onNodeWithText("안전을 위해 이 앱은 더 바꾸지 않도록 잠가 두었어요. 원래 상태 기록은 그대로 있어요. 앱 데이터를 지우거나 bluedotOFF를 삭제하지 말고, 설정 → 진단 정보 복사 및 보내기로 알려주세요.").performScrollTo().assertExists()
    }
    /** Owner choice: when apps need attention, the recent-location card moves below the registered list. */
    @Test fun recentCardMovesBelowTheListWhenAttentionIsNeeded(){
        val recent=DiscoveredApp("demo.recent","최근 앱",10796,launcher=true,access=LocationAccess(fine=System.currentTimeMillis()-60_000),hasLocationDeclaration=true,indicator=IndicatorAssessment(IndicatorEligibility.STRONG_CANDIDATE))
        val base=model(applied=true).entries.single()
        fun top(text:String)=compose.onNodeWithText(text,useUnmergedTree=true).fetchSemanticsNode().positionInRoot.y
        render("98-home-normal-order",model(applied=true).copy(discovered=listOf(recent),historyAvailable=true))
        assertTrue(top("최근 위치 사용 앱")<top("등록한 앱 · 1개"))
        render("98b-home-attention-order",model(applied=true).copy(entries=listOf(base.copy(state=TargetState.NEEDS_REAPPLY_POLICY)),discovered=listOf(recent),historyAvailable=true))
        assertTrue(top("최근 위치 사용 앱")>top("등록한 앱 · 1개"))
    }
    /** U-14: English + large text + narrow screen (cover-screen width). Screenshots for review; key controls on screen. */
    @Test @Config(qualifiers="en-rUS-w360dp-h780dp-night-xxhdpi",fontScale=1.3f) fun englishLargeNarrowScreens(){
        val apps=listOf("Weather","Maps").mapIndexed{i,l->DiscoveredApp("demo.app$i",l,11000+i,launcher=true,hasLocationDeclaration=true,indicator=IndicatorAssessment(IndicatorEligibility.STRONG_CANDIDATE))}
        render("99a-en-large-narrow-picker",model(page=Page.PICK).copy(entries=emptyList(),discovered=apps,historyAvailable=true,filter=AppFilter.PREINSTALLED))
        compose.onNode(isSelected() and hasText(UiText.text(controller.get(),AppFilter.PREINSTALLED.label))).assertIsDisplayed()
        render("99b-en-large-narrow-history",model(applied=true,page=Page.HISTORY).copy(discovered=apps,historyAvailable=true,historyView=HistoryView.REAPPLY))
        compose.onNode(isSelected() and hasText(UiText.text(controller.get(),HistoryView.REAPPLY.label))).assertIsDisplayed()
        render("99c-en-large-narrow-settings",model(applied=true,page=Page.SETTINGS).copy(backgroundWatch=true,usbDebugging=true,watchRunning=true))
        val base=model(applied=true).entries.single()
        render("99d-en-large-narrow-home",model(applied=true).copy(entries=listOf(base.copy(state=TargetState.NEEDS_REAPPLY_POLICY))))
    }
    /** The US-model notice can be confirmed once; the callback stores that and the notice is gone. */
    @Test fun usNoticeCanBeConfirmed(){
        var dismissed=false
        val current=androidx.compose.runtime.mutableStateOf(model(applied=true).copy(usModel=true,connected=true))
        controller.get().setContent{BlueDotApp(current.value,{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},dismissUsNotice={dismissed=true;current.value=current.value.copy(usModel=false)})}
        compose.onNodeWithText("미국 판매 모델 안내").assertExists()
        compose.onNodeWithText("확인했어요").performClick()
        compose.waitForIdle()
        assertTrue(dismissed);compose.onNodeWithText("미국 판매 모델 안내").assertDoesNotExist()
    }
    /** A broken restore journal is not shown as a connection problem, and the user is told not to clear data. */
    @Test fun journalProblemIsShownSeparatelyFromConnection(){
        render("91-home-journal-problem",model(applied=true).copy(journalProblem=true,connected=true))
        compose.onNodeWithText("복원 기록을 확인하지 못했어요").assertExists()
        compose.onNodeWithText("앱 데이터를 지우거나 앱을 삭제하지 마세요. 기록이 사라지면 숨긴 앱을 원래대로 되돌릴 수 없어요. 설정 → 진단 정보 복사 및 보내기로 알려주세요.").assertExists()
        // Not connected at the same time: no "연결 준비" next to "다시 연결해도 해결되지 않아요".
        render("91b-home-journal-problem-offline",model(applied=true).copy(journalProblem=true,connected=false,automaticReady=false))
        compose.onNodeWithText("복원 기록을 확인하지 못했어요").assertExists();compose.onNodeWithText("연결 준비").assertDoesNotExist()
        render("91-home-journal-ok",model(applied=true).copy(connected=true))
        compose.onNodeWithText("복원 기록을 확인하지 못했어요").assertDoesNotExist()
    }
    @Test @Config(qualifiers="en-rUS-w412dp-h915dp-night-xxhdpi") fun homeAttentionUsesPlainEnglish(){
        val base=model(applied=true).entries.single()
        render("83-home-attention-en",model(applied=true).copy(entries=listOf(base.copy(state=TargetState.NEEDS_REAPPLY_POLICY))))
        compose.onNodeWithText("Some apps\nneed reapplying").assertExists()
        compose.onNodeWithText("Reapply needed: 1").assertExists()
        compose.onNodeWithText("Keep completed apps as they are. Check only the apps that need attention.").assertExists()
    }
    private val leftOffApp=DiscoveredApp("demo.old","기존 위치 앱",10001,launcher=true,indicator=IndicatorAssessment(IndicatorEligibility.EXEMPT_SYSTEM_USAGE,true,0x100),hasLocationDeclaration=true)
    /** After a journal reset (e.g. bluedotOFF reinstalled) an OFF app has no record: it is listed but not offered for
     *  hiding (that would record OFF as its original), and "표시 켜기" is pointed to instead. */
    @Test fun offTargetsAfterJournalResetAreListedButNotPickable(){
        val m=model(page=Page.PICK).copy(entries=emptyList(),discovered=listOf(leftOffApp),historyAvailable=true)
        render("76-picker-cleared-journal",m)
        compose.onNodeWithText("기존 위치 앱").assertExists()
        compose.onNodeWithText("숨김 남음 · 기록 없음").assertExists()
        compose.onNodeWithText("등록한 앱").assertDoesNotExist()
        compose.onNodeWithText("‘숨김 남음 · 기록 없음’은 bluedotOFF 기록 없이 이미 숨겨져 있는 앱이에요. 다시 숨길 필요가 없고, 설정 → ‘숨김이 남은 앱’에서 숨김을 풀 수 있어요.").assertExists()
        assertFalse(m.canSelect(leftOffApp))
    }
    /** Settings: "앱 삭제 전 복원" with its explanation, and "진단 정보 복사 및 보내기" under 정보 및 지원. */
    /** Automatic re-apply keeps Home visible with one progress line (no full-screen overlay). */
    @Test fun autoReapplyShowsALineAndKeepsHome(){
        render("94-auto-reapply",model(applied=true).copy(busy=true,autoReapplying=true,progress=BatchProgress(3,8,BatchPhase.CHECKING)))
        compose.onNodeWithText("자동 재적용 중 · 3 / 8").assertExists()
        compose.onNodeWithText("앱 추가").assertExists()
        compose.onNodeWithText("적용하고 안전하게 확인하고 있어요").assertDoesNotExist()
    }
    @Test fun settingsHasTheAutoReapplySwitchOnByDefault(){
        render("88-settings-auto-reapply",model(page=Page.SETTINGS))
        compose.onNode(androidx.compose.ui.test.isToggleable()and androidx.compose.ui.test.hasText("앱을 열 때 자동 재적용")).performScrollTo().assertIsOn()
    }
    /** Background watch (1.1.0): off by default; the first switch-on asks, and its confirm unlocks after 5 seconds. */
    @Test fun backgroundWatchAsksFirstWithAFiveSecondWait(){
        var turnedOn:Boolean?=null
        controller.get().setContent { BlueDotApp(model(page=Page.SETTINGS),{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},backgroundWatchChanged={turnedOn=it}) }
        val row=compose.onNode(androidx.compose.ui.test.isToggleable()and androidx.compose.ui.test.hasText("백그라운드 감시")).performScrollTo().assertIsOff()
        // Freeze the clock only after scrolling, so the countdown can be checked step by step.
        compose.mainClock.autoAdvance=false
        row.performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("USB 디버깅을 항상 켜 둬야 해요.").assertExists()
        compose.onNodeWithText("켜기 (5초)").assertIsNotEnabled().performClick()
        org.junit.Assert.assertNull(turnedOn)
        compose.mainClock.advanceTimeBy(5100)
        compose.onNodeWithText("켜기").assertIsEnabled().performClick()
        org.junit.Assert.assertEquals(true,turnedOn)
    }
    /** Background watch is the third Settings section: after the scope and wireless debugging, before the reapply notice. */
    @Test fun backgroundWatchIsTheThirdSettingsSection(){
        render("89-settings-background-watch",model(page=Page.SETTINGS))
        // Traversal follows the vertical Column even when later rows are below the viewport.
        // Clipped bounds collapse off-screen rows to zero and cannot establish their order.
        val labels=compose.onAllNodes(isToggleable()).fetchSemanticsNodes().map{it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString{t->t.text}}
        val expected=listOf("무선 디버깅 자동 관리","백그라운드 감시",BatteryOptimization.TITLE,"재적용 알림")
        assertTrue(labels.containsAll(expected));assertTrue(expected.zipWithNext().all{(a,b)->labels.indexOf(a)<labels.indexOf(b)})
    }
    /** While watching, automatic management is only the restart path: its switch is locked and explains why. */
    @Test fun automaticManagementIsLockedWhileWatching(){
        var changed:Boolean?=null
        controller.get().setContent { BlueDotApp(model(page=Page.SETTINGS).copy(backgroundWatch=true,autoManagement=true,usbDebugging=false),{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},managementChanged={changed=it}) }
        compose.onNode(androidx.compose.ui.test.isToggleable()and androidx.compose.ui.test.hasText("무선 디버깅 자동 관리")).performScrollTo().performClick()
        compose.onNodeWithText("백그라운드 감시를 쓰는 중이에요").assertExists()
        org.junit.Assert.assertNull(changed)
        compose.onNodeWithText("확인").performClick()
        compose.onNodeWithText("백그라운드 감시를 쓰는 동안에는 평소에 무선 디버깅을 켜지 않아요. 재부팅이나 업데이트 뒤 감시를 다시 켤 때만 잠깐 켜요.").assertExists()
        compose.onNodeWithText("USB 디버깅 켜러 가기").performScrollTo().assertExists()
    }
    /** Watch chosen but stopped because USB debugging is off: Home says so and offers Developer options. */
    @Test fun homeSaysWhenBackgroundWatchIsStopped(){
        var opened=false
        controller.get().setContent { BlueDotApp(model(applied=true).copy(backgroundWatch=true,watchRunning=false,usbDebugging=false,autoManagement=true),{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},openDeveloperOptions={opened=true}) }
        compose.onNodeWithText("백그라운드 감시가 멈춰 있어요").assertExists()
        compose.onNodeWithText("USB 디버깅을 다시 켜고, Wi-Fi에 연결된 상태에서 bluedotOFF를 열어주세요.").assertExists()
        compose.onNodeWithText("USB 디버깅 켜기").performClick();assertTrue(opened)
    }
    @Test fun homeHasNoWatchCardWhileWatching(){
        controller.get().setContent { BlueDotApp(model(applied=true).copy(backgroundWatch=true,watchRunning=true,usbDebugging=true),{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithText("백그라운드 감시가 멈춰 있어요").assertDoesNotExist()
    }
    /** Reapply history rows of registered apps open the detail page, like the location tab. */
    @Test fun reapplyHistoryRowOpensTheApp(){
        var opened:String?=null
        val row=ReapplyHistoryItem("1","demo.weather","demo.weather",System.currentTimeMillis()-60_000,true,ReapplySource.BACKGROUND)
        controller.get().setContent { BlueDotApp(model(applied=true,page=Page.HISTORY).copy(historyView=HistoryView.REAPPLY,reapplyHistory=listOf(row)),{}, {opened=it}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithText("날씨").performClick()
        org.junit.Assert.assertEquals("demo.weather",opened)
    }
    /** Once acknowledged, switching on again does not ask. */
    @Test fun backgroundWatchDoesNotAskAgainOnceAcknowledged(){
        var turnedOn:Boolean?=null
        controller.get().setContent { BlueDotApp(model(page=Page.SETTINGS).copy(watchAcknowledged=true),{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},backgroundWatchChanged={turnedOn=it}) }
        compose.onNode(androidx.compose.ui.test.isToggleable()and androidx.compose.ui.test.hasText("백그라운드 감시")).performScrollTo().performClick()
        org.junit.Assert.assertEquals(true,turnedOn)
        compose.onNodeWithText("켜기 전에 꼭 읽어주세요.").assertDoesNotExist()
    }
    @Test fun autoReapplySummarySaysHowManyWereReapplied(){
        val e=model(applied=true).entries.single();val ok=listOf(BatchResult("a",e),BatchResult("b",e))
        org.junit.Assert.assertEquals("2개 앱 자동 재적용 완료",BatchOutcome(true,ok,emptyList(),emptyList(),auto=true).summary())
        org.junit.Assert.assertEquals("자동 재적용 1개 · 확인 필요 1개",BatchOutcome(true,listOf(BatchResult("a",e),BatchResult("b",error="x")),emptyList(),emptyList(),auto=true).summary())
    }
    @Test fun settingsOffersRestoreBeforeUninstallAndDiagnostics(){
        render("88-settings-uninstall",model(page=Page.SETTINGS))
        compose.onNodeWithText("앱 삭제 전 복원").performScrollTo().assertExists()
        compose.onNodeWithText("bluedotOFF를 지우기 전에 눌러주세요. 숨긴 앱을 모두 원래 상태로 되돌려요. 앱 삭제나 데이터 초기화만으로는 숨김이 풀리지 않아요.").performScrollTo().assertExists()
        compose.onNodeWithText("진단 정보 복사 및 보내기").performScrollTo().assertExists()
    }
    @Test fun restoreBeforeUninstallSaysWhenUninstallingIsSafe(){
        render("93-uninstall-ready",model(applied=true).copy(entries=emptyList(),uninstallCheck=0))
        compose.onNodeWithText("숨긴 앱을 모두 원래 상태로 되돌렸어요. 이제 bluedotOFF를 삭제해도 돼요.",substring=true).assertExists()
        compose.onNodeWithText("앱 정보 열기").assertExists()
    }
    @Test fun restoreBeforeUninstallWarnsWhileAppsRemain(){
        render("93-uninstall-left",model(applied=true).copy(uninstallCheck=3))
        compose.onNodeWithText("3개 앱을 지금 복원하지 못했어요.",substring=true).assertExists()
        compose.onNodeWithText("앱 정보 열기").assertDoesNotExist()
    }
    @Test fun settingsOffersAppsLeftHidden(){
        render("88-settings-turn-back-on",model(page=Page.SETTINGS))
        compose.onNodeWithText("숨김이 남은 앱 (재설치 시에만)").performScrollTo().assertExists()
    }
    @Test fun showPageSeparatesPreinstalledAppsAndConfirmsWithoutPromisingTheDot(){
        val system=leftOffApp.copy(packageName="demo.camera",label="카메라",uid=10002,preinstalled=true)
        val m=model(page=Page.SHOW).copy(entries=emptyList(),discovered=listOf(leftOffApp,system),showPicked=setOf("demo.old"))
        render("90-show-page",m)
        compose.onNodeWithText("숨김 남음 · 기록 없음").assertExists()
        compose.onNodeWithText("기본 앱 · 원래 꺼져 있었을 수 있어요").assertExists()
        compose.onNodeWithText("1개 숨김 풀기").assertIsEnabled().performClick()
        compose.onNodeWithText("선택한 1개 앱의 숨김을 풀어요(파란 위치 표시 설정을 켜요). 시스템에서 표시를 제외하는 앱은 숨김을 풀어도 파란 위치 표시가 나타나지 않을 수 있어요. 위치 권한과 사용 기록은 그대로예요.").assertExists()
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
        compose.onNodeWithText("Applied · Last checked").assertExists()
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
        compose.onNodeWithText("bluedotOFF 적용 중 ✓").assertExists()
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
    @Test @Config(qualifiers="en-rUS-w412dp-h915dp-xxhdpi") fun englishLastCheckedReadsAsOneSentence(){
        render("79-home-en-last-checked",model(applied=true).copy(lastCheckedAt=System.currentTimeMillis()))
        compose.onNodeWithText("Last checked just now").assertExists()
    }
    @Test @Config(qualifiers="en-rUS-w412dp-h915dp-xxhdpi") fun simpleEnglishHomeAndHiddenPicker(){
        render("79-home-en",model(applied=true))
        val off=DiscoveredApp("demo.off","Example",10002,launcher=true,hasLocationDeclaration=true,indicator=IndicatorAssessment(IndicatorEligibility.EXEMPT_SYSTEM_USAGE,true,0x100))
        render("79-picker-en",model(page=Page.PICK).copy(entries=emptyList(),discovered=listOf(off)))
        compose.onNodeWithText("Hidden · no record").assertExists()
    }
    @Test fun waterProgressKeepsRealCountsAndHidesThePageBehindIt(){
        render("79-water-half",model().copy(busy=true,progress=BatchProgress(35,70,BatchPhase.CHECKING)))
        compose.onNodeWithText("35 / 70개 확인 · 50%").assertExists()
        compose.onNodeWithText("앱 추가").assertDoesNotExist()
    }
    @Test fun permissionRemovalIsAvailableOnlyAfterOpeningAdvancedSettings(){
        render("79-settings",model(page=Page.SETTINGS).copy(autoPermission=true))
        compose.onNodeWithText("자동 관리 권한 제거").assertDoesNotExist()
        compose.onNodeWithText("고급 설정").performScrollTo().performClick()
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
    @Test fun restoreProgressHasItsOwnLabelAndBlueBackLayer(){render("80-restore-half",model().copy(busy=true,operationHide=false,progress=BatchProgress(35,70,BatchPhase.CHECKING)));compose.onNodeWithText("복원하고 안전하게 확인하고 있어요").assertExists()}
    @Test @Config(qualifiers="ko-rKR-w412dp-h915dp-night-xxhdpi") fun vividBlueHideWaterInDarkTheme(){render("80-hide-half-dark",model().copy(busy=true,progress=BatchProgress(35,70,BatchPhase.CHECKING)))}
    @Test fun successfulRestoreBannerExpiresButErrorsRemain(){
        compose.mainClock.autoAdvance=false
        val base=model();val outcome=BatchOutcome(false,listOf(BatchResult("demo.weather",base.entries.single())),base.entries,emptyList())
        val current=androidx.compose.runtime.mutableStateOf(base.copy(completion=outcome))
        controller.get().setContent{BlueDotApp(current.value,{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})}
        compose.mainClock.advanceTimeBy(1000);compose.onNodeWithText("1개 앱을 원래 표시 상태로 복원했어요.").assertExists()
        compose.mainClock.advanceTimeBy(4100);compose.onNodeWithText("1개 앱을 원래 표시 상태로 복원했어요.").assertDoesNotExist()
        compose.mainClock.autoAdvance=true
        compose.runOnIdle{current.value=base.copy(completion=outcome.copy(cleanupWarning="종료 확인 필요"),wirelessWarning="종료 확인 필요")}
        compose.waitForIdle();compose.mainClock.advanceTimeBy(6000);compose.onNodeWithText("종료 확인 필요").assertExists()
        compose.mainClock.autoAdvance=true
    }
    private fun sharedMembers():List<Snapshot>{val names=listOf("demo.phone","demo.provider");return names.mapIndexed{i,p->Snapshot(Identity(p,0,10129,listOf("signer"),1,1,1),if(i==0)"전화" else "114 서비스",names,i==0,i==0,i==0,false,0x300,0x300,0,4,4,false,sharedUserId="shared",coarseDeclared=i==0)}}
    @Test fun groupConsentListsEveryMemberBeforeSelection(){var accepted=false;val m=model(page=Page.PICK).copy(sharedOffer=SharedOffer(sharedMembers()));controller.get().setContent{BlueDotApp(m,{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},sharedAccept={accepted=true})};compose.onNodeWithText("demo.phone").assertExists();compose.onNodeWithText("demo.provider").assertExists();org.junit.Assert.assertFalse(accepted);compose.onNodeWithText("묶음 전체 선택").performClick();org.junit.Assert.assertTrue(accepted)}
    @Test fun failedGroupValidationHasNoSelectionAction(){render("80-shared-blocked",model().copy(sharedOffer=SharedOffer(sharedMembers(),error="공유 앱 묶음을 확인해야 해요.")));compose.onNodeWithText("묶음 전체 선택").assertDoesNotExist();compose.onNodeWithText("demo.provider").assertExists()}
    @Test @Config(qualifiers="en-rUS-w412dp-h915dp-xxhdpi") fun sharedEnglishExplainsWholeGroup(){render("80-shared-en",model().copy(sharedOffer=SharedOffer(sharedMembers())));compose.onNodeWithText("Select the whole group").assertExists();compose.onNodeWithText("Apps sharing a location setting").assertExists()}
    @Test fun usModelShowsWarningOnlyWhenFlagged(){render("84-home-us-model",model(applied=true).copy(usModel=true));compose.onNodeWithText("미국 판매 모델 안내").assertExists()}
    @Test fun nonUsModelHasNoRegionWarning(){render("84-home-default",model(applied=true));compose.onNodeWithText("미국 판매 모델 안내").assertDoesNotExist()}
    @Test @Config(qualifiers="en-rUS-w412dp-h915dp-xxhdpi") fun usModelWarningIsEnglishOutsideKorean(){render("84-home-us-model-en",model(applied=true).copy(usModel=true));compose.onNodeWithText("US model notice").assertExists()}
    @Test fun welcomeWarnsSomeAppsMayLoseHiding(){render("01-welcome-notice",model(gate=Gate.WELCOME));compose.onNodeWithText("미리 알아두세요").assertExists();compose.onNodeWithText("위치 권한을 ‘정확한 위치’로 허용한 앱만 숨길 수 있어요.").assertExists();compose.onNodeWithText("풀린 앱은 bluedotOFF를 열 때 다시 숨겨요. USB 디버깅을 켜 두면 앱을 열지 않아도 감지하는 대로 다시 숨겨요.").assertExists()}
    @Test @Config(qualifiers="en-rUS-w412dp-h915dp-xxhdpi") fun welcomeNoticeIsEnglishOutsideKorean(){render("01-welcome-notice-en",model(gate=Gate.WELCOME));compose.onNodeWithText("Good to know").assertExists()}
    @Test fun homeHasNoWelcomeNotice(){render("01-home-no-notice",model(applied=true));compose.onNodeWithText("미리 알아두세요").assertDoesNotExist()}
    @Test @Config(qualifiers="ko-rKR-w412dp-h915dp-night-xxhdpi") fun emptyBlueHomeKeepsNavigationOnPageBackground(){render("86-home-empty-blue-dark",model().copy(entries=emptyList()));compose.onNodeWithText("설정").assertExists()}
    @Test fun uninstalledAppShowsAsNoActionNeeded(){
        val base=model(applied=true);val gone=base.entries.single().let{it.copy(identity=it.identity.copy(packageName="demo.gone"),label="삭제앱",state=TargetState.MISSING_TARGET,settlingVerified=false)}
        render("87-home-uninstalled",base.copy(entries=base.entries+gone))
        compose.onNodeWithText("삭제앱").assertDoesNotExist();compose.onNodeWithText("등록한 앱 · 1개").assertExists();compose.onNodeWithText("1개 앱 적용 중").assertExists();compose.onNodeWithText("상태 확인 필요",substring=true).assertDoesNotExist()
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
    @Test fun stoppedBackend(){render("03-backend-not-running",model(gate=Gate.NOT_RUNNING));compose.onNodeWithText("연결 설정 열기").assertExists()}
    @Test fun registeredOnlyReadDoesNotClaimToScanAllInstalledApps(){
        render("85-registered-only",model(gate=Gate.CHECKING).copy(checkingRegistered=true,scanProgress=ScanProgress(10,52)))
        compose.onNodeWithText("등록한 앱의 상태를 확인하고 있어요").assertExists()
        compose.onNodeWithText("10 / 52개 앱 확인 · 19%").assertExists()
    }
    @Test fun compatibilityChecking(){render("04-compatibility",model(gate=Gate.CHECKING));compose.onNodeWithText("기기를 확인하고 있어요").assertExists()}
    @Test fun blueHome(){render("05-blue-home",model());compose.onNodeWithText("앱 추가").assertExists()}
    @Test fun scopeChoiceDoesNotRewindProgressDuringFinalRead(){render("81-scope-final-read",model(gate=Gate.CHECKING).copy(scopeResolved=true));compose.onNodeWithText("연결 준비 · 11 / 11").assertExists();compose.onNodeWithText("연결 준비 · 10 / 11").assertDoesNotExist()}
    @Test fun applying(){render("06-applying",model().copy(busy=true));compose.onNodeWithText("적용하고 안전하게 확인하고 있어요").assertExists();compose.onNodeWithText("끝날 때까지 이 화면을 열어 두세요. 앱을 나가면 남은 앱은 바꾸지 않아요.").assertExists()}
    @Test fun appliedHome(){render("07-applied-system-theme",model(applied=true));compose.onNodeWithText("적용 완료").assertExists()}
    @Test fun successfulHideUsesHomeSummaryAndRetainsClosureWarnings(){
        val base=model(applied=true)
        val done=BatchOutcome(true,listOf(BatchResult("demo.weather",base.entries.single())),base.entries,emptyList())
        render("24-home-applied",base.copy(completion=done))
        compose.onNodeWithText(done.summary()).assertDoesNotExist()
        compose.onNodeWithText("1개 앱 적용 중").assertExists()
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
        compose.onNodeWithText("고급 설정").performScrollTo().performClick()
        compose.onNodeWithText("읽기 전용으로 다시 확인").performScrollTo().assertExists()
    }
    @Test fun reapply(){render("08-reapply",model(page=Page.REAPPLY));compose.onNodeWithText("모두 재적용").assertExists()}
    @Test @Config(qualifiers="ko-rKR-w412dp-h915dp-night-xxhdpi") fun dark(){render("09-dark",model(applied=true))}
    @Test @Config(qualifiers="ko-rKR-w960dp-h750dp-xhdpi") fun fold(){render("10-fold-wide",model(applied=true))}
    private val notice="선택한 앱은 지금 복원할 수 없어요. 홈의 ‘등록한 앱’에서 앱을 하나씩 눌러 상태를 확인해주세요."
    /** The notice is drawn in the system theme's colours (not inverted) and closes as soon as it is tapped. */
    private fun noticeShot(name:String){
        compose.mainClock.autoAdvance=false
        controller.get().setContent { BlueDotApp(model(applied=true).copy(notice=notice),{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
        // Layout-driven changes (the measured navigation height) need idle passes between frames.
        repeat(4){compose.mainClock.advanceTimeBy(250);compose.waitForIdle()}
        compose.onNodeWithText(notice).assertExists()
        // The notice sits above the bottom navigation instead of covering it.
        val noticeBottom=compose.onNodeWithText(notice).getBoundsInRoot().bottom;val navTop=compose.onNodeWithText("기록").getBoundsInRoot().top
        assertTrue("notice $noticeBottom nav $navTop",noticeBottom<=navTop)
        val view=controller.get().window.decorView
        val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);compose.runOnIdle{view.draw(Canvas(image))}
        val dir=File(checkNotNull(System.getProperty("screenshotDir")));dir.mkdirs()
        File(dir,"$name.png").outputStream().use{check(image.compress(Bitmap.CompressFormat.PNG,100,it))}
    }
    @Test fun noticeUsesTheLightThemeAndClosesOnTap(){
        noticeShot("92-notice-light")
        compose.onNodeWithText(notice).performClick();compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText(notice).assertDoesNotExist()
    }
    @Test @Config(qualifiers="ko-rKR-w412dp-h915dp-night-xxhdpi") fun noticeUsesTheDarkTheme(){noticeShot("92-notice-dark")}
    /** On pages with pinned bottom buttons the notice sits above those buttons too (large font, small screen). */
    @Test @Config(qualifiers="ko-rKR-w360dp-h640dp-xhdpi") fun noticeSitsAboveThePageButtons(){
        org.robolectric.RuntimeEnvironment.setFontScale(1.5f)
        compose.mainClock.autoAdvance=false
        controller.get().setContent { BlueDotApp(model(page=Page.RESTORE,applied=true).copy(homePicked=setOf("demo.weather"),notice=notice),{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
        repeat(4){compose.mainClock.advanceTimeBy(250);compose.waitForIdle()}
        val noticeBottom=compose.onNodeWithText(notice).getBoundsInRoot().bottom;val buttonTop=compose.onNodeWithText("1개 복원").getBoundsInRoot().top
        assertTrue("notice $noticeBottom button $buttonTop",noticeBottom<=buttonTop)
    }
    @Test fun autoBlockerOpensACenteredGuideInsteadOfASnackbar(){
        controller.get().setContent { BlueDotApp(model(applied=true).copy(notice=app.bluedotoff.adb.AutoBlocker.MESSAGE),{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
        compose.waitForIdle()
        compose.onNodeWithText("보안 위험 자동 차단을 꺼주세요").assertExists();compose.onNodeWithText("보안 설정 열기").assertExists();compose.onNodeWithText("닫기").assertExists()
        compose.onNodeWithText("자동 차단 끄기",substring=true).assertExists()
        val view=checkNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog().window).decorView
        val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);compose.runOnIdle{view.draw(Canvas(image))}
        File(checkNotNull(System.getProperty("screenshotDir")).also{File(it).mkdirs()},"87-auto-blocker-dialog.png").outputStream().use{check(image.compress(Bitmap.CompressFormat.PNG,100,it))}
    }
    @Test fun notificationDenied(){render("11-notifications-denied",model(page=Page.SETTINGS).copy(notifications=false));compose.onNode(androidx.compose.ui.test.isToggleable()and androidx.compose.ui.test.hasText("재적용 알림")).assertIsOff()}
    @Test fun unsupported(){render("12-unsupported",model(gate=Gate.UNSUPPORTED));compose.onNodeWithText("이 기기는 지원하지 않아요").assertExists();compose.onNodeWithText("bluedotOFF 적용").assertDoesNotExist()}
    @Test fun compatibilityPass(){render("13-ready",model().copy(entries=emptyList()));compose.onNodeWithText("준비 완료").assertExists()}
    @Test fun pickerAndSharedUid(){val e=model().entries.single();val s=Snapshot(e.identity,"날씨",listOf(e.identity.packageName,"shared.peer"),true,true,true,false,0x80301,0x301,0,4,4,false);render("14-picker-shared",model(page=Page.PICK).copy(candidates=listOf(s)));compose.onNodeWithText("다른 앱과 묶여 있어요 · 함께 선택돼요").assertExists();compose.onNodeWithText("현재 적용할 수 없는 앱",substring=true).assertDoesNotExist();compose.onNodeWithText("0개 추가").assertIsNotEnabled()}
    @Test fun cachedAppliedIsNotDisplayedAsVerified(){render("17-cached-stale",model(applied=true).copy(connected=false,freshPackages=emptySet()));compose.onNodeWithText("Blue Dot\nbluedotOFF 적용 중").assertDoesNotExist();compose.onNodeWithText("적용 중 · 마지막 확인 기준").assertExists()}
    @Test fun multiSelectionSurvivesQueryAndReturn(){val m=model(page=Page.PICK);val id=m.entries.single().identity;val a=Snapshot(id,"날씨",listOf(id.packageName),true,true,true,false,0x80301,0x301,0,4,4,false);render("18-multiselect",m.copy(candidates=listOf(a),picked=setOf(id.packageName),query="no match",historyAvailable=true));compose.onNodeWithText("1개 추가").assertIsEnabled();compose.onNodeWithText("1개 선택됨").assertExists();compose.onNodeWithText("선택한 앱을 등록하고 파란 위치 표시를 숨겨요.").assertExists()}
    @Test fun safeDetail(){val e=model().entries.single();val s=Snapshot(e.identity,"날씨",listOf(e.identity.packageName),true,true,true,false,0x80301,0x301,0,4,4,false);render("15-safe-detail",model(page=Page.DETAIL).copy(selected=e.identity.packageName,candidates=listOf(s)));compose.onNodeWithText("bluedotOFF 재적용").assertIsEnabled()}
    @Test fun sharedUidDetailBlocksHide(){val e=model().entries.single();val s=Snapshot(e.identity,"날씨",listOf(e.identity.packageName,"shared.peer"),true,true,true,false,0x80301,0x301,0,4,4,false);render("16-blocked-detail",model(page=Page.DETAIL).copy(selected=e.identity.packageName,candidates=listOf(s)));compose.onNodeWithText("bluedotOFF 재적용").assertIsNotEnabled()}
    @Test fun singletonUnverifiedShowsExactReason(){val e=model().entries.single();val s=Snapshot(e.identity,"카카오맵",listOf(e.identity.packageName),true,true,true,false,0x300,0x300,0,4,4,false,sharedUserId="daum.uid.shared");render("31-singleton-unverified",model(page=Page.DETAIL).copy(selected=e.identity.packageName,candidates=listOf(s)));compose.onNodeWithText(BlockReason.SINGLETON_UNVERIFIED.message).assertExists();compose.onNodeWithText("bluedotOFF 재적용").assertIsNotEnabled()}
    @Test fun coreUidShowsSpecificReason(){val m=history().copy(entries=emptyList(),historyView=HistoryView.ALL);render("32-core-reason",m.copy(discovered=m.discovered.map{it.copy(uid=1000,block=BlockReason.CORE_UID.message)}));compose.onNodeWithText(BlockReason.CORE_UID.message).assertExists();compose.onNodeWithText("숨기기").assertDoesNotExist()}
    @Test fun persistentShowsSpecificReason(){val m=history().copy(entries=emptyList(),historyView=HistoryView.ALL);render("33-persistent-reason",m.copy(discovered=m.discovered.map{it.copy(block=BlockReason.PERSISTENT_CORE.message)}));compose.onNodeWithText(BlockReason.PERSISTENT_CORE.message).assertExists();compose.onNodeWithText("숨기기").assertDoesNotExist()}
    @Test fun verifiedSingletonHistoryInlineAction(){var called="";val m=history().copy(entries=emptyList());render("34-singleton-inline",m.copy(discovered=m.discovered.map{it.copy(label="카카오맵",sharedUserId="daum.uid.shared",uidClass=UidClass.SINGLETON_SHARED_USER,indicatorHint=FineIndicatorHint.SENSITIVE)})){called=it};compose.onNodeWithText("숨기기").performClick();assert(called=="demo.weather");compose.onNodeWithText("최근 위치 사용").assertExists()}
    @Test fun reviewStateNeverOffersHideOrRestore(){val m=model(page=Page.DETAIL);val e=m.entries.single().copy(state=TargetState.NEEDS_REVIEW,sharedUserId="test.shared",error=OwnershipChangedException().message!!);val s=Snapshot(e.identity,"날씨",listOf(e.identity.packageName),true,true,true,false,0x200,0x300,0,4,4,false,sharedUserId="test.shared",singletonVerified=true);render("35-peer-change-review",m.copy(selected=e.identity.packageName,entries=listOf(e),candidates=listOf(s)));compose.onNodeWithText(e.error).assertExists();compose.onNodeWithText("bluedotOFF 재적용").assertDoesNotExist();compose.onNodeWithText("숨김 해제 · 원래 상태로 복원").assertDoesNotExist()}
    @Test fun peerReviewHistoryHasNoInlineWriteAction(){val m=history().let{it.copy(entries=it.entries.map{e->e.copy(state=TargetState.NEEDS_REVIEW)})};render("36-history-peer-review",m);compose.onNodeWithText("앱 묶음 변경 · 확인 필요").assertExists();compose.onNodeWithText("숨기기").assertDoesNotExist()}
    @Test fun osSensitivityResetHasAccurateReapplyAction(){val m=history().copy(entries=history().entries.map{it.copy(state=TargetState.NEEDS_REAPPLY_POLICY)});render("37-os-policy-reapply",m);compose.onNodeWithText("표시 설정 변경 · 재적용 필요").assertExists();compose.onNodeWithText("재적용").assertIsEnabled();compose.onNodeWithText("bluedotOFF 적용 중 ✓").assertDoesNotExist()}
    private fun history(applied:Boolean=false)=model(applied=applied,page=Page.HISTORY).let{m->m.copy(discovered=listOf(DiscoveredApp(m.entries.single().identity.packageName,"날씨",10792,launcher=true,access=LocationAccess(fine=System.currentTimeMillis()))),historyAvailable=true)}
    /** Tapping an app in History opens its detail page; the header has a refresh button. */
    @Test fun historyRowOpensTheAppAndCanBeRefreshed(){
        var opened="";var refreshed=false
        controller.get().setContent{BlueDotApp(history(true),{}, {opened=it}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},refreshHistory={refreshed=true})}
        compose.waitForIdle()
        compose.onNodeWithText("날씨").performClick();org.junit.Assert.assertEquals("demo.weather",opened)
        compose.onNodeWithContentDescription("다시 확인").performClick();org.junit.Assert.assertTrue(refreshed)
    }
    @Test fun hiddenHistoryShowsVerifiedCheck(){render("19-history-hidden",history(true));compose.onNodeWithText("bluedotOFF 적용 중 ✓").assertExists();compose.onNodeWithText("숨기기").assertDoesNotExist()}
    @Test fun unhiddenHistoryDirectActionStaysInline(){var called="";val m=history().copy(entries=emptyList());render("20-history-action",m){called=it};compose.onNodeWithText("숨기기").performClick();assert(called=="demo.weather");compose.onNodeWithText("최근 위치 사용").assertExists()}
    @Test fun allHistoryUnsupportedStillVisibleWithoutAction(){val m=history().copy(entries=emptyList());render("21-history-core",m.copy(historyView=HistoryView.ALL,discovered=m.discovered.map{it.copy(uid=1000,block="지원되지 않는 시스템 구성요소")}));compose.onNodeWithText("지원되지 않는 시스템 구성요소").assertExists();compose.onNodeWithText("숨기기").assertDoesNotExist()}
    @Test fun historyUpdateOffersReapply(){val m=history();render("22-history-update",m.copy(entries=m.entries.map{it.copy(state=TargetState.NEEDS_REAPPLY_UPDATE)}));compose.onNodeWithText("업데이트 · 재적용 필요").assertExists();compose.onNodeWithText("재적용").assertIsEnabled()}
    @Test fun historyFailureUsesTopSummaryAndNotSuccess(){val results=listOf(BatchResult("demo.weather",error="권한 상태 확인 필요"));val m=history().copy(entries=emptyList(),batchResults=results,completion=BatchOutcome(true,results,emptyList(),emptyList(),error="권한 상태 확인 필요"));render("26-history-failure",m);compose.onNodeWithText("권한 상태 확인 필요").assertExists();compose.onNodeWithText("날씨: 권한 상태 확인 필요").assertDoesNotExist();compose.onNodeWithText("bluedotOFF 적용 중 ✓").assertDoesNotExist()}
    @Test fun emptyHistory(){render("24-history-empty",model(page=Page.HISTORY).copy(entries=emptyList(),historyAvailable=true));compose.onNodeWithText("최근 위치 사용 기록이 없어요.").assertExists()}
    @Test fun homeBatchRestoreSelection(){val m=model(page=Page.RESTORE,applied=true).copy(homePicked=setOf("demo.weather"));render("25-home-batch-restore",m);compose.onNodeWithText("1개 복원").assertIsEnabled();compose.onNodeWithText("앱 검색").assertExists();compose.onNodeWithText("전체 선택").assertExists()}
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
    @Test fun reapplyHistoryHasItsOwnRowsAndNoLocationOrAdbCopy(){
        val now=System.currentTimeMillis()
        val m=history().copy(historyView=HistoryView.REAPPLY,connected=false,automaticReady=false,historyAvailable=false,reapplyHistory=listOf(
            ReapplyHistoryItem("recent","demo.weather","날씨",now,true,ReapplySource.APP_OPEN),
            ReapplyHistoryItem("older","demo.maps","지도",now-60_000,false,ReapplySource.MANUAL)
        ))
        render("92-reapply-history",m)
        compose.onAllNodesWithText("재적용 기록").assertCountEquals(2)
        compose.onNodeWithText("날씨").assertExists();compose.onNodeWithText("demo.weather").assertDoesNotExist()
        compose.onNodeWithText("재적용 완료 · ${ReapplySource.APP_OPEN.label} · 방금 전").assertExists()
        compose.onNodeWithText("재적용 실패 · ${ReapplySource.MANUAL.label} · 1분 전").assertExists()
        compose.onNodeWithText("현재 기록을 확인하려면 무선 디버깅으로 연결해 주세요.").assertDoesNotExist()
        compose.onNodeWithText("최근 위치를 쓴 앱이에요. 시스템 서비스까지 보려면 ‘전체 위치 사용’을 누르세요.").assertDoesNotExist()
    }
    @Test fun reapplyHistoryEmptyMessageAndRefreshCannotDuplicate(){
        val m=history().copy(historyView=HistoryView.REAPPLY,reapplyHistory=emptyList(),rechecking=true)
        render("92-reapply-empty",m)
        compose.onNodeWithText("아직 재적용 기록이 없어요.").assertExists()
        compose.onNodeWithContentDescription("다시 확인").assertIsNotEnabled()
    }
    @Test fun reapplyHistoryCanRefreshWithoutAdbConnection(){
        var refreshed=false
        val m=history().copy(historyView=HistoryView.REAPPLY,connected=false,automaticReady=false)
        controller.get().setContent{BlueDotApp(m,{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},refreshHistory={refreshed=true})}
        compose.onNodeWithContentDescription("다시 확인").assertIsEnabled().performClick()
        org.junit.Assert.assertTrue(refreshed)
    }
    @Test fun pullingDownTheEmptyOfflineHistoryRefreshesOnce(){
        var refreshes=0
        val m=history().copy(historyView=HistoryView.REAPPLY,connected=false,automaticReady=false,reapplyHistory=emptyList())
        controller.get().setContent{BlueDotApp(m,{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},refreshHistory={refreshes++})}
        compose.onRoot().performTouchInput { swipeDown(startY=height*0.5f,endY=height*0.8f,durationMillis=600) }
        compose.waitForIdle()
        assertTrue("Pull refresh should call the local callback once",refreshes==1)
    }
    @Test fun importedHistoryUsesKnownAppNamesWithoutAnotherScan(){
        val m=history().copy(historyView=HistoryView.REAPPLY,discovered=listOf(DiscoveredApp("demo.weather","날씨",12000)),
            reapplyHistory=listOf(ReapplyHistoryItem("legacy","demo.weather","demo.weather",System.currentTimeMillis(),true,ReapplySource.BACKGROUND)))
        controller.get().setContent{BlueDotApp(m,{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})}
        compose.onNodeWithText("날씨").assertExists();compose.onNodeWithText("demo.weather").assertDoesNotExist()
    }
}
