package com.guideglasses.core.domain.glasses

/**
 * 幀率節流。
 *
 * CameraX 的 `ImageAnalysis` 會以相機的原生速率送幀（通常 30fps），
 * 但我們只想要 2–5fps。多出來的幀必須**盡早丟掉** —— 在轉檔之前，
 * 因為 RGBA→JPEG 才是真正花時間的部分。
 *
 * 純 Kotlin、時鐘由呼叫端傳入，因此節流行為可以完整用單元測試驗證，
 * 不必真的等一秒鐘。
 */
class FrameRateLimiter(targetFps: Float) {

    init {
        require(targetFps > 0f) { "targetFps 必須大於 0" }
    }

    private val minIntervalMillis: Long = (1000f / targetFps).toLong().coerceAtLeast(1L)

    private var lastAcceptedAt: Long? = null

    /** 這一幀應該被處理嗎？回傳 true 時會把它記為「已接受」。 */
    fun shouldAccept(nowMillis: Long): Boolean {
        val last = lastAcceptedAt
        if (last != null && nowMillis - last < minIntervalMillis) return false
        lastAcceptedAt = nowMillis
        return true
    }

    /** 重設，讓下一幀無條件被接受。串流重新開始時呼叫。 */
    fun reset() {
        lastAcceptedAt = null
    }
}

/**
 * 依「長邊像素」算出目標解析度，維持原始長寬比。
 *
 * 影像若已經小於目標就不放大 —— 放大不會增加資訊，只會增加後續處理成本。
 */
object ResolutionPlanner {

    /**
     * @param sourceWidth 原始寬
     * @param sourceHeight 原始高
     * @param longEdgePixels 期望的長邊像素
     * @return (寬, 高)，皆為正整數
     */
    fun scaleToLongEdge(
        sourceWidth: Int,
        sourceHeight: Int,
        longEdgePixels: Int,
    ): Pair<Int, Int> {
        require(sourceWidth > 0 && sourceHeight > 0) { "來源尺寸必須為正" }
        require(longEdgePixels > 0) { "longEdgePixels 必須為正" }

        val longEdge = maxOf(sourceWidth, sourceHeight)
        if (longEdge <= longEdgePixels) return sourceWidth to sourceHeight

        val scale = longEdgePixels.toDouble() / longEdge
        val width = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val height = (sourceHeight * scale).toInt().coerceAtLeast(1)
        return width to height
    }
}
