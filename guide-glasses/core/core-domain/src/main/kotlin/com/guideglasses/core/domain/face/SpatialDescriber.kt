package com.guideglasses.core.domain.face

import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * 把「畫面中的位置」翻成「相對於使用者的方位」。
 *
 * 舊專案只播報「你面前的人是王老師」。但使用者聽到之後還是不知道要
 * 往哪邊點頭、往哪邊伸手。「右前方是王老師」才是可以行動的資訊。
 */
object BearingResolver {

    fun resolve(centerX: Float): Bearing = when {
        centerX < LEFT_BOUNDARY -> Bearing.LEFT
        centerX > RIGHT_BOUNDARY -> Bearing.RIGHT
        else -> Bearing.AHEAD
    }

    enum class Bearing(val spoken: String) {
        LEFT("左前方"),
        AHEAD("正前方"),
        RIGHT("右前方"),
    }

    /**
     * 三等分。刻意不切得更細（例如「左前方偏左」）——
     * 語音資訊愈簡短愈好用，過度精確反而增加聽的負擔。
     */
    private const val LEFT_BOUNDARY = 1f / 3f
    private const val RIGHT_BOUNDARY = 2f / 3f
}

/**
 * 用已知的臉部實際寬度反推距離。
 *
 * 眼鏡是單目相機、沒有深度感測器。`docs/03` 比較過幾種方案之後選了這個 ——
 * 純幾何、零額外算力、誤差對導盲場景足夠。
 *
 * 原理是針孔相機模型：
 * ```
 * 距離 = (實際寬度 × 焦距像素) / 影像中的像素寬度
 * 焦距像素 = (影像寬度 / 2) / tan(水平視角 / 2)
 * ```
 * 兩式合併後影像寬度會約分掉，所以只需要「臉佔畫面寬度的比例」。
 */
class FaceDistanceEstimator(
    /** 相機水平視角（度）。**這個值需要實機量測校正**，預設是一般手機廣角鏡的概略值。 */
    private val horizontalFovDegrees: Float = DEFAULT_HORIZONTAL_FOV_DEGREES,
    /** 成人臉部平均寬度（公尺）。 */
    private val averageFaceWidthMeters: Float = AVERAGE_FACE_WIDTH_METERS,
) {
    init {
        require(horizontalFovDegrees > 0f && horizontalFovDegrees < 180f) {
            "水平視角必須介於 0 到 180 度"
        }
        require(averageFaceWidthMeters > 0f) { "臉部寬度必須為正" }
    }

    private val halfFovTangent: Float =
        tan(Math.toRadians(horizontalFovDegrees.toDouble() / 2.0)).toFloat()

    /**
     * @param faceWidthRatio 臉部寬度佔畫面寬度的比例（0..1）。
     * @return 估計距離（公尺），無法估計時為 null。
     */
    fun estimateMeters(faceWidthRatio: Float): Float? {
        if (faceWidthRatio <= 0f || faceWidthRatio > 1f) return null
        if (halfFovTangent <= 0f) return null

        val distance = averageFaceWidthMeters / (2f * faceWidthRatio * halfFovTangent)
        return distance.takeIf { it.isFinite() && it > 0f }
    }

    /**
     * 距離的口語描述。
     *
     * 刻意不報小數 —— 「三點二公尺」在聽覺上比「三公尺」難處理，
     * 而且這個估計本身就有 20-30% 誤差，報小數是假精確。
     */
    fun describe(faceWidthRatio: Float): String? {
        val meters = estimateMeters(faceWidthRatio) ?: return null
        return when {
            meters < VERY_CLOSE_METERS -> "就在眼前"
            meters < 1.5f -> "大約一公尺"
            else -> "大約 ${meters.roundToInt()} 公尺"
        }
    }

    companion object {
        /**
         * 預設水平視角。
         *
         * **無法確認 Rokid Glasses 相機的實際視角** —— 官方規格未載明。
         * 這是一般手機廣角鏡的概略值，實機校正方式見 DOCUMENTATION.md。
         */
        const val DEFAULT_HORIZONTAL_FOV_DEGREES = 66f

        /** 成人臉部（顴骨間）平均寬度約 14-15 公分。 */
        const val AVERAGE_FACE_WIDTH_METERS = 0.145f

        /** 小於這個距離就不報數字了，直接說「就在眼前」。 */
        const val VERY_CLOSE_METERS = 0.7f
    }
}
