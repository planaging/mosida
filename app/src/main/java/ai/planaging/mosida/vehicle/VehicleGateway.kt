package ai.planaging.mosida.vehicle

import ai.planaging.mosida.model.Guidance
import ai.planaging.mosida.model.NaviStop
import ai.planaging.mosida.model.SeatPos
import kotlinx.coroutines.flow.StateFlow

/**
 * 앱이 차량에서 필요한 값만 모아 둔 창구.
 * - 시연·테스트: FakeVehicleGateway (화면 버튼으로 문 열림·착석을 흉내)
 * - 실제 Pleos 차량/에뮬레이터: PleosVehicleGateway
 * 화면과 운행 로직은 이 인터페이스만 보므로, 어느 쪽을 써도 동작이 같다.
 */
interface VehicleGateway {
    /** 오른쪽 뒷문(발판 있는 주 출입구)이 열려 있는지 */
    val rearRightDoorOpen: StateFlow<Boolean>

    /** 차가 움직이는 중인지. true면 터치 조작을 막는다. */
    val isMoving: StateFlow<Boolean>

    /**
     * 기어 값을 실제로 받았는지. false면(값 없음·UNKNOWN·수신 전) 정차로 간주하지 않고
     * 승하차 확인 창을 막는다. 운행 기록 저장은 그대로 한다.
     */
    val gearKnown: StateFlow<Boolean>

    /**
     * 좌석별 착석 감지. 차량이 지원하지 않으면 null.
     * null인 좌석은 "모름"으로 보고 기록만으로 판단한다.
     */
    val seatOccupied: StateFlow<Map<SeatPos, Boolean>?>

    /**
     * 좌석별 안전벨트 체결 상태. true = 체결, false = 풀림. 지원 안 하면 null.
     * 맵에 없는 좌석은 "모름".
     */
    val seatBeltBuckled: StateFlow<Map<SeatPos, Boolean>?>

    /** 계기판 주행거리(km). 읽을 수 없으면 null */
    val odometerKm: StateFlow<Double?>

    /** 길안내 (NaviHelper 경로 정보 조회). 없으면 null */
    val guidance: StateFlow<Guidance?>

    /**
     * 남은 정류장을 차량 내비 경로로 보낸다(순서대로). 비어 있으면 경로 취소.
     * 차량 내비는 경유지 2곳까지만 받으므로, 구현체가 [다음, 그다음] + 마지막 집으로 줄여 보낸다.
     * 하차할 때마다 다시 불러 경유지를 굴린다(롤링).
     */
    fun routeTo(stops: List<NaviStop>) {}

    fun start()
    fun stop()
}
