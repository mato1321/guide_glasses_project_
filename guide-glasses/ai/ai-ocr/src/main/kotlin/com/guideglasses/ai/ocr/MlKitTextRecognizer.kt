package com.guideglasses.ai.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.glasses.CameraFrame
import com.guideglasses.core.domain.ocr.RecognizedText
import com.guideglasses.core.domain.ocr.TextBlock
import com.guideglasses.core.domain.ocr.TextRecognizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.coroutines.resume

/**
 * 端側 OCR，用 ML Kit Text Recognition v2 的中文模型。
 *
 * 這是三層漸進策略的第一層：**免費、離線、約 100ms**。
 * 日常最常見的情境 —— 藥袋、菜單、門牌、站牌、信件 —— 到這裡就夠了，
 * 只有辨識明顯不佳時才需要往雲端送。
 *
 * 用 bundled 版（`com.google.mlkit:text-recognition-chinese`）而不是
 * `play-services-mlkit-*` 版：**Rokid Glasses 是否預裝 Google Play Services
 * 無法確認**，bundled 版把模型打包進 APK，沒有這個不確定性。
 * 代價是 APK 變大約 20MB。
 *
 * 舊專案（`Text_Recognition`）的做法是把影像上傳到 FastAPI 再打
 * Google Cloud Vision，每次都要網路、每次都要錢、每次都要 1 秒以上。
 * 而且它的 `preprocess_doc()` 還先把彩色轉灰階，**反而降低 Vision 的準確率**。
 */
class MlKitTextRecognizer(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TextRecognizer {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** bundled 模型隨 APK 一起安裝，永遠可用。 */
    override val isAvailable: Boolean = true

    override suspend fun recognize(frame: CameraFrame): AppResult<RecognizedText> =
        withContext(ioDispatcher) {
            val bitmap = frame.toBitmap()
                ?: return@withContext AppResult.Failure(
                    AppError.Unknown("無法解析影像，format=${frame.format}"),
                )

            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                val result = awaitRecognition(image)
                    ?: return@withContext AppResult.Failure(
                        AppError.NoResult("ML Kit 沒有回傳結果"),
                    )

                AppResult.Success(result.toDomain(bitmap.height))
            } catch (e: Throwable) {
                Log.e(TAG, "OCR 失敗", e)
                AppResult.Failure(AppError.Unknown("ocr failed: ${e.message}"))
            } finally {
                bitmap.recycle()
            }
        }

    private suspend fun awaitRecognition(
        image: InputImage,
    ): com.google.mlkit.vision.text.Text? = suspendCancellableCoroutine { continuation ->
        recognizer.process(image)
            .addOnSuccessListener { continuation.resume(it) }
            .addOnFailureListener {
                Log.e(TAG, "ML Kit process 失敗", it)
                continuation.resume(null)
            }
    }

    private fun com.google.mlkit.vision.text.Text.toDomain(imageHeight: Int): RecognizedText {
        val blocks = textBlocks.mapNotNull { block ->
            val content = block.text.trim()
            if (content.isEmpty()) return@mapNotNull null

            // 用區塊高度佔畫面的比例當「字有多大」的代理指標。
            // 招牌模式靠它挑出最顯眼的那塊字。
            val height = block.boundingBox?.height() ?: 0
            TextBlock(
                text = content,
                heightRatio = if (imageHeight > 0) height.toFloat() / imageHeight else 0f,
            )
        }

        return RecognizedText(
            text = text.trim(),
            blocks = blocks,
            source = RecognizedText.Source.ON_DEVICE,
        )
    }

    private fun CameraFrame.toBitmap(): Bitmap? = when (format) {
        CameraFrame.Format.JPEG ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        CameraFrame.Format.RGBA_8888 ->
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
            }

        CameraFrame.Format.NV21 -> null
    }

    fun close() {
        runCatching { recognizer.close() }
            .onFailure { Log.w(TAG, "關閉 ML Kit recognizer 失敗", it) }
    }

    private companion object {
        const val TAG = "MlKitOcr"
    }
}
