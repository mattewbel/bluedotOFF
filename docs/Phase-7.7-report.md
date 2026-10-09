# bluedotOFF test.14 — 대량 적용 확인 대기 겹침

2026-10-06. 사용자 요청: 74개 적용의 긴 대기 시간을 줄여 보는 구현을 진행하고, 휴대폰을 분리하므로 **설치·실기기 검증은 마지막으로 미룬다**. 2026-10-06에는 구현·자동 검사·APK/소스 배포 준비까지만 진행했다. 2026-10-07 사용자 요청으로 아래 실기기 검증을 추가 완료했다.

## 결과와 실측 구분

- `1.0.0-test.14`, versionCode14의 debug/release APK를 빌드했다. 기존 서명을 각각 유지한다. 2026-10-07 같은 debug signer로 데이터를 유지한 채 Fold7에 업데이트 설치했다.
- 두 개 이상 숨기기/재적용에서는 최대 4개 앱의 확인 대기를 겹친다. 실제 setter/getter는 **한 worker에서 순서대로** 처리한다. 단일 앱과 Restore는 순차 경로를 유지한다.
- 앱별 즉시 확인 → 최소 500ms 대기 후 확인 → 추가 최소 1000ms 대기 후 확인을 유지한다. due가 지난 읽기를 새 앱 준비보다 먼저 수행한다. 한 앱의 대기 동안 다른 독립 UID 앱을 준비해 총 대기를 줄인다.
- 전체 자동 검사 **611개 통과**: core257 + localDebug177 + localRelease177, failure/error/skip0. 양쪽 lint 오류0/경고26으로 test.13과 같은 경고 개수다.
- 74개 가상 비교: getter10ms, journal commit3ms, setter/profile/연결 시간0의 동일 조건에서 기존 순차 실행 **115884ms**, 대기 겹침 실행 **30805ms**. 최대 pending4, 7개 window, 각 앱의 최소 대기와 최종 durable 성공을 검사했다. **이것은 실제 Fold7 속도가 아니다.**
- 기존 test.13에서 읽었던 74개 적용 로그의 helper 처리시간 합계127322ms와 이번 가상30.8초를 직접 나눠 실기기 개선 배율로 주장하지 않는다. 실제 NSD·TLS·helper 시작·fsync·CPU·Android 부하·새로 쓰는 앱 비율에 따라 달라진다.
- 2026-10-06 개발 단계의 ADB/실기기 조작·metadata write·시스템 설정 write·권한 변경·설치·앱 초기화는 모두0회였다. 2026-10-07 검증 단계의 실제 변경 횟수는 아래에 구분한다.

## 변경 구조

`SafetyEngine`에서 기존 준비 검사, 최종 확인, 실패 시 좁은 원복을 공통 함수로 분리했다. 기존 준비 조건은 그대로이며, 진행 중인 앱과 UID가 겹치는 다음 앱은 추가로 차단한다. 새로운 `userHideWindow`는 전역 작업 lock을 전체 window 동안 보유한다. 여러 thread/connection으로 setter를 병렬 실행하지 않는다.

각 앱은 다음 순서를 따른다.

1. 검증된 firmware/ABI, 최신 설치 신원·UID peers·공유 namespace·권한·AppOps·기존 복원 기록을 확인한다.
2. originalBit와 invariantDigest가 있는 PENDING_HIDE를 기존 JournalStore로 fsync/atomic rename/정확한 reread한 뒤에만 쓰기를 허용한다.
3. durable 저장 뒤 fresh preflight와 기존 Local ADB/helper guard를 거친다. 화면 이탈도 실제 setter 직전에 한 번 더 확인한다.
4. FINE mask0x100의 기존 typed setter를 사용하거나 이미 원하는 값이면 쓰지 않는다. 즉시 읽기 확인이 끝난 앱만 대기열에 넣는다.
5. 기존 500ms/추가1000ms 대기와 동일한 invariant 검사를 각각 실행한다. 다른 앱 준비 시간이 들어가 검사가 더 늦어질 수 있지만 대기를 짧게 하지 않는다.
6. 최종 journal commit이 성공한 다음에만 APPLIED/settlingVerified를 결과와 진행 개수로 내보낸다.

MainActivity의 기존 사용자 조작 `perform` 안에서 다중 Hide에만 연결한다. UI에서 선택한 순서를 기록하며, 완료 callback은 실제로 정리된 대상 수를 진행률에 반영한다. 100%는 기존과 같이 결과/기록/연결 종료 뒤에만 표시한다. 단일 Hide와 모든 Restore의 순차 대기는 유지한다.

한 helper의 최대12개, 새 앱 admission55초 budget, helper watchdog120초/idle10초, 정상 helper EXIT 및 socket close receipt, 최종 작업 종료 뒤 무선 디버깅 OFF 정책은 그대로다. 시간 budget이 지나면 새 앱을 넣지 않고 이미 시작한 최대4개를 정리한다. 하나의 IO 호출 자체는 도중 강제 중단하지 않는다. 호출 지연/연결 손실로 종료가 불확실하면 기존 cleanup 안내와 다음 helper 차단을 유지한다.

## 실패·중단 처리

- 어떤 앱이든 실패하면 새 앱 admission을 중지한다. 이미 시작한 앱은 각각 확인하거나 기존 안전 원복 경로로 정리한다. 성공한 다른 앱을 한꺼번에 원복하지 않는다.
- UID peers/공유 namespace가 변경된 사실을 관찰하면 해당 앱은 NEEDS_REVIEW이며, 이후 peers가 원래대로 보이더라도 blind rollback을 하지 않는다. 설치 신원이 바뀐 앱에도 과거 originalBit를 쓰지 않는다.
- 복원은 fresh getter와 변하지 않은 typed helper guard를 통과해야 하며 FINE0x100만 사용한다. 권한·다른 flags·AppOps·다른 앱 상태는 원복 대상으로 삼지 않는다.
- 연결 손실은 후속 앱/자동 쓰기 재시도로 이어지지 않는다. 시작한 각 앱의 원래 값과 실패/검토 상태를 보존한다.
- 화면을 떠나면 새 앱을 시작하지 않고 이미 시작한 사용자 작업만 정리한다. background daemon/timer/boot 자동 적용을 추가하지 않았다.
- journal read 손상, commit false 또는 예외는 공통 JournalPersistenceException으로 처리한다. 저장 실패 뒤에는 대기 중인 다른 앱에 대해서도 새 getter/setter/원복을 실행하지 않고 durable PENDING 증거를 남긴다. final commit 실패를 성공으로 표시하거나 실패한 저장 파일을 정상 파일로 덮어쓰지 않는다. 기존 순차 Runner도 저장 실패 뒤 다음 앱을 실행하지 않도록 강화했다.
- 프로세스가 종료되면 이미 쓰기 전에 저장한 최대4개의 원래 값이 남는다. 기존 readonly Reconcile과 boot/update 처리는 byte-identical이며 재실행/재부팅 자체로 setter를 호출하지 않는다.

프로토콜·journal schema·Entry/Identity/Snapshot 정의·LocalWriteGuard·Eligibility·helper allowlist·암호화/키·페어링·History/Picker·자동 관리 동의와 무선 종료 정책·UI layout·permission manifest는 변경하지 않았다. 영어에는 새 기록 저장 실패 문구만 추가했다. 기존 번역은 모두 보존했다.

## 자동 검사와 검토

core 신규27개는 ON/OFF74개, 원래 값 보존 및 Restore, 각 확인 시점, 최대4개와 helper count/time budget, delayed OS reset, 다른 flags/grant/AppOps drift, transient shared UID, 동일 in-flight UID, 재설치/서명 교체, ambiguous setter reply, 연결 끊김, journal 초기/중간/최종 실패와 예외/손상, wait 중단, wait 중단+저장 실패의 결합, callback 실패, 화면 이탈, 전역 owner lock, 중복/초과 입력을 검사한다. 실패 주입의 fake port는 실제 LocalWriteGuard를 사용한다.

app 신규5개는 **실제 JournalStore 구현**을 Robolectric SDK34에서 사용한다. 74개·7개 orderly closure·최종 진행률, 4개 PENDING 상태에서 process death/relaunch readonly reconcile, 화면 이탈 후 부분 결과, 저장 파일 checksum 손상, 연결 손실을 검사한다. 이 검사들은 파일의 fsync/atomic rename/reread와 저장 schema를 통과하지만 Android17/Fold7 Binder·무선 동작 실측은 아니다. 기존 core230 및 app 양쪽172개의 회귀 검사도 모두 통과했다.

개발 중 직접 검토에서 wait 중단 뒤 정리하다 저장 실패가 발생하는 결합 경로를 발견해, 이후 남은 앱의 원복 setter를 막는 처리를 추가하고 회귀 검사했다. 최종 전체 빌드/검사 뒤 소스 diff와 보호 파일을 다시 대조했다. test.13 source ZIP을 기준으로 기존7개 파일만 변경됐고883개 기존 파일은 보존됐다. 신규 production entry point/permission/service/dependency는 없다.

안티그래비티에는 기기 정보/비밀을 제외한 코드의 별도 검토를 준비했으나, **자동 승인 검토가 비공개 소스의 외부 전송에 대한 구체적 승인 부족으로 실행을 거부했다**. 실제 CLI 전송/검토는 실행되지 않았고 재시도/우회하지 않았다. 독립 검토 통과로 주장하지 않는다. Codex의 직접 코드 검토·자동 회귀·정적 감사 결과만 기록한다.

정적 감사와 테스트는 실제 firmware 동작의 형식적 안전 증명이 아니다. test.14는 아래 Fold7 검증을 통과한 테스트 빌드이며 영구 OFF나 다른 firmware 호환성을 보장하지 않는다.

## 배포와 마지막 실기기 절차

이전 정상 test.13 APK2개/source ZIP/실기기·배포 감사는 `baselines/test13-before-overlapped-checks`에 보존했다. baseline source SHA256은 `30b9e390f017c2465eaff2b07013493f19df0041c6928da0efd61c14ac1eaf9f`이다. 이번 APK SHA256:

- debug: `d938f47a62fb2428bb3bf2d8a657ee3bb35d11ced8addaa8a1f2ffc08715d854`
- release: `6f535a00e9ff1de2a0a7f2889d61dbe03fcd43d7806975bb43aeaa3971cbe06c`

기존 debug/release signer, APK ZIP16KiB 및 native ELF16KiB 정렬, LICENSE/NOTICE assets, 기존 permission8개/shortService1개 구성 검사 통과. 상세는 build-audit/safety-audit/source-audit/device-validation/delivery-audit JSON과 test.13→14 review diff에 둔다. 소스 ZIP은 device dumps·개인 자료·원래 journal·pairing code·키·서명 비밀·빌드 캐시·도구용 mutation script를 포함하지 않는다.

다음 절차는 개발 단계에서 정한 실기기 검증 계획이었다. 2026-10-07 실행 결과와 남은 범위를 아래에 기록한다.

1. 기존 journal·originalBit·설치/UID·실패 상태를 읽기 전용으로 보존하고 pending 작업이 없는지 확인한다. 키 내용은 export하지 않는다.
2. 같은 debug signer로 update 설치해 등록/복원 기록/페어링 유지 여부를 확인한다. 초기화/삭제는 하지 않는다.
3. 작은 Hide 묶음부터 결과/즉시·500ms·1500ms flags/권한/AppOps/원래 값/정상 helper 종료를 확인한다. 실제 metadata 변경을 수반하는 대상/원복 범위를 구체적으로 확인한 뒤 시행한다.
4. 기존처럼 74개 대량 처리의 wall time·개별 체크 간격·최대 in-flight·모든 window drain/closure·개수/퍼센트/완료·최종 wireless OFF를 측정한다. 이미 OFF인 reapply와 새 ON→OFF 비율을 기록해 속도 비교를 구분한다.
5. 화면 이탈/재진입·단일 Hide/Restore 회귀·사용자 육안 표시와 위치 기능 유지도 확인한다. 프로세스 강제 종료나 시스템/사용자 데이터 변경을 수반하는 별도 실험은 필요할 때 사전 승인을 받는다.

개발 단계에서는 실기기 절차를 미뤘으며, 이후 2026-10-07 아래 범위를 실행했다.


## 2026-10-07 Fold7 실기기 결과

**test.14 debug 업데이트 설치, 74개 재적용, 진행률·완료 안내, 정상 helper 종료·자동 wireless OFF, 화면 이탈 정리, 단일 경로와 카카오맵 실제 OFF 확인 완료. OS의 표시 민감도 재분류 제한도 재현했다.** production 코드는 추가 변경하지 않았고, 설치 APK hash는 위 빌드와 동일하다.

기존 등록 74개·원래 값·UID/서명/최초 설치 기준과 페어링 암호화 파일 hash를 보존했다. 초기화/삭제/Forget/권한 grant·revoke/AppOps mode 변경은0회다. 충전 중 화면 유지7은 이미 켜져 있었고 PC가 이 설정을 다시 쓰지는 않았다. 시작 당시 72개는 OFF였고 메시지와 Google 지도2개는 ON이었다. 사용자에게 이 실제 변경 범위를 확인받고 기존 74개를 재적용했다.

| 검증 | 실기기 결과 |
|---|---|
| 74개 사용자 작업 wireless ON→최종 OFF | **43.372초** |
| helper 처리시간 합계 | **42.442초**, 그중 시작/profile4.229초 |
| 마지막 위치 검증까지 metadata 구간 | 40.940초 |
| 버튼→완료 화면 host 관찰 | 49.250초 이내; 약6초 간격 UI polling이므로 정확한 완료 시각이 아님 |
| helper별 완료 수 | 12,12,12,12,12,12,2 — 모두 정상 EXIT/socket close |
| 동시 미완료 확인 | 최대4개, setter/getter는 순차 |
| 즉시→500ms 최소 관찰 간격 | 506ms |
| 500ms→최종 최소 관찰 간격 | 1006ms |
| UI 진행률 | 8/74·10%, 16/74·21%, 28/74·37%, 40/74·54%, 52/74·70%, 60/74·81%, 72/74·97% 관찰 |
| 완료 안내 | “74개 앱의 Blue Dot 숨김을 적용했습니다.” |
| 전체 불변 조건 | 74개 UID/peers/공유 namespace/버전/위치 grant/raw AppOps/COARSE/0x100 외 FINE 비트 유지 |
| 대량 작업 setter | FINE mask256/value0 정확히2회, 메시지·Google 지도만 |

이전 test.13의 같은74개 helper 합계127.322초와 비교하면 이번42.442초는 약3분의1이다. 다만 이전과 이번은 동일한 제어 실험이 아니며, 이번에는 이미OFF72개·ON→OFF2개였다. 모든74개가 처음ON인 경우나 다른 기기 속도로 일반화하지 않는다. 가상 시간30.805초와도 구분한다.

첫 시도는 Android 네트워크 허용 창 대기 중 연결 발견 시간이 끝나 대상 작업을 시작하지 못했다. 대상 setter0회, 기존 기록 보존, 불확실한 종료 안내가 나타났다. 사용자가 신뢰하는 네트워크 허용을 완료한 뒤 별도 재시험에서 위 결과를 얻었다. 이 허용 대기 시간은 적용 속도에 포함하지 않았다.

### 화면 이탈과 단일 경로

이미OFF 상태의 같은 기존 대상을 재확인하다 PENDING4개인 시점에 홈으로 나갔다. 새 대상은 시작하지 않았고 시작한4개만 최종 확인/저장을 마쳤다. 70개는 미처리로 보고하고 등록/원래 기록은 유지했다. 이 시험 setter0회, 적용 helper 정상 종료 및 wireless OFF receipt를 확인했다. 재진입 시 읽기 전용 조회를 위한 잠깐의 ON/OFF는 별도 작업이다.

카카오맵의 originalBit는 데이터 초기화 후 등록 시부터0이었다. 단일 Restore는 원래OFF를 유지하며 등록 해제, 단일 Hide는 재등록했다. 두 경로 모두 세 단계 검증/정상 종료/완료 문구를 확인했고 setter0회였다. **실제 ON 값으로 쓰는 Restore 시험으로 주장하지 않는다.** 이후 마지막 재적용은 실제 mask256/value0 setter1회와 세 단계 검증을 확인했다.

### OS 재분류 재현과 최종 실제 표시

카카오맵의 단일 재등록 최종 검증 당시 FINE은0x200이었으나, 이후 실제 위치 화면을 열고 읽자0x300으로 돌아왔고 사용자는 두 표시가 보인다고 답했다. 해당 사이 bluedotOFF의 ON setter는 없었다. 같은 UID10502에 대해 `PermissionControllerServiceImpl: Updating user sensitive` 로그가 최종 OFF 검증 이후 발생한 것을 확인했다. 기존 Phase6.3에서 실제 설치된 PermissionController의 mask0x300 sensitivity 재계산 경로를 분석한 증거와 일치한다. **이번 upstream trigger/주기 전체를 확정하지는 않았다.**

앱 재조회는 이를 NEEDS_REAPPLY_POLICY와 “표시 설정 변경 · 재적용 필요”로 표시했다. 승인된 기존 대상 범위에서 카카오맵 하나를 명시적으로 재적용했다. 첫 추가 시도는 Codex가 위치 화면으로 너무 빨리 이동해 대상 작업 시작 전에 안전 중단됐고 setter0회였다. 다음 시도에서는 완료/종료를 기다려 실제OFF 쓰기와 최종 검증을 마쳤다. 이후 카카오맵에서 새 FINE 위치 접근이 발생했고, 사용자가 **“둘 다 사라짐”**을 확인했다.

최종 조회 시74개 모두 FINE0x100 OFF, APPLIED/settlingVerified, pending0이며 페어링 hash와 원래 값 기록은 보존됐다. 카카오맵의 위치 grant/AppOps/다른 flags도 동일했다. wireless는OFF, 충전 중 화면 유지7이다. **일시 검증 결과이며 OS가 이후 다시 켤 가능성은 남는다.** 반복 자동 재쓰기나 영구OFF 보장은 추가하지 않았다.

이번 실제 대상 metadata setter는 총3회다: 대량2회와 카카오맵 수동 재적용1회. 전부 FINE mask0x100/value0다. 앱의 시스템 설정 쓰기 로그는24회이고 키는 adb_wifi_enabled만이다. 연결 확인·실패 복구·재진입·시험의 ON/OFF를 합한 횟수이며 grant/revoke/다른 전역 설정 쓰기는 아니다. 개인 기기 로그·journal·키·네트워크 식별 정보는 공개 소스/감사에 포함하지 않는다.

### 최초 호환성 검사에 대한 정확한 설명

환영 화면보다 먼저 전체 검사를 끝내는 구조는 아니다. `MainActivity.refresh`는 환영 안내 뒤 삼성/SDK37 이상을 기본 검사하고, `AndroidSetupRuntime.supported`도 같은 조건이다. 실제 기기는 samsung/SDK37/OneUI90000였다. OneUI9 이상 조건은 `FirmwareSupport.setup`이며 **Local ADB 연결 후** helper HEALTH/PROFILE에서 framework의 메서드/transaction, History, SystemUI 표시 경로, 지역 정책과 shell 실행 신원을 읽기 전용으로 검증한다. 기본 버전 조건만 맞는다고 즉시 적용을 허용하지 않는다.

`SafetyEngine.prepare`는 각 명시적 작업에 firmware 지원/profile 및 대상 신원·UID/권한/AppOps/원복 기록을 확인하며, 실제 setter의 helper guard도 통과해야 한다. 정밀 호환성 검사 통과 전에는 대상 metadata 쓰기를 시작할 수 없다. **“맨 첫 화면에서 이미 OneUI/전체 호환성 확인 완료”라는 설명은 부정확하다.** 이번에는 호환성 순서를 바꾸는 production 변경은 하지 않았다.

새 초기화/페어링/재부팅/release 실기기 실행/강제 프로세스 종료/전체74개ON/실제ON Restore/다른 기기·firmware는 이번에 다시 시험하지 않았다. 기존 자동 검사611개 결과와 이번 실제 기기 결과를 구분한다. 상세 집계는 `device-validation-1.0.0-test.14.json`에 기록했다.
