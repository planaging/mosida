package ai.planaging.mosida.trip

import ai.planaging.mosida.model.ConfirmMethod
import org.json.JSONObject

/**
 * 기관 전산(일일 등하원 엑셀) 열 형식으로 내보낸다. 하루의 등원·하원 운행을 어르신별 한 줄로 합친다.
 *
 * 주간보호의 그날 제공시간은 등원 때 차에 탄 시각부터 하원 때 집에서 내린 시각까지다.
 * 시작·종료시간은 실제로 확인된 시각 그대로이고, 제공시간은 그 둘의 차이를 계산만 한다(시각을 맞추거나 고치지 않는다).
 * 수급코드·이름·인정번호·거주지·이용구분은 센터가 전산에서 붙이고, 최종 청구 전 담당자가 확인한다.
 */
object ArgosExport {
    val HEADER = listOf(
        "연번", "k_nalja", "수급코드", "수급자명", "인정번호",
        "시작코스", "시작차번호", "시작거주지", "시작시간", "시작기사", "시작동승",
        "종료코스", "종료차번호", "종료거주지", "종료시간", "종료기사", "종료동승",
        "이용구분", "제공시간", "총제공분",
    )

    private val BOARD = setOf(ConfirmMethod.PICKUP_CONFIRMED, ConfirmMethod.BOARDED_SENSOR, ConfirmMethod.BOARDED_TAP)
    private val ALIGHT = setOf(ConfirmMethod.DOOR_CONFIRMED, ConfirmMethod.DOOR_CONFIRMED_GUARDIAN, ConfirmMethod.MANUAL_AFTER_WARNING)

    /** 그날 장부(JSON)에 끝난 운행을 합친다. 등원 운행은 시작 칸만, 하원 운행은 종료 칸만 채운다. */
    fun merge(ledger: String?, s: TripUiState): String {
        val root = ledger?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val trip = s.selectedTrip ?: return root.toString()
        val prefix = if (trip.isPickup) "시작" else "종료"
        val wanted = if (trip.isPickup) BOARD else ALIGHT
        // 어르신별 마지막 기록을 본다. 취소가 마지막이면 칸을 비운다(예: 탑승 후 "방금 탑승 취소").
        s.records.filter { it.elderId != null && (it.method in wanted || it.method == ConfirmMethod.UNDO) }
            .groupBy { it.elderId!! }
            .forEach { (id, rows) ->
                val last = rows.last()
                val row = root.optJSONObject(id) ?: JSONObject().also { root.put(id, it) }
                if (last.method == ConfirmMethod.UNDO) {
                    row.remove(prefix + "시간")
                } else {
                    row.put(prefix + "코스", trip.vehicleCode)
                    row.put(prefix + "차번호", trip.vehicleName) // 번호판은 센터 전산에서 붙인다
                    row.put(prefix + "시간", last.time)
                    row.put(prefix + "기사", trip.driver)
                    row.put(prefix + "동승", trip.escort ?: "")
                }
            }
        return root.toString()
    }

    /** 탑승부터 하차까지(분). 둘 중 하나라도 없으면 null */
    fun serviceMinutes(start: String?, end: String?): Int? {
        fun m(t: String?) = t?.let { Regex("^(\\d{1,2}):(\\d{2})$").find(it) }?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        val a = m(start) ?: return null
        val b = m(end) ?: return null
        return (b - a).takeIf { it >= 0 }
    }

    /** 장부에서 어르신별 등원 탑승 시각(그날 제공시간의 시작) */
    fun serviceStarts(ledger: String?): Map<String, String> {
        val root = ledger?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        return root.keys().asSequence().mapNotNull { id -> root.getJSONObject(id).optString("시작시간").takeIf { it.isNotEmpty() }?.let { id to it } }.toMap()
    }

    fun toCsv(ledger: String, date: String): String {
        val root = JSONObject(ledger)
        val ids = root.keys().asSequence().toList().sorted()
        fun line(v: List<String>) = v.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }
        val rows = ids.mapIndexed { i, id ->
            val r = root.getJSONObject(id)
            val minutes = serviceMinutes(r.optString("시작시간"), r.optString("종료시간"))
            line(HEADER.map { col ->
                when (col) {
                    "연번" -> "${i + 1}"
                    "k_nalja" -> date
                    "수급자명" -> id // 실명은 센터에서 대조표로 붙인다
                    "제공시간" -> minutes?.let { "${it / 60}:${"%02d".format(it % 60)}" } ?: ""
                    "총제공분" -> minutes?.toString() ?: ""
                    else -> r.optString(col)
                }
            })
        }
        return "﻿" + (listOf(line(HEADER)) + rows).joinToString("\n")
    }
}
