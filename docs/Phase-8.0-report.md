# bluedotOFF test.17 / Phase8.0

## 구현
- 숨김: #356AFF 파란 물결이 빠져 시스템 배경이 보임. 복원: #191C20 검정 물결이 빠져 #356AFF 배경이 보임. 완료 후 시스템 테마. 작은 수학식 Canvas, 추가 미디어·의존성 없음. 시스템 애니메이션 OFF는 정적 표시.
- 성공한 복원 안내만 완료 시각 기준 5초 후 숨김. 오류·연결 종료 경고는 유지. Activity 재생성 때도 남은 시간 사용.
- 일반 복원은 Hide와 같은 최대 4개 확인 대기 겹침, 12개/55초 연결 묶음. Binder 읽기·쓰기 직렬 유지, 즉시/500ms/1500ms 검증 유지.
- 공유 UID는 별도 전체 묶음 경로. 개별 guard 유지. 2~8개 전체 peer·서명·설치·권한·AppOps·firmware/ABI 재검증. 비선언 companion도 모든 설치 및 비변경 상태를 고정. 모든 original record를 원자적으로 저장한 뒤 UID당 단일 FINE mask256 setter. 전체 묶음만 복원. 구성원·소유 변경, 불확실한 원래 값, core/persistent는 차단.
- 화면 포커스를 잃으면 새 대상은 시작하지 않음. 시작한 확인 및 기록 저장은 완료 시도. 자동 재개/부팅 쓰기 없음. 연결 단절·저장 오류에서는 성공을 주장하지 않음.

## 자동 검증
core 297개, debug 211개, release 211개: 총 719개 통과. Debug/release 빌드 및 lint 오류 0. 정확한 결과·APK 크기·서명은 build-audit JSON 참고.
74개 복원 모델(읽기10ms, 저장3ms): 이전 순차 115884ms, 겹침 30805ms. 실제 휴대폰 시간으로 주장하지 않음.
저장 실패·소유 변경·구성원 변경·중단·원래 OFF 보존·단일 setter·개별 shared guard·schema1 journal 호환·알림5초·다크모드·영어 안내 검사 포함.

## 현재 Fold7 읽기 전용 조사
Phone com.samsung.android.dialer + KT114 com.samsung.kt114provider2, application UID10129, namespace com.samsung.android.uid.dialer. 정확한 peer 2개, persistent/core 아님. FINE/COARSE 0x300/0x300 읽음. KT114는 위치를 직접 선언하지 않는 companion이므로 직접 권한 허용으로 오인하지 않음.
기존 Phase6.3 공유 더미의 UID 단위 변경 관찰 및 설치된 framework의 appId 저장 구현과 대조. 이것만으로 새 전화 묶음 실제 변경 시험 통과를 주장하지 않음.

## 실기기 상태와 제한
최종 설치 및 읽기 검증 결과는 device-validation-1.0.0-test.17.json에 기록. 사용자 승인 범위의 전화 묶음 Hide/Restore와 일반 앱 복원 실측 완료. 실제 수신 전화 시험은 아직 하지 않음.
모든 Galaxy 기종 검증 아님. 공유 앱 선택은 전체 묶음에만 적용하고 core/persistent/소유 불확실 상태는 사용자 동의로 우회하지 않음. OS가 비트를 다시 켤 수 있음. 삭제/데이터 초기화로 자동 복원되지 않음.

## 근거
- Android Activity lifecycle: https://developer.android.com/guide/components/activities/activity-lifecycle
- Process lifecycle: https://developer.android.com/guide/components/activities/process-lifecycle
- AOSP PermissionManagerServiceImpl shared-user permission state: https://android.googlesource.com/platform/frameworks/base/+/c7c5ed7080db54842663da506f44b107c87cfa3a/services/core/java/com/android/server/pm/permission/PermissionManagerServiceImpl.java

## test.17 최종 읽기 전용 설치 검증
USB 업데이트 설치 성공. 기존 등록74개·원래 비트 기록·페어링 키 hash 보존. 표본5개 FINE/COARSE/grants/AppOps 동일, 새 위치 setter event0. 전화+번호 안내 서비스 이름과 패키지 두 개·전체 선택 버튼 실기기 확인. 자동 관리가 조회 때 ON→OFF, 최종 OFF. 충전 중 화면 유지7 확인.
전체 변경 APK 증가: debug 172316 bytes, release 66616 bytes. 애니메이션 미디어 추가0, 의존성 변경0. 기존 lint 경고32개 유지, 오류0.
새 그룹 변경·일반 복원 실측 완료. 실제 수신 전화와 전화 앱의 위치 사용 중 인디케이터 육안 검증은 미실시.

## 승인된 실기기 최종 시험
전화+번호 안내 서비스: 단일 UID setter로 두 앱 모두 FINE0x300→0x200→0x300. 즉시/500ms/1500ms 검증, 전체 grants/AppOps/peers/설치 신원/다른 플래그 유지. 중단 사이 묶음에 추가 Hide 기록이 생겼고, 재개 때 사용자에게 현재 상태 선택을 받아 숨김 유지. 최종 두 앱 모두0x200, originalBit256 기록 유지.
카카오맵+메시지: 두 앱 모두0x200→0x300 복원, 이후 승인한 재적용으로0x200 복귀. 실제 setter 간격66ms, 첫 before부터 마지막 settled1500ms까지1663ms. 연결 준비·종료 시간은 제외한 수치. 74개 전체 실제 복원 시간은 미측정이며 115884→30805ms는 모델 결과.
복원 성공 배너는 탭 후7.078s와9.141s 표본에서 표시,11.203s 표본에서 사라짐. 약2.06초 간격 UI 표본으로 표시·소멸 확인, 정확한5초는 코드/모델 검사로 확인. 오류·연결 종료 경고 유지 검사 통과.
최종 활성 등록76개(기존74+전화 묶음2). 기존74의 원래 비트·설치 신원·페어링 키 hash 보존. 기존 표본5개 metadata 동일. 실제 target setter 총7회(전화 묶음3, 카카오맵2, 메시지2), 다른72개 setter0. 모든 검증 단계에서 승인된4개 구성원의 권한·AppOps·비민감도 플래그 유지. 최종 무선 디버깅OFF, USB/개발자 옵션 유지, 충전 화면 유지7. 원복/재적용 필수 시험 잔여0.
전화 알림만 보이고 앱이 resumed이면 계속할 수 있음. 전체 통화 화면 등으로 Activity가 pause되면 새 대상 변경 중지, 시작한 검증은 완료 시도, 복귀 시 자동 재개하지 않음. 실제 수신 통화는 시험하지 않았으므로 통화 수신 시 모든 OEM 동작을 검증했다고 주장하지 않음.
