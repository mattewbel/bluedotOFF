# bluedotOFF 1.0.0-test.4 — 기록 보기 정리

사용자가 직접 실행한 앱보다 Android/Samsung/Google 내부 서비스가 기록 상단에 나타나, 파란 위치 표시를 띄운 앱처럼 보이는 혼동을 줄였습니다. 기본 **앱 기록**과 선택 가능한 **전체 위치 사용**을 구분합니다.

## 확인한 표시 경로

현재 Fold7 firmware에서 수집한 SystemUI 소스의 `AppOpsPrivacyItemMonitor.isSystemApp` / `shouldDisplayLocationOp`는 위치 접근을 별도로 분류합니다. 정상 권한 접근에서는 `0x100`을 사용하고, preflight 결과가 다른 경우에는 `0x200`을 사용하는 경로도 있습니다. `AppOpsControllerImpl.isUserVisible`은 `PermissionManager.shouldShowPackageForIndicatorCached` 및 일부 attribution tag도 확인합니다. 따라서 AppOps 위치 기록 전체가 실제 파란 표시의 기록은 아닙니다.

이번 기본 보기는 사용자에게 관련 있는 앱 기록을 좁히는 정책입니다. Android 권한 사용 화면과 정확히 같은 목록, 과거 표시 발생의 확정 기록, 모든 시스템 앱의 영구 면제를 보장하지 않습니다. 현재 메타데이터와 과거 접근 시점의 분류가 다를 수 있습니다.

## 기본 보기 정책

- 실제 양의 위치 접근 시각이 있는 기록만 사용합니다.
- 등록하여 관리 중인 숨김 앱은 OFF여도 유지합니다. 기록이 없으면 가짜 기록을 생성하지 않습니다.
- 사용자 0의 일반 앱 UID이며, 직접 실행 가능한 앱 또는 사용자가 설치한 앱의 FINE 기록을 우선 보여줍니다.
- 핵심 UID, persistent/핵심 구성 요소, 실행 화면이 없는 사전설치 서비스, COARSE만 있는 기록, 미등록 앱의 현재 FINE `0x100`이 없는 기록은 전체 보기에서 확인합니다.
- 민감도 getter가 실패하면 UNKNOWN으로 두고 관련 앱 기록을 유지합니다. 조회 실패를 표시 대상의 확정이나 쓰기 허용으로 사용하지 않습니다.
- 사전설치라는 이유만으로 지도 등 실행 가능한 기본 앱을 제외하지 않습니다.

전체 보기에는 내부 서비스를 포함한 원래 위치 접근 기록이 남습니다. 확인되지 않은 항목은 중립 색상과 설명으로 표시하고, 기본 정책에서 제외된 미등록 항목에는 기록 화면의 숨기기 버튼을 제공하지 않습니다. 기존 앱 추가 경로 및 전체 UID 안전 검사에는 이 표시 정책을 적용하지 않습니다.

Home의 최근 앱 요약은 전체 보기 선택과 관계없이 기본 앱 기록을 사용합니다. 보기 선택은 화면 이동/조회 갱신/Activity 상태 복원에서 유지됩니다. 체크 표시와 재적용, inline 실패 처리 및 원복 경로는 유지합니다.

## 검증

| 항목 | 결과 |
| --- | --- |
| Core + Debug 테스트 | 148개 통과 (Core 98, App 50) |
| Release 테스트 | App 50개 추가 실행 통과 |
| Debug / Release lint | 각각 오류 0, 경고 7 |
| 화면 렌더링 | 기본/전체 기록 등 30장 생성, 두 보기 직접 시각 확인 |
| 실제 보기 전환 | Compose 클릭으로 내부 서비스의 표시/제외 확인 |
| 숨긴 앱 기록 | OFF 유지, 검증 체크와 재적용 회귀 검사 통과 |
| 안전성 | 공유 UID가 표시 정책으로 허용되지 않음; 기존 전체 SafetyEngine 테스트 통과 |
| APK 서명 | Debug/Release 각각 test.3과 동일 서명, 두 종류는 별도 서명 |
| 새 빌드 실기기 | **설치·기록 UI 읽기 전용 검증 통과** — Fold7 F966NKSUCCZIH |
| 이번 수정의 대상 메타데이터 쓰기 | **0회** |

새롭게 추가한 기기 조회는 FINE 위치 기록이 있는 항목에 대한 기존 검증된 `getPermissionFlags` 읽기뿐입니다. 초기 가벼운 앱 탐색, 전체 UID 소유 확인, 실제 적용 전의 신원/권한/AppOps/flag 안전 검증을 유지합니다. 표시 정책은 쓰기 권한을 부여하지 않습니다.

2026-10-04 15:45 KST, 동일 Debug 서명으로 test.3 → test.4 업그레이드했습니다. 설치된 APK의 SHA-256이 전달 파일과 일치합니다. 설치 전 사용자가 관리하던 journal 4개(미세미세·당근·우지커피·번개장터)의 신원·originalBit·enabled·state를 유지했습니다. 미세미세 FINE 0x200, 날씨날씨 0x80301, NAVER 0x200 및 각 COARSE/BACKGROUND·권한·peer·설치 정보도 설치 전후 일치합니다. 이전 Phase 6.2 종료 후 사용자가 변경한 현재 상태를 기준으로 비교했으며 과거 상태로 원복하지 않았습니다.

Shizuku 서버가 꺼져 있어 공식 앱의 컴퓨터용 시작 명령으로 ADB UID 2000 서버를 시작했습니다. 기본 보기에 미세미세·당근의 검증 체크와 카카오맵이 남는 것을 확인했습니다. 전체 보기에 Google 위치 기록이 추가되고 기본 보기에서는 빠집니다. UI 탐색은 일부 화면의 표본이며 보고된 모든 내부 서비스의 전체 목록을 수동으로 전수 확인한 것은 아닙니다. 설치 후 적용·원복은 실행하지 않았고 새 setter 로그도 0회입니다. 파란 표시 OFF→ON은 이번 UI 수정에서 반복하지 않았습니다.

실제 탐색 측정 1회: 일반 앱 778, 전체 위치 기록 125, lightweight 280ms, UID map 190ms, history 693ms, sort 36ms. history 시간에는 새 FINE getter가 포함됩니다. 단일 측정이며 광학 화면 반응 시간이나 성능 보장은 아닙니다. 기본 앱 기록 화면으로 복귀시켰습니다.

## 산출물 및 이전 증거

- Debug `bluedotOFF-1.0.0-test.4.apk`: `eddf3b325f6b1421887e428fad7b86133bc27fe55ea3c25982eae61bfd85682d`
- Release `bluedotOFF-1.0.0-test.4-release.apk`: `5bb016826680fdf0f0ddd21f33bf2aafdb332908a1d5861acc61e8f26f49f0c9`
- Source `bluedotOFF-1.0.0-test.4-source.zip`: SHA 및 비밀정보 제외 결과는 `delivery-audit-1.0.0-test.4.json`.
- 이번 빌드 상태: `device-validation.json`, `build-audit-1.0.0-test.4.json`, `safety-audit.json`.
- test.3 실제 OFF→ON/다중 원복 증거는 [이전 보고서](Phase-6.2-report.md) 및 `device-validation-1.0.0-test.3.json`에 보존했습니다. 이번 실기기 PASS로 재사용하지 않습니다.
