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
    @Test fun backgroundWatchTextsHaveEnglish(){
        for(k in listOf("백그라운드 감시","백그라운드 감시를 켤까요?","켜기 전에 꼭 읽어주세요.","USB 디버깅을 항상 켜 둬야 해요.",
            "bluedotOFF를 열지 않아도 숨김이 풀린 앱을 자동으로 다시 숨겨요.","백그라운드에서 동작해서 배터리 사용량이 늘 수 있어요.",
            "일부 은행·결제 앱은 USB 디버깅이 켜져 있으면 실행되지 않을 수 있어요.","USB 디버깅과 무선 디버깅이 모두 꺼지면 감시가 멈춰요.",
            "USB 디버깅을 켜 두면 bluedotOFF를 열지 않아도 숨김이 풀린 앱을 바로 다시 적용해요.","백그라운드 감시가 멈췄을 때 알려드려요.","백그라운드 감시가 멈췄어요",
            "다시 켜려면 Wi-Fi에 연결된 상태에서 bluedotOFF를 열어주세요.","진단 정보 복사 및 보내기",
            "진단 정보를 복사하고 메일 앱을 열었어요. 내용을 확인한 뒤 보내 주세요. 설치된 앱 목록이나 위치 기록은 들어 있지 않아요.")+WatchNotification.Reason.entries.map{it.message})
            assertNotEquals(k,k,UiText.text(language("en"),k))
        assertEquals("OK (3s)",UiText.text(language("en"),"확인 (3초)"))
        assertEquals("Could not open a mail app, so the diagnostic info was only copied. Paste it into a mail to a@b.c.",UiText.text(language("en"),"메일 앱을 열 수 없어서 진단 정보를 복사만 했어요. a@b.c 로 붙여넣어 보내 주세요."))
    }
    /** The stop advice names USB debugging when it is off, and wireless debugging when the app cannot turn it on itself. */
    @Test fun watchStopAdviceMatchesTheSituation(){
        assertTrue(WatchNotification.advice(usbOn=false,autoManaged=true).startsWith("USB 디버깅을 다시 켜고"))
        assertTrue(WatchNotification.advice(usbOn=false,autoManaged=false).contains("무선 디버깅을 켠 뒤"))
        assertFalse(WatchNotification.advice(usbOn=true,autoManaged=true).contains("USB"))
        for(usb in listOf(true,false))for(auto in listOf(true,false)){val k=WatchNotification.advice(usb,auto);assertNotEquals(k,k,UiText.text(language("en"),k))}
    }
    @Test fun everyNonKoreanLanguageFallsBackToEnglish(){
        for(tag in listOf("es-ES","zh-CN","ja-JP","de-DE"))assertEquals(tag,"Home",UiText.text(language(tag),"홈"))
    }
    @Test fun dynamicCountsAndCompletionTranslateWithoutTouchingValues(){
        assertEquals("35 / 70 apps checked · 50%",UiText.text(language("en"),"35 / 70개 확인 · 50%"))
        assertEquals("Applied to 70 apps.",UiText.text(language("en"),"70개 앱 적용 완료"))
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
        assertEquals("Applied · Last checked",UiText.text(language("en"),model.status(entry)))
        assertFalse(model.copy(entries=listOf(entry.copy(state=app.bluedotoff.core.TargetState.NEEDS_REAPPLY_BOOT))).allLastVerifiedApplied)
    }
}
