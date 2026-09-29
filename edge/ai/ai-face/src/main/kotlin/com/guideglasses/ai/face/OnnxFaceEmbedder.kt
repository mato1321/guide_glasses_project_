package com.guideglasses.ai.face

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.face.DetectedFace
import com.guideglasses.core.domain.face.FaceEmbedder
import com.guideglasses.core.domain.face.FaceEmbedding
import com.guideglasses.core.domain.glasses.CameraFrame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

/**
 * 端側人臉特徵抽取，直接執行 InsightFace 匯出的 `.onnx`。
 *
 * ## 為什麼不轉成 tflite
 *
 * InsightFace 的模型是 PyTorch 匯出的 ONNX，張量排列是 **NCHW**
 * （先全部 R、再全部 G、再全部 B）。TFLite / Android 的慣例是 **NHWC**
 * （每個像素的 R G B 連在一起）。轉檔工具要做這層重排，一旦沒處理好：
 *
 * **模型照樣吐出 512 個數字，不會有任何錯誤訊息，只是那些數字沒有意義。**
 * 系統會安靜地認不出任何人 —— 這是最難除錯的一種失敗。
 *
 * 直接跑 ONNX 就沒有轉換這一步，也就沒有這個風險。代價是 APK 多約 15MB。
 *
 * ## 模型規格
 *
 * 對應 `buffalo_sc/w600k_mbf.onnx`（MobileFaceNet，約 13MB，512 維）：
 *
 * | 項目 | 值 |
 * |---|---|
 * | 輸入 | `[1, 3, 112, 112]` float32，**NCHW** |
 * | 色彩 | RGB（不是 BGR） |
 * | 正規化 | `(pixel - 127.5) / 127.5` → `[-1, 1]` |
 * | 輸出 | `[1, 512]` float32 |
 *
 * 前處理與 InsightFace 的 `ArcFaceONNX`（`input_mean=127.5`、
 * `input_std=127.5`、`swapRB=True`）一致，所以眼鏡算出來的特徵與後端同源。
 *
 * 模型放置方式見 `src/main/assets/README.md`。
 */
class OnnxFaceEmbedder(
    context: Context,
    private val modelAssetName: String = MODEL_ASSET_NAME,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : FaceEmbedder {

    private val appContext = context.applicationContext

    private val session: OrtSession? by lazy {
        runCatching { loadSession() }
            .onFailure { Log.w(TAG, "找不到或無法載入人臉模型：$modelAssetName", it) }
            .getOrNull()
    }

    override val isAvailable: Boolean
        get() = session != null

    /**
     * 輸出維度。從模型讀取而不是寫死 —— 換模型時（512 / 256 / 128）不必改程式，
     * 而且 `FaceMatcher` 會用維度判斷舊資料還能不能比對。
     */
    override val dimension: Int
        get() = runCatching {
            val info = session?.outputInfo?.values?.firstOrNull()?.info
                as? ai.onnxruntime.TensorInfo
            info?.shape?.lastOrNull()?.toInt() ?: 0
        }.getOrDefault(0)

    override suspend fun embed(
        frame: CameraFrame,
        face: DetectedFace,
    ): AppResult<FaceEmbedding> = withContext(ioDispatcher) {
        val ort = session ?: return@withContext AppResult.Failure(
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

            val values = runInference(ort, scaled)
                ?: return@withContext AppResult.Failure(
                    AppError.NoResult("模型沒有回傳特徵向量"),
                )

            AppResult.Success(FaceEmbedding(values))
        } catch (e: Throwable) {
            Log.e(TAG, "特徵抽取失敗", e)
            AppResult.Failure(AppError.Unknown("embedding failed: ${e.message}"))
        } finally {
            if (scaled !== cropped) scaled?.recycle()
            if (cropped !== full) cropped?.recycle()
            full.recycle()
        }
    }

    private fun runInference(ort: OrtSession, bitmap: Bitmap): FloatArray? {
        val environment = OrtEnvironment.getEnvironment()
        val inputName = ort.inputNames.firstOrNull() ?: return null

        OnnxTensor.createTensor(environment, bitmap.toNchwBuffer(), INPUT_SHAPE).use { tensor ->
            ort.run(mapOf(inputName to tensor)).use { results ->
                @Suppress("UNCHECKED_CAST")
                val output = results[0].value as? Array<FloatArray> ?: return null
                return output.firstOrNull()
            }
        }
    }

    /**
     * Bitmap → **NCHW** float 緩衝區。
     *
     * 這是與 [TfLiteFaceEmbedder] 唯一實質不同的地方，也是最容易寫錯的地方：
     * 三個色板要**分開連續存放**（先寫完整張圖的 R，再 G，再 B），
     * 而不是每個像素寫 R G B。寫錯不會報錯，只會讓辨識全部失效。
     */
    private fun Bitmap.toNchwBuffer(): FloatBuffer {
        val pixelCount = INPUT_SIZE * INPUT_SIZE
        val buffer = FloatBuffer.allocate(CHANNELS * pixelCount)

        val pixels = IntArray(pixelCount)
        getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        // 通道 0 = R，通道 1 = G，通道 2 = B。每個通道寫完整張圖再換下一個。
        for (shift in intArrayOf(16, 8, 0)) {
            for (pixel in pixels) {
                val channel = (pixel shr shift) and 0xFF
                buffer.put((channel - MEAN) / STD)
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun loadSession(): OrtSession {
        val bytes = appContext.assets.open(modelAssetName).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            // 眼鏡只有 2GB RAM 與有限散熱，不要開太多執行緒跟系統搶資源。
            setIntraOpNumThreads(NUM_THREADS)
        }
        return OrtEnvironment.getEnvironment().createSession(bytes, options)
    }

    fun close() {
        runCatching { session?.close() }
            .onFailure { Log.w(TAG, "關閉 ONNX session 失敗", it) }
    }

    companion object {
        private const val TAG = "OnnxFaceEmbedder"

        /** 模型檔名。放在 `ai/ai-face/src/main/assets/` 之下。 */
        const val MODEL_ASSET_NAME = "w600k_mbf.onnx"

        /** 模型輸入邊長。 */
        const val INPUT_SIZE = 112

        private const val CHANNELS = 3
        private const val NUM_THREADS = 2

        /** ArcFace 系列的正規化常數，與 InsightFace 的 `input_mean` / `input_std` 一致。 */
        private const val MEAN = 127.5f
        private const val STD = 127.5f

        /** NCHW：批次 1、3 通道、112×112。 */
        private val INPUT_SHAPE = longArrayOf(1, CHANNELS.toLong(), INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
    }
}
