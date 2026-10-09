# bluedotOFF test.22 — 대량 적용·복원 연결 재사용

## 변경

- 대량 숨김 적용, 선택한 앱 숨김 해제, 모두 복원, 적용 범위를 낮출 때의 복원은 기존 `perform`/`WirelessWork.runBatch` 경로를 공유합니다. 이 경로의 인증된 TLS 소켓을 작업 끝까지 한 번만 유지하고, 마지막에 닫도록 변경했습니다.
- 12개/55초 작업 구간과 helper의 120초 전체·10초 요청 대기 제한은 그대로입니다. 구간마다 새 helper를 시작하고 PROFILE로 shell 신원과 firmware/Binder ABI를 다시 확인합니다. helper 프로세스를 한꺼번에 여러 개 띄우지 않습니다.
- shell 정상 종료 코드 0과 올바른 ADB stream 종료를 모두 받은 뒤 다음 helper를 시작합니다. 구간마다 서로 다른 stream ID를 사용하고, 이전 backend 객체를 즉시 무효화해 다음 helper에 잘못된 요청을 보내지 못하게 합니다.
- 요청 손실·잘못된 응답·종료 불확실·작업 예외 이후에는 다음 구간을 시작하거나 쓰기를 재시도하지 않습니다. 마지막 연결 종료까지 확인돼야 기존 자동 OFF 정책을 평가합니다.
- 기존 단일 앱 검사·즉시/500ms/1500ms 확인·권한/AppOps/UID 불변 조건·원래 비트 기록·rollback·복원 로직·진행률·화면 이탈 중단은 유지합니다. typed helper 명령, FINE 0x100 setter, native loopback TLS, 권한 목록과 서명 설정은 변경하지 않았습니다.
- connection 로그와 helper 로그를 분리하고 connection ID로 연결했습니다. 개인 기기 로그는 공개 저장소에 포함하지 않습니다.

## 근거와 한계

Android 연결 알림은 [AOSP AdbDebuggingManager](https://android.googlesource.com/platform/frameworks/base/+/1849a4c11671573e5b2815f4b3ea7eb9d24ab98d/services/core/java/com/android/server/adb/AdbDebuggingManager.java)가 표시합니다. 앱이 이를 삭제하는 기능을 추가한 것이 아니라 반복되는 물리 연결을 줄이는 변경입니다. 최초 연결, 별도 조회나 이후 새 작업의 연결 알림은 남을 수 있습니다. 아래 실측은 Fold7 한 대의 승인된 시험 범위입니다.

[ADB protocol](https://android.googlesource.com/platform/system/core/+/de980f27a701eb8f7130218f9bac768fe7155d30/adb/protocol.txt)의 stream ID와 OPEN/WRTE/OKAY/CLSE 규칙, [AOSP shell service](https://android.googlesource.com/platform/system/core/+/75b3266/adb/daemon/shell_service.cpp)의 exit frame 이후 stream 종료 순서를 참고했습니다. 서버 구현의 오래된 공개 소스만으로 Fold7 동작을 단정하지 않습니다.

## 검증 상태

- 전체 자동 테스트 **816개 통과**: core 306, debug 255, release 255. 실패·건너뛰기 0.
- 실제 JournalStore를 쓰는 모의 기기에서 **74개 적용 → 74개 복원**이 각각 7개 구간으로 완료됨을 확인했습니다. 원래 비트 저장, pending 해소, 완료 전 99% 제한과 최종 원복을 검사했습니다. 모의 시험이며 실제 휴대폰 74개 작업 실측이 아닙니다.
- FakeWire와 실제 Kotlin 연결 코드에서 **인증 1회 → 새 PROFILE/helper 7회 → 소켓 종료 1회**, 이전 backend 무효화, 화면 이탈, 오류 후 다음 작업 차단, 최종 소켓 종료 실패 시 자동 OFF 차단을 확인했습니다.
- 잘못된 stream ID, 이전 stream의 남은 응답, 정상 exit 이후 CLSE 누락, 불완전 shell frame, nonzero exit, timeout과 응답 오류를 검사했습니다. 정상 종료가 증명된 바로 이전 stream의 빈 CLSE만 최대 8개까지 버리고, 초기 0/0 종료 패킷·다른 ID·종료 패킷 폭주는 실패로 처리합니다.
- debug/release 빌드와 lint 완료. lint 오류 0, 기존 경고 각 35개. 기존 서명·manifest 권한 목록·16KB ELF/ZIP 정렬·고정 loopback·단일 narrow setter 검사 통과.
- 자동 검사 결과와 APK는 ignored `artifacts/build-audit-1.0.0-test.22.json`, `artifacts/bluedotOFF-1.0.0-test.22.apk`, `artifacts/bluedotOFF-1.0.0-test.22-release.apk`에 보존했습니다.

## Fold7 실기기 검증 — 2026-10-09

- 기존 등록은 52개였습니다. 업데이트 설치 시 원래 비트 기록·자체 페어링 키·자동 관리 동의를 보존했습니다.
- 처음 13개 복원 시험은 12개 완료 후 다음 helper 시작에서 중단됐습니다. 이전 stream의 CLSE에 다시 CLSE를 보내던 응답과 뒤늦게 도착한 중복 종료 패킷을 실제 로그에서 확인했습니다. 정상 종료 CLSE에 응답하지 않도록 수정하고, 증명된 이전 stream의 중복 종료만 제한적으로 처리했습니다. 쓰기 재시도나 임의의 이전 응답 허용은 추가하지 않았습니다.
- 수정 후 기기에서 **7개 연속 읽기 구간**을 한 연결로 실행했습니다. 모두 서로 다른 helper PID, PROFILE, 정상 종료, stream 종료와 마지막 소켓 종료를 확인했습니다. 대상 permission metadata 쓰기는 0회입니다. 이 진단은 기존 고급 진단과 같이 debug 빌드에만 표시됩니다.
- 승인한 일반 앱 13개만 **숨김 → 복원 → 다시 숨김**으로 검사했습니다. 각 작업은 2개 helper 구간, 인증 연결 1개, 정상 helper/stream 종료와 최종 소켓 종료로 완료됐습니다.
- 수정 후 각 작업에서 Android 시스템의 무선 ADB 연결 알림 ID 62를 **게시 1회·취소 1회** 확인했습니다. 알림 ID는 [AOSP system_messages.proto](https://android.googlesource.com/platform/frameworks/base/+/cd3b356296bb/proto/src/system_messages.proto)의 NOTE_ADB_WIFI_ACTIVE와 대조했습니다. 각 작업 이후 adb_wifi_enabled=0과 자동 OFF 성공을 확인했습니다. 별도 앱 목록 조회는 별도 연결이므로 그 알림까지 없앤다는 의미는 아닙니다.
- 연결 시작부터 종료까지 13개 복원 **9.59초**, 최종 13개 숨김 **9.65초**였습니다. Wi-Fi 서비스 발견·대상 선택 시간은 제외했습니다. 74개 전체의 이번 버전 실측은 수행하지 않았습니다.
- 복원 중 13개 ON과 다른 39개 불변, 마지막에 **52개 모두 시험 전 FINE flags와 동일**, 모두 APPLIED·pending 없음, 원래 비트·설치 신원·다른 39개 기록 불변을 확인했습니다. 전체 시험의 setter 기록은 승인한 13개에만 mask 0x100 / value 0 또는 0x100이며, 다른 앱 쓰기는 없습니다.
- 개별 앱의 실제 지도 기능·위치 아이콘을 새로 눈으로 확인하는 시험은 이번 연결 개선 범위에 포함하지 않았습니다. 권한/AppOps/UID와 즉시·500ms·1500ms 불변 검증은 기존 엔진을 그대로 사용했습니다.
- 공개 문서에는 기기 일련번호·자체 키·개인 위치 사용 내역을 포함하지 않았고, 실제 증거는 ignored `.private/test22-device`에 보존했습니다.
- 최종 816개 검사와 패키징 검사를 통과한 APK를 업데이트 설치했습니다. 설치 전후 journal 바이트·52개 FINE flags·설정 및 페어링 파일 해시가 동일했고, 마지막 실행 후에도 홈의 52개 완료 표시와 무선 디버깅 OFF를 확인했습니다.
