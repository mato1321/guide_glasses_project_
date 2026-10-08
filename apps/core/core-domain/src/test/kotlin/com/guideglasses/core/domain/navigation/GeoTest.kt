package com.guideglasses.core.domain.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GeoTest {

    // 台北車站與台北101。Haversine 算出的直線距離約 5.03 公里。
    private val taipeiMain = Coordinate(25.0478, 121.5170)
    private val taipei101 = Coordinate(25.0339, 121.5645)

    /**
     * 守的是「沒有用平面近似算球面距離」。台灣緯度下 1 度經度約 101 公里、
     * 1 度緯度約 111 公里，直接當平面算在市區尺度就會偏掉數百公尺。
     */
    @Test
    fun `距離計算落在合理範圍`() {
        val meters = Geo.distanceMeters(taipeiMain, taipei101)
        assertThat(meters).isGreaterThan(4_800.0)
        assertThat(meters).isLessThan(5_300.0)
    }

    @Test
    fun `距離對稱`() {
        assertThat(Geo.distanceMeters(taipeiMain, taipei101))
            .isWithin(0.01).of(Geo.distanceMeters(taipei101, taipeiMain))
    }

    @Test
    fun `同一點距離為零`() {
        assertThat(Geo.distanceMeters(taipeiMain, taipeiMain)).isWithin(0.01).of(0.0)
    }

    @Test
    fun `正北方的方位角接近零`() {
        val north = Coordinate(taipeiMain.latitude + 0.01, taipeiMain.longitude)
        assertThat(Geo.bearingDegrees(taipeiMain, north)).isWithin(1.0).of(0.0)
    }

    @Test
    fun `正東方的方位角接近九十度`() {
        val east = Coordinate(taipeiMain.latitude, taipeiMain.longitude + 0.01)
        assertThat(Geo.bearingDegrees(taipeiMain, east)).isWithin(1.0).of(90.0)
    }

    @Test
    fun `台北車站到101大致朝東南`() {
        val bearing = Geo.bearingDegrees(taipeiMain, taipei101)
        assertThat(bearing).isGreaterThan(90.0)
        assertThat(bearing).isLessThan(180.0)
    }

    // ===== 相對轉向：跨零度是最容易寫錯的地方 =====

    @Test
    fun `朝北時目標在東邊要右轉`() {
        assertThat(Geo.relativeTurn(headingDegrees = 0.0, targetBearingDegrees = 90.0))
            .isWithin(0.01).of(90.0)
    }

    @Test
    fun `朝北時目標在西北要左轉一點而不是右轉一大圈`() {
        // 350 度看似「右轉 350」，實際上是「左轉 10」。
        assertThat(Geo.relativeTurn(headingDegrees = 0.0, targetBearingDegrees = 350.0))
            .isWithin(0.01).of(-10.0)
    }

    @Test
    fun `朝西北時目標在正北要右轉一點`() {
        assertThat(Geo.relativeTurn(headingDegrees = 350.0, targetBearingDegrees = 0.0))
            .isWithin(0.01).of(10.0)
    }

    @Test
    fun `正後方回傳一百八十度而不是負一百八十`() {
        assertThat(Geo.relativeTurn(headingDegrees = 0.0, targetBearingDegrees = 180.0))
            .isWithin(0.01).of(180.0)
    }

    @Test
    fun `轉向角永遠落在正負一百八十之間`() {
        for (heading in 0 until 360 step 7) {
            for (target in 0 until 360 step 11) {
                val turn = Geo.relativeTurn(heading.toDouble(), target.toDouble())
                assertThat(turn).isAtLeast(-180.0)
                assertThat(turn).isAtMost(180.0)
            }
        }
    }

    // ===== 口語 =====

    @Test
    fun `小角度說直走`() {
        assertThat(Geo.spokenTurn(0.0)).isEqualTo("直走")
        assertThat(Geo.spokenTurn(15.0)).isEqualTo("直走")
        assertThat(Geo.spokenTurn(-15.0)).isEqualTo("直走")
    }

    @Test
    fun `中角度說往前方偏左右`() {
        assertThat(Geo.spokenTurn(40.0)).isEqualTo("往右前方")
        assertThat(Geo.spokenTurn(-40.0)).isEqualTo("往左前方")
    }

    @Test
    fun `大角度說轉彎`() {
        assertThat(Geo.spokenTurn(90.0)).isEqualTo("右轉")
        assertThat(Geo.spokenTurn(-90.0)).isEqualTo("左轉")
    }

    @Test
    fun `接近反方向說請回頭`() {
        assertThat(Geo.spokenTurn(170.0)).isEqualTo("請回頭")
        assertThat(Geo.spokenTurn(-170.0)).isEqualTo("請回頭")
    }
}
