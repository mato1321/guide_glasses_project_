package com.guideglasses.core.domain.obstacle

import com.google.common.truth.Truth.assertThat
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.glasses.CameraFrame
import com.guideglasses.core.domain.glasses.CaptureRequest
import com.guideglasses.core.domain.glasses.FrameSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WatchObstaclesUseCaseTest {

    /** 依序吐出固定張數的影像，然後結束。真正的相機串流不會結束，但測試要有終點。 */
    private class FakeFrameSource(private val count: Int) : FrameSource {
        override fun frames(request: CaptureRequest): Flow<AppResult<CameraFrame>> = flow {
            repeat(count) { index -> emit(AppResult.Success(frame(index.toLong()))) }
        }

        override suspend fun captureOnce(request: CaptureRequest): AppResult<CameraFrame> =
            AppResult.Success(frame(0L))

        private fun frame(ts: Long) =
            CameraFrame(ByteArray(0), CameraFrame.Format.RGBA_8888, 640, 640, 0, ts)
    }

    /** 每一張都回傳同一組偵測結果。 */
    private class FakeDetector(
        override val isAvailable: Boolean = true,
        private val detections: List<Detection> = emptyList(),
    ) : ObstacleDetector {
        override suspend fun detect(frame: CameraFrame): AppResult<List<Detection>> =
            AppResult.Success(detections)
    }

    private fun car(width: Float, centerX: Float = 0.5f) = Detection(
        type = ObstacleClass.CAR,
        left = centerX - width / 2f,
        top = 0.4f,
        width = width,
        height = 0.3f,
        confidence = 0.9f,
    )

    private fun guideBrick(centerX: Float) = Detection(
        type = ObstacleClass.GUIDE_BRICK,
        left = centerX - 0.05f,
        top = 0.7f,
        width = 0.1f,
        height = 0.1f,
        confidence = 0.8f,
    )

    private fun useCase(
        frames: Int,
        detections: List<Detection>,
        detectorAvailable: Boolean = true,
        // 時鐘固定在 0：同一個物體在整個測試裡都被視為「剛剛才講過」。
        debouncer: ObstacleDebouncer = ObstacleDebouncer(now = { 0L }),
        pathAlignmentDebouncer: PathAlignmentDebouncer = PathAlignmentDebouncer(now = { 0L }),
    ) = WatchObstaclesUseCase(
        frameSource = FakeFrameSource(frames),
        detector = FakeDetector(detectorAvailable, detections),
        debouncer = debouncer,
        pathAlignmentDebouncer = pathAlignmentDebouncer,
    )

    @Test
    fun `近處的車會播報一次`() = runTest {
        // 佔畫面一半的車 ≈ 2-3 公尺，落在會播報的範圍。
        val announcements = useCase(frames = 1, detections = listOf(car(width = 0.5f)))
            .watch().toList()

        assertThat(announcements).hasSize(1)
        assertThat(announcements.first().text).contains("車輛")
    }

    @Test
    fun `連續多張、同一台車只播一次`() = runTest {
        val announcements = useCase(frames = 10, detections = listOf(car(width = 0.5f)))
            .watch().toList()

        // 去抖動時鐘固定，10 張裡只有第一張會過。沒有這層，走路時同一台車
        // 會被唸幾十遍，功能直接不能用。
        assertThat(announcements).hasSize(1)
    }

    @Test
    fun `太遠的車不主動播報`() = runTest {
        // 佔畫面 5% 的車 ≈ 20 公尺以外。使用者沒問就不該一直有聲音。
        val announcements = useCase(frames = 3, detections = listOf(car(width = 0.05f)))
            .watch().toList()

        assertThat(announcements).isEmpty()
    }

    @Test
    fun `斑馬線這類導引物在持續模式不播`() = runTest {
        val crosswalk = Detection(
            type = ObstacleClass.CROSSWALK,
            left = 0.3f, top = 0.6f, width = 0.4f, height = 0.2f, confidence = 0.8f,
        )
        val announcements = useCase(frames = 3, detections = listOf(crosswalk))
            .watch().toList()

        assertThat(announcements).isEmpty()
    }

    @Test
    fun `沒有模型時不做任何事`() = runTest {
        val announcements = useCase(
            frames = 5,
            detections = listOf(car(width = 0.5f)),
            detectorAvailable = false,
        ).watch().toList()

        assertThat(announcements).isEmpty()
    }

    @Test
    fun `一張影像最多播報兩個`() = runTest {
        val threeCars = listOf(
            car(width = 0.50f, centerX = 0.2f),
            car(width = 0.45f, centerX = 0.5f),
            car(width = 0.40f, centerX = 0.8f),
        )
        val announcements = useCase(frames = 1, detections = threeCars).watch().toList()

        assertThat(announcements).hasSize(2)
    }

    // ===== 路徑置中提醒（導盲磚／斑馬線／人行道） =====

    @Test
    fun `導盲磚置中時不會有路徑提醒`() = runTest {
        val announcements = useCase(frames = 1, detections = listOf(guideBrick(centerX = 0.5f)))
            .watch().toList()

        assertThat(announcements).isEmpty()
    }

    @Test
    fun `導盲磚偏左時提醒往左靠`() = runTest {
        // centerX 落在左三分之一，代表導盲磚在使用者左邊、使用者偏到了右邊。
        val announcements = useCase(frames = 1, detections = listOf(guideBrick(centerX = 0.15f)))
            .watch().toList()

        assertThat(announcements).hasSize(1)
        assertThat(announcements.first().text).contains("左")
    }

    @Test
    fun `導盲磚偏右時提醒往右靠`() = runTest {
        val announcements = useCase(frames = 1, detections = listOf(guideBrick(centerX = 0.85f)))
            .watch().toList()

        assertThat(announcements).hasSize(1)
        assertThat(announcements.first().text).contains("右")
    }

    @Test
    fun `持續偏同一邊在冷卻時間內只提醒一次`() = runTest {
        // 時鐘固定、連續兩張影格都偏左——狀態沒變，冷卻時間內不該重複提醒。
        val announcements = useCase(frames = 2, detections = listOf(guideBrick(centerX = 0.15f)))
            .watch().toList()

        assertThat(announcements).hasSize(1)
    }

    @Test
    fun `障礙物警告與路徑置中提醒會同時出現`() = runTest {
        // 同一張影格裡，車是危險要警告，偏移的導盲磚是另一件事要提醒——
        // 兩者問的問題不同，應該都播，不是互斥的。
        val announcements = useCase(
            frames = 1,
            detections = listOf(car(width = 0.5f), guideBrick(centerX = 0.15f)),
        ).watch().toList()

        assertThat(announcements).hasSize(2)
        assertThat(announcements.any { it.text.contains("車輛") }).isTrue()
        assertThat(announcements.any { it.text.contains("導盲磚") }).isTrue()
    }
}
