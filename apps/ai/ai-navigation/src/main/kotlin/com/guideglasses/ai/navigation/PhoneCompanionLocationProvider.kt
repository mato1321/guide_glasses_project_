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
 * ## 兩個來源，先問手機
 *
 * 1. **手機直連**（[directEndpoint]）：眼鏡連著手機熱點時，直接問手機上的
 *    `LocalLocationServer`。只隔一層區網，而且 4G 或後端斷掉時照樣拿得到位置 ——
 *    步行導航用已下載的路線還能繼續走
 * 2. **經由後端**（[endpoint]）：手機把座標 POST 給後端，這裡 GET 回來。
 *    眼鏡接的是一般路由器、或手機直連伺服器沒開時用這條
 *
 * 兩邊回應格式相同。直連**連續 [DIRECT_FAILURES_BEFORE_BACKOFF] 次連不上**時，
 * [DIRECT_RETRY_MILLIS] 內不再試，免得每次輪詢都先卡在連線逾時。偶發一次慢回應
 * 不算（實測經 USB 串接時 15 次裡有 2 次超過 1 秒；熱點 Wi-Fi 也會偶爾卡一下）——
 * 以前一次逾時就停用直連 10 秒，後端又剛好斷線時，導航就說「收不到手機定位」。
 * 連得上但座標太舊也不算失敗，下一輪照樣先問手機。
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
    /** 手機直連的網址（`http://<手機IP>:<埠>`）；null 代表不用直連，每次輪詢都會重新問一次。 */
    private val directEndpoint: (() -> String?)? = null,
    private val directClient: OkHttpClient = directDefaultClient(),
    /** 定位來源改變時通知（給 log 用），例如「手機直連」→「經由後端」。 */
    private val onSourceChanged: (String) -> Unit = {},
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : LocationProvider {

    override val isAvailable: Boolean get() = endpoint.isNotBlank() || directEndpoint != null

    // 每一筆的精度隨座標帶在 Coordinate.accuracyMeters，這裡不預先假設一個固定值。
    override val accuracyMeters: Float? = null

    @Volatile
    private var directRetryAtMillis = 0L

    @Volatile
    private var directFailures = 0

    @Volatile
    private var lastSource: String? = null

    override fun locations(): Flow<Coordinate> = flow {
        while (true) {
            fetchOnce()?.let { emit(it) }
            delay(pollIntervalMillis)
        }
    }

    private suspend fun fetchOnce(): Coordinate? = withContext(ioDispatcher) {
        fetchDirect()?.let { coordinate ->
            reportSource(SOURCE_DIRECT)
            return@withContext coordinate
        }
        if (endpoint.isBlank()) return@withContext null
        (fetchFrom(endpoint, client) as? Fetch.Reached)?.coordinate?.also { reportSource(SOURCE_RELAY) }
    }

    private fun fetchDirect(): Coordinate? {
        val base = directEndpoint?.invoke()?.takeIf { it.isNotBlank() } ?: return null
        if (monotonicMillis() < directRetryAtMillis) return null
        return when (val result = fetchFrom(base, directClient)) {
            is Fetch.Reached -> {
                directFailures = 0
                result.coordinate
            }
            Fetch.Unreachable -> {
                if (++directFailures >= DIRECT_FAILURES_BEFORE_BACKOFF) {
                    directFailures = 0
                    directRetryAtMillis = monotonicMillis() + DIRECT_RETRY_MILLIS
                }
                null
            }
        }
    }

    private fun fetchFrom(base: String, httpClient: OkHttpClient): Fetch =
        try {
            val request = Request.Builder().url("$base/current-location").get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return Fetch.Unreachable
                val payload = response.body?.string() ?: return Fetch.Unreachable
                Fetch.Reached(parseLocation(payload, maxAgeMillis))
            }
        } catch (e: IOException) {
            Fetch.Unreachable
        }

    private fun reportSource(source: String) {
        if (lastSource == source) return
        lastSource = source
        onSourceChanged(source)
    }

    /** 一次查詢的結果：連得上（座標可能因為太舊而是 null），或連不上。 */
    private sealed interface Fetch {
        data class Reached(val coordinate: Coordinate?) : Fetch
        data object Unreachable : Fetch
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
        /**
         * 超過這個年齡的座標就丟掉。
         *
         * 企畫書 P1 寫 5 秒，前提是戶外 GPS 每秒一筆。眼鏡實測（2026-09-30）：室內只有
         * 網路定位，約 10 秒更新一次，而且**每一筆送到手機時就已經是 7.5 秒前的位置**
         * （連續取樣 age_ms：8.0／12.3／7.5／12.5／7.8／12.8 秒）—— 5 秒會把室內的
         * 每一筆都丟掉，查公車永遠「拿不到定位」。
         *
         * 這道門檻要擋的是手機凍結、斷線造成的舊座標，那種年齡會一路漲到幾分鐘，
         * 15 秒一樣擋得住；戶外有 GPS 時年齡約 1 秒，不受影響。
         */
        const val DEFAULT_MAX_AGE_MILLIS = 15_000L

        /** 直連連續失敗幾次才暫停，見類別說明。 */
        const val DIRECT_FAILURES_BEFORE_BACKOFF = 3

        /** 直連暫停之後，多久再試一次。 */
        const val DIRECT_RETRY_MILLIS = 10_000L

        const val SOURCE_DIRECT = "手機直連"
        const val SOURCE_RELAY = "經由後端"

        private val json = Json { ignoreUnknownKeys = true }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

        /**
         * 直連只隔一層熱點區網，正常是毫秒級（實測中位數 16 ms）。
         *
         * 連線逾時設短：眼鏡接的是一般路由器時閘道不是手機，要盡快放棄改走後端。
         * 讀取逾時留 2 秒：連得上就代表是手機，偶爾慢一下（實測最慢 1.9 秒）比放棄好。
         */
        fun directDefaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
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
