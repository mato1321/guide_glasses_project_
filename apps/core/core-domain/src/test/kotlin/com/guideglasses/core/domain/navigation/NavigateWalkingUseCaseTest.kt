package com.guideglasses.core.domain.navigation

import com.google.common.truth.Truth.assertThat
import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.announce.AnnouncementPriority
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 這是在家（沒有真的走路、沒有真的 GPS）就能驗證導航邏輯對不對的方式——
 * 用假的位置序列模擬「使用者依序走過幾個點」，不需要裝置、不需要出門。
 * 真正的語音體驗（TTS 唸起來順不順）還是要用 Android 的模擬定位在手機上測，
 * 這份測試只保證「邏輯」不會錯，兩者互補。
 */
class NavigateWalkingUseCaseTest {

    /**
     * 依序吐出固定的座標序列，模擬使用者持續往前走。
     *
     * 用共用的可變佇列而不是每次呼叫 `locations()` 都重新從頭開始——
     * [NavigateWalkingUseCase] 會對同一個 provider 呼叫好幾次 `locations()`
     * （拿起點一次、每一步等待一次），必須接續模擬「繼續往前走」，
     * 不能每次都從同一個點重新出發，否則測不出真正的行為。
     */
    private class FakeLocationProvider(
        override val isAvailable: Boolean = true,
        points: List<Coordinate>,
    ) : LocationProvider {
        override val accuracyMeters: Float? = null
        private val remaining = points.toMutableList()

        override fun locations(): Flow<Coordinate> = flow {
            while (remaining.isNotEmpty()) {
                emit(remaining.removeAt(0))
            }
        }
    }

    private class FakeWalkingRouteGateway(
        override val isAvailable: Boolean = true,
        private val result: AppResult<List<NavigationStep>> = AppResult.Success(emptyList()),
    ) : WalkingRouteGateway {
        override suspend fun fetchSteps(origin: Coordinate, destination: Coordinate) = result
        override suspend fun fetchStepsByName(origin: Coordinate, destinationName: String) = result
    }

    private fun coordinate(lat: Double, lng: Double) = Coordinate(lat, lng)

    // 跟目的地差 1 度（約 100 公里），保證不會被誤判成「已經到了」。
    private fun farFrom(target: Coordinate) = coordinate(target.latitude + 1.0, target.longitude)

    @Test
    fun `依序走到每個轉彎點會依序播報，最後說已抵達`() = runTest {
        val origin = coordinate(25.0, 121.0)
        val pointB = coordinate(25.001, 121.0)
        val pointC = coordinate(25.002, 121.0)

        val steps = listOf(
            NavigationStep("往西走天橋", origin, 0),
            NavigationStep("向左轉進入基隆路一段", pointB, 100),
            NavigationStep("目的地在您的左邊", pointC, 50),
        )

        val locationProvider = FakeLocationProvider(
            points = listOf(origin, farFrom(pointB), pointB, farFrom(pointC), pointC),
        )
        val gateway = FakeWalkingRouteGateway(result = AppResult.Success(steps))

        val announcements = NavigateWalkingUseCase(locationProvider, gateway)
            .navigate(destination = pointC)
            .toList()

        assertThat(announcements.map { it.text }).containsExactly(
            "往西走天橋",
            "向左轉進入基隆路一段",
            "目的地在您的左邊",
            "已經抵達目的地附近",
        ).inOrder()
    }

    @Test
    fun `後端不可用時只播一句不可用`() = runTest {
        val locationProvider = FakeLocationProvider(points = listOf(coordinate(25.0, 121.0)))
        val gateway = FakeWalkingRouteGateway(isAvailable = false)

        val announcements = NavigateWalkingUseCase(locationProvider, gateway)
            .navigate(destination = coordinate(25.1, 121.1))
            .toList()

        assertThat(announcements).hasSize(1)
        assertThat(announcements.first().text).contains("不可用")
    }

    @Test
    fun `拿不到定位時播報對應訊息`() = runTest {
        val locationProvider = FakeLocationProvider(isAvailable = false, points = emptyList())
        val gateway = FakeWalkingRouteGateway(result = AppResult.Success(emptyList()))

        val announcements = NavigateWalkingUseCase(locationProvider, gateway)
            .navigate(destination = coordinate(25.1, 121.1))
            .toList()

        assertThat(announcements).hasSize(1)
        assertThat(announcements.first().text).contains("定位")
    }

    @Test
    fun `查不到路線時播報查不到`() = runTest {
        val locationProvider = FakeLocationProvider(points = listOf(coordinate(25.0, 121.0)))
        val gateway = FakeWalkingRouteGateway(
            result = AppResult.Failure(AppError.NoNetwork("timeout")),
        )

        val announcements = NavigateWalkingUseCase(locationProvider, gateway)
            .navigate(destination = coordinate(25.1, 121.1))
            .toList()

        assertThat(announcements).hasSize(1)
        assertThat(announcements.first().text).contains("查不到")
    }

    @Test
    fun `空路線也算查不到`() = runTest {
        val locationProvider = FakeLocationProvider(points = listOf(coordinate(25.0, 121.0)))
        val gateway = FakeWalkingRouteGateway(result = AppResult.Success(emptyList()))

        val announcements = NavigateWalkingUseCase(locationProvider, gateway)
            .navigate(destination = coordinate(25.1, 121.1))
            .toList()

        assertThat(announcements).hasSize(1)
        assertThat(announcements.first().text).contains("查不到")
    }

    @Test
    fun `所有轉彎播報都是 NAVIGATION 優先級，會被障礙物警告打斷`() = runTest {
        val origin = coordinate(25.0, 121.0)
        val steps = listOf(NavigationStep("往西走天橋", origin, 0))

        val locationProvider = FakeLocationProvider(points = listOf(origin))
        val gateway = FakeWalkingRouteGateway(result = AppResult.Success(steps))

        val announcements = NavigateWalkingUseCase(locationProvider, gateway)
            .navigate(destination = origin)
            .toList()

        assertThat(announcements).isNotEmpty()
        announcements.forEach { announcement ->
            assertThat(announcement.priority).isEqualTo(AnnouncementPriority.NAVIGATION)
        }
    }
}
