package app.bluedotoff

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import app.bluedotoff.core.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.platform.ComposeView
import android.view.ViewGroup
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import androidx.test.espresso.IdlingPolicies
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="ko-rKR-w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetailActionTest {
    @get:Rule val compose=createEmptyComposeRule()
    private lateinit var controller:ActivityController<ComponentActivity>

    @Before fun create(){IdlingPolicies.setMasterPolicyTimeout(3,TimeUnit.SECONDS);controller=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()}
    @After fun destroy(){
        val content=controller.get().findViewById<ViewGroup>(android.R.id.content)
        for(i in 0 until content.childCount)(content.getChildAt(i) as? ComposeView)?.disposeComposition()
        content.removeAllViews();controller.pause().stop().destroy();shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun verifiedAppliedDisablesHideButKeepsRestoreAvailable(){
        showDetail(entry(TargetState.APPLIED,enabled=true,settlingVerified=true))
        compose.onNode(hasText("적용 중") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("숨김 해제 · 원래 상태로 복원").assertIsEnabled()
    }

    @Test fun unverifiedAppliedRemainsRetryable(){
        showDetail(entry(TargetState.APPLIED,enabled=true,settlingVerified=false))
        compose.onNodeWithText("bluedotOFF 재적용").assertIsEnabled()
    }
    @Test fun recoverableFailureRemainsRetryable(){
        showDetail(entry(TargetState.FAILED_SAFE,enabled=true,settlingVerified=false,error="작업을 중단했고 원래 상태를 확인했어요."))
        compose.onNodeWithText("bluedotOFF 재적용").assertIsEnabled()
        compose.onNodeWithText("숨김 해제 · 원래 상태로 복원").assertIsEnabled()
    }

    @Test fun latestReadShowingResetAllowsReapplyDespiteOlderAppliedRecord(){
        showDetail(entry(TargetState.APPLIED,enabled=true,settlingVerified=true),fineFlags=0x80301)
        compose.onNodeWithText("재적용 필요").assertExists()
        compose.onNodeWithText("bluedotOFF 재적용").assertIsEnabled()
    }
    private fun showDetail(e:Entry,fineFlags:Int=0x80201){
        val snapshot=Snapshot(e.identity,e.label,listOf(e.identity.packageName),true,true,true,false,fineFlags,0x301,0,4,4,false)
        val model=UiModel(gate=Gate.READY,page=Page.DETAIL,entries=listOf(e),candidates=listOf(snapshot),selected=e.identity.packageName,connected=true,freshPackages=setOf(e.identity.packageName))
        controller.get().setContent{BlueDotApp(model,{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})}
        compose.waitForIdle()
    }

    private fun entry(state:TargetState,enabled:Boolean,settlingVerified:Boolean,error:String="")=Entry(
        Identity("demo.weather",0,10792,listOf("demo"),1,1,1),"날씨",256,enabled,state,
        1728000000000,"demo","demo",true,error=error,settlingVerified=settlingVerified)
}
