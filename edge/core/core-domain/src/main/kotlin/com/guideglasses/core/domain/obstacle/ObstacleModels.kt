package com.guideglasses.core.domain.obstacle

import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.glasses.CameraFrame

/**
 * 障礙物類別。
 *
 * ## ⚠️ 這個 enum 的順序**不是**模型的類別索引
 *
 * 模型交付後校對過了（`data.yaml`，8 類），兩邊的順序幾乎完全不同：
 *
 * | 模型索引 | `data.yaml` 名稱 | 對應到 |
 * |---:|---|---|
 * | 0 | `bicycle` | [BICYCLE] |
 * | 1 | `car` | [CAR] |
 * | 2 | `crosswalk` | [CROSSWALK] |
 * | 3 | `guidebrick` | [GUIDE_BRICK] |
 * | 4 | `motorcycle` | [MOTORCYCLE] |
 * | 5 | `obstacle` | [OBSTACLE] |
 * | 6 | `people` | [PERSON] |
 * | 7 | `sidewalk` | [SIDEWALK] |
 *
 * 只有索引 1 與 7 剛好對得上。**若有人按 ordinal 對照，索引 0 的腳踏車會
 * 變成行人、索引 6 的人會變成導盲磚** —— 不會有任何錯誤訊息，只會把車唸成
 * 盲磚，正是原本註解警告的情況。
 *
 * 因此對照一律**按名稱**，寫在
 * `ai-vision` 的 `YoloObstacleDetector.CLASS_NAMES`，並有測試鎖住。
 * 之後換模型只需要改那份清單，不要動這個 enum 的順序。
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
     * 模型的通用 `obstacle` 類別 —— 訓練資料把「擋路的東西」歸成一類，
     * 沒有再細分。
     *
     * `realWidthMeters` 是 null 而不是隨便給一個值：這一類涵蓋從三角錐到
     * 施工圍籬，寬度差十倍以上，用它反推距離只會得到自信滿滿的錯誤答案。
     * 寧可播報「右前方，障礙物」不講距離 —— 使用者知道「有東西但不知道多遠」
     * 還能自己放慢腳步，聽到錯的公尺數卻會直接撞上去。
     */
    OBSTACLE("障礙物", null, Kind.HAZARD),

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
