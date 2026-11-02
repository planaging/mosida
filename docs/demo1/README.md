# 모시다 데모1 — 좌석 배치 + 하차 확인 (벨류업판)

> 이 폴더는 벨류업판이다. 추가 기능·새 시연 순서는 **VALUEUP.md** 를 먼저 볼 것. 아래는 원본 README.

출발 전 차종(스타리아 11 / 카니발 9 / 레이 4) 좌석판에 어르신을 앉히고 운행을 시작한다.
운행 중 화면은 [좌석 | 가운데 길안내 | 다음 하차] 3칸.
오른쪽 뒷문이 열리면 "○○ 님 하차하셨나요?" 확인 창이 뜨고, 사람이 확인해야만 기록이 남는다.
탑승자가 남아 있거나 착석이 감지되면 운행 종료가 잠긴다.

## 파일 구성

| 파일 | 역할 |
|---|---|
| `model/Models.kt` | 차종 좌석판(스타리아·카니발·레이), 어르신, 운행 기록, 종료 잠금 사유, 길안내 |
| `trip/TripController.kt` | 핵심 규칙 (좌석 배치·자동 배치, 결석, 문 열림 → 확인 창, 보호자 인계, 하차 취소, 종료 잠금, 요약, CSV) |
| `trip/SampleData.kt` | 시연용 가상 명단 7명 (실명·주소 없음) |
| `vehicle/VehicleGateway.kt` | 차량 값 창구 (뒷문, 주행 중, 좌석 착석) |
| `vehicle/FakeVehicleGateway.kt` | 시연 패널 버튼으로 차량 값을 흉내 |
| `vehicle/PleosVehicleGateway.kt` | Pleos Vehicle SDK로 실제 도어 값 읽기 |
| `voice/Announcer.kt` | 음성 안내 (안드로이드 기본 TTS) |
| `ui/Screens.kt` | 좌석 배치 · 운행(정차) · 주행 · 하차 확인 · 종료 잠금 · 기록 + 시연 패널 |
| `MainActivity.kt` | 시작점. `useFakeVehicle`로 가짜/실제 차량 전환, `useVoice`로 음성 켜고 끄기 |

## 오늘 밤 순서

1. Android Studio → New Project → **Automotive(또는 Car) → Empty Activity** (Compose)
   - Name `모시다`, Package name **`ai.planaging.mosida`**, Language Kotlin, Minimum SDK **API 28**
2. 이 폴더의 `app/src/main/java/ai/planaging/mosida/` 아래 파일들을 프로젝트의 같은 위치에 복사 (템플릿의 MainActivity.kt는 덮어쓰기)
3. `AndroidManifest.xml`에서 권한 1줄, `uses-feature`, `<queries>` 블록을 프로젝트 매니페스트에 합치기 (템플릿 테마는 그대로 둬도 됨)
4. `settings.gradle.kts`의 `repositories`에 추가
   ```kotlin
   maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
   ```
5. `app/build.gradle.kts`의 `dependencies`에 추가 → Sync
   ```kotlin
   implementation("ai.pleos.playground:Vehicle:2.3.1")
   ```
6. Pleos 에뮬레이터 선택 → Run

## 시연 (가짜 차량 모드, 기본값)

벨류업판 시연 순서는 VALUEUP.md 참고.

색 의미는 고정: 파랑 = 누르는 버튼, 초록 = 탑승·안전 확인, 주황 = 다음 하차·확인 필요, 빨강 = 위험.

## 실제 Pleos 도어로 바꾸기

1. `MainActivity.kt`에서 `useFakeVehicle = false`
2. `PleosVehicleGateway.kt`의 **TODO VERIFY** 3곳 확인
   - 오른쪽 뒷문 상수: `DoorValue.ROW_2_RIGHT`로 가정함 → API Reference > DoorValue에서 실제 이름 확인
   - import 경로가 빨간 줄이면 Alt+Enter로 정리
   - 문 값: 0 = 닫힘, 0보다 크면 열림으로 가정
3. Virtual Console에서 오른쪽 뒷문을 열고 닫으며 확인 창이 뜨는지 확인

## 아직 안 한 것 (2단계)

- 좌석 착석·벨트 센서를 Pleos SDK에 실제 연결 (PleosVehicleGateway.kt TODO VERIFY 4 — Seat 도메인의 registerSeatOccupancy / registerSeatBeltBuckledState, 먼저 check…Capabilities로 뒷좌석 지원 확인). 가짜 차량 모드에서는 이미 동작
- 길안내를 NaviHelper SDK에 연결 (PleosVehicleGateway.kt TODO VERIFY 5). "차량 내비로 길안내" 버튼은 TripController.sendNextToVehicleNavi()에 연결 예정
- 주행 중 판단을 CarUxRestrictions로 연결
- 센터 알림("센터에 바로 알리기" 버튼)
- 운행일지 자동(주행거리), 배차
