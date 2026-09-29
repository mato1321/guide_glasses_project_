package com.guideglasses.core.domain.obstacle

import com.guideglasses.core.domain.announce.AnnouncementPriority
import com.guideglasses.core.domain.face.BearingResolver
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * 用已知寬度反推距離。與人臉的估距同一套針孔相機模型。
 *
 * ```
 * 距離 = 實際寬度 / (2 × 寬度佔畫面比例 × tan(水平視角 / 2))
 * ```
 *
 * 誤差對導盲場景足夠 —— 使用者需要的是「兩公尺」還是「十公尺」，
 * 不是精確到公分。
 */
class ObstacleDistanceEstimator(
    private val horizontalFovDegrees: Float = DEFAULT_HORIZONTAL_FOV_DEGREES,
) {
    private val halfFovTangent: Float =
        tan(Math.toRadians(horizontalFovDegrees.toDouble() / 2.0)).toFloat()

    /** @return 估計距離（公尺）；類別沒有已知寬度或比例不合法時為 null。 */
    fun estimateMeters(detection: Detection): Float? {
        val realWidth = detection.type.realWidthMeters ?: return null
        if (detection.width <= 0f || detection.width > 1f || halfFovTangent <= 0f) return null
        val distance = realWidth / (2f * detection.width * halfFovTangent)
        return distance.takeIf { it.isFinite() && it > 0f }
    }

    companion object {
        /**
         * **與人臉估距共用同一個待校正的值。**
         * Rokid Glasses 的實際水平視角官方未載明，需實機量測
         * （`docs/TASKS.md` A14）。校正時兩邊要一起改。
         */
        const val DEFAULT_HORIZONTAL_FOV_DEGREES = 66f
    }
}

/**
 * 類別 × 距離 → 播報優先級。
 *
 * 這是障礙物功能最重要的一段邏輯：**決定什麼時候可以打斷使用者正在聽的東西**。
 * 太敏感會變成疲勞轟炸（使用者最後會關掉功能），太遲鈍則失去意義。
 */
class DangerClassifier(
    private val criticalMeters: Float = CRITICAL_METERS,
    private val warningMeters: Float = WARNING_METERS,
) {

    /**
     * @param userAsked 使用者主動問「前面有什麼」。主動查詢的回應永遠是
     *   [AnnouncementPriority.USER_RESPONSE]，因為他正在等答案。
     */
    fun priorityFor(
        detection: Detection,
        distanceMeters: Float?,
        userAsked: Boolean,
    ): AnnouncementPriority? {
        if (userAsked) return AnnouncementPriority.USER_RESPONSE

        // 導引類（斑馬線、盲磚）永遠不該打斷 —— 它們是資訊不是警告。
        if (!detection.type.isHazard) return AnnouncementPriority.AMBIENT

        val distance = distanceMeters ?: return null

        return when {
            distance <= criticalMeters -> AnnouncementPriority.CRITICAL
            distance <= warningMeters -> AnnouncementPriority.NAVIGATION
            // 更遠的東西在使用者沒問的時候不主動講，否則走在路上會一直有聲音。
            else -> null
        }
    }

    companion object {
        /**
         * 立即危險距離。
         *
         * 以步行 1.4 m/s 計算，2 公尺約是 1.4 秒的反應時間 ——
         * 剛好夠停下腳步。再近就來不及了。
         */
        const val CRITICAL_METERS = 2f

        /** 提醒距離。給使用者調整路線的餘裕，但還不到緊急。 */
        const val WARNING_METERS = 5f
    }
}

/**
 * 把偵測結果組成一句人話。
 *
 * 原則是**短**。「右前方兩公尺有車」比
 * 「偵測到一個信心度 0.87 的車輛物件位於畫面右側」有用得多。
 */
class ObstacleAnnouncementComposer(
    private val distanceEstimator: ObstacleDistanceEstimator = ObstacleDistanceEstimator(),
) {

    fun compose(detection: Detection): String {
        val bearing = BearingResolver.resolve(detection.centerX).spoken
        val distance = distanceEstimator.estimateMeters(detection)

        return buildString {
            append(bearing)
            if (distance != null) {
                append(if (distance < 1f) "，就在眼前" else "，${distance.roundToInt()} 公尺")
            }
            append("，${detection.type.spoken}")
        }
    }

    /**
     * 多個障礙物時的整體描述（回應「前面有什麼」）。
     *
     * 最多唸三個 —— 再多使用者記不住，而且唸完可能已經走過去了。
     * 依「危險優先、近的優先」排序。
     */
    fun composeSummary(detections: List<Detection>, limit: Int = MAX_SPOKEN): String {
        if (detections.isEmpty()) return "前面沒有偵測到障礙物"

        val ordered = detections.sortedWith(
            compareByDescending<Detection> { it.type.isHazard }
                .thenByDescending { it.area },
        )
        return ordered.take(limit).joinToString("；") { compose(it) }
    }

    companion object {
        const val MAX_SPOKEN = 3
    }
}

/**
 * 播報去抖動。
 *
 * 相機以 2–5fps 跑，同一台車會被連續偵測到幾十次。沒有這一層，
 * 使用者會聽到「右前方兩公尺有車」重複三十遍 —— 功能會直接不能用。
 *
 * 與 `AnnouncementManager` 的 `dedupeKey` 是兩層防護：這裡先擋掉
 * 同一個物體的重複，那裡再擋掉不同來源的重複播報。
 */
class ObstacleDebouncer(
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lastSpoken = mutableMapOf<String, Long>()

    /**
     * @return true 代表現在可以播報。
     */
    fun shouldAnnounce(detection: Detection): Boolean {
        val key = keyFor(detection)
        val timestamp = now()
        val previous = lastSpoken[key]
        if (previous != null && timestamp - previous < windowMillis) return false
        lastSpoken[key] = timestamp
        return true
    }

    /**
     * 去抖動的鍵。
     *
     * 用「類別 + 大致方位」而不是精確座標 —— 同一台車在連續影格之間座標
     * 會微幅變動，用精確值當鍵等於沒有去抖動。
     */
    private fun keyFor(detection: Detection): String =
        "${detection.type.name}:${BearingResolver.resolve(detection.centerX).name}"

    fun reset() = lastSpoken.clear()

    companion object {
        /**
         * 同一個物體在這段時間內只播一次。
         *
         * 5 秒是走路約 7 公尺 —— 場景已經明顯改變，再講一次才有新資訊。
         */
        const val DEFAULT_WINDOW_MILLIS = 5_000L
    }
}
