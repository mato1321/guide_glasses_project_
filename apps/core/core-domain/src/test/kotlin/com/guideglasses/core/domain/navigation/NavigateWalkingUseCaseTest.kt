package com.guideglasses.core.domain.navigation

import com.google.common.truth.Truth.assertThat
import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.announce.AnnouncementPriority
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
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
            // 眼鏡沒有羅盤，「往西」改成沿路走，見 spokenInstruction。
            "沿著天橋走",
            "向左轉進入基隆路一段",
            "目的地在您的左邊",
            "已經抵達目的地附近",
        ).inOrder()
    }

    // ===== 只有一步的路線：走到終點才說已抵達 =====

    // 台北 101 附近實機遇到的形狀：只有一步「往西走信義路五段」，上車站在 90 公尺外。
    private val start = coordinate(25.0330, 121.5654)
    private val stop90mWest = coordinate(25.0330, 121.5645)
    private val halfway = coordinate(25.0330, 121.56495)
    private val singleStep = listOf(NavigationStep("往西走信義路五段", start, 90, endLocation = stop90mWest))

    @Test
    fun `只有一步的路線，要真的走到站牌才說已抵達`() = runTest {
        val locationProvider = FakeLocationProvider(points = listOf(start, halfway, stop90mWest))

        val announcements = NavigateWalkingUseCase(locationProvider, FakeWalkingRouteGateway(result = AppResult.Success(singleStep)))
            .navigate(destination = stop90mWest)
            .toList()

        assertThat(announcements.map { it.text }).containsExactly(
            "沿著信義路五段走，約 90 公尺",
            "已經抵達目的地附近",
        ).inOrder()
    }

    @Test
    fun `還沒走到站牌、定位就中斷時，說導航暫停而不是已抵達`() = runTest {
        // 修正前：第一句講完立刻說「已經抵達目的地附近」，人其實還在 90 公尺外。
        val locationProvider = FakeLocationProvider(points = listOf(start, halfway))

        val announcements = NavigateWalkingUseCase(locationProvider, FakeWalkingRouteGateway(result = AppResult.Success(singleStep)))
            .navigate(destination = stop90mWest)
            .toList()

        assertThat(announcements.map { it.text }).containsExactly(
            "沿著信義路五段走，約 90 公尺",
            "定位中斷，導航暫停",
        ).inOrder()
    }

    @Test
    fun `以地名導航時用最後一步的終點判斷抵達`() = runTest {
        val locationProvider = FakeLocationProvider(points = listOf(start, halfway))

        val announcements = NavigateWalkingUseCase(locationProvider, FakeWalkingRouteGateway(result = AppResult.Success(singleStep)))
            .navigate(destinationName = "捷運台北101站")
            .toList()

        assertThat(announcements.map { it.text }).doesNotContain("已經抵達目的地附近")
    }

    // ===== 方向核對 =====

    @Test
    fun `往反方向走出一段距離，會提醒轉身`() = runTest {
        val wrongWay30mEast = coordinate(25.0330, 121.5657)
        val locationProvider = FakeLocationProvider(points = listOf(start, wrongWay30mEast, start, halfway, stop90mWest))

        val announcements = NavigateWalkingUseCase(locationProvider, FakeWalkingRouteGateway(result = AppResult.Success(singleStep)))
            .navigate(destination = stop90mWest)
            .toList()

        assertThat(announcements.map { it.text }).containsExactly(
            "沿著信義路五段走，約 90 公尺",
            "方向好像相反了，請轉身往回走",
            "已經抵達目的地附近",
        ).inOrder()
    }

    @Test
    fun `方向正確時不會多講話`() = runTest {
        val locationProvider = FakeLocationProvider(points = listOf(start, halfway, stop90mWest))

        val announcements = NavigateWalkingUseCase(locationProvider, FakeWalkingRouteGateway(result = AppResult.Success(singleStep)))
            .navigate(destination = stop90mWest)
            .toList()

        assertThat(announcements.map { it.text }).doesNotContain("方向好像相反了，請轉身往回走")
    }

    // ===== 太久收不到新座標 =====

    /**
     * 給出起點之後就再也沒有新座標 —— 手機 App 被關、後端停了，或座標都太舊被丟掉。
     * 真的 provider 是永不結束的輪詢，所以這裡也是停住而不是結束。
     */
    private class StalledLocationProvider(private val origin: Coordinate) : LocationProvider {
        override val isAvailable = true
        override val accuracyMeters: Float? = null
        private var served = false

        override fun locations(): Flow<Coordinate> = flow {
            if (!served) {
                served = true
                emit(origin)
            }
            awaitCancellation()
        }
    }

    /** 每隔 [intervalMillis] 給一筆座標，模擬持續在走、定位也正常。 */
    private class SteadyLocationProvider(points: List<Coordinate>, private val intervalMillis: Long) : LocationProvider {
        override val isAvailable = true
        override val accuracyMeters: Float? = null
        private val remaining = points.toMutableList()

        override fun locations(): Flow<Coordinate> = flow {
            while (remaining.isNotEmpty()) {
                emit(remaining.removeAt(0))
                if (remaining.isNotEmpty()) delay(intervalMillis)
            }
        }
    }

    @Test
    fun `15 秒收不到新座標就提醒，而不是安靜地等`() = runTest {
        val announcements = NavigateWalkingUseCase(
            StalledLocationProvider(start),
            FakeWalkingRouteGateway(result = AppResult.Success(singleStep)),
        )
            .navigate(destination = stop90mWest)
            .take(2)
            .toList()

        assertThat(announcements.map { it.text }).containsExactly(
            "沿著信義路五段走，約 90 公尺",
            "暫時收不到手機定位，請確認手機的導盲定位還開著",
        ).inOrder()
        // 一直收不到時最多一分鐘講一次，交給 AnnouncementManager 的去重。
        assertThat(announcements[1].dedupeWindowMillis).isEqualTo(NavigateWalkingUseCase.LOCATION_STALE_REPEAT_MILLIS)
    }

    @Test
    fun `一直在走、只是還沒走到，超過 15 秒也不會被說成收不到定位`() = runTest {
        // 每 5 秒一筆，總共走 20 秒才到站 —— 等待時間超過 15 秒，但每一筆都在 15 秒內。
        val walking = listOf(
            start,
            coordinate(25.0330, 121.56518),
            coordinate(25.0330, 121.56495),
            coordinate(25.0330, 121.56472),
            stop90mWest,
        )

        val announcements = NavigateWalkingUseCase(
            SteadyLocationProvider(walking, intervalMillis = 5_000L),
            FakeWalkingRouteGateway(result = AppResult.Success(singleStep)),
        )
            .navigate(destination = stop90mWest)
            .toList()

        assertThat(announcements.map { it.text }).containsExactly(
            "沿著信義路五段走，約 90 公尺",
            "已經抵達目的地附近",
        ).inOrder()
    }

    // ===== 方位詞改寫 =====

    @Test
    fun `需要東西南北的指示改成沿路走，其他指示原樣保留`() {
        fun spoken(instruction: String, meters: Int = 0) =
            NavigateWalkingUseCase.spokenInstruction(NavigationStep(instruction, start, meters))

        assertThat(spoken("往西走信義路五段", 90)).isEqualTo("沿著信義路五段走，約 90 公尺")
        // 眼鏡實測 Google 回傳的原文，第二行要原樣保留。
        assertThat(spoken("往西走信義路五段\n目的地在右邊", 90)).isEqualTo("沿著信義路五段走，約 90 公尺。目的地在右邊")
        // 眼鏡實測（小南門附近）：曾被唸成「沿著朝延平南路前進走」。
        assertThat(spoken("往北朝延平南路前進", 39)).isEqualTo("往延平南路的方向走，約 39 公尺")
        assertThat(spoken("往西走向信義路", 60)).isEqualTo("往信義路的方向走，約 60 公尺")
        assertThat(spoken("往東沿著忠孝東路走", 50)).isEqualTo("沿著忠孝東路走，約 50 公尺")
        assertThat(spoken("往東南方走", 30)).isEqualTo("直走，約 30 公尺")
        assertThat(spoken("向北走上中山北路二段", 120)).isEqualTo("沿著中山北路二段走，約 120 公尺")
        assertThat(spoken("往北走，然後右轉進入中山北路")).isEqualTo("直走，然後右轉進入中山北路")
        assertThat(spoken("向左轉進入基隆路一段", 100)).isEqualTo("向左轉進入基隆路一段")
        assertThat(spoken("目的地在您的左邊")).isEqualTo("目的地在您的左邊")
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
