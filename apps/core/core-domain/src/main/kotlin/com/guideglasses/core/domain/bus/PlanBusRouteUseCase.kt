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
            is AppResult.Success -> Outcome.Planned(
                best,
                result.data,
                spoken + spokenEta(result.data, best.walkToBoardingSeconds) + HINT_CONFIRM_BUS,
            )
            is AppResult.Failure -> Outcome.PlannedWithoutEta(best, spoken + MESSAGE_NO_ETA + HINT_CONFIRM_BUS)
        }
    }

    private fun spokenPlan(plan: BusPlan, origin: Coordinate): String {
        val distance = Geo.distanceMeters(origin, plan.boardingStopCoordinate).toInt()
        return "建議搭乘 ${spokenBusName(plan.busNumber)}，上車站是 ${plan.boardingStop}，" +
            "距離您約 $distance 公尺，步行約 ${plan.walkToBoardingMinutes} 分鐘。"
    }

    /**
     * 到站時間只用結構化欄位組句，**不唸 [BusEta.message]**。
     *
     * `message` 是後端給人看的說明，失敗時是除錯資訊 —— 實機唸出過
     * 「找不到資料：此路線在 1000 公尺內找不到對應站牌，bus=88區間車」；
     * 成功時又跟前面的「最近一班⋯」重複，還可能是字面上的 `success`。
     * 後端回「未知」時也不能照唸成「最近一班 未知 到站」。
     */
    private fun spokenEta(eta: BusEta, walkSeconds: Int): String {
        val first = arrivalSeconds(eta.firstArrival) ?: return MESSAGE_NO_ETA
        val second = arrivalSeconds(eta.secondArrival)

        val sentence = StringBuilder("最近一班").append(spokenArrival(eta.firstArrival, first))
        if (second != null) sentence.append("，下一班").append(spokenArrival(eta.secondArrival, second))
        sentence.append("。")
        if (first < walkSeconds) {
            sentence.append(if (second != null) "走過去可能趕不上最近一班，建議搭下一班。" else "走過去可能趕不上。")
        }
        return sentence.toString()
    }

    private fun spokenArrival(text: String, seconds: Int): String =
        if (seconds == 0) "即將進站" else "約 ${text.trim()}後到站"

    sealed interface Outcome {
        data class Planned(val plan: BusPlan, val eta: BusEta, val spoken: String) : Outcome
        data class PlannedWithoutEta(val plan: BusPlan, val spoken: String) : Outcome
        data object NoRouteFound : Outcome
        data object LocationUnavailable : Outcome
        data object Unavailable : Outcome
        data class Failed(val error: AppError) : Outcome
    }

    internal companion object {
        /** 手機 companion 回報位置本來就是輪詢，逾時代表暫時連不上，不是永久失敗。 */
        const val LOCATION_TIMEOUT_MS = 8_000L

        const val MESSAGE_NO_ETA = "目前查不到到站時間。"
        const val HINT_CONFIRM_BUS = "上車前可以說「確認公車」核對車號。"

        /**
         * 路線名稱怎麼唸。
         *
         * 「307」「紅25」要加「路公車」；「88區間車」「敦化幹線」本身就是完整名稱，
         * 再加會變成實機唸出的「88區間車 路公車」。
         */
        fun spokenBusName(busNumber: String): String {
            val name = busNumber.trim()
            return if (name.lastOrNull()?.isDigit() == true) "$name 路公車" else name
        }

        /**
         * 後端的到站時間文字 → 秒數。
         *
         * 後端格式（`bus_logic.pretty_eta_seconds`）：「3分20秒」「45秒」「進站/到站」「未知」。
         * 認不得（包括「未知」與空字串）回 null，讓呼叫端改說「查不到」，而不是照唸。
         */
        fun arrivalSeconds(text: String): Int? {
            val t = text.trim()
            if (t.isEmpty() || t == "未知") return null
            if ("進站" in t || "到站" in t) return 0
            val minutes = Regex("""(\d+)\s*分""").find(t)?.groupValues?.get(1)?.toInt()
            val seconds = Regex("""(\d+)\s*秒""").find(t)?.groupValues?.get(1)?.toInt()
            if (minutes == null && seconds == null) return null
            return (minutes ?: 0) * 60 + (seconds ?: 0)
        }
    }
}
