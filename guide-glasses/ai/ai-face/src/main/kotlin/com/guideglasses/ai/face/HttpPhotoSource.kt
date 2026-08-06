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
import java.net.SocketTimeoutException
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
        Log.w(TAG, "找不到主機 $url", e)
        AppResult.Failure(AppError.NoNetwork(e.message ?: "unknown host"))
    } catch (e: SocketTimeoutException) {
        AppResult.Failure(AppError.NoNetwork(e.message ?: "timeout"))
    } catch (e: IOException) {
        AppResult.Failure(AppError.NoNetwork(e.message ?: "io failure"))
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
