package ai.planaging.mosida

import ai.planaging.mosida.model.EndBlock
import ai.planaging.mosida.trip.CareRecordExport
import ai.planaging.mosida.trip.CenterTripFile
import ai.planaging.mosida.trip.FULL_DAY_MIN
import ai.planaging.mosida.trip.Phase
import ai.planaging.mosida.trip.SampleData
import ai.planaging.mosida.trip.TripController
import ai.planaging.mosida.trip.TripSnapshot
import ai.planaging.mosida.trip.TripUiState
import ai.planaging.mosida.ui.MosidaApp
import ai.planaging.mosida.vehicle.FakeVehicleGateway
import ai.planaging.mosida.vehicle.PleosVehicleGateway
import ai.planaging.mosida.vehicle.VehicleGateway
import ai.planaging.mosida.voice.Announcer
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    /**
     * true  = 시연 패널로 문 열림·주행·착석을 흉내 (SDK 없이도 데모 가능)
     * false = Pleos 에뮬레이터/차량의 실제 도어 값 사용 (Virtual Console로 문 조작)
     */
    private val useFakeVehicle = false

    /** 음성 안내 (다음 하차, 벨트 풀림, 주행 중 문 열림, 잔류 감지) */
    private val useVoice = true

    private lateinit var gateway: VehicleGateway
    private lateinit var controller: TripController
    private var voice: Announcer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        gateway = if (useFakeVehicle) FakeVehicleGateway() else PleosVehicleGateway(this)
        if (!useFakeVehicle) {
            // Pleos 차량 권한은 보통 설치 때 자동 부여된다. 빠진 것만 물어본다(시연 중 허용 창이 뜨지 않게).
            val missing = CAR_PERMISSIONS.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        }

        controller = TripController(gateway, lifecycleScope, SampleData.elders)
        controller.start()
        val newTable = importTrip(intent)
        loadCenterTrips()
        if (newTable) stateFile().delete() else restoreTrip()
        keepTripSaved()
        if (useVoice) {
            voice = Announcer(this)
            startVoiceGuide()
        }
        if (!useFakeVehicle) comeBackAfterRoute()

        val fake = gateway as? FakeVehicleGateway
        setContent {
            val state by controller.state.collectAsState()
            var showLog by remember { mutableStateOf(false) }

            MosidaApp(
                state = state,
                controller = controller,
                fake = fake,
                showLog = showLog,
                onShowLog = { showLog = it },
                onExport = { exportCsv() },
            )
        }
    }

    /**
     * 음성 안내: 값이 바뀔 때만 한 번씩.
     * 화면(Compose)이 아니라 상태 흐름에 붙인다 — Pleos 내비 지도가 앞에 떠서 앱 화면이 멈춰 있어도
     * 기사는 안내를 들어야 하기 때문이다.
     */
    private fun startVoiceGuide() {
        val state = controller.state
        fun TripUiState.running() = phase == Phase.RUNNING && !tripEnded

        lifecycleScope.launch {
            state.map { it.running() to it.next?.id }.distinctUntilChanged().collect { (running, _) ->
                if (!running) return@collect
                val n = state.value.next
                if (state.value.isPickup) {
                    if (state.value.noPassengers) say("태울 어르신이 없습니다. 운행을 종료해 주세요.")
                    else if (n != null) say("다음 탑승, ${n.order}번 ${n.callName}. ${n.seatLabel} 자리" + (n.plannedAt?.let { ", 예정 시각 $it" } ?: "") + "입니다.")
                    else if (state.value.onboard.isNotEmpty()) say("모두 타셨습니다. 센터로 갑니다.")
                    return@collect
                }
                say(
                    if (n == null) "모든 어르신이 하차하셨습니다. 뒷좌석을 한 번 더 확인해 주세요."
                    else if (state.value.serviceSoFar(n)?.let { it < FULL_DAY_MIN } == true)
                        "다음 하차, ${n.order}번 ${n.callName}. 8시간이 되는 시각은 ${state.value.fullDayAt(n)}입니다."
                    else "다음 하차, ${n.order}번 ${n.callName}. " + (if (n.area.isEmpty()) "" else "${n.area}, ") + "${n.seatLabel} 좌석입니다."
                )
            }
        }
        lifecycleScope.launch {
            state.map { if (it.running()) it.unbuckled.firstOrNull() else null }.distinctUntilChanged { a, b -> a?.id == b?.id }.collect { e ->
                if (e != null) say("${e.seatLabel} 안전벨트가 풀렸습니다. 안전한 곳에 정차해 주세요.")
            }
        }
        lifecycleScope.launch {
            state.map { it.running() && it.doorDanger }.distinctUntilChanged().collect { danger ->
                if (danger) say("주행 중 뒷문이 열렸습니다. 즉시 정차해 주세요.")
            }
        }
        // 이름 안내(탈 때·내릴 때), 보호자 연락 알림
        lifecycleScope.launch {
            state.map { it.notice }.distinctUntilChanged { a, b -> a?.seq == b?.seq }.collect { n ->
                n?.speak?.let { say(it) }
            }
        }
        lifecycleScope.launch {
            state.map { it.endBlock }.distinctUntilChanged().collect { block ->
                if (block is EndBlock.OccupiedSeat) say("${block.seat.label}에 사람이 감지됐습니다. 직접 확인해 주세요.")
            }
        }
    }

    /**
     * Pleos 내비는 경로를 받으면 지도 화면을 앞으로 띄운다. 하차할 때마다 경로가 바뀌므로
     * 그대로 두면 다음 "하차하셨나요?" 창이 지도에 가려진다. 경로가 들어간 뒤 모시다를 다시 앞으로 올린다.
     * (지도를 직접 보고 싶을 땐 "차량 내비로 길안내" 버튼 — 그 경로는 여기를 타지 않는다)
     */
    private fun comeBackAfterRoute() {
        lifecycleScope.launch {
            controller.state.map { it.naviStops() }.distinctUntilChanged().drop(1).collect {
                delay(2500)
                bringToFront()
            }
        }
        // 정차해서 뒷문을 열면 "하차하셨나요?"가 바로 보여야 한다.
        lifecycleScope.launch {
            controller.state.map { it.phase == Phase.RUNNING && it.doorOpen }.distinctUntilChanged().collect { if (it) bringToFront() }
        }
    }

    /** "다른 앱 위에 표시" 허용이 있어야 동작한다(없으면 Android가 조용히 막는다). */
    private fun bringToFront() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    /**
     * 시연용 표 받기: `adb shell am start ... --es trip_b64 <파일의 base64>`.
     * 에뮬레이터는 PC에서 앱 폴더로 파일을 직접 넣을 수 없어서 이 길을 쓴다. 실제 운행은 서버에서 받는다.
     * 읽을 수 있는 하원표일 때만 저장한다.
     */
    private fun importTrip(intent: Intent?): Boolean {
        val b64 = intent?.getStringExtra("trip_b64") ?: return false
        intent.removeExtra("trip_b64")
        val json = runCatching { String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: return false
        if (CenterTripFile.parse(json).isEmpty()) return false
        File(getExternalFilesDir(null), CenterTripFile.FILE_NAME).writeText(json, Charsets.UTF_8)
        return true
    }

    private fun stateFile() = File(filesDir, TripSnapshot.FILE_NAME)

    /** 앱이 꺼졌다 켜져도 끝나지 않은 운행을 이어 간다. */
    private fun restoreTrip() {
        val file = stateFile()
        if (!file.exists()) return
        val ok = runCatching { controller.restore(file.readText(Charsets.UTF_8)) }.getOrDefault(false)
        android.util.Log.i("MosidaTrip", "restore: $ok")
        if (!ok) file.delete()
    }

    /** 기록이 바뀔 때마다 저장한다. 파일을 임시로 쓴 뒤 바꿔 넣어 쓰는 중에 꺼져도 깨지지 않게 한다. 운행이 끝나면 엑셀도 자동 저장. */
    private fun keepTripSaved() {
        lifecycleScope.launch {
            controller.state.map { TripSnapshot.key(it) }.distinctUntilChanged().drop(1).collect {
                val s = controller.state.value
                val file = stateFile()
                if (TripSnapshot.worthKeeping(s)) {
                    val tmp = File(filesDir, TripSnapshot.FILE_NAME + ".tmp")
                    tmp.writeText(TripSnapshot.toJson(s), Charsets.UTF_8)
                    tmp.renameTo(file)
                } else {
                    file.delete()
                }
            }
        }
        lifecycleScope.launch {
            controller.state.map { it.tripEnded }.distinctUntilChanged().collect {
                if (it) {
                    exportCsv()
                    saveCareRecord()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 운행 중에는 새 표를 받지 않는다(진행 중인 기록을 지키기 위해).
        val s = controller.state.value
        if (intent.hasExtra("trip_b64") && (s.phase == Phase.SETUP || s.tripEnded)) {
            if (importTrip(intent)) {
                controller.startOver()
                stateFile().delete()
                loadCenterTrips()
            }
        }
    }

    /** 센터 화면이 저장한 하원표가 있으면 읽는다. 없으면 시연용 샘플 명단 그대로. */
    private fun loadCenterTrips() {
        val file = File(getExternalFilesDir(null), CenterTripFile.FILE_NAME)
        if (!file.exists()) return
        val trips = CenterTripFile.parse(file.readText(Charsets.UTF_8))
        android.util.Log.i("MosidaTrip", "center trips: ${trips.map { it.label }}")
        controller.setCenterTrips(trips)
        loadServiceStarts()
    }

    private fun ledgerFile() = File(filesDir, "record-ledger-${java.time.LocalDate.now()}.json")

    /** 오늘 등원 탑승 시각을 하원 화면에 알려 준다(8시간 기준 안내). */
    private fun loadServiceStarts() {
        val f = ledgerFile()
        controller.setServiceStarts(CareRecordExport.serviceStarts(if (f.exists()) f.readText(Charsets.UTF_8) else null))
    }

    private fun say(text: String) {
        voice?.say(text)
    }

    /** 그날의 등원·하원을 합친 기관 전산 형식 파일. 운행이 끝날 때마다 갱신한다. */
    private fun saveCareRecord() {
        val day = java.time.LocalDate.now().toString()
        val dir = getExternalFilesDir(null)
        val ledgerFile = ledgerFile()
        val ledger = CareRecordExport.merge(if (ledgerFile.exists()) ledgerFile.readText(Charsets.UTF_8) else null, controller.state.value)
        ledgerFile.writeText(ledger, Charsets.UTF_8)
        val csv = CareRecordExport.toCsv(ledger, day)
        File(dir, "기관전산_$day.csv").writeText(csv, Charsets.UTF_8)
        android.util.Log.i("MosidaRecord", "saved 기관전산_$day.csv\n$csv")
        controller.setServiceStarts(CareRecordExport.serviceStarts(ledger))
    }

    private fun exportCsv() {
        val file = File(getExternalFilesDir(null), controller.exportFileName())
        val csv = controller.exportCsv()
        file.writeText(csv, Charsets.UTF_8)
        android.util.Log.i("MosidaExport", "saved ${file.absolutePath} (${csv.lines().size} lines)\n$csv")
        Toast.makeText(this, "저장됨: ${file.absolutePath}", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        voice?.release()
        controller.stop()
        super.onDestroy()
    }

    companion object {
        val CAR_PERMISSIONS = arrayOf(
            "pleos.car.permission.CAR_DOORS",
            "pleos.car.permission.CAR_SEATS",
            "pleos.car.permission.CAR_DRIVE",
        )
    }
}
