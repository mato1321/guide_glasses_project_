package com.guideglasses.core.domain.glasses

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FrameRateLimiterTest {

    @Test
    fun `第一幀一定被接受`() {
        val limiter = FrameRateLimiter(targetFps = 2f)

        assertThat(limiter.shouldAccept(0L)).isTrue()
    }

    @Test
    fun `2fps 時間隔未滿 500ms 的幀會被丟棄`() {
        val limiter = FrameRateLimiter(targetFps = 2f)

        assertThat(limiter.shouldAccept(0L)).isTrue()
        assertThat(limiter.shouldAccept(100L)).isFalse()
        assertThat(limiter.shouldAccept(499L)).isFalse()
        assertThat(limiter.shouldAccept(500L)).isTrue()
    }

    @Test
    fun `被丟棄的幀不會更新時間基準`() {
        val limiter = FrameRateLimiter(targetFps = 2f)

        limiter.shouldAccept(0L)
        limiter.shouldAccept(300L) // 丟棄
        limiter.shouldAccept(400L) // 丟棄

        // 若丟棄的幀有更新基準，這裡會是 false
        assertThat(limiter.shouldAccept(500L)).isTrue()
    }

    @Test
    fun `模擬 30fps 進來 2fps 出去`() {
        val limiter = FrameRateLimiter(targetFps = 2f)
        var accepted = 0

        // 相機以 30fps 送 3 秒 = 90 幀
        for (i in 0 until 90) {
            if (limiter.shouldAccept(i * 1000L / 30)) accepted++
        }

        // 3 秒 @ 2fps 應該是 6 幀左右
        assertThat(accepted).isIn(5..7)
    }

    @Test
    fun `高幀率設定也能正確節流`() {
        val limiter = FrameRateLimiter(targetFps = 10f)

        assertThat(limiter.shouldAccept(0L)).isTrue()
        assertThat(limiter.shouldAccept(99L)).isFalse()
        assertThat(limiter.shouldAccept(100L)).isTrue()
    }

    @Test
    fun `reset 之後下一幀無條件接受`() {
        val limiter = FrameRateLimiter(targetFps = 1f)

        limiter.shouldAccept(0L)
        assertThat(limiter.shouldAccept(10L)).isFalse()

        limiter.reset()

        assertThat(limiter.shouldAccept(20L)).isTrue()
    }

    @Test
    fun `targetFps 必須大於 0`() {
        val error = runCatching { FrameRateLimiter(targetFps = 0f) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `極高的 targetFps 不會讓間隔變成 0`() {
        // 1000fps -> 間隔 1ms，不可以是 0 否則永遠不節流
        val limiter = FrameRateLimiter(targetFps = 10_000f)

        assertThat(limiter.shouldAccept(0L)).isTrue()
        assertThat(limiter.shouldAccept(0L)).isFalse()
        assertThat(limiter.shouldAccept(1L)).isTrue()
    }
}

class ResolutionPlannerTest {

    @Test
    fun `橫向影像依長邊縮放並維持比例`() {
        val (w, h) = ResolutionPlanner.scaleToLongEdge(1920, 1080, 640)

        assertThat(w).isEqualTo(640)
        assertThat(h).isEqualTo(360)
    }

    @Test
    fun `直向影像依長邊縮放`() {
        val (w, h) = ResolutionPlanner.scaleToLongEdge(1080, 1920, 640)

        assertThat(w).isEqualTo(360)
        assertThat(h).isEqualTo(640)
    }

    @Test
    fun `已經小於目標時不放大`() {
        val (w, h) = ResolutionPlanner.scaleToLongEdge(320, 240, 640)

        assertThat(w).isEqualTo(320)
        assertThat(h).isEqualTo(240)
    }

    @Test
    fun `剛好等於目標時不變`() {
        val (w, h) = ResolutionPlanner.scaleToLongEdge(640, 480, 640)

        assertThat(w).isEqualTo(640)
        assertThat(h).isEqualTo(480)
    }

    @Test
    fun `極端長寬比不會縮到 0`() {
        val (w, h) = ResolutionPlanner.scaleToLongEdge(4000, 3, 640)

        assertThat(w).isEqualTo(640)
        assertThat(h).isAtLeast(1)
    }

    @Test
    fun `4比3 常見尺寸`() {
        val (w, h) = ResolutionPlanner.scaleToLongEdge(1280, 960, 640)

        assertThat(w).isEqualTo(640)
        assertThat(h).isEqualTo(480)
    }

    @Test
    fun `非法輸入會被拒絕`() {
        assertThat(
            runCatching { ResolutionPlanner.scaleToLongEdge(0, 480, 640) }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)

        assertThat(
            runCatching { ResolutionPlanner.scaleToLongEdge(640, 480, 0) }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
    }
}
