package com.guideglasses.core.domain.bus

import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.navigation.Coordinate

/**
 * 公車路線／到站查詢的抽象。
 *
 * 現在的實作打團隊自己的 Flask 後端（`docs/TASKS.md` D4 標記的 MVP），
 * 之後要換成 TDX 即時資料時只需要換綁定，[PlanBusRouteUseCase] 不必動。
 */
interface BusPlanningGateway {

    val isAvailable: Boolean

    suspend fun fetchPlans(origin: Coordinate, destination: Coordinate): AppResult<List<BusPlan>>

    /**
     * 用地名而不是座標查路線。
     *
     * 給開放式語句用（「帶我去台北車站」）——LLM 從語句抽出來的是文字，
     * 不是座標。後端直接把地名交給 Google Routes API 解析，這裡不需要
     * 另外做地理編碼。
     */
    suspend fun fetchPlansByName(origin: Coordinate, destinationName: String): AppResult<List<BusPlan>>

    suspend fun fetchEta(plan: BusPlan): AppResult<BusEta>
}
