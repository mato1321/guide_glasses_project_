package com.guideglasses.core.domain.navigation

import com.guideglasses.core.domain.AppResult

/**
 * 步行逐步轉彎指示的查詢抽象。
 *
 * 跟 [com.guideglasses.core.domain.bus.BusPlanningGateway] 分開——那個是
 * 「查一次公車方案」，這個是「查一次、拿到一串座標＋要講的話」給
 * [NavigateWalkingUseCase] 持續比對播報用。
 */
interface WalkingRouteGateway {

    val isAvailable: Boolean

    suspend fun fetchSteps(origin: Coordinate, destination: Coordinate): AppResult<List<NavigationStep>>

    /** 用地名查（開放式語句「帶我去台北車站」，LLM 抽出來的文字）。 */
    suspend fun fetchStepsByName(origin: Coordinate, destinationName: String): AppResult<List<NavigationStep>>
}
