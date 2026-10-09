package app.bluedotoff

import android.app.Application
import android.content.res.Configuration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class UiTextTest {
    private fun language(tag:String)=RuntimeEnvironment.getApplication().createConfigurationContext(Configuration().apply{setLocale(Locale.forLanguageTag(tag))})
    @Test fun languageIsReadFromCurrentConfigurationRatherThanCached(){
        assertEquals("Home",UiText.text(language("en-US"),"홈"))
        assertEquals("홈",UiText.text(language("ko-KR"),"홈"))
        assertEquals("Home",UiText.text(language("en-GB"),"홈"))
    }
    @Test fun everyNonKoreanLanguageFallsBackToEnglish(){
        for(tag in listOf("es-ES","zh-CN","ja-JP","de-DE"))assertEquals(tag,"Home",UiText.text(language(tag),"홈"))
    }
    @Test fun dynamicCountsAndCompletionTranslateWithoutTouchingValues(){
        assertEquals("35 / 70 apps checked · 50%",UiText.text(language("en"),"35 / 70개 확인 · 50%"))
        assertEquals("bluedotOFF applied to 70 apps.",UiText.text(language("en"),"70개 앱에 bluedotOFF 적용완료"))
        assertEquals("Example app icon",UiText.text(language("en"),"Example app 아이콘"))
        assertEquals("카카오맵",UiText.text(language("en"),"카카오맵"))
    }
    @Test fun unknownErrorsRemainVisibleAndNeverBecomeSuccess(){
        assertEquals("UNKNOWN_IO_FAILURE",UiText.text(language("en"),"UNKNOWN_IO_FAILURE"))
    }
    @Test fun internalDiagnosticsBecomePlainErrorGuidance(){
        assertEquals(UiText.GENERIC_ERROR,UiText.error("HELPER_OPERATION_REJECTED"))
        assertEquals(UiText.GENERIC_ERROR,UiText.error("ADB session generation changed"))
        assertEquals("복원 기록 저장 확인 실패",UiText.error("복원 기록 저장 확인 실패"))
        assertEquals("",UiText.error(""))
        assertEquals("The task couldn't be finished. Check the device connection and try again.",UiText.text(language("en"),UiText.error("Helper descriptor mismatch")))
    }
    @Test fun offlineLastVerifiedCopyDoesNotPromiseCurrentLiveState(){
        val id=app.bluedotoff.core.Identity("test.app",0,10001,listOf("signer"),1,1,1)
        val entry=app.bluedotoff.core.Entry(id,"app",256,true,app.bluedotoff.core.TargetState.APPLIED,1,"fw","boot",true,settlingVerified=true)
        val model=UiModel(entries=listOf(entry),connected=false)
        assertTrue(model.allLastVerifiedApplied)
        assertEquals("bluedotOFF applied · Last checked",UiText.text(language("en"),model.status(entry)))
        assertFalse(model.copy(entries=listOf(entry.copy(state=app.bluedotoff.core.TargetState.NEEDS_REAPPLY_BOOT))).allLastVerifiedApplied)
    }
}
