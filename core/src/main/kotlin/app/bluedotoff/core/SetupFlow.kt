package app.bluedotoff.core

/** Actual settings and a bounded live session determine the step. No persisted step number. */
enum class SetupStage {
    WELCOME, NOTIFICATION_PERMISSION, DEVELOPER_OPTIONS_OFF, WIFI_NOT_CONNECTED, WIRELESS_DEBUGGING_OFF,
    NEARBY_PERMISSION, GUIDE_PAIRING_CODE_PAGE, WAIT_FOR_PAIRING_ADVERTISEMENT,
    PAIRING_NOTIFICATION_READY, PAIRING, CONNECTING, VERIFYING, AUTOMATIC_MANAGEMENT, ENABLING_MANAGEMENT, APPLICATION_SCOPE, FINAL_CHECK, READY,
    PAIRING_RETRY, CONNECTION_RECOVERY, DISCOVERY_RECOVERY, SESSION_EXPIRED, UNSUPPORTED
}
enum class SetupAction { START, NOTIFICATIONS, DEVICE_INFO, WIFI_SETTINGS, WIRELESS_SETTINGS, NEARBY, AUTOMATIC_MANAGEMENT, APPLICATION_SCOPE, RETRY_PAIRING, RETRY_CONNECTION, CHOOSE_CONNECTION, RESUME, NONE }
data class SetupFacts(
    val welcomed:Boolean=true, val notificationResolved:Boolean=true,
    val developer:Boolean=true, val wireless:Boolean=true, val supported:Boolean=true,
    val paired:Boolean=false, val nearbyResolved:Boolean=true, val sessionStarted:Boolean=false,
    val pairAdvertisement:Boolean=false, val pairing:Boolean=false, val connecting:Boolean=false,
    val verifying:Boolean=false, val verified:Boolean=false, val forcePairing:Boolean=false,
    val error:SetupStage?=null, val wifiConnected:Boolean=true, val completedOffline:Boolean=false,
    val managementChoiceResolved:Boolean=true, val managing:Boolean=false, val applicationScopeResolved:Boolean=true, val finalCheckResolved:Boolean=true
)
data class SetupPresentation(val stage:SetupStage,val progress:Int,val title:String,val body:String,val action:SetupAction,val button:String="")
object SetupFlow {
    private fun completed(f:SetupFacts)=when {
        f.managing->SetupStage.ENABLING_MANAGEMENT
        !f.managementChoiceResolved->SetupStage.AUTOMATIC_MANAGEMENT
        !f.applicationScopeResolved->SetupStage.APPLICATION_SCOPE
        !f.finalCheckResolved->SetupStage.FINAL_CHECK
        else->SetupStage.READY
    }
    fun stage(f:SetupFacts):SetupStage=when {
        !f.supported->SetupStage.UNSUPPORTED
        !f.welcomed&&!f.paired->SetupStage.WELCOME
        !f.notificationResolved&&!f.paired->SetupStage.NOTIFICATION_PERMISSION
        !f.developer->SetupStage.DEVELOPER_OPTIONS_OFF
        !f.wifiConnected->SetupStage.WIFI_NOT_CONNECTED
        f.verified&&f.completedOffline&&f.paired->completed(f)
        !f.nearbyResolved&&(!f.paired||f.forcePairing)->SetupStage.NEARBY_PERMISSION
        !f.wireless->SetupStage.WIRELESS_DEBUGGING_OFF
        f.pairing->SetupStage.PAIRING
        f.connecting->SetupStage.CONNECTING
        f.verifying->SetupStage.VERIFYING
        f.error!=null->f.error
        f.verified&&f.paired&&!f.forcePairing->completed(f)
        f.paired&&!f.forcePairing->SetupStage.CONNECTING
        !f.sessionStarted->SetupStage.GUIDE_PAIRING_CODE_PAGE
        f.pairAdvertisement->SetupStage.PAIRING_NOTIFICATION_READY
        else->SetupStage.WAIT_FOR_PAIRING_ADVERTISEMENT
    }
    fun presentation(f:SetupFacts):SetupPresentation {
        val s=stage(f)
        return when(s){
            SetupStage.WELCOME->SetupPresentation(s,1,"연결 준비","몇 가지 설정을 켜면 이 앱에서 바로 연결할 수 있어요.",SetupAction.START,"시작하기")
            SetupStage.NOTIFICATION_PERMISSION->SetupPresentation(s,2,"알림을 켜면 연결이 쉬워요","알림에서 페어링 코드를 입력하고, 재부팅·업데이트 후 확인 안내를 받을 수 있어요. 알림 없이 연결하려면 분할 화면이 필요해요.",SetupAction.NOTIFICATIONS,"알림 허용")
            SetupStage.DEVELOPER_OPTIONS_OFF->SetupPresentation(s,3,"먼저 개발자 옵션을 켜주세요","휴대전화 정보에서 ‘소프트웨어 정보’를 열고 ‘빌드번호’를 7번 연속 누르세요. 돌아오면 다음 단계로 넘어가요.",SetupAction.DEVICE_INFO,"휴대전화 정보 열기")
            SetupStage.WIFI_NOT_CONNECTED->SetupPresentation(s,4,"먼저 Wi-Fi에 연결해주세요","Wi-Fi를 켜고 네트워크에 연결하세요. 연결되면 자동으로 다음 단계로 넘어가요.",SetupAction.WIFI_SETTINGS,"Wi-Fi 열기")
            SetupStage.WIRELESS_DEBUGGING_OFF->SetupPresentation(s,6,"무선 디버깅을 켜주세요",if(f.paired)"이미 페어링된 기기예요. 무선 디버깅만 켜면 코드를 다시 입력할 필요가 없어요." else "개발자 옵션에서 ‘무선 디버깅’을 켜주세요. 이어서 바로 페어링 코드 화면을 열어도 돼요.",SetupAction.WIRELESS_SETTINGS,"무선 디버깅 열기")
            SetupStage.NEARBY_PERMISSION->SetupPresentation(s,5,"근처 기기 권한을 허용해주세요","아래 버튼을 누르면 ‘근처에 있는 기기를 찾아 연결하고 기기 간 상대적 위치를 파악’하는 권한 창이 떠요. 그 창에서 ‘허용’을 눌러주세요. 이 휴대폰의 무선 연결을 찾는 데 사용해요.",SetupAction.NEARBY,"근처 기기 허용")
            SetupStage.GUIDE_PAIRING_CODE_PAGE,SetupStage.WAIT_FOR_PAIRING_ADVERTISEMENT->SetupPresentation(s,7,"‘페어링 코드로 기기 페어링’을 누르세요","코드 화면을 열어두세요. 준비되면 알림의 ‘코드 입력’에서 6자리 숫자를 입력하고 전송하세요.",SetupAction.WIRELESS_SETTINGS,"무선 디버깅 화면 열기")
            SetupStage.PAIRING_NOTIFICATION_READY->SetupPresentation(s,7,"페어링 코드를 입력하세요","설정의 코드 화면을 유지한 채 알림의 ‘코드 입력’을 누르세요. 입력 후 전송하면 자동으로 연결돼요.",SetupAction.NONE)
            SetupStage.PAIRING->SetupPresentation(s,7,"코드를 확인하고 있어요","잠시만 기다려주세요.",SetupAction.NONE)
            SetupStage.CONNECTING->SetupPresentation(s,8,"이 기기에 연결하고 있어요","저장된 연결 정보를 확인하고 있어요. 새 코드를 입력할 필요는 없어요.",SetupAction.NONE)
            SetupStage.VERIFYING->SetupPresentation(s,8,"기기와 앱을 확인하고 있어요","기기 호환성과 앱의 복원 기록을 먼저 확인해요. 적용 범위를 고른 뒤 전체 앱을 확인해요. 설정은 변경하지 않아요.",SetupAction.NONE)
            SetupStage.AUTOMATIC_MANAGEMENT->SetupPresentation(s,9,"자동 관리를 추천해요","권장 · 선택사항\n자동 관리를 사용하면 앱에서 작업할 때 무선 디버깅을 켜고, 작업과 연결 종료를 확인한 뒤 꺼요. 이를 위해 이 앱에 시스템 설정 변경 권한을 추가해요. Wi-Fi와 개발자 옵션은 직접 켜야 하며, 다른 무선 디버깅 연결도 함께 끊길 수 있어요. 나중에 설정에서 끌 수 있어요.",SetupAction.AUTOMATIC_MANAGEMENT,"자동 관리 사용 (권장)")
            SetupStage.ENABLING_MANAGEMENT->SetupPresentation(s,9,"자동 관리를 준비하고 있어요","연결 종료까지 확인한 뒤 적용 범위를 선택해요. 잠시만 기다려주세요.",SetupAction.NONE)
            SetupStage.APPLICATION_SCOPE->SetupPresentation(s,10,"적용 범위를 선택해주세요","신뢰하는 앱의 파란 위치 표시를 숨깁니다. 나중에 설정에서 범위를 바꿀 수 있어요.",SetupAction.APPLICATION_SCOPE,"적용 범위 선택")
            SetupStage.FINAL_CHECK->SetupPresentation(s,11,"마지막으로 기기와 앱을 확인해요","선택한 적용 범위로 앱의 위치 표시 상태를 확인해요. 설정은 변경하지 않아요.",SetupAction.NONE)
            SetupStage.READY->SetupPresentation(s,11,"준비 완료","연결을 확인했어요.",SetupAction.NONE)
            SetupStage.PAIRING_RETRY->SetupPresentation(s,7,"새 페어링 코드를 열어주세요","코드가 맞지 않거나 시간이 만료됐어요. 새 코드 화면을 열고 다시 입력해주세요.",SetupAction.RETRY_PAIRING,"새 코드로 다시 연결")
            SetupStage.CONNECTION_RECOVERY->SetupPresentation(s,8,"기기 연결을 다시 확인해주세요","연결 승인이 해제됐거나 연결이 끊겼을 수 있어요. 다시 확인해도 연결되지 않으면 한 번만 다시 페어링해주세요.",SetupAction.RETRY_CONNECTION,"연결 다시 확인")
            SetupStage.DISCOVERY_RECOVERY->SetupPresentation(s,8,"이 기기의 연결을 선택해주세요","무선 디버깅이 켜져 있는지 확인한 뒤, 목록에서 이 휴대폰을 선택해주세요.",SetupAction.CHOOSE_CONNECTION,"연결할 기기 선택")
            SetupStage.SESSION_EXPIRED->SetupPresentation(s,7,"연결 준비가 잠시 중단됐어요","코드 화면을 다시 열어 이어서 연결할 수 있어요.",SetupAction.RESUME,"이어서 연결")
            SetupStage.UNSUPPORTED->SetupPresentation(s,3,"이 기기의 지원 여부를 확인할 수 없어요","Samsung One UI 9 이상에서 지원 여부를 확인할 수 있어요. 대상 앱의 설정은 변경하지 않았어요.",SetupAction.NONE)
        }
    }
}

/** Notification replacement and removal are independent of whether permission was granted. */
enum class PairNotice { NONE, GUIDE, CODE }
object PairNoticePolicy {
    fun desired(stage:SetupStage,notifications:Boolean)=if(!notifications)PairNotice.NONE else when(stage){
        SetupStage.WAIT_FOR_PAIRING_ADVERTISEMENT->PairNotice.GUIDE
        SetupStage.PAIRING_NOTIFICATION_READY->PairNotice.CODE
        else->PairNotice.NONE
    }
}

/** Completion comes from actual facts, never from the displayed step number. */
data class SetupStepStatus(val number:Int,val label:String,val complete:Boolean,val current:Boolean)
object SetupProgress {
    val labels=listOf("시작하기","알림 선택","개발자 옵션","Wi-Fi 연결","근처 기기 권한","무선 디버깅","페어링 코드 입력","기기와 앱 확인","자동 관리 선택","적용 범위","최종 기기 확인")
    fun steps(f:SetupFacts):List<SetupStepStatus> {
        val current=SetupFlow.presentation(f).progress
        val done=listOf(f.welcomed||f.paired,f.notificationResolved||f.paired,f.developer,f.wifiConnected,
            f.nearbyResolved,f.wireless||(f.verified&&f.completedOffline),f.paired&&!f.forcePairing,
            f.verified,f.managementChoiceResolved,f.applicationScopeResolved,f.finalCheckResolved&&f.verified)
        return labels.mapIndexed{i,label->SetupStepStatus(i+1,label,done[i],i+1==current&&SetupFlow.stage(f)!=SetupStage.READY)}
    }
}
