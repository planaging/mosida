package ai.planaging.mosida.trip

import ai.planaging.mosida.model.Elder

/**
 * 시연용 가상 명단. 실명·실제 주소는 절대 넣지 않는다.
 * order = 하차 순서. 좌석은 비워 두고 "출발 전 좌석 배치" 화면에서 앉힌다.
 *
 * 좌표는 실제 집이 아니다. Pleos 에뮬레이터 내비는 지도 데이터가 판교·성남 일대만 있어서
 * (지도 밖 좌표는 CAUSE_SEARCH_ROUTE), 에뮬레이터 기본 위치(판교, 37.4127/127.0947) 근처에 놓았다.
 * 이름과 동네는 모두 지어낸 것이다.
 */
object SampleData {
    val elders = listOf(
        Elder("e1", "김○희", "가람동", order = 1, needsGuardian = false, lat = 37.4187, lng = 127.0947),
        Elder("e2", "서○민", "가람동", order = 2, needsGuardian = true, lat = 37.4162, lng = 127.0992),
        Elder("e3", "오○진", "가람동", order = 3, needsGuardian = true, lat = 37.4137, lng = 127.1027),
        Elder("e4", "문○영", "가람동", order = 4, needsGuardian = false, lat = 37.4117, lng = 127.1077),
        Elder("e5", "백○수", "나래동", order = 5, needsGuardian = false, lat = 37.4287, lng = 127.1137),
        Elder("e6", "신○아", "나래동", order = 6, needsGuardian = true, lat = 37.4317, lng = 127.1177),
        Elder("e7", "조○현", "다솜동", order = 7, needsGuardian = false, lat = 37.4167, lng = 127.1197),
    )
}
