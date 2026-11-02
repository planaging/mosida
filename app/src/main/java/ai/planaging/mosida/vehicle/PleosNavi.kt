package ai.planaging.mosida.vehicle

import ai.planaging.mosida.model.Guidance
import ai.planaging.mosida.model.NaviStop
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Pleos NaviHelper SDK 3.0.5 연결.
 *
 * - 경로는 Pleos 기본 내비가 계산한다. 앱은 정류장을 넘기고 안내만 받아 보여 준다(자체 경로 엔진 금지).
 * - 경유지는 최대 2곳(FIRST, SECOND) → [다음 집, 그다음 집] + 목적지(마지막 집). 하차할 때마다 다시 요청(롤링).
 * - 안내: onTBTInfo(회전) + onDrivingInfo(남은 거리·시간) → [Guidance].
 */
class PleosNavi(context: Context) {

    private val navi = NaviHelper(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var pendingStops: List<NaviStop>? = null
    private var currentStops: List<NaviStop> = emptyList()

    private var lastTurn: TBTInfo? = null
    private var nextRemainM: Int? = null
    private var nextRemainSec: Int? = null

    private val _guidance = MutableStateFlow<Guidance?>(null)
    val guidance: StateFlow<Guidance?> = _guidance

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(info: RouteStartInfo) {
            Log.i(TAG, "route started: ${info.distance}m ${info.duration}s")
        }

        override fun onTBTInfo(list: List<TBTInfo>) {
            lastTurn = list.firstOrNull()
            publish()
        }

        override fun onDrivingInfo(info: DrivingInfo) {
            // 다음 정류장 = 첫 번째 경유지(없으면 목적지)
            val next = info.waypoints.firstOrNull() ?: info.destination
            nextRemainM = next.distance
            nextRemainSec = next.duration
            publish()
        }

        override fun onWayPointArrived(info: WaypointArrivedInfo) {
            Log.i(TAG, "waypoint arrived: ${info.index} ${info.name}")
        }

        override fun onRouteCancelled() {
            Log.i(TAG, "route cancelled")
        }

        override fun onError(code: NaviErrorCode) {
            Log.w(TAG, "navi error: $code")
        }
    }

    fun start() {
        navi.initialize(
            {
                main.post {
                    ready = true
                    navi.addListener(listener)
                    Log.i(TAG, "NaviHelper ready")
                    pendingStops?.let { pendingStops = null; routeTo(it) }
                }
            },
            { e -> Log.w(TAG, "NaviHelper init failed: ${e.message}") },
        )
    }

    fun stop() {
        runCatching { navi.removeListener(listener) }
        runCatching { navi.release() }
        ready = false
    }

    fun routeTo(stops: List<NaviStop>) {
        if (!ready) {
            pendingStops = stops
            return
        }
        if (stops == currentStops) return
        currentStops = stops
        lastTurn = null; nextRemainM = null; nextRemainSec = null
        if (stops.isEmpty()) {
            runCatching { navi.cancelRoute() }
            _guidance.value = null
            return
        }
        val dest = stops.last()
        val waypoints = stops.dropLast(1).take(2).mapIndexed { i, s ->
            RequestWaypointInfo(s.lat, s.lng, s.label, if (i == 0) WaypointIndex.FIRST else WaypointIndex.SECOND, "", "", "", "")
        }
        val destination = RouteInfo(dest.lat, dest.lng, null, null, dest.label, "", "", "", RouteOption.RECOMMENDED, "", false)
        Log.i(TAG, "route: ${waypoints.map { it.poiName }} -> ${dest.label}")
        runCatching { navi.requestRouteWithWaypoints(RouteWithWaypointsRequest(destination, waypoints)) }
            .onFailure { Log.w(TAG, "route request failed: ${it.message}") }
        publish()
    }

    private fun publish() {
        main.post {
            val next = currentStops.firstOrNull()
            if (next == null) {
                _guidance.value = null
                return@post
            }
            val turn = lastTurn
            _guidance.value = Guidance(
                turn = turn?.let { "${meters(it.distance.toInt())} 앞 ${turnText(it.type, it.description)}" } ?: "경로 안내 준비 중",
                road = "${next.label} 방면",
                distance = nextRemainM?.let { meters(it) } ?: "-",
                etaMin = nextRemainSec?.let { (it + 59) / 60 } ?: 0,
            )
        }
    }

    private fun meters(m: Int): String = if (m >= 1000) "%.1fkm".format(m / 1000.0) else "${m}m"

    private fun turnText(t: TurnType, description: String?): String = when (t) {
        TurnType.TURN_RIGHT, TurnType.TURN_SHARP_R -> "우회전"
        TurnType.TURN_LEFT, TurnType.TURN_SHARP_L -> "좌회전"
        TurnType.TURN_STRAIGHT -> "직진"
        TurnType.TURN_KEEP_R -> "오른쪽 방향"
        TurnType.TURN_KEEP_L -> "왼쪽 방향"
        TurnType.TURN_UTURN -> "유턴"
        TurnType.TURN_WAY -> "경유지"
        TurnType.TURN_ARRIVE -> "도착"
        else -> description?.takeIf { it.isNotBlank() } ?: "안내"
    }

    companion object {
        private const val TAG = "PleosNavi"
    }
}
