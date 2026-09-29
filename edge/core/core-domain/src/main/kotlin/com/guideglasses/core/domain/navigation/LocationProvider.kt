package com.guideglasses.core.domain.navigation

import kotlinx.coroutines.flow.Flow

/**
 * 定位來源的抽象。
 *
 * ## 為什麼要抽象化
 *
 * 「眼鏡到底有沒有 GPS」目前仍是**推定沒有**、尚未實測
 * （`docs/TASKS.md` A10）。與其等答案才開工，不如現在就定介面 ——
 * 實測結果只決定要綁哪一個實作，導航狀態機完全不需要知道座標從哪來。
 *
 * 這把「有沒有 GPS」從**架構問題**降級成**一個 DI 綁定**。
 *
 * | 實作 | 何時用 |
 * |---|---|
 * | `GlassesGpsLocationProvider` | A10 實測發現眼鏡有 GPS_PROVIDER → 手機層完全不需要 |
 * | `PhoneCompanionLocationProvider` | 眼鏡確認無 GPS（目前推定） |
 * | `NetworkLocationProvider` | 只當降級，20–100m 精度對步行導航不足 |
 *
 * 詳見 `docs/ARCHITECTURE.md` §5.3。
 */
interface LocationProvider {

    val isAvailable: Boolean

    /** 給觀測與降級判斷用。精度太差時導航應該說出來而不是硬導。 */
    val accuracyMeters: Float?

    /**
     * 位置串流。
     *
     * 斷線時應該讓 flow 結束或發出錯誤，**不要靜默停止** ——
     * 使用者需要知道導航停了。
     */
    fun locations(): Flow<Coordinate>
}

/**
 * 座標。
 *
 * @param accuracyMeters 這一筆的水平精度。都市高樓區 GPS 誤差可達 15–30m，
 *   「偏離重規劃」的閾值必須考慮它，否則會不停誤報。
 */
data class Coordinate(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val timestampMillis: Long = 0L,
) {
    init {
        require(latitude in -90.0..90.0) { "緯度必須介於 -90 到 90" }
        require(longitude in -180.0..180.0) { "經度必須介於 -180 到 180" }
    }
}
