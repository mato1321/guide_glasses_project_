package com.guideglasses.ai.face

import android.graphics.Bitmap
import android.util.Log
import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.face.DetectedFace
import com.guideglasses.core.domain.face.FaceEmbedding
import com.guideglasses.core.domain.face.FaceIdentificationStrategy
import com.guideglasses.core.domain.face.FaceMatch
import com.guideglasses.core.domain.face.FaceMatcher
import com.guideglasses.core.domain.face.KnownPerson
import com.guideglasses.core.domain.glasses.CameraFrame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * 遠端人臉辨識，沿用團隊既有的 InsightFace FastAPI 後端。
 *
 * 這條路徑**今天就能用** —— 不需要 `.tflite` 模型檔，也不需要改後端。
 * `Face_Recognition/` 已經在眼鏡上驗證過同一個 `/recognize` 端點。
 *
 * ## 與 `Face_Recognition` 現行做法的差異
 *
 * 這裡實作的是 `docs` 中建議的「端側偵測 + 遠端辨識」：
 *
 * | | Face_Recognition 現行 | 這裡 |
 * |---|---|---|
 * | 觸發 | 固定每 5 秒 | 使用者說「這是誰」時 |
 * | 上傳內容 | **整張畫面** 640×480 約 40KB | **裁切後的臉** 約 3-8KB |
 * | 沒有人臉時 | 照樣上傳 | **完全不上傳** |
 * | logging | `Level.BODY`（請求體寫兩次） | 無 |
 *
 * 傳輸量與無效請求都大幅減少，這三點正是延遲分析（`docs`）指出的主因。
 *
 * ## 後端契約
 *
 * ```
 * POST {endpoint}
 * Content-Type: multipart/form-data; name="file"
 *
 * → {"success": true, "face_count": 1,
 *    "faces": [{"name": "王小明", "confidence": 0.72, "bbox": [...]}]}
 * ```
 *
 * 與 `Face_Recognition/Python/main.py` 的 `/recognize` 相同，**不需要修改後端**。
 */
class RemoteFaceIdentification(
    private val endpoint: String,
    private val client: OkHttpClient = defaultClient(),
    private val confidentThreshold: Float = FaceMatcher.DEFAULT_CONFIDENT_THRESHOLD,
    private val tentativeThreshold: Float = FaceMatcher.DEFAULT_TENTATIVE_THRESHOLD,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FaceIdentificationStrategy {

    private val json = Json { ignoreUnknownKeys = true }

    override val isAvailable: Boolean get() = endpoint.isNotBlank()

    override val name: String = "remote"

    override suspend fun identify(
        frame: CameraFrame,
        face: DetectedFace,
    ): AppResult<FaceMatch> = withContext(ioDispatcher) {
        val jpeg = frame.croppedFaceJpeg(face)
            ?: return@withContext AppResult.Failure(AppError.NoResult("臉部裁切失敗"))

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "face.jpg", jpeg.toRequestBody(JPEG_MEDIA_TYPE))
            .build()

        try {
            client.newCall(Request.Builder().url(endpoint).post(body).build()).execute()
                .use { response ->
                    if (!response.isSuccessful) {
                        return@withContext AppResult.Failure(
                            AppError.Remote(response.code, "recognize failed"),
                        )
                    }
                    val payload = response.body?.string()
                        ?: return@withContext AppResult.Failure(
                            AppError.Remote(response.code, "empty body"),
                        )
                    parse(payload)
                }
        } catch (e: UnknownHostException) {
            AppResult.Failure(AppError.NoNetwork(e.message ?: "unknown host"))
        } catch (e: SocketTimeoutException) {
            AppResult.Failure(AppError.NoNetwork(e.message ?: "timeout"))
        } catch (e: IOException) {
            AppResult.Failure(AppError.NoNetwork(e.message ?: "io failure"))
        }
    }

    private fun parse(payload: String): AppResult<FaceMatch> {
        val decoded = runCatching { json.decodeFromString<RecognizeResponse>(payload) }
            .getOrElse {
                return AppResult.Failure(
                    AppError.Remote(debugMessage = "malformed response: ${it.message}"),
                )
            }

        // 已經只送一張臉過去，回傳多筆時取信心最高的。
        val best = decoded.faces.maxByOrNull { it.confidence }
            ?: return AppResult.Success(FaceMatch.Unknown(0f))

        val similarity = best.confidence.toFloat()

        // 後端用 "unknown" 表示資料庫裡沒這個人。
        if (best.name.isBlank() || best.name.equals(UNKNOWN_NAME, ignoreCase = true)) {
            return AppResult.Success(FaceMatch.Unknown(similarity))
        }

        val person = KnownPerson(
            // 後端不回傳 id。用名字的雜湊當穩定識別碼 —— 只用於播報去抖動，
            // 不會寫進本地資料庫，所以不會與端側的 id 衝突。
            id = best.name.hashCode().toLong(),
            name = best.name,
            relation = null,
            // 遠端不回傳特徵向量。放一個佔位值，因為這條路徑不做本地比對。
            embedding = PLACEHOLDER_EMBEDDING,
        )

        return AppResult.Success(
            when {
                similarity >= confidentThreshold -> FaceMatch.Confident(person, similarity)
                similarity >= tentativeThreshold -> FaceMatch.Tentative(person, similarity)
                else -> FaceMatch.Unknown(similarity)
            },
        )
    }

    /**
     * 裁出臉部並編成 JPEG。
     *
     * 只送臉不送整張畫面，除了快很多之外也比較保護隱私 ——
     * 背景裡的其他人、住家、診間都不會離開裝置。
     */
    private fun CameraFrame.croppedFaceJpeg(face: DetectedFace): ByteArray? {
        val full = toBitmapOrNull() ?: return null
        var cropped: Bitmap? = null
        return try {
            cropped = full.cropFace(face) ?: return null
            ByteArrayOutputStream().use { stream ->
                cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                stream.toByteArray()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "裁切或編碼失敗", e)
            null
        } finally {
            if (cropped !== full) cropped?.recycle()
            full.recycle()
        }
    }

    @Serializable
    private data class RecognizeResponse(
        val success: Boolean = true,
        @SerialName("face_count") val faceCount: Int = 0,
        val faces: List<RemoteFace> = emptyList(),
    )

    @Serializable
    private data class RemoteFace(
        val name: String = "",
        val confidence: Double = 0.0,
    )

    companion object {
        private const val TAG = "RemoteFaceId"
        private const val UNKNOWN_NAME = "unknown"
        private const val JPEG_QUALITY = 90
        private val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()

        /** 遠端策略不做本地比對，這個值不會被拿去算相似度。 */
        private val PLACEHOLDER_EMBEDDING = FaceEmbedding(floatArrayOf(0f))

        /**
         * 逾時刻意設短。使用者問「這是誰」之後等超過幾秒就失去意義了 ——
         * 那個人可能已經走掉，或是尷尬地站在那裡等回應。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            // 刻意不加 HttpLoggingInterceptor —— Level.BODY 會讓 multipart
            // 的請求體被寫兩次，是 Face_Recognition 延遲的成因之一。
            .build()
    }
}
