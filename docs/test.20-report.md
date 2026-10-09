# test.20 — 홈 시스템 앱 확인 수정

2026-10-09. 기준 로컬 커밋 `9dd41da`(test.19 실기기 검증 완료).

## 원인과 수정

홈의 `MainActivity.markLocalEvents()`는 일반 앱 UID에서 `PackageManager.getPackageInfo(GET_SIGNING_CERTIFICATES)`를 호출한다. 현재 manifest 조회 선언은 설정 패키지와 MAIN/LAUNCHER intent이며, 일반 앱에 보이지 않는 내부 패키지가 있다. NameNotFoundException을 SHIZUKU_REQUIRED로 저장하면서, Local ADB의 검증된 APPLIED 상태도 다시 덮어썼다. 실제 표시 비트 OFF와 홈의 ‘연결 후 확인 필요’가 일치하지 않는 원인이다.

[Android의 package visibility](https://developer.android.com/training/package-visibility)와 [조회 선언](https://developer.android.com/training/package-visibility/declaring)을 대조했고, 설치된 test.19의 조회 선언도 읽었다. 해당 8개는 PC shell의 user0 설치 목록에 존재했다. 특정 패키지 이름을 하드코딩하거나 전체 앱 조회 권한을 추가하지 않았다.

`LocalEntryEvents`로 기존 초기 이벤트 판정을 분리했다. 일반 조회에서 보이지 않으면 기존 기록을 그대로 유지한다. 새로 APPLIED로 만들어 주거나 ON/OFF를 추정하지 않는다. 기존 Local ADB `backend.read`와 `Reconcile`/`SharedGroupReconcile`이 전체 설치·UID·권한·표시 비트를 확인한 결과가 상태를 결정한다. 연결이 없으면 ‘마지막 확인 기준’이라는 기존 캐시 표시를 유지한다. 과거 SHIZUKU_REQUIRED 기록도 실제 backend 검사를 통과한 경우에만 회복된다.

재부팅·펌웨어 변경, 실제로 확인한 UID/서명/최초 설치 변경, 앱 업데이트 판정은 그대로다. 중단 작업·복원 실패·공유 UID 검토 기록은 그대로 보호한다. 실제 삭제 여부는 일반 조회 실패만으로 결정하지 않고 authoritative backend 결과를 사용한다. 실제 변경 작업의 설치 동일성 재검사는 유지된다.

홈은 완료 수·재적용 필요 수·그 밖의 상태 확인 필요 수를 구분해 표시한다. 실제 ON, 재부팅 또는 업데이트로 재적용이 필요하면 ‘일부 앱의 재적용이 필요해요’로 표시하며, 다른 검토 상태를 표시 ON이라고 표현하지 않는다. 영어와 한국어 문구를 추가했다.

## 변경 범위

권한·manifest 조회 범위·의존성·core 정책·Local ADB helper/Binder/reflection·FINE 0x100 setter·shared UID 적용/복원 엔진·지연/재검사·자동 무선 디버깅 코드는 변경하지 않았다. journal 형식과 원복 값도 변경하지 않는다. 이번 수정은 상태 판정과 화면 설명이며 자동 재적용을 추가하지 않았다.

## 검증

초기 회귀 검사에서 공유 UID 테스트 데이터의 구성원 목록이 기존 journal의 정렬 규칙을 따르지 않아 NEEDS_REVIEW가 발생했다. 테스트 데이터를 실제 저장 규칙대로 정렬했고 제품의 공유 UID 검사 조건은 완화하지 않았다. 검사 실패를 성공으로 간주하지 않았다. 최종 빌드 및 실기기 결과는 아래에 기록한다.

최종 core 304개(변경 없음으로 기존 결과 재사용) + local debug 234개 + local release 234개 = **772개 통과**, 오류·실패·skip 없음. debug/release의 전체 app 검사를 다시 실행했다. 추가 회귀 검사는 일반 조회 실패, 이전 확인 불가 기록의 authoritative 회복, 표시 ON 유지, 실제 삭제/교체/업데이트·부팅·펌웨어 변경, 보호된 중단/검토 상태, 핵심 공유 UID 전체 판정과 홈 개수 구분을 다룬다. 한국어·영어 다크 화면을 렌더링해 직접 확인했다.

두 APK 빌드 성공, lint 각각 **오류 0·기존 경고 35**. `scripts/finalize_test20.py`로 기존 서명·동일 manifest 권한·16KB native/ZIP 정렬·라이선스 자산·loopback/typed helper/단일 FINE mask256 setter를 확인했다. `artifacts/build-audit-1.0.0-test.20.json`과 debug/release APK를 생성했다. APK 크기는 test.19 대비 각각 88/96바이트 증가했다. 새로운 의존성은 없다.

## Fold7 실기기 결과

동일 서명의 debug test.19 → test.20을 `adb install -r`로 설치했다. 데이터·등록·페어링 초기화는 하지 않았다. 실제 기존 등록은 98개이며 설치 직후 journal은 설치 전과 바이트 단위로 같았다.

앱의 기존 자체 Local ADB 연결을 사용한 읽기 검사가 완료된 뒤, 홈에 **97개 숨김 적용 완료·1개 재적용 필요·그 밖의 상태 확인 필요 0개**가 표시됐다. 잘못 SHIZUKU_REQUIRED로 표시되던 8개는 실제 backend 검사를 통과해 APPLIED로 회복됐다. 홈으로 나갔다 앱으로 돌아온 뒤에도 검사가 완료되면 동일한 결과가 유지됐다. 실제 한국어·삼성 사용자 글꼴·다크 모드 화면과 UI 계층을 대조했다.

남은 토스는 `NEEDS_REAPPLY_POLICY`, 실제 FINE `0x300`으로 ON이므로 ‘재적용 필요’ 표시를 유지했다. 수정만으로 토스를 OFF로 바꾸거나 모든 앱이 완료됐다고 표시하지 않았다.

시험 전후 98개 앱의 FINE flags는 모두 동일하다. journal은 8개의 state 값만 SHIZUKU_REQUIRED→APPLIED로 바뀌었고, 원래 비트·UID/서명/설치 정보·구성원·활성 여부·검증 기록 등 그 밖의 필드는 모두 동일하다. 앱 범위·UI·무선 자동 관리·페어링 설정과 페어링 결과 기록도 동일하다. SystemUI PID도 동일하다. 감사 기록의 실제 target setter 이벤트는 **0개**다.

기존 동의된 자동 무선 디버깅 관리의 ON→OFF 2회는 모두 성공했고 최종 `adb_wifi_enabled=0`이다. PC의 직접 helper 호출도 PROFILE/GET_PERMISSION_FLAGS 읽기뿐이다. USB 디버깅·충전 중 화면 유지 설정은 이번 수정에서 변경하지 않았다.

개인 기기 화면과 패키지/journal 자료는 Git에서 제외된 `.private/test20-device/`에 보관했다. `verification.json`, `operation-audit.json` 및 화면 계층으로 대조했으며 개인 키·페어링 코드를 공개 자료에 넣지 않았다. 실제 OFF/ON 쓰기, 재부팅·앱 삭제·OS 업데이트는 이번 시험에서 실행하지 않았으며 관련 회귀 검사는 모델 검사다. 이 앱 이외의 기능 전체를 검증했다고 주장하지 않는다. 공개 push는 하지 않았다.
