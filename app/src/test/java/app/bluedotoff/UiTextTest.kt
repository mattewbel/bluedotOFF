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
    /** 1.1.2: one catalog per language; unsupported languages use English; zh splits by script/region. */
    @Test fun catalogIsChosenPerLanguageWithEnglishForTheRest(){
        val cases=mapOf("ko-KR" to null,"en-US" to "en","fr-FR" to "en","de" to "en","es-MX" to "es","pt-PT" to "pt-BR","pt-BR" to "pt-BR",
            "hi-IN" to "hi","zh-TW" to "zh-Hant","zh-HK" to "zh-Hant","zh-Hant-CN" to "zh-Hant","zh-CN" to "zh-Hans","zh-Hans" to "zh-Hans","zh" to "zh-Hans")
        for((tag,expected) in cases)assertEquals(tag,expected,UiText.catalogFor(Locale.forLanguageTag(tag)))
    }
    /** A sentence missing in a language's catalog shows in English, never in Korean (unless English lacks it too). */
    @Test fun missingTranslationFallsBackToEnglish(){
        assertEquals("Home",UiText.text(language("fr-FR"),"홈"))
        assertEquals("존재하지 않는 진단 문구",UiText.text(language("es"),"존재하지 않는 진단 문구"))
    }
    @Test fun backgroundWatchTextsHaveEnglish(){
        for(k in listOf("백그라운드 감시","백그라운드 감시를 켤까요?","켜기 전에 꼭 읽어주세요.","USB 디버깅을 항상 켜 둬야 해요.",
            "bluedotOFF를 열지 않아도 숨김이 풀린 앱을 자동으로 다시 숨겨요.","백그라운드에서 동작해서 배터리 사용량이 늘 수 있어요.",
            "일부 은행·결제 앱은 USB 디버깅이 켜져 있으면 실행되지 않을 수 있어요.","USB 디버깅과 무선 디버깅이 모두 꺼지면 감시가 멈춰요.",
            "USB 디버깅을 켜 두면 bluedotOFF를 열지 않아도 숨김이 풀린 앱을 감지하는 대로 다시 적용해요.","백그라운드 감시가 멈췄을 때 알려드려요.","백그라운드 감시가 멈췄어요",
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
    /** 1.1.2: languages without a catalog still show English; supported ones show their own catalog. */
    @Test fun everyNonKoreanLanguageFallsBackToEnglish(){
        for(tag in listOf("fr-FR","ja-JP","de-DE","ru-RU"))assertEquals(tag,"Home",UiText.text(language(tag),"홈"))
        for(tag in listOf("es-ES","pt-BR","hi-IN","zh-TW","zh-CN")){val shown=UiText.text(language(tag),"홈");assertNotEquals(tag,"홈",shown);assertNotEquals(tag,"Home",shown)}
    }
    @Test fun dynamicCountsAndCompletionTranslateWithoutTouchingValues(){
        assertEquals("35 / 70 apps checked · 50%",UiText.text(language("en"),"35 / 70개 확인 · 50%"))
        assertEquals("Applied to 70 apps.",UiText.text(language("en"),"70개 앱 적용 완료"))
        assertEquals("Example app icon",UiText.text(language("en"),"Example app 아이콘"))
        assertEquals("카카오맵",UiText.text(language("en"),"카카오맵"))
    }
    @Test fun batteryOptimizationCopyAndProgressHaveEnglish(){
        val strings=listOf(
            BatteryOptimization.TITLE,
            BatteryOptimization.DESCRIPTION,
            "배터리 사용 제한을 해제할까요?",
            "화면이 꺼진 동안에도 감시가 덜 늦어지도록, 배터리 설정을 ‘제한 없음’으로 바꿀 수 있어요. 배터리 사용량이 늘 수 있어요. 필수 설정은 아니며, 나중에 설정에서도 바꿀 수 있어요.",
            "배터리 → 제한 없음에서 설정을 바꿀 수 있어요.",
            "설정 화면을 열지 못했어요. 휴대폰 설정의 앱 정보 → 배터리에서 바꿔주세요.",
            "해제됨","설정하기","확인하기","앱 정보 → 배터리에서 ‘최적화’를 선택해주세요.",
            "끄려면 앱 정보 → 배터리에서 ‘최적화’를 선택해주세요.","배터리 설정 상태를 확인할 수 없어요. 설정 열기")
        for(k in strings)assertNotEquals(k,k,UiText.text(language("en"),k))
        assertEquals("Disable battery optimization",UiText.text(language("en"),BatteryOptimization.TITLE))
        assertEquals("Connection setup · 10 / 12",UiText.text(language("en"),"연결 준비 · 10 / 12"))
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
    @Test fun legacyRecoverableErrorsGetUpdatedCopyButRollbackBlockStaysExact(){
        assertEquals("변경 후 상태를 확인하지 못해 원래 표시 상태로 되돌렸어요. 다시 확인해 주세요.",UiText.error("안전성 확인에 실패해 원래 표시 상태로 복원했습니다."))
        assertEquals("표시 상태가 유지되는지 확인하지 못했어요. 다시 확인해 주세요.",UiText.error("현재 상태의 지속성을 확인하지 못했습니다. 다시 확인해 주세요."))
        assertEquals("숨김 유지 여부를 다시 확인해야 해요. 재적용을 눌러주세요.",UiText.error("이전 빌드의 기록은 지속성 검사가 없어 새 적용 확인이 필요합니다."))
        val serious="복원을 확인하지 못했습니다. 추가 변경을 차단했습니다."
        assertEquals(serious,UiText.error(serious))
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
