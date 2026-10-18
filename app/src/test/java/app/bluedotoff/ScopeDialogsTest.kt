package app.bluedotoff

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import app.bluedotoff.core.ApplicationScope
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit
import androidx.test.espresso.IdlingPolicies

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="ko-rKR-w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScopeDialogsTest {
    @get:Rule val compose=createEmptyComposeRule()
    private lateinit var controller:ActivityController<ComponentActivity>
    private var dialog:AutoCloseable?=null
    @Before fun create(){IdlingPolicies.setMasterPolicyTimeout(3,TimeUnit.SECONDS);controller=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()}
    @After fun destroy(){dialog?.close();controller.pause().stop().destroy()}
    private fun screenshot(name:String){
        compose.waitForIdle();shadowOf(Looper.getMainLooper()).idle()
        val view=checkNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog().window).decorView
        val bmp=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp));val dir=File(checkNotNull(System.getProperty("screenshotDir")));dir.mkdirs()
        File(dir,"$name.png").outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)};bmp.recycle()
    }
    @Test fun twoOptionsMapLegacyMiddleScopeToAllWithoutCommitting(){
        var chosen:ApplicationScope?=null
        dialog=ScopeDialogs.choose(controller.get(),ApplicationScope.GROUPS,tint=0f){chosen=it}
        compose.onNodeWithText("위험도 보통").assertIsDisplayed()
        compose.onNodeWithText("3단계").assertDoesNotExist()
        compose.onAllNodesWithText("일반 적용").onFirst().performClick()
        compose.onNodeWithText("위험도 낮음").assertIsDisplayed()
        compose.onNodeWithText("위험도 보통").assertDoesNotExist()
        compose.onNodeWithText("다음").assertIsDisplayed();Assert.assertNull(chosen)
        screenshot("84-scope-choice-ko")
        compose.onNodeWithText("다음").performClick();Assert.assertEquals(ApplicationScope.NORMAL,chosen)
    }
    @Test fun restoreListIsOptionalAndCancelNeverRunsTheRestoreCallback(){
        var confirmed=false
        dialog=ScopeDialogs.confirm(controller.get(),ApplicationScope.NORMAL,listOf("전화" to "demo.phone","번호 안내" to "demo.directory"),tint=0f){confirmed=true}
        compose.onNodeWithText("demo.phone").assertDoesNotExist()
        compose.onNodeWithText("복원 후 범위 변경").assertIsDisplayed()
        screenshot("84-scope-restore-ko")
        compose.onNodeWithText("복원 대상 2개 보기").performClick()
        compose.onNodeWithText("demo.phone").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("demo.directory").performScrollTo().assertIsDisplayed()
        Assert.assertFalse(confirmed);compose.onNodeWithText("취소").performClick();Assert.assertFalse(confirmed)
    }
    @Test @Config(qualifiers="en-rUS-w412dp-h915dp-night-xxhdpi") fun darkEnglishUsesTwoOptionsAndModerateRisk(){
        dialog=ScopeDialogs.choose(controller.get(),ApplicationScope.ALL,tint=0f){}
        compose.onNodeWithText("Standard mode").assertIsDisplayed();compose.onAllNodesWithText("All apps mode").onFirst().assertIsDisplayed();compose.onNodeWithText("Level 3").assertDoesNotExist()
        compose.onNodeWithText("Moderate risk").assertIsDisplayed();compose.onNodeWithText("Next").assertIsDisplayed()
        screenshot("84-scope-choice-en-dark")
    }
    @Test @Config(qualifiers="ko-rKR-w412dp-h915dp-xxhdpi",fontScale=1.5f) fun largeFontKeepsActionsReachable(){
        dialog=ScopeDialogs.choose(controller.get(),ApplicationScope.ALL,tint=0f){}
        compose.onNodeWithText("다음").assertIsDisplayed();compose.onNodeWithText("취소").assertIsDisplayed()
        screenshot("84-scope-choice-large")
    }
    @Test @Config(qualifiers="ko-rKR-w960dp-h750dp-xhdpi") fun unfoldedDialogRemainsCenteredAndBounded(){
        dialog=ScopeDialogs.choose(controller.get(),ApplicationScope.GROUPS,tint=0f){}
        compose.onNodeWithText("다음").assertIsDisplayed();screenshot("84-scope-choice-wide")
    }
    /** First setup: one explicit "이 범위로 시작" picks the scope (nothing is chosen before the tap). */
    @Test fun firstSetupScopeIsChosenWithOneExplicitTap(){
        var chosen:ApplicationScope?=null
        dialog=ScopeDialogs.choose(controller.get(),ApplicationScope.NORMAL,firstSetup=true,tint=0f){chosen=it}
        Assert.assertNull(chosen)
        compose.onNodeWithText("이 범위로 시작").assertIsDisplayed().performClick();Assert.assertEquals(ApplicationScope.NORMAL,chosen)
    }
    // Native Robolectric drawing can leave a resized Dialog layout pending.
    // Check deferred-load interactions with the legacy renderer; the other
    // tests still render native light/dark/large-font dialog screenshots.
    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY) fun loweringShowsConfirmationBeforeReadCompletesAndCannotRestoreEarly(){
        var result:((List<Pair<String,String>>?,String)->Unit)?=null
        var confirmed=false
        dialog=ScopeDialogs.lowering(controller.get(),ApplicationScope.NORMAL,tint=0f,load={result=it}){confirmed=true}
        compose.onNodeWithText("범위를 낮출까요?").assertIsDisplayed()
        compose.onNodeWithText("등록 앱의 적용 범위를 확인하고 있어요.").assertIsDisplayed()
        compose.onNode(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate)).assertExists()
        compose.onNodeWithText("이 범위 사용").assertIsNotEnabled()
        Assert.assertFalse(confirmed)
        compose.runOnIdle{result!!(listOf("전화" to "demo.phone"),"")}
        shadowOf(Looper.getMainLooper()).idle()
        compose.onNodeWithText("복원 후 범위 변경").assertIsEnabled()
        compose.onNodeWithText("등록한 앱 중 1개가 새 범위에서 빠져서 원래 상태로 돌아가요.").assertExists()
        compose.onNodeWithText("취소").performClick();Assert.assertFalse(confirmed)
    }
    @Test fun failedScopePreviewKeepsConfirmationDisabled(){
        dialog=ScopeDialogs.lowering(controller.get(),ApplicationScope.NORMAL,load={it(null,"조회 실패")}){}
        compose.onNodeWithText("조회 실패").assertExists()
        compose.onNodeWithText("이 범위 사용").assertIsNotEnabled()
    }
    /** Owner 2026-10-18: no platform dialogs; setup and Home dialogs share the panel look. */
    @Test fun setupNoticesUseThePanelStyle(){
        var done=0
        dialog=ScopeDialogs.alert(controller.get(),"알림을 켜면 더 쉽게 연결할 수 있어요","본문","알림 허용" to {done+=1},"분할 화면으로 계속" to {done+=2},"돌아가기" to {},onClose={done+=10})
        compose.onNodeWithText("돌아가기").assertIsDisplayed();screenshot("86-setup-notice-panel")
        compose.onNodeWithText("분할 화면으로 계속").performClick();Assert.assertEquals("action ran and the panel closed",12,done)
        dialog=ScopeDialogs.watch(controller.get()){};compose.onNodeWithText("일부 은행·결제 앱이 열리지 않을 수 있어요.").assertIsDisplayed();screenshot("86-watch-risk-panel")
        dialog?.close();dialog=ScopeDialogs.autoBlocker(controller.get());compose.onNodeWithText("보안 설정 열기").assertIsDisplayed();screenshot("86-setup-auto-blocker-panel")
    }
    @Test fun updateOfferUsesThePanelStyle(){
        var answer:Boolean?=null
        controller.get().setContent{androidx.compose.material3.MaterialTheme(colorScheme=BlueDotAppearance.scheme(controller.get())){UpdateOfferDialog{answer=it}}}
        compose.onNodeWithText("끄기").assertIsDisplayed();screenshot("86-update-offer-panel")
        compose.onNodeWithText("켜기").performClick();Assert.assertEquals(true,answer)
    }
}
