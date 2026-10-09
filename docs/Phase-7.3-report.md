# bluedotOFF Phase 7.3 — 1.0.0-test.10

2026-10-05 진행 보고서. 구현·자동 검사·두 APK 빌드·서명 검증은 완료했다. Fold7에서 최초 동의·자기 권한 부여·일반 앱의 자동 ON/OFF·저장된 키 재연결·동일 signer 업데이트 유지·카카오맵 숨김을 실측했다. 원복 및 재부팅·권한이 있는 상태의 데이터 초기화 검증은 아직 진행 중이다.

## 변경 사항

- 무선 디버깅 자동 관리 기본 OFF. 최초 페어링 뒤 사용자가 설명을 확인하고 ‘자동 관리 사용’을 눌러야 자기 앱에만 필요한 시스템 설정 권한을 부여한다. 일반 화면에서는 기술적인 권한 이름을 앞세우지 않는다.
- 설정 쓰기는 고정 `adb_wifi_enabled=1/0` 두 기능뿐이다. 범용 key/value 입력, 외부 Intent로 쓰기 요청, 임의 package/permission 부여 기능은 없다. 자기 권한 제거 기능도 자기 package와 WRITE_SECURE_SETTINGS로 고정한다.
- 자동 ON은 사용자 사용 중, 개발자 옵션 ON, 실제 Wi-Fi 연결, 동의, 실제 권한, 저장된 연결 정보, 현재 무선 OFF 조건을 모두 요구한다. boot/background ON은 없다.
- 완료 결과와 durable journal을 확정한 뒤 helper의 정상 shell EXIT(0), 소켓 종료를 확인하고 일반 앱 UID에서 OFF한다. 갑작스러운 CLSE나 실패 종료를 정상 종료로 인정하지 않는다. 종료/OFF 실패 안내는 위치 작업 결과와 분리한다. 동의를 OFF로 바꾸면 추가 자동 쓰기를 중단한다.
- 개발자 옵션 → Wi-Fi 실제 연결 → 무선 디버깅 → 페어링 → 호환성 확인 순서. 삼성 소프트웨어 정보 공개 action이 접근 가능한 경우 우선하고, 그렇지 않으면 공개 휴대전화 정보 → Settings로 fallback한다. 현재 Fold7에서는 휴대전화 정보에서 사용자가 소프트웨어 정보를 누르는 경로를 사용한다.
- NSD 서비스 이름·네트워크·관찰 세대별로 loss를 처리한다. 사라졌다 다시 나타난 같은 이름/포트도 새 코드 세대로 취급하고, 이전 답변과 중복 전송을 거부한다.
- 위치 관련 선언/기록/AppOps가 확인된 앱만 확장 목록에 포함한다. 적용 가능한 앱, 접힌 적용 불가/확인 필요 목록, 현재 검색·필터 전체 선택을 제공한다. 화면 밖 결과도 선택하며 다른 조건에서 선택한 항목은 보존한다.
- 목록 캐시는 표시 편의용이며 실제 변경 때 fresh SafetyEngine 검사를 다시 실행한다. 설치·제거·교체, 빌드 변경, 새로고침, TTL로 무효화한다.
- One UI 9 이상은 안내·읽기 검사를 허용한다. 위치 변경은 검증된 Binder/History/indicator 메서드 ABI와 의미가 일치할 때만 허용한다. 전체 APK/patch hash만 달라졌다는 이유로 앱 실행을 막지 않는다. singleton 공유 사용자 정책은 기존 strict gate를 유지한다.

## ACTUALLY TESTED

### 코드 변경 전 증거와 복원

빈 journal + 미세미세/날씨날씨 FINE 0x100 OFF를 읽기 전용으로 조사했다. 과거 snapshot과 package/version/설치·업데이트 시간/UID/전체 peers/APK hash가 같고, 과거 journal의 signer 및 알려진 originalBit=256과 일치했다. dumpsys 날짜는 초 단위로만 비교했으며 APK·signer·UID 등 다른 증거를 함께 사용했다.

실기기 검증 단계에서 동일 설치와 현재 모든 metadata를 재확인하고, 과거에 검증된 동일 framework ABI를 확인한 뒤 두 앱만 mask 0x100/value 0x100으로 복원했다. NAVER나 다른 앱의 OFF를 일괄 ON으로 바꾸지 않았다.

| 대상 | FINE 이전 → 복원 | 즉시/500ms/1500ms | 나머지 metadata |
|---|---|---|---|
| 미세미세 | 0x200 → 0x300 | 모두 원래 ON | 불변 |
| 날씨날씨 | 0x80201 → 0x80301 | 모두 원래 ON | 불변 |

첫 읽기는 쓰기 반환 직후 시작했고 47–62ms 안에 완료했다. 500ms 읽기는 500ms에 시작해 547–563ms에 완료, 1500ms 읽기는 1500ms에 시작해 1547–1562ms에 완료했다. COARSE 전체·FINE의 나머지 비트·grant·raw AppOps·설치 신원·UID peers를 다시 비교했다. journal은 빈 상태 그대로 보존했다. 별도 `TARGET_RECOVERY` 쓰기 총 2회다. 이 시험은 test.10 Local ADB 자동 관리 성공 증거가 아니다.

### 실기기에서 발견한 잘못된 빌드번호 화면

사용자가 개발자 옵션 OFF에서 앱 버튼을 눌렀을 때 원하는 삼성 소프트웨어 정보와 다른 빌드번호 화면으로 이동한다고 보고했다. 설치된 Settings APK를 읽어 원인을 확인했다. `Settings$FirmwareVersionActivity`는 `CatalystSettingsActivity("firmware_version")`로 Android 버전 정보 화면을 연다. 그 빌드번호는 `SimpleBuildNumberPreferenceController`의 표시 항목이다. 삼성의 실제 소프트웨어 정보는 별도 `SoftwareInfoSettings`이며 `BuildNumberPreferenceController`를 사용한다. 화면에 빌드번호가 있다는 사실은 7회 클릭으로 개발자 옵션을 켜는 화면이라는 증거가 아니었다.

잘못된 exported Activity 후보를 제거했고, 소프트웨어 정보 공개 action이 없으면 휴대전화 정보를 여는 정상 fallback으로 수정했다. 버튼도 ‘휴대전화 정보 열기’로 바꿨다. 비공개 삼성 fragment를 강제로 열지 않는다. 해당 잘못된 Activity가 정상 resolve되는 상황에서도 선택하지 않는 회귀 검사를 추가했다. 이 발견으로 앞선 ‘직접 소프트웨어 정보 이동 확인’ 판정을 폐기한다.

### 설치 및 초기화 준비

- test.10 debug APK를 기존 test.9와 같은 signer로 업데이트해 설치 성공을 확인했다. 메인 화면 실행과 실제 안내 문구를 확인했다.
- 업데이트 후 두 복원 대상과 카카오맵의 표시 비트 ON, 빈 journal, 개발자/USB ON, 무선/Wi-Fi OFF, 충전 중 화면 유지 값 7을 읽어 확인했다.
- 초기화 전 journal·target identity/metadata·시스템 설정·페어링 파일 존재/해시를 로컬 research에 보존했다. 개인 키와 페어링 코드는 추출하거나 배포하지 않았다. ciphertext 해시는 복구 가능한 키 백업을 의미하지 않는다.
- 사용자의 별도 승인 후 bluedotOFF 데이터만 초기화했고 OS의 Success를 확인했다. 다른 앱 데이터는 초기화하지 않았다.
- 연결 안내 Activity는 외부 실행이 거부됐다. 공개 메인 화면으로 정상 진입했으며 export를 바꾸거나 비공개 화면 접근을 우회하지 않았다.

### Wi-Fi 단계와 권한 준비

사용자가 실제 Wi-Fi 안내를 확인하고 네트워크에 연결한 뒤, 별도 확인 버튼 없이 무선 디버깅 단계로 바뀌었다고 확인했다. 무선 디버깅 ON은 PC의 읽기 전용 조회에서도 확인했다. 앱의 근처 기기 권한은 실제 granted 상태이며, 이미 허용된 경우 ‘자동 연결 준비’ 단계는 생략된다. 네트워크 허용 창이 매번 뜨지 않는 것은 무선 디버깅 OFF 상태라는 의미가 아니다.

개발자 옵션을 껐다 켠 뒤 충전 중 화면 유지 값이 0으로 바뀌어, 기존 사용자 요청에 따라 PC에서 7로 복구했다. 이 보조 설정 쓰기 1회는 앱의 자동 무선 디버깅 쓰기와 별도로 집계한다. 아직 앱의 자동 관리 동의 파일과 시스템 설정 쓰기 기록은 없다.

### 설정 뒤에서 앱이 정지되어 페어링 재무장이 멈춘 문제

최초 코드 알림은 실제 표시됐고 NSD callback 진입→알림 요청은 약 443.8ms, notify 호출 반환은 약 11.1ms였다. 이후 사용자가 코드 창을 닫았지만 알림 변화가 없었다고 보고했다. 해당 순간 앱 process는 `cached=true`, `curProcState=16`, `isFrozen=true`였다. loss callback과 새 알림 요청도 기록되지 않았다. 앱으로 돌아온 뒤에는 세션 만료 안내가 나왔다. 정지된 process를 Activity+Receiver만으로 유지할 수 있다는 앞선 가정은 실패로 분류한다. 이 관찰만으로 NSD loss 전달 자체의 모든 내부 시점을 특정했다고 주장하지 않는다.

Phase7.0 요청서 31절은 process lifetime 때문에 필요한 경우 페어링 중에만 임시 foreground service를 허용한다. 이에 사용자 시작 연결 준비에만 `shortService`를 추가했다. 비공개 컴포넌트, sticky 재시작 없음, 별도 조용한 진행 알림, 최대 150초, 성공·취소·지원 불가·수동 전환·연결 단계 진입 시 정리, OS timeout 처리 및 늦은 start 무효화를 적용한다. NSD 자체를 busy polling하지 않는다. 시스템 설정이나 대상 metadata를 이 서비스가 쓰지는 않는다. [Android shortService 문서](https://developer.android.com/develop/background-work/services/fgs/service-types#short-service)에 맞춰 OS 약 3분 제한보다 일찍 중단한다. 수정 APK를 같은 signer로 다시 설치한 뒤 사용자가 닫기→코드 입력 제거→다시 열기→새 코드 알림 표시를 확인했고, 새 코드를 RemoteInput으로 전송했다. 실제 NSD 기록에도 loss 및 새 resolved 이벤트가 있다. 중복 resolved callback 두 번마다 코드 알림 한 번만 요청됐으며, 새 코드 pairing은 75ms에 성공했다. saved-key 연결/검증은 shell UID2000, 정상 helper EXIT 및 socket close, 총 1854ms로 완료됐다. 이 단계의 대상 write와 시스템 설정 write는 0회다. 코드 알림 요청 지연은 두 번 각각 약 41.6ms/32.5ms, notify 반환은 11.3ms/10.0ms였다. 초기 443.8ms보다 작지만 표본 두 개만으로 일반적인 체감 개선을 보장하지 않는다. 150초 가까이 Settings에 머무는 장시간 freeze 시험은 아직 미실측이다.

### 자동 관리 최초 동의와 일반 앱 OFF

동의 전에 네 대상(미세미세·날씨날씨·카카오맵·NAVER 지도)의 metadata, 빈 journal, 시스템 설정을 보존했다. 사용자가 앱의 설명 창에서 ‘자동 관리 사용’을 직접 선택했다. 자기 package로 고정한 WSS grant attempt/verified 이벤트가 생성됐고, 일반 앱 UID10798에서 `adb_wifi_enabled` ON→OFF 성공 및 PC getter의 0을 확인했다. 대상 setter는 0회다. 네 대상 metadata와 journal은 byte/구조 비교로 동일했고, 무선 디버깅 외 시스템 설정도 그대로다. 해당 Local ADB batch는 completed/helperExited/closed 모두 true이며, 종료 시각 근사값 뒤 약 3ms에 OFF audit가 기록됐다. elapsed와 wall-clock marker를 섞은 근사값으로 나노초 수준 순서를 주장하지 않으며 코드 순서도 함께 검사했다. 자기 permission 쓰기 1회, 앱 시스템 설정 쓰기 1회다.

### 자동 ON·재연결·목록 확인

사용자가 홈으로 나간 뒤 앱 아이콘으로 다시 열었다. 일반 UID10798의 OFF→ON 성공, saved key reuse, fresh shell/profile 검증, 정상 helper 종료/socket close, 최종 일반 UID OFF가 기록됐다. 새 페어링 코드는 사용하지 않았다. 이 batch는 7039ms이며, 전체 ON→OFF 간격은 약 8051ms였다. target setter는 계속 0회였다. 사용자가 시스템 앱 포함 목록의 정상 동작을 확인했고, 현재 목록의 행 높이 때문에 앱이 적게 보인다는 UX 문제를 보고했다. 이에 아이콘 40→32dp, 행 최소 높이 84→56dp, 앱 이름/적용 상태 같은 줄, 상단 여백 축소를 적용하고 긴 차단 사유의 별도 줄을 유지한다. 수정 UI의 일반/큰 글씨/펼친 화면을 자동 렌더링해 눈으로 검사했다. 일반 412dp 예시는 한 화면에 6개 행, 글씨 1.5배 예시는 4개 행이 보이며 이름/상태가 겹치지 않는다. 선택 동작과 차단 사유 표시는 기존 검사를 통과했다.

동일 signer의 수정 APK 업데이트 후에도 새 grant 없이 일반 UID의 ON→saved-key reconnect→fresh 검증→정상 종료→OFF가 성공했다. 동의·페어링·WSS 유지가 실제 확인됐고 네 대상 metadata도 그대로였다. 이 시점까지 앱 시스템 설정 쓰기 5회, 자기 grant 1회, test.10 target setter 0회다.

### 목록 상단 정리 추가 요청

사용자가 카카오맵 표시 ON을 확인했고, 제목과 선택 수를 같은 줄로 묶고 검색/전체 선택을 같은 줄에 놓도록 추가 요청했다. 최종 UI는 ‘신뢰하는 앱 선택’ 오른쪽에 ‘N개 선택됨’만 표시하며 정렬 설명은 제거한다. 기존 전체 선택 행에 검색창을 배치하고 전체 선택 체크박스를 다른 앱 행과 같은 오른쪽 끝에 둔다. 검색은 기본 52dp 높이로 줄이되 큰 글씨 설정에서는 입력 글씨 높이에 맞춰 확장한다. 확장 스위치 문구는 ‘위치정보 사용 전체 앱 표시’로 바꾸고 괄호 설명을 제거한다. 시스템 앱을 포함하는 실제 목록 범위는 유지한다. 최신 사용자 요청이 Phase7.3 초기의 더 긴 문구 요구를 대체한다. 모델 렌더링에서 일반 화면 7개 완전 행 및 일부 다음 행, 큰 글씨 6개 행을 확인했다. 최종 APK를 설치했고, 카카오맵 한 개의 숨김/복원 시험을 이어서 진행한다. 사용자 요청에 맞춘 최신 화면의 휴대폰 가독성은 확인 중이다.

### 최종 화면에서 카카오맵 단일 숨김

사용자가 최신 APK의 검색창에서 카카오맵 하나만 선택해 추가했고 파란 점·위치 아이콘이 모두 사라졌다고 확인했다. 앱 자체 Local ADB의 실제 setter는 mask 0x100/value 0 한 번이며 FINE은 0x300→0x200이다. 즉시·500ms·1500ms readback, journal APPLIED 및 originalBit=256, settlingVerified=true를 확인했다. COARSE 전체·나머지 FINE 비트·grant·raw AppOps·설치/UID peers는 불변이고, 미세미세·날씨날씨·NAVER 지도의 snapshot도 시험 전과 정확히 같다. 새 UI에서 카카오맵 검색·단일 선택·추가가 실제 동작했다. 정상 helper EXIT0와 socket close 뒤 무선 디버깅은 0이다. 이 시점까지 test.10 대상 setter 1회, 앱 시스템 설정 쓰기 누적 19회, 자기 권한 부여 1회이며, 과거 소유 효과 복원 2회는 별도다. 원래 표시 상태 복원 확인을 기다리고 있다.

## AUTOMATED/MODEL TESTED

전체 실행 489회: core 223, Android debug 133, Android release 133. 실패·오류·건너뛰기 0. Android 검사는 SDK34 Robolectric 모델이며 실제 Samsung Settings provider나 Android17 NSD가 동작했다는 의미는 아니다.

주요 검사는 모든 자동 ON 조건 조합, 동의와 권한 분리, 데이터 초기화 후 권한만 남은 경우 자동 쓰기 금지, 권한 상실, 완료/helper/socket 종료 조건, 정상 EXIT/실패 EXIT/갑작스러운 CLSE, 자동 OFF 후 setup 결과 유지, Wi-Fi 단절, 같은 서비스의 새 세대/이전 답변 거부, 화면 밖 전체 선택 및 타 검색 선택 보존, blocked/unknown 제외, TTL·빌드·패키지 캐시 무효화, 사용자 확인 창 취소/동의, 페어링 전용 서비스의 시간 초과·늦은 시작·중복 시작·백그라운드 시작 금지·성공/취소 정리다.

debug/release APK 빌드·서명·ZIP 및 native 16KiB 정렬·라이선스·manifest·고정 권한/설정 표면 검사가 통과했다. 최종 lint는 각각 오류 0, 경고 23이다. 경고는 기존 reflection/실험 ABI·동기 journal 저장·도구/라이브러리 업데이트·뷰/스타일 권고 등이며 숨기기 위해 검사를 해제하지 않았다. WSS는 Android development grant를 의도적으로 사용하는 선언에만 해당 lint 설명을 붙였다.

밝은/어두운/펼친 화면/큰 글씨 안내 그림과 실제 Compose 렌더링을 생성했고, 새 Picker와 자동 관리 설정의 일반·펼친·큰 글씨 화면을 눈으로 검사했다. 실제 휴대폰 screenshot을 제품 asset으로 쓰지 않는다.

test.9 immutable Git baseline 및 tracked archive hash를 확인했다. SafetyEngine 본문, Reconcile, fresh Snapshot/getters, narrow FINE setter, journal/originalBit, singleton gate, boot 정책, 페어링 키 암호화, native transport는 보호 구간 비교를 통과했다. setup/discovery/session 종료에 필요한 변경만 추가했다.

## INFERRED

WRITE_SECURE_SETTINGS의 development grant 및 데이터 초기화 후 유지 가능성은 설치된 framework 조사와 [AOSP Android permission 구현](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/permission/java/com/android/server/permission/access/permission/AppIdPermissionPolicy.kt)에 근거한다. Fold7의 최초 부여 및 일반 UID ON/OFF는 실측했다. 동일 signer 업데이트 유지도 실측했다. 재부팅·권한을 가진 상태의 data clear 후 유지 여부는 해당 시나리오를 실제 검증하기 전까지 삼성 실측 결과로 취급하지 않는다.

NSD 계측은 앱 callback 첫 진입과 처리, 알림 요청/반환 및 자체 active notification 확인 시각을 기록한다. OS가 광고를 내보낸 정확한 시각이나 상단 팝업이 실제 보인 시각은 앱 기록만으로 알 수 없다. 지연 감소를 아직 주장하지 않으며 busy polling을 추가하지 않았다.

무선 디버깅 종료가 일부 보안 앱과의 충돌을 줄일 수 있지만 금융앱 호환이나 감지 우회를 보장하지 않는다. 개발자 옵션과 USB 디버깅은 별도 설정이다.

## NOT TESTED / 진행 중

- fresh 상태의 개발자 옵션 OFF 안내: 실제 잘못된 빌드번호 화면이 발견되어 수정했다. 수정된 휴대전화 정보 fallback은 사용자 재확인 대기.
- 장시간 Settings 유지 후 freeze 방지와 OS timeout의 실기기 확인. 알림의 실제 상단 팝업 시각과 일반적인 체감 지연 개선은 미측정.
- bulk scan과 확장 목록은 실기기에서 확인했다. 접힌 적용 불가 항목 펼치기와 전체 선택의 화면 밖 범위는 아직 모델 검사만 완료했다.
- 수정된 밀도 UI의 실제 휴대폰 가독성 확인.
- 자동 관리로 실제 Hide/Restore 및 실제 표시 OFF/ON, 재부팅 후 WSS 유지, WSS가 부여된 상태의 data clear 후 동의 OFF. 동일 signer 업데이트 유지 검사는 완료했다.

이 항목들이 끝나기 전에는 Phase7.3 실기기 acceptance 완료로 보고하지 않는다. 상세 복원 및 초기화 절차는 [복원 절차](Phase-7.3-recovery-procedure.md), 로컬 배포 감사는 artifacts의 build/safety/device/source audit에 둔다. device 원본 로그·journal·개인 키·코드는 소스 ZIP에서 제외한다.
