package com.guideglasses.core.domain.glasses

import com.guideglasses.core.domain.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * 影像來源。
 *
 * 刻意以原始位元組表示影像，而不是 android.graphics.Bitmap —— 這樣
 * domain 層保持純 Kotlin，可在 JVM 單元測試中驗證，轉型的責任留給實作端。
 */
interface FrameSource {

    /**
     * 連續取得影像。若目前能力不支援串流，Flow 會立刻以
     * [com.guideglasses.core.domain.AppError.CapabilityUnavailable] 結束。
     */
    fun frames(request: CaptureRequest): Flow<AppResult<CameraFrame>>

    /** 取得單張影像。所有實作都必須支援。 */
    suspend fun captureOnce(request: CaptureRequest): AppResult<CameraFrame>
}

/**
 * @param targetFps 期望的張數／秒。實作會夾在自身能力上限內。
 * @param longEdgePixels 期望的長邊像素。YOLO 類模型通常 640 就夠，
 *   拉高只會增加傳輸延遲與耗電。
 */
data class CaptureRequest(
    val targetFps: Float = 2f,
    val longEdgePixels: Int = 640,
) {
    init {
        require(targetFps > 0f) { "targetFps 必須大於 0" }
        require(longEdgePixels > 0) { "longEdgePixels 必須大於 0" }
    }
}

/**
 * 一張影像。
 *
 * @param bytes 影像位元組。
 * @param format 位元組的編碼格式。
 * @param width 像素寬。
 * @param height 像素高。
 * @param rotationDegrees 需要順時針旋轉幾度才是正立方向。
 * @param timestampMillis 擷取時間，用於計算端到端延遲。
 */
data class CameraFrame(
    val bytes: ByteArray,
    val format: Format,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val timestampMillis: Long,
) {
    enum class Format { JPEG, NV21, RGBA_8888 }

    // ByteArray 使用參考相等，data class 自動產生的 equals 會給出誤導性的結果，
    // 因此明確覆寫成內容比較。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CameraFrame) return false
        return format == other.format &&
            width == other.width &&
            height == other.height &&
            rotationDegrees == other.rotationDegrees &&
            timestampMillis == other.timestampMillis &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + rotationDegrees
        result = 31 * result + timestampMillis.hashCode()
        return result
    }
}
