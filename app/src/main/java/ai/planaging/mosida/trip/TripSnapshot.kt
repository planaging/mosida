package ai.planaging.mosida.trip

import ai.planaging.mosida.model.ConfirmMethod
import ai.planaging.mosida.model.Elder
import ai.planaging.mosida.model.RideStatus
import ai.planaging.mosida.model.SeatPos
import ai.planaging.mosida.model.TripRecord
import ai.planaging.mosida.model.VehicleLayouts
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/**
 * 끝나지 않은 운행을 파일로 남겨, 앱이 꺼졌다 켜져도 이어서 진행한다.
 * 그날 운행만 되살린다. 확인 창은 저장하지 않는다(뒷문을 다시 열면 다시 묻는다).
 */
object TripSnapshot {
    const val FILE_NAME = "mosida-trip-state.json"

    /** 저장할 내용만 추린 값. 이 값이 바뀔 때만 파일을 쓴다(센서·안내 변화로는 쓰지 않는다). */
    fun key(s: TripUiState): List<Any?> = listOf(
        s.phase, s.tripEnded, s.elders, s.records, s.boardedAt, s.notifiedIds, s.clearedSeats,
        s.lastDroppedId, s.lastBoardedId, s.pickupBase, s.odoStart, s.selectedTrip?.vehicleCode, s.selectedTrip?.round, s.layout.id,
    )

    /** 이어서 진행할 것이 있는가: 운행 중이거나, 출발 전 탑승을 이미 시작했을 때 */
    fun worthKeeping(s: TripUiState): Boolean =
        !s.tripEnded && (s.phase == Phase.RUNNING || s.boardedAt.isNotEmpty())

    fun toJson(s: TripUiState, today: LocalDate = LocalDate.now()): String = JSONObject().apply {
        put("date", today.toString())
        put("phase", s.phase.name)
        put("title", s.tripTitle)
        put("layout", s.layout.id)
        s.selectedTrip?.let { put("vehicle", it.vehicleCode); put("round", it.round) }
        s.startedAt?.let { put("startedAt", it.toString()) }
        s.odoStart?.let { put("odoStart", it) }
        s.lastDroppedId?.let { put("lastDroppedId", it) }
        s.lastBoardedId?.let { put("lastBoardedId", it) }
        s.pickupBase?.let { put("baseActual", it.first); put("basePlanned", it.second) }
        put("elders", JSONArray(s.elders.map { e ->
            JSONObject().apply {
                put("id", e.id); put("name", e.maskedName); put("area", e.area); put("order", e.order)
                put("guardian", e.needsGuardian); put("status", e.status.name)
                e.seat?.let { put("row", it.row); put("col", it.col) }
                e.droppedAt?.let { put("droppedAt", it) }
                e.lat?.let { put("lat", it) }
                e.lng?.let { put("lng", it) }
                e.plannedAt?.let { put("plannedAt", it) }
            }
        }))
        put("records", JSONArray(s.records.map { r ->
            JSONObject().apply {
                put("time", r.time); put("name", r.elderName); put("kind", r.kind); put("seat", r.seat)
                put("method", r.method.name); put("by", r.confirmedBy)
                r.elderId?.let { put("id", it) }
                r.doorOpenedAt?.let { put("door", it) }
            }
        }))
        put("boarded", JSONArray(s.boardedAt.map { (id, b) ->
            JSONObject().apply { put("id", id); put("time", b.time); put("method", b.method.name) }
        }))
        put("notified", JSONArray(s.notifiedIds.toList()))
        put("cleared", JSONArray(s.clearedSeats.map { JSONObject().apply { put("row", it.row); put("col", it.col) } }))
    }.toString()

    /** 읽을 수 없거나 오늘 운행이 아니면 null. 센서·내비 값은 그대로 두고 운행 내용만 되살린다. */
    fun restore(json: String, base: TripUiState, today: LocalDate = LocalDate.now()): TripUiState? = runCatching {
        val o = JSONObject(json)
        if (o.getString("date") != today.toString()) return null
        val vehicle = o.optString("vehicle")
        val round = o.optInt("round")
        base.copy(
            phase = Phase.valueOf(o.getString("phase")),
            tripTitle = o.getString("title"),
            layout = VehicleLayouts.all.first { it.id == o.getString("layout") },
            selectedTrip = base.centerTrips.firstOrNull { it.vehicleCode == vehicle && it.round == round },
            startedAt = o.optString("startedAt").takeIf { it.isNotEmpty() }?.let(LocalTime::parse),
            odoStart = if (o.has("odoStart")) o.getDouble("odoStart") else null,
            lastDroppedId = o.optString("lastDroppedId").takeIf { it.isNotEmpty() },
            lastBoardedId = o.optString("lastBoardedId").takeIf { it.isNotEmpty() },
            pickupBase = if (o.has("baseActual")) o.getInt("baseActual") to o.getInt("basePlanned") else null,
            elders = o.getJSONArray("elders").objects().map { e ->
                Elder(
                    id = e.getString("id"),
                    maskedName = e.getString("name"),
                    area = e.getString("area"),
                    order = e.getInt("order"),
                    needsGuardian = e.getBoolean("guardian"),
                    seat = if (e.has("row")) SeatPos(e.getInt("row"), e.getInt("col")) else null,
                    status = RideStatus.valueOf(e.getString("status")),
                    droppedAt = e.optString("droppedAt").takeIf { it.isNotEmpty() },
                    lat = if (e.has("lat")) e.getDouble("lat") else null,
                    lng = if (e.has("lng")) e.getDouble("lng") else null,
                    plannedAt = e.optString("plannedAt").takeIf { it.isNotEmpty() },
                )
            },
            records = o.getJSONArray("records").objects().map { r ->
                TripRecord(
                    r.getString("time"), r.getString("name"), r.getString("kind"), r.getString("seat"),
                    ConfirmMethod.valueOf(r.getString("method")), r.getString("by"),
                    r.optString("id").takeIf { it.isNotEmpty() },
                    r.optString("door").takeIf { it.isNotEmpty() },
                )
            },
            boardedAt = o.getJSONArray("boarded").objects().associate {
                it.getString("id") to Boarding(it.getString("time"), ConfirmMethod.valueOf(it.getString("method")))
            },
            notifiedIds = List(o.getJSONArray("notified").length()) { o.getJSONArray("notified").getString(it) }.toSet(),
            clearedSeats = o.getJSONArray("cleared").objects().map { SeatPos(it.getInt("row"), it.getInt("col")) }.toSet(),
        )
    }.getOrNull()

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
}
