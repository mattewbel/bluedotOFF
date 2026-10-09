# bluedotOFF Phase 7.4 — 1.0.0-test.11

2026-10-06. 사용자 요청: 약 70개 적용의 진행 표시·완료 안내·안전한 속도 개선, 자동 무선 디버깅 종료 문제, 홈의 등록 수/상태/연결 문구, 시스템 언어에 따른 영어 지원. 실기기 확인은 코드·자동 검사·배포 파일 확인 다음 마지막 단계로 둔다.

## 변경

- 연결 준비 → 앱별 확인 → 결과 저장/연결 종료 단계를 표시한다. 실제 완료한 항목 수와 총 수, 퍼센트, 진행 막대를 보여준다. 마지막 앱을 확인한 뒤에도 연결 정리 전에는 최대 99%다. 끝난 뒤 성공/확인 필요 수를 화면과 알림 막대에 표시한다. 자동 OFF 실패는 성공한 위치 작업과 별도의 경고 및 무선 디버깅 열기 버튼으로 남긴다.
- 대량 적용을 최대 12개 또는 작업 55초마다 새로운 helper 연결로 나눈다. 제한은 앱과 앱 사이에서 확인하며 이미 시작한 안전성 검사/복원을 자르지 않는다. 각 묶음의 journal 확정 → helper 정상 EXIT0 → socket close → 일반 앱 OFF를 유지하고 다음 묶음은 명시적 사용자 작업에 속한 새 foreground lease로 시작한다. helper의 120초/idle10초 watchdog을 늘리거나 제거하지 않았다. 안전성 검토가 필요한 실패의 stop latch는 연결이 바뀌어도 유지한다.
- SafetyEngine이 이미 수행하는 fresh preflight/쓰기 직전/즉시/500ms/1500ms 조회를 모두 보존한다. UI용 중복 첫 조회와 종료 후 모든 등록 앱을 다시 읽는 반복을 제거한다. 표시에는 SafetyEngine의 1500ms 확인과 durable 결과를 사용하며 마지막 확인 기준임을 명시한다. 실제 다음 변경에 이 표시 snapshot을 사용하지 않는다. 원래 비트/journal 형식/rollback/singleton gate는 그대로다.
- 작업 소유권과 진행 결과를 앱 메모리에 유지해 Activity 재생성 때문에 중복 실행하거나 진행 결과가 사라지는 일을 줄인다. 별도 서비스/부팅 실행/자동 재시도는 추가하지 않았다. 작업 화면이 보일 때 화면을 켜 둔다. 화면을 떠나면 현재 앱의 atomic 처리를 마치고 다음 대상은 변경하지 않으며 기존 완료 결과/원복 기록을 보존한다. 강제 종료/OS process kill에도 자동 OFF를 보장하는 기능은 아니다.
- 홈의 등록 제목과 목록 끝에 현재 활성 등록 앱 수를 표시한다. 정상 APPLIED 기록이 무선 연결 종료만으로 ‘현재 상태 확인 필요’가 되지 않으며 ‘숨김 적용 완료 · 마지막 확인 기준’으로 표시한다. 실제 재부팅/업데이트/정책 변경/불확실한 상태는 계속 재확인/재적용으로 구분한다. 성공 상태의 그림도 마지막 확인 결과와 일치시킨다.
- 홈의 연결 설명은 ‘설정 변경 연결’, ‘설정 변경 준비됨’, 또는 ‘작업할 때 자동 연결 · 지금은 연결 종료됨’으로 바꾼다. 자동 관리가 준비된 정상 OFF를 오류 안내로 표시하지 않는다. 연결은 기기 내부 설정을 변경하거나 다시 읽는 데 필요하다.
- 시스템 언어가 한국어면 한국어, 영어 또는 그 밖의 언어면 영어를 사용한다. 화면·버튼·표준 상태/실패 사유·완료 알림·페어링/재적용 알림·합성 설정 안내 그림을 번역한다. 앱 이름과 저장된 journal/state/UID/package/IPC 값은 바꾸지 않는다. 알려지지 않은 진단 오류는 숨기거나 성공 문구로 치환하지 않고 원문을 유지한다. 언어 선택은 현재 Context configuration을 읽는다.

## 원인과 속도 한계

`MinimalPrivilegedHelper.main`의 watchdog은 시작 후 120000ms 또는 마지막 요청 뒤 10000ms를 넘으면 exit70으로 종료한다. 기존 `MainActivity.perform`은 모든 선택 앱을 한 helper에서 처리한다. SafetyEngine의 필수 대기만 70×1.5초=105초이고 조회·서명/소유 확인·durable 저장·연결 준비 시간이 더해지므로 제한을 넘길 수 있다. 비정상 종료에서는 normal EXIT0 receipt를 얻지 못해 `WirelessWork`가 자동 OFF를 보류한다. 이 코드 경로는 대량 적용/무선 디버깅 잔류를 설명할 수 있지만 사용자의 실제 실패 로그와 일치하는지는 실기기 읽기 전까지 추정으로 둔다.

최적화는 중복 조회 제거와 bounded 연결이다. 500ms/1500ms 대기를 줄이거나 안전 검사를 병렬화하지 않았고, 새 연결마다 필요한 인증/ABI 검증 비용은 남는다. 실제 몇 초 또는 몇 % 빨라졌다는 숫자는 실기기 비교 전에는 제시하지 않는다.

## ACTUALLY TESTED

로컬 컴파일, 기존 workflow의 core/debug/release 검사, 두 APK 빌드, UI 렌더링을 수행했다. 최종 실행 515회(core227/debug144/release144), 실패·오류·건너뛰기 0. lint는 양쪽 모두 오류0/경고22. 두 APK의 기존 debug/release signer와 ZIP/native16KiB 정렬·고정 permission/컴포넌트를 검증했다. 자세한 결과는 `build-audit-1.0.0-test.11.json`에 기록한다. 한국어/영어/큰 글씨 진행 화면과 영어 완료/오프라인 홈의 native Compose 결과를 눈으로 검사했다. 원래 test.10 APK/source ZIP/진행 보고서는 `baselines/test10-before-batch-ux`에 보존했다. source ZIP baseline SHA256은 `243bb8c6b633598227ca09f062928ced1ce4ef7e9334c2befee22a8374eba5ab`이다.

## AUTOMATED / MODEL TESTED

70개 virtual target 시험에서 실제 SafetyEngine을 사용해 각 대상의 pending journal, originalBit256, fresh getter/checked setter, 500ms 및 추가 1000ms 대기, settled APPLIED를 확인한다. 전체 가상 시간은 120초보다 길지만 개별 연결 묶음은 제한보다 짧다. 이것은 물리적 기기 속도 측정이 아니다. 종료 중 70/70은 99%, 완료 후 100%; 연결을 바꾸어도 소유 불확실 실패 뒤의 대상은 실행하지 않음을 검사한다.

한 owner만 실행되고 종료 전 progress/outcome이 덮어써지지 않으며 별도 OFF 경고가 target 오류로 바뀌지 않는 모델 검사도 수행한다. 한국어/영어 configuration 전환, dynamic 개수, 알 수 없는 오류 보존, 영어 알림의 RemoteInput schema와 비밀 표시 정책, 모든 안내 그림의 영어 표기, 홈의 마지막 확인 상태/재부팅 구분을 검사한다.

보존 감사는 SafetyEngine/Reconcile, narrow setter, JournalStore/originalBit, fresh Binder getter/checked setter, singleton, crypto/native 및 boot 정책이 test.9 baseline과 동일함을 확인한다. 새 permission·exported service·IPC writer·범용 설정 writer는 없다.

## INFERRED

120초 helper 종료는 기존 대량 처리 실패와 OFF 보류의 유력한 원인이다. 중복 조회 제거는 통신/조회 작업량을 줄이지만 전체 소요 시간이 반드시 일정 비율 감소한다고 추정하지 않는다. 화면을 떠난 cached process의 작업은 계속 보장되지 않는다는 Android 정책도 고려한다. [Android process lifecycle](https://developer.android.com/guide/components/activities/process-lifecycle), [AOSP cached apps freezer](https://source.android.com/docs/core/perf/cached-apps-freezer).

## NOT TESTED / 마지막 실기기 단계

70개 실제 새 적용의 시간·진행 표시·각 연결 정상 종료/최종 OFF, 실제 표시 OFF/원복 ON, 실기기 시스템 언어 전환 및 화면 회전은 아직 완료하지 않았다. 기존 70개 관리 앱을 임의로 모두 원복/재적용하거나 데이터를 지우지 않는다. 이 단계의 승인/물리적 확인은 소프트웨어 검증이 끝난 뒤 요청한다. 과거 Phase7.3의 미완료 재부팅/WSS-data-clear 검사는 별도 미완료로 유지하며 이번 작업 완료로 가장하지 않는다.


실기기 마지막 단계에서 USB ADB `get-state`만 실행했으며 현재 Fold7이 목록에 없었다. test.11 설치·실제 batch 재적용/복원·system-setting write·permission metadata write는 이번 작업에서 0회다. 기존 사용자가 적용한 약70개 상태와 journal은 변경하지 않았다. 기기가 다시 연결되면 먼저 기존 실패/정상 종료 로그와 전체 journal/target 신원을 읽기 전용으로 보존하고, 같은 signer 업데이트로 기록 유지를 확인한다. 실제 metadata 변경을 수반하는 새로운 시험은 별도 사용자 확인 뒤 진행한다.


## 2026-10-06 실제 Fold7 검사 추가

원래 test.11 업데이트에서 등록/페어링 보존과 단독 읽기 후 자동 OFF는 확인됐지만, 영어에서 Android ICU 정규식 오류가 발생했고 대량 재적용은 12/74개 후 빠른 OFF→ON에 의한 이전 NSD 포트 사용으로 중단됐다. 원본 APK·소스 ZIP은 보존했다. 두 문제를 test.12에서 수정하고 74개(약 2분 10초) 완료·7개 정상 종료·자동 OFF·한국어/영어 화면·카카오맵 ON/OFF를 실제로 검증했다. 뒤에 OS가 카카오맵/메시지의 표시 민감도를 재계산해 다시 켠 제한도 관찰했다. 최종 카카오맵은 수동 재적용해 OFF이며, 메시지는 재적용 필요, 기존 여우비 복원 확인 기록은 보호 상태다. 자세한 완료 결과와 검증하지 않은 항목은 [test.12 실기기 보고서](Phase-7.5-report.md)에 기록했다.
