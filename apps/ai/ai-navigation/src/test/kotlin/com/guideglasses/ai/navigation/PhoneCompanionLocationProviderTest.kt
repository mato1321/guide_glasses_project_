package com.guideglasses.ai.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `/current-location` 回應的解析，重點是丟掉過舊的座標（企畫書 P1）。
 *
 * 新舊只看後端給的 `age_ms`，不拿眼鏡的時鐘跟別的裝置比 ——
 * 眼鏡的時間實測快了 4 小時 15 分。
 */
class PhoneCompanionLocationProviderTest {

    private val maxAge = PhoneCompanionLocationProvider.DEFAULT_MAX_AGE_MILLIS
    private val now = { 1_000_000L }

    private fun parse(payload: String) = PhoneCompanionLocationProvider.parseLocation(payload, maxAge, now)

    @Test
    fun `新的座標照收，帶上精度，時間換算成眼鏡本地時間`() {
        val coordinate = parse("""{"success":true,"lat":25.03,"lng":121.56,"accuracy_m":16.2,"age_ms":1300}""")

        assertThat(coordinate).isNotNull()
        assertThat(coordinate!!.latitude).isEqualTo(25.03)
        assertThat(coordinate.accuracyMeters).isWithin(0.01f).of(16.2f)
        assertThat(coordinate.timestampMillis).isEqualTo(1_000_000L - 1300)
    }

    @Test
    fun `超過 15 秒的舊座標丟掉`() {
        assertThat(parse("""{"success":true,"lat":25.03,"lng":121.56,"age_ms":15001}""")).isNull()
        // 手機鎖螢幕被凍結後，後端還留著幾分鐘前的那一筆 —— 以前會被當成新座標。
        assertThat(parse("""{"success":true,"lat":25.03,"lng":121.56,"age_ms":180000}""")).isNull()
    }

    @Test
    fun `剛好 15 秒還算新`() {
        assertThat(parse("""{"success":true,"lat":25.03,"lng":121.56,"age_ms":15000}""")).isNotNull()
    }

    @Test
    fun `室內網路定位的年齡（實測 7點5 到 12點8 秒）要能通過`() {
        for (age in listOf(7_522, 12_813)) {
            assertThat(parse("""{"success":true,"lat":25.03,"lng":121.56,"age_ms":$age}""")).isNotNull()
        }
    }

    @Test
    fun `舊版後端沒有 age_ms 時無從判斷，照舊接受`() {
        val coordinate = parse("""{"success":true,"lat":25.03,"lng":121.56,"updated_at":1790740000.0}""")

        assertThat(coordinate).isNotNull()
        assertThat(coordinate!!.accuracyMeters).isNull()
    }

    @Test
    fun `還沒有手機回報過、格式不對、或失敗時回 null`() {
        assertThat(parse("""{"success":true,"lat":0.0,"lng":0.0,"updated_at":null,"age_ms":null}""")).isNull()
        assertThat(parse("""{"success":false,"lat":25.03,"lng":121.56}""")).isNull()
        assertThat(parse("not json")).isNull()
    }
}
