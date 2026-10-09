# bluedotOFF Phase 6.1

2026-10-04 KST · Samsung SM-F966N / One UI 90000 / Android SDK 37 / F966NKSUCCZIH.

## 결론과 관찰 정정

**날씨날씨 숨김 실패라는 최초 관찰은 사용자가 철회했다. 실제로 보고 있던 앱은 미세미세였다.** 날씨날씨 새로고침에서는 표시가 없다고 다시 확인했다. 이 정정 전의 ‘날씨날씨 OFF인데 파란 점이 남음’ 답변은 실패 판정의 근거에서 제외한다.

독립적인 SystemUI 기록에서도 **10:53:25.883 미세미세 `cheehoon.ha.particulateforecaster`, UID 10474, isSystem=false**가 나타났고 해당 패키지의 location PrivacyItem, `location=true`, TopRight location-only dot ViewState가 생성됐다. 같은 구간 날씨날씨 UID 10078은 **isSystem=true**로 필터링됐다. 다른 패키지를 추측한 것이 아니라 실제 표시 항목에서 식별했다. 과거의 모든 짧은 표시 순간을 소급 계측한 것은 아니다.

Phase 4/5의 더미 앱과 이번 날씨날씨 모두 `FINE 0x80301 → 0x80201`, `isSystem=false → true`, 새 위치 접근 유지, target location PrivacyItem 제외라는 같은 경로를 따른다. 현재 firmware/app에서 기존 원리가 고장 났거나 자동 원복됐다는 증거는 없다. 철회된 관찰 때문에 날씨날씨를 unsupported로 차단하지 않았다.

사용자는 추가 비교 대상을 당근에서 **미세미세로 변경**했다. 당근은 읽기 전용 신원 확인만 했고 metadata를 변경하지 않았다. 미세미세의 기존 APK 시험에서 사용자가 실제 화면 **ON → OFF → ON**을 각각 확인했고 원복했다. 새 APK에서 재시험은 아래 검증표에 별도로 기록한다.

## 최초 false-positive 조사

날씨날씨: `com.lifeoverflow.app.weather`, user 0, UID **10078**, UID peer는 본인 하나, v4.14.0 / versionCode **735**, firstInstallTime **1753248272400**, lastUpdateTime **1790872818748**.

Signer SHA-256: `702e0a570173f2ecb9099caa7c59a704e834f526ca894a82bb83c542ebf3f227`.

| 검사 | 적용 전 / 원복 baseline | 기존 APK Hide 후 |
|---|---|---|
| FINE grant / COARSE grant | true / true | true / true |
| FINE flags | `0x80301` | **`0x80201`** |
| 즉시 / 500ms / 1500ms | — | `0x80201` / `0x80201` / `0x80201` |
| before XOR after | — | **`0x100`** |
| COARSE flags | `0x301` | `0x301` |
| FINE / COARSE raw AppOps mode | allow / allow | allow / allow |
| UID / peer / signer / version | 위 설치 | 동일 |
| 새 target FINE event | 10:32:57, isSystem=false | **10:34:44.118**, isSystem=true |
| target location PrivacyItem | 날씨날씨 UID 10078 | 제외됨, location=false |
| 사용자 실물 관찰 | 최초 색상 구분이 어려웠음 | 정정 후 **새로고침해도 안 보임** |

기존 APK에는 setter 진입 로그가 없었다. 버튼 동작과 좁은 flags 차이, 소스의 단일 setter 경로를 확인했으며, 이를 독립적인 Binder 호출 계측이라고 주장하지 않는다. 새 APK에서는 실제 transact 진입/반환과 readback을 기록한다.

**표시 착각과 별개로, 캐시 기반 성공 표시 문제는 실재했다.** 초기 조사에서 Shizuku server가 실제로 중지돼 있는데도 기존 journal의 APPLIED 두 행과 성공 테마가 남았다. 기존 `UiModel.allApplied`는 journal 상태만 봤으며 연결 및 fresh authoritative snapshot을 요구하지 않았다. read-only reconcile 또한 과거/중단 기록을 단일 OFF 조회만으로 성공으로 승격할 수 있었다.

무선 디버깅 토글과 Shizuku server 생존은 별개다. SDK의 `Shizuku.getUid()`는 캐시를 사용할 수 있으므로 새 빌드는 live `IShizukuService.getUid()`와 ping을 함께 확인한다. [공식 API 구현](https://github.com/RikkaApps/Shizuku-API/blob/master/api/src/main/java/rikka/shizuku/Shizuku.java), [공식 server AIDL](https://github.com/RikkaApps/Shizuku-API/blob/master/aidl/src/main/aidl/moe/shizuku/server/IShizukuService.aidl).

## 새 빌드의 판정과 UX

- APPLIED에는 현재 설치 identity/UID peer/grants/AppOps/비트 외 flags/firmware 검증과 fresh package read가 필요하다. 연결 종료·복귀 재검사 시작 시 fresh 표시 자격을 무효화한다.
- Hide와 Restore 모두 즉시·약 500ms·약 1500ms authoritative readback을 검사한다. 이미 원하는 bit인 no-op도 세 검사를 수행하되 setter는 호출하지 않는다.
- 비동기 원복·invariant 변화 시 성공 표시와 애니메이션을 금지하고 FAILED_SAFE/FAILED_ROLLBACK으로 처리한다. 원복 값은 전체 flags가 아닌 저장한 원본 **0x100 한 비트**다.
- 과거 빌드 journal은 `settlingVerified=false`로 읽으며 단일 OFF 조회로 APPLIED로 승격하지 않는다. 원본 비트를 보존한 채 명시적인 재검증을 기다린다. 중단된 Hide 역시 자동 성공으로 처리하지 않는다.
- 성공 상태는 실제 점을 상시 감시하는 기능이 아니다. 홈에 **등록한 앱에만 적용되며 다른 앱의 위치 접근으로 표시가 나타날 수 있음**을 안내한다.
- Shizuku 문구는 server 실행/중지와 일반 ADB 모드를 설명한다. 쓰기 전 live UID 2000, shell SELinux, API 13 및 공식 manager 서명을 검사한다.
- 최근 사용순은 FINE/COARSE **실제 access event timestamp**의 최댓값을 쓴다. reject 기록은 제외하며 기록 없음은 이름순으로 아래에 배치한다. history 형식/조회 실패는 목록 전체 이름순 fallback이다. Usage Access나 새 앱 권한을 추가하지 않았다.
- 선택 화면은 체크박스, 선택 수, 가로 아이콘/이름 요약, 검색, sticky ‘N개 추가’로 구성된다. 검색 및 Back 후 복귀에서 선택/검색어를 보존한다. 추가 버튼은 등록과 숨김 적용을 함께 수행한다고 명시한다.
- 성공/정보 snackbar와 닫기 버튼을 제거했다. 오류 snackbar만 자동 소멸하며 복구 오류는 상세 inline card에 남는다. 성공 햅틱 호출을 완전히 제거했고 시각 피드백만 유지한다.
- FLAG_SYSTEM/UPDATED_SYSTEM_APP만으로 차단하지 않는다. user 0의 독립 app UID와 기존 narrow invariants를 만족하는 사전설치 앱을 후보로 허용한다. 낮은/core UID, shared UID/여러 peer, persistent 구성 요소, 명시적인 핵심 OS·자기 앱·Shizuku, 불확실한 identity/권한/AppOps/firmware는 차단한다. 시스템 앱 전체 지원을 검증했다고 주장하지 않는다.

AppOps history는 현재 삼성 framework의 `getOpsForPackage` Proxy, transaction **20**, Parcelable writers **7개 method hash**를 별도 read-only profile로 검증한다. 기존 쓰기 profile key는 유지해 과거 원본 journal 복원을 막지 않는다. public Parcel/Parcelable API를 이용하며 hidden-API exemption, reflective private access, 원격 프로세스 실행을 추가하지 않았다.

## 미세미세 실기기 기록

`cheehoon.ha.particulateforecaster`, user 0, UID **10474**, peer 하나, v7.8.6 / versionCode **789**, firstInstallTime **1753246673325**, lastUpdateTime **1789144827888**.

Signer SHA-256: `dc19381e360c6c2b6995e6659b2bc15bfc8ea26c0978415ac8a65a76f2b4fc33`.

기존 APK 비교: FINE **`0x300 → 0x200 → 0x300`**, COARSE `0x300` 유지, grant FINE/COARSE/background true 유지, raw AppOps allow/allow 유지. 사용자 응답: **‘파란 표시가 보임’ → ‘둘 다 사라짐’ → ‘다시 나타남’**. 11:01:13.986 새 event는 isSystem=true와 빈 location item; 11:03:41.341 원복 후 isSystem=false와 미세미세 location item.

새 APK batch는 미세미세·날씨날씨·기존 네이버지도 세 항목을 처리했다. **실제 write는 미세미세 한 건**, 이미 OFF인 두 항목은 no-op 검증이었다. 네이버지도 현재 표시 bit를 바꾸지 않았다.

| 새 APK Hide trace | FINE | COARSE | ping / live remote UID | setter 이후 경과 |
|---|---|---|---|---|
| before / preflight | `0x300` | `0x300` | true / 2000 | — |
| setter_request / enter / return | mask `0x100`, value `0` | 쓰지 않음 | 일반 shell gate | transaction 8 |
| immediate | `0x200` | `0x300` | true / 2000 | **4ms** |
| settled_500ms | `0x200` | `0x300` | true / 2000 | **510ms** |
| settled_1500ms | `0x200` | `0x300` | true / 2000 | **1517ms** |

세 snapshot의 설치 identity, peer, signer, version, grants, background flags, AppOps 및 FINE 비트 외 flags는 동일하다. XOR는 `0x100`. 11:18:45.989 새 미세미세 AppOp 1은 **isSystem=true**, 처리 location item 없음, `location=false/chip=false`였다. grant와 실제 AppOps 접근 기록은 유지됐다.

## 검증표

| 항목 | 결과 |
|---|---|
| A 최근 위치 사용순 | **PASS**; Fold7 live AppOps historyAvailable=true, 실제 timestamp 조회, 최근순 화면 |
| B 검색·다중 선택·N개 추가 | **PASS**; package 검색 3회, 선택 수 1→3, 검색 및 Back/재진입 보존, 한 번에 처리 |
| C 성공 snackbar 없음 | **PASS**; batch 최종 홈에 성공 배너/닫기 없음 |
| D notification 성공 snackbar 없음 | **PASS, 이미 허용된 경로**; 기기 POST_NOTIFICATIONS=true, 알림 켜짐, 허용 버튼/반복 안내/성공 snackbar 없음. 최초 허용 dialog는 이번에 다시 만들지 않았고 callback의 성공 문구 제거는 source review |
| E success haptic 없음 | **PASS**; main source에 HapticFeedback 호출 없음. 진동 서비스 기록의 bluedotOFF 요청은 구 APK 시각뿐이며 새 APK 적용 이후 요청 없음 |
| F 대상 실제 Hide | **PASS**; 사용자가 변경한 비교 대상 미세미세에 새 APK transact 및 XOR 검증 |
| G 실제 Blue Dot OFF | **PASS**; 새 APK 적용 후 사용자 ‘둘 다 안 보임’ |
| H fresh readback | **PASS**; 즉시/510ms/1517ms 및 새 위치 event 후 OFF |
| I Restore | **PASS**; 새 APK FINE `0x200 → 0x300`, 즉시/500/1500ms 및 grant/AppOps 불변 검증 |
| J 실제 Blue Dot ON | **PASS**; 새 APK 원복 후 사용자 ‘다시 나타남’, 미세미세 isSystem=false와 location item 복귀 |

PC 검증: **73 tests PASS**, lint **0 errors / 7 warnings**, merge manifest/static source audit PASS. 39 safety, 5 DEX boundary, 8 journal, 3 notification, 18 Compose render tests. 비동기 원복, 1500ms invariant drift, no-op settling, legacy/cached journal, 설치 바뀜/서명/UID, 좁은 rollback을 포함한다. 오류를 고치기 전 컴파일 실패 한 번은 `getSELinuxContext()` 명시 호출로 수정했다.

UI 연락 시트와 실기기 다중 선택 PNG를 직접 확인했다. dark/outer/large device font와 desktop rendered fold wide 화면을 확인했으며 이번 Phase 6.1의 실제 펼친 화면을 새로 검증한 것은 아니다. 색상·스크린샷의 파란 픽셀 부재만으로 점 OFF를 판정하지 않는다. 앞선 Phase 4에서 표시 레이어가 ADB screencap에서 빠지는 현상이 있었고 이번에도 실제 사용자 관찰을 따로 받는다.

## 범위와 마무리

APK **1.0.0-test.2 / versionCode 2**, SHA-256 **`d6eeb82283f159f0a0a703013f36c5b773c4e850b25bb629f729595575883954`**. debug signing, compile/target SDK 35, runtime gate SDK 37은 유지한다. Android 17 SDK 이행 및 release signing은 후속 과제다.

Root/Knox/bootloader/system/framework/SystemUI 수정, AppOps setter, target grant/revoke, 위치 설정 변경, 실제 앱 강제 종료는 수행하지 않았다. 실험 중 target 시스템 쓰기는 현재 설치의 FINE 0x100뿐이다. 정상 앱 실행/새로고침, 공식 Shizuku shell starter, 자체 APK 업데이트와 private journal/진단 로그는 사용했다. 앱 백그라운드 watchdog이나 서비스는 없다.

**사용자의 별도 명시 요청으로 USB 연결 중 화면 꺼짐 방지를 설정했다.** `stay_on_while_plugged_in: 0 → 2`로 USB 충전 시에만 화면을 유지한다. 이는 APK 기능이 아니라 이번 세션에서 요청한 설정이며 유지한다. 다른 개발자 옵션·무선 디버깅 토글·화면 timeout은 변경하지 않았다.

**최종 원복 완료:** 미세미세 FINE **`0x300`**, COARSE `0x300`, 날씨날씨 FINE **`0x80301`**, COARSE `0x301`. 두 항목은 journal에서 NOT_APPLIED/enabled=false, originalBit=256을 보존한다. 네이버지도 FINE **`0x200`**은 이번 실험 시작 때의 OFF 상태 그대로이며 이번 Phase 6.1에서는 setter를 호출하지 않았다.

미세미세 원복 후 **11:25:31.038** 새 AppOp 1은 isSystem=false, 처리 항목에 미세미세 UID 10474가 다시 들어왔고 location=true/chip=true였다. 사용자도 실제 표시 복귀를 확인했다. 새 APK source 및 실제 설치 base.apk SHA-256은 위 전달용 APK 해시와 일치한다. own PID 10226 AndroidRuntime:E 기록은 비어 있다.

새 APK 기록의 setter는 **3건**(미세미세 Hide·Restore, 날씨날씨 Restore), 모두 FINE/mask256/value0또는256/user0다. 구 APK 비교에서 날씨날씨 Restore·Hide와 미세미세 Hide·Restore 4건을 별도로 실행했다. 이 횟수는 해당 버튼/flags 관찰 및 앱 자체 trace의 범위이며 OS 전체의 독립적인 syscall 감사가 아니다. 동일 설치에 저장한 originalBit 외 값을 복원하지 않았다.

최종 알림은 이미 허용돼 있었고 정상 설정 화면에서 성공 메시지와 반복 권한 안내가 없었다. grant/revoke로 이를 다시 만들지 않았다. bounded vibrator dump의 구 APK 성공 요청은 ‘ignored_for_settings’였으므로 과거에 실제 진동이 재생됐다고 주장하지 않는다. 새 APK에서는 요청 호출 자체가 없다.

추가 final evidence: `58-v2-mise-restore-ready`, `59–60-v2-restored`, `61-v2-final-mise`, `62-final-weather`, `62-final-validation-events`, `62-vibrator-history`, `63-v2-settings`, `64-final-own-package`. 전달용 감사 JSON은 `bluedotOFF/artifacts/device-validation.json`이며 구 Phase 6 기록은 `device-validation-phase6.json`으로 보존했다.

원본 evidence: `research/phase61_20261004/`의 `19-old-hide-timeline`, `20-old-hide-event`, `28-live-display-*`, `35-mise-baseline`, `42-mise-hide`, `44-mise-hidden`, `46-mise-restore`, `47-mise-restored`, `51-v2-final-home`, `52–54-v2-selection`, `56-v2-batch-complete`, `56-v2-validation-events`, `57-v2-mise-hidden`. 전체 설치 목록/개인 앱 화면이 포함된 raw research 자료는 전달용 source ZIP에 포함하지 않는다.
