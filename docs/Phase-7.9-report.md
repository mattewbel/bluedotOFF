# bluedotOFF test.16 — 화면·알림 설정 개선

2026-10-07. 사용자 요청 14개 항목을 test.15 기준으로 구현했다. 기준 APK·소스·감사는 baselines/test15-before-ux-refresh에 보존했다.

| 요청 | 처리 |
|---|---|
|1 연결 필요 강조|시스템 tertiaryContainer/onTertiaryContainer와 얇은 경계. 테마 대응.|
|2 상단 점 이동|홈의 72dp 전용 줄 제거, 공통 제목 오른쪽에 18dp 파란 점. 연결 준비도 같은 위치.|
|3 물 빠지는 진행 배경|실제 BatchProgress 퍼센트의 남은 비율을 부드럽게 그린다. 작업 화면 뒤 기존 내용은 숨기고 스크롤 상태는 보존. timer·네트워크·작업 순서 변경 없음. 99%는 연결 종료 대기, 100% 후 시스템 테마. 애니메이션 OFF는 즉시 상태 표시.|
|4 단순 아이콘|런처/adaptive/monochrome와 알림 아이콘의 장식 막대 제거, 파란 원 한 개.|
|5 시작 설명 축약|신뢰하는 앱의 위치 기능과 기록은 유지하고 위치 표시만 숨긴다는 문장까지만.|
|6 도움말 순서|알림·개발자·Wi-Fi·무선·코드·통합 확인·자동 관리·홈 순서. 코드 입력 번호는 실제 4/5. 분할 화면 대안 별도. shared JSON→SVG→앱 도움말 동일 내용.|
|7 알림 단계 급진행|권한 요청 중 resolved=false 및 화면 유지. 결과가 허용일 때만 진행. 거절/닫기는 알림 단계 유지. 뒤로/알림 없이 계속에서 권고+재허용/분할 계속/돌아가기. 반복 거절·전체 OFF는 앱 알림 설정으로 연결.|
|8 분할 화면 강조|제목·굵은 색상 배너·입력 전 안내. 코드 창을 닫으면 연결할 수 없다는 설명 유지.|
|9 전화 및 유사 앱|안전 판정을 완화하지 않았다. 사전설치 FLAG만으로 차단하는 조건은 없고 독립 UID 및 검증된 singleton은 기존대로 허용. 여러 패키지가 권한을 공유하는 대상은 개별 OFF 안전 조건을 만족하지 않음. 기기별 판정.|
|10 상태 표현|현재 표시 없음→이미 적용된 앱. 영어 Already hidden. 단, 이 OFF가 bluedotOFF가 썼다는 증명이 아니므로 현재 꺼짐 상태를 새 복원 기준으로 저장한다는 안내를 함께 유지.|
|11 권한 제거|스위치 OFF는 동의만 OFF, 시스템 권한 회수와 다르다. 버튼은 고급 설정 안으로 이동하고 차이 설명.|
|12 경계와 빈 공간|등록 목록을 테마 Surface와 outlineVariant 경계로 감싼다. 제목 앞 빈 점 영역과 불필요한 큰 위 여백 제거.|
|13 기종 호환성|모델 제한 없이 삼성·SDK·실제 ABI를 검사하는 기존 구조 유지. ARM64 native 연결 전 CPU 사전 검사 추가. 구형 Android에서도 최신 뒤로가기 API를 무조건 호출하지 않음. 화면 폭·큰 글씨·테마별 렌더링 확인. 모든 Galaxy 지원을 보장하지 않음.|
|14 쉬운 영어|새 문구를 모두 검토·번역. 매개변수·패키지명·기록은 변경하지 않음. 영어 다이어그램의 긴 문구가 카드 밖으로 넘지 않도록 표시 폭 제한, 전체 설명은 본문에 제공.|

## 안전 조건과 호환성 범위

Firmware/Framework Binder/History/SystemUI/CSC 프로필 검증은 동일하다. SDK/One UI 버전만으로 지원 판정을 끝내지 않는다. 현재 프로필은 ARM64·One UI 9 이상·SDK37 이상·검증된 내부 API를 요구하고 미국 CSC는 차단한다. Legacy singleton shared-user 증거는 firmware에 묶여 있어 다른 기기에서는 검증되지 않은 상태로 차단될 수 있다. 이 제한을 근거 없이 풀지 않았다. 실제 write allowlist·권한 grant·AppOps·UID 범위·원복 저널은 유지했다.

전화 앱의 공유 UID 판정은 앱별 조작이 다른 구성 요소에 영향을 줄 수 있어 사용자 선택만으로 해소되지 않는다. 개별 narrow 경로에서 peers 한 개를 요구하는 기존 SafetyEngine/LocalWriteGuard/helper 조건을 유지한다. 일반 독립 앱과 사전설치 독립 앱은 동일 경로다.

## 근거

알림 권한은 허용·거절·창 닫기의 실제 결과를 기다려야 한다. [Android 알림 권한 공식 문서](https://developer.android.com/develop/ui/compose/notifications/notification-permission). sharedUserId는 여러 패키지에 같은 사용자 ID를 부여한다. [Android manifest 공식 문서](https://developer.android.com/guide/topics/manifest/manifest-element). adaptive/monochrome 아이콘은 시스템 테마 설정에 따라 색이 달라질 수 있다. [Android adaptive icon 공식 문서](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive). 설치된 기기의 원본과 보호 코드 비교가 최종 적용 판정의 근거다.

## 최종 검사

빌드·자동 검사·실기기 결과는 확정 후 아래 및 artifacts 감사 JSON에 기록한다. 화면 모의 렌더링과 물리 기기 확인은 구분한다. 새 설치 실기기 알림 권한 거절·재허용을 검사하기 위한 데이터 초기화나 권한 강제 변경은 별도 승인 없이 하지 않는다.


### 확정한 자동 검사 결과

최종 Gradle 빌드 성공. core 274 + Android debug 200 + release 200 = 674개, 오류·실패·skip 0. debug/release APK 서명과 16KiB ZIP/native ELF 정렬 통과. lint 오류 0, 경고 각 32개. 알림 요청 대기/결과/거절 후 뒤로/명시적 분할 계속, 삼성·SDK·ABI 사전 조건, 구형 SDK30 지원 불가 안내, 실제 진행률/99% 종료 대기, 진행 화면 밑 기존 내용의 시각·접근성 숨김, 제목과 점의 같은 줄, 고급 권한 제거, 테마 대비 4.5 이상을 검증했다.

밝은/어두운 홈, 연결 안내, 한국어/영어, 좁은/넓은 화면, 큰 글씨, 수동 분할 화면, 진행률 50% 화면을 렌더링하고 이미지를 확인했다. 새 한국어 화면 문구의 영어 누락 검사 및 모든 guide JSON 필드의 영어 대응 검사도 통과했다. UI에는 원래 비트 출처를 추정하거나 이미 OFF인 앱을 자동 등록하는 변경이 없다.

AndroidX ComponentActivity의 OnBackPressedDispatcher를 연결 준비 화면에 사용한다. Android16+의 뒤로가기 제스처가 과거 onBackPressed override를 호출하지 않는 문제를 피한다. FLAG_SECURE는 유지한다. 예전 70/71/72 권한 결과 경로는 보존했고 권한 결과 배열 형식을 새 부모 클래스와 맞췄다. 알림 권고 화면의 선택은 실제 결과 또는 명시적 건너뛰기에서만 처리된다.


### Fold7 실기기 읽기 전용 확인

test.16을 기존 데이터 보존 업데이트로 설치했다. 등록 74개 원복 기준과 페어링 키 해시, 자동 관리 동의를 보존했다. 홈의 제목 오른쪽 파란 점과 다크 테마 목록 경계를 실제 화면에서 확인했다. 앱 추가 목록은 정상 표시되고, 설정의 권한 제거 버튼은 고급 설정을 펼쳤을 때만 나타난다. 읽기 전용 스캔은 설치 패키지 778개, 적용 가능한 현재 OFF 74개, 지원 프로필 정상으로 완료됐다. 등록한 앱이 OFF라는 이유로 후보에서 빠지는 현상은 재현되지 않았다.

기존 자동 관리 동의로 무선 디버깅 ON→읽기 전용 조회→OFF 이벤트를 확인했다. 최종 무선 디버깅 OFF, 개발자 옵션·USB 디버깅 ON, Wi-Fi ON. 사용자의 충전 중 화면 유지 요청에 따라 기존 0을 7로 다시 켰다. 이 설정 변경과 무선 관리 이벤트는 대상 위치 메타데이터 쓰기와 별도로 기록했다.

대상 위치 메타데이터 쓰기 0회, 앱 데이터 초기화 0회. 대표 5개 앱(카카오맵·미세미세·날씨날씨·당근·네이버지도)의 FINE/COARSE flags·권한 grant·raw AppOps mode·UID peers가 설치 전후 동일했다. 카카오맵은 이번 설치 직전부터 FINE 0x200이었다. 원복 기록과 setter 이벤트 비교에서도 새 대상 쓰기가 없었다. 전체 74개의 개별 flags를 이번 실기기 비교로 모두 검증했다는 뜻은 아니다.

전화 앱 com.samsung.android.dialer의 실제 UID는 10129이며 peers 전체는 com.samsung.android.dialer와 com.samsung.kt114provider2 두 개다. core/src/main/kotlin/app/bluedotoff/core/Eligibility.kt:25의 `peers.size > 1`이 MULTI_PACKAGE_SHARED_UID로 차단한다. 사전설치 여부가 원인이 아니다. 전화만 별도로 설정하면 공유 구성 요소에 영향을 줄 수 있어 개별 narrow 경로를 열지 않았다.

최초 설치의 알림 허용·거절·재허용, 수동 분할 페어링, 실제 적용 중 물 빠짐 애니메이션은 이번 물리 기기에서 다시 시험하지 않았다. 이 경로는 자동 검사와 화면 렌더링으로 검증했다. 기존 74개 원복 기록을 없애는 초기화나 미승인 대상 쓰기로 실기기 시험을 만들지 않았다. 다른 Galaxy 모델의 실기기 호환성도 미검증이다.
