package com.guideglasses.core.domain.obstacle

import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.glasses.CameraFrame

/**
 * 障礙物類別。
 *
 * ⚠️ **這份清單與順序需要與 `Obstacle_Recognition` 交付的模型對齊**
 * （`docs/TASKS.md` B2）。目前是依導盲需求先訂的框架，模型到位後
 * 要用實際的類別索引校對 —— **索引對錯不會報錯，只會把車唸成盲磚**。
 *
 * @param spoken 播報用的名稱。
 * @param realWidthMeters 典型實際寬度（公尺），用於單目距離估計。
 *   null 代表尺寸變化太大、不適合用寬度反推距離。
 * @param kind 決定播報策略：危險物要警告，導引物是引路的線索。
 */
enum class ObstacleClass(
    val spoken: String,
    val realWidthMeters: Float?,
    val kind: Kind,
) {
    PERSON("行人", 0.45f, Kind.HAZARD),
    CAR("車輛", 1.8f, Kind.HAZARD),
    MOTORCYCLE("機車", 0.7f, Kind.HAZARD),
    BICYCLE("腳踏車", 0.6f, Kind.HAZARD),
    POLE("柱子", 0.15f, Kind.HAZARD),

    /**
     * 以下三類是**導引**而非危險 —— 使用者要的是「往這邊走」，
     * 不是「小心前方有盲磚」。播報語氣與節奏都不同。
     */
    CROSSWALK("斑馬線", null, Kind.GUIDE),
    GUIDE_BRICK("導盲磚", null, Kind.GUIDE),
    SIDEWALK("人行道", null, Kind.GUIDE),
    ;

    enum class Kind { HAZARD, GUIDE }

    val isHazard: Boolean get() = kind == Kind.HAZARD
}

/**
 * 一筆偵測結果。
 *
 * 座標一律用**相對比例**（0..1），與 [com.guideglasses.core.domain.face.DetectedFace]
 * 一致 —— 同一組判斷邏輯在 640×480 與 1280×960 之下行為才會相同。
 */
data class Detection(
    val type: ObstacleClass,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val confidence: Float,
) {
    init {
        require(width > 0f && height > 0f) { "偵測框尺寸必須為正" }
    }

    val centerX: Float get() = left + width / 2f
    val area: Float get() = width * height

    /** 底邊在畫面中的位置。愈接近底部通常代表愈近，可與寬度估距互相佐證。 */
    val bottom: Float get() = top + height
}

/** 障礙物偵測的抽象。實作在 `ai-vision`（等模型交付）。 */
interface ObstacleDetector {
    val isAvailable: Boolean
    suspend fun detect(frame: CameraFrame): AppResult<List<Detection>>
}
