# bluedotOFF Phase 7.1 — Lifecycle와 연결 준비 UX

2026-10-05, Galaxy Z Fold7 SM-F966N / One UI90000 / Android17 SDK37. test.8 업데이트 설치와 이번 기기의 핵심 lifecycle 실기기 검증을 완료했다. 저장 키 재사용, 실제 Wireless OFF→ON, 동일 boot force-stop, getter 소켓 중단/복구, 사용자 host Forget와 알림 재페어링, 실제 재부팅·알림·명시적 단일 재적용이 통과했다. 새 설치 최초 권한 창과 Developer Options OFF 전체 walkthrough는 현재 기기에서 수행하지 않았으므로 모든 novice acceptance를 실측 PASS로 선언하지 않는다.

실제 대상 쓰기는 사용자가 누른 미세미세 재적용1건(FINE mask0x100, value0)이다. 자동/의도하지 않은 target 쓰기0건. 재부팅 과정에서 OS가 OFF 비트를 ON으로 재계산했다. 시험 앱 미세미세만 기존 OFF로 되돌렸고, 나머지는 재적용 필요 상태를 그대로 남겼다. 기존 등록8개의 originalBit/enabled/ownership은 유지했다.

## 기준본과 안전 경계

test.7 commit `e87fd18`, branch/tag `baseline/test7-local-adb`/`test7-local-adb`, 동결 폴더11개 SHA256와 기존 private evidence를 보존했다. 작업 branch `codex/phase71-lifecycle-ux`. 실기기에서 발견한 복구 수정은 `db4f1e3`에 기록했다.

SafetyEngine/Domain, Discovery/HistoryPolicy, BootEvents, JournalStore, AppOpsHistory, SingletonSupport, BinderBackend, LocalAdbBackend, MinimalPrivilegedHelper와 native bridge의10개 소스가 test.7 ZIP과 byte-for-byte 같다. 허용 setter는 기존 FINE0x100뿐이며 grant/revoke·AppOps/COARSE/0x200 setter·root·Knox·framework/SystemUI 수정·arbitrary shell·Accessibility·overlay·background 자동 rewrite를 추가하지 않았다. test.7 카카오맵 A/B/A 실측과 이번297개 회귀 검사를 유지한다. 이번 test.8에서는 새 전체 Restore A/B/A를 실측했다고 주장하지 않고, 실제 reboot 후 미세미세 단일 재적용을 추가 검증했다.

## 구현과 실기기에서 수정한 문제

`core/SetupFlow.kt` enum/presentation이 실제 상태로 현재 primary action 하나를 선택한다. UI 단계 번호를 저장하지 않는다. 개발자/무선 설정, 기존 pairing, 알림·근처 기기 권한, live endpoint와 fresh verification 결과가 source of truth다. 이미 설정된 사용자는 완료 단계를 건너뛰며, 권한 거절 시 in-app/manual fallback으로 연결 자체를 막지 않는다.

SetupSession은5분 bounded NSD/500ms observation을 사용하고 상시 service 없이 종료·만료 때 정리한다. Activity rotation은 process-local session을 유지하고 process death 뒤 실제 상태에서 다시 시작한다. UUID+generation의 단일 lease로 stale/duplicate reply를 거부하고 코드 bytes를 종료 시 clear한다. Code/key/RemoteInput 로그·clipboard·analytics는 없다. 입력 Activity FLAG_SECURE와 save/autofill 제한도 유지했다.

첫 실측에서15초 검색 제한 뒤 own endpoint가 발견돼도 DISCOVERY_RECOVERY가 남는 문제를 찾았다. 늦은 own-GUID 서비스는 검색 오류만 해제하도록 수정했다. 승인 실패의 최대2회 제한은 유지한다. 성공 후 반복 onResume는 fresh 검증 횟수를 확보하고 종료된 discovery callback은 epoch로 무시한다. 두 회귀 검사를 추가한 최종 APK에서 정상 setup 재진입→Home 자동 복귀와 lifecycle 시험을 수행했다.

서비스 감지는 `_adb-tls-pairing._tcp`의 own-address advertisement이며 Settings 화면 내용을 읽는 기능이 아니다. 동일 id700의 조용한 GUIDE 알림을 HIGH channel의 CODE/RemoteInput으로 교체한다. 소실·소비·성공·취소·stale·만료 시 입력 알림을 제거한다. 코드 화면에서 사용자가 ‘코드 입력’을 누르는 public direct reply를 사용한다. 키보드를 강제로 여는 API·full-screen intent·trampoline/input injection은 없다. [Android direct reply](https://developer.android.com/develop/ui/compose/notifications/create-notification), [heads-up 표시](https://developer.android.com/design/ui/mobile/guides/home-screen/notifications), [Android12 trampoline 제한](https://developer.android.com/about/versions/12/behavior-changes-12).

## Fold7 실제 결과

| 검사 | 실제 결과 |
|---|---|
| 기존 알림/근처 기기 권한·개발자 옵션 판별 | 이미 granted/ON을 감지하고 반복 요청 생략. 새 설치 최초 창은 미수행 |
| paired + Wireless OFF 안내 | 새 코드 없이 무선 디버깅만 켜라는 단일 행동 안내 |
| saved-key reconnect | shell UID2000 / SELinux `u:r:shell:s0`, 지원 ABI·helper·journal fresh 조회 |
| Wireless ON→OFF→ON | 연결 불가 action card, cached APPLIED 확정 없음. port38797→43009 새 검색·코드 없이 재연결 |
| 동일 boot force-stop/relaunch | boot_count54, 잘못된 NEEDS_REAPPLY_BOOT0, metadata 변경0 |
| 실제 getter socket close | getter 실패·backend unavailable 후 새 연결 전체 getter/History PASS, target write0 |
| 사용자 bluedotOFF host Forget | Main 검사 실패, Setup 자동2회 실패 후 보수적 승인/연결 복구 안내로 정지 |
| 사용자 알림 RemoteInput re-pair | Settings 화면에서 코드 입력/전송→76ms pairing→shell/profile/reconcile 성공. 앱 입력란 복귀 불필요 |
| 알림 체감 | 사용자 약0.5~1초 반응 지연. heads-up 팝업인지 알림창인지는 별도 확정하지 않음. 성공 후 입력 알림 없음 |
| 실제 reboot | boot_count54→55, encrypted identity/GUID hash 동일, 이 기기는 Wireless ON 유지 |
| BOOT 수신·재적용 알림 | 첫 post-boot sample 뒤 늦게 수신. 등록8개 BOOT marker와 id4101/reapply 알림 실제 생성 |
| post-boot reconnect | 새 코드 없이 shell/profile/전체 getter, appBootCount55, History779행 |
| 사용자 미세미세 재적용 | 실제 narrow setter1회 0x300→0x200, 즉시/500ms/1500ms 모두 PASS |
| 실제 위치·표시 | 새 FINE access 유지, SystemUI isSystem=true 필터. 사용자 파란 점·위치 아이콘 둘 다 안 보임 확인 |
| 최종 invariants |9개 target identity/UID peers/grants/raw modes/COARSE/0x100 외 flags 동일,8개 journal ownership/enabled/originalBit 동일 |

BOOT 방송이 즉시 도착했다고 주장하지 않는다. 최초 capture에서 journal의 이전 저장 상태가 남았지만 이후 실제 수신으로 BOOT marker와 알림이 생성됐고, Home fresh 판정도 재적용 필요였다. 앱의 foreground 조회는 current boot_count로 별도 reconcile한다. 플랫폼은 일부 background 제한 상태에서 BOOT 전달을 지연할 수 있지만 이번 기기의 지연 원인은 특정하지 못했다(조회 당시 standby10, RUN_ANY_IN_BACKGROUND 기본allow). 따라서 restricted 상태 때문이라고 단정하지 않는다. [Android background 제한 문서](https://developer.android.com/topic/performance/background-optimization).

## 재부팅 전후 최종 상태

아래 변화에서 부팅 시 OS 재계산과 앱 setter를 분리했다. 사용자 미세미세 재적용을 제외한 다른 package에 앱 setter는 없었다.

| 앱 | 최초 FINE | 재부팅 직후 | 최종 FINE | 최종 journal |
|---|---|---|---|---|
| 미세미세 |0x200|0x300|0x200|APPLIED, boot55 |
| 중고나라 |0x200|0x300|0x300|NEEDS_REAPPLY_BOOT |
| 토스 |0x200|0x300|0x300|NEEDS_REAPPLY_BOOT |
| 우지커피 |0x80201|0x80301|0x80301|NEEDS_REAPPLY_BOOT |
| 삼성 날씨 |0x80201|0x80301|0x80301|NEEDS_REAPPLY_BOOT |
| 날씨날씨 |0x80201|0x80301|0x80301|NEEDS_REAPPLY_BOOT |
| 카카오맵 |0x300|0x300|0x300|NEEDS_REAPPLY_BOOT |
| 당근 |0x300|0x300|0x300|NEEDS_REAPPLY_UPDATE |
| NAVER 지도 |0x200|0x300|0x300|기존 관리 밖·journal 추가 없음 |

NAVER의 기존 OFF를 앱이 ON으로 정규화한 것이 아니다. 재부팅 OS 재계산으로 ON이 됐고 NAVER setter0·journal 추가0이다. 기존 관리 앱도 자동으로 다시 숨기지 않았다. 나머지를 사용하려면 사용자가 필요한 앱의 재적용을 선택해야 한다. 원복 비트는8개 모두 저장된0x100이고 registered/enabled=true 상태를 유지했다. 대상 COARSE와 AppOps는 전후 동일하다. 기존 grant/revoke도0회다. 최종 helper 프로세스와 Shizuku server는 없다. USB 충전 중 화면 유지7은 기존 사용자 설정을 유지했다.

## 초보자 흐름과 action 수의 범위

| 사용자 상황 | 제품 흐름 | 이번 실측 범위 |
|---|---|---|
| 완전 신규·개발자 OFF | 시작 → 짧은 알림 설명/OS 선택 → 휴대전화 정보/빌드번호7회 → 무선 ON → 근처 기기 선택 → 코드 알림 | 현재 휴대폰을 초기화하거나 permission을 강제로 revoke하지 않아 전체 사용자 tap/action 수 미실측. UI/state 회귀 검사 |
| 개발자 ON·새 pairing | 완료 단계를 skip → 무선 ON → 코드 화면 → 알림 입력·전송 | 이번 기존 권한 상태에서 Forget 후 코드 화면 열기/입력/전송을 실제 수행. 완전 새 설치 persona와 구분 |
| paired 재사용·무선 OFF | 설정 이동 → ON → 앱 복귀 → 자동 fresh 확인 | 앱 안내/설정 이동2회는 Codex가 조작, 사용자는 switch ON1회와 앱 복귀1회 수행. 새 코드 입력0·성공 확인0 |
| paired·무선 ON | 앱 열기 → auto reconnect/Home | 추가 pairing0·완료 확인0. force-stop/설정 재진입/재부팅 뒤 모두 실제 검증 |
| 재페어링 inline 입력 | 코드 화면 열기 → 알림 ‘코드 입력’ → 입력 → 전송 | 논리 행동 최소4개. 알림 확장/키보드/raw tap 수는 관측하지 않았으므로4개를 실측 전체 tap 수로 쓰지 않음 |

Shizuku 스타일의 advertisement→입력 알림→Settings를 유지한 inline 제출을 실제 확인했다. 포트 입력·앱으로 되돌아와 제출·페어링 성공 확인 dialog는 정상 경로에서 필요 없었다. 사용자는 Android 설정 스위치·permission 선택·Forget·코드 입력·PIN 잠금 해제를 직접 수행해야 한다. 일반 앱이 Settings 위에서 editor를 강제 open하는 구현은 없고, Android 공개 API의 사용자 action을 사용한다. HIGH 채널만으로 모든 환경의 heads-up을 보장하지 않는다. [알림 runtime permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

## 오프라인 검사와 명시적 미실측 항목

core193, Android debug104/release104: 서로 다른297개·총401회 실행, failures/errors/skipped0. Debug/release assemble 및 lint PASS, 각각0 errors/23 warnings(대부분 기존 SetTextI18n/UseKtx). 기존 signer·manifest5 permissions·16KB ELF/ZIP alignment·동결11개 파일·보호 소스10개·vendor provenance를 확인했다.

알림 최초 allow/deny 창, Developer Options OFF 전체 실기기 novice walkthrough, 실제 wrong/expired code, setter 도중 실제 소켓 끊기는 미수행했다. 대신 관련 core/UI/session/model 검사를 실행했고 setter interruption은 model에서 다룬다. release APK는 오프라인 테스트/감사했지만 debug 데이터를 유지하기 위해 release로 전환 설치하지 않았다. 첫 설치가 아닌 실제 기존 사용자 환경의 skip/re-pair를 신규 사용자 전체 실측으로 바꾸어 쓰지 않는다. 장시간 PermissionController 재계산을 막거나 자동 rewrite하는 기능도 없다.

## 산출물과 증거

versionName1.0.0-test.8 / code8 / compile-target37. 현재 휴대폰에 debug test.8 설치, SHA256 `d94b1144da8d532402ed0d86345220b73254299988b9acdae4249d51ae04b130`(28,967,156 bytes). Release SHA256 `fe92fc4e08b031f3236416d33eedca065738d08a62aa33e152a9c127d652ebb6`(20,092,191 bytes). 기존 debug cert `3b3b208f92d3d437acf30c333d9a7b5b0bc8f535056e18edd82df2701db2be0c`, release cert `619fe1ecbdf59d5d0b838ddc6420e58be90f828be11cb61fe6c8e6aa4ceaad62` 유지.

`artifacts/bluedotOFF-1.0.0-test.8.apk`, `-release.apk`, `-source.zip`, build/safety/device/source/delivery audits 제공. Source ZIP은831개 원본 파일+hash manifest이며 wrapper/native vendor/license 포함. 이번 실제 device dumps·journal·key·code·research/cache/signing secrets는 ZIP 제외. 설치 APK와 전달 APK hash가 일치한다.

Private 증거는 `research/phase71_20261005`에 구분 보존했다: before/installed/force-stop/wireless/forgotten/pre-reboot/post-reboot/boot-later/reapplied/final metadata와 getter/socket/NSD/typed setter proof. `final-verification.json`은 모든 불변성과 OS reset, 단일 명시적 쓰기, 최종 state를 대조한다. 보고서와 device audit는 완료된 실기기 결과 및 미실측 한계를 함께 기록한다.
