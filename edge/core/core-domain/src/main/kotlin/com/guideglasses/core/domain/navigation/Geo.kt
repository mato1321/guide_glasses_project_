package com.guideglasses.core.domain.navigation

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 球面幾何。純數學，可完整以單元測試驗證。
 *
 * 用 Haversine 而不是簡單的平面近似：台灣的緯度下，1 度經度約 101 公里、
 * 1 度緯度約 111 公里，直接當平面算在市區尺度誤差就會超過導航閾值。
 */
object Geo {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** 兩點之間的地表距離（公尺）。 */
    fun distanceMeters(from: Coordinate, to: Coordinate): Double {
        val dLat = Math.toRadians(to.latitude - from.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)

        val a = sin(dLat / 2) * sin(dLat / 2) +
            sin(dLon / 2) * sin(dLon / 2) * cos(lat1) * cos(lat2)
        return 2 * EARTH_RADIUS_METERS * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * 從 from 指向 to 的方位角（0–360，0 = 正北，順時針）。
     *
     * 這是**絕對方位**。要轉成「往左轉還是往右轉」還需要使用者目前的朝向，
     * 那來自 IMU 的電子羅盤 —— 見 [relativeTurn]。
     */
    fun bearingDegrees(from: Coordinate, to: Coordinate): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * 目標方位相對於使用者朝向的轉向角。
     *
     * @return -180..180。負數往左、正數往右。
     *
     * 取**最短轉向**：使用者朝北（0 度）、目標在西北（350 度）時，
     * 答案應該是「左轉 10 度」而不是「右轉 350 度」。
     * 跨零度是這段最容易寫錯的地方。
     */
    fun relativeTurn(headingDegrees: Double, targetBearingDegrees: Double): Double {
        var diff = (targetBearingDegrees - headingDegrees + 540.0) % 360.0 - 180.0
        if (diff == -180.0) diff = 180.0
        return diff
    }

    /** 把轉向角翻成口語。刻意不報角度 —— 「往右前方」比「右轉 37 度」好用。 */
    fun spokenTurn(relativeTurnDegrees: Double): String = when {
        abs(relativeTurnDegrees) <= SLIGHT_DEGREES -> "直走"
        relativeTurnDegrees > 0 && relativeTurnDegrees <= TURN_DEGREES -> "往右前方"
        relativeTurnDegrees < 0 && relativeTurnDegrees >= -TURN_DEGREES -> "往左前方"
        relativeTurnDegrees > TURN_DEGREES && relativeTurnDegrees <= SHARP_DEGREES -> "右轉"
        relativeTurnDegrees < -TURN_DEGREES && relativeTurnDegrees >= -SHARP_DEGREES -> "左轉"
        else -> "請回頭"
    }

    private const val SLIGHT_DEGREES = 20.0
    private const val TURN_DEGREES = 60.0
    private const val SHARP_DEGREES = 135.0
}
