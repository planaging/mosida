package ai.planaging.mosida.trip

import ai.planaging.mosida.model.Elder
import ai.planaging.mosida.model.NaviStop
import ai.planaging.mosida.model.RideStatus
import ai.planaging.mosida.model.SeatPos
import ai.planaging.mosida.model.VehicleLayout
import ai.planaging.mosida.model.VehicleLayouts
import org.json.JSONObject

/** 센터 화면(center-web)이 저장한 하원표에서 차 한 대의 한 회차. */
data class CenterTrip(
    /** "dropoff" 또는 "pickup" */
    val kind: String,
    val vehicleCode: String,
    val vehicleName: String,
    val driver: String,
    val escort: String?,
    val round: Int,
    val departAt: String,
    val elders: List<Elder>,
) {
    val isPickup: Boolean get() = kind == "pickup"
    val label: String get() = "$vehicleCode $vehicleName ${round}차 · ${elders.size}명" + if (isPickup) " · $departAt 도착" else ""
    val title: String get() = (if (isPickup) "등원" else "하원") + " ${round}차 · 운전 $driver" + (escort?.let { " · 동승 $it" } ?: "")
    val layout: VehicleLayout
        get() = when (vehicleCode) {
            "00" -> VehicleLayouts.RAY_4
            "02" -> VehicleLayouts.CARNIVAL_9
            else -> VehicleLayouts.STARIA_11
        }
}

object CenterTripFile {
    const val FILE_NAME = "mosida-trip.json"

    /**
     * 시연용 정차 지점. 표에는 E번호만 있고 좌표가 없어서 에뮬레이터 지도(판교·성남) 안의 가짜 좌표를 순서대로 쓴다.
     * 실제 운행에서는 어르신별 정차 핀으로 바뀐다.
     */
    private val demoPins = SampleData.elders.mapNotNull { e -> e.lat?.let { lat -> e.lng?.let { lng -> lat to lng } } } +
        listOf(37.4097 to 127.1127, 37.4067 to 127.1057)

    /** 시연용 센터 위치(에뮬레이터 지도 안의 가짜 좌표). 등원 경로의 마지막 목적지. */
    val demoCenter = NaviStop("센터", 37.4020, 127.1086)

    /** 읽을 수 없는 파일이면 빈 목록. */
    fun parse(json: String): List<CenterTrip> = runCatching {
        val root = JSONObject(json)
        val kind = root.getString("kind")
        val trips = root.getJSONArray("trips")
        List(trips.length()) { i ->
            val t = trips.getJSONObject(i)
            val stops = t.getJSONArray("stops")
            CenterTrip(
                kind = kind,
                vehicleCode = t.getString("vehicle"),
                vehicleName = t.getString("name"),
                driver = t.getString("driver"),
                escort = t.optString("escort").takeUnless { t.isNull("escort") || it.isEmpty() },
                round = t.getInt("round"),
                departAt = t.getString("departAt"),
                elders = List(stops.length()) { j ->
                    val s = stops.getJSONObject(j)
                    val pin = demoPins[j % demoPins.size]
                    Elder(
                        id = s.getString("elder"),
                        maskedName = s.getString("elder"),
                        area = "",
                        order = s.getInt("order"),
                        needsGuardian = false, // 어르신 조건표가 들어오면 표에 함께 싣는다
                        seat = parseSeat(s.getString("seat")),
                        lat = pin.first,
                        lng = pin.second,
                        status = if (kind == "pickup") RideStatus.WAITING else RideStatus.ONBOARD,
                        plannedAt = s.optString("at").takeUnless { s.isNull("at") || it.isEmpty() },
                    )
                },
            )
        }
    }.getOrDefault(emptyList())

    /** "2열 오른쪽" → SeatPos(2, 2) */
    private fun parseSeat(label: String): SeatPos? {
        val m = Regex("(\\d+)열 (왼쪽|가운데|오른쪽)").find(label) ?: return null
        return SeatPos(m.groupValues[1].toInt(), listOf("왼쪽", "가운데", "오른쪽").indexOf(m.groupValues[2]))
    }
}
