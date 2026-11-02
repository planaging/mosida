package ai.planaging.mosida.vehicle

import ai.planaging.mosida.model.Guidance
import ai.planaging.mosida.model.NaviStop
import ai.planaging.mosida.model.SeatPos
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Pleos 차량(에뮬레이터 포함)에 붙는 구현. Vehicle SDK 2.3.2 기준.
 *
 * - 문: 오른쪽 뒷문(ROW_2_RIGHT) 위치 리스너. 값이 0보다 크면 열림.
 * - 주행 중: 기어가 P가 아니면 주행 중으로 본다. (속도 API가 없어 기어로 판단)
 * - 좌석: 착석·벨트 리스너. 차량이 그 좌석을 지원하지 않으면 맵에 넣지 않는다("모름").
 * - 길안내: [PleosNavi] (NaviHelper). 남은 정류장을 경로로 보내고 회전 안내를 받는다.
 *
 * SDK 콜백은 바인더 스레드에서 올 수 있어 StateFlow 갱신은 메인 스레드로 모은다.
 */
class PleosVehicleGateway(context: Context) : VehicleGateway {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private val cleanups = mutableListOf<() -> Unit>()

    private val _door = MutableStateFlow(false)
    private val _moving = MutableStateFlow(false)
    private val _seats = MutableStateFlow<Map<SeatPos, Boolean>?>(null)
    private val _belts = MutableStateFlow<Map<SeatPos, Boolean>?>(null)

    override val rearRightDoorOpen: StateFlow<Boolean> = _door
    override val isMoving: StateFlow<Boolean> = _moving
    override val seatOccupied: StateFlow<Map<SeatPos, Boolean>?> = _seats
    override val seatBeltBuckled: StateFlow<Map<SeatPos, Boolean>?> = _belts
    private val _odometer = MutableStateFlow<Double?>(null)
    override val odometerKm: StateFlow<Double?> = _odometer
    private val navi = PleosNavi(appContext)
    override val guidance: StateFlow<Guidance?> = navi.guidance

    override fun routeTo(stops: List<NaviStop>) = navi.routeTo(stops)

    private val rearRight = DoorArea(DoorValue.ROW_2_RIGHT)

    /** SDK 좌석 ↔ 앱 좌석판 위치. 열 번호는 앱 기준(1열 = 운전석 줄). */
    private val seatMap: Map<SeatValue, SeatPos> = mapOf(
        SeatValue.ROW_1_RIGHT to SeatPos(1, 2),
        SeatValue.ROW_2_LEFT to SeatPos(2, 0),
        SeatValue.ROW_2_CENTER to SeatPos(2, 1),
        SeatValue.ROW_2_RIGHT to SeatPos(2, 2),
        SeatValue.ROW_3_LEFT to SeatPos(3, 0),
        SeatValue.ROW_3_CENTER to SeatPos(3, 1),
        SeatValue.ROW_3_RIGHT to SeatPos(3, 2),
    )

    override fun start() {
        val v = Vehicle(appContext)
        v.initialize()
        vehicle = v
        // 서비스 바인딩이 비동기라 잠깐 뒤에 등록한다.
        main.postDelayed({ if (vehicle === v) subscribe(v) }, INIT_DELAY_MS)
        // 내비는 부가 기능: 실패해도 문·좌석 신호는 그대로 쓴다.
        runCatching { navi.start() }.onFailure { Log.w(TAG, "navi start failed: ${it.message}") }
    }

    override fun stop() {
        runCatching { navi.stop() }
        cleanups.forEach { runCatching { it() } }
        cleanups.clear()
        vehicle?.let { runCatching { it.release() } }
        vehicle = null
    }

    private fun subscribe(v: Vehicle) {
        // ── 문 ──
        val door = v.getDoor()
        val doorL = object : DoorPositionListener {
            override fun onPositionUpdated(pos: Int?) = setDoor(pos)
            override fun onFailed(e: Exception) = warn("door listener", e)
        }
        door.getCurrentDoorPosition(rearRight, { pos: Int? -> setDoor(pos) }, { e -> warn("door read", e) })
        door.registerDoorPosition(rearRight, doorL)
        cleanups += { door.unregisterDoorPosition(rearRight, doorL) }

        // ── 기어 → 주행 중 ──
        val gear = v.getGear()
        val gearL = object : GearSelectionListener {
            override fun onSelected(g: VehicleGear?) = setGear(g)
            override fun onFailed(e: Exception) = warn("gear listener", e)
        }
        gear.getCurrentGearSelectionState({ g: VehicleGear? -> setGear(g) }, { e -> warn("gear read", e) })
        gear.registerGearSelection(gearL)
        cleanups += { gear.unregisterGearSelection(gearL) }

        // ── 주행거리 → 운행일지 ──
        val odo = v.getOdometer()
        val odoL = object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(km: Float?) = setOdometer(km)
            override fun onFailed(e: Exception) = warn("odometer listener", e)
        }
        odo.getCurrentDistanceDriven({ km: Float? -> setOdometer(km) }, { e -> warn("odometer read", e) })
        odo.registerDistanceDriven(odoL)
        cleanups += { odo.unregisterDistanceDriven(odoL) }

        // ── 좌석 착석·벨트: 차량이 지원한다고 답한 좌석만 등록 (에뮬레이터는 2열까지) ──
        val seat = v.getSeat()
        seat.checkSeatOccupancyCapabilities(
            { areas: List<SeatArea> -> registerSeats(v, occupancy = true, supported = areas.toSeatValues()) },
            { e -> warn("occupancy capability", e); registerSeats(v, occupancy = true, supported = seatMap.keys) },
        )
        seat.checkSeatBeltBuckledCapabilities(
            { areas: List<SeatArea> -> registerSeats(v, occupancy = false, supported = areas.toSeatValues()) },
            { e -> warn("belt capability", e); registerSeats(v, occupancy = false, supported = seatMap.keys) },
        )
    }

    private fun List<SeatArea>.toSeatValues(): Set<SeatValue> = flatMap { it.values.toList() }.toSet()

    private fun registerSeats(v: Vehicle, occupancy: Boolean, supported: Set<SeatValue>) {
        val seat = v.getSeat()
        Log.i(TAG, (if (occupancy) "occupancy" else "belt") + " supported: $supported")
        seatMap.filterKeys { it in supported }.forEach { (sv, pos) ->
            val area = SeatArea(sv)
            if (occupancy) {
                val l = object : SeatOccupancyListener {
                    override fun onSeatOccupancyUpdated(on: Boolean?) = setSeat(_seats, pos, occupied(on))
                    override fun onFailed(e: Exception) = warn("occupancy $sv", e)
                }
                seat.getCurrentSeatOccupancy(area, { on: Boolean? -> setSeat(_seats, pos, occupied(on)) }, { e -> warn("occupancy read $sv", e) })
                seat.registerSeatOccupancy(area, l)
                cleanups += { seat.unregisterSeatOccupancy(area, l) }
            } else {
                val l = object : SeatBeltBuckledStateListener {
                    override fun onSeatBeltBuckledStateUpdated(on: Boolean?) = setSeat(_belts, pos, on)
                    override fun onFailed(e: Exception) = warn("belt $sv", e)
                }
                seat.getCurrentSeatBeltBuckledState(area, { on: Boolean? -> setSeat(_belts, pos, on) }, { e -> warn("belt read $sv", e) })
                seat.registerSeatBeltBuckledState(area, l)
                cleanups += { seat.unregisterSeatBeltBuckledState(area, l) }
            }
        }
    }

    /**
     * Vehicle SDK 2.3.2의 착석 Boolean은 원시값 1일 때 true다. 그런데 차량 표준(VHAL SEAT_OCCUPANCY)은 1=빈 좌석, 2=앉음이고,
     * 에뮬레이터 콘솔도 "앉음"을 2로 보낸다(2026-10-29 실측: 콘솔 앉음 → SDK false). 그래서 뒤집어서 "앉음"으로 쓴다.
     * 실차에서 반대로 나오면 이 값 하나만 false로 바꾼다.
     */
    private val sdkOccupancyInverted = true
    private fun occupied(on: Boolean?): Boolean? = if (sdkOccupancyInverted) on?.not() else on

    private fun setOdometer(km: Float?) {
        main.post { _odometer.value = km?.toDouble() }
    }

    private fun setDoor(pos: Int?) {
        main.post {
            val open = (pos ?: 0) > 0
            Log.i(TAG, "rear-right door pos=$pos open=$open")
            _door.value = open
        }
    }

    private fun setGear(g: VehicleGear?) {
        main.post {
            val moving = g != null && g != VehicleGear.GEAR_PARK && g != VehicleGear.GEAR_UNKNOWN
            Log.i(TAG, "gear=$g moving=$moving")
            _moving.value = moving
        }
    }

    private fun setSeat(flow: MutableStateFlow<Map<SeatPos, Boolean>?>, pos: SeatPos, on: Boolean?) {
        if (on == null) return
        main.post { flow.update { (it ?: emptyMap()) + (pos to on) } }
    }

    private fun warn(what: String, e: Exception) {
        Log.w(TAG, "$what failed: ${e.message}")
    }

    companion object {
        private const val TAG = "PleosVehicle"
        private const val INIT_DELAY_MS = 1500L
    }
}
