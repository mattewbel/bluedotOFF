# bluedotOFF Phase 6.2

작성일: 2026-10-04 (Asia/Seoul). 버전: **1.0.0-test.3 / versionCode 3**.

**상태: 최종 동일 debug signer test.3을 Fold7에 설치해 핵심 실기기 regression을 통과했다. 기록 조회·inline Hide·실물 OFF/원복 ON·후보/UID 차단·다중 추가/원복·성능 계측을 확인했다.** 사용자 육안 답변은 OFF `둘 다 사라짐`, 원복 ON `둘 다 다시 나타남`이다. 두 실험 앱은 시작 flags로 복원했고 기존 네이버지도 OFF는 유지했다. FINE 0x100 write만 총 4회이며 다른 설정 변경·삭제·데이터 초기화·release migration은 하지 않았다. **target update/missed-update/reboot를 이번 APK로 새로 발생시키는 lifecycle 실측은 하지 않았고 N은 PARTIAL이다.** 따라서 모든 lifecycle까지 무조건 PASS라고 주장하지 않는다.

## 산출물과 서명

| 산출물 | 용도 | SHA-256 |
|---|---|---|
| `bluedotOFF/artifacts/bluedotOFF-1.0.0-test.3.apk` | 기존 debug test.2 위에 데이터 보존 업데이트 가능한 실기기 검증용 | c5c595f56e4032a433a233a6f94872347db3f98a42caa164386003018b38d826 |
| `bluedotOFF/artifacts/bluedotOFF-1.0.0-test.3-release.apk` | 별도 지속 서명, debuggable=false 테스트 배포용 | 33245434bc5819b119483f7ef8ab15a0f90fe0608200c47566f3e896f2740de6 |
| `bluedotOFF/artifacts/bluedotOFF-1.0.0-test.3-source.zip` | 빌드 가능한 소스·테스트·문서. SDK 경로·키·암호·캐시 제외 | 패키징 후 별도 audit |

debug certificate SHA-256: `3b3b208f92d3d437acf30c333d9a7b5b0bc8f535056e18edd82df2701db2be0c`. 실제 test.2와 일치함을 apksigner로 확인했다.

release certificate SHA-256: `619fe1ecbdf59d5d0b838ddc6420e58be90f828be11cb61fe6c8e6aa4ceaad62`. 공개 인증서 해시이며 private key나 암호가 아니다. apksigner verify로 실제 APK의 서명을 확인한다.

release key는 소스 트리 밖 workspace `.private/`의 PKCS12로 한 번 생성했다. RSA 3072, 로컬 난수 암호, env 기반 keytool 입력을 사용했고 실제 암호를 명령 인수·채팅·로그·Gradle 코드에 출력하지 않았다. 암호는 gitignored 로컬 properties에서 읽는다. key와 properties 두 파일을 사용자가 안전하게 별도 백업해야 다음 release 업데이트가 가능하다. `prepare_signing.py`는 기존 키를 덮어쓰지 않는다. 소스 ZIP에는 비밀 자료가 들어가지 않는다.

**debug→release 전환은 아직 하지 않았다.** 다른 서명이므로 test.2 위에 release를 바로 업데이트 설치할 수 없다. 실제 전환은 현재 journal·target 상태 확인 → 현재 설치의 originalBit로 안전한 Restore → 필요한 사용자 데이터 처리 → 사용자와 결정한 제거/새 설치 순서가 필요하다. 자동 uninstall·데이터 초기화·journal의 다른 설치로 강제 이식 기능을 추가하지 않았다. 실기기 검증은 우선 동일 debug signer의 test.3으로 진행할 수 있다.

## BlueDot History

하단 `홈 | 기록 | 설정`을 추가했다. 기록 화면은 실제 AppOps 접근 시각 최신순의 간결한 행 목록이다. AppOps의 FINE(op 1)·COARSE(op 0) 접근 배열에서 실제 양수 timestamp만 가져와 병합한다. reject 배열은 읽어 형식을 검증하되 최근 실제 사용 계산에서 제외한다. 동률은 이름·packageName으로 결정적으로 정렬하며 임의 timestamp를 만들지 않는다.

기록을 읽기 전에 기존 Phase 6.1의 firmware-specific getOpsForPackage Proxy/dispatch 및 AppOps Parcelable writer 해시를 검증한다. Binder descriptor, shell UID, 응답 크기·package/UID 일치·op 중복·map/event 개수·남는 데이터도 확인한다. history ABI는 기존 쓰기 profile에서 분리되어 있으며 실패하면 사용 기록을 사용할 수 없다고 표시하고 Picker는 이름순 fallback을 사용한다. 기록 실패가 setter를 호출하지 않는다.

기본 문구는 `방금 전 위치를 사용했습니다`, `2분 전` 등 실제 사용 시각이다. FINE/COARSE 종류를 표시한다. foreground/background, 현재 active 상태, GPS 좌표, 화면에 방금 표시를 띄운 유일 원인은 이 데이터로 단정하지 않는다. 조건부 `Blue Dot 표시 대상`은 live connection + fresh full snapshot + safe target + FINE history + 현재 bit ON일 때만 가능하다. 아직 full safety를 읽지 않은 대상은 `적용 전 지원 여부 확인`이라고 표시한다.

등록된 숨김 앱은 fresh APPLIED + settlingVerified가 있어야 `Blue Dot 숨김 적용됨 ✓`이다. 연결이 없거나 캐시만 있으면 현재 상태 확인 필요다. 업데이트 대상은 재적용 필요와 `[재적용]`을 표시한다. core/shared/persistent 등의 미지원 앱도 기록에서 제거하지 않으며 사유를 표시하고 숨기기 버튼을 제공하지 않는다. lazy full preflight에서 추가 미지원 사유가 확인된 앱도 이유를 유지한다.

`[숨기기]`는 기록 화면에서 바로 수행한다. 성공·실패 모두 기록 탭을 유지한다. 안전 절차는 기존 SafetyEngine을 그대로 사용한다: live shell/firmware/ABI → 현재 설치 UID·signer·firstInstallTime·version → authoritative UID peers → grants/flags/raw modes → durable originalBit journal → 적용 직전 재조회 → FINE 0x100만 쓰기 → 즉시/약 500/약 1500ms settling → invariants. 이미 OFF면 setter 없이 동일 settling 검사를 거친다. 성공 dot은 fade하여 theme check로 바뀐다. 성공 snackbar/dialog/haptic은 없다.

Home에는 실제 기록이 있을 때 최근 1개 요약과 기록 보기·바로 숨기기를 표시한다. foreground 복귀 시 상태와 기록을 자동 갱신한다. 상시 service/watchdog/polling은 없다. 기록 다중 선택은 이번 필수 기능으로 채택하지 않았으며 단일 inline action을 기본으로 했다. Picker 다중 추가 및 Home 다중 Restore는 구현했다.

## 후보 탐색과 UID 안전성

1. 검증된 shell PackageManager `getAllPackages`에서 이름 목록을 읽는다.
2. package별 `getPackageInfo(MATCH_APEX | MATCH_STATIC_SHARED_AND_SDK_LIBRARIES, userId=0)` 한 건씩 읽는다. 이 두 조회 플래그는 기존 수집 firmware에서 확인했으며 permissions/signers를 요청하지 않는다. APEX와 FLAG_INSTALLED가 없는 항목은 일반 앱에서 제외한다. 성공적인 null 응답은 사용자 0 범위에서 정보를 얻을 수 없는 항목으로 분류한다. 조회 예외는 계속 전체 실패한다. 전체 무거운 PackageInfo 목록을 한 응답으로 요청하지 않는다.
3. 일반 설치 앱의 UID를 묶고 **서로 다른 UID마다 authoritative getPackagesForUid를 한 번씩** 읽어 전체 peer 이름을 확인한 뒤 지도를 게시한다. PackageInfo가 반환되지 않은 sibling도 peer 목록에는 유지한다. 알려진 sibling 누락·중복 peer·이름 스캔 밖의 새 peer·조회 오류는 whole scan 실패다. null 항목을 제외한 부분 정보만으로 독립 UID를 판정하지 않는다. 초기 signer, requestedPermissions 전체, permission flags, 상세 AppOps modes, firstInstallTime/version의 full safety load는 계속 생략한다. 이 추가 경량 peer 조회 비용은 재연결 후 UID map ms로 실측해야 한다.
4. 표시용 launcher query와 label을 결합하고 첫 가벼운 목록을 게시한다. 로컬 visibility는 launcher/icon/label 표시에만 사용한다. sole UID ownership의 증거로 사용하지 않는다.
5. AppOps history를 비동기로 채우고 최근순 정렬한다. 모든 이름을 대상으로 읽으므로 launcher가 없는 실제 위치 접근 앱, COARSE-only 앱과 미지원 시스템 앱도 기록에 남을 수 있다.
6. 사용자가 선택하거나 상세 safety가 필요할 때만 full snapshot을 lazy load한다. **모든 Hide/Restore 직전에는 항상 authoritative full snapshot·getPackagesForUid를 다시 읽는다.** cached UID map은 쓰기 승인 자료가 아니다.

`UidPolicy`는 Android UserHandle.getAppId와 같은 `uid % 100000` semantics를 사용한다. core는 appId < 10000이면 별도 hard block이며 Android adapter는 Process.FIRST_APPLICATION_UID와 비교한다. raw uid만 비교하지 않는다. 현재 쓰기 범위는 user 0 + application appId 10000..19999로 유지한다. 다른 profile/isolated UID는 차단한다. public SDK에서 직접 노출되지 않는 UserHandle helper 호출이나 hidden-API exemption을 추가하지 않았다.

UID peer가 둘 이상이거나 whole scan 확인이 실패하면 독립 UID로 승인하지 않는다. persistent application·명시적 core component·sharedUserId도 차단한다. FLAG_SYSTEM/UPDATED_SYSTEM_APP는 `기본 앱` 표시 분류일 뿐 hard block이 아니다. 독립 UID의 삼성/Google 기본 앱은 나머지 full gate를 통과하면 대상이 될 수 있다.

기본 Picker 후보는 `launcher 있음 OR 실제 위치 기록 있음`이다. launcher가 없다는 이유는 write hard block이 아니다. 필터는 `최근 위치 | 내가 설치한 앱 | 기본 앱 | 전체`, 기본값은 최근 위치다. `전체` 역시 이 사용자용 후보 집합 전체이며 launcher도 기록도 없는 daemon을 무조건 펼치는 모드는 아니다. history가 아직 로딩 중이거나 사용할 수 없으면 최근 위치 필터에서 launcher 후보를 이름순으로 표시한다. 필터·검색은 display 기능이며 write gate와 독립적이다. 검색/Back/화면 재생성의 picked set·query·filter를 보존한다.

TransactionTooLargeException 등 whole authoritative scan 실패는 부분 UID 지도를 게시하지 않고 간단한 조회 오류로 처리한다. 이름 개수·중복·응답 크기를 제한한다. large PackageInfo Parcelable list를 회피하지만 getAllPackages 이름 목록 자체가 거대한 경우 Android Binder 한계를 완전히 없앤다고 주장하지 않는다. 실제 scan 중 known UID peer 불일치나 이름 밖 peer가 발견되면 incomplete로 보고 재확인을 요청한다. scoped null 자체는 설치 변경의 확정 증거로 간주하지 않는다. local visibility로 보충해 안전하다고 판단하지 않는다.

scan/history/target full read는 Dispatchers.IO, 검색·필터 정렬은 Dispatchers.Default, icon loading은 비동기 IO다. 이전 scan은 package별 cancellation 확인과 generation guard로 폐기한다. 이름/UID 조회 도중 설치 변경이 발생하더라도 setter 직전 identity/peer 재확인이 최종 방어다.

## 성능 계측

최종 설치 APK 해시와 `/data/app/.../base.apk`의 실제 SHA-256을 대조했다. 아래 숫자는 최종 APK 설치 시각 **2026-10-04 14:44:40 KST 이후** validation log만 사용한다. 이전 진단 APK의 수치를 섞지 않았다.

| Fold7 측정 | 실제 값 |
|---|---|
| global package names | 837 |
| 설치된 user-0 ApplicationInfo 항목 (조회 플래그가 포함하는 공유 라이브러리 포함) | 778 |
| APEX 제외 / user-0 scoped unavailable | 58 / 1 |
| authoritative UID groups / candidates / real History entries | 645 / 327 / 125 |
| 첫 완성 scan의 lightweight / UID peer map / AppOps history / sort | 225 / 142 / 273 / 13 ms |
| Picker open→first usable Compose frame | 40–69 ms / median 55 ms / 3 samples |
| 검색→frame | 0–43 ms / median 7.5 ms / 46 samples |
| filter→frame | 14–58 ms / median 36.0 ms / 4 samples |
| target full preflight | 4–6 ms / median 5 ms / 5 samples |
| lazy detail | 22–22 ms / median 22 ms / 1 samples |

Picker frame 수치는 이미 읽은 모델을 가진 화면 진입 계측이며 광학 측정이나 콜드 앱 시작 전체 지연이 아니다. ms 분해능과 스케줄링으로 일부 검색 sample은 0이다. user-0 `pm list packages -U`는 777개이며 SDK/static shared library 조회 범위 차이 때문에 ApplicationInfo count 778과 구분한다. 각 scan sample과 개별 계측은 device-validation.json에 보존했다.

실측에서 이전 계측 코드가 동일 필터 결과를 낳는 검색 후 화면을 떠난 시간을 다음 frame까지 합산하는 33120ms sample을 발견했다. 처리 결과 publication revision으로 frame hook을 실행하고 화면 이탈 시 pending interaction을 버리도록 수정했다. 최종 APK로 필터·검색·Back/reentry를 재검사했으며 이 이전 APK sample은 최종 성능에서 제외했다. 이 변경은 대상 설정이나 safety engine을 바꾸지 않는다.

초기 scan/history/preflight는 IO, 정렬·검색·필터는 Default, 아이콘은 비동기이며 full permission/signer safety는 lazy다. 초기 전체 scan과 UID peer 조회 비용은 위 실제 값으로 보고하며 엄밀한 전체 콜드 시작 latency는 별도 범위다.

## 등록 앱 대량 관리

Home `편집`에서 체크하고 `선택한 N개 숨김 해제`로 originalBit를 복원한다. 각 target은 독립 durable transaction과 fresh gate를 거친다. 전체 flags snapshot이나 타 target의 bit를 복원하지 않는다. 원래 OFF인 target은 OFF로 유지한다.

stale identity/shared UID/missing package 등 명확한 대상별 preflight 거절은 해당 대상만 변경하지 않고 다음 대상으로 진행한다. FAILED_SAFE는 해당 transaction의 좁은 원복 확인 결과를 표시한다. FAILED_ROLLBACK 또는 연결/예기치 않은 예외는 남은 batch를 중단하고 변경하지 않은 이유를 package별로 표시한다. 광범위한 batch rollback은 없다. 부분 성공 시 실패한 선택과 inline 결과를 유지한다. Picker는 전체 성공일 때만 Home으로 돌아간다.

## Update lifecycle과 알림

프로세스가 살아 있을 때 PACKAGE_REPLACED 동적 receiver는 로컬 journal의 enabled target을 NEEDS_REAPPLY_UPDATE로 기록한다. foreground 화면에는 journal 변경 event를 전달하여 read-only refresh를 실행한다. foreground에서는 같은 상태를 다시 알리는 notification을 억제한다. duplicate event가 이미 같은 marker이면 새 알림을 발행하지 않는다.

놓친 event는 시작/onResume의 로컬 조회와 이어지는 Shizuku authoritative full reconcile로 보완한다. 같은 install의 versionCode/lastUpdateTime 변화는 UPDATE 상태다. UID·signer·firstInstallTime 변화는 과거 설치 INACTIVE_INSTALL로 비활성화하고 자동으로 새 설치에 적용하지 않는다. authoritative missing은 MISSING_TARGET으로 originalBit를 보존한다. 로컬 visibility로 확인되지 않는 경우 Shizuku 확인 필요이며 로컬 부재만으로 설치 제거를 확정하지 않는다.

update 후 bit가 OFF여도 재적용 marker를 보수적으로 유지한다. 사용자가 재적용하면 OFF는 no-op settling, ON은 narrow 0x100 write다. receiver, startup, resume, notification tap에는 자동 setter가 없다. event 이후 다른 permission metadata 변화가 있었다고 기존 originalBit를 새 snapshot으로 덮어쓰지 않는다.

알림은 실제 재적용이 필요한 boot/update 상태에서만 발행하고 notification permission 거절·앱 전체 알림 차단을 존중한다. tap은 target 상세/재적용 화면으로 이동할 뿐이다. 프로세스가 완전히 종료됐거나 force-stop/기기 정책으로 broadcast를 놓친 경우 즉시 알림을 보장하지 않으며 다음 foreground 확인이 보완 경로다. update/version 이벤트가 같아서 상태가 이미 UPDATE인 동안에는 중복 안내를 억제한다.

## Exact writes와 permission audit

유일한 privileged setter는 기존 `IPermissionManager.updatePermissionFlags` 한 경로다.

```text
permission = android.permission.ACCESS_FINE_LOCATION
mask = 0x100
value = 0 또는 그 설치 journal.originalBit (0 / 0x100)
checkAdjustPolicy = true
persistentDeviceId = default:0
userId = 0
```

rollback도 같은 한 bit 경로다. AppOps setter, grant/revoke, COARSE/0x200/다른 metadata setter, location switch, root/su/Sui, Knox, bootloader, SELinux/CSC/시스템 파일 수정, Accessibility/overlay, Runtime.exec/ProcessBuilder, background service가 없다. Sui automatic initialization은 기존처럼 비활성화한다. 공식 Shizuku signer pin, live Binder ping/remote UID 2000/SELinux shell/API 13 검증을 유지한다.

debug/release merged permissions는 동일하다: Shizuku API_V23, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, AndroidX 앱 전용 signature permission. INTERNET, LOCATION, QUERY_ALL_PACKAGES, REQUEST_INSTALL_PACKAGES 등 새 민감 권한이 없다. backup/cleartext는 false, release debuggable=false, minify=false다. R8를 새로 강제 적용하지 않았다. compile/targetSdk는 기존 35, runtime 지원 gate는 37이며 새 SDK migration은 이번 범위 밖이다.

## PC tests와 UI 검토

최종 실행: `:core:test :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease`.

| suite | test 수 |
|---|---:|
| SafetyEngineTest | 39 |
| DexAbiTest | 5 |
| DiscoveryTest | 31 |
| LifecycleBatchTest | 16 |
| JournalStoreTest | 8 |
| NotificationTest | 4 |
| HistoryParcelTest | 6 |
| UiRenderTest | 28 |
| 합계 (core + debug app, distinct cases) | **137** |

release app에서 46개를 별도 실행하여 합계 183 executions다. 최종 XML 결과에서 failures/errors 0을 확인한다. debug/release lint는 각각 **0 errors, 7 warnings**다. 기존 toolchain/SDK 관련 warning은 보고서에서 제거하지 않는다.

추가 테스트는 core appId 경계·multi-user·shared peers·독립 기본 앱·persistent·launcher/history 교집합·5,000 packages·실제 Android TransactionTooLargeException·불완전 authoritative/local visibility·timestamps/동률/denied/FINE+COARSE·cold/missed update·UID/signer/reinstall/missing·update OFF no-op/ON explicit write·batch 원본 ON/OFF 혼합·partial failure·uncertain rollback/disconnect 중단을 다룬다. UI는 hidden/unhidden/unsupported/update/inline failure/empty History, direct action callback, filter/daemon 제외, batch Restore 선택, dark/wide와 기존 18개 regression render를 포함한다. History parser identity/count/empty tests에 더해 최종 debug APK에서 Android 17의 실제 Parcel 응답과 firmware/history/indicator ABI gate 통과를 확인했다. release의 해당 경로는 아직 기기에 설치하지 않았다.

28개 PC Compose PNG와 contact sheet를 생성했다. 실제 기록 행·inline action·Picker chips/sticky add·dark/wide를 시각 검토했다. PC callback/render test를 실제 SafetyEngine Binder success나 실물 Blue Dot 관찰로 바꾸어 주장하지 않는다.

작업 중 발견한 Gradle Properties 이름 충돌·위임 상태 smart cast·Compose produceState lint 문제는 수정했다. release에만 없는 개발용 PreviewActivity 제거 선언의 MissingClass는 **삭제 선언 한 줄에만** suppression을 적용했으며 실제 merged activity는 MainActivity 하나뿐이다. 최종 필수 검증은 모두 통과한 산출물만 전달한다.

## 실제 Fold7 검증

초기 APK에서는 global getAllPackages와 user-0 PackageInfo 범위 차이로 APEX·static library·officehubrow null에서 전체 목록 조회가 실패했다. APEX/shared library 조회 플래그, scoped unavailable 분류, UID별 authoritative peer confirmation으로 수정했고 최종 APK에서 전체 scan이 통과했다. officehubrow null의 정확한 숨김/설치 원인을 확정했다는 주장은 하지 않는다. 실패 재현 로그는 진단 session 파일에 별도 보존했다.

동일 signer `install -r`만 사용해 데이터와 journal을 유지했다. shell UID 2000 Shizuku와 검증 firmware/ABI를 확인했다. baseline 미세미세 0x300 → History inline Hide 0x200 → 다중 추가에서 이미 OFF no-op settling → Home 다중 Restore 0x300 순으로 검사했다. 날씨날씨는 batch add에서 0x80301→0x80201, batch Restore에서 0x80301이다. NAVER에는 setter가 없었다.

실제 사용자 OFF/원복 ON 답변을 현재 대화에서 받았다. 처음 baseline 육안 질문은 응답이 없었으며 그 ON 판정은 fresh 0x300, 미세미세 AppOps active와 SystemUI isSystem=false/location=true로 확인한 기술적 baseline이다. 실제 OFF와 원복 ON은 각각 사용자 관찰과 SystemUI를 함께 확인했다. OFF에서도 새 FINE access는 유지됐고 isSystem=true/location=false, 원복 뒤는 isSystem=false/location=true와 실제 PrivacyItem이 돌아왔다. screenshot에 빠지는 표시 레이어를 screenshot만으로 PASS 처리하지 않았다.

| 항목 | 검증 결과 |
|---|---|
| A | PASS: History populated; actual 125 positive-access records |
| B | PASS: latest-first display, deterministic policy; fresh Mise moved into recent rows |
| C | PASS: new Mise access and foreground-return automatic refresh; weather existing real history read |
| D | PASS: Mise History inline Hide; remains on History |
| E | PASS: user confirmed both indicators disappeared; fresh AppOps still active and SystemUI isSystem=true/location=false |
| F | PASS: History Blue Dot 숨김 적용됨 check, fresh APPLIED settling |
| G | PASS: user confirmed both indicators returned after batch Restore; SystemUI isSystem=false/location=true |
| H | PASS: recent/user/preinstalled/all chips; launcher or real history candidate policy; unsupported retained |
| I | PASS: independent-UID preinstalled Google visible as candidate; full write support not inferred before fresh preflight |
| J | PASS: Android/core services visible with hard block and no Hide action |
| K | PASS: Samsung dialer UID 10129 shares kt114provider2; Picker disabled |
| L | PASS: final-build actual scan/UID/history/sort/frame/search/filter/preflight/detail metrics |
| M | PASS: only Mise and weather selected for batch Restore; originalBit restored independently, NAVER untouched |
| N | PARTIAL: same-signer controller upgrades preserve journal; actual foreground identity reconcile and no automatic setter observed. Target update/missed-update/reboot events covered by PC tests, not newly forced on device; no existing permitted/granted dummy available. |
| O | PASS for tested Phase 6.1 flows: settling, narrow bits, fresh APPLIED, multi-add and retained selection/search/filter, already-OFF no-op, failure gates |
| P | PASS in tested interval: no success haptic call in source and no new controller UID vibration entry; bounded system history, earlier entries excluded |
| Q | PASS: successful inline and batch operations use theme/check feedback, no success Snackbar/dialog in actual UI and source |

총 4개의 setter request/enter/return을 기록했으며 각 요청은 user 0, FINE, mask=256, value=0 또는 originalBit 256이다. immediate/500ms/1500ms의 UID/peers/signers/version/설치 시각/grants/COARSE/BACKGROUND/raw modes 및 FINE의 다른 비트가 모두 보존됐다. 이미 OFF인 미세미세의 3개 settling readback은 setterPreviouslyCalled=false와 0x200이었다. UI의 체크는 fresh APPLIED 확인 뒤만 표시됐다.

### 최종 실제 target 상태

Fresh read 시각: 2026-10-04T06:00:03.931546+00:00 UTC (한국 시각은 UTC+9). final state JSON의 grants/설치 시각 요약은 user 0만 분리했다.

| 대상 | UID | FINE / COARSE / BACKGROUND | journal | 이번 setter |
|---|---:|---|---|---:|
| 미세미세 | 10474 | 0x300 / 0x300 / 0x2b00 | NOT_APPLIED, disabled, originalBit 256 | Hide 1 + Restore 1 |
| 날씨날씨 | 10078 | 0x80301 / 0x301 / 0xb01 | NOT_APPLIED, disabled, originalBit 256 | Hide 1 + Restore 1 |
| 네이버지도 | 10395 | 0x200 / 0x300 / 0x2b00 | APPLIED, enabled, originalBit 256 | 0, 기존 OFF 유지 |

USB stay-awake는 이전 사용자 요청의 값 2를 읽었고 변경하지 않았다. 끝에 Home을 보여줬다. 제품 debug→release 전환, 앱 삭제/초기화, real target 강제 업데이트, permission grant/revoke, 위치 기능 스위치는 수행하지 않았다.

## 제한과 검증 범위

현재 firmware에서 핵심 regression은 PASS다. N의 등록 target update/missed-update/reboot를 실제 새 이벤트로 발생시키는 검사는 이번에 완료한 것으로 주장하지 않는다. 현재 더미 앱이 미설치 상태였고 추가 위치 권한 부여나 실사용 앱 임의 업데이트를 수행하지 않았다. 해당 상태 머신·alive/missed update·서명/UID 변화·OFF no-op/ON explicit write·중복 알림은 PC 테스트로 확인했으며 Phase 4/5 결과는 이번 APK lifecycle 보장을 대신하지 않는다. 같은 signer controller 업데이트 후 journal 보존과 실제 foreground reconcile/no-auto-write는 확인했다.

release APK는 signing/merged manifest/46 unit executions/lint로 검증했고, 실제 Shizuku Binder 경로는 동일 signer debug APK로 검사했다. release hardware migration은 수행하지 않았다. bounded vibrator history에서 최종 검사 시간의 새 app.bluedotoff UID 요청은 없었으며 이전 빌드의 10–11시 기록은 제외했다. 모든 시간·모든 기기의 햅틱 부재 증명으로 일반화하지 않는다.

불완전 또는 설치 중 변경된 authoritative scan은 안전하게 전체 실패할 수 있고 아주 큰 이름 목록의 Binder 한계는 남아 있다. 로컬 launcher/icon/label visibility에 따른 packageName fallback, lazy safety 확인, foreground/background provenance 미확정, 상시 active tracking 없음, 다른 firmware/기기/장시간 안정성 미보장은 기존 범위대로 유지한다.

**Phase 6.2 구현·배포 산출물과 현재 Fold7 핵심 실기기 검증을 마무리했다. 실제 target-update/reboot lifecycle의 새 실측 및 release hardware 경로는 위 제한으로 분리했다.**
