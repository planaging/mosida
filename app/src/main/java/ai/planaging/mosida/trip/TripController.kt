package ai.planaging.mosida.trip

import ai.planaging.mosida.model.ConfirmMethod
import ai.planaging.mosida.model.Elder
import ai.planaging.mosida.model.EndBlock
import ai.planaging.mosida.model.Guidance
import ai.planaging.mosida.model.NaviStop
import ai.planaging.mosida.model.Notice
import ai.planaging.mosida.model.RideStatus
import ai.planaging.mosida.model.SeatPos
import ai.planaging.mosida.model.SeatRole
import ai.planaging.mosida.model.TripRecord
import ai.planaging.mosida.model.VehicleLayout
import ai.planaging.mosida.model.VehicleLayouts
import ai.planaging.mosida.vehicle.VehicleGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class Phase { SETUP, RUNNING }

/** 운행 기록 화면·CSV 맨 위에 보여줄 요약 */
data class TripSummary(
    val start: String,
    val end: String,
    val minutes: Long?,
    val dropped: Int,
    val total: Int,
    val guardian: Int,
    val absent: Int,
    val warnings: Int,
)

private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** 화면이 그릴 전부 */
data class TripUiState(
    val tripTitle: String = "하원 2차",
    val phase: Phase = Phase.SETUP,
    val layout: VehicleLayout = VehicleLayouts.STARIA_11,
    /** 출발 전 배치에서 누른 좌석 */
    val selectedSeat: SeatPos? = null,
    val elders: List<Elder> = emptyList(),
    val records: List<TripRecord> = emptyList(),
    val isMoving: Boolean = false,
    val doorOpen: Boolean = false,
    val seatOccupied: Map<SeatPos, Boolean>? = null,
    val seatBelt: Map<SeatPos, Boolean>? = null,
    /** 한 번이라도 벨트가 채워졌던 좌석. "풀림"은 채웠다가 풀린 경우만 뜻한다(처음부터 안 맨 상태와 구분). */
    val beltBuckledOnce: Set<SeatPos> = emptySet(),
    val guidance: Guidance? = null,
    val dropoffPrompt: DropoffPrompt? = null,
    val endBlock: EndBlock? = null,
    val tripEnded: Boolean = false,
    val startedAt: LocalTime? = null,
    val endedAt: LocalTime? = null,
    /** 착석 감지됐지만 기사가 직접 보고 "사람 없음"을 확인한 좌석. 센서가 꺼지면 다시 빠진다. */
    val clearedSeats: Set<SeatPos> = emptySet(),
    /** 방금 하차 처리한 어르신. "방금 하차 취소" 버튼용 */
    val lastDroppedId: String? = null,
    /** 센터 화면에서 받은 오늘 운행들. 비어 있으면 샘플 명단. */
    val centerTrips: List<CenterTrip> = emptyList(),
    val selectedTrip: CenterTrip? = null,
    /** 화면 위 알림 띠 + 음성 (이름 안내, 보호자 연락) */
    val notice: Notice? = null,
    /** 출발 전 탑승을 마친 어르신 (착석 센서 또는 "탑승 확인" 버튼) */
    val boardedAt: Map<String, Boarding> = emptyMap(),
    /** 도착 예정 알림을 이미 보낸 어르신 (한 번만) */
    val notifiedIds: Set<String> = emptySet(),
    /** 보호자 전화 연결 화면 (시연: 실제로 걸지 않는다) */
    val callingGuardian: Elder? = null,
    /** 등원: 집 앞에서 뒷문이 열렸을 때의 탑승 확인 창 */
    val boardPrompt: BoardPrompt? = null,
    /** 등원: 모두 태운 뒤 센터에서 뒷문이 열렸을 때의 "모두 내리셨나요?" 창 */
    val arrivalPrompt: Boolean = false,
    val lastBoardedId: String? = null,
    /** 등원: 첫 분이 실제로 탄 시각과 그분의 예정 시각(분). 뒤 일정이 얼마나 밀렸는지 재는 기준 */
    val pickupBase: Pair<Int, Int>? = null,
    /** 밀린 시간 표시를 갱신하기 위한 현재 시각(분) */
    val nowMin: Int = 0,
) {
    val isPickup: Boolean get() = selectedTrip?.isPickup == true
    val waiting: List<Elder> get() = elders.filter { it.status == RideStatus.WAITING }.sortedBy { it.order }
    /** 같은 차가 이어서 도는 다음 회차 */
    val nextRound: CenterTrip?
        get() = selectedTrip?.let { s -> centerTrips.firstOrNull { it.vehicleCode == s.vehicleCode && it.round == s.round + 1 } }

    /** 등원: 다음 분이 예정보다 몇 분 밀렸는지. 첫 분을 태운 뒤부터 계산한다. */
    val pickupDelayMin: Int?
        get() {
            val base = pickupBase ?: return null
            val planned = next?.plannedAt?.let { t -> LocalTime.parse(t, HHMM).let { it.hour * 60 + it.minute } } ?: return null
            return (nowMin - base.first - (planned - base.second)).takeIf { it > 0 }
        }
    val boardedIds: Set<String> get() = boardedAt.keys

    /** 결석을 뺀 오늘 실제 탑승 인원 */
    val present: List<Elder> get() = elders.filter { it.status != RideStatus.ABSENT }
    val absent: List<Elder> get() = elders.filter { it.status == RideStatus.ABSENT }
    val onboard: List<Elder> get() = elders.filter { it.status == RideStatus.ONBOARD }.sortedBy { it.order }
    /** 다음에 들를 집: 하원은 다음 내리실 분, 등원은 다음 타실 분 */
    val next: Elder? get() = if (isPickup) waiting.firstOrNull() else onboard.firstOrNull()
    val droppedCount: Int get() = elders.count { it.status == RideStatus.DROPPED }
    val unassigned: List<Elder> get() = present.filter { it.seat == null }
    val unbuckled: List<Elder> get() = onboard.filter { e -> e.seat?.let { isUnbuckled(it) } == true }
    /** 채웠던 벨트가 지금 풀려 있음 */
    fun isUnbuckled(p: SeatPos): Boolean = p in beltBuckledOnce && seatBelt?.get(p) == false
    val canStart: Boolean get() = present.isNotEmpty() && unassigned.isEmpty() && present.size <= layout.capacity
    val lastDropped: Elder? get() = lastDroppedId?.let { id -> elders.firstOrNull { it.id == id && it.status == RideStatus.DROPPED } }
    /** 차가 움직이는데 뒷문이 열려 있음 */
    val doorDanger: Boolean get() = doorOpen && isMoving
    fun elderAt(p: SeatPos): Elder? = elders.firstOrNull { it.seat == p }

    /** 탑승 순서 = 하차의 역순. 마지막에 내릴 분이 먼저 타서 안쪽에 앉는다. */
    val boardingOrder: List<Elder> get() = present.filter { it.seat != null }.sortedByDescending { it.order }
    val nextToBoard: Elder? get() = if (unassigned.isEmpty()) boardingOrder.firstOrNull { it.id !in boardedIds } else null
    val allBoarded: Boolean get() = present.isNotEmpty() && unassigned.isEmpty() && boardingOrder.all { it.id in boardedIds }

    /** 착석이 감지됐는데 아직 "사람 없음" 확인을 안 한 좌석 (앞줄부터) */
    fun sensedUnclearedSeats(): List<SeatPos> =
        seatOccupied.orEmpty().filter { (p, on) -> on && p !in clearedSeats }.keys.sortedWith(compareBy({ it.row }, { it.col }))

    /** 내비로 보낼 남은 정류장. 운행 중이 아니면 빈 목록(경로 취소). 이름 대신 "○번 댁". */
    fun naviStops(): List<NaviStop> =
        if (phase != Phase.RUNNING || tripEnded) emptyList()
        else (if (isPickup) waiting else onboard).mapNotNull { e ->
            val lat = e.lat ?: return@mapNotNull null
            val lng = e.lng ?: return@mapNotNull null
            NaviStop("${e.order}번 댁", lat, lng)
        } + if (isPickup && (waiting.isNotEmpty() || onboard.isNotEmpty())) listOf(CenterTripFile.demoCenter) else emptyList()

    val summary: TripSummary
        get() {
            val start = startedAt
            val minutes = start?.let { Duration.between(it, endedAt ?: LocalTime.now()).toMinutes().coerceAtLeast(0) }
            return TripSummary(
                start = start?.format(HHMM) ?: "-",
                end = endedAt?.format(HHMM) ?: "운행 중",
                minutes = minutes,
                dropped = droppedCount,
                total = present.size,
                guardian = records.count { it.method == ConfirmMethod.DOOR_CONFIRMED_GUARDIAN },
                absent = absent.size,
                warnings = records.count {
                    it.method == ConfirmMethod.DOOR_OPEN_MOVING ||
                        it.method == ConfirmMethod.SEAT_CHECKED ||
                        it.method == ConfirmMethod.MANUAL_AFTER_WARNING
                },
            )
        }
}

/** 실제로 탄 시각과 확인 방법. 운행을 시작할 때 이 값 그대로 기록에 옮긴다. */
data class Boarding(val time: String, val method: ConfirmMethod)

/** 등원 집 앞 탑승 확인 창 */
data class BoardPrompt(val suggested: Elder, val choosingOther: Boolean = false)

data class DropoffPrompt(
    val openedAt: String,
    val suggested: Elder,
    val guardianChecked: Boolean = false,
    val choosingOther: Boolean = false,
)

/**
 * 규칙
 * [출발 전] 차종 고르기 → 결석 표시 → 좌석 누르고 명단에서 어르신 누르기(또는 자동 배치) → 전원 배치돼야 운행 시작
 *          시작하면 탑승·결석이 기록에 남는다
 * [운행 중]
 *  1) 오른쪽 뒷문이 "닫힘 → 열림"이고, 차가 멈춰 있고, 탑승자가 있으면 확인 창
 *     차가 움직이는 중이면 확인 창 대신 경고 + 기록
 *  2) 기록은 사람이 확인 버튼을 눌러야만 남는다 (잘못 누르면 "방금 하차 취소", 취소도 기록에 남는다)
 *  3) 보호자 인계 필요 어르신은 인계 체크 전엔 확인 불가
 *  4) 운행 종료는 기록상 탑승자 0명 + 착석 센서 0석(직접 확인한 좌석 제외)일 때만
 * [탑승 호출] 좌석이 다 정해지면 하차의 역순(마지막에 내릴 분부터)으로 한 분씩 부른다. 배정된 자리 착석 센서가
 *          "빈 → 앉음"이 되거나 "탑승 확인"을 누르면 인사하고 다음 분을 부른다(센서 없는 자리는 버튼).
 *          뒷문이 열려 하차 확인 창이 뜨면 내리실 분을 불러드린다.
 * [보호자 연락 · 시연] 다음 집으로 출발(기어 D)할 때 내비 도착 예정 시간으로 알림, 하차 확인 뒤 "잘 들어가셨습니다" 알림,
 *          "보호자 전화" 버튼. 실제 발송·발신은 하지 않고 운행 기록에만 남긴다(알림톡·전화 연동은 다음 단계).
 */
class TripController(
    private val gateway: VehicleGateway,
    private val scope: CoroutineScope,
    initialElders: List<Elder>,
) {
    private val _state = MutableStateFlow(TripUiState(elders = initialElders))
    val state: StateFlow<TripUiState> = _state

    private var lastDoorOpen = false

    fun start() {
        gateway.start()
        scope.launch {
            gateway.rearRightDoorOpen.collect { open ->
                val wasOpen = lastDoorOpen
                lastDoorOpen = open
                _state.update { it.copy(doorOpen = open) }
                if (open && !wasOpen) onRearDoorOpened()
            }
        }
        scope.launch {
            gateway.isMoving.collect { m ->
                val was = _state.value.isMoving
                _state.update { it.copy(isMoving = m) }
                if (m && !was) onDeparted()
            }
        }
        scope.launch {
            gateway.seatOccupied.collect { s ->
                val before = _state.value.seatOccupied
                _state.update { st ->
                    st.copy(seatOccupied = s, clearedSeats = st.clearedSeats.filter { s?.get(it) == true }.toSet())
                }
                // 처음 받은 값(에뮬레이터 초기값 등)은 "앉음"으로 치지 않는다. 확실히 비어 있다가 앉은 자리만.
                if (before != null && s != null) {
                    s.filter { (p, on) -> on && before[p] == false }.keys.forEach { boardAt(it) }
                }
            }
        }
        scope.launch {
            gateway.seatBeltBuckled.collect { b ->
                _state.update { st ->
                    val nowBuckled = b?.filterValues { it }?.keys.orEmpty()
                    st.copy(seatBelt = b, beltBuckledOnce = st.beltBuckledOnce + nowBuckled)
                }
            }
        }
        scope.launch {
            while (true) {
                val t = LocalTime.now()
                _state.update { it.copy(nowMin = t.hour * 60 + t.minute) }
                delay(20_000)
            }
        }
        scope.launch { gateway.guidance.collect { g -> _state.update { it.copy(guidance = g) } } }
        // 운행 중 남은 어르신이 바뀌면(출발·하차·하차 취소) 차량 내비 경로를 다시 보낸다 = 경유지 롤링.
        scope.launch {
            _state.map { it.naviStops() }.distinctUntilChanged().collect { gateway.routeTo(it) }
        }
    }

    fun stop() = gateway.stop()

    // ───────── 센터 표 불러오기 ─────────

    /** 앱을 켤 때: 첫 운행을 조용히 펼쳐 둔다(호출은 직원이 운행을 고를 때 시작). */
    fun setCenterTrips(trips: List<CenterTrip>) {
        _state.update { it.copy(centerTrips = trips) }
        trips.firstOrNull()?.let { applyTrip(it) }
    }

    /** 이 차가 맡을 운행을 고른다. 자리는 센터 표 그대로이고, 곧바로 탑승 호출을 시작한다. */
    fun selectCenterTrip(trip: CenterTrip) {
        if (_state.value.phase != Phase.SETUP) return
        applyTrip(trip)
        announceBoardingIfReady()
    }

    private fun applyTrip(trip: CenterTrip) = _state.update {
        it.copy(
            selectedTrip = trip,
            tripTitle = trip.title,
            layout = trip.layout,
            elders = trip.elders,
            selectedSeat = null,
            boardedAt = emptyMap(),
            pickupBase = null,
            lastBoardedId = null,
        )
    }

    /** 운행 기록 화면에서: 같은 차의 다음 회차로 넘어간다. 기록은 이어서 쌓인다. */
    fun startNextRound() {
        val trip = _state.value.nextRound ?: return
        applyTrip(trip)
        _state.update {
            it.copy(phase = Phase.SETUP, tripEnded = false, startedAt = null, endedAt = null, endBlock = null, lastDroppedId = null, clearedSeats = emptySet())
        }
        announceBoardingIfReady()
    }

    // ───────── 출발 전 좌석 배치 ─────────

    fun selectVehicle(layout: VehicleLayout) = _state.update { st ->
        st.copy(layout = layout, selectedSeat = null, elders = st.elders.map { it.copy(seat = null) }, boardedAt = emptyMap())
    }

    fun selectSeat(p: SeatPos) = _state.update { st ->
        if (st.layout.roleAt(p) != SeatRole.PASSENGER) st
        else st.copy(selectedSeat = if (st.selectedSeat == p) null else p)
    }

    /** 고른 좌석에 어르신을 앉힌다. 그 자리에 있던 분은 미배정으로 돌아간다. 결석이면 무시. */
    fun assignToSelected(elder: Elder) {
        assignToSelectedSeat(elder)
        announceBoardingIfReady()
    }

    private fun assignToSelectedSeat(elder: Elder) = _state.update { st ->
        val target = st.selectedSeat ?: return@update st
        if (st.elders.firstOrNull { it.id == elder.id }?.status == RideStatus.ABSENT) return@update st
        st.copy(
            selectedSeat = null,
            elders = st.elders.map {
                when {
                    it.id == elder.id -> it.copy(seat = target)
                    it.seat == target -> it.copy(seat = null)
                    else -> it
                }
            },
        )
    }

    fun unassign(elder: Elder) = _state.update { st ->
        st.copy(elders = st.elders.map { if (it.id == elder.id) it.copy(seat = null) else it })
    }

    /** 출발 전에만: 결석 ↔ 탑승 전환. 결석이면 좌석도 비운다. */
    fun toggleAbsent(elder: Elder) = _state.update { st ->
        if (st.phase != Phase.SETUP) return@update st
        st.copy(
            elders = st.elders.map {
                when {
                    it.id != elder.id -> it
                    it.status == RideStatus.ABSENT -> it.copy(status = if (st.isPickup) RideStatus.WAITING else RideStatus.ONBOARD)
                    else -> it.copy(status = RideStatus.ABSENT, seat = null)
                }
            },
        )
    }

    /**
     * 하차 순서대로 자동 배치: 먼저 내리는 분일수록 문 가까운 자리.
     * 문이 있는 행의 오른쪽 → 왼쪽, 그다음 앞 행, 그다음 뒤 행 순서. 결석자는 뺀다.
     */
    fun autoAssign() {
        autoAssignSeats()
        announceBoardingIfReady()
    }

    private fun autoAssignSeats() = _state.update { st ->
        val l = st.layout
        val seats = l.passengerSeats.sortedWith(
            compareBy<SeatPos>({ kotlin.math.abs(it.row - l.doorRow) + if (it.row > l.doorRow) 0.5 else 0.0 }, { -it.col })
        )
        val ordered = st.present.sortedBy { it.order }
        val assigned = ordered.mapIndexed { i, e -> e.id to seats.getOrNull(i) }.toMap()
        st.copy(selectedSeat = null, elders = st.elders.map { it.copy(seat = assigned[it.id]) }, boardedAt = emptyMap())
    }

    /** 전원 배치됐을 때만 운행 시작. 탑승·결석을 기록에 남긴다. */
    fun startTrip(): Boolean {
        val s = _state.value
        if (!s.canStart) return false
        val t = now()
        val absentRecords = s.absent.sortedBy { it.order }.map {
            TripRecord(t, "${it.maskedName} 님", "결석", "-", ConfirmMethod.ABSENT_MARKED, "동승자", it.id)
        }
        // 탑승 시각은 착석 센서·확인 버튼이 들어온 그때의 값이다. 확인이 없는 분은 시각을 채우지 않는다.
        // 등원은 집 앞에서 한 분씩 기록하므로 여기서 남길 탑승 줄이 없다.
        val boarded = if (s.isPickup) emptyList() else s.boardedAt.mapNotNull { (id, b) ->
            s.present.firstOrNull { it.id == id }?.let {
                TripRecord(b.time, "${it.maskedName} 님", "탑승", it.seatLabel, b.method, if (b.method == ConfirmMethod.BOARDED_SENSOR) "좌석 센서" else "동승자", it.id)
            }
        }
        val unconfirmed = (if (s.isPickup) emptyList() else s.present).filter { it.id !in s.boardedAt }.sortedByDescending { it.order }.map {
            TripRecord("-", "${it.maskedName} 님", "탑승 확인 안 됨", it.seatLabel, ConfirmMethod.BOARD_UNCONFIRMED, "-", it.id)
        }
        _state.update {
            it.copy(
                phase = Phase.RUNNING,
                selectedSeat = null,
                startedAt = LocalTime.now(),
                records = it.records + absentRecords + boarded + unconfirmed,
            )
        }
        return true
    }

    // ───────── 운행 중 ─────────

    private fun onRearDoorOpened() {
        val s = _state.value
        if (s.phase != Phase.RUNNING || s.tripEnded) return
        if (s.isMoving) {
            addRecord(TripRecord(now(), "-", "경고", "오른쪽 뒷문", ConfirmMethod.DOOR_OPEN_MOVING, "차량"))
            return
        }
        if (s.isPickup) {
            if (s.boardPrompt != null || s.arrivalPrompt) return
            val n = s.next
            if (n != null) {
                _state.update { it.copy(boardPrompt = BoardPrompt(n)) }
                post(null, pickupCall(n))
            } else if (s.onboard.isNotEmpty()) {
                _state.update { it.copy(arrivalPrompt = true) }
                post(null, "센터에 도착했습니다. 모두 내리셨나요?")
            }
            return
        }
        if (s.dropoffPrompt != null) return
        val next = s.next ?: return
        _state.update { it.copy(dropoffPrompt = DropoffPrompt(openedAt = now(), suggested = next)) }
        post(null, "${next.order}번 ${next.callName} 댁에 도착했습니다. 천천히 내리세요.")
    }

    // ───────── 등원: 집 앞 탑승 · 센터 도착 ─────────

    fun chooseOtherBoarding() = _state.update { st ->
        st.boardPrompt?.let { st.copy(boardPrompt = it.copy(choosingOther = true)) } ?: st
    }

    fun pickBoardingElder(e: Elder) {
        _state.update { st -> st.boardPrompt?.let { st.copy(boardPrompt = BoardPrompt(e)) } ?: st }
        post(null, pickupCall(e))
    }

    /** 뒷문이 열릴 때 부르는 말. 아직 안 나오셨을 수 있어 인사 대신 자리를 안내한다. */
    private fun pickupCall(e: Elder) = "${e.order}번 ${e.callName}, ${e.seatLabel} 자리로 모셔 주세요. 안전벨트 꼭 매 주세요."

    fun dismissBoarding() = _state.update { it.copy(boardPrompt = null) }

    /** 사람이 확인을 눌러야 탑승이 기록된다. 시각은 지금 이 순간이고 예정 시각으로 바꾸지 않는다. */
    fun confirmPickup() {
        val p = _state.value.boardPrompt ?: return
        val e = p.suggested
        val t = now()
        _state.update { st ->
            val planned = e.plannedAt?.let { s -> LocalTime.parse(s, HHMM).let { it.hour * 60 + it.minute } }
            val clock = LocalTime.now().let { it.hour * 60 + it.minute }
            st.copy(
                boardPrompt = null,
                elders = st.elders.map { if (it.id == e.id) it.copy(status = RideStatus.ONBOARD) else it },
                lastBoardedId = e.id,
                pickupBase = st.pickupBase ?: planned?.let { clock to it },
                nowMin = clock,
                records = st.records +
                    TripRecord(t, "${e.maskedName} 님", "탑승", e.seatLabel, ConfirmMethod.PICKUP_CONFIRMED, "동승자", e.id) +
                    TripRecord(t, "${e.maskedName} 님", "보호자 알림 · 잘 타셨습니다", "-", ConfirmMethod.GUARDIAN_NOTICE, "앱", e.id),
            )
        }
    }

    /** 잘못 누른 탑승을 되돌린다. 원래 기록은 지우지 않는다. */
    fun undoLastPickup() {
        val s = _state.value
        val e = s.elders.firstOrNull { it.id == s.lastBoardedId && it.status == RideStatus.ONBOARD } ?: return
        _state.update { st ->
            st.copy(
                elders = st.elders.map { if (it.id == e.id) it.copy(status = RideStatus.WAITING) else it },
                records = st.records + TripRecord(now(), "${e.maskedName} 님", "탑승 취소", e.seatLabel, ConfirmMethod.UNDO, "동승자", e.id),
                lastBoardedId = null,
            )
        }
    }

    /** 집 앞까지 가서 확인했는데 오늘 안 오시는 경우. 사람이 고른다. 경로는 남은 집으로 다시 잡힌다. */
    fun markNoShow(e: Elder) {
        _state.update { st ->
            st.copy(
                boardPrompt = null,
                endBlock = null,
                elders = st.elders.map { if (it.id == e.id) it.copy(status = RideStatus.ABSENT, seat = null) else it },
                records = st.records + TripRecord(now(), "${e.maskedName} 님", "오늘 안 오심", "-", ConfirmMethod.PICKUP_NO_SHOW, "동승자", e.id),
            )
        }
    }

    fun dismissArrival() = _state.update { it.copy(arrivalPrompt = false) }

    /** 문 앞에서 순서대로 내리시므로 한 번만 확인한다. 이어서 좌석 센서를 보고 운행을 끝낸다. */
    fun confirmArrival() {
        val t = now()
        _state.update { st ->
            st.copy(
                arrivalPrompt = false,
                elders = st.elders.map { if (it.status == RideStatus.ONBOARD) it.copy(status = RideStatus.DROPPED, droppedAt = t) else it },
                records = st.records + st.onboard.map { TripRecord(t, "${it.maskedName} 님", "하차", it.seatLabel, ConfirmMethod.ARRIVED, "동승자", it.id) },
                lastBoardedId = null,
            )
        }
        requestEndTrip()
    }

    // ───────── 이름 안내 · 보호자 연락 ─────────

    /** 좌석이 다 정해지고 아직 아무도 안 탔으면 첫 분을 부른다. */
    private fun announceBoardingIfReady() {
        val s = _state.value
        if (s.phase != Phase.SETUP || s.isPickup || s.boardedIds.isNotEmpty()) return
        val n = s.nextToBoard ?: return
        post(
            "탑승 순서는 하차의 역순 · 먼저 ${n.order}번 ${n.maskedName} 님 · ${n.seatLabel}",
            "탑승을 시작합니다. 마지막에 내리실 분부터 타십니다. 먼저 ${n.order}번 ${n.callName}, ${n.seatLabel} 자리입니다.",
        )
    }

    /** 배정된 자리에 앉으셨다(착석 센서). 출발 전에만. */
    private fun boardAt(p: SeatPos) {
        val s = _state.value
        if (s.isPickup) {
            // 등원: 앉으신 걸 알려 주기만 한다. 기록은 사람이 확인을 눌러야 남는다.
            if (s.phase == Phase.RUNNING) s.elderAt(p)?.takeIf { it.status == RideStatus.WAITING }?.let { post("${it.maskedName} 님 앉으셨어요 · 탑승 확인을 눌러 주세요", null) }
            return
        }
        if (s.phase != Phase.SETUP) return
        val e = s.elderAt(p)?.takeIf { it.status == RideStatus.ONBOARD && it.id !in s.boardedIds } ?: return
        board(e, ConfirmMethod.BOARDED_SENSOR)
    }

    /** 센서 없는 자리나 센서가 안 잡힐 때: 지금 부른 분의 탑승을 손으로 확인한다. */
    fun confirmBoarding() {
        _state.value.nextToBoard?.let { board(it, ConfirmMethod.BOARDED_TAP) }
    }

    private fun board(e: Elder, method: ConfirmMethod) {
        _state.update { it.copy(boardedAt = it.boardedAt + (e.id to Boarding(now(), method))) }
        val s = _state.value
        val n = s.nextToBoard
        val then = when {
            n != null -> " 다음 탑승, ${n.order}번 ${n.callName}, ${n.seatLabel}입니다."
            s.allBoarded -> " 모두 타셨습니다. 운행을 시작해 주세요."
            else -> ""
        }
        post(
            "${e.maskedName} 님 탑승 · ${e.seatLabel}" + (n?.let { "  →  다음 ${it.order}번 ${it.maskedName} 님" } ?: "  ·  모두 탑승"),
            "${e.order}번 ${e.callName}, 어서 오세요. ${e.seatLabel} 자리입니다. 안전벨트 꼭 매 주세요.$then",
        )
    }

    /** 다음 집으로 출발하면 그 집 보호자께 도착 예정을 알린다(내비 남은 시간). 한 분께 한 번만. */
    private fun onDeparted() {
        val s = _state.value
        if (s.phase != Phase.RUNNING || s.tripEnded) return
        val e = s.next?.takeIf { it.id !in s.notifiedIds } ?: return
        val eta = s.guidance?.etaMin
        // 내비가 아직 남은 시간을 못 줬거나(0) 아주 가까우면 "곧 도착"
        val arrive = if (eta != null && eta >= 2) "약 ${eta}분 뒤 도착" else "곧 도착"
        val what = if (s.isPickup) "$arrive, 준비해 주세요" else arrive
        _state.update {
            it.copy(
                notifiedIds = it.notifiedIds + e.id,
                records = it.records + TripRecord(now(), "${e.maskedName} 님", "보호자 알림 · $what", "-", ConfirmMethod.GUARDIAN_NOTICE, "앱", e.id),
            )
        }
        post("${e.maskedName} 님 보호자께 '$what' 알림을 보냈어요", "${e.order}번 ${e.callName} 보호자께 도착 예정을 알려 드렸습니다.")
    }

    /** 보호자 전화 (시연: 연결 화면과 기록만, 실제로 걸지 않는다) */
    fun callGuardian(e: Elder) {
        _state.update {
            it.copy(
                callingGuardian = e,
                records = it.records + TripRecord(now(), "${e.maskedName} 님", "보호자 전화", "-", ConfirmMethod.GUARDIAN_CALL, "기사", e.id),
            )
        }
        post(null, "${e.order}번 ${e.callName} 보호자께 전화를 연결합니다.")
    }

    fun endCall() = _state.update { it.copy(callingGuardian = null) }

    /** 알림 띠는 6초 뒤 저절로 사라진다. 음성은 MainActivity가 seq로 한 번만 읽는다. */
    private fun post(text: String?, speak: String?) {
        val seq = (_state.value.notice?.seq ?: 0) + 1
        _state.update { it.copy(notice = Notice(seq, text, speak)) }
        scope.launch {
            delay(6000)
            _state.update { if (it.notice?.seq == seq) it.copy(notice = null) else it }
        }
    }

    fun toggleGuardian() = _state.update { st ->
        st.dropoffPrompt?.let { st.copy(dropoffPrompt = it.copy(guardianChecked = !it.guardianChecked)) } ?: st
    }

    fun chooseOther() = _state.update { st ->
        st.dropoffPrompt?.let { st.copy(dropoffPrompt = it.copy(choosingOther = true)) } ?: st
    }

    fun pickElder(elder: Elder) = _state.update { st ->
        st.dropoffPrompt?.let {
            st.copy(dropoffPrompt = it.copy(suggested = elder, choosingOther = false, guardianChecked = false))
        } ?: st
    }

    fun dismissPrompt() = _state.update { it.copy(dropoffPrompt = null) }

    fun canConfirm(p: DropoffPrompt): Boolean = !p.suggested.needsGuardian || p.guardianChecked

    fun confirmDropoff() {
        val p = _state.value.dropoffPrompt ?: return
        if (!canConfirm(p)) return
        val method = if (p.suggested.needsGuardian) ConfirmMethod.DOOR_CONFIRMED_GUARDIAN else ConfirmMethod.DOOR_CONFIRMED
        markDropped(p.suggested, method, by = "동승자")
        val e = p.suggested
        _state.update {
            it.copy(
                dropoffPrompt = null,
                records = it.records + TripRecord(now(), "${e.maskedName} 님", "보호자 알림 · 잘 들어가셨습니다", "-", ConfirmMethod.GUARDIAN_NOTICE, "앱", e.id),
            )
        }
    }

    fun manualDropoff(elder: Elder) {
        markDropped(elder, ConfirmMethod.MANUAL_AFTER_WARNING, by = "기사")
        _state.update { it.copy(endBlock = null) }
    }

    /** 잘못 누른 하차를 되돌린다. 원래 기록은 지우지 않고 "하차 취소" 줄을 덧붙인다. */
    fun undoLastDropoff() {
        val e = _state.value.lastDropped ?: return
        _state.update { st ->
            st.copy(
                elders = st.elders.map { if (it.id == e.id) it.copy(status = RideStatus.ONBOARD, droppedAt = null) else it },
                records = st.records + TripRecord(now(), "${e.maskedName} 님", "하차 취소", e.seatLabel, ConfirmMethod.UNDO, "동승자", e.id),
                lastDroppedId = null,
            )
        }
    }

    /**
     * 착석 감지 좌석을 기사가 직접 보고 "사람 없음" 확인 → 기록하고 다시 종료를 시도한다.
     * 기사는 뒤를 한 번 훑어보고 확인하므로, 지금 감지된 좌석을 한 번에 처리하고 기록은 좌석마다 한 줄씩 남긴다.
     */
    fun clearSeatWarning() {
        val block = _state.value.endBlock as? EndBlock.OccupiedSeat
        if (block == null) {
            _state.update { it.copy(endBlock = null) }
            return
        }
        _state.update { st ->
            val sensed = st.sensedUnclearedSeats().ifEmpty { listOf(block.seat) }
            val t = now()
            st.copy(
                endBlock = null,
                clearedSeats = st.clearedSeats + sensed,
                records = st.records + sensed.map { TripRecord(t, "-", "잔류 확인", it.label, ConfirmMethod.SEAT_CHECKED, "기사") },
            )
        }
        requestEndTrip()
    }

    fun requestEndTrip() {
        val s = _state.value
        if (s.isPickup && s.waiting.isNotEmpty()) {
            _state.update { it.copy(endBlock = EndBlock.NotPickedUp(s.waiting)) }
            return
        }
        if (s.onboard.isNotEmpty()) {
            _state.update { it.copy(endBlock = EndBlock.StillOnboard(s.onboard)) }
            return
        }
        val seat = s.sensedUnclearedSeats().firstOrNull()
        if (seat != null) {
            _state.update { it.copy(endBlock = EndBlock.OccupiedSeat(seat, s.elderAt(seat))) }
            return
        }
        _state.update { it.copy(tripEnded = true, endBlock = null, endedAt = LocalTime.now(), lastDroppedId = null) }
    }

    fun dismissEndBlock() = _state.update { it.copy(endBlock = null) }

    /** 경로는 남은 어르신이 바뀔 때 자동으로 보낸다. 버튼은 내비가 놓쳤을 때 다시 보내기용. */
    fun sendNextToVehicleNavi() {
        gateway.routeTo(emptyList())
        gateway.routeTo(_state.value.naviStops())
    }

    private fun addRecord(r: TripRecord) = _state.update { it.copy(records = it.records + r) }

    private fun markDropped(elder: Elder, method: ConfirmMethod, by: String) {
        val t = now()
        _state.update { st ->
            st.copy(
                elders = st.elders.map {
                    if (it.id == elder.id) it.copy(status = RideStatus.DROPPED, droppedAt = t) else it
                },
                records = st.records + TripRecord(t, "${elder.maskedName} 님", "하차", elder.seatLabel, method, by, elder.id),
                lastDroppedId = elder.id,
            )
        }
    }

    fun exportCsv(): String {
        val s = _state.value
        fun row(vararg v: String) = v.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }
        val header = row("시간", "어르신", "구분", "좌석", "확인 방식", "확인자")
        val lines = s.records.map { row(it.time, it.elderName, it.kind, it.seat, it.method.label, it.confirmedBy) }
        val sm = s.summary
        val summary = listOf(
            "",
            row("요약", s.tripTitle, s.layout.name),
            row("출발", sm.start),
            row("종료", sm.end),
            row("소요(분)", sm.minutes?.toString() ?: "-"),
            row("하차", "${sm.dropped} / ${sm.total}"),
            row("보호자 인계", sm.guardian.toString()),
            row("결석", sm.absent.toString()),
            row("경고·확인", sm.warnings.toString()),
        )
        return "﻿" + (listOf(header) + lines + summary).joinToString("\n")
    }

    private fun now(): String = LocalTime.now().format(HHMM)
}
