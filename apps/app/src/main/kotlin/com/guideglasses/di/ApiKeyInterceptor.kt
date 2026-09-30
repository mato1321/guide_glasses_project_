package com.guideglasses.di

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * 打到自家後端（`backend/`）的請求都帶上 `X-Api-Key`。
 *
 * 後端除了 `/health` 以外一律驗證，缺少或錯誤回 401（企畫書 P0）——
 * 後端經由 Cloudflare 通道公開之後，沒有這一層任何人拿到網址就能
 * 用光 OpenAI 與 Google Routes 的額度。
 *
 * ⚠️ 金鑰編進 APK，反編譯就拿得到。這道防線擋的是「路人掃到網址就能用」，
 * 擋不了刻意拆 APK 的人；後者要靠 Cloudflare 的流量限制，或改成每台裝置
 * 各自的權杖，之後再加。
 *
 * 只用在後端的閘道上，**不要**用在人臉註冊工具（`HttpPhotoSource`，另一個服務）。
 */
class ApiKeyInterceptor(private val apiKey: String) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (apiKey.isBlank()) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header(HEADER, apiKey).build())
    }

    companion object {
        const val HEADER = "X-Api-Key"

        /** 在閘道原本的 client 上加金鑰 —— 保留各自的逾時設定，不共用同一個 client。 */
        fun OkHttpClient.withApiKey(apiKey: String): OkHttpClient =
            newBuilder().addInterceptor(ApiKeyInterceptor(apiKey)).build()
    }
}
