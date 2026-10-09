# bluedotOFF test.15 — 기기·전체 앱 확인 통합

2026-10-07. 요청: 정밀 호환성 검사·기존 등록 확인 단계에 전체 앱 ON/OFF 읽기 스캔을 합치고, 홈과 앱 추가에서 그 결과를 재사용한다.

## 구현한 흐름

기본 호환성 확인 → 연결/페어링 → 정밀 firmware/profile 검사 → 전체 앱 ON/OFF 및 등록 상태 읽기 확인 → 선택적인 자동 관리 추천 → 홈이다. 기존 자동 관리 동의/권한 요청/페어링은 유지한다. VERIFYING 화면을 “기기와 앱을 확인하고 있어요”로 바꾸고 영어 문구도 추가했다.

`StartupScan.read`는 호환성 통과 후 기존 authoritative 전체 패키지/UID/AppOps/indicator 분류를 실행한다. 기존 full Snapshot 읽기에서 등록 앱 결과를 수집해 등록 Reconcile에 재사용한다. 전체 분류에서 읽지 않은 등록 앱만 별도 조회한다. 일반 Main 시작에서 전체 조회가 필요한 경우에도 같은 묶음을 사용한다. 기존 화면 내 목록 캐시가 유효한 경우의 등록 상태 새 조회는 유지한다.

OFF 앱은 계속 현재 표시 없음으로 표시한다. 새/초기화된 journal에 OFF 앱을 자동 등록하지 않고 과거 originalBit를 추정하지 않는다. 등록 Reconcile은 기존 구현을 사용하며 대상 권한 metadata를 쓰지 않는다.

## 조회 결과 전달과 무효화

조회가 끝나도 helper 정상 EXIT/socket close를 확인한 뒤에만 결과를 전달한다. 전달 결과는 같은 프로세스 안에서만 저장하며60초 미만/같은 firmware fingerprint/boot/정확히 같은 journal/패키지 변경 없음 조건에서1회 사용한다. 파일·백업·Intent에 저장하지 않는다. 실패·취소·만료·설치/삭제/업데이트·journal 변경은 이전 결과를 재사용하지 않는다. 조건 불일치는 기존 조회로 돌아간다.

Setup이 READY로 홈을 열 때만 결과를 유지해 session을 먼저 닫는다. 일반 취소와 session 종료는 결과를 폐기한다. 정상 전달 뒤 Activity의 두 번째 close가 결과를 지우지 않도록 했다. Setup 중 Main의 NSD 자동 조회가 같은 스캔을 겹쳐 시작하는 경로를 막고, 홈 전달 직후 초기 NSD 갱신도 전달 결과를 유지한다.

홈/앱 추가의 표시 데이터에만 사용하며 `freshPackages`로 승격하지 않는다. 실제 적용/복원은 패키지 이름을 기존 SafetyEngine에 전달하고 모든 fresh preflight/동일 narrow FINE setter/즉시·500ms·추가1000ms 검증을 다시 수행한다. 단일/대량 Hide/Restore 작업 코드는 cache invalidation1행 외 동일하다. journal schema·originalBit·SafetyEngine·Reconcile·Eligibility·helper/transport·무선 종료 정책·manifest/dependencies는 유지했다.

정밀 검사가 페어링 전에 가능해진 것은 아니다. 초기 기본 검사와 연결 후 정밀 검사 순서는 유지한다. OS가 읽기 후 표시 민감도를 다시 켤 수 있는 기존 제한도 그대로다. 동시 helper/socket/백그라운드 자동 재적용을 추가하지 않는다.

## 검사·배포 상태

최종 빌드/전체 회귀/실기기 상태는 아래 확정 결과와 build-audit/device-validation JSON에 기록한다. 조회 시간 자체가0이 되는 변경은 아니며, 같은 상태의 직접 비교 전 속도 개선 수치를 보장하지 않는다. 이전 test.14의 APK/debug/release/source/device/build/delivery 감사는 baselines/test14-before-combined-startup에 보존했다.


## 자동 검증과 변경 검토

핵심 로직 274개, Android debug 186개, release 186개로 총 646개의 자동 검사를 수행했다. 신규 검사는 호환성 확인 전 앱 조회 금지, 등록 앱의 중복 full read 제거, OFF 앱 자동 등록 금지, 원래 비트 보존, 삭제된 앱 처리, 조회 취소/실패, 한 번만 전달하는 캐시, 60초 만료, 부팅/firmware/journal/설치 앱 변경에 따른 무효화를 다룬다. 실제 JournalStore와 SetupSession을 사용하는 Android 모의 환경 검사도 포함한다. 물리 기기 검사는 별도로 기록한다.

최종 차이는 test.14 기준으로 검토했다. SafetyEngine/Domain/Eligibility/LocalWriteGuard/Discovery/JournalStore와 helper, Local ADB transport, 무선 관리 코드 등 17개 보호 파일은 동일하다. BinderBackend의 full read/실제 setter는 동일하며 적용/복원 경로에는 캐시 폐기 한 줄만 추가했다. 기존 번역은 보존했다.


## 최종 빌드·Fold7 읽기 전용 검증

2026-10-07 최종 Gradle 빌드 성공. debug/release APK 모두 생성·서명 검증·16KiB ZIP/native ELF 정렬 검증 통과. 자동 검사 646개 모두 통과(실패/오류/skip 0), lint 오류 0, 경고는 각 variant 28개다. 기존 debug/release 서명을 유지했다.

실제 Fold7에 test.14에서 test.15로 데이터 보존 업데이트했다. 등록 74개와 모든 원래 비트/UID/서명/설치 식별 기록, 자체 페어링 키의 해시, 자동 관리 동의를 보존했다. 충전 중 화면 유지 설정은 기존 7 상태다. 앱 cold start의 전체 조회도 성공했다. 이후 기존 페어링 상태에서 설정 → 연결 설정 열기를 실행해 통합 확인 후 홈 복귀 → 앱 추가를 검사했다. 이 후반 흐름의 helper는 정확히 1회였으며 홈과 앱 추가 진입 후에도 추가 helper가 없었다. 정상 EXIT/socket close를 확인했고 무선 디버깅은 최종 OFF다. 임시 5/5 화면은 짧게 지나가 XML에서 포착하지 못했으므로 그 화면의 물리 캡처 확인을 주장하지 않는다.

전체 분류에서 user0 설치 패키지 778개, UID 그룹 645개를 조사했다(전체 package names 837 중 APEX 58, user0 unavailable 1). 현재 표시 OFF인 독립·런처·위치 대상 73개도 목록에 유지된다. 전체 조회 elapsed 5585ms, 통합 확인 helper 전체 7073ms였으며 같은 조건의 이전 버전 대조 측정이 없어 속도 개선 비율은 주장하지 않는다.

대상 permission metadata setter 0회, grant/revoke 0회, 데이터 초기화/Forget/삭제 0회다. 기존 자동 관리 동의에 따른 무선 ON/OFF 2쌍만 실행했고 최종 OFF였다. 대표 5개 앱의 FINE/COARSE flags, grants, peers, raw modes는 전후 동일하다. 등록 상태는 APPLIED 73개, NEEDS_REAPPLY_POLICY 1개이고 pending 0개다. 카카오맵은 업데이트 전부터 FINE 0x300(ON)이었으므로 정확히 재적용 확인 대상으로 표시했고 이번 조회에서 자동 변경하지 않았다.

새 사용자/데이터 초기화 후 최초 페어링 및 자동 관리 추천 전체 실기기 흐름은 이번에 초기화하지 않아 재검사하지 않았다. 60초 만료·패키지 변경·취소 시 캐시 무효화는 자동 검사로 확인했고 물리 기기에서 강제로 재현하지 않았다. 개인 기기 증거는 .private 안에만 보존하고 배포 source ZIP에서 제외한다.
