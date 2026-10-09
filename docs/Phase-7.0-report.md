# bluedotOFF Phase 7.0 — Local ADB test.7 최종 검증

`1.0.0-test.7 / versionCode 7`, compile/target 37의 Local ADB 전환 acceptance를 Fold7에서 통과했다. Shizuku server 없이 자체 pairing·저장 키 재연결·shell UID2000 Binder 접근·카카오맵 Hide/Restore를 검증했고, 최종 debug APK를 기존 데이터 보존 업데이트로 설치했다. 최종 상태는 카카오맵 ON, NAVER 기존 OFF, 기존 등록6개 OFF다. 관찰 대상9개 metadata가 시험 전 값과 정확히 같다. **대상 metadata setter는 승인 이후 카카오맵 FINE/mask0x100에만 총6회**, 읽기 전용 acceptance 단계에서는0회다.

이 결과는 시험한 firmware의 동작에 대한 PASS다. 카카오맵의 민감도 비트가 첫 숨김 뒤 ON으로 재분류되는 현상을 실제로 관찰했다. 새 상태를 읽어 `NEEDS_REAPPLY_POLICY`로 감지하고 수동 재적용했으며, 자동·영구 숨김을 보장하지 않는다. 이번 단계의 실제 reboot, Android authorization revoke, 알림 거부 상태 최초 pairing은 수행하지 않았고 관련 자동 검증과 실기기 검증을 아래에서 구분한다.

## 기준본과 안전성 보존

`baselines/test6-shizuku`의 원본 source·APK·ZIP·report·device evidence 72개 SHA256을 다시 대조했다. 모두 동일하다. 기준 commit `40ba62306d5482dc1beea12b4eb7112b3c5b38ec`, branch `baseline/test6-shizuku`, tag `test6-shizuku`; 작업 branch `codex/phase7-local-adb`.

SafetyEngine/Domain, Discovery/HistoryPolicy, JournalStore, AppOpsHistory, SingletonSupport는 기준본과 byte-for-byte 동일하다. BinderBackend의 `allPackages()`부터 끝까지의 discovery/snapshot/UID/History getter 구현도 동일하다. SDK37 Shizuku reference는 `baseline/phase7-shizuku-reference` branch/commit `110d3db`에 보존했다. 실제 A/B/A와 중단 검사 후 production에서 reference source set4개 파일·Shizuku 의존성·provider·권한·query·metadata를 제거했다. `local`만 빌드하며 기존 journal enum/denylist의 legacy 문자열은 저장 format/정책 호환용으로 유지한다.

최종 회귀 검사: core **171**, Android/Robolectric debug **81**, release **81**. 서로 다른 검사252개, variant 실행333개, failures/errors/skipped0. Local debug/release assemble와 lint 성공. Lint는 각 variant **0 errors/38 warnings**이며 Korean SetupActivity의 SetTextI18n, UseKtx 등 경고가 남아 있다. AGP9의 release unit-test 기본 제외를 피하도록 `android.onlyEnableUnitTestForTheTestedBuildType=false`를 명시했다. SDK migration은 별도 commit `ca8b938`이다. AGP9.1.1 / Gradle9.3.1 / built-in Kotlin2.2.10 / JDK17 / Android37.0 / build-tools36 / NDK27.2.12479018 / arm64 / 16KB native alignment를 사용한다. [AGP 공식 호환표](https://developer.android.com/build/releases/agp-9-1-0-release-notes), [built-in Kotlin migration](https://developer.android.com/build/migrate-to-built-in-kotlin), [AGP9 unit-test 기본값](https://developer.android.com/build/releases/agp-9-0-0-release-notes).

## 선택한 구조와 라이선스

앱의 native pairing/TLS → loopback ADB → 설치 APK의 고정 `app_process` helper → 기존 Binder getters/SafetyEngine. Helper stdin/stdout은 bounded BD07 length frame과 strict typed JSON이다. 임의 shell/command/host/descriptor/transaction 입력을 받지 않는다. Pairing과 operation batch는 단일 mutex와 generation을 공유한다. Helper는 batch 종료 시 EOF/socket close로 종료하며, 최대120초·idle10초 watchdog이 있다. 종료 후 실제 process 목록에서 helper 부재를 확인했다.

| 방식 | 선택 근거 |
|---|---|
| A: AOSP 필요한 native 부분 | 채택. SPAKE2/AES-GCM/BoringSSL과 fixed-loopback JNI, 앱의 bounded ADB shell-v2 codec. full adb server/executable을 포함하지 않는다. |
| B: 외부 ADB library | libadb-android3.1.1을 검토했지만 사용하지 않았다. Apache/GPL dual license와 LGPL dependency 구성을 추가할 필요가 없었다. 이 library의 Android17 호환을 검증했다는 주장은 하지 않는다. |
| C: full adb executable/server | 일반 shell/server surface와 추가 executable 크기 때문에 제외했다. 최종 APK는 debug28,904,732 bytes, release20,043,035 bytes다. |

AOSP ADB commit `9084198a2d4b0f6a0f174260fb42da33485b684d`, AOSP BoringSSL `0cd1f6a94b670e6b61af95a06e71e690ae8e3262`, upstream BoringSSL `d32c35e573fc9c8dbe0436805cd557065892de4c`. Apache2 ADB NOTICE, BoringSSL/fiat-crypto LICENSE를 source·assets에 보존했다. Native private material clear를 추가하고 upstream/current hashes를 기록했다. LADB/Shizuku 외부 source를 복사하지 않았다. 기존 프로젝트의 자체 Shizuku reference는 보존했다. [AOSP Android17 ADB](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/android17-release/), [AOSP BoringSSL](https://android.googlesource.com/platform/external/boringssl/+/refs/heads/android17-release/).

## Android17 네트워크 실증과 pairing

[Android17 ADB Wi-Fi2.0](https://developer.android.com/blog/posts/introducing-fast-and-reliable-wireless-debugging-with-android-debug-bridge-adb-wi-fi-2-0), [ADB 공식 문서](https://developer.android.com/tools/adb)를 기준으로 `_adb-tls-pairing._tcp`와 `_adb-tls-connect._tcp`를 분리한다. 서비스가 반환한 주소는 현재 기기 주소 또는 loopback이어야 하며 실제 socket destination은 항상 `127.0.0.1`이다. 저장된 device GUID와 CONNECT 이름을 대조하고 port는 매번 resolve한다.

INTERNET 미선언 실제 앱은 socket errno1 EPERM 및 NSD SecurityException이었다. 선언 후 errno0과 pairing/connect 성공. SDK37에서도 INTERNET만으로 loopback socket이 열렸다. 앱의 네트워크 권한 설명은 자체 기기 무선 디버깅용이며 외부 server/analytics/ad/update 통신은 없다.

SDK37 permission 실증:

| 경로 | Fold7 결과 |
|---|---|
| broad permission 없이 FLAG_NO_PICKER | error7. 이 결과를 private NSD audit에 기록했다. |
| FLAG_SHOW_PICKER CONNECT | 실제 시스템 선택 후 own-device CONNECT resolve/TLS/helper/전체 읽기 성공. broad 권한 불필요. |
| FLAG_USER_APPROVED_ONLY | 앱 재시작 뒤 사전 선택된 CONNECT로 no-code reconnect 성공. |
| FLAG_SHOW_PICKER PAIR | 사용자 화면에서 목록 없이 로딩. 당시 PC read-only mDNS에는 CONNECT만 남고 PAIR advertisement가 사라짐. Settings pairing dialog와 picker 이동 충돌로 추정하지만 정확한 OS 내부 원인은 입증하지 않았다. |
| ACCESS_LOCAL_NETWORK 허용 + NO_PICKER | 사용자가 근처 기기 권한을 직접 허용하고 Settings 코드 창에서 알림 입력. PAIR own-device resolve, pairing 뒤 새 CONNECT resolve, 새 read-only proof 성공. |

따라서 정상 6자리 코드 입력 UX에만 ACCESS_LOCAL_NETWORK를 설명·요청한다. 거절 시 알림에서 `페어링 포트 + 공백 + 코드`를 입력하거나 split-screen in-app fallback을 사용할 수 있다. 거절 fallback parser/state는 자동 검사했으며, 이 Fold7에서 broad 권한을 다시 revoke해 최초 pairing을 반복하지 않았다. [DiscoveryRequest flags](https://developer.android.com/reference/android/net/nsd/DiscoveryRequest), [Local network permission](https://developer.android.com/privacy-and-security/local-network-permission).

RSA2048 host key는 private noBackup AtomicFile에 AndroidKeyStore non-exportable AES256-GCM key로 wrapping한다. versioned envelope/AEAD corruption·wrong-key·bounds 검사를 자동 검증했다. 기존 pairing 파일의 format은 그대로다. Missing key/GUID로 reconnect identity를 새로 만들지 않는다. Own reset은 앱의 key/GUID만 삭제하고 Android Forget/Revoke는 사용자가 직접 한다. 실제 key 삭제·Android authorization revoke는 수행하지 않았다.

RemoteInput은 explicit mutable PendingIntent/non-exported receiver이며 5분·generation·single-use·cancel 검사를 적용한다. 입력값은 secret visibility notification과 memory에서만 다루며 사용 후 clear한다. 실제 code/private key/RemoteInput을 chat/log/evidence/source ZIP에 수집하지 않는다. 초기 앱 입력과 알림 입력 안내가 혼동된 시도는 성공으로 세지 않았다. SDK37 실제 pairing 성공 때는 pairing-time instrumentation 추가 전이어서 정확한 crypto 소요시간은 별도 측정되지 않았다.

TLS1.3 + AOSP SPAKE2/TLS-exporter binding을 사용한다. AOSP 방식과 같이 adbd self-signed server certificate를 일반 PKI 검증하지 않는다. Certificate pinning을 구현/입증했다는 주장은 하지 않는다. Own endpoint/GUID와 shell/context/firmware/ABI 검증을 함께 사용한다.

## SDK37 read-only acceptance A~O

증거: `research/phase7_20261004/read-only-acceptance.json`, `local-sdk37-final-readonly.json`, `acceptance-local-adb-batches.jsonl`, `acceptance-local-adb-nsd-audit.jsonl`, `readonly-sdk37-final-*`, 각 시점 Home/History label JSON.

| 항목 | 실제 결과 |
|---|---|
| A Shizuku OFF | shell server 중지 후 실제 process 부재, manager force-stop. 앱 Local ADB로 계속 동작. Manager 앱은 삭제하지 않았다. |
| B 개발자 옵션 | 앱 Settings.Global read-only로 ON 감지, onResume 재검사. |
| C Settings 이동 | 사용자 무선 디버깅 Settings 이동/수동 toggle 성공. |
| D 알림 | 실제 RemoteInput pairing notification 입력 사용, session 종료 시 제거. |
| E 실제 pairing | target35 성공 이후 SDK37에서도 권한 허용 + 알림 입력으로 새 pairing 성공. |
| F 앱 재시작 | force-stop/relaunch 후 저장한 encrypted key로 재페어링 없이 재연결. |
| G 무선 OFF→ON | SDK37 사용자가 직접 toggle, CONNECT port43721→41037, 같은 GUID/저장 키로 no-code 재연결. |
| H/I shell | 실제 UID2000 / `u:r:shell:s0`. |
| J firmware/ABI | Samsung SM-F966N / SDK37 / OneUI90000 / user / KR, 기존 ABI `046bc2fbf34bce569bf04f81b9548cfe8b49c1842783c46af5315aab5e4d28b8`. Framework12 methods/6 tx, History7 methods/tx20, SystemUI5 methods 및 shell permission/getter checks 통과. |
| K/L package/UID | application779개, global package list와 authoritative UID peer map 성공. |
| M/N History/flags | 기존 AppOps typed Parcel·FINE/COARSE getters 및 App History/All Location Usage 화면 확인. |
| O journal | 7 entries/6 enabled, fresh snapshot 후 6 APPLIED, originalBit와 enabled 유지. |

관찰 대상9개 identity·grants·flags·raw modes 등 수집 fields 모두 before와 동일. 카카오맵 UID10502 / singleton `daum.uid.shared` / peer `[net.daum.android.map]` / FINE0x300 / COARSE0x300 / raw4,4 유지. NAVER unmanaged FINE0x200 유지. 기존6 enabled 대상은 현재 OFF와 original256을 보존했다. 신규 setter tag 로그는0회이고 read-only build/helper gate가 false다.

## 기존 lifecycle 오류 수정

Android15+ force-stop 후 relaunch에서 BOOT_COMPLETED가 재전달된다. [공식 stopped-state 동작](https://developer.android.com/about/versions/15/behavior-changes-all#enhanced-stop-states). 실제 boot_count54와 계속 증가한 uptime을 확인했다. test.6 receiver의 무조건 boot marker 대신 `BootEvents`가 동일한 확인된 boot_count를 구분하도록 수정했고5 tests 및 실제 재시작을 통과했다. Unknown boot identity는 기존 보수적 Reconcile.boot를 유지한다.

이 실험이 잘못 만든6 NEEDS_REAPPLY_BOOT marker는 기존 before journal과 exact identity/invariant/flags/boot54/fresh read를 모두 대조한 일회성 debug recovery로 복구했다. Target metadata write0. Fixture와 임시 recovery code는 삭제했으며 현재 source에 없다. SafetyEngine/Domain은 변경하지 않았다. Actual reboot는 이번 단계에서 수행하지 않았다.

## 오류 검사와 narrow helper

자동 검사에는 malformed ADB header/checksum/length, shell injection, fixed APK path, notification code/port parser·expired/stale reply, key envelope corruption, wrong shell UID/context, profile mismatch, missing key, mDNS zero/multiple endpoints, stream identity, helper invalid JSON/crash/EOF/timeout, negotiated payload chunking, no request retry가 포함된다. SafetyEngine의 기존 before/after-write loss, original-bit rollback, interrupted journal/reconcile 및 peer/identity/permission 변경 회귀도 통과했다. Faults는 fake wire/model로 주입했으며 실제 Android authorization revoke나 잘못된 실코드 입력을 수행한 것으로 쓰지 않는다.

읽기 전용 gate를 실제 승인 후 해제했고 최종 app/backend/helper 모두 narrow UPDATE를 허용한다. FINE/mask256/value0 또는 originalBit/user0/default namespace만 허용한다. App·helper 양쪽 fresh identity digest/invariant digest/exact flags, authoritative peer/eligibility/SafetyEngine을 통과해야 한다. Grant/revoke/AppOps setter/COARSE/0x200 path는 없다. Session UUID·generation·helper PID/start/close/timing, typed operation/descriptor/tx 및 settling verification audit는 secret 없이 private bounded JSONL에 남긴다.

연결 상실 뒤 blind setter retry는 없다. Existing SafetyEngine은 post-write read/rollback을 확인할 수 없으면 FAILED_ROLLBACK이라는 더 보수적인 sticky-review state를 사용한다. 재연결의 read-only 확인만으로 이를 APPLIED로 바꾸지 않는다. 이 기존 state 의미를 새 backend 때문에 바꾸지 않는다.

최종 debug APK에서 인증된 실제 helper 소켓을 닫고 getter가 IOException으로 실패하며 backend LIVE가 false가 되는 것을 검사했다. 원래 journal bit256 유지, 대상 metadata write0. 다음 읽기 전용 proof는 저장 키로 재연결해 UID/context/profile/History/779 application을 다시 검증했다. 실소켓 중단은 getter 단계 시험이다. 실제 대상 setter 중간에 연결을 끊지는 않았다. 추가 model3개는 setter 전 pending-hide→FAILED_SAFE, setter 후 ACK 상실→FAILED_ROLLBACK 및 재시도 없음, 이미 original로 복구된 pending-restore의 read-only 정리를 검사한다.

Helper 오류 응답은 `SHELL_IDENTITY_INVALID`, `COMPATIBILITY_FAILED`, `HELPER_PROTOCOL_FAILED`, `HELPER_OPERATION_REJECTED` whitelist이며 raw exception 메시지를 보내지 않는다. Transport 단계는 ADB connection/authentication/helper start/protocol failure로 구분한다. Logical rejection은 연결을 유지해 기존 안전한 원복 검사가 가능하고 wire failure는 session을 폐기한다. TLS 실패만으로 Android authorization revoke와 네트워크 장애를 유일하게 구별할 수 있다고 주장하지 않는다.

## Settings와 UI

Home/History/Picker 구조를 유지하고 privileged batch만 LocalAdbSession으로 감쌌다. Shizuku 문구 대신 Local ADB 연결 설정을 제공한다. Developer/Wireless settings는 읽기만 하며 자동 ON/OFF를 하지 않는다. Samsung software detail은 non-exported SubSettings여서 직접 실행하지 않고 exported public Device/About으로 fallback한다. WIRELESS_DEBUGGING_SETTINGS action은 이 firmware에서 resolve되지 않아 exported 개발자 옵션 + `toggle_adb_wireless` highlight를 사용한다. 일반 사용의 정상 경로는 IP/port 입력 없이 저장 키와 NSD 자동 연결이다.

## 성능

작은 target35 proof: reconnect121ms/helper211ms/전체1162ms, restart943ms. 첫 전체 scan101700ms였으나 TCP_NODELAY 후 동일 scan2900ms로 개선했다. target35 OFF→ON2991ms. SDK37 picker3432ms, approved-only restart2923ms, 새 broad pairing 뒤 proof4205ms. 읽기 전용 candidate proof2724ms / TLS reconnect36ms / helper HEALTH206ms. Main UI 실제 batches는 verify732ms,6-target fresh reconcile697ms,전체 History discovery2757ms다. 이 마지막 batch의 helper+profile549ms/discovery2180ms. 최종 APK Activity cold launch263ms는 Activity 표시 시간이며 전체 backend ready 시간으로 해석하지 않는다. 최초 pairing crypto 및 NSD resolve 단독 소요시간은 instrumentation 시점 차이로 정확히 측정하지 못했다.

| 실제 setter batch | TLS reconnect | helper+profile | operation batch, settling 포함 | 전체 | immediate→1500ms 검증 |
|---|---:|---:|---:|---:|---:|
| candidate Hide |29ms|703ms|1620ms|2353ms|1517ms|
| candidate Reapply |31ms|586ms|1635ms|2253ms|1522ms|
| candidate Restore |38ms|601ms|1633ms|2272ms|1518ms|
| 최종 APK Hide |32ms|588ms|1747ms|2368ms|1514ms|
| 최종 APK Reapply |30ms|614ms|1642ms|2286ms|1527ms|
| 최종 APK Restore |33ms|586ms|1640ms|2260ms|1518ms|

Hide/Restore는 안전성을 위한 필수1.5초 settling을 포함한다. 새 pairing이나 전체779앱 scan을 각 setter마다 반복하지 않는다. 기존 scan 첫100초 지연은 TCP delayed-ACK 상호작용으로 추정하며 TCP_NODELAY 적용 전후 차이는 실측이다.

## 실제 카카오맵 A/B/A와 정확한 쓰기

요청서56절에 따라 사용자가 실제 ON baseline과 숨김/원복 시험을 승인했다. 카카오맵은 UID10502, `daum.uid.shared`의 유일 peer `net.daum.android.map`, FINE granted, AppOps raw foreground4다. Candidate 및 최종 APK에서 각각 Hide→수동Reapply→Restore를 실행했다. 실제 setter 값 순서는 `[0,0,256,0,0,256]`이다. **6회 모두 package 카카오맵/user0/ACCESS_FINE_LOCATION/mask256/descriptor android.permission.IPermissionManager/transaction8**, shellUID2000/context `u:r:shell:s0`다. COARSE0x200·권한 grant·AppOps mode·다른 package는 쓰지 않았다.

각6회 immediate/500ms/1500ms, 총18회 readback에서 Hide FINE0x200 또는 Restore0x300, COARSE0x300을 확인했다. 첫 Hide는 두 빌드에서 settling 후 새 위치 사용 시0x300으로 되돌아왔다. 그 사이 앱의 추가 setter는 없었고 UI는 `NEEDS_REAPPLY_POLICY`로 바뀌었다. OS 민감도 재분류에 부합하나 이번 증거만으로 PermissionController의 특정 PID가 변경했다고 단정하지 않는다. 자동 write 대신 새 snapshot 기반의 승인된 동일 대상 수동 Reapply를 실행했다.

Candidate에서 사용자는 ON baseline, Reapply 후 파란 점·위치 아이콘 둘 다 OFF, 실제 Restore 뒤 둘 다 ON을 직접 확인했다. 최종 배포 APK에서는 같은 승인 대상의 A/B/A를 instrumentation으로 다시 확인했다. Reapply 뒤 새 위치 이벤트 `23:28:26.028`에서 SystemUI `isSystem=true`와 카카오맵 PrivacyItem 제외, fresh AppOps 정상 접근을 확인했다. Restore는 실제 setter256을 실행했고 `23:31:06.921`의 새 이벤트에서 `isSystem=false`, 카카오맵 location PrivacyItem이 돌아왔다. 최종 APK 해시 자체에 대해 사용자 육안 확인을 다시 요청한 것은 아니다. 위치 접근은 OFF 중에도 유지됐다.

증거는 private research의 `trial-*`, `final-apk-*`, `final-delivery-*`이며 제품 source ZIP에 개인 기기 package/raw Parcel/journal/log/key를 포함하지 않는다. 최종9개 identity·APK hash·전체 수집 permission/grant·flags·raw modes가 before와 같다는 것은 `validate_phase7_delivery.py`로 검증했다.

## 권한·의존성·산출물 감사

Merged permission은 INTERNET, ACCESS_LOCAL_NETWORK, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, AndroidX own signature DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION의5개다. 앱 자체에 위치 권한, QUERY_ALL_PACKAGES, install/overlay/accessibility/root/foreground service가 없다. SetupActivity와 RemoteInput receiver는 non-exported다. APK allowBackup=false. Manifest/dex/runtime dependency에 Shizuku API/provider가 없고 외부 목적지 socket 또는 arbitrary shell API가 없다. Static pattern/manifest/dex 검사는 형식 검증의 증거이며 전체 보안에 대한 형식적 증명은 아니다.

AndroidX Compose BOM2024.10.01/Compose1.7.5, Activity1.9.3, Lifecycle2.8.6, Kotlin stdlib2.2.10은 Apache2 runtime이다. Test-only JUnit4.13.2(EPL1), Robolectric4.14.1(MIT), AndroidX Test1.6.1/Espresso3.6.1(Apache2)은 APK에 포함하지 않는다. Native notices3개와 vendor provenance를 보존했고 APK ELF PT_LOAD alignment4개 모두16384, `zipalign -c -P16 4` 통과다.

| 산출물 | SHA256 | bytes |
|---|---|---:|
| `bluedotOFF-1.0.0-test.7.apk` |`e0a3ca47b6e3033af2d3ff7f1dd5d7943fe8cd99c46410f7a362ce0021cf68ba`|28904732|
| `bluedotOFF-1.0.0-test.7-release.apk` |`bfd0a90e7f1fe65f276b3db73e2d6f222989901dc902e6c5df871f9164cbca5c`|20043035|
| `bluedotOFF-1.0.0-test.7-source.zip` | 별도 `delivery-audit-1.0.0-test.7.json`에 기록 | ZIP audit 참조 |

Debug signer SHA256 `3b3b208f92d3d437acf30c333d9a7b5b0bc8f535056e18edd82df2701db2be0c`, release signer `619fe1ecbdf59d5d0b838ddc6420e58be90f828be11cb61fe6c8e6aa4ceaad62`. 기존 인증서와 동일하다. Fold7 설치 base.apk SHA256도 debug 배포본과 정확히 같다. Release는 PC에서 서명·빌드·단위검사·lint·alignment를 검증했고 휴대폰의 debug 데이터를 지우는 release 전환은 하지 않았다.

Source ZIP은 현재 main/local/core/test/native vendor/Gradle wrapper/문서/선별 offline audit scripts만 포함한다. `.private`, keystore, pairing key/code, local.properties, research, build/.cxx/.gradle cache 및 과거 기기 실험용 mutator script는 제외한다. 원래 release signing private key는 제공하지 않는다. APK build/safety/device/source delivery audit JSON을 `artifacts`에 별도로 제공한다.

## 최종 상태와 제한

최종9개 metadata 대조 시점은 `2026-10-04T14:41:50.792748Z`이며 KST23:41:50이다. 카카오맵 FINE0x300/COARSE0x300/NOT_APPLIED/disabled/originalBit256, NAVER unmanaged FINE0x200, 기존6 enabled은 fresh APPLIED와 기존 OFF/originalBit256을 유지한다. 날씨날씨 FINE0x80301도 unchanged다. Force-stop/relaunch 후 Home은 **6개 앱 · 현재 상태 확인 완료**이며 cached journal만으로 판정하지 않았다. Boot_count54, developer options1, Wireless Debugging1, 충전 화면 유지7, Shizuku server/helper 모두 부재다. 페어링은 보존하고 무선 디버깅은 사용자가 선택한 ON 그대로 둔다.

실기기에서 확인한 것은 initial pairing, 저장 키 reconnect, 동일 boot 앱 restart, Wireless OFF→ON, UID/context/ABI/History/getters, actual Hide/Reapply/Restore, fresh 위치 접근, 표시 A/B/A 및 getter 소켓 중단이다. 실제 reboot/기기 update/revoke/wrong pairing code/notification denial는 이번 단계에서 반복하지 않았다. Reboot/update/policy의 fail-closed 재적용 state, expired reply, envelope corruption 및 after-write loss는 자동/model 검증 범위다. 앱이 Wireless Debugging을 자동 OFF하는 제품 기능은 넣지 않았으며 Settings.Global 읽기 결과만으로 Knox/OS timing까지 보장하지 않는다. 기기 내부 연결만 사용하지만 INTERNET/근처 기기 권한의 OS 설명은 넓은 범위로 표시될 수 있다.

현재 firmware 외 모든 OneUI9/Android17 기기에 동작한다고 확대하지 않는다. 관리 대상을 원복하지 않고 앱을 삭제하거나 데이터를 지우면 자동 원복은 보장하지 않는다. OS가0x100을 재계산할 수 있으며 정확한 영구 유지 시간은 미검증이다. 지원 profile/소유/permissions/AppOps/identity가 달라지면 쓰기를 차단하고 사용자가 원인을 확인해야 한다.
