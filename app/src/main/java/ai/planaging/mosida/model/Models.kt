package ai.planaging.mosida.model

/** 좌석 위치. row = 1(앞)부터, col = 0(왼) 1(가운데) 2(오른) */
data class SeatPos(val row: Int, val col: Int) {
    val label: String
        get() = "${row}열 " + when (col) {
            0 -> "왼쪽"
            1 -> "가운데"
            else -> "오른쪽"
        }
}

enum class SeatRole { DRIVER, STAFF, PASSENGER, AISLE }

/**
 * 차종별 좌석판. rows[행][열] = 그 자리의 역할. 모든 행은 3칸(왼·가운데·오른)으로 맞춘다.
 * AISLE = 통로(좌석 없음).
 */
data class VehicleLayout(
    val id: String,
    val name: String,
    val rows: List<List<SeatRole>>,
    /** 오른쪽 슬라이딩 문(주 출입구)이 있는 행 */
    val doorRow: Int,
) {
    fun roleAt(p: SeatPos): SeatRole = rows.getOrNull(p.row - 1)?.getOrNull(p.col) ?: SeatRole.AISLE

    val passengerSeats: List<SeatPos>
        get() = rows.flatMapIndexed { r, row ->
            row.mapIndexedNotNull { c, role -> if (role == SeatRole.PASSENGER) SeatPos(r + 1, c) else null }
        }

    val capacity: Int get() = passengerSeats.size
}

object VehicleLayouts {
    private val D = SeatRole.DRIVER
    private val S = SeatRole.STAFF
    private val P = SeatRole.PASSENGER
    private val A = SeatRole.AISLE

    /** 스타리아 투어러 11인승: 1열 기사·동승 직원만(어르신 안 태움) / 2열 3 / 3열 좌우 독립 2(가운데 통로) / 4열 3 */
    val STARIA_11 = VehicleLayout(
        id = "staria11", name = "스타리아 투어러 11인승",
        rows = listOf(
            listOf(D, A, S),
            listOf(P, P, P),
            listOf(P, A, P),
            listOf(P, P, P),
        ),
        doorRow = 2,
    )

    /** 카니발 9인승: 1열 2 / 2열 독립 2 / 3열 독립 2 / 4열 벤치 3 */
    val CARNIVAL_9 = VehicleLayout(
        id = "carnival9", name = "카니발 9인승",
        rows = listOf(
            listOf(D, A, S),
            listOf(P, A, P),
            listOf(P, A, P),
            listOf(P, P, P),
        ),
        doorRow = 2,
    )

    /** 레이 4인승: 앞 기사+동승자 / 뒤 2 */
    val RAY_4 = VehicleLayout(
        id = "ray4", name = "레이 4인승",
        rows = listOf(
            listOf(D, A, S),
            listOf(P, A, P),
        ),
        doorRow = 2,
    )

    val all = listOf(STARIA_11, CARNIVAL_9, RAY_4)
}

/** ABSENT = 결석·오늘 안 오심. 좌석·탑승 인원·순서에서 빠진다. WAITING = 등원 운행에서 아직 안 타신 분. */
enum class RideStatus { WAITING, ONBOARD, DROPPED, ABSENT }

/** 어르신 한 분. 차량 화면에는 마스킹된 이름만 쓴다(개인정보 최소 노출). */
data class Elder(
    val id: String,
    val maskedName: String,     // 예: "오○진"
    val area: String,           // 동네 단위만 (상세 주소 표시 안 함)
    val order: Int,             // 하차 순서
    val needsGuardian: Boolean, // 보호자 인계가 필요한 어르신
    val seat: SeatPos? = null,  // 출발 전 좌석 배치에서 정한다
    val status: RideStatus = RideStatus.ONBOARD,
    val droppedAt: String? = null,
    /** 정차 지점(집 앞). 시연 데이터는 동네 근처의 가짜 좌표다. */
    val lat: Double? = null,
    val lng: Double? = null,
    /** 등원 고정 탑승 예정 시각(센터 도착 목표 기준). 예정일 뿐 기록에는 실제 시각만 남긴다. */
    val plannedAt: String? = null,
) {
    val seatLabel: String get() = seat?.label ?: "미배정"

    /** 음성으로 부르는 호칭. 차 스피커로 나가므로 성만 부른다("김 어르신"). 가명 번호(E12)면 그냥 "어르신". */
    val callName: String
        get() = if (maskedName.matches(Regex("E\\d+"))) "어르신" else maskedName.firstOrNull()?.let { "$it 어르신" } ?: "어르신"
}

/** 내비에 넘기는 정류장. 차 화면·내비에는 이름 대신 "○번 댁"만 쓴다. */
data class NaviStop(val label: String, val lat: Double, val lng: Double)

enum class ConfirmMethod(val label: String) {
    BOARDED_SENSOR("좌석 센서 착석"),
    BOARDED_TAP("탑승 확인 버튼"),
    BOARD_UNCONFIRMED("출발 전 탑승 확인이 없어 시각 없음"),
    ABSENT_MARKED("출발 전 결석 처리"),
    DOOR_CONFIRMED("뒷문 열림 + 확인"),
    DOOR_CONFIRMED_GUARDIAN("뒷문 열림 + 확인 · 보호자 인계"),
    MANUAL_AFTER_WARNING("잔류 경고 후 수동 처리"),
    UNDO("잘못 누른 기록 취소"),
    SEAT_CHECKED("좌석 센서 감지 → 직접 확인 · 사람 없음"),
    DOOR_OPEN_MOVING("주행 중 뒷문 열림 경고"),
    GEAR_UNKNOWN("기어 상태 확인 불가 · 승하차 확인 막음"),
    PICKUP_CONFIRMED("뒷문 열림 + 탑승 확인"),
    PICKUP_NO_SHOW("집 앞 확인 · 오늘 안 오심"),
    ARRIVED("센터 도착 + 모두 내림 확인"),
    GUARDIAN_NOTICE("보호자 알림 (시연 · 실제 발송 없음)"),
    GUARDIAN_CALL("보호자 전화 (시연 · 실제 발신 없음)"),
}

/**
 * 화면 위 알림 띠 + 음성 한 번. seq가 바뀔 때만 새 알림으로 본다.
 * text가 없으면 띠 없이 말만 한다(예: 하차 확인 창이 이미 떠 있을 때).
 */
data class Notice(val seq: Int, val text: String?, val speak: String?)

/** 운행 기록 한 줄. 실제로 감지·확인된 시각만 남긴다(확인이 없으면 시각을 채우지 않고 "-"). */
data class TripRecord(
    val time: String,
    val elderName: String,
    val kind: String,
    val seat: String,
    val method: ConfirmMethod,
    val confirmedBy: String,
    val elderId: String? = null,
    /** 이 승하차 확인 직전에 집 앞에서 오른쪽 뒷문이 열린 시각. 확인 시각(time)과 따로 남긴다. */
    val doorOpenedAt: String? = null,
)

/** 운행 종료를 막는 이유 */
sealed interface EndBlock {
    data class StillOnboard(val elders: List<Elder>) : EndBlock
    data class OccupiedSeat(val seat: SeatPos, val lastElder: Elder?) : EndBlock
    /** 등원: 아직 안 태웠거나 "오늘 안 오심" 처리가 안 된 분이 있음 */
    data class NotPickedUp(val elders: List<Elder>) : EndBlock
}

/** 길안내 한 줄 (NaviHelper의 경로·회전 정보를 화면용으로 줄인 것) */
data class Guidance(
    val turn: String,        // "300m 앞 우회전"
    val road: String,        // "가람로 방면"
    val distance: String,    // "1.2km"
    val etaMin: Int,         // 다음 집까지 남은 분
)
