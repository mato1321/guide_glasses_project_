package com.guideglasses.ai.navigation

import com.guideglasses.core.domain.navigation.Coordinate
import com.guideglasses.core.domain.navigation.LocationProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
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
 * → {"success": true, "lat": 25.03, "lng": 121.51, "accuracy_m": 16.2, "age_ms": 1300}
 * ```
 *
 * ## 丟掉過舊的座標
 *
 * 後端只記得「最後一筆」。手機螢幕一鎖、App 被砍、後端重開，這一筆就不再更新 ——
 * 以前這裡照樣每次都當成新座標吐出去，還蓋上現在的時間，導航會拿著幾分鐘前的
 * 位置繼續判斷轉彎。現在 `age_ms` 超過 [maxAgeMillis] 就丟掉（企畫書 P1），
 * 上層收不到座標，自然會說「拿不到定位」而不是亂講。
 *
 * `age_ms` 由手機與後端各自用單調時鐘累加而成，**不依賴眼鏡的牆上時鐘** ——
 * 眼鏡的時間實測快了 4 小時 15 分，任何跨裝置的時間比對都會錯。
 * 舊版後端沒有 `age_ms` 時無從判斷，照舊接受。
 */
class PhoneCompanionLocationProvider(
    private val endpoint: String,
    private val client: OkHttpClient = defaultClient(),
    // 手機每秒回報一次；以前 3 秒輪詢一次，最壞要多等 3 秒才知道新位置，
    // 企畫書驗收標準是「手機回報到眼鏡取得 < 3 秒」。
    private val pollIntervalMillis: Long = 1_000L,
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LocationProvider {

    override val isAvailable: Boolean get() = endpoint.isNotBlank()

    // 每一筆的精度隨座標帶在 Coordinate.accuracyMeters，這裡不預先假設一個固定值。
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
                parseLocation(payload, maxAgeMillis)
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
        @SerialName("accuracy_m") val accuracyM: Double? = null,
        @SerialName("age_ms") val ageMs: Long? = null,
    )

    companion object {
        /** 超過這個年齡的座標就丟掉（企畫書 P1：眼鏡丟棄超過 5 秒的舊座標）。 */
        const val DEFAULT_MAX_AGE_MILLIS = 5_000L

        private val json = Json { ignoreUnknownKeys = true }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

        /**
         * `/current-location` 的回應 → 座標。還沒有人回報、格式不對、或太舊都回 null。
         *
         * @param now 眼鏡本地時間，只用來把年齡換算成 [Coordinate.timestampMillis]；
         *   新舊判斷只看後端給的 `age_ms`，不拿本地時鐘跟別的裝置比。
         */
        internal fun parseLocation(
            payload: String,
            maxAgeMillis: Long,
            now: () -> Long = System::currentTimeMillis,
        ): Coordinate? {
            val decoded = runCatching { json.decodeFromString<LocationResponse>(payload) }
                .getOrNull() ?: return null
            if (!decoded.success || (decoded.lat == 0.0 && decoded.lng == 0.0)) return null

            val ageMs = decoded.ageMs
            if (ageMs != null && ageMs > maxAgeMillis) return null

            return Coordinate(
                latitude = decoded.lat,
                longitude = decoded.lng,
                accuracyMeters = decoded.accuracyM?.toFloat(),
                timestampMillis = now() - (ageMs ?: 0L),
            )
        }
    }
}
