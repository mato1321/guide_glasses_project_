package com.guideglasses.ai.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.glasses.CameraFrame
import com.guideglasses.core.domain.obstacle.Detection
import com.guideglasses.core.domain.obstacle.ObstacleClass
import com.guideglasses.core.domain.obstacle.ObstacleDetector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 端側障礙物偵測，直接執行 YOLOv8 匯出的 `.onnx`。
 *
 * ## 為什麼是 ONNX 而不是 tflite
 *
 * 與 [com.guideglasses.ai.face.OnnxFaceEmbedder] 同一個理由：YOLOv8 是
 * PyTorch 匯出的，張量排列是 **NCHW**；轉 tflite 要做 NHWC 重排，
 * 弄錯不會報錯，只會安靜地什麼都偵測不到。少一次轉換就少一個出錯的機會。
 *
 * ## 模型規格
 *
 * | 項目 | 值 |
 * |---|---|
 * | 輸入 | `[1, 3, 640, 640]` float32，**NCHW**，RGB，`pixel / 255` |
 * | 前處理 | letterbox（保持長寬比，補 114 灰邊） |
 * | 輸出 | `[1, 4 + 8 + 32, 8400]` float32 |
 *
 * 輸出的 44 個通道是 `cx, cy, w, h` + 8 個類別分數 + 32 個 mask 係數。
 * **這裡只讀前 12 個通道**：domain 的 [Detection] 只要框與類別，
 * 用不到實例分割的遮罩。多解 32 個係數與 160×160 的 proto 只是白花時間，
 * 而導盲的延遲是使用者站在路口等的時間。
 *
 * ## letterbox 不能省
 *
 * 直接把畫面拉伸成正方形也能跑，而且座標換算更簡單 —— 但模型是用 letterbox
 * 訓練的，拉伸會讓長寬比失真，行人變胖、車輛變扁。同樣不會報錯，
 * 只會讓準確率悄悄掉下去。
 */
class YoloObstacleDetector(
    context: Context,
    private val modelAssetName: String = MODEL_ASSET_NAME,
    private val confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
    private val iouThreshold: Float = DEFAULT_IOU_THRESHOLD,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ObstacleDetector {

    private val appContext = context.applicationContext

    private val session: OrtSession? by lazy {
        runCatching { loadSession() }
            .onFailure { Log.w(TAG, "找不到或無法載入障礙物模型：$modelAssetName", it) }
            .getOrNull()
    }

    override val isAvailable: Boolean
        get() = session != null

    /**
     * 模型的輸入邊長。從模型讀而不是寫死 —— 換成 320 或 960 的模型時
     * 不必改程式，而寫死了又剛好對不上的話，結果是靜默的全盤誤判。
     */
    private val inputSize: Int
        get() = runCatching {
            val info = session?.inputInfo?.values?.firstOrNull()?.info as? TensorInfo
            info?.shape?.lastOrNull()?.toInt()?.takeIf { it > 0 } ?: DEFAULT_INPUT_SIZE
        }.getOrDefault(DEFAULT_INPUT_SIZE)

    override suspend fun detect(frame: CameraFrame): AppResult<List<Detection>> =
        withContext(ioDispatcher) {
            val ort = session ?: return@withContext AppResult.Failure(
                AppError.CapabilityUnavailable(
                    CAPABILITY,
                    "缺少障礙物模型 $modelAssetName",
                ),
            )

            val bitmap = frame.toBitmapOrNull() ?: return@withContext AppResult.Failure(
                AppError.Unknown("無法解析影像，format=${frame.format}"),
            )

            runCatching {
                val size = inputSize
                val letterbox = bitmap.letterbox(size)
                val input = letterbox.bitmap.toNchwFloatBuffer(size)

                val env = OrtEnvironment.getEnvironment()
                val inputName = ort.inputNames.first()

                OnnxTensor.createTensor(
                    env,
                    input,
                    longArrayOf(1, 3, size.toLong(), size.toLong()),
                ).use { tensor ->
                    ort.run(mapOf(inputName to tensor)).use { results ->
                        val raw = results[0].value as Array<*>
                        decode(raw, letterbox, bitmap.width, bitmap.height)
                    }
                }
            }.fold(
                onSuccess = { AppResult.Success(it) },
                onFailure = { error ->
                    Log.w(TAG, "障礙物推論失敗", error)
                    AppResult.Failure(AppError.Unknown("obstacle inference failed: ${error.message}"))
                },
            )
        }

    // ===== 後處理 =====

    /**
     * 解析 YOLOv8 的輸出。
     *
     * ultralytics 匯出的形狀是 `[1, C, N]`（C 是通道、N 是候選框）。
     * 有些工具鏈會轉置成 `[1, N, C]`，這裡用 `C << N` 判斷方向 ——
     * 通道數是幾十，候選框是幾千，不會誤判。
     */
    private fun decode(
        raw: Array<*>,
        letterbox: Letterbox,
        originalWidth: Int,
        originalHeight: Int,
    ): List<Detection> {
        @Suppress("UNCHECKED_CAST")
        val matrix = raw[0] as Array<FloatArray>
        if (matrix.isEmpty()) return emptyList()

        val rows = matrix.size
        val cols = matrix[0].size
        val channelsFirst = rows < cols

        val channels = if (channelsFirst) rows else cols
        val candidates = if (channelsFirst) cols else rows

        // 前 4 個是框，接著才是類別分數。通道數不足代表模型與這份程式不匹配，
        // 硬解下去只會得到隨機的框。
        if (channels < BOX_CHANNELS + CLASS_NAMES.size) {
            Log.w(TAG, "模型輸出通道數 $channels 與 ${CLASS_NAMES.size} 個類別不符")
            return emptyList()
        }

        fun at(channel: Int, index: Int): Float =
            if (channelsFirst) matrix[channel][index] else matrix[index][channel]

        val found = mutableListOf<ScoredBox>()

        for (i in 0 until candidates) {
            var bestScore = 0f
            var bestClass = -1
            for (c in CLASS_NAMES.indices) {
                val score = at(BOX_CHANNELS + c, i)
                if (score > bestScore) {
                    bestScore = score
                    bestClass = c
                }
            }
            if (bestClass < 0 || bestScore < confidenceThreshold) continue

            val type = CLASS_NAMES[bestClass].toObstacleClass() ?: continue

            // 模型輸出的是 letterbox 之後、輸入尺寸座標系的中心點與寬高。
            val cx = at(0, i)
            val cy = at(1, i)
            val w = at(2, i)
            val h = at(3, i)

            found += ScoredBox(
                type = type,
                left = cx - w / 2f,
                top = cy - h / 2f,
                right = cx + w / 2f,
                bottom = cy + h / 2f,
                score = bestScore,
            )
        }

        return found
            .nonMaxSuppress(iouThreshold)
            .mapNotNull { it.toDetection(letterbox, originalWidth, originalHeight) }
    }

    private data class ScoredBox(
        val type: ObstacleClass,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val score: Float,
    ) {
        val area: Float get() = max(0f, right - left) * max(0f, bottom - top)

        fun iou(other: ScoredBox): Float {
            val interLeft = max(left, other.left)
            val interTop = max(top, other.top)
            val interRight = min(right, other.right)
            val interBottom = min(bottom, other.bottom)
            val interArea = max(0f, interRight - interLeft) * max(0f, interBottom - interTop)
            val union = area + other.area - interArea
            return if (union <= 0f) 0f else interArea / union
        }

        /**
         * 還原到原始畫面，再轉成 0..1 的相對座標。
         *
         * domain 一律用相對座標，這樣同一組判斷邏輯在 640×480 與 1280×960
         * 之下行為才會相同。
         */
        fun toDetection(
            letterbox: Letterbox,
            originalWidth: Int,
            originalHeight: Int,
        ): Detection? {
            if (originalWidth <= 0 || originalHeight <= 0 || letterbox.scale <= 0f) return null

            val x1 = ((left - letterbox.padX) / letterbox.scale / originalWidth).coerceIn(0f, 1f)
            val y1 = ((top - letterbox.padY) / letterbox.scale / originalHeight).coerceIn(0f, 1f)
            val x2 = ((right - letterbox.padX) / letterbox.scale / originalWidth).coerceIn(0f, 1f)
            val y2 = ((bottom - letterbox.padY) / letterbox.scale / originalHeight).coerceIn(0f, 1f)

            val width = x2 - x1
            val height = y2 - y1
            // Detection 的 init 要求寬高為正，裁切到邊界外的框會變成 0。
            if (width <= 0f || height <= 0f) return null

            return Detection(
                type = type,
                left = x1,
                top = y1,
                width = width,
                height = height,
                confidence = score,
            )
        }
    }

    /** 逐類別做 NMS —— 人站在車前面時兩個框重疊，但它們是不同的東西。 */
    private fun List<ScoredBox>.nonMaxSuppress(threshold: Float): List<ScoredBox> =
        groupBy { it.type }.values.flatMap { group ->
            val sorted = group.sortedByDescending { it.score }.toMutableList()
            val kept = mutableListOf<ScoredBox>()
            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                kept += best
                sorted.removeAll { it.iou(best) > threshold }
            }
            kept
        }

    // ===== 前處理 =====

    private data class Letterbox(val bitmap: Bitmap, val scale: Float, val padX: Float, val padY: Float)

    /** 等比縮放後置中，四周補 114 灰 —— 與 ultralytics 訓練時的做法一致。 */
    private fun Bitmap.letterbox(size: Int): Letterbox {
        val scale = min(size.toFloat() / width, size.toFloat() / height)
        val scaledWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (height * scale).roundToInt().coerceAtLeast(1)
        val padX = (size - scaledWidth) / 2f
        val padY = (size - scaledHeight) / 2f

        val canvasBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(canvasBitmap).apply {
            drawColor(Color.rgb(PAD_GREY, PAD_GREY, PAD_GREY))
            val scaled = Bitmap.createScaledBitmap(this@letterbox, scaledWidth, scaledHeight, true)
            drawBitmap(scaled, padX, padY, null)
            if (scaled !== this@letterbox) scaled.recycle()
        }
        return Letterbox(canvasBitmap, scale, padX, padY)
    }

    /** NCHW：先全部 R、再全部 G、再全部 B。 */
    private fun Bitmap.toNchwFloatBuffer(size: Int): FloatBuffer {
        val pixels = IntArray(size * size)
        getPixels(pixels, 0, size, 0, 0, size, size)

        val buffer = FloatBuffer.allocate(3 * size * size)
        val plane = size * size
        for (i in 0 until plane) {
            val pixel = pixels[i]
            buffer.put(i, ((pixel shr 16) and 0xFF) / 255f)
            buffer.put(plane + i, ((pixel shr 8) and 0xFF) / 255f)
            buffer.put(2 * plane + i, (pixel and 0xFF) / 255f)
        }
        return buffer
    }

    private fun CameraFrame.toBitmapOrNull(): Bitmap? = when (format) {
        CameraFrame.Format.JPEG ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        CameraFrame.Format.RGBA_8888 ->
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
            }

        CameraFrame.Format.NV21 -> null
    }

    private fun loadSession(): OrtSession {
        val bytes = appContext.assets.open(modelAssetName).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(INFERENCE_THREADS)
        }
        return OrtEnvironment.getEnvironment().createSession(bytes, options)
    }

    companion object {
        const val MODEL_ASSET_NAME = "obstacle_yolov8.onnx"

        /** [AppError.CapabilityUnavailable] 的識別字。 */
        const val CAPABILITY = "obstacle-detector"

        /**
         * 類別索引 → 名稱。**順序就是 `data.yaml` 的 `names`，不可以動。**
         *
         * 這份清單是模型與 domain 之間唯一的對照點。
         * [ObstacleClass] 的 enum 順序與這裡**完全不同**，所以對照一律走
         * [toObstacleClass] 的名稱比對，絕不用 ordinal —— 弄錯不會報錯，
         * 只會把腳踏車唸成行人。
         *
         * 換模型時只改這裡，並更新 `ObstacleClassMappingTest`。
         */
        val CLASS_NAMES = listOf(
            "bicycle",
            "car",
            "crosswalk",
            "guidebrick",
            "motorcycle",
            "obstacle",
            "people",
            "sidewalk",
        )

        /** `data.yaml` 的名稱 → domain 類別。認不得的名稱回傳 null 並被丟棄。 */
        fun String.toObstacleClass(): ObstacleClass? = when (this) {
            "bicycle" -> ObstacleClass.BICYCLE
            "car" -> ObstacleClass.CAR
            "crosswalk" -> ObstacleClass.CROSSWALK
            "guidebrick" -> ObstacleClass.GUIDE_BRICK
            "motorcycle" -> ObstacleClass.MOTORCYCLE
            "obstacle" -> ObstacleClass.OBSTACLE
            "people" -> ObstacleClass.PERSON
            "sidewalk" -> ObstacleClass.SIDEWALK
            else -> null
        }

        /** `cx, cy, w, h`。類別分數從第 5 個通道開始。 */
        private const val BOX_CHANNELS = 4

        private const val DEFAULT_INPUT_SIZE = 640

        /**
         * 信心門檻。
         *
         * 比一般 demo 常用的 0.25 高 —— 誤報對導盲使用者的代價很實際：
         * 每一次假警報都會讓他停下腳步。寧可漏掉遠處模糊的目標。
         */
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.35f

        const val DEFAULT_IOU_THRESHOLD = 0.45f

        /** letterbox 補邊的灰度，與 ultralytics 一致。 */
        private const val PAD_GREY = 114

        private const val INFERENCE_THREADS = 2

        private const val TAG = "YoloObstacle"
    }
}
