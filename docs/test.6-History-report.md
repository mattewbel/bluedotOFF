# bluedotOFF test.6 — History display policy cleanup

완료: 2026-10-04. **1.0.0-test.6 / versionCode 6**을 Fold7에 기존 인증서로 업데이트 설치하고 읽기 전용 검증을 완료했다. 대상 permission metadata 쓰기 **0회**. Hide/Restore 동작 및 Phase 7 작업은 수행하지 않았다.

## 표시 정책

- 두 보기만 유지: **앱 기록 / 전체 위치 사용**.
- `HistoryDisplayReason`으로 기본 포함·제외 이유를 명시하고 `HistoryPolicy.relevant`는 그 표시 결정을 반환한다. SafetyEngine의 허용 여부나 APPLIED 상태를 새 boolean에 합치지 않는다.
- raw 접근은 실제 positive FINE/COARSE 이벤트만 인정한다. 0·음수 시각으로 기록을 만들지 않는다. 기존 AppOps parcel reader와 Binder discovery는 변경하지 않았다.
- 기본 기록은 user 0 일반 app UID, non-internal, 런처가 있거나 사용자 설치 앱, positive FINE history, 현재 NON_SENSITIVE가 아닌 항목을 유지한다. UNKNOWN 힌트의 사용자-facing 앱을 버리지 않되, 실제 파란 표시를 확인했다고 주장하지 않는다. 불명확한 런처 없는 사전설치 서비스는 기본 보기에 넣지 않는다.
- 이미 등록된 앱은 실제 positive 기록이 있는 경우 현재 0x100 OFF여도 유지한다. 등록 여부가 APPLIED 성공을 뜻하지 않으며, 기존 connected/fresh/settling 검증 상태를 그대로 사용한다.
- core UID, persistent/기존 보호 구성 요소, 런처 없는 사전설치 서비스, 일반 COARSE-only, 비관리 NON_SENSITIVE 항목은 전체 보기에 남는다. 기존 managed history 예외의 우선순위는 보존했다.
- FLAG_SYSTEM/UPDATED_SYSTEM_APP 자체는 제외 predicate가 아니다. 런처가 있는 사전설치 앱은 일반 사용자 앱처럼 포함될 수 있다.
- History 정책에 package별 blacklist나 이번 Settings 실측 14개를 맞추는 규칙을 추가하지 않았다. 기존 Safety의 핵심 구성 요소 분류는 그대로이며 새로운 표시용 package 예외는 없다.

## 데이터 출처와 Home

Samsung Settings의 권한 사용 현황은 삼성 PrivacyDashboard의 비공개 DB를 사용한다. non-root / Shizuku shell UID 2000은 같은 source를 읽을 수 없다. 이 앱의 기록 출처는 AppOps positive access이며 **Settings와 동일한 목록이라고 주장하지 않는다**. 이전 읽기 전용 추적은 `Settings-History-readonly-20261004.md`에 보존했다.

UI 설명과 도움말에서 사용자 앱 중심 정리 / 전체 위치 사용 / 기록 자체는 실제 Blue Dot 발생 증거가 아님을 안내한다. **raw history, 기본 보기 포함 여부, 숨김 APPLIED 상태**는 별개다. 0x100 OFF는 AppOps·Settings 기록 제거를 뜻하지 않는다.

Home은 `UiModel.recentHistoryApp → appHistory → HistoryPolicy.APPS`만 사용한다. History에서 전체 보기를 선택해도 Home source는 바뀌지 않는다. 실기기 Home 요약: 날씨 · 29분 전.

## 쓰기·안전 코드 보존

변경된 production Kotlin은 `Discovery.kt`의 positive 시각/표시 정책, `UiModel`의 Home 요약 getter, `BlueDotUi.kt` 설명/요약 표시뿐이다. 그 밖의 production Kotlin **9개 파일은 SHA-256이 변경 전과 동일**하다.

`SafetyEngine.kt`, eligibility/UID gates, journal 저장 코드, lifecycle/reconcile, BinderBackend 전체(0x100 setter 포함), AppOps reader, SingletonSupport, firmware/ABI 증거와 profile을 변경하지 않았다. test.5 source ZIP과 대조해 MainActivity의 getter 추가 외 모든 코드 및 Discovery의 UID/ownership/candidate/batch 코드를 동일하게 확인했다. `canAct`, `canActFromHistory`, Hide/Restore action 경로도 그대로다. 표시 정책은 권한·AppOps·0x200·복원 비트를 변경하지 않는다.

## 테스트·빌드

- Core **127**, Android debug **72**, Android release **72** 통과: 서로 다른 테스트 **199개**, 총 실행 **271회**. 실패·skip 0.
- 추가 9개: positive FINE 사용자 앱, non-positive 기록 제외, managed OFF 기록/상태 분리, 불명확 내부 항목 전체 이동, package 이름 무관 정책, 표시와 ownership/selection safety 분리, 두 종류 preinstall flag 허용, Home 기본 History source, History 전환과 eligibility/journal 분리.
- 기존 COARSE-only, core/persistent, 사전설치 런처, unknown hint, shared UID 및 52개 SafetyEngine 테스트도 통과했다.
- debug/release assemble·서명 검증 완료. lint 각 **0 errors / 7 warnings**(기존 commit/dependency/resource 경고).
- Debug/release signer는 기존 전달본과 동일하다. Fold7에는 debug 업데이트만 수행했으며 release migration·삭제·데이터 초기화는 하지 않았다.

## Fold7 읽기 전용 결과

SM-F966N / One UI 9.0 / Android 17, Shizuku ADB UID 2000. 실제 installed user 0 raw positive 접근 **126개**, 기본 정책 포함 **68개**. 기존 scan의 전체 raw 개수와 대조했다. 이 숫자는 raw 보존 기간 전체에 대한 실측이며 Settings의 24시간 목록 14개와 맞춘 수치가 아니다.

PC 대조 스크립트 첫 값 61은 Windows 줄바꿈으로 런처를 누락한 파싱 오류였다. 이를 수정하고 실제 Binder/metadata를 다시 읽은 최종 값은 68이다. production 앱의 PackageManager 런처 조회는 변경하지 않았고, 실제 화면에서 사전설치 항목도 확인했다.

| 앱 | package | UID | 런처 | FINE flags | 기본 보기 | 정책 이유 |
|---|---|---:|---|---|---|---|
| 카카오맵 | `net.daum.android.map` | 10502 | 있음 | `0x300` | 포함 | `USER_FACING_APP` |
| 미세미세 | `cheehoon.ha.particulateforecaster` | 10474 | 있음 | `0x200` | 포함 | `MANAGED_APP` |
| 날씨날씨 | `com.lifeoverflow.app.weather` | 10078 | 있음 | `0x80301` | 포함 | `USER_FACING_APP` |
| 삼성 날씨 | `com.sec.android.daemonapp` | 10232 | 있음 | `0x80301` | 포함 | `USER_FACING_APP` |
| 카메라 | `com.sec.android.app.camera` | 10126 | 있음 | `0x300` | 포함 | `USER_FACING_APP` |
| 전화 | `com.samsung.android.dialer` | 10129 | 있음 | `0x300` | 포함 | `USER_FACING_APP` |
| Google Maps | `com.google.android.apps.maps` | 10293 | 있음 | `0x300` | 포함 | `USER_FACING_APP` |
| Google Play 서비스 | `com.google.android.gms` | 10273 | 없음 | `0x30` | 전체만 | `PREINSTALLED_WITHOUT_LAUNCHER` |
| Android 시스템 | `android` | 1000 | 없음 | `0x30` | 전체만 | `OTHER_USER_OR_CORE_UID` |
| Samsung Location SDK | `com.sec.location.nsflp2` | 5013 | 없음 | `0x30` | 전체만 | `OTHER_USER_OR_CORE_UID` |
| Continuity Service | `com.samsung.android.mcfds` | 10104 | 없음 | `0x20` | 전체만 | `PREINSTALLED_WITHOUT_LAUNCHER` |
| 맞춤형 서비스 | `com.samsung.android.rubin.app` | 10114 | 없음 | `0x20` | 전체만 | `PREINSTALLED_WITHOUT_LAUNCHER` |

카카오맵·미세미세·날씨날씨와 관리 중 5개(미세미세·당근·우지커피·중고나라·토스)는 기본 기록에 남는다. 삼성 날씨·카메라·전화 등 사용자-facing 사전설치 앱도 실제 화면에서 확인했다. 내부 서비스 5개는 기본 보기에서 빠지고 전체 위치 사용에는 실제 행이 남는다. 전체 목록을 실제 Blue Dot 발생 목록으로 표시하지 않는다.

분류 집계: {'OTHER_USER_OR_CORE_UID': 44, 'PREINSTALLED_WITHOUT_LAUNCHER': 12, 'USER_FACING_APP': 63, 'MANAGED_APP': 5, 'NON_SENSITIVE': 1, 'NO_POSITIVE_FINE_ACCESS': 1}. 집계는 현 기기 값에 대한 정책 대조이며 향후 다른 앱/기기 값에 따라 달라질 수 있다.

## 대상 flags와 journal

업데이트 전후 기존 journal 대상 및 NAVER/날씨날씨를 포함한 **8개 대상**의 FINE/COARSE flags, 전체 user 0 grant 상태, raw AppOps mode, UID peers·설치 신원·APK hash를 대조해 동일함을 확인했다. NAVER의 사용자 지정 0x100 OFF도 그대로다.

| package | UID | FINE flags | COARSE flags | FINE/COARSE raw mode | 전후 |
|---|---:|---|---|---|---|
| cheehoon.ha.particulateforecaster | 10474 | `0x200` | `0x300` | 0 / 0 | 동일 |
| com.elz.secondhandstore | 10391 | `0x200` | `0x300` | 4 / 4 | 동일 |
| com.lifeoverflow.app.weather | 10078 | `0x80301` | `0x301` | 0 / 0 | 동일 |
| com.nhn.android.nmap | 10395 | `0x200` | `0x300` | 4 / 4 | 동일 |
| com.towneers.www | 10229 | `0x200` | `0x300` | 4 / 4 | 동일 |
| kr.co.wylie.m.oozy | 10652 | `0x80201` | `0x301` | 4 / 4 | 동일 |
| net.daum.android.map | 10502 | `0x300` | `0x300` | 4 / 4 | 동일 |
| viva.republica.toss | 10366 | `0x200` | `0x300` | 4 / 4 | 동일 |

Journal의 originalBit·identity·enabled·state 및 그 밖의 필드는 모두 동일하다. **기존 앱의 읽기 전용 resume 재검사는 APPLIED 항목의 lastVerifiedTime(직렬화 key: verifiedAt)을 갱신하므로 업데이트 전후 파일 전체 bytes는 동일하지 않다.** 이 동작을 변경하지 않았다. 초기 재검사 종료 후 History/Home 보기 전환·스크롤 동안에는 journal 파일 bytes까지 동일함을 확인했다. 복원 비트 변경을 검증 시각 갱신과 혼동하지 않는다.

test.6 설치 이후 controller log의 setter_request/enter/return **0개**. 호스트 검증도 read-only getter와 화면 보기만 실행했으며 grant/revoke·AppOps set·숨김·원복·재적용을 호출하지 않았다.

## 전달본·증거

- 설치 APK: `artifacts/bluedotOFF-1.0.0-test.6.apk` — SHA-256 `c2de8815d113530048007319bd367e999346028d91bf012aa6f6e9b074882041`.
- 별도 release: `artifacts/bluedotOFF-1.0.0-test.6-release.apk` — SHA-256 `3268be01a7c2296ab27f29b431554c84a4c3c234a1b47218a1503e94ab358723`. 현재 debug와 signer가 다르므로 직접 덮어쓰는 migration은 하지 않는다.
- 소스 ZIP: `artifacts/bluedotOFF-1.0.0-test.6-source.zip`.
- `artifacts/device-validation-1.0.0-test.6.json`, `build-audit-1.0.0-test.6.json`, `safety-audit.json`.
- 기기 원본: `research/test6_20261004/`의 flags/journal before·after, history-policy-device.json, 두 보기 XML/PNG, Home PNG, controller-events-final.jsonl, read-only-audit.json, source-scope-audit.json.

이번 단계는 History 표시 정리로 종료한다. **Shizuku 제거/Phase 7은 시작하지 않았다.**
