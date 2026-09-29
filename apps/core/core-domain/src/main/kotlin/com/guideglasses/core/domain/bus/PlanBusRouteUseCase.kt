package com.guideglasses.core.domain.bus

import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.navigation.Coordinate
import com.guideglasses.core.domain.navigation.Geo
import com.guideglasses.core.domain.navigation.LocationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 查詢公車路線：目前位置 → 候選方案 → 到站時間。
 *
 * 短指令觸發（見 [com.guideglasses.core.domain.assistant.VoiceCommand]），
 * 與「前面有什麼」「這是誰」同一種形狀：使用者說出來就直接做，
 * 沒有畫面可以先選方案，因此固定用總時間最短的一個 —— 與舊原型
 * `gps_connect` 預設呈現「方案一」的精神一致，差別是這裡用時間排序
 * 而不是後端回傳順序。
 *
 * 座標一律來自 [LocationProvider]。眼鏡沒有 GPS（`docs/DEVICE_FINDINGS.md`
 * A10），目前綁定的實作是手機 companion 轉傳，但這個 UseCase 完全不知道
 * 座標從哪來 —— 這正是抽象化的目的。
 *
 * 目的地有兩種來源，[execute] 的參數決定用哪一種：
 * - 不傳（或傳 `null`）：用建構子的 [destination]（設定檔固定值），對應短指令
 *   「查公車路線」——沒有畫面選目的地，且不需要 LLM 就能動。
 * - 傳一個地名字串：對應開放式語句（「帶我去台北車站」），目的地是 LLM
 *   從語句裡抽出來的文字，交給後端的 Google Routes API 直接解析地名，
 *   這裡不需要另外做地理編碼。
 */
class PlanBusRouteUseCase(
    private val locationProvider: LocationProvider,
    private val planningGateway: BusPlanningGateway,
    private val destination: BusStopTarget,
) {

    suspend fun execute(destinationName: String? = null): Outcome {
        if (!planningGateway.isAvailable) return Outcome.Unavailable
        if (!locationProvider.isAvailable) return Outcome.LocationUnavailable

        val origin = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            locationProvider.locations().first()
        } ?: return Outcome.LocationUnavailable

        val fetchResult = if (destinationName.isNullOrBlank()) {
            planningGateway.fetchPlans(origin, Coordinate(destination.latitude, destination.longitude))
        } else {
            planningGateway.fetchPlansByName(origin, destinationName)
        }

        val plans = when (fetchResult) {
            is AppResult.Success -> fetchResult.data
            is AppResult.Failure -> return Outcome.Failed(fetchResult.error)
        }

        if (plans.isEmpty()) return Outcome.NoRouteFound

        val best = plans.minByOrNull { it.totalMinutes } ?: plans.first()
        val spoken = spokenPlan(best, origin)

        return when (val result = planningGateway.fetchEta(best)) {
            is AppResult.Success -> Outcome.Planned(best, result.data, spoken + spokenEta(result.data))
            is AppResult.Failure -> Outcome.PlannedWithoutEta(best, spoken)
        }
    }

    private fun spokenPlan(plan: BusPlan, origin: Coordinate): String {
        val distance = Geo.distanceMeters(origin, plan.boardingStopCoordinate).toInt()
        return "建議搭乘 ${plan.busNumber} 路公車，上車站是 ${plan.boardingStop}，" +
            "距離您約 $distance 公尺，步行約 ${plan.walkToBoardingMinutes} 分鐘。"
    }

    private fun spokenEta(eta: BusEta): String {
        val extra = eta.message.takeIf { it.isNotBlank() }?.let { "。$it" }.orEmpty()
        return "最近一班 ${eta.firstArrival} 到站，再下一班 ${eta.secondArrival}$extra" +
            "。到站前可以說「確認公車」用相機核對車號。"
    }

    sealed interface Outcome {
        data class Planned(val plan: BusPlan, val eta: BusEta, val spoken: String) : Outcome
        data class PlannedWithoutEta(val plan: BusPlan, val spoken: String) : Outcome
        data object NoRouteFound : Outcome
        data object LocationUnavailable : Outcome
        data object Unavailable : Outcome
        data class Failed(val error: AppError) : Outcome
    }

    private companion object {
        /** 手機 companion 回報位置本來就是輪詢，逾時代表暫時連不上，不是永久失敗。 */
        const val LOCATION_TIMEOUT_MS = 8_000L
    }
}
