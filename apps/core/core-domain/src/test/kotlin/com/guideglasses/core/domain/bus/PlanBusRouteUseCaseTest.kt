package com.guideglasses.core.domain.bus

import com.google.common.truth.Truth.assertThat
import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.navigation.Coordinate
import com.guideglasses.core.domain.navigation.LocationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 「查公車路線」最後唸出來的那一句。
 *
 * 這裡守的是 2026-09-30 眼鏡實測唸錯的內容：後端的除錯訊息被唸出來、
 * 「最近一班 未知 到站」、「88區間車 路公車」。
 */
class PlanBusRouteUseCaseTest {

    private val here = Coordinate(25.0330, 121.5654)
    private val destination = BusStopTarget(25.049066, 121.510058, "測試地點")

    private fun plan(busNumber: String = "88區間車", walkSeconds: Int = 69) = BusPlan(
        busNumber = busNumber,
        boardingStop = "捷運台北101/世貿站(信義)",
        alightingStop = "終點站",
        boardingStopCoordinate = Coordinate(25.0330, 121.5645),
        walkToBoardingMinutes = 1,
        walkToBoardingSeconds = walkSeconds,
        totalMinutes = 25,
    )

    private fun eta(first: String, second: String, message: String) = BusEta(
        firstArrival = first,
        secondArrival = second,
        message = message,
        stationId = "",
        stopId = "",
        direction = 0,
    )

    private class FakeLocation(private val at: Coordinate) : LocationProvider {
        override val isAvailable = true
        override val accuracyMeters: Float? = null
        override fun locations(): Flow<Coordinate> = flowOf(at)
    }

    private class FakeGateway(
        private val plans: List<BusPlan>,
        private val eta: AppResult<BusEta>,
    ) : BusPlanningGateway {
        override val isAvailable = true
        override suspend fun fetchPlans(origin: Coordinate, destination: Coordinate) = AppResult.Success(plans)
        override suspend fun fetchPlansByName(origin: Coordinate, destinationName: String) = AppResult.Success(plans)
        override suspend fun fetchEta(plan: BusPlan) = eta
    }

    private suspend fun spoken(plan: BusPlan, eta: AppResult<BusEta>): String =
        when (val outcome = PlanBusRouteUseCase(FakeLocation(here), FakeGateway(listOf(plan), eta), destination).execute()) {
            is PlanBusRouteUseCase.Outcome.Planned -> outcome.spoken
            is PlanBusRouteUseCase.Outcome.PlannedWithoutEta -> outcome.spoken
            else -> error("預期有方案，實際是 $outcome")
        }

    @Test
    fun `後端的除錯訊息不會被唸出來，查不到到站時間就直說`() = runTest {
        val text = spoken(
            plan(),
            AppResult.Success(eta("未知", "未知", "找不到資料：此路線在 1000 公尺內找不到對應站牌，bus=88區間車")),
        )

        assertThat(text).contains("目前查不到到站時間。")
        assertThat(text).doesNotContain("找不到資料")
        assertThat(text).doesNotContain("bus=")
        assertThat(text).doesNotContain("未知")
    }

    @Test
    fun `成功時只唸到站時間，不重複後端的說明也不會唸出 success`() = runTest {
        val backendMessage = "最近一班 3分20秒 到站，您步行需 1分9秒，可趕上。；下一班 12分5秒 到站。；建議搭乘最近一班。"
        for (message in listOf(backendMessage, "success")) {
            val text = spoken(plan(), AppResult.Success(eta("3分20秒", "12分5秒", message)))

            assertThat(text).contains("最近一班約 3分20秒後到站，下一班約 12分5秒後到站。")
            assertThat(text).doesNotContain("success")
            assertThat(text).doesNotContain("您步行需")
        }
    }

    @Test
    fun `進站中說即將進站，走過去趕不上時建議搭下一班`() = runTest {
        val text = spoken(plan(walkSeconds = 69), AppResult.Success(eta("進站/到站", "8分0秒", "success")))

        assertThat(text).contains("最近一班即將進站，下一班約 8分0秒後到站。走過去可能趕不上最近一班，建議搭下一班。")
    }

    @Test
    fun `只有一班且趕不上時不提下一班`() = runTest {
        val text = spoken(plan(walkSeconds = 300), AppResult.Success(eta("2分0秒", "未知", "success")))

        assertThat(text).contains("最近一班約 2分0秒後到站。走過去可能趕不上。")
        assertThat(text).doesNotContain("下一班")
    }

    @Test
    fun `到站時間查詢失敗時仍然給方案`() = runTest {
        val text = spoken(plan(), AppResult.Failure(AppError.NoNetwork("timeout")))

        assertThat(text).startsWith("建議搭乘 88區間車，上車站是 捷運台北101/世貿站(信義)")
        assertThat(text).contains("目前查不到到站時間。")
    }

    @Test
    fun `路線名稱是數字結尾才加路公車`() {
        assertThat(PlanBusRouteUseCase.spokenBusName("307")).isEqualTo("307 路公車")
        assertThat(PlanBusRouteUseCase.spokenBusName("紅25")).isEqualTo("紅25 路公車")
        assertThat(PlanBusRouteUseCase.spokenBusName("88區間車")).isEqualTo("88區間車")
        assertThat(PlanBusRouteUseCase.spokenBusName("敦化幹線")).isEqualTo("敦化幹線")
    }

    @Test
    fun `後端的到站時間文字轉成秒數，認不得的回 null`() {
        assertThat(PlanBusRouteUseCase.arrivalSeconds("3分20秒")).isEqualTo(200)
        assertThat(PlanBusRouteUseCase.arrivalSeconds("45秒")).isEqualTo(45)
        assertThat(PlanBusRouteUseCase.arrivalSeconds("12分")).isEqualTo(720)
        assertThat(PlanBusRouteUseCase.arrivalSeconds("進站/到站")).isEqualTo(0)
        assertThat(PlanBusRouteUseCase.arrivalSeconds("未知")).isNull()
        assertThat(PlanBusRouteUseCase.arrivalSeconds("")).isNull()
        assertThat(PlanBusRouteUseCase.arrivalSeconds("末班車已過")).isNull()
    }
}
