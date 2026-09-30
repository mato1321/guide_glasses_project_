package com.guideglasses.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 眼鏡直連時手機回的內容：格式要與後端 /current-location 相同，眼鏡才能共用解析。 */
class LocationHttpResponderTest {

    private val key = "test-key"
    private val fix = LocationHttpResponder.Fix(lat = 25.0330761, lng = 121.5645082, accuracyM = 16.2f, elapsedRealtimeNanos = 10_000_000_000L)

    private fun respond(
        method: String = "GET",
        path: String = "/current-location",
        apiKey: String? = key,
        fix: LocationHttpResponder.Fix? = this.fix,
        nowNanos: Long = 10_320_000_000L,
    ) = LocationHttpResponder.respond(method, path, apiKey, key, fix, nowNanos)

    @Test
    fun `回傳與後端相同格式的座標，age_ms 用手機自己的開機時間算`() {
        val reply = respond()

        assertEquals(200, reply.status)
        assertEquals("""{"success":true,"lat":25.0330761,"lng":121.5645082,"accuracy_m":16.2,"age_ms":320}""", reply.body)
    }

    @Test
    fun `還沒有定位時回 0,0 與 null，跟後端還沒人回報時一樣`() {
        val reply = respond(fix = null)

        assertEquals(200, reply.status)
        assertTrue(reply.body.contains("\"lat\":0.0"))
        assertTrue(reply.body.contains("\"age_ms\":null"))
    }

    @Test
    fun `沒帶或帶錯金鑰回 401 —— 同熱點的其他裝置不能讀位置`() {
        assertEquals(401, respond(apiKey = null).status)
        assertEquals(401, respond(apiKey = "wrong").status)
    }

    @Test
    fun `health 不用金鑰，讓眼鏡先判斷連不連得到手機`() {
        assertEquals(200, respond(path = "/health", apiKey = null).status)
    }

    @Test
    fun `查詢字串不影響路徑判斷，其他路徑 404、非 GET 405`() {
        assertEquals(200, respond(path = "/current-location?t=1").status)
        assertEquals(404, respond(path = "/update-location").status)
        assertEquals(405, respond(method = "POST").status)
    }

    @Test
    fun `手機沒設定金鑰時不驗證`() {
        val reply = LocationHttpResponder.respond("GET", "/current-location", null, "", fix, 10_320_000_000L)
        assertEquals(200, reply.status)
    }
}
