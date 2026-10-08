package com.guideglasses.ai.navigation

import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.bus.BusOcrGateway
import com.guideglasses.core.domain.bus.BusOcrResult
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
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * 公車車頭／LED 顯示的 OCR 確認，沿用團隊既有的 FastAPI 後端
 * （`api/` 資料夾，原型 `bus_ocr.java` 呼叫的同一個端點）。
 *
 * 影像來源改用眼鏡既有的 [com.guideglasses.core.domain.glasses.FrameSource]，
 * 不是原型那樣自己重寫一份 Camera2 —— 端側擷取邏輯只該有一份。
 */
class HttpBusOcrGateway(
    private val endpoint: String,
    private val client: OkHttpClient = defaultClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BusOcrGateway {

    private val json = Json { ignoreUnknownKeys = true }

    override val isAvailable: Boolean get() = endpoint.isNotBlank()

    override suspend fun confirmBus(
        frame: CameraFrame,
        busNumber: String,
        stopId: String,
        direction: Int,
    ): AppResult<BusOcrResult> = withContext(ioDispatcher) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("bus_no", busNumber)
            .addFormDataPart("stop_id", stopId)
            .addFormDataPart("direction", direction.toString())
            .addFormDataPart("image", "bus.jpg", frame.bytes.toRequestBody(JPEG_MEDIA_TYPE))
            .build()

        runCatching {
            client.newCall(Request.Builder().url(endpoint).post(body).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use AppResult.Failure(AppError.Remote(response.code, "bus-ocr failed"))
                }
                val payload = response.body?.string()
                    ?: return@use AppResult.Failure(AppError.Remote(response.code, "empty body"))
                parse(payload)
            }
        }.getOrElse { toNetworkFailure(it) }
    }

    private fun parse(payload: String): AppResult<BusOcrResult> {
        val decoded = runCatching { json.decodeFromString<OcrResponse>(payload) }
            .getOrElse {
                return AppResult.Failure(AppError.Remote(debugMessage = "malformed bus-ocr: ${it.message}"))
            }

        return AppResult.Success(
            BusOcrResult(
                matched = decoded.matched,
                message = decoded.message,
                recognizedText = decoded.ocrText,
            ),
        )
    }

    private fun toNetworkFailure(error: Throwable): AppResult.Failure = when (error) {
        is UnknownHostException -> AppResult.Failure(AppError.NoNetwork(error.message ?: "unknown host"))
        is SocketTimeoutException -> AppResult.Failure(AppError.NoNetwork(error.message ?: "timeout"))
        is IOException -> AppResult.Failure(AppError.NoNetwork(error.message ?: "io failure"))
        else -> AppResult.Failure(AppError.Unknown(error.message ?: "unexpected failure"))
    }

    @Serializable
    private data class OcrResponse(
        val success: Boolean = true,
        val matched: Boolean = false,
        val message: String = "",
        @SerialName("ocr_text") val ocrText: String = "",
    )

    companion object {
        private val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            // 上傳照片後端要跑 YOLO/OCR 推論，逾時給寬一點。
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
