package com.guideglasses.ai.face

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.face.DetectedFace
import com.guideglasses.core.domain.face.FaceEmbedding
import com.guideglasses.core.domain.face.FaceEmbedder
import com.guideglasses.core.domain.glasses.CameraFrame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * 端側人臉特徵抽取，用 TFLite 跑 MobileFaceNet 類的模型。
 *
 * 這是取代舊架構的關鍵一環。舊做法是每 3-5 秒把**整張畫面**上傳到跑
 * InsightFace 的伺服器，帶來三個問題：延遲（見 `docs/08` §2）、
 * 隱私（使用者一整天遇到的所有路人的臉都上網）、離線失效。
 *
 * 改成端側之後這三個問題一次消失，而且對「認識的 10-50 個人」這種規模，
 * MobileFaceNet 的準確度綽綽有餘。
 *
 * ---
 *
 * ## ⚠️ 需要模型檔才能運作
 *
 * 本專案**不含模型權重**（`.tflite` 被 `.gitignore` 排除，且模型有各自的
 * 授權條款）。沒有模型時 [isAvailable] 為 false，上層會播報
 * 「人臉特徵模型不可用」而不是靜默失敗。
 *
 * 取得並安裝模型：
 *
 * 1. 準備一個輸出人臉特徵向量的 TFLite 模型（MobileFaceNet 或 FaceNet）
 * 2. 放到 `ai/ai-face/src/main/assets/[MODEL_ASSET_NAME]`
 * 3. 確認輸入尺寸與 [INPUT_SIZE] 一致（多數 MobileFaceNet 是 112×112）
 * 4. 確認輸出維度與模型相符 —— 本類別會自動從 interpreter 讀取，不需寫死
 *
 * 若模型的前處理不是 `[-1, 1]` 正規化，需要調整 [toInputBuffer]。
 */
class TfLiteFaceEmbedder(
    context: Context,
    private val modelAssetName: String = MODEL_ASSET_NAME,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : FaceEmbedder {

    private val appContext = context.applicationContext

    private val interpreter: Interpreter? by lazy {
        runCatching { loadInterpreter() }
            .onFailure { Log.w(TAG, "找不到或無法載入人臉模型：$modelAssetName", it) }
            .getOrNull()
    }

    override val isAvailable: Boolean
        get() = interpreter != null

    override val dimension: Int
        get() = interpreter?.getOutputTensor(0)?.shape()?.lastOrNull() ?: 0

    override suspend fun embed(
        frame: CameraFrame,
        face: DetectedFace,
    ): AppResult<FaceEmbedding> = withContext(ioDispatcher) {
        val tflite = interpreter ?: return@withContext AppResult.Failure(
            AppError.CapabilityUnavailable(
                "face-embedder",
                "缺少人臉特徵模型 $modelAssetName",
            ),
        )

        val full = frame.toBitmapOrNull() ?: return@withContext AppResult.Failure(
            AppError.Unknown("無法解析影像，format=${frame.format}"),
        )

        var cropped: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            cropped = full.cropFace(face) ?: return@withContext AppResult.Failure(
                AppError.NoResult("臉部裁切失敗"),
            )
            scaled = Bitmap.createScaledBitmap(cropped, INPUT_SIZE, INPUT_SIZE, true)

            val output = Array(1) { FloatArray(dimension) }
            tflite.run(scaled.toInputBuffer(), output)

            AppResult.Success(FaceEmbedding(output[0]))
        } catch (e: Throwable) {
            Log.e(TAG, "特徵抽取失敗", e)
            AppResult.Failure(AppError.Unknown("embedding failed: ${e.message}"))
        } finally {
            if (scaled !== cropped) scaled?.recycle()
            if (cropped !== full) cropped?.recycle()
            full.recycle()
        }
    }

    private fun loadInterpreter(): Interpreter {
        val descriptor = appContext.assets.openFd(modelAssetName)
        val buffer = descriptor.use { fd ->
            fd.createInputStream().use { stream ->
                stream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fd.startOffset,
                    fd.declaredLength,
                )
            }
        }
        return Interpreter(buffer, Interpreter.Options().apply { numThreads = NUM_THREADS })
    }

    /**
     * Bitmap → 模型輸入張量。
     *
     * 正規化到 `[-1, 1]`，這是 MobileFaceNet 系列最常見的前處理。
     * **換模型時要確認這一段** —— 前處理不符會讓特徵完全失去意義，
     * 而且不會有任何錯誤訊息，只會安靜地認不出任何人。
     */
    private fun Bitmap.toInputBuffer(): ByteBuffer {
        val buffer = ByteBuffer
            .allocateDirect(INPUT_SIZE * INPUT_SIZE * CHANNELS * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            buffer.putFloat((((pixel shr 16) and 0xFF) - 127.5f) / 127.5f) // R
            buffer.putFloat((((pixel shr 8) and 0xFF) - 127.5f) / 127.5f) // G
            buffer.putFloat(((pixel and 0xFF) - 127.5f) / 127.5f) // B
        }
        buffer.rewind()
        return buffer
    }

    fun close() {
        runCatching { interpreter?.close() }
            .onFailure { Log.w(TAG, "關閉 interpreter 失敗", it) }
    }

    companion object {
        private const val TAG = "TfLiteFaceEmbedder"

        /** 模型檔名。放在 `ai/ai-face/src/main/assets/` 之下。 */
        const val MODEL_ASSET_NAME = "mobilefacenet.tflite"

        /** 模型輸入邊長。多數 MobileFaceNet 是 112。 */
        const val INPUT_SIZE = 112

        private const val CHANNELS = 3

        /** 眼鏡只有 2GB RAM，不要開太多執行緒跟系統搶資源。 */
        private const val NUM_THREADS = 2
    }
}
