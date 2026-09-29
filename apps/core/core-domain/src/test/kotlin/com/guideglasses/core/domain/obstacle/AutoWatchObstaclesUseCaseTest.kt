package com.guideglasses.core.domain.obstacle

import com.google.common.truth.Truth.assertThat
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.glasses.CameraFrame
import com.guideglasses.core.domain.glasses.CaptureRequest
import com.guideglasses.core.domain.glasses.FrameSource
import com.guideglasses.core.domain.motion.MotionSensorGateway
import com.guideglasses.core.domain.motion.SensorCapabilities
import com.guideglasses.core.domain.motion.WalkingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi :: class)
class AutoWatchObstaclesUseCaseTest {

    /** 依序吐出固定的走路狀態，然後結束。 */
    private class FakeMotionSensorGateway(
        private val states: List<WalkingState>,
    ) : MotionSensorGateway {
        override val capabilities = SensorCapabilities.NONE
        override fun walkingState(): Flow<WalkingState> = flow { states.forEach { emit(it) } }
        override fun relativeHeading(): Flow<Float> = flow {}
        override fun resetHeadingReference() = Unit
        override fun stepCount(): Flow<Long>? = null
    }

    /** 記下 frames() 被呼叫幾次，用來驗證「站著時相機真的沒被打開」。 */
    private class FakeFrameSource(private val framesPerCall: Int) : FrameSource {
        var callCount = 0
            private set

        override fun frames(request: CaptureRequest): Flow<AppResult<CameraFrame>> = flow {
            callCount++
            repeat(framesPerCall) { i ->
                emit(AppResult.Success(CameraFrame(ByteArray(0), CameraFrame.Format.RGBA_8888, 640, 640, 0, i.toLong())))
            }
        }

        override suspend fun captureOnce(request: CaptureRequest): AppResult<CameraFrame> =
            AppResult.Success(CameraFrame(ByteArray(0), CameraFrame.Format.RGBA_8888, 640, 640, 0, 0L))
    }

    private class FakeDetector(private val detections: List<Detection>) : ObstacleDetector {
        override val isAvailable = true
        override suspend fun detect(frame: CameraFrame): AppResult<List<Detection>> =
            AppResult.Success(detections)
    }

    private fun closeCar() = Detection(
        type = ObstacleClass.CAR, left = 0.25f, top = 0.4f, width = 0.5f, height = 0.3f, confidence = 0.9f,
    )

    @Test
    fun `走路時會產生障礙物播報`() = runTest {
        val frameSource = FakeFrameSource(framesPerCall = 1)
        val watch = WatchObstaclesUseCase(frameSource, FakeDetector(listOf(closeCar())))
        val auto = AutoWatchObstaclesUseCase(FakeMotionSensorGateway(listOf(WalkingState.WALKING)), watch)

        val announcements = auto.run().toList()

        assertThat(announcements).hasSize(1)
        assertThat(frameSource.callCount).isEqualTo(1)
    }

    @Test
    fun `站著不動時完全不會碰相機`() = runTest {
        val frameSource = FakeFrameSource(framesPerCall = 1)
        val watch = WatchObstaclesUseCase(frameSource, FakeDetector(listOf(closeCar())))
        val auto = AutoWatchObstaclesUseCase(FakeMotionSensorGateway(listOf(WalkingState.STILL)), watch)

        val announcements = auto.run().toList()

        assertThat(announcements).isEmpty()
        assertThat(frameSource.callCount).isEqualTo(0)
    }

    @Test
    fun `感測器沒有訊號時不會主動開相機`() = runTest {
        val frameSource = FakeFrameSource(framesPerCall = 1)
        val watch = WatchObstaclesUseCase(frameSource, FakeDetector(listOf(closeCar())))
        // 關鍵差異：emptyList() 讓 FakeMotionSensorGateway 的 walkingState()
        // 一個狀態都不吐、直接結束 —— 模擬「這台裝置偵測不到走路狀態」。
        val auto = AutoWatchObstaclesUseCase(FakeMotionSensorGateway(emptyList()), watch)

        val announcements = auto.run().toList()

        assertThat(announcements).isEmpty()
        assertThat(frameSource.callCount).isEqualTo(0)
    }
}