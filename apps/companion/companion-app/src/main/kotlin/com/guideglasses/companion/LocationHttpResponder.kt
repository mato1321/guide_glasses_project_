package com.guideglasses.companion

import java.security.MessageDigest
import java.util.Locale

/**
 * 眼鏡直連時要回什麼（純邏輯，不碰 socket，方便在 JVM 上測試）。
 *
 * 格式刻意與後端 `GET /current-location` 相同 —— 眼鏡的 `PhoneCompanionLocationProvider`
 * 用同一套解析與「丟掉過舊座標」的判斷，不用分辨回應是手機還是後端給的：
 *
 * ```
 * {"success":true,"lat":25.03,"lng":121.56,"accuracy_m":16.2,"age_ms":320}
 * ```
 */
internal object LocationHttpResponder {

    /** 最近一筆定位。[elapsedRealtimeNanos] 是 `Location.getElapsedRealtimeNanos()`。 */
    data class Fix(val lat: Double, val lng: Double, val accuracyM: Float, val elapsedRealtimeNanos: Long)

    data class Reply(val status: Int, val body: String)

    /**
     * @param apiKeyHeader 請求帶的 `X-Api-Key`，沒帶是 null。
     * @param expectedKey 這支手機編進 APK 的金鑰；空字串代表不驗證。
     * @param nowElapsedNanos 現在的 `SystemClock.elapsedRealtimeNanos()`，用來算 `age_ms`。
     */
    fun respond(
        method: String,
        path: String,
        apiKeyHeader: String?,
        expectedKey: String,
        fix: Fix?,
        nowElapsedNanos: Long,
    ): Reply {
        val route = path.substringBefore('?')
        if (method != "GET") return Reply(405, """{"success":false,"message":"只支援 GET"}""")

        // 跟後端一樣，/health 不用金鑰：讓眼鏡能先判斷「連不連得到手機」。
        if (route == "/health") return Reply(200, """{"status":"ok"}""")
        if (route != "/current-location") return Reply(404, """{"success":false,"message":"not found"}""")

        // 同一個熱點底下的其他裝置也連得到這個埠 —— 位置是個資，要金鑰才給。
        if (expectedKey.isNotEmpty() && !sameKey(apiKeyHeader, expectedKey)) {
            return Reply(401, """{"success":false,"message":"缺少或錯誤的 X-Api-Key"}""")
        }

        if (fix == null) {
            return Reply(200, """{"success":true,"lat":0.0,"lng":0.0,"accuracy_m":null,"age_ms":null}""")
        }
        val ageMs = ((nowElapsedNanos - fix.elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0)
        // 手寫 JSON：只有數字，不必為此在 JVM 測試裡拉 org.json（Android stub 在單元測試會丟例外）。
        val body = String.format(
            Locale.US,
            """{"success":true,"lat":%.7f,"lng":%.7f,"accuracy_m":%.1f,"age_ms":%d}""",
            fix.lat, fix.lng, fix.accuracyM, ageMs,
        )
        return Reply(200, body)
    }

    /** 固定時間比較，避免從回應時間逐字元猜出金鑰。 */
    private fun sameKey(provided: String?, expected: String): Boolean =
        provided != null && MessageDigest.isEqual(provided.toByteArray(), expected.toByteArray())
}
