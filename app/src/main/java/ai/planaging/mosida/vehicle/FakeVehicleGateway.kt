package ai.planaging.mosida.vehicle

import ai.planaging.mosida.model.Guidance
import ai.planaging.mosida.model.SeatPos
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 시연용 가짜 차량. 화면 아래 "시연 패널" 버튼으로 값을 바꾼다.
 * Pleos SDK 연결이 막혀도 데모 영상은 이걸로 찍을 수 있다.
 */
class FakeVehicleGateway : VehicleGateway {
    private val _door = MutableStateFlow(false)
    private val _moving = MutableStateFlow(false)
    private val _seats = MutableStateFlow<Map<SeatPos, Boolean>?>(emptyMap())
    private val _belts = MutableStateFlow<Map<SeatPos, Boolean>?>(emptyMap())

    override val rearRightDoorOpen: StateFlow<Boolean> = _door
    override val isMoving: StateFlow<Boolean> = _moving
    override val seatOccupied: StateFlow<Map<SeatPos, Boolean>?> = _seats
    override val seatBeltBuckled: StateFlow<Map<SeatPos, Boolean>?> = _belts

    /** 시연용 길안내. "주행 시작"을 누를 때마다 다음 구간 안내로 바뀐다. */
    private val legs = listOf(
        Guidance(turn = "300m 앞 우회전", road = "가람로 방면", distance = "1.2km", etaMin = 3),
        Guidance(turn = "150m 앞 좌회전", road = "남춘로 방면", distance = "0.8km", etaMin = 2),
        Guidance(turn = "500m 직진", road = "나래동 방면", distance = "2.4km", etaMin = 6),
        Guidance(turn = "200m 앞 우회전", road = "다솜동 방면", distance = "1.6km", etaMin = 4),
    )
    private var leg = 0
    private val _guidance = MutableStateFlow<Guidance?>(legs[0])
    override val guidance: StateFlow<Guidance?> = _guidance

    override fun start() {}
    override fun stop() {}

    fun setDoorOpen(open: Boolean) { _door.value = open }

    fun setMoving(moving: Boolean) {
        // 정차 → 주행으로 바뀔 때 다음 구간으로 (첫 출발은 첫 구간 그대로)
        if (moving && !_moving.value && leg++ > 0) _guidance.value = legs[(leg - 1) % legs.size]
        _moving.value = moving
    }

    fun setSeatOccupied(seat: SeatPos, occupied: Boolean) {
        _seats.value = (_seats.value ?: emptyMap()) + (seat to occupied)
    }

    fun setBeltBuckled(seat: SeatPos, buckled: Boolean) {
        val current = _belts.value ?: emptyMap()
        if (!buckled && current[seat] != true) {
            // "풀림"은 채웠다가 풀린 경우만 경고한다. 시연 버튼은 바로 풀 수 있게 먼저 채운 것으로 보낸다.
            _belts.value = current + (seat to true)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                _belts.value = (_belts.value ?: emptyMap()) + (seat to false)
            }, 150)
            return
        }
        _belts.value = current + (seat to buckled)
    }
}
