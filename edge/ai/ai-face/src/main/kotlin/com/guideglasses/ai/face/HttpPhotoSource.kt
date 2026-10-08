package com.guideglasses.ai.face

import android.graphics.BitmapFactory
import android.util.Log
import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.face.PersonPhotos
import com.guideglasses.core.domain.face.PhotoSource
import com.guideglasses.core.domain.glasses.CameraFrame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * 從註冊工具（`tools/face_enroll_server.py`）取得人臉照片。
 *
 * 契約：
 * ```
 * GET {base}/manifest        -> {"people":[{"name":"王小明","photos":["王小明/001.jpg"]}]}
 * GET {base}/photos/{ref}    -> 圖片位元組
 * ```
 *
 * 逾時刻意設得比辨識寬鬆很多 —— 同步是使用者主動觸發、可以等的動作，
 * 而辨識時多等一秒那個人可能已經走掉了。
 */
class HttpPhotoSource(
    private val baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PhotoSource {

    private val json = Json { ignoreUnknownKeys = true }

    override val isAvailable: Boolean get() = baseUrl.isNotBlank()

    private val root: String get() = baseUrl.trimEnd('/')

    override suspend fun listPeople(): AppResult<List<PersonPhotos>> =
        withContext(ioDispatcher) {
            when (val body = get("$root/manifest")) {
                is AppResult.Failure -> body
                is AppResult.Success -> runCatching {
                    json.decodeFromString<ManifestResponse>(body.data.decodeToString())
                }.fold(
                    onSuccess = { manifest ->
                        AppResult.Success(
                            manifest.people.map { PersonPhotos(it.name, it.photos) },
                        )
                    },
                    onFailure = {
                        AppResult.Failure(
                            AppError.Remote(debugMessage = "manifest 格式錯誤：${it.message}"),
                        )
                    },
                )
            }
        }

    override suspend fun loadPhoto(reference: String): AppResult<CameraFrame> =
        withContext(ioDispatcher) {
            // 姓名多半是中文，路徑一定要編碼。斜線是路徑分隔不能編碼，
            // 所以逐段處理而不是整串丟進 URLEncoder。
            val encoded = reference.split('/').joinToString("/") {
                URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20")
            }

            when (val body = get("$root/photos/$encoded")) {
                is AppResult.Failure -> body
                is AppResult.Success -> body.data.toFrameOrFailure()
            }
        }

    /**
     * 只解析尺寸，不把整張點陣圖載進記憶體。
     *
     * 眼鏡只有 2GB RAM，而註冊照片可能是手機拍的 4000×3000。
     * 實際解碼交給下游（偵測與特徵抽取各自需要時再做）。
     */
    private fun ByteArray.toFrameOrFailure(): AppResult<CameraFrame> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(this, 0, size, bounds)

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return AppResult.Failure(AppError.NoResult("照片無法解碼"))
        }

        return AppResult.Success(
            CameraFrame(
                bytes = this,
                format = CameraFrame.Format.JPEG,
                width = bounds.outWidth,
                height = bounds.outHeight,
                // 註冊照片是靜態檔案，沒有相機的旋轉中繼資料要補償。
                rotationDegrees = 0,
                timestampMillis = System.currentTimeMillis(),
            ),
        )
    }

    private fun get(url: String): AppResult<ByteArray> = try {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                AppResult.Failure(AppError.Remote(response.code, "GET 失敗 $url"))
            } else {
                response.body?.bytes()
                    ?.let { AppResult.Success(it) }
                    ?: AppResult.Failure(AppError.Remote(response.code, "空回應"))
            }
        }
    } catch (e: UnknownHostException) {
        // 位址是 IP 時幾乎不會走到這裡；用主機名時代表 DNS 或網路真的不通。
        Log.w(TAG, "找不到主機 $url", e)
        AppResult.Failure(AppError.NoNetwork(e.message ?: "unknown host"))
    } catch (e: IOException) {
        // 「連不到註冊工具」≠「沒有網路」。混為一談的代價實測過：眼鏡 ping
        // 電腦 0% 掉包、位址也正確，使用者卻聽到「目前沒有網路」，然後跑去
        // 檢查一個根本沒壞的東西。同樣的教訓 RemoteLlmIntentGateway 記過一次。
        //
        // ⚠️ 不要只接 ConnectException。直覺上「埠沒開 = 連線被拒」，
        // 但實測 Windows 防火牆對沒開的埠是**丟包**而不是回 RST，眼鏡上
        // 拿到的是 SocketTimeoutException（`nc` 也是回 Timeout 而非 refused）。
        // 兩種都要涵蓋，所以接在 IOException 這一層 ——
        // 對使用者而言「連不到」就是連不到，原因由 debugMessage 留給 log。
        Log.w(TAG, "連不到 $url（註冊工具沒啟動或防火牆擋住）", e)
        AppResult.Failure(
            AppError.Remote(debugMessage = "連不到 $url：${e.javaClass.simpleName}"),
        )
    } catch (e: IllegalArgumentException) {
        AppResult.Failure(AppError.Remote(debugMessage = "位址格式錯誤：$url"))
    }

    @Serializable
    private data class ManifestResponse(val people: List<ManifestPerson> = emptyList())

    @Serializable
    private data class ManifestPerson(
        val name: String = "",
        @SerialName("photos") val photos: List<String> = emptyList(),
    )

    companion object {
        private const val TAG = "HttpPhotoSource"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
