# bluedotOFF test.13 — 최초 연결과 데이터 초기화 후 목록

이 버전은 초보자가 설정 화면에 한 번 들어가 무선 디버깅 ON → 페어링 코드 입력까지 이어갈 수 있도록 준비 순서를 바꾸고, 최초 연결의 마지막에 자동 관리 추천을 추가한다. 기본 앱 선택 목록에서 이미 표시가 꺼진 일반 앱이 사라지는 경우도 수정한다.

## 영상과 사용자 확인

- 약 116초, 1080×2520 영상은 로컬에서만 분석했다. 오디오 트랙은 없다. 원본·프레임·OCR·기기 식별정보·코드는 배포 소스에 포함하지 않는다.
- 사용자가 **앱 데이터/캐시를 초기화했다**고 확인했다. 영상은 새 사용자 시작, 앱 선택 목록 약 2개, 등록 앱이 없는 홈을 보여준다.
- 초기화는 bluedotOFF의 등록·원래 비트·페어링 키·자동 관리 동의를 제거한다. 다른 앱에 적용된 OS의 표시 민감도 비트를 복원하는 기능은 아니다.
- 기존 기본 Picker는 `managed || STRONG_CANDIDATE`만 포함한다. 빈 journal + 이미 FINE 0x100 OFF인 대상은 `EXEMPT_SYSTEM_USAGE`여서 빠진다. 코드·사용자 확인·영상과 일치하는 원인이다. 개발 당시에는 전체 개수/flags가 미확인이었고, 아래 실기기 검증에서 실제 목록·샘플 flags를 대조했다.
- 준비 화면의 검은 녹화는 native Setup Activity 전체에 적용된 `FLAG_SECURE`와 일치한다. 앱은 이 보호를 유지한다. 다른 앱/Settings 자체의 녹화 제한까지 동일 원인으로 단정하지 않는다. [Android FLAG_SECURE 설명](https://developer.android.com/security/fraud-prevention/activities).

## 1. 실제 소프트웨어 정보 경로

과거 Fold7에서 수집한 Settings APK를 다시 읽었다. `Settings$MyDeviceInfoActivity`는 exported=true이며 공개 `android.settings.DEVICE_INFO_SETTINGS`가 실제 삼성 휴대전화 정보로 연결된다. `res/xml/sec_my_device_info.xml`의 `software_info` 항목은 내부 `com.samsung.android.settings.deviceinfo.softwareinfo.SoftwareInfoSettings` fragment로 연결된다. 그 내부 화면을 여는 `com.android.settings.SubSettings`는 exported=false이다. 실제 `res/xml/sec_software_info_settings.xml`의 `build_number`는 `BuildNumberPreferenceController`를 사용한다.

`Settings$FirmwareVersionActivity`는 공개지만 실제 삼성 개발자 옵션 활성화 화면과 다른 Catalyst/SimpleBuildNumber 화면이다. 이 잘못된 바로가기를 다시 사용하지 않는다. 이 firmware에서 실제 소프트웨어 정보용 공개 action/activity를 확인하지 못했으므로, 최초 연결 전 일반 앱 권한으로 그 화면까지 확실히 바로 이동시킬 수 있다고 약속하지 않는다. 내부 fragment를 다른 exported 화면에 주입하거나 shell 권한으로 억지로 실행하지 않는다.

개선:

- 공개 삼성 software action이 실제로 접근 가능하면 그 경로를 우선한다. 현재 Fold7에서는 확인되지 않은 경로이므로 휴대전화 정보 fallback이 유지된다.
- 휴대전화 정보 Intent에 실제 preference key `software_info` 강조 인자를 보낸다. 실제 software action이 있는 기기에서는 `build_number`를 사용한다.
- 이동할 때 **소프트웨어 정보 → 빌드번호 7번 누르기** Toast를 띄운다. 앱 안내와 그림도 유지한다.
- 강조 인자는 Settings가 처리하는 요청이다. 우리 앱이 다른 앱 화면을 직접 그리는 기능이 아니며, 실제 Fold7의 강조/스크롤 효과는 미확인이다. Toast는 잠시 표시되는 안내다. 별도 화면 위 표시/접근성 권한을 추가하지 않았다.

[SettingsActivity 원본](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/master/src/com/android/settings/SettingsActivity.java)은 `:settings:fragment_args_key`/`:settings:show_fragment_args` 전달 규약 참고이며, 삼성 화면의 실제 처리 여부를 보증하는 증거는 아니다.

## 2. 앱으로 돌아오지 않는 페어링

기존: 무선 디버깅 OFF에서 Settings를 열면 아직 짧은 서비스가 준비되지 않았다. ON 이후 근처 기기 권한 단계가 되지만 앱이 뒤에 있어 권한/서비스 준비가 늦을 수 있었다. Android runtime은 배경에서 서비스가 없어도 `keepPairingAlive(true)` 성공을 반환했다.

수정:

- 새 페어링의 무선 디버깅 버튼을 누르면, 필요한 근처 기기 권한을 **우리 앱이 보이는 동안 먼저** 요청한다.
- 허용 이후 Settings를 열기 전에 기존 pairing 전용 shortService를 준비한다. 아직 무선 디버깅 OFF여도 명시적인 Settings 이동 구간에는 감지를 유지한다.
- Settings에서 ON → 코드 창을 바로 열면 새 광고로 알림을 준비한다. Activity resume는 필수 조건이 아니다.
- 코드 창 닫기/재열기마다 기존 lease/generation 무효화·알림 교체를 유지한다. 이전 알림 답변이나 중복 답변은 두 번째 pairing을 실행하지 않는다.
- 배경에서는 실제 요청한 서비스가 있는 경우에만 유지 성공으로 판단한다. 권한 거절/서비스 시작 실패는 기존 수동 입력으로 안내한다.
- 기존 최대 150초, 성공·취소·timeout 종료, non-sticky 정책은 유지한다. 무제한 배경 탐색/상시 서비스는 추가하지 않는다. 늦게 코드 창을 열어 시간이 끝났다면 앱에서 이어서 연결해야 한다.

[Android 백그라운드 서비스 제한](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)을 피하기 위해 화면이 보이는 사용자 조작 시점에 준비한다. OS의 실제 광고 전달 시간이나 즉시 알림 도착은 아직 Fold7에서 재측정하지 않았다.

## 3. 데이터 초기화 후 앱 목록

`PickerPolicy.alreadyNotSensitive`는 아래 조건을 모두 만족하는 앱을 기본 목록에 남긴다.

- 기존 `canCheck` 통과: authoritative, 안전성 차단 사유 없음, user0 일반 application UID, 확인 완료.
- launcher 존재, internal service 아님, 위치 권한 선언 존재.
- 지원된 profile/예외 목록/권한/정상 raw mode를 읽은 결과 `EXEMPT_SYSTEM_USAGE`, granted preflight의 branch=0x100, logicalSystemUsage=true.

이 상태는 **현재 표시 없음**으로 구분한다. 예전에 우리 앱이 숨겼다는 사실이나 삭제된 원래 ON 값을 추정하지 않는다. denied branch, core/multi-peer/internal/공식 package exemption/unknown은 이 예외로 포함하지 않는다. 기존 STRONG_CANDIDATE와 등록 앱 포함, 전체 위치 사용 보기, History 정책, 실제 SafetyEngine은 그대로다.

새 등록 시 현재 상태가 복원 기준임을 Picker 아래에서 안내한다. 등록 당시 originalBit=0인 상세 화면에는 복원해도 그 OFF 기준을 유지한다고 설명한다. 기존 journal이 있으면 기존 originalBit를 그대로 사용한다. PC에 보존된 과거 journal을 자동 가져오거나 현재 비트를 ON으로 추측하지 않았다.

빈 journal에서 74 OFF 사용자 앱 + 2 ON 앱이 모두 기본 Picker에 남는 모델 검사를 추가했다. OFF 상태 새 등록/복원은 원래 값 0을 기록하고 setter 없이 지속성을 확인하는 기존 경로임도 검사한다.

## 4. 최초 홈 이전 자동 관리 추천

최초 pairing/읽기 전용 연결 검증 후 **5/5 자동 관리를 추천해요**를 표시한다. **자동 관리 사용 (권장)** / **지금은 사용하지 않기**를 직접 선택한다. 기본 OFF, 명시적 선택 전 시스템 권한 추가/ON/OFF는 없다.

설명은 사용할 때 무선 디버깅 ON, 정상 종료 후 OFF, 자기 앱의 시스템 설정 변경 권한, Wi-Fi/개발자 옵션은 직접 켜기, 다른 무선 연결 종료 가능, 나중에 설정에서 끄기를 포함한다. 선택하면 기존 typed own-WRITE_SECURE_SETTINGS 명령과 기존 WirelessWork 종료 검증을 재사용한다. 위치 권한 grant/revoke 또는 대상 metadata write는 하지 않는다. 실패는 안내하고 수동 연결/건너뛰기를 유지한다. 중복 선택과 종료된 session의 지연 작업은 차단한다.

새 설치/데이터 초기화 후 선택은 다시 표시된다. 기존 페어링된 사용자에게는 최초 안내를 반복하지 않는 migration을 적용한다. 자동 관리 동의나 실제 권한을 임의로 켜지 않는다. 한국어/영어, 큰 글씨, 좁은 화면의 scroll과 기존 공통 UI를 유지한다.

## 검증 상태

최종 자동 검사 **574개 모두 통과**: core 230 + localDebug 172 + localRelease 172, failure/error/skip 0. 디버그·배포 APK 빌드 완료, 양쪽 lint error 0 / warning 26. 이전 22개에 더해 새 SharedPreferences 호출의 KTX 문법 권장 4개가 있으며 검사 실패나 런타임 오류로 숨기지 않았다. 실제 UI 렌더러로 한국어 추천·영어 큰 글씨 추천·초기화 후 OFF 앱 Picker를 확인했다. Robolectric 화면/세션은 SDK34 시뮬레이터와 Android17 정책의 fake runtime이며 실제 Fold7 동작 검사와 구분한다.

기존 debug/release 서명 각각 유지, ZIP·arm64 ELF 16KiB 정렬과 license assets 검사 통과. 최종 APK SHA-256:

- 설치용 debug: `d945f4b54893a5195e5d6f0931e590ff85a446677b96446d52dcf7b108b014c1`
- release: `281e6b606f804b1af43a09c84b90ff44be3c6bfef95145a683ba2b5070889f44`

빌드·lint·서명·소스 보호의 상세 결과는 배포 폴더 `build-audit-1.0.0-test.13.json`, `safety-audit-1.0.0-test.13.json`에 기록한다. validated test.12 source ZIP을 baseline에 보존하고 실제 변경 diff를 검토했다. SafetyEngine, typed setter, helper, journal schema, crypto/keys, NSD/TLS, WirelessWork, 기존 Main 대상 루프/대량 진행, permission manifest는 byte-identical이다. Main에는 추천 단계의 종료 경고를 홈에 전달하는 문구 읽기만 추가했다. 19개 기존 파일의 변경을 허용 범위와 대조하고 나머지 baseline 파일 867개를 보존했다.

## 2026-10-06 Fold7 실기기 검증

test.12에서 test.13을 `install -r`로 업데이트했다. 사용자가 이미 데이터를 초기화해 등록은 0개였고, 업데이트 자체는 journal과 암호화된 페어링 파일의 해시를 보존했다. 키 내용은 읽거나 내보내지 않았다. 이후 새 사용자 전체 연결 검사를 위해 **사용자가 별도 승인한 bluedotOFF 자체 데이터 초기화 1회**를 진행했고 새 키로 다시 페어링했다. 다른 앱 데이터와 위치 설정은 바꾸지 않았다.

### 목록 복구와 안전성

- 실제 raw discovery 778개, 기본 목록 76개: 이미 표시가 없는 안전한 일반 앱 72개 + STRONG 후보 4개.
- 실제 화면의 전체 선택은 **74개 선택됨**이다. 나머지 2개인 모드 및 루틴·전화는 실제 UID peers가 여러 개인 공유 UID여서 기존 안전 규칙이 선택을 차단한다. 사전설치 여부만으로 차단한 결과가 아니다.
- 날씨날씨·미세미세·당근·카카오맵이 기본 화면에 실제로 보이고 **현재 표시 없음**으로 표시됐다. 네이버지도도 같은 읽기 전용 분류에 포함됐다. 초기화·새 페어링 후에도 같은 개수를 확인했다.
- 카카오맵·미세미세·당근·네이버지도 FINE `0x200` / COARSE `0x300`, 날씨날씨 FINE `0x80201` / COARSE `0x301`은 검증 전후 동일하다. 다섯 샘플의 UID/peers, 권한 grant, raw AppOps mode도 동일하다.
- 전체 선택/해제는 선택 개수 확인에만 사용했다. 추가/Hide/Restore는 누르지 않았으며 삭제된 originalBit를 추정하거나 PC의 journal을 가져오지 않았다.

### 최초 연결과 자동 관리

- 실제 초기화 후 알림 허용 창을 사용자가 허용했다. 무선 디버깅 이동 버튼은 **Settings로 나가기 전에** 실제 Android17 근처 기기 권한을 요청했고 사용자가 허용한 뒤 개발자 옵션으로 이동했다. Settings를 열기 전에 기존 shortService가 실행 중인 것도 확인했다.
- 사용자는 bluedotOFF로 돌아오지 않고 무선 디버깅 ON → 코드 창을 열어 **코드 입력 알림이 보인다**고 확인했다. 코드 창 닫기/재열기/입력 묶음 확인에는 **됐어**라고 답했다. 알림 덤프는 기본 redaction 상태로 ID/서비스 유무만 검사했으며 숨겨진 action text를 미표시의 증거로 해석하지 않았다.
- 처음에는 여러 확인 질문과 도구 검사를 나눠 진행하는 동안 준비 제한 시간이 끝나 **연결 준비가 잠시 중단됐어요**가 실제 표시됐다. 이 시도는 연결 성공으로 계산하지 않았다. 코드상 shortService는 150초이며 이 제한과 만료 안내를 유지했다. **이어서 연결**로 재개 후 이번에는 바로 새 코드를 입력·전송했고, 사용자에게서 **자동관리를 추천해요 뜨네**라는 확인을 받았다.
- 초기화 후 선택 전 동의와 managementChoiceResolved는 false였고 대상 setter·시스템 쓰기 로그는 0이었다. 추천 표시 뒤 실제 동의와 choiceResolved가 true가 됐으며 홈에 진입했다. 기존 요청에서 사용자가 추천 버튼 직접 선택을 안내받은 흐름이고 Codex가 해당 동의 버튼을 대신 누르지 않았다.
- own WRITE_SECURE_SETTINGS는 기존 grant가 초기화 후 유지돼 새 grant 명령 분기는 이번 기기에서 반복하지 않았다. 그 분기는 자동 검사와 이전 버전의 기기 검증에 포함돼 있으며 이번 실기기 결과와 구분한다.
- 업데이트 직후 조회에 ON/OFF 2회, 초기화·추천 선택·최종 조회 흐름에 OFF/ON/OFF 3회의 성공 기록을 확인했다. 최종 `adb_wifi_enabled=0`, 알림 700/701 없음, PairingLifetimeService 없음이다. 홈 진입 후에도 새 코드 없이 자체 키로 읽기 검사를 완료했다.

### 소프트웨어 정보 경로와 남은 범위

공개 DEVICE_INFO_SETTINGS Intent에 software_info 강조 key를 넣어 현재 Fold7의 실제 **소프트웨어 정보** 항목이 있는 화면에 도달했다. UI 트리의 selected/focused는 false였고 시각적 강조 효과를 보증하지 못했다. 개발자 옵션과 Wi-Fi가 이미 켜져 있어 앱의 해당 초기 단계는 건너뛰었다. 실제 앱 Toast와 삼성 강조 효과는 **미확인**이다. 공개 외부 경로로 실제 내부 소프트웨어 정보까지 바로 이동할 수 있다는 주장은 추가하지 않는다.

검증 도구가 non-exported Setup Activity를 shell에서 직접 여는 요청은 Android가 거부했다. 우회하지 않고 사용자가 정상적으로 앱에 돌아오는 경로로 나머지 검증을 마쳤다. 이 도구 실행 거부를 제품의 연결 실패로 섞지 않는다. 준비 화면은 FLAG_SECURE를 유지하므로 화면 녹화의 검은 영역 제한도 남는다.

이번 실기기 검증에서 **대상 위치 metadata write 0회**, Codex의 permission grant/revoke 0회다. 시스템 설정 쓰기는 충전 중 화면 유지 7 설정 1회와 기존/직접 선택한 무선 디버깅 자동 관리 5회다. 사용자의 Android 권한 허용과 무선 디버깅 ON 조작은 별도다. 새 Hide/Restore 실기기 write는 이 표시·연결 검증에서 반복하지 않았다. 해당 안전 루프는 test.12와 byte-identical이며 자동 회귀 검사는 통과했다.

최종 휴대폰 상태: **test.13 설치, 등록 0개, 새 페어링 완료, 자동 관리 ON, 무선 디버깅 OFF, USB 디버깅·개발자 옵션·Wi-Fi 유지, 충전 중 화면 유지 ON**. 앱 초기화로 없어진 이전 등록과 originalBit는 복구하지 않았다. 새로 등록할 때 현재 OFF였던 앱은 그 OFF가 복원 기준이 된다.

수집한 개인 기기 자료는 `.private`에만 보존했고 배포에는 개수/상태/검증 범위만 포함한다. device-validation JSON은 실기기 결과이며 safety-audit의 `staticAuditOnly=true / deviceTested=false`는 **그 정적 검사 자체**의 범위를 나타낸다. 전체 실기기 미검증을 의미하지 않는다. APK와 서명은 개발 완료본 그대로이고 보고서·소스 ZIP/배포 해시는 결과를 반영해 갱신한다.
