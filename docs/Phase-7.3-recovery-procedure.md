# test.10 실기기 검증 전 복원 절차

현재 journal은 비어 있고, 미세미세와 날씨날씨의 FINE 표시 비트는 OFF다. 빈 journal에서 새로 등록해 복원하면 원래 상태를 잘못 기록할 수 있으므로 먼저 과거 증거로 확인한다.

2026-10-05 코드 변경 전 읽기 전용 비교에서 두 앱 모두 과거 snapshot과 package/version/install/update/UID/authoritative peers/APK hash가 같았다. 과거 journal의 signer와 최초 설치·업데이트 시간도 일치하며 알려진 originalBit는 각각 256이다. dumpsys 날짜는 초 단위이므로 과거 journal의 밀리초는 초 단위로 비교했다. 날짜만으로 동일 설치를 판단하지 않는다. 원본 증거는 배포하지 않는 로컬 research에 보존한다.

자동 검사 완료 후 다음 순서로만 진행한다.

1. 현재 metadata·권한 grant·raw AppOps·journal·개발자/USB/무선 설정·페어링 상태를 읽어 다시 기록한다. 페어링 상태의 존재와 해시만 기록하며 개인 키와 코드는 보고서나 소스 ZIP에 넣지 않는다.
2. 두 앱의 설치 신원·signer·APK·UID peers를 직전 값 및 과거 증거와 다시 대조한다. 값이 다르거나 원래 비트가 불명확하면 중단한다.
3. 검증된 permission Binder ABI에서 두 앱에만 mask 0x100, value 0x100 복원 쓰기를 한다. 이미 ON이면 쓰지 않는다. NAVER 등 다른 앱의 OFF를 일괄 ON으로 바꾸지 않는다.
4. 즉시·500ms·1500ms 읽기에서 FINE의 0x100 이외 비트, COARSE 전체, grant, AppOps, identity, authoritative peers가 보존됐는지 확인한다. 쓰기 및 결과는 TARGET_RECOVERY로 구분한다.
5. 기존 journal과 별개인 과거 소유 증거로 수행한 복원임을 기록한다. 빈 journal에 추정 originalBit를 만들어 넣지 않는다.
6. test.10 동일 signer 업데이트 후 먼저 데이터를 보존한 상태로 읽기 전용 확인을 한다.
7. 앱 데이터 초기화는 직전 별도 승인을 받는다. 기존 앱 데이터와 내부 페어링 키가 사라져 재페어링이 필요하며, 암호화 파일 사본만으로 Android Keystore 키를 복구할 수 있다고 주장하지 않는다. 기존 위치 효과와 복원 기록이 남아 있으면 초기화하지 않는다.
8. 사용자가 앱에서 자동 관리 사용에 명시적으로 동의한 뒤에만 자기 WRITE_SECURE_SETTINGS 부여를 시험한다. 그 직전 audit를 다시 저장한다. 일반 앱 UID의 설정 쓰기는 SYSTEM_SETTING, 자기 권한 변경은 SELF_PERMISSION, 위치 표시 비트 쓰기는 TARGET_METADATA로 구분한다.

초기 상태의 개발자 옵션/Wi-Fi/무선 디버깅 변경은 사용자가 실제 OS 화면에서 진행한다. 자동 입력, 비공개 화면 강제 진입, 보안 확인 우회는 하지 않는다. 가능한 단계만 실측하고 나머지는 미실측으로 표시한다.

실측에서 FirmwareVersionActivity는 삼성 개발자 옵션 화면이 아닌 별도 Android 버전 화면으로 확인됐다. 앱 후보에서 제거했으며 공개 휴대전화 정보 화면 fallback을 사용한다.
