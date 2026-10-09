# bluedotOFF Phase 7.2 — test.9

2026-10-05 · Galaxy Z Fold7 SM-F966N · One UI9 / Android17 / KR / user0

test.9의 기본 앱 선택 목록을 **검증된 위치 표시 후보**로 정리하고, 고급 목록과 현재 단계 그림 안내, 무선 디버깅 설정 바로가기를 추가했다. Fold7에 debug APK를 업데이트 설치하고 실제 읽기 전용 수락 검사를 마쳤다. 위치 권한 metadata write는 **0회**다. 별도 승인을 받은 PC shell의 무선 디버깅 OFF만 **1회** 실행했다. 최종 무선 디버깅은 OFF, USB 디버깅·개발자 옵션은 ON, 충전 중 화면 유지 값은7이다.

자동 OFF의 OS 제어 가능성은 확인했지만 **앱 자동 OFF는 출시하지 않았다**. 요청서40절의 helper/backend 변경 금지를 유지하며31절의 Settings fallback을 제공한다. 진행 중인 앱 작업에서 자동 OFF와 마지막 응답의 순서를 보장하는 실험까지 완료했다고 주장하지 않는다.

## 연구 순서와 근거

`Indicator-Eligibility-Research.md`를 Picker 구현 전에 완성했다. 연구 완료 seal의 UTC 시각은 `2026-10-05T09:56:00.165569+00:00`, 당시 보고서 SHA256은 `15894de405b4b574045431a0d034b7c988b83c77b0cea640d0720de3cc14c634`이다. 초기 보고서를 private 증거에 그대로 보존하고, 최종 보고서에는 구현 후 검증 결과를 별도 addendum으로 추가했다.

Android17 공식 설명, Android17 AOSP release tag와 pinned commit의8개 원문, Samsung 공개 지원 문서, GitHub/community 사례를 검색·대조했다. Samsung 공개 문서만으로 One UI9의 정확한식을 확인할 수 없어서 현재 Fold7의 SystemUI/framework/services 해시와 실제 코드를 기준으로 삼았다. 조사 범위·URL·소스 revision·15개 앱 비교표·불확실성은 연구 보고서에 있다.

현재 firmware: `samsung/q7qksx/q7q:17/CP2A.260605.016/F966NKSUCCZIH_OKRCCZIH:user/release-keys`.

| 파일 | 현재 기기 SHA256 |
|---|---|
| SystemUI.apk | `6c463b842b511ebb8277cc5d3e332a1679e13fb944e45a51757767a03499301b` |
| framework.jar | `4b75a3b3dcea2deebaa403d6021da9e9bf9c9f9336a6956a69594884472086eb` |
| services.jar | `29af5a05172b7c95e7f2a9efadc8d343daf5d51852a3644c7e2ce204da2709a8` |

## 실제 predicate와 Picker 구현

Samsung `AppOpsPrivacyItemMonitor`의 위치 감시는 FINE `op1`이다. `isSystemApp`은 preflight granted 분기에서 FINE flags의 `0x100`, denied 분기에서 `0x200`을 검사한다. 해당 비트가 없으면 logical system usage로 분류한다. 이 논리의 system은 `FLAG_SYSTEM`과 같은 뜻이 아니다. 현재 KR 설정의 showSystem=false에서는 이런 사용이 위치 PrivacyItem에서 필터링된다. Samsung 코드의 background 처리는 해당 위치 return에서 AOSP17과 다르며, 보고서에 구분했다.

`PermissionManager.shouldShowPackageForIndicatorCached`는 플랫폼의 역할 기반 예외 집합을 확인하는 또 하나의 입력이며 전체 위치 표시 필터가 아니다. 실제 bound 앱 Context에서 읽힌 제외 집합은 `android`, `com.google.android.as`, `com.samsung.android.smartface`다. 여섯 framework 역할 리소스에서 계산하며 역할 package 값을 새 blacklist로 박아 넣지 않았다. 임시 unbound app_process Context가 반환한 빈 리소스는 올바른 기준으로 쓰지 않았다.

`core/IndicatorEligibility.kt`의 `IndicatorClassifier.logicalSystem/classify`가 표시 후보 판정을, `PickerPolicy.list`가 기본/고급 표시 정책을 담당한다. `IndicatorPlatform.kt`는 현재 fingerprint와 SystemUI 해시, live framework 리소스를 검증한다. 실패하면 UNKNOWN이다. `BinderBackend.discover`의 추가 단계는 기존 typed PackageInfo·permission flags·raw mode getter만 사용한다.

기본 목록은 **STRONG_CANDIDATE + 기존 lightweight UID 소유 검사 통과**, 또는 기존 등록 대상이다. launcher와 SYSTEM/UPDATED_SYSTEM_APP는 후보 판정 기준으로 사용하지 않는다. 등록 여부는 저장된 ownership을 유지하기 위한 표시 예외이며 live indicator 분류를 바꾸지 않는다. 전체 필터를 초기값으로 삼아 최근 사용 기록이 없는 정상 후보도 찾을 수 있게 했다.

모델은 현재 검증된 firmware의 일반적인 단일 앱 FINE 요청에 대한 **잠재 후보**다. grant와 raw mode0/4로 preflight granted 분기를 재현한 결과를 실제 preflight getter와 로그로 대조했다. 여러 attribution chain, concurrent apps, event hold와 모든 순간의 화면 표시가 완전히 동일하다고 보증하지 않는다. 불명확한 mode/조회 실패는 UNKNOWN 또는 권한 상태 확인 대상으로 고급 목록에 남긴다. 후보 표시와 적용 직전 SafetyEngine은 독립적이다.

History의 사용자 앱/전체 위치 사용 정책은 바꾸지 않았다. 이번 변경은 Picker이며, raw positive AppOps의 의미를 실제 파란 점과 동일하다고 바꾸어 설명하지 않는다.

## Fold7의 실제 목록

778개 설치 항목을 bound Context로 읽었다. STRONG_CANDIDATE76, EXEMPT_SYSTEM_USAGE54, EXEMPT_PACKAGE3, INELIGIBLE_OP566, INELIGIBLE_PERMISSION_STATE78, UNKNOWN1이다. 기존 UID 검사에서 강한 후보2개가 제외되고 등록 대상1개가 유지되어 기본 후보는75개다.

| 앱 | 실제 핵심 값과 기본 결과 |
|---|---|
| 카카오맵 | UID10502, sole peer1, singleton `daum.uid.shared`, FINE0x300/raw4 → 포함. 실제 검색 화면 확인 |
| Google Maps | UID10293, peer1, SYSTEM+UPDATED_SYSTEM_APP, FINE0x300/raw4 → 포함. 실제 검색 화면 확인 |
| 삼성 카메라 | UID10126, peer1, SYSTEM, FINE0x300/raw4 → 포함 |
| 삼성 날씨 | UID10232, peer1, SYSTEM, FINE0x80301/raw0 → 포함 |
| 당근·토스 | FINE0x300/raw4, 독립 application UID → 포함 |
| Samsung Location SDK | UID5013, FINE0x30/raw0 → logical system usage, 기본 제외. 특수 UID 안전성도 별도 차단 |
| Continuity Service | UID10104, FINE0x20/raw0 → logical system usage, 기본 제외. 고급 목록에 실제 표시 확인 |
| 맞춤형 서비스 | UID10114, FINE0x20/raw0 → logical system usage, 기본 제외 |
| Google Play services | UID10273, FINE0x30/raw0 → 현재 내부 위치 사용 기준 기본 제외. package 이름 자체로 차단하지 않음 |
| Android 시스템 | UID1000, 역할 예외 + core UID → 기본 제외 |
| 전화 사용자 앱 | UID10129, FINE0x300/raw4 → 표시 후보지만 authoritative peer2이므로 기존 소유 안전성 검사로 기본 제외 |
| 미세미세 | 시작부터 FINE0x200/raw0, 현재 미등록 → 표시 비트 OFF에 따른 기본 제외, 고급에서 접근 가능. 권한/플래그를 정규화하지 않음 |
| 날씨날씨 | 시작부터 등록1개, FINE0x80201/raw0 → logical system usage라도 관리 대상 유지, originalBit256 보존 |

UNKNOWN1개는 Trichrome static shared-library alias다. 일반 full PackageInfo 조회가 해당 alias를 해결하지 못해 보수적으로 UNKNOWN이 됐다. 적용 직전에도 기존 fresh 조회가 필수이며 이 실패를 UID 소유 허용으로 바꾸지 않는다.

카카오맵을 실제 실행한 새 자연 이벤트에서는 SystemUI FINE op1, `isSystem=false`, location PrivacyItem을 확인했고 사용자가 파란 점과 위치 아이콘이 둘 다 보인다고 확인했다. 이번에는 Hide/Restore를 실행하지 않았다. Maps·Camera 등 다른 앱의 현재 물리 표시까지 확인했다는 뜻은 아니다. 기존 단계의 ON/OFF/ON 증거는 연구 표에서 과거 확인으로 명시했다.

## 무선 디버깅 OFF 시험과 제한

정확한 제어는 Global `adb_wifi_enabled` → SettingsProvider → Samsung AdbService observer → Wi-Fi ADB transport다. shell UID2000의 WRITE_SECURE_SETTINGS grant를 읽었다. 앱의 고정 helper에는 Settings write가 없으며 이번에도 추가하지 않았다.

별도 사용자 승인 후 PC USB shell에서 `settings put global adb_wifi_enabled 0`을 **한 번만** 실행했다. 먼저15개 앱 전체 readback·journal checksum과 원래 비트·마지막 helper batch completed/closed·helper process 부재를 확인했다. journal의 기존 fsync/atomic rename/정확한 재조회 코드는 baseline과 같다. 시험 전후 journal byte digest도 동일했다.

Global 값은1→0, AdbService의 정확한 `adb_wifi` section은 `enabled=false`, `tls_port=0`이었다. `persist.adb.tls_server.enable`와 TLS port property는 shell 조회에서 처음부터 빈 값이었다. 최초 trial audit는 이 추가 assertion 때문에 investigate로 남겼고, 쓰기를 반복하지 않은 별도 getter 후속 증거로 서비스 OFF를 확정했다. 광범위한 진단 필터는 불필요한 public key/Wi-Fi 필드를 출력할 수 있어 후속 검사에서는 해당 section의 두 숫자/boolean만 보존했다. 제품 assets/source ZIP에는 이런 device dump가 없다.

USB ADB1, developer options1, boot55, stay-awake7을 유지했다. 앱 재진입은 연결 필요 안내로 바뀌었고, 등록 앱은 남으면서 cached APPLIED를 현재 확인으로 표시하지 않았다. 저장된 페어링 키 재연결은 새 코드 없이 이미 동작했으며, 이번 설치 후 처음 측정한 암호문 해시와 OFF 전후 해시는 같았다. pre-upgrade 암호문 해시를 따로 측정하지 않아 업데이트 전후 byte 동일성까지 주장하지 않는다.

모든 helper/socket batch는 OFF 전에 이미 종료됐으므로 **진행 중인 앱 소켓의 자동 OFF 응답/종료 순서를 실측한 시험은 아니다**. OS shell의 제어 가능성과 앱 오프라인 결과 보존을 확인한 범위다. 앱 자동 OFF의 success/failure/settling 순서 검사를 완료했다는 허위 테스트를 추가하지 않았다. 프로토콜 확장을 별도로 허용하고 검증할 때까지 자동 OFF 옵션은 없다. Settings 버튼은 busy일 때 비활성화하며 BlueDot 결과 상태를 바꾸지 않는다.

Fold7에서 direct wireless Settings intent를 resolve할 수 없어 DeveloperOptions fallback을 사용했다. `toggle_adb_wireless` 강조 인자를 전달하며 실제 무선 디버깅 행이 보이는 곳으로 열렸다. 강조 효과 자체를 육안 검증했다고 주장하지 않는다. 사용자가 OS 스위치를 직접 끄는 동작은 유지한다.

## 그림 안내

실제 개인 화면 대신 One UI 구조를 단순화한 native Canvas 그림을 사용했다. 앱 asset `guide/steps.json` 하나에서 현재 단계 그림과 도움말1→4 문구를 읽고, 같은 내용으로 README용 SVG5개를 생성한다. 수동/알림 거절 분기 그림도 포함한다. 실제 코드·IP·기기명·SSID·개인 알림은 없다. 코드 칸은 여섯 bullet이다.

실제 Fold7 Help에서 네 단계 그림/접근성 설명을 읽었다. 이미 연결된 Setup은 완료 단계를 자동 생략하고 Home으로 복귀했다. 승인된 무선 OFF 후 Setup에는 현재 필요한 **2단계 그림 한 장만** 나타났다. Light/dark/Fold wide/large-font manual 렌더를 검사했다. 최초 설치의 알림 거절과 Developer Options OFF 전체 novice walkthrough를 실기기로 재연하지는 않았다. 해당 분기와 자동 skip은 core/UI 검사로 검증했고 기존 pairing/session state machine은 변경하지 않았다.

## 수락 검사 A–T

| 항목 | 결과 |
|---|---|
| A–C 연구·현재 firmware·외부 근거 | PASS, 구현 전 보고서/seal 및 pinned source 비교 |
| D 기본 Picker | PASS, 실제75개 |
| E–H 내부 서비스 처리 | PASS, 위5개 기본 제외, live flags/roles의 이유 명시 |
| I–J 카카오맵·Google Maps | PASS, 실제 검색 목록에서 포함 |
| K 사용자 앱 유지 | PASS, 당근·토스 등 포함. 이미 OFF인 미세미세는 고급, 관리 중 날씨날씨는 기본 유지 |
| L 고급 보기 | PASS, Continuity 실제 기본 제외/고급 포함, unknown 보수적 경로 |
| M 의도하지 않은 metadata write | PASS0,15개 grant/flags/raw modes/UID peers/install identity 그대로 |
| N OFF 제어 경로 | PASS, SettingsProvider/Samsung observer와 shell grant |
| O 승인 시험 | PASS, PC shell OS OFF1회. 앱 자동 OFF 시험과 구분 |
| P 연결 종료 후 결과 보존 | PASS, 기존 등록·원래 비트·journal 동일, offline UI fresh 확정 없음 |
| Q Settings fallback | PASS, Developer Options의 wireless 행 표시 |
| R–S 이미지·초보자 안내 | PASS, synthetic5개/shared spec, 네 단계 Help, theme/layout QA |
| T 현재 단계만 표시 | PASS, 실제 OFF step2 한 장 + 이미 연결된 flow skip |

## 회귀 검사와 산출물

core210, Android debug113/release113 = **총436회 실행**, 고유323개, failure/error/skipped0. Debug/release assemble 및 lint 통과, 각 lint0 errors/26 warnings. 버전 code9/name1.0.0-test.9, compile/target37이다. 추가 권한·Service·overlay·Accessibility·Shizuku·root 의존성은 없다. 기존 debug/release signer와16KB ELF/ZIP alignment를 유지했다.

test.8 동결 파일11개와 보호 소스14개가 byte-identical이다. SafetyEngine·Journal·LocalAdbBackend·MinimalHelper·SingletonSupport·typed narrow setter·settling·reapply·pairing session/native 코드를 유지했다. BinderBackend 추가는 표시용 getter이며 private getter/setter tail, Main mutation pipeline과 History/UID 정책은 baseline 그대로라는 별도 감사를 통과했다.

Debug SHA256 `4343af4a987e91a4e8290ad5a728b9e162c18f0e25e14a2937e07c5ed491bc3a` (28,382,472 bytes). 설치 APK hash와 일치한다. Release SHA256 `6f969a1a09c1b181e67bcbe961d0977570d9648ab72a935ddb0b2f84b98f0f7a` (20,126,127 bytes). 기존 사용자 데이터를 유지하기 위해 release로 전환 설치하지 않았다.

제공 파일은 `artifacts/bluedotOFF-1.0.0-test.9.apk`, `-release.apk`, `-source.zip`과 build/safety/device/source/delivery audit JSON이다. Source ZIP은 wrapper/native vendor/license/두 보고서와 reusable guide를 포함하며 device dumps·journal·keys·code·cache·signing secrets를 제외한다. ZIP의 실제 SHA256은 순환 참조를 피하기 위해 ZIP 내부 보고서가 아닌 delivery/source audit에 기록한다.

Private 실측 증거는 `research/phase72_20261005`에 research-before / final-before-off / final-after-off / actual picker / filtered UI / approved OFF / follow-up getter로 구분했다. 최종 device audit는 확인 범위와 미실측 항목을 함께 기록한다.
