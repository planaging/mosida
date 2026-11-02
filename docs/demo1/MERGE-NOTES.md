# 데모1(벨류업판) 병합 메모 — 2026-10-29

`mosida-demo1-valueup.zip`을 이 저장소에 합쳤다. 원본 zip은 Downloads에 그대로 둠.

## 그대로 가져온 것

`model/`, `trip/`, `ui/`, `voice/`, `vehicle/VehicleGateway.kt`, `vehicle/FakeVehicleGateway.kt`, `MainActivity.kt` — 내용 손대지 않음.
(MainActivity는 권한 상수만 `CAR_PERMISSIONS` 배열로 바꿈: 문·좌석·주행)

## 다시 쓴 것: `vehicle/PleosVehicleGateway.kt`

README의 TODO VERIFY를 실제 SDK(Vehicle 2.3.2 classes.jar) 기준으로 확인하고 다시 썼다.

| TODO VERIFY | 결과 |
|---|---|
| 1) 오른쪽 뒷문 상수 | `DoorValue.ROW_2_RIGHT` 맞음 |
| 2) 리스너가 어느 문인지 모름 | 틀림. `registerDoorPosition(DoorArea, listener)` — 문마다 따로 등록. 재조회 불필요 |
| 3) 문 값 0=닫힘 | 에뮬레이터 Virtual Console로 확인 (결과는 아래 "확인 로그") |
| 4) 좌석 착석·벨트 | `registerSeatOccupancy(SeatArea, SeatOccupancyListener)` / `registerSeatBeltBuckledState(...)` 연결함. 좌석 열 매핑은 `seatMap` |
| 5) NaviHelper | 미연결(null) |
| 주행 중 판단 | `Gear`로 판단: P·UNKNOWN이면 정차, 그 외 주행. (속도 API 없음) |

## 빌드 구조 (README "오늘 밤 순서"와 다른 점)

- Android Studio New Project 대신 이 저장소를 그대로 연다. Gradle wrapper 포함.
- Pleos SDK 버전은 **2.3.2** (문서의 2.3.1은 저장소에 없음).
- Java는 `C:\Android\jdk-21` (Studio 번들 Java 25는 Gradle 8.x 미지원). 전역 `gradle.properties`에 고정.
- 빌드: `.\gradlew.bat :app:assembleDebug` → `app\build\outputs\apk\debug\app-debug.apk`
- 설치: `adb install -r -g app-debug.apk`
- 런처에 앱 2개: **모시다**(시연 앱) / **모시다 신호 점검**(probe, 개발용)

## 확인 로그 (2026-10-29, Pleos Connect v2.1.0 에뮬레이터, Vehicle 서비스 2.3.22)

probe 앱(`SignalProbeActivity`)으로 확인. Virtual Console API로 신호를 보내고 logcat `MosidaProbe`로 수신 확인.

| 신호 | 결과 |
|---|---|
| **문 위치** | ✅ 1열·2열 좌우 + 트렁크(REAR) 6곳. **0=닫힘, 1=열림**. 변화 알림 약 1초 안에 수신 |
| 문 3열 | ❌ `area ID not supported for DOOR_POS` (에뮬레이터 차량이 2열 모델) |
| **좌석 착석** | ✅ 1열 2석 + 2열 3석(좌·중·우). 초기값 2열 전부 `true` |
| **안전벨트** | ✅ 같은 5석. 초기값 `false` |
| 좌석·벨트 3열 | ❌ `area ID not supported` |
| **기어** | ✅ P↔D 변화 알림 약 1초 |
| **주행거리** | ✅ `PERF_ODOMETER` 5초 주기 갱신 (0.0) |
| 권한 | `CAR_DOORS`·`CAR_SEATS`·`CAR_DRIVE` 설치 시 자동 부여(`-g`). 별도 동의창 없음 |

→ `BoardingSignalSource`는 **`DoorSignalSource`(문 신호)를 기본**으로 간다. `ManualTapSource`는 실차에서 신호가 없을 때의 대체.
→ 3열(스타리아·카니발)은 에뮬레이터로 시험 불가. 지원 안 되는 좌석은 "모름"으로 두는 현재 설계 유지.

### Virtual Console을 명령어로 쓰기

브라우저 없이도 조작 가능. 서버가 1초 주기 하트비트(`POST /browser/session?client=<id>`)를 받아야 `emulator_active`가 된다(3초 끊기면 비활성).

- `C:\Android\vc-heartbeat.ps1` — 하트비트를 계속 보낸다 (백그라운드로 띄워 둔다)
- `C:\Android\vc.ps1 door rr open|close` / `gear P|D` / `speed 30` / `state`
- 문 파라미터: `PUT /vehicle/body?door_rr=1` (fl/fr/rl/rr), 기어: `PUT /vehicle/gear?gear=Drive`

### 알아둘 것

- 에뮬레이터에 **한국어 TTS 엔진이 없다** (`tts_default_synth=null`). Pleos 자체 `caas` SpeechService만 있음 → `Announcer`는 조용히 넘어감. 시연 영상에 음성이 필요하면 실차 또는 Pleos TTS SDK로.
- 빌드 중 에뮬레이터를 같이 돌리면 메모리 부족으로 Android가 재시작될 수 있다(12GB에서도). 빌드 끝난 뒤 설치.
- 에뮬레이터를 강제 종료하면 다음 실행 때 "crash report" 창이 떠서 부팅이 멈춘다 → 창을 닫아야 진행.
