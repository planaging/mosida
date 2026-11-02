package ai.planaging.mosida.ui

import ai.planaging.mosida.R
import ai.planaging.mosida.model.ConfirmMethod
import ai.planaging.mosida.model.Elder
import ai.planaging.mosida.model.EndBlock
import ai.planaging.mosida.model.RideStatus
import ai.planaging.mosida.model.SeatPos
import ai.planaging.mosida.model.SeatRole
import ai.planaging.mosida.model.VehicleLayouts
import ai.planaging.mosida.trip.BoardPrompt
import ai.planaging.mosida.trip.DropoffPrompt
import ai.planaging.mosida.trip.FULL_DAY_MIN
import ai.planaging.mosida.trip.Phase
import ai.planaging.mosida.trip.TripController
import ai.planaging.mosida.trip.TripUiState
import ai.planaging.mosida.vehicle.FakeVehicleGateway
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 밤 모드 팔레트.
 * 의미 고정: 파랑 = 누르는 버튼, 초록 = 탑승·안전 확인, 주황 = 다음 하차·확인 필요, 빨강 = 위험.
 */
object C {
    val Bg = Color(0xFF0B1426)
    val Panel = Color(0xFF14213D)      // 네이비
    val Panel2 = Color(0xFF1C2B4A)
    val Line = Color(0xFF2A3B5E)
    val Text = Color(0xFFFFFFFF)
    val Muted = Color(0xFFA9B6CC)
    val Dim = Color(0xFF8394B2)

    val Blue = Color(0xFF2563EB)
    val BlueText = Color(0xFF8DB4FF)
    val BlueBg = Color(0xFF10224A)
    val Green = Color(0xFF2BC48A)
    val GreenText = Color(0xFF5EE0AE)
    val GreenBg = Color(0xFF0F2E26)
    val Orange = Color(0xFFFF7A3D)     // 오렌지(밤 화면용 밝기)
    val OrangeText = Color(0xFFFFA877)
    val OrangeBg = Color(0xFF3A2210)
    val Red = Color(0xFFFF5A5A)
    val RedText = Color(0xFFFFC9C9)
    val RedBg = Color(0xFF4A1414)
}

@Composable
fun MosidaApp(
    state: TripUiState,
    controller: TripController,
    fake: FakeVehicleGateway?,
    showLog: Boolean,
    onShowLog: (Boolean) -> Unit,
    onExport: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.phase == Phase.SETUP -> SetupScreen(state, controller)
                // 주행 중에는 기록 화면을 열어 두었어도 주행 화면이 우선이다.
                state.isMoving && !state.tripEnded -> DrivingScreen(state)
                state.tripEnded || showLog -> TripLogScreen(state, controller, onExport, onBack = { onShowLog(false) }, ended = state.tripEnded)
                else -> MainScreen(state, controller, onShowLog = { onShowLog(true) })
            }
            // 주행 중에는 누르는 창을 띄우지 않는다. 정차하면 그대로 다시 나타난다.
            if (!state.isMoving) {
                state.dropoffPrompt?.let { DropoffDialog(it, state, controller) }
                state.boardPrompt?.let { BoardDialog(it, state, controller) }
                if (state.arrivalPrompt) ArrivalDialog(state, controller)
                state.endBlock?.let { EndLockedScreen(it, state, controller) }
                state.callingGuardian?.let { CallScreen(it, controller) }
            }
            state.notice?.text?.let { NoticeBar(it, Modifier.align(Alignment.BottomCenter)) }
        }
        if (fake != null && state.phase == Phase.RUNNING) DemoPanel(fake, state)
    }
}

// ───────────────────────── 공통 조각 ─────────────────────────

@Composable
private fun Pill(text: String, bg: Color, fg: Color, big: Boolean = true) {
    Box(
        Modifier.height(if (big) 48.dp else 40.dp).background(bg, RoundedCornerShape(24.dp)).padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontSize = if (big) 21.sp else 18.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun PrimaryButton(text: String, height: Int, fontSize: Int, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(height.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = C.Blue, contentColor = C.Text,
            disabledContainerColor = C.Panel2, disabledContentColor = C.Muted,
        ),
    ) { Text(text, fontSize = fontSize.sp, fontWeight = FontWeight.Black) }
}

@Composable
private fun SecondaryButton(text: String, modifier: Modifier = Modifier, danger: Boolean = false, height: Int = 84, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(height.dp),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(2.dp, if (danger) C.Red else Color(0xFF34476E)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (danger) Color(0xFF2A0F12) else C.Panel2),
    ) { Text(text, color = if (danger) C.RedText else C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun AppHeader(title: String, sub: String, right: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(84.dp).padding(horizontal = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Image(painterResource(R.drawable.mosida_logo), contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
            Text(title, color = C.Text, fontSize = 30.sp, fontWeight = FontWeight.Black)
            Text(sub, color = C.Muted, fontSize = 24.sp)
        }
        right()
    }
}

/** 화면 맨 위 빨간 경고 띠 */
@Composable
private fun AlertBar(title: String, sub: String, strong: Boolean = false) {
    Column(Modifier.fillMaxWidth().background(if (strong) C.Red else C.RedBg).padding(horizontal = 40.dp, vertical = 18.dp)) {
        Text(title, color = C.Text, fontSize = if (strong) 40.sp else 34.sp, fontWeight = FontWeight.Black)
        Text(sub, color = if (strong) C.Text else C.RedText, fontSize = 21.sp)
    }
}

// ───────────────────────── 좌석판 (차종별) ─────────────────────────

private enum class SeatMode { SETUP, RUNNING, MINI }

/** 위에서 내려다본 차 + 차종 레이아웃대로 좌석. 통로 칸은 비워 둔다. */
@Composable
private fun CarSeats(state: TripUiState, modifier: Modifier, mode: SeatMode, onSeat: (SeatPos) -> Unit = {}) {
    val cellH: Dp = when (mode) { SeatMode.SETUP -> 90.dp; SeatMode.RUNNING -> 82.dp; SeatMode.MINI -> 64.dp }
    Box(
        modifier
            .border(3.dp, C.Line, RoundedCornerShape(topStart = 80.dp, topEnd = 80.dp, bottomStart = 32.dp, bottomEnd = 32.dp))
            .padding(top = 40.dp, start = 16.dp, end = 16.dp, bottom = 14.dp),
    ) {
        Text("앞", color = C.Muted, fontSize = 14.sp, modifier = Modifier.align(Alignment.TopCenter).offset(y = (-30).dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.layout.rows.forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { c, role ->
                        val pos = SeatPos(r + 1, c)
                        val cell = Modifier.weight(1f).height(cellH)
                        if (role == SeatRole.AISLE) Spacer(cell)
                        else SeatCell(pos, role, state, mode, cell, onSeat)
                    }
                }
            }
            if (mode != SeatMode.MINI) {
                val doorText = if (state.doorOpen && mode == SeatMode.RUNNING) "오른쪽 슬라이딩 문 · ${state.layout.doorRow}열 · 열림"
                else "오른쪽 슬라이딩 문 · ${state.layout.doorRow}열"
                Text(doorText, color = C.OrangeText, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

private data class Look(val bg: Color, val border: Color, val tagColor: Color, val nameColor: Color, val tag: String, val thick: Boolean = false)

@Composable
private fun SeatCell(pos: SeatPos, role: SeatRole, state: TripUiState, mode: SeatMode, modifier: Modifier, onSeat: (SeatPos) -> Unit) {
    // 운행 중에는 이미 내린 어르신을 좌석판에서 지운다 (그 자리에 착석이 감지되면 "착석 감지"로 경고).
    val seated = state.elderAt(pos)
    val elder = seated?.takeUnless { mode != SeatMode.SETUP && it.status == RideStatus.DROPPED }
    val look: Look = when (role) {
        SeatRole.DRIVER -> Look(C.Panel2, Color.Transparent, C.Muted, C.Text, "운전석")
        SeatRole.STAFF -> Look(C.Panel2, Color.Transparent, C.Muted, C.Text, "동승자")
        else -> if (mode == SeatMode.SETUP) setupLook(pos, elder, state) else runningLook(pos, elder, seated, state)
    }
    val name = when (role) {
        SeatRole.DRIVER -> "기사"
        SeatRole.STAFF -> "동승자"
        else -> elder?.maskedName ?: if (mode == SeatMode.SETUP) pos.label else ""
    }
    val clickable = mode == SeatMode.SETUP && role == SeatRole.PASSENGER
    Column(
        modifier
            .background(look.bg, RoundedCornerShape(14.dp))
            .border(if (look.thick) 3.dp else 2.dp, look.border, RoundedCornerShape(14.dp))
            .then(if (clickable) Modifier.clickable { onSeat(pos) } else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        if (mode != SeatMode.MINI) Text(look.tag, color = look.tagColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(name, color = look.nameColor, fontSize = if (mode == SeatMode.MINI) 16.sp else 20.sp, fontWeight = FontWeight.Bold)
    }
}

private fun setupLook(pos: SeatPos, elder: Elder?, state: TripUiState): Look = when {
    state.selectedSeat == pos -> Look(C.BlueBg, Color(0xFF4C8DFF), Color(0xFF9DBBFF), C.Text, "선택됨", thick = true)
    elder != null && elder.id in state.boardedIds -> Look(C.GreenBg, C.Green, C.GreenText, C.Text, "탑승")
    elder != null && elder.id == state.nextToBoard?.id -> Look(C.OrangeBg, C.Orange, C.OrangeText, C.Text, "다음 탑승", thick = true)
    elder != null -> Look(Color(0xFF1E2F52), Color(0xFF4A6496), C.Muted, C.Text, pos.label)
    else -> Look(Color.Transparent, C.Line, C.Dim, C.Dim, "빈 좌석")
}

/**
 * 운행 중 좌석 모습. 빈 자리의 착석 감지는 **모두 내린 뒤에만** 빨갛게 경고한다.
 * 하차 직후엔 센서가 늦게 풀리거나(짐·방석) 에뮬레이터처럼 계속 켜져 있을 수 있어,
 * 운행 중간에 빨간 칸이 뜨면 "내렸는데 왜 빨갛지?"로 기사를 헷갈리게 한다. 확인은 운행 종료 잠금이 맡는다.
 */
private fun runningLook(pos: SeatPos, elder: Elder?, seated: Elder?, state: TripUiState): Look {
    val sensed = state.seatOccupied?.get(pos) == true
    val cleared = pos in state.clearedSeats
    val belt = state.seatBelt?.get(pos)
    val onboard = elder?.status == RideStatus.ONBOARD
    val isNext = elder != null && elder.id == state.next?.id
    val allDropped = state.next == null
    return when {
        elder == null && sensed && cleared -> Look(C.Panel2, C.Line, C.Muted, C.Dim, "확인함 · 사람 없음")
        elder == null && sensed && allDropped -> Look(C.RedBg, C.Red, C.RedText, C.Text, "착석 감지", thick = true)
        elder == null && seated != null -> Look(Color.Transparent, C.Line, C.Dim, C.Dim, "하차 ${seated.droppedAt ?: ""}")
        elder == null -> Look(Color.Transparent, C.Line, C.Dim, C.Dim, "빈 좌석")
        onboard && state.isUnbuckled(pos) -> Look(C.RedBg, C.Red, C.RedText, C.Text, "벨트 풀림", thick = true)
        isNext -> Look(C.OrangeBg, C.Orange, C.OrangeText, C.Text, if (state.isPickup) "다음 탑승" else "다음 하차", thick = true)
        elder?.status == RideStatus.WAITING -> Look(Color.Transparent, C.Line, C.Muted, C.Muted, "대기 ${elder.plannedAt ?: ""}")
        onboard -> Look(C.GreenBg, C.Green, C.GreenText, C.Text, if (belt == true) "벨트 체결" else "탑승")
        sensed && !cleared -> Look(C.RedBg, C.Red, C.RedText, C.Text, "착석 감지", thick = true)
        else -> Look(Color.Transparent, C.Line, C.Dim, C.Dim, "하차 ${elder.droppedAt ?: ""}")
    }
}

// ───────────────────────── 0. 출발 전 좌석 배치 ─────────────────────────

@Composable
private fun SetupScreen(state: TripUiState, controller: TripController) {
    val absentCount = state.absent.size
    val sub = "${state.tripTitle} · 오늘 탑승 ${state.present.size}명" + if (absentCount > 0) " · 결석 ${absentCount}명" else ""
    Column(Modifier.fillMaxSize()) {
        AppHeader("출발 전 좌석 배치", sub) {
            // 센터 표의 운행은 차종이 정해져 있어 고르지 않는다(누르면 자리가 지워진다).
            if (state.selectedTrip == null) Row(Modifier.background(C.Panel, RoundedCornerShape(18.dp)).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VehicleLayouts.all.forEach { l ->
                    val on = l.id == state.layout.id
                    Button(
                        onClick = { controller.selectVehicle(l) },
                        modifier = Modifier.height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (on) C.Blue else Color.Transparent, contentColor = if (on) C.Text else C.Muted),
                    ) { Text(l.name, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
        Row(Modifier.weight(1f).padding(start = 40.dp, end = 40.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(
                Modifier.width(560.dp).fillMaxHeight().background(C.Panel, RoundedCornerShape(28.dp)).padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("좌석을 누르고 → 오른쪽 명단에서 어르신을 누르세요", color = C.Muted, fontSize = 19.sp)
                CarSeats(state, Modifier.fillMaxWidth().weight(1f), SeatMode.SETUP) { controller.selectSeat(it) }
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().background(C.Panel, RoundedCornerShape(28.dp)).padding(horizontal = 28.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("오늘 탑승 명단", color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("배치 ${state.present.size - state.unassigned.size} / ${state.present.size}", color = C.Muted, fontSize = 20.sp)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.elders.sortedBy { it.order }) { e ->
                        val absent = e.status == RideStatus.ABSENT
                        val todo = !absent && e.seat == null
                        Row(
                            Modifier.fillMaxWidth().height(58.dp)
                                .background(
                                    when {
                                        absent -> Color.Transparent
                                        todo -> C.OrangeBg
                                        else -> C.Panel2
                                    },
                                    RoundedCornerShape(16.dp),
                                )
                                .border(
                                    2.dp,
                                    when {
                                        absent -> C.Line
                                        todo -> C.Orange
                                        else -> Color.Transparent
                                    },
                                    RoundedCornerShape(16.dp),
                                )
                                .clickable(enabled = !absent) { controller.assignToSelected(e) }
                                .padding(start = 20.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("${e.order}. ${e.maskedName} 님", color = if (absent) C.Dim else C.Text, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    when {
                                        absent -> "결석"
                                        todo -> "미배정"
                                        else -> (e.plannedAt?.let { "$it 탑승 · " } ?: "") + (state.fullDayAt(e)?.let { "8시간 $it · " } ?: "") + e.seatLabel
                                    },
                                    color = if (todo) C.OrangeText else if (absent) C.Dim else C.Muted,
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                OutlinedButton(
                                    onClick = { controller.toggleAbsent(e) },
                                    modifier = Modifier.height(44.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, C.Line),
                                    contentPadding = PaddingValues(horizontal = 14.dp),
                                ) { Text(if (absent) "결석 취소" else "결석", color = C.Muted, fontSize = 16.sp) }
                            }
                        }
                    }
                }
            }
            Column(Modifier.width(420.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(
                    Modifier.fillMaxWidth().weight(1f).background(C.Panel, RoundedCornerShape(28.dp)).padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (state.centerTrips.isNotEmpty()) {
                        Text("센터 표에서 내 운행 고르기", color = C.Muted, fontSize = 20.sp)
                        state.centerTrips.forEach { t ->
                            val on = t == state.selectedTrip
                            OutlinedButton(
                                onClick = { controller.selectCenterTrip(t) },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(2.dp, if (on) C.Blue else C.Line),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = if (on) C.BlueBg else Color.Transparent),
                                contentPadding = PaddingValues(horizontal = 10.dp),
                            ) { Text(t.label, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                    Text("배치 확인", color = C.Muted, fontSize = 22.sp)
                    val left = state.unassigned
                    val cap = state.layout.capacity
                    val count = state.present.size
                    when {
                        count == 0 -> Note("오늘 탑승할 어르신이 없습니다", C.OrangeBg, C.OrangeText)
                        count > cap -> Note("${state.layout.name}은 어르신 ${cap}명까지 탈 수 있습니다", C.RedBg, C.RedText)
                        left.isEmpty() -> Note("${count}명 모두 좌석이 정해졌습니다", C.GreenBg, C.GreenText)
                        else -> Note("${left.joinToString { it.maskedName }} 님 좌석이 아직 없습니다", C.OrangeBg, C.OrangeText)
                    }
                    if (state.isPickup) {
                        Text("집 앞에서 뒷문이 열리면 탑승을 확인합니다", color = C.Muted, fontSize = 19.sp)
                        Text("먼저 타시는 분이 안쪽, 나중에 타시는 분이 문 가까이 앉습니다", color = C.Muted, fontSize = 19.sp)
                    } else if (left.isEmpty() && count in 1..cap) {
                        // 탑승 호출: 하차의 역순
                        Text("탑승 순서 · 마지막에 내릴 분부터", color = C.Text, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        val nb = state.nextToBoard
                        state.boardingOrder.forEach { e ->
                            val done = e.id in state.boardedIds
                            val now = e.id == nb?.id
                            Text(
                                (if (done) "✓ " else if (now) "▶ " else "   ") + "${e.order}. ${e.maskedName} 님 · ${e.seatLabel}",
                                color = if (now) C.OrangeText else if (done) C.GreenText else C.Muted,
                                fontSize = 20.sp, fontWeight = if (now) FontWeight.Black else FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (nb != null) PrimaryButton("${nb.maskedName} 님 탑승 확인", 72, 22) { controller.confirmBoarding() }
                        else Note("모두 탑승했습니다", C.GreenBg, C.GreenText)
                    } else {
                        Text("자동 배치는 먼저 내리는 분을 문 가까운 자리에 앉힙니다", color = C.Muted, fontSize = 19.sp)
                        if (absentCount > 0) Text("결석 ${absentCount}명은 운행 기록에 '결석'으로 남습니다", color = C.Muted, fontSize = 19.sp)
                    }
                }
                if (state.selectedTrip == null) SecondaryButton("하차 순서대로 자동 배치", Modifier.fillMaxWidth(), height = 80) { controller.autoAssign() }
                PrimaryButton("배치 완료 · 운행 시작", 96, 26, enabled = state.canStart) { controller.startTrip() }
            }
        }
    }
}

@Composable
private fun Note(text: String, bg: Color, fg: Color) {
    Box(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(18.dp)).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(text, color = fg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

// ───────────────────────── 1. 운행 중 (정차): 좌석 | 길안내 | 다음 하차 ─────────────────────────

@Composable
private fun MainScreen(state: TripUiState, controller: TripController, onShowLog: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        AppHeader("모시다", "${state.tripTitle} · ${state.layout.name}") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (state.doorOpen) Pill("뒷문 열림", C.OrangeBg, C.OrangeText)
                Pill("정차 중", C.GreenBg, C.GreenText)
                Pill(if (state.isPickup) "대기 ${state.waiting.size} · 탑승 ${state.onboard.size}" else "탑승 ${state.onboard.size} · 하차 ${state.droppedCount}", C.Panel, C.Text)
                OutlinedButton(onClick = onShowLog, modifier = Modifier.height(48.dp), border = BorderStroke(2.dp, C.Line)) {
                    Text("운행 기록", color = C.Text, fontSize = 19.sp)
                }
            }
        }
        Row(Modifier.weight(1f).padding(start = 40.dp, end = 40.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(
                Modifier.width(460.dp).fillMaxHeight().background(C.Panel, RoundedCornerShape(28.dp)).padding(horizontal = 22.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("좌석", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                CarSeats(state, Modifier.fillMaxWidth().weight(1f), SeatMode.RUNNING)
            }
            NaviPanel(state, controller, Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.width(500.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                NextPanel(state, controller, Modifier.fillMaxWidth().weight(1f))
                // 잘못 누른 하차 되돌리기 (기록에 "하차 취소"로 남는다)
                if (state.isPickup) state.elders.firstOrNull { it.id == state.lastBoardedId && it.status == RideStatus.ONBOARD }?.let { d ->
                    SecondaryButton("방금 탑승 취소 · ${d.maskedName} 님", Modifier.fillMaxWidth(), height = 72) { controller.undoLastPickup() }
                }
                state.lastDropped?.let { d ->
                    SecondaryButton("방금 하차 취소 · ${d.maskedName} 님", Modifier.fillMaxWidth(), height = 72) { controller.undoLastDropoff() }
                }
                val left = state.onboard.size + state.waiting.size
                // 탑승자가 남아 있어도 누를 수 있다 → 잠금 화면으로 이유를 보여준다
                PrimaryButton(if (left == 0) "운행 종료" else if (state.isPickup) "운행 종료 · ${left}명 남음" else "운행 종료 · ${left}명 탑승 중", 92, 23) { controller.requestEndTrip() }
            }
        }
    }
}

/** 가운데: 회전 안내 + 노선도형 하차 순서 + 차량 내비로 보내기 */
@Composable
private fun NaviPanel(state: TripUiState, controller: TripController, modifier: Modifier) {
    val g = state.guidance
    Column(modifier.background(C.Panel, RoundedCornerShape(28.dp)).padding(horizontal = 30.dp, vertical = 26.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Box(Modifier.size(104.dp).background(C.Blue, RoundedCornerShape(26.dp)), contentAlignment = Alignment.Center) {
                Text(turnArrow(g?.turn), color = C.Text, fontSize = 60.sp, fontWeight = FontWeight.Black)
            }
            Column(Modifier.weight(1f)) {
                val allDone = state.next == null
                Text(g?.turn ?: if (state.noPassengers) "경로 없음" else if (allDone) (if (state.isPickup) "센터로 이동" else "모든 하차 완료") else "경로 정보 없음", color = C.Text, fontSize = 48.sp, fontWeight = FontWeight.Black)
                Text(
                    g?.let { "${it.road} · " + (state.next?.let { n -> "${n.maskedName} 님 댁" } ?: "센터") + if (it.distance == "-") "" else "까지 ${it.distance}" }
                        ?: if (state.noPassengers) "태울 어르신이 없습니다" else if (allDone) (if (state.isPickup) "도착해서 뒷문을 열면 하차를 확인합니다" else "뒷좌석 확인 후 센터로 복귀") else "차량 내비 연결 후 표시됩니다",
                    color = C.Muted, fontSize = 22.sp,
                )
            }
            if (g != null && g.etaMin > 0) Text("${g.etaMin}분", color = C.Text, fontSize = 44.sp, fontWeight = FontWeight.Black)
        }
        RouteLine(state, Modifier.fillMaxWidth().weight(1f))
        OutlinedButton(
            onClick = { controller.sendNextToVehicleNavi() },
            modifier = Modifier.fillMaxWidth().height(72.dp),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(2.dp, C.Blue),
        ) { Text("차량 내비로 길안내 · 다음 집 목적지 보내기", color = C.BlueText, fontSize = 21.sp, fontWeight = FontWeight.Bold) }
    }
}

/** 안내 문구에 맞는 화살표 */
private fun turnArrow(turn: String?): String = when {
    turn == null -> "↑"
    turn.contains("좌회전") -> "↰"
    turn.contains("우회전") -> "↱"
    turn.contains("유턴") -> "↶"
    else -> "↑"
}

/** 노선도: 완료 → 현재 → 다음(주황) → 이후 2명 → +나머지 */
@Composable
private fun RouteLine(state: TripUiState, modifier: Modifier) {
    val upcoming = if (state.isPickup) state.waiting else state.onboard
    val sub = { e: Elder -> e.plannedAt ?: e.area }
    Column(modifier.background(C.Panel2, RoundedCornerShape(22.dp)).padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("오늘 경로", color = C.Muted, fontSize = 20.sp)
        Box(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 40.dp).offset(y = 26.dp).height(8.dp).background(C.Line, RoundedCornerShape(4.dp)))
            Row(Modifier.fillMaxWidth()) {
                Stop(if (state.isPickup) "탑승 ${state.onboard.size}명" else "하차 ${state.droppedCount}명", "완료", 40, C.Muted, C.Muted, "✓", C.Bg)
                Stop("현재 위치", state.guidance?.road ?: "", 36, C.Blue, C.BlueText, "", C.Text, nameColor = C.BlueText)
                upcoming.getOrNull(0)?.let { Stop(it.maskedName, state.guidance?.takeIf { g -> g.etaMin > 0 }?.let { g -> "${g.etaMin}분" } ?: sub(it), 56, C.Orange, C.Orange, "${it.order}", C.Text, big = true) }
                upcoming.getOrNull(1)?.let { Stop(it.maskedName, sub(it), 44, C.Panel, C.Green, "${it.order}", C.Text) }
                upcoming.getOrNull(2)?.let { Stop(it.maskedName, sub(it), 44, C.Panel, C.Green, "${it.order}", C.Text) }
                if (upcoming.size > 3) Stop("+${upcoming.size - 3}명", upcoming.drop(3).joinToString("·") { sub(it) }.take(10), 36, C.Panel, C.Line, "", C.Text, nameColor = C.Muted)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Stop(
    name: String, sub: String, size: Int, fill: Color, ring: Color, mark: String, markColor: Color,
    big: Boolean = false, nameColor: Color = C.Text,
) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.height(60.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(size.dp).background(fill, CircleShape).border(4.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
                Text(mark, color = markColor, fontSize = 18.sp, fontWeight = FontWeight.Black)
            }
        }
        Text(name, color = nameColor, fontSize = if (big) 24.sp else 20.sp, fontWeight = if (big) FontWeight.Black else FontWeight.Bold)
        Text(sub, color = C.Muted, fontSize = 17.sp)
    }
}

@Composable
private fun NextPanel(state: TripUiState, controller: TripController, modifier: Modifier) {
    val next = state.next
    Column(modifier.background(C.Panel, RoundedCornerShape(28.dp)).padding(horizontal = 32.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.noPassengers) {
            Text("태울 어르신이 없습니다", color = C.OrangeText, fontSize = 44.sp, fontWeight = FontWeight.Black)
            Text("모두 오늘 안 오셨어요. 운행 종료를 눌러 주세요", color = C.Muted, fontSize = 22.sp)
        } else if (state.isPickup) {
            if (next == null) {
                Text("모두 타셨습니다", color = C.GreenText, fontSize = 44.sp, fontWeight = FontWeight.Black)
                Text("센터에 도착해서 뒷문을 열면 하차를 확인합니다", color = C.Muted, fontSize = 22.sp)
            } else {
                Text("다음 탑승 · ${state.onboard.size + 1} / ${state.present.size}", color = C.Muted, fontSize = 22.sp)
                Text("${next.maskedName} 님", color = C.Text, fontSize = 72.sp, fontWeight = FontWeight.Black)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    next.plannedAt?.let { Pill("$it 예정", C.BlueBg, C.BlueText) }
                    Pill(next.seatLabel, C.Panel2, C.Text)
                }
                state.pickupDelayMin?.let { d ->
                    Note("예정보다 ${d}분 밀림" + if (state.waiting.size > 1) " · 뒤 ${state.waiting.size - 1}명도 밀려요" else "", C.OrangeBg, C.OrangeText)
                }
                Spacer(Modifier.weight(1f))
                if (next.id in state.notifiedIds) Text("✓ 보호자께 도착 예정 알림 보냄", color = C.GreenText, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                SecondaryButton("📞 보호자 전화", Modifier.fillMaxWidth(), height = 72) { controller.callGuardian(next) }
            }
        } else if (next == null) {
            Text("모든 어르신 하차 완료", color = C.GreenText, fontSize = 44.sp, fontWeight = FontWeight.Black)
            Text("뒷좌석을 한 번 더 보고 운행 종료를 누르세요", color = C.Muted, fontSize = 22.sp)
        } else {
            Text("다음 하차 · ${state.droppedCount + 1} / ${state.present.size}", color = C.Muted, fontSize = 22.sp)
            Text("${next.maskedName} 님", color = C.Text, fontSize = 72.sp, fontWeight = FontWeight.Black)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (next.area.isNotEmpty()) Pill(next.area, C.Panel2, C.Text)
                Pill(next.seatLabel, C.Panel2, C.Text)
                state.fullDayAt(next)?.let { Pill("8시간 $it", C.BlueBg, C.BlueText) }
            }
            Spacer(Modifier.weight(1f))
            if (next.needsGuardian) {
                Column(Modifier.fillMaxWidth().background(C.OrangeBg, RoundedCornerShape(20.dp)).padding(horizontal = 22.dp, vertical = 18.dp)) {
                    Text("보호자 인계 필요", color = C.OrangeText, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text("보호자를 만난 뒤 하차 확인을 눌러 주세요", color = Color(0xFFE8D3C4), fontSize = 18.sp)
                }
            }
            if (next.id in state.notifiedIds) Text("✓ 보호자께 도착 예정 알림 보냄", color = C.GreenText, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            SecondaryButton("📞 보호자 전화", Modifier.fillMaxWidth(), height = 72) { controller.callGuardian(next) }
        }
    }
}

/** 화면 아래 알림 띠 (이름 안내·보호자 연락). 6초 뒤 사라진다. */
@Composable
private fun NoticeBar(text: String, modifier: Modifier) {
    Box(
        modifier.padding(bottom = 36.dp).background(C.GreenBg, RoundedCornerShape(26.dp)).border(2.dp, C.Green, RoundedCornerShape(26.dp))
            .padding(horizontal = 36.dp, vertical = 20.dp),
    ) { Text(text, color = C.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
}

/** 보호자 전화 연결 화면. 시연판은 실제로 걸지 않는다(번호도 화면에 보이지 않음). */
@Composable
private fun CallScreen(e: Elder, controller: TripController) {
    Box(Modifier.fillMaxSize().background(Color(0xF2060C17)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(900.dp).background(C.Panel, RoundedCornerShape(32.dp)).padding(horizontal = 52.dp, vertical = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Text("📞", fontSize = 72.sp)
            Text("${e.maskedName} 님 보호자", color = C.Text, fontSize = 56.sp, fontWeight = FontWeight.Black)
            Text("전화 연결 중…", color = C.GreenText, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("시연 모드 · 실제로 전화가 걸리지 않습니다. 운행 기록에 남습니다.", color = C.Muted, fontSize = 20.sp)
            SecondaryButton("통화 종료", Modifier.fillMaxWidth(), danger = true) { controller.endCall() }
        }
    }
}

// ───────────────────────── 2. 주행 중 ─────────────────────────

@Composable
private fun DrivingScreen(state: TripUiState) {
    val next = state.next
    val g = state.guidance
    Column(Modifier.fillMaxSize()) {
        if (state.doorDanger) AlertBar("주행 중 뒷문 열림", "즉시 안전한 곳에 정차해 주세요", strong = true)
        state.unbuckled.firstOrNull()?.let { e ->
            AlertBar("${e.seatLabel} ${e.maskedName} 님 · 안전벨트 풀림", "안전한 곳에 정차 후 확인해 주세요")
        }
        Row(Modifier.weight(1f).padding(horizontal = 40.dp, vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(
                Modifier.width(400.dp).fillMaxHeight().background(C.Panel, RoundedCornerShape(28.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("탑승 ${state.onboard.size}명", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                CarSeats(state, Modifier.fillMaxWidth().weight(1f), SeatMode.MINI)
            }
            Row(
                Modifier.weight(1f).fillMaxHeight().background(C.Panel, RoundedCornerShape(28.dp)).padding(horizontal = 44.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(40.dp),
            ) {
                Box(Modifier.size(180.dp).background(C.Blue, RoundedCornerShape(44.dp)), contentAlignment = Alignment.Center) {
                    Text(turnArrow(g?.turn), color = C.Text, fontSize = 110.sp, fontWeight = FontWeight.Black)
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(g?.turn ?: "주행 중", color = C.Text, fontSize = 92.sp, fontWeight = FontWeight.Black)
                    if (g != null) Text(g.road, color = Color(0xFFDCE3EF), fontSize = 32.sp)
                    if (next != null) Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                        Text(if (state.isPickup) "다음 탑승" else "다음 하차", color = C.OrangeText, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text("${next.maskedName} 님", color = C.Text, fontSize = 46.sp, fontWeight = FontWeight.Black)
                        if (g != null && g.etaMin > 0) Text("· ${g.etaMin}분", color = C.Muted, fontSize = 30.sp)
                    } else Text(if (state.noPassengers) "태울 어르신 없음" else if (state.isPickup) "센터로 이동" else "모두 하차 완료", color = C.GreenText, fontSize = 46.sp, fontWeight = FontWeight.Black)
                }
            }
            Column(
                Modifier.width(280.dp).fillMaxHeight().background(C.Panel, RoundedCornerShape(28.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("주행 중\n화면 조작 잠금", color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("정차하면 상세 화면으로 바뀝니다", color = C.Muted, fontSize = 18.sp)
            }
        }
    }
}

// ───────────────────────── 3. 하차 확인 ─────────────────────────

@Composable
private fun DropoffDialog(p: DropoffPrompt, state: TripUiState, controller: TripController) {
    Box(Modifier.fillMaxSize().background(Color(0xF2060C17)), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.width(360.dp).height(620.dp).background(C.Panel, RoundedCornerShape(32.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("감지된 문 · ${state.layout.name}", color = C.Muted, fontSize = 19.sp)
                CarSeats(state, Modifier.fillMaxWidth().weight(1f), SeatMode.MINI)
                Text("오른쪽 슬라이딩 문 열림 · ${p.openedAt}", color = C.OrangeText, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
            Column(
                Modifier.width(1000.dp).background(C.Panel, RoundedCornerShape(32.dp)).padding(horizontal = 52.dp, vertical = 40.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Text("확인 전까지 기록되지 않습니다", color = C.Muted, fontSize = 21.sp)
                if (p.choosingOther) {
                    Text("누가 내리셨나요?", color = C.Text, fontSize = 52.sp, fontWeight = FontWeight.Black)
                    state.onboard.forEach { e ->
                        SecondaryButton("${e.maskedName} 님 · ${e.seatLabel}", Modifier.fillMaxWidth(), height = 72) { controller.pickElder(e) }
                    }
                } else {
                    val e = p.suggested
                    Text("${e.maskedName} 님\n하차하셨나요?", color = C.Text, fontSize = 60.sp, fontWeight = FontWeight.Black, lineHeight = 70.sp)
                    // 8시간 사전 안내: 기록을 바꾸지 않고, 지금 내리시면 몇 시간인지 알려 계획을 바꿀 수 있게 한다.
                    state.serviceSoFar(e)?.takeIf { it < FULL_DAY_MIN }?.let { m ->
                        Note("지금 내리시면 오늘 제공시간 ${m / 60}시간 ${m % 60}분 · 8시간까지 ${FULL_DAY_MIN - m}분 (${state.fullDayAt(e)})", C.OrangeBg, C.OrangeText)
                    }
                    if (e.needsGuardian) {
                        Row(
                            Modifier.fillMaxWidth().background(C.OrangeBg, RoundedCornerShape(22.dp)).clickable { controller.toggleGuardian() }.padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Checkbox(
                                checked = p.guardianChecked,
                                onCheckedChange = { controller.toggleGuardian() },
                                modifier = Modifier.size(64.dp),
                                colors = CheckboxDefaults.colors(checkedColor = C.Green, uncheckedColor = C.OrangeText),
                            )
                            Column {
                                Text("보호자를 만났어요", color = C.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                                Text("혼자 계시면 안 되는 어르신 · 체크해야 확인 가능", color = Color(0xFFE8D3C4), fontSize = 19.sp)
                            }
                        }
                        if (!p.guardianChecked) SecondaryButton("📞 보호자가 안 나오셨어요 · 전화", Modifier.fillMaxWidth(), height = 72) { controller.callGuardian(e) }
                    }
                    PrimaryButton("하차 확인", 108, 36, enabled = controller.canConfirm(p)) { controller.confirmDropoff() }
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        SecondaryButton("다른 어르신이 내렸어요", Modifier.weight(1f)) { controller.chooseOther() }
                        SecondaryButton("아직 안 내렸어요", Modifier.weight(1f)) { controller.dismissPrompt() }
                    }
                }
            }
        }
    }
}

// ───────────────────────── 3-1. 등원: 집 앞 탑승 확인 · 센터 도착 확인 ─────────────────────────

@Composable
private fun BoardDialog(p: BoardPrompt, state: TripUiState, controller: TripController) {
    Box(Modifier.fillMaxSize().background(Color(0xF2060C17)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(1000.dp).background(C.Panel, RoundedCornerShape(32.dp)).padding(horizontal = 52.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("확인 전까지 기록되지 않습니다", color = C.Muted, fontSize = 21.sp)
            if (p.choosingOther) {
                Text("누가 타셨나요?", color = C.Text, fontSize = 52.sp, fontWeight = FontWeight.Black)
                state.waiting.forEach { e ->
                    SecondaryButton("${e.maskedName} 님 · ${e.seatLabel}", Modifier.fillMaxWidth(), height = 72) { controller.pickBoardingElder(e) }
                }
            } else {
                val e = p.suggested
                Text("${e.maskedName} 님\n타셨나요?", color = C.Text, fontSize = 60.sp, fontWeight = FontWeight.Black, lineHeight = 70.sp)
                Text(e.seatLabel + (e.plannedAt?.let { " · 예정 $it" } ?: ""), color = C.OrangeText, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                PrimaryButton("탑승 확인", 108, 36) { controller.confirmPickup() }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    SecondaryButton("아직 안 나오셨어요", Modifier.weight(1f)) { controller.dismissBoarding() }
                    SecondaryButton("📞 보호자 전화", Modifier.weight(1f)) { controller.callGuardian(e) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    SecondaryButton("다른 어르신이 타셨어요", Modifier.weight(1f)) { controller.chooseOtherBoarding() }
                    SecondaryButton("오늘 안 오심", Modifier.weight(1f), danger = true) { controller.markNoShow(e) }
                }
            }
        }
    }
}

@Composable
private fun ArrivalDialog(state: TripUiState, controller: TripController) {
    Box(Modifier.fillMaxSize().background(Color(0xF2060C17)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(1000.dp).background(C.Panel, RoundedCornerShape(32.dp)).padding(horizontal = 52.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Text("센터 도착", color = C.Muted, fontSize = 21.sp)
            Text("${state.onboard.size}명 모두\n내리셨나요?", color = C.Text, fontSize = 60.sp, fontWeight = FontWeight.Black, lineHeight = 70.sp)
            PrimaryButton("모두 내림 · 운행 종료", 108, 34) { controller.confirmArrival() }
            SecondaryButton("아직이에요", Modifier.fillMaxWidth()) { controller.dismissArrival() }
        }
    }
}

// ───────────────────────── 4. 운행 종료 잠금 ─────────────────────────

@Composable
private fun EndLockedScreen(block: EndBlock, state: TripUiState, controller: TripController) {
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        AlertBar(
            "운행을 종료할 수 없습니다",
            when (block) {
                is EndBlock.StillOnboard -> "하차 기록이 없는 어르신이 ${block.elders.size}명 있습니다"
                is EndBlock.NotPickedUp -> "아직 안 태운 어르신이 ${block.elders.size}명 있습니다"
                is EndBlock.OccupiedSeat -> "모두 하차 처리됐지만 좌석 센서 ${state.sensedUnclearedSeats().size}곳에 사람이 감지됐습니다"
            },
        )
        Row(Modifier.weight(1f).padding(horizontal = 40.dp, vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(
                Modifier.width(480.dp).fillMaxHeight().background(C.Panel, RoundedCornerShape(28.dp)).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("좌석 센서 · ${state.layout.name}", color = C.Muted, fontSize = 20.sp)
                CarSeats(state, Modifier.fillMaxWidth().weight(1f), SeatMode.RUNNING)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                val where = when (block) {
                    is EndBlock.OccupiedSeat -> state.sensedUnclearedSeats().joinToString(", ") { it.label }.ifEmpty { block.seat.label }
                    is EndBlock.StillOnboard -> "차 뒤쪽"
                    is EndBlock.NotPickedUp -> "남은 집"
                }
                Column(Modifier.fillMaxWidth().background(C.Panel, RoundedCornerShape(28.dp)).padding(32.dp)) {
                    Text("$where 좌석을 직접 확인해 주세요", color = C.Text, fontSize = 42.sp, fontWeight = FontWeight.Black)
                    Text("잠드신 어르신이나 두고 내린 짐일 수 있습니다", color = C.Muted, fontSize = 22.sp)
                }
                when (block) {
                    is EndBlock.StillOnboard -> block.elders.take(2).forEach { e ->
                        PrimaryButton("${e.maskedName} 님 지금 하차 처리 · ${e.seatLabel}", 92, 27) { controller.manualDropoff(e) }
                    }
                    is EndBlock.NotPickedUp -> block.elders.take(3).forEach { e ->
                        SecondaryButton("${e.maskedName} 님 오늘 안 오심", Modifier.fillMaxWidth(), height = 80) { controller.markNoShow(e) }
                    }
                    is EndBlock.OccupiedSeat -> PrimaryButton("직접 확인함 · 사람 없음 · 운행 종료", 104, 30) { controller.clearSeatWarning() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    SecondaryButton("센터에 바로 알리기", Modifier.weight(1f), danger = true) { /* 2단계: 센터 알림 */ }
                    SecondaryButton("돌아가기", Modifier.weight(1f)) { controller.dismissEndBlock() }
                }
            }
        }
    }
}

// ───────────────────────── 5. 운행 기록 ─────────────────────────

@Composable
private fun TripLogScreen(state: TripUiState, controller: TripController, onExport: () -> Unit, onBack: () -> Unit, ended: Boolean) {
    val sm = state.summary
    // 담당자 확인용 기본 화면은 승하차·경고만. 엑셀에는 언제나 전체가 들어간다.
    var showAll by remember { mutableStateOf(false) }
    val notices = setOf(ConfirmMethod.GUARDIAN_NOTICE, ConfirmMethod.GUARDIAN_CALL)
    val shown = if (showAll) state.records else state.records.filter { it.method !in notices }
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(if (ended) "운행 종료 · 기록" else "운행 기록", color = C.Text, fontSize = 36.sp, fontWeight = FontWeight.Black)
                Text("${state.tripTitle} · ${state.layout.name} · 감지·확인된 시각만 저장됩니다", color = C.Muted, fontSize = 20.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton(if (showAll) "승하차만 보기" else "전체 보기", Modifier.width(200.dp), height = 76) { showAll = !showAll }
                if (ended) state.nextRound?.let { n -> PrimaryButton("${n.round}차 시작", 76, 24, modifier = Modifier.width(200.dp)) { controller.startNextRound() } }
                if (!ended) SecondaryButton("돌아가기", Modifier.width(200.dp), height = 76) { onBack() }
                // 차량 화면은 토스트가 안 보일 수 있어 버튼 글자로 저장을 알린다.
                var saved by remember { mutableStateOf<String?>(null) }
                PrimaryButton(saved ?: "엑셀로 내보내기", 76, 24, modifier = Modifier.width(320.dp)) {
                    onExport()
                    saved = "저장됨 ✓ · ${state.records.size}줄"
                }
            }
        }
        // 운행일지 요약: 센터 보고용
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Stat("출발", sm.start, Modifier.weight(1f))
            Stat("종료", sm.end, Modifier.weight(1f))
            Stat("소요", sm.minutes?.let { "${it}분" } ?: "-", Modifier.weight(1f))
            Stat("주행", sm.drivenKm?.let { "%.1fkm".format(it) } ?: "-", Modifier.weight(1f))
            Stat("하차", "${sm.dropped} / ${sm.total}", Modifier.weight(1f), good = sm.total > 0 && sm.dropped == sm.total)
            Stat("보호자 인계", "${sm.guardian}", Modifier.weight(1f))
            Stat("결석", "${sm.absent}", Modifier.weight(1f))
            Stat("경고·확인", "${sm.warnings}", Modifier.weight(1f), danger = sm.warnings > 0)
        }
        Column(Modifier.weight(1f).fillMaxWidth().background(C.Panel, RoundedCornerShape(24.dp)).padding(vertical = 8.dp)) {
            LogRow("시간", "어르신", "구분", "좌석", "확인 방식", "확인자", header = true)
            LazyColumn {
                items(shown) { r ->
                    LogRow(r.time, r.elderName, r.kind, r.seat, r.method.label, r.confirmedBy, header = false)
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, good: Boolean = false, danger: Boolean = false) {
    val bg = when { danger -> C.RedBg; good -> C.GreenBg; else -> C.Panel }
    val fg = when { danger -> C.RedText; good -> C.GreenText; else -> C.Text }
    Column(modifier.background(bg, RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 14.dp)) {
        Text(label, color = C.Muted, fontSize = 17.sp)
        Text(value, color = fg, fontSize = 30.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun LogRow(a: String, b: String, c: String, d: String, e: String, f: String, header: Boolean) {
    val color = if (header) C.Muted else C.Text
    val size = if (header) 18.sp else 21.sp
    // 경고·취소 줄은 눈에 띄게
    val kindColor = when {
        header -> color
        c == "경고" || c == "잔류 확인" -> C.RedText
        c == "하차 취소" || c == "결석" || c == "탑승 확인 안 됨" -> C.OrangeText
        c == "탑승" -> C.GreenText
        else -> color
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(a, color = C.Muted, fontSize = size, modifier = Modifier.width(100.dp))
        Text(b, color = color, fontSize = size, fontWeight = if (header) FontWeight.Normal else FontWeight.Bold, modifier = Modifier.width(180.dp))
        Text(c, color = kindColor, fontSize = size, fontWeight = if (header) FontWeight.Normal else FontWeight.Bold, modifier = Modifier.width(120.dp))
        Text(d, color = C.Muted, fontSize = size, modifier = Modifier.width(160.dp))
        Text(e, color = color, fontSize = size, modifier = Modifier.weight(1f))
        Text(f, color = C.Muted, fontSize = size, modifier = Modifier.width(120.dp))
    }
}

// ───────────────────────── 시연 패널 (가짜 차량 + 운행 중일 때만) ─────────────────────────

@Composable
private fun DemoPanel(fake: FakeVehicleGateway, state: TripUiState) {
    val door by fake.rearRightDoorOpen.collectAsState()
    val moving by fake.isMoving.collectAsState()
    // 벨트 시연: 탑승 중 두 번째 어르신 좌석 / 잔류 시연: 비어 있는 첫 승객 좌석
    val beltSeat = state.onboard.getOrNull(1)?.seat
    val ghostSeat = state.layout.passengerSeats.firstOrNull { state.elderAt(it) == null }
        ?: state.layout.passengerSeats.lastOrNull()
    val beltOff = beltSeat != null && state.seatBelt?.get(beltSeat) == false
    val ghost = ghostSeat != null && state.seatOccupied?.get(ghostSeat) == true
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF03060B)).padding(horizontal = 40.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("시연 패널", color = C.Muted, fontSize = 15.sp)
        DemoButton(if (door) "뒷문 닫기" else "뒷문 열기") { fake.setDoorOpen(!door) }
        DemoButton(if (moving) "정차" else "주행 시작") { fake.setMoving(!moving) }
        if (beltSeat != null) DemoButton(if (beltOff) "${beltSeat.label} 벨트 체결" else "${beltSeat.label} 벨트 풀기") { fake.setBeltBuckled(beltSeat, beltOff) }
        if (ghostSeat != null) DemoButton(if (ghost) "${ghostSeat.label} 착석 해제" else "${ghostSeat.label} 착석 감지") { fake.setSeatOccupied(ghostSeat, !ghost) }
    }
}

@Composable
private fun DemoButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, border = BorderStroke(1.dp, C.Line)) { Text(text, color = C.Text) }
}
