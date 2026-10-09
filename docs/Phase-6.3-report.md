# bluedotOFF Phase 6.3 — test.5

2026-10-04, Galaxy Z Fold7 SM-F966N / One UI 9.0 / Android 17 SDK 37 / F966NKSUCCZIH.

**결과: 안전한 narrow singleton 경로와 실제 카카오맵 ON/OFF/ON 검증 완료. 최종 debug test.5 설치 및 읽기 전용 회귀 확인 완료. 영구 OFF를 보장하는 결과는 아니다.** OS PermissionController의 sensitivity 재계산으로 0x100이 다시 ON이 되는 실제 사례도 발견했다. 이를 숨기지 않고 별도 재적용 상태와 제한으로 반영했다.

## test.4 baseline

첨부 요청의 ‘아직 설치되지 않았다’는 설명과 달리, 기기에 이미 설치된 test.4의 APK 해시가 보관된 test.4와 일치했다. 동일 APK를 그대로 사용해 코드 변경 전에 읽기 전용 baseline을 확인했다.

- APK SHA-256: `eddf3b325f6b1421887e428fad7b86133bc27fe55ea3c25982eae61bfd85682d`.
- NAVER FINE `0x200` 유지. 기존 journal의 미세미세·당근·우지커피·중고나라 4개 originalBit/identity/APPLIED 유지.
- 앱 기록에서 내부 서비스 제외, 전체 위치 사용에서 보존. 카카오맵·미세미세·날씨 일반 앱 기록 유지.
- baseline metadata write **0회**. 이후에만 production 코드 변경 시작.
- 증거: `research/phase63_20261004/baseline.json`, `00-test4-all.xml`, `01-test4-apps.xml`, `02-baseline-journal.json`.

## 정확한 이전 차단 원인과 정책 변경

test.4 source ZIP의 `BinderBackend.kt:160`은 `isCritical(pkg) || info.sharedUserId != null`을 critical 인자로 전달했다. 상세 `:208`에서도 `appId < 10000 || FLAG_PERSISTENT || info.sharedUserId != null || isCritical(packageName)`을 합쳤다. 카카오맵의 실제 값은 `false || false || true || false`였다. **유일한 차단 항은 `sharedUserId != null`이었다. FLAG_SYSTEM/UPDATED_SYSTEM_APP가 차단한 사례가 아니다.** 상세와 기록의 ‘핵심 OS 구성 요소’ 설명이 부정확했다. 이전 사실은 `KakaoMap-readonly-20261004.md`에 보존한다.

현재는 `core/Eligibility.kt`의 `UidOwnership` / `EligibilityPolicy`가 NORMAL, CORE_UID, OTHER_USER, SPECIAL_UID, PERSISTENT_CORE, CRITICAL_PACKAGE, MULTI_PACKAGE_SHARED_UID, SINGLETON_SHARED_USER, UNKNOWN_UID_OWNERSHIP를 분리한다. `Snapshot.systemCritical`은 기존 normal journal digest 호환을 위해 남지만 sharedUserId를 합성하지 않는다. 새 namespace는 journal에 별도 저장한다.

FLAG_SYSTEM, FLAG_UPDATED_SYSTEM_APP, ROM/priv-app 위치, 제조사/Google/통신사 출처는 표시 정보다. 어느 단독 항도 Hide rejection 조건이 아니다. 정확한 FINE grant, 정상 raw AppOps 0/4, 서명·설치 신원, user 0의 일반 appId, authoritative UID ownership 및 기존 firmware/ABI gate는 유지한다.

Legacy sharedUserId가 있어도 authoritative peers가 정확히 `[target]`이면 별도 singleton 상태다. 실증된 fingerprint/ABI asset이 없으면 **‘이 앱은 Legacy Shared UID를 사용합니다. 이 기기에서는 아직 안전성이 검증되지 않았습니다.’**로 차단한다. 최종 test.5의 지원 증거는 아래 현재 Fold7 fingerprint/ABI에 한정한다. 다른 firmware에 자동 확장하지 않는다.

peer 2개 이상은 계속 **‘이 앱은 다른 앱과 UID를 공유하므로 개별 적용할 수 없습니다.’**로 차단한다. core/persistent/critical/불명확 소유는 각각의 실제 사유를 표시한다. 기록 화면과 상세 화면이 같은 구조화된 사유를 사용한다.

## Shared-user 의미: 현재 firmware와 격리 더미

Android 공식 [manifest sharedUserId 문서](https://developer.android.com/guide/topics/manifest/manifest-element#uid)는 같은 ID와 서명에 의한 UID 공유를 설명한다. 현재 peer가 하나라는 사실은 shared-user namespace 자체가 없다는 의미가 아니다.

현재 `/system/framework/services.jar` SHA-256은 `29af5a05172b7c95e7f2a9efadc8d343daf5d51852a3644c7e2ce204da2709a8`이며 기존 정적 분석 파일과 일치했다. `PermissionService.java:1559` 및 `:1862`의 default-device getter/setter와 `AppIdPermissionPolicy.java:266`은 permission state가 **userId/appId/permission**에 속함을 보여준다. `PermissionService.java:715`는 요청 mask/value를 내부 flags로 변환한다. PackageManager에서 package의 appId를 얻어 이 state를 다루므로, packageName 지정 자체가 같은 UID의 다른 peer를 격리하지 않는다.

정상 targetSdk 35/minSdk 30 APK A/B를 동일 disposable test signer로 설치했다. platform signing, system 설치, bypass install flag, root를 사용하지 않았다. FINE/COARSE 권한은 사용자가 실제 Android 권한 창에서 정확한 위치·앱 사용 중 허용을 선택했다. assistant grant/revoke 명령은 **0회**다. 사용자가 허용했다고 답했어도 실제 getter가 false인 두 번의 관찰은 제외하고, true 확인 후에만 시험했다.

| 단계 | 실제 UID peers | A FINE | B FINE | COARSE | raw FINE/COARSE |
|---|---|---|---|---|---|
| A+B baseline, UID 10801 | A, B | 0x80301 | 0x80301 | 양쪽 0x301 | 양쪽 4/4 |
| A mask 0x100/value 0 | A, B | 0x80201 | **0x80201** | 그대로 | 그대로 |
| 저장한 originalBit 복원 | A, B | 0x80301 | 0x80301 | 그대로 | 그대로 |
| B 정상 제거 후 A baseline | A | 0x80301 | 제거됨 | 0x301 | 4/4 |
| A singleton OFF | A | 0x80201 | — | 그대로 | 그대로 |
| A singleton originalBit 복원 | A | 0x80301 | — | 그대로 | 그대로 |

예상대로 multi-peer에서는 한 package의 변경이 다른 peer getter에 전파됐다. 그러므로 production multi-peer gate를 열지 않았다. singleton에서는 다른 package가 현재 UID에 없고 다른 FINE bits, COARSE, grants, mode, UID, namespace, APK/설치 신원이 불변이었다. 더미 journal은 원본과 baseline을 fsync 후 기록했다. 완료 뒤 A도 제거했다.

더미 callback은 singleton OFF **이전**에 직접 관찰했다. OFF 후에는 위치 요청과 AppOps running이 유지됐다. 저장된 로그에 OFF 이후의 새 callback이 있다고 과장하지 않는다. 실제 앱 OFF 상태의 위치 기능은 아래 카카오맵 UI·새 AppOps 접근으로 별도 확인했다.

증거: `20-multi-*.json`, `21-singleton-*.json`, `dummy-writes.jsonl`, `dummy-result.json`, `services-static-hash.json`.

## 카카오맵 실제 값과 trial

| 항목 | trial fresh baseline / 최종 원복 |
|---|---|
| packageName | net.daum.android.map |
| UID / appId / user | 10502 / 10502 / 0 |
| authoritative UID peers 전체 | **[net.daum.android.map]** — 모든 readback 시 동일 |
| sharedUserId | daum.uid.shared |
| FLAG_SYSTEM / UPDATED_SYSTEM_APP / persistent / core | 모두 false |
| Launcher | com.kakao.map.main.view.IntroActivity |
| FINE / COARSE 선언·grant | 모두 true |
| FINE 전체 flags | **0x300**, USER_SENSITIVE_WHEN_GRANTED와 USER_SENSITIVE_WHEN_DENIED |
| FINE 0x100 / 0x200 | 모두 ON; OFF trial에서도 0x200 유지 |
| COARSE flags | 0x300 |
| raw FINE / COARSE mode | MODE_FOREGROUND **4 / 4**; package-level allow 표시와 구분 |
| version | 1.24.7 / versionCode 50229 |
| firstInstallTime / lastUpdateTime | 1753246722954 / 1753246722954 |
| signer SHA-256 | d90a5438321e50a1aeac40a1152d37f2599ec8fa418acaf426e295a9f262c68c |
| candidate | SINGLETON_SHARED_USER / SingletonSharedUserExperimental |
| History 기본보기 | 일반 app UID, launcher, 실제 FINE history, 내부 서비스 아님. OFF 관리 중에도 유지 |

처음에는 UNVERIFIED 빌드로 정확한 차단 이유를 실기기 기록 화면에서 확인했다(`32-unverified-kakao-history.xml`). 더미/정적 분석 후에는 debug APK 한 설치에만 UID·signer·firstInstallSeconds·firmware/ABI를 pin한 TRIAL_ONLY asset을 사용했다. release에서 TRIAL_ONLY는 항상 false다. 사용자 baseline 답변 **‘둘 다 보임 — 시험 진행’** 이후 실제 앱 SafetyEngine을 통한 Hide만 실행했다.

첫 Hide는 durable originalBit=256/shared namespace journal 후 mask=256/value=0 **1회**였다. FINE `0x300 → 0x200`, COARSE `0x300` 유지. 모든 stage에서 UID·peers·namespace·signer·version·install times·grants·raw modes·FINE의 다른 bits·COARSE/background flags가 불변이었다. 즉시 기준 실제 readback은 **0ms / 504ms / 1514ms**였다.

새 카카오맵 현재 위치 요청에서 FINE AppOps의 새 접근, GPS/MONITOR_LOCATION running과 정상 현재 위치 UI를 확인했다. SystemUI는 `isSystem=true`, PrivacyItemController의 위치 목록은 비었다. 사용자는 **‘둘 다 사라짐’**을 확인했다. 실제 앱 callback을 hook하지 않았으므로 UI/새 AppOps 기능 증거라고 명시한다.

### 발견한 OS 재분류와 실제 Restore 재시험

첫 Restore 직전 FINE이 이미 `0x300`이었다. 그 Restore는 setter **0회**의 settling 확인이었다. 이를 실제 restore write로 세지 않았다. 사용자도 **‘아무것도 조작하지 않았음’**이라고 확인했다.

같은 시점의 실제 OS 로그는 `10-04 16:53:54.005 PermissionControllerServiceImpl: Updating user sensitive for uid 10502`다. 현재 기기의 GooglePermissionController APK를 읽어 해시 대조하고 서비스/유틸리티 두 클래스만 오프라인 분석했다. `PermissionControllerServiceImpl.java:548-563`은 UID sensitivity 재계산을 실행한다. **`UserSensitiveFlagsUtils.java:198-200`은 계산한 값과 현재 flags&768을 비교하고, 다르면 OS가 mask=768(0x300)으로 sensitivity flags를 다시 설정한다.** 따라서 이 bit가 OS의 재계산 대상이라는 코드 경로와 해당 UID의 실제 실행 기록을 확인했다. 이 호출의 upstream trigger와 재발 주기 전체는 확정하지 않았다.

지원 여부는 안전한 narrow mutation/isolation 검증 범위에 한정한다. ‘OS가 이후 재계산하지 않는다’, ‘영구 OFF’로 승격하지 않는다. OS 재분류를 막는 설정, background 자동 재쓰기, broad rollback을 추가하지 않았다. 재조회 시 bit가 ON이면 **NEEDS_REAPPLY_POLICY / ‘표시 설정 변경 · 재적용 필요’**로 표시한다. app update나 cached APPLIED로 오인하지 않도록 테스트했다.

실제 Restore 경로 확인을 위해 같은 승인된 시험 범위에서 추가 Hide 후 fresh OFF를 확인하고 즉시 originalBit Restore를 실행했다. 이번에는 `before.fineFlags=0x200`에서 **mask 0x100/value 0x100 setter가 실제 실행**됐다. OFF와 실제 Restore의 settling은 각각 **0/508/1514ms**, 모든 불변값 PASS였다. 다시 위치를 요청하면 `isSystem=false`, 카카오맵 location PrivacyItem/표시 event가 돌아왔다. 사용자도 추가 실제 원복 뒤 **‘둘 다 다시 보임’**을 확인했다.

전체 ON/OFF/ON 화면 관찰, 첫 no-op Restore와 추가 actual Restore는 `kakao-human-observations.json`, `kakao-trial-result.json`, `61/63/67-*-events.jsonl`, `43/62/64/68-*-systemui-*.txt`에 구분해 기록했다. 지원 증거의 fingerprint는 `samsung/q7qksx/q7q:17/CP2A.260605.016/F966NKSUCCZIH_OKRCCZIH:user/release-keys`, ABI profile은 `046bc2fbf34bce569bf04f81b9548cfe8b49c1842783c46af5315aab5e4d28b8`이다.

## 사전설치 일반 앱 read-only trial

Google Maps `com.google.android.apps.maps`: user 0 / UID-appId **10293**, authoritative peers `[com.google.android.apps.maps]`, sharedUserId null, persistent/core false, **SYSTEM와 UPDATED_SYSTEM_APP 모두 true**, launcher 있음, FINE/COARSE declared/granted true, flags `0x300/0x300`, raw modes `4/4`. 실제 FINE allow history가 약 30일 전부터 존재했다. known signer `7ce83c1b71f3d572fed04c8d40c5cb10ff75e6d87d9df6fbd53f0468c2905053`, 실제 양수 firstInstallTime과 versionCode도 확인했다.

UidOwnership=NORMAL과 기존 normal gate를 통과하는 값이다. 최종 APK Picker에서 ‘지도 / 기본 앱 · 적용 전 안전성 확인’ 행과 활성 선택 가능 상태를 확인했다. 실제 적용이나 원복은 하지 않았다. **Maps metadata write 0회**. 증거: `preinstalled-result.json`, `73-final-preinstalled-picker.xml`.

## Race / lifecycle / journal 방어

- 최초 preflight, durable commit 후 preflight, setter 직전 추가 fresh snapshot, 즉시/500/1500ms readback에서 authoritative peers와 namespace를 대조한다.
- peer 변화는 exception/state로 누적한다. 후속 조회에서 다시 peer 1개로 돌아와도 관찰한 변화를 잊고 자동 rollback하지 않는다.
- SHARED_UID_CHANGED/NEEDS_REVIEW는 sticky다. foreground reconcile이 등록 singleton의 실제 peers를 다시 확인한다. boot/update marker와 refresh는 setter를 호출하지 않는다.
- peer 변화가 있으면 추가 batch 중지. old install의 originalBit를 새 peer/namespace/identity에 사용하지 않는다. 실제 peer 추가를 카카오맵에 만들지는 않았으며 해당 race/lifecycle 검증은 의미 있는 시뮬레이션 테스트다.
- 정상 stable ownership의 failure rollback만 같은 originalBit 경로를 사용한다. rollback 장애의 FAILED_ROLLBACK 차단을 유지한다. 실기기에 일부러 failure를 주입하지 않았다.
- journal schema 1 / 기존 normal digest / 기존 originalBit를 유지한다. 신규 sharedUserId만 optional field로 추가했다. 전체 permission flags는 restore 값으로 저장하지 않는다.

## 검증과 최종 결과물

core **121**, debug unit/Compose **69**, release unit/Compose **69**: **190개 구분되는 검사, 총 259회 실행 PASS**. 실제 renderer PNG 37개. 기존 정상/실패·boot/update·cached APPLIED·authoritative map·History/Picker/batch·ABI 검사를 포함한다. 신규로 system/updated flags, singleton/multi-peer, missing ownership/signer, peer race와 transient 변경, namespace, originalBit/rollback, legacy journal 호환, debug trial pin/release 차단, 구체적 rejection UI, inline action, OS sensitivity reset 상태를 검증했다.

Debug/release assemble PASS. Lint는 각각 **0 errors, 7 warnings**다: durable SharedPreferences commit 1건, dependency update 안내 5건, 기존 resource v26 구성 1건. 경고를 숨기지 않았다. static audit는 코드 pattern/merged manifest 검사이며 formal proof는 아니다. 성공 Snackbar/haptic, background setter, 접근성/overlay, grant/revoke/AppOps/COARSE/0x200 setter는 추가하지 않았다.

| 결과물 | SHA-256 |
|---|---|
| bluedotOFF-1.0.0-test.5.apk — **Fold7 최종 설치** | 024409cd6a0ce679699d29d640ed79fa52bfc835ccbdc1486d94c28f57868e51 |
| bluedotOFF-1.0.0-test.5-release.apk | 42285383295387f44e1d3904729bd1d546f36ac324bb31c0f9d36366d4ca82b1 |
| bluedotOFF-1.0.0-test.5-source.zip | 최종 delivery-audit-1.0.0-test.5.json에서 별도 확인 |

기존 debug signer `3b3b208f92d3d437acf30c333d9a7b5b0bc8f535056e18edd82df2701db2be0c`, release signer `619fe1ecbdf59d5d0b838ddc6420e58be90f828be11cb61fe6c8e6aa4ceaad62`를 유지한다. debug install -r로 데이터를 보존했다. source ZIP에는 local SDK 설정·signing secrets·keystore·cache를 제외하고 secret exclusion audit를 실행한다.

실제 mutation/physical ABA는 **별도 pinned trial APK**에서 수행했다. trial SHA는 `research/phase63_20261004/kakao-trial-result.json`에 기록했다. 최종 APK는 검증 asset 승격 및 OS-reset 안내 상태를 반영해 다시 빌드했고, 그 정확한 해시를 실기기 설치 후 읽기 전용으로 확인했다. 최종 APK에서 새로운 대상 metadata write는 0회다. release APK는 offline 검사/빌드만 했고 실제 기기 migration/ABA는 수행하지 않았다.

최종 설치 APK에서도 default History의 카카오맵 ‘Blue Dot 표시 대상 / 숨기기’, 기존 미세미세 APPLIED, 전체 위치 사용의 맞춤형 서비스·폰 서비스/core UID 사유, Google Maps Picker 행을 확인했다. 전체 services 목록을 지우지 않았다. 현재 카카오맵은 original ON으로 복원되어 숨김 적용 대상에 남겨 두지 않았다.

## 정확한 write 횟수와 최종 기기 상태

| 이번 Phase의 assistant/앱 FINE metadata setter | 횟수 |
|---|---:|
| 더미 multi-peer OFF + original Restore | 2 |
| 더미 singleton OFF + original Restore | 2 |
| 카카오맵 첫 Hide | 1 |
| 카카오맵 첫 Restore — 이미 ON, setter 없음 | 0 |
| 카카오맵 추가 Hide + actual original Restore | 2 |
| **합계 — 모두 FINE/mask 0x100** | **7** |
| baseline / Google Maps / 최종 설치 APK 읽기 전용 검사 | 0 |

이는 실제 setter 요청 로그에 근거한 assistant/앱의 횟수다. OS의 자체 permission/sensitivity 동작 및 사용자가 Android 권한 창에서 허용한 동작까지 assistant setter로 합산하지 않는다.

**추가 별도 설정 쓰기 1회:** 작업 중 사용자가 ‘충전 중 화면이 꺼지지 않게 하라’고 명시적으로 요청했으므로 `stay_on_while_plugged_in: 0 → 7`을 설정했다. 이 새 요청이 해당 설정에 한해 앞선 추가 system write 금지를 대체한다. 제품 코드에 넣지 않았고 FINE write 합계와 분리했다. 최종 `mIsPowered=true`, `mStayOn=true`, `mWakefulness=Awake`; 충전 중 화면 유지가 켜져 있다.

최종 상태: 카카오맵 FINE **0x300**, COARSE **0x300**, raw **4/4**, sole peer/동일 설치/서명 유지, journal enabled=false/NOT_APPLIED/originalBit256. 미세미세와 **NAVER FINE 0x200** 유지; 날씨 FINE **0x80301** 유지. 기존 미세미세·당근·우지커피·중고나라 네 journal entries의 original/install/state는 보존했고 모두 APPLIED다. NAVER를 신규 등록하거나 원복하지 않았다. 더미 A/B 제거 완료. Shizuku는 기존 ADB 모드로 유지하며 Phase 7 작업은 시작하지 않았다.

최종 기기 기록은 `bluedotOFF/artifacts/device-validation.json`, 최종 결과 감사는 `safety-audit.json`에 보존한다. 범위는 **검증된 현재 firmware의 안전한 좁은 singleton 제어**, OS 재분류 후의 영구 숨김 보장은 포함하지 않는다.
