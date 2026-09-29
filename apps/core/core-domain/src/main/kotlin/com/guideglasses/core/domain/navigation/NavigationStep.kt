package com.guideglasses.core.domain.navigation

/**
 * 一步步行轉彎指示。
 *
 * [instruction] 直接是 Google Routes API 算好的人話（「在忠孝東路左轉」），
 * 不是我們自己拿座標算方位角轉出來的——真實街道有單行道、天橋、路口角度，
 * 純幾何算不出這些，交給 Google 比較準。我們只決定「什麼時候該講」。
 */
data class NavigationStep(
    val instruction: String,
    val location: Coordinate,
    val distanceMeters: Int,
)
