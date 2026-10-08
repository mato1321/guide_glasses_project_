package com.guideglasses.glasses.camerax

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.guideglasses.core.domain.glasses.CameraFrame
import com.guideglasses.core.domain.glasses.CaptureRequest
import com.guideglasses.core.domain.glasses.ResolutionPlanner
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * 把 CameraX 的 [ImageProxy] 轉成領域層的 [CameraFrame]。
 *
 * 舊專案（`Face_Recognition` / `AI_Assistant`）在這一步有兩個效能問題，
 * 這裡都避開了：
 *
 * 1. **逐像素 Kotlin 迴圈。** 舊版在 `rowStride != width * pixelStride`
 *    時會走「慢速路徑」—— 640×480 就是 30 萬次迴圈加 480 次 `setPixels`，
 *    在中低階 SoC 上可能 100–400ms。這裡改用 CameraX 內建的
 *    `ImageProxy.toBitmap()`，由 framework 處理 stride 與色彩轉換。
 *
 * 2. **無條件編碼成 JPEG。** 端側推論（MediaPipe / TFLite / ML Kit）
 *    要的是像素不是 JPEG，先編碼再解碼是純浪費。這裡讓呼叫端用
 *    [CaptureRequest.outputFormat] 指定，要 RGBA 就不編碼。
 */
internal object ImageProxyConverter {

    /**
     * @param proxy 來源影像。**呼叫端負責 close()**，本函式不會關閉它。
     * @param request 決定輸出格式、目標解析度、JPEG 品質。
     * @param timestampMillis 擷取時間戳，由呼叫端提供以便量測端到端延遲。
     */
    fun convert(
        proxy: ImageProxy,
        request: CaptureRequest,
        timestampMillis: Long,
    ): CameraFrame {
        val source = proxy.toBitmap()
        val rotation = proxy.imageInfo.rotationDegrees

        val (targetWidth, targetHeight) = ResolutionPlanner.scaleToLongEdge(
            sourceWidth = source.width,
            sourceHeight = source.height,
            longEdgePixels = request.longEdgePixels,
        )

        var working = source

        if (targetWidth != source.width || targetHeight != source.height) {
            val scaled = Bitmap.createScaledBitmap(working, targetWidth, targetHeight, true)
            working.recycleIfNot(scaled)
            working = scaled
        }

        // 旋轉一律在這裡處理完，輸出的 CameraFrame.rotationDegrees 永遠是 0。
        // 讓每個下游各自處理旋轉，遲早會有人忘記，而且人臉／文字方向錯了
        // 辨識率會直接崩掉。只有 rotation != 0 時才付出這次複製的成本。
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(
                working, 0, 0, working.width, working.height, matrix, true,
            )
            working.recycleIfNot(rotated)
            working = rotated
        }

        return try {
            when (request.outputFormat) {
                CameraFrame.Format.JPEG -> working.toJpegFrame(request.jpegQuality, timestampMillis)
                CameraFrame.Format.RGBA_8888 -> working.toRgbaFrame(timestampMillis)
                CameraFrame.Format.NV21 -> throw UnsupportedOperationException(
                    "NV21 輸出尚未實作。端側推論請改用 RGBA_8888，遠端上傳請用 JPEG。",
                )
            }
        } finally {
            working.recycle()
        }
    }

    private fun Bitmap.toJpegFrame(quality: Int, timestampMillis: Long): CameraFrame {
        val stream = ByteArrayOutputStream(estimatedJpegBytes())
        compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return CameraFrame(
            bytes = stream.toByteArray(),
            format = CameraFrame.Format.JPEG,
            width = width,
            height = height,
            rotationDegrees = 0,
            timestampMillis = timestampMillis,
        )
    }

    private fun Bitmap.toRgbaFrame(timestampMillis: Long): CameraFrame {
        val buffer = ByteBuffer.allocate(byteCount)
        copyPixelsToBuffer(buffer)
        return CameraFrame(
            bytes = buffer.array(),
            format = CameraFrame.Format.RGBA_8888,
            width = width,
            height = height,
            rotationDegrees = 0,
            timestampMillis = timestampMillis,
        )
    }

    /** 粗估 JPEG 大小以減少 ByteArrayOutputStream 的重新配置。 */
    private fun Bitmap.estimatedJpegBytes(): Int = (width * height / 8).coerceAtLeast(8 * 1024)

    /**
     * `createScaledBitmap` 與 `createBitmap` 在不需要變更時可能回傳同一個實例。
     * 直接 recycle 會把還要用的 bitmap 弄壞，所以先比對參考。
     */
    private fun Bitmap.recycleIfNot(other: Bitmap) {
        if (this !== other) recycle()
    }
}
