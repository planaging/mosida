package ai.planaging.mosida.probe

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
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Diagnostic screen: answers "can an app read door / seat / belt / gear / odometer signals?"
 * Every signal change is shown on screen and logged under tag [TAG] so it can be checked via logcat
 * while toggling doors in the Pleos Virtual Console.
 */
class SignalProbeActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var vehicle: Vehicle
    private lateinit var status: TextView
    private lateinit var events: TextView
    private val state = linkedMapOf<String, String>()
    private val unregister = mutableListOf<() -> Unit>()

    private val doors = listOf(
        DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT,
        DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT,
        DoorValue.ROW_3_LEFT, DoorValue.ROW_3_RIGHT,
        DoorValue.REAR,
    )
    private val seats = listOf(
        SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT,
        SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT,
        SeatValue.ROW_3_LEFT, SeatValue.ROW_3_CENTER, SeatValue.ROW_3_RIGHT,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestCarPermissions()
    }

    private fun buildUi() {
        status = TextView(this).apply { textSize = 22f; typeface = Typeface.MONOSPACE }
        events = TextView(this).apply { textSize = 18f; typeface = Typeface.MONOSPACE }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(40, 40, 40, 40)
            addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(ScrollView(this@SignalProbeActivity).apply { addView(events) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        setContentView(root)
        render()
    }

    private fun requestCarPermissions() {
        val missing = CAR_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startVehicle() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        permissions.forEachIndexed { i, p ->
            log("permission ${p.substringAfterLast('.')} = ${if (grantResults[i] == PackageManager.PERMISSION_GRANTED) "OK" else "DENIED"}")
        }
        startVehicle()
    }

    private fun startVehicle() {
        vehicle = Vehicle(this)
        vehicle.initialize()
        log("vehicle.initialize() called")
        // Service binding is asynchronous; give it a moment before the first calls.
        main.postDelayed({ probeAll() }, 1500)
    }

    private fun probeAll() {
        val door = vehicle.getDoor()
        door.checkDoorPositionCapabilities(
            { bounds: Map<DoorArea, *> -> log("door capability: ${bounds.size} areas -> ${bounds.keys}") },
            { e: Exception -> log("door capability FAILED: ${e.message}") },
        )
        doors.forEach { dv ->
            val area = DoorArea(dv)
            val key = "door ${dv.name}"
            door.getCurrentDoorPosition(area, { pos: Int? -> set(key, pos?.toString() ?: "null") }, { e -> set(key, "ERR ${e.message}") })
            val l = object : DoorPositionListener {
                override fun onPositionUpdated(position: Int?) = change(key, position?.toString() ?: "null")
                override fun onFailed(e: Exception) = set(key, "ERR ${e.message}")
            }
            door.registerDoorPosition(area, l)
            unregister += { door.unregisterDoorPosition(area, l) }
        }

        val seat = vehicle.getSeat()
        seat.checkSeatOccupancyCapabilities(
            { areas: List<SeatArea> -> log("seat occupancy capability: ${areas.size} areas") },
            { e: Exception -> log("seat occupancy capability FAILED: ${e.message}") },
        )
        seat.checkSeatBeltBuckledCapabilities(
            { areas: List<SeatArea> -> log("seat belt capability: ${areas.size} areas") },
            { e: Exception -> log("seat belt capability FAILED: ${e.message}") },
        )
        seats.forEach { sv ->
            val area = SeatArea(sv)
            val occ = "seat ${sv.name}"
            val belt = "belt ${sv.name}"
            seat.getCurrentSeatOccupancy(area, { v: Boolean? -> set(occ, v.toString()) }, { e -> set(occ, "ERR ${e.message}") })
            seat.getCurrentSeatBeltBuckledState(area, { v: Boolean? -> set(belt, v.toString()) }, { e -> set(belt, "ERR ${e.message}") })
            val lo = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(occupied: Boolean?) = change(occ, occupied.toString())
                override fun onFailed(e: Exception) = set(occ, "ERR ${e.message}")
            }
            val lb = object : SeatBeltBuckledStateListener {
                override fun onSeatBeltBuckledStateUpdated(buckled: Boolean?) = change(belt, buckled.toString())
                override fun onFailed(e: Exception) = set(belt, "ERR ${e.message}")
            }
            seat.registerSeatOccupancy(area, lo)
            seat.registerSeatBeltBuckledState(area, lb)
            unregister += { seat.unregisterSeatOccupancy(area, lo); seat.unregisterSeatBeltBuckledState(area, lb) }
        }

        val gear = vehicle.getGear()
        gear.getCurrentGearSelectionState({ g: VehicleGear? -> set("gear", g.toString()) }, { e -> set("gear", "ERR ${e.message}") })
        val lg = object : GearSelectionListener {
            override fun onSelected(g: VehicleGear?) = change("gear", g.toString())
            override fun onFailed(e: Exception) = set("gear", "ERR ${e.message}")
        }
        gear.registerGearSelection(lg)
        unregister += { gear.unregisterGearSelection(lg) }

        val odo = vehicle.getOdometer()
        odo.getCurrentDistanceDriven({ km: Float? -> set("odometer", km.toString()) }, { e -> set("odometer", "ERR ${e.message}") })
        val ld = object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(km: Float?) = change("odometer", km.toString())
            override fun onFailed(e: Exception) = set("odometer", "ERR ${e.message}")
        }
        odo.registerDistanceDriven(ld)
        unregister += { odo.unregisterDistanceDriven(ld) }
    }

    /** Initial value (no event line). */
    private fun set(key: String, value: String) {
        main.post {
            state[key] = value
            Log.i(TAG, "STATE $key=$value")
            render()
        }
    }

    /** Value pushed by a listener: also appended to the event log. */
    private fun change(key: String, value: String) {
        main.post {
            val before = state[key]
            state[key] = value
            log("$key: $before -> $value")
            render()
        }
    }

    private fun log(msg: String) {
        main.post {
            Log.i(TAG, "EVENT $msg")
            events.text = "${LocalTime.now().format(CLOCK)}  $msg\n${events.text}"
        }
    }

    private fun render() {
        status.text = buildString {
            appendLine("모시다 신호 점검")
            appendLine()
            state.forEach { (k, v) -> appendLine("%-20s %s".format(k, v)) }
        }
    }

    override fun onDestroy() {
        unregister.forEach { runCatching { it() } }
        if (::vehicle.isInitialized) vehicle.release()
        super.onDestroy()
    }

    companion object {
        const val TAG = "MosidaProbe"
        private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val CAR_PERMISSIONS = listOf(
            "pleos.car.permission.CAR_DOORS",
            "pleos.car.permission.CAR_SEATS",
            "pleos.car.permission.CAR_DRIVE",
            "pleos.car.permission.CAR_INFO",
        )
    }
}
