package com.guideglasses.ai.navigation

import com.guideglasses.core.domain.navigation.Coordinate
import com.guideglasses.core.domain.navigation.LocationProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 手機 companion 當 GPS 感測器。
 *
 * 眼鏡實測沒有 GPS（`docs/DEVICE_FINDINGS.md` A10），這是
 * `docs/ARCHITECTURE.md` §5.3 定義的 `PhoneCompanionLocationProvider` 實作。
 * 手機只回報座標，導航狀態機與所有播報都留在眼鏡端 —— 換裝置有 GPS 時
 * 只需要換這個 DI 綁定，[com.guideglasses.core.domain.bus.PlanBusRouteUseCase]
 * 完全不用動。
 *
 * 傳輸走「同一個 Wi-Fi + HTTP」，與 `RemoteFaceIdentification` 同一個模式
 * （零開發、已在眼鏡上驗證過）：手機把座標 POST 給共用後端，
 * 這裡用輪詢的方式 GET 回來，而不是另外接一條連線層。
 *
 * ## 後端契約
 * ```
 * GET {endpoint}/current-location
 * → {"success": true, "lat": 25.03, "lng": 121.51}
 * ```
 */
class PhoneCompanionLocationProvider(
    private val endpoint: String,
    private val client: OkHttpClient = defaultClient(),
    private val pollIntervalMillis: Long = 3_000L,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LocationProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override val isAvailable: Boolean get() = endpoint.isNotBlank()

    // 輪詢 HTTP 沒有原生的精度回報，手機端 FusedLocationProvider 的精度
    // 不會經過這個端點傳回來，因此不假裝知道，交由呼叫端自行判斷。
    override val accuracyMeters: Float? = null

    override fun locations(): Flow<Coordinate> = flow {
        while (true) {
            fetchOnce()?.let { emit(it) }
            delay(pollIntervalMillis)
        }
    }

    private suspend fun fetchOnce(): Coordinate? = withContext(ioDispatcher) {
        if (!isAvailable) return@withContext null

        try {
            val request = Request.Builder().url("$endpoint/current-location").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val payload = response.body?.string() ?: return@withContext null
                val decoded = runCatching { json.decodeFromString<LocationResponse>(payload) }
                    .getOrNull() ?: return@withContext null

                if (!decoded.success || (decoded.lat == 0.0 && decoded.lng == 0.0)) {
                    return@withContext null
                }

                Coordinate(
                    latitude = decoded.lat,
                    longitude = decoded.lng,
                    timestampMillis = System.currentTimeMillis(),
                )
            }
        } catch (e: IOException) {
            null
        }
    }

    @Serializable
    private data class LocationResponse(
        val success: Boolean = false,
        val lat: Double = 0.0,
        val lng: Double = 0.0,
    )

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }
}
