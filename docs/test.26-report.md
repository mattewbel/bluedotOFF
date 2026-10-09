# test.26 — 중복 오류 표시 정리와 적용 유지 안내

- 홈·앱 추가·기록·기존 재적용 화면에서 BatchErrors가 반복 출력하던 앱별 오류 줄을 제거했습니다. 상단 완료/부분 실패/중단/종료 경고와 개별 앱 상세의 실제 오류는 유지합니다. batch 결과·journal·진단 저장은 변경하지 않았습니다.
- 적용 작업 화면에 ‘일부 앱은 Android가 파란 위치 표시를 다시 켤 수 있어요. 적용 직후에도 풀릴 수 있으니, 필요하면 홈에서 다시 적용해주세요.’를 추가했습니다. 복원 작업 화면에는 이 적용 안내를 표시하지 않습니다. 영어 안내도 함께 추가했습니다.
- 재적용 화면의 설명은 실제 표시 방식에 맞춰 ‘결과는 상단에서 안내합니다’로 수정했습니다. 기존 test.25의 우선 정렬과 대상 확인 창은 유지합니다.
- SafetyEngine·permission flags·AppOps·UID/shared UID·Binder·지연 확인·복원/롤백·화면 이탈 중단·자동 무선 디버깅 정책은 변경하지 않았습니다.

## 표시가 다시 켜지는 현상

기존 Fold7 조사에서 실제 설치된 PermissionController의 UserSensitiveFlagsUtils가 sensitivity flags를 mask 0x300으로 다시 계산하는 경로와 카카오맵 해당 UID의 실행 로그를 확인했습니다. 토스의 FINE 0x300 복귀도 이전 test.20 등에서 관찰했습니다. [Phase 6.3](Phase-6.3-report.md), [test.20](test.20-report.md)

Android 공개 코드에도 UID별 sensitivity 재계산 요청 경로가 있습니다. [PermissionPolicyService](https://android.googlesource.com/platform/frameworks/base/+/71c765985b5f3c6716785d7323636d96134c9e8d/services/core/java/com/android/server/policy/PermissionPolicyService.java)

이번 연결 후 토스 UID 10366의 실제 FINE은 0x300, journal은 NEEDS_REAPPLY_POLICY였습니다. 최근 앱 기록의 마지막 setter는 mask 0x100/value 0이며, 즉시·500ms·1500ms 모두 FINE 0x200이었습니다. 마지막 쓰기 약 13.4초 뒤 같은 UID의 `PermissionControllerServiceImpl: Updating user sensitive` 로그가 있었고 이후 bluedotOFF의 추가 setter는 없었습니다. OS sensitivity 재계산에 부합하는 정황이며, 그 요청을 촉발한 upstream 동작이나 정확한 ON 쓰기의 호출자는 이번 자료만으로 확정하지 않습니다. 토스 자체 보안 기능이 직접 비트를 바꾼 것으로 단정하지 않습니다. 이를 막기 위한 자동 쓰기나 상시 디버깅 기능을 추가하지 않습니다.

## 검증

- Debug/Release 빌드와 자동 검사 852개(core 310, Debug 271, Release 271)가 통과했습니다. 실패·오류·건너뜀은 0개, Lint 오류 0개/기존 경고 41개씩입니다. 서명·매니페스트·16KB 정렬·기존 좁은 setter 경로 정적 검사도 통과했습니다.
- 중단 결과를 가진 홈/앱 추가/기록/재적용 모델에서 상단 안내 유지·앱별 오류 목록 제거를 확인했습니다. 적용 안내가 복원에는 표시되지 않는 것도 확인했습니다. 한국어·영어·큰 글씨 렌더링을 검수했습니다.
- Fold7에 test.26 업데이트 설치와 홈 확인을 마쳤습니다. 신규 실제 적용/중단 쓰기 시험은 수행하지 않았습니다. 저장된 94개 원복 기록과 페어링/설정 파일, 등록 앱 전체 FINE flags는 검증 전과 동일하며 대상 setter 이벤트 0회였습니다.
- APK는 `artifacts/bluedotOFF-1.0.0-test.26.apk`와 `artifacts/bluedotOFF-1.0.0-test.26-release.apk`에 있습니다. 개인 기기 기록과 토스 대조 자료는 Git 제외 영역에 보관했습니다. 공개 업로드는 수행하지 않았습니다.
