package com.guideglasses.core.domain.glasses

import com.google.common.truth.Truth.assertThat
import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CameraSelfTestUseCaseTest {

    private class FakeFrameSource(
        private val result: AppResult<CameraFrame>,
    ) : FrameSource {
        var lastRequest: CaptureRequest? = null

        override fun frames(request: CaptureRequest): Flow<AppResult<CameraFrame>> =
            flowOf(result)

        override suspend fun captureOnce(request: CaptureRequest): AppResult<CameraFrame> {
            lastRequest = request
            return result
        }
    }

    private fun frame(
        width: Int = 640,
        height: Int = 480,
        bytes: Int = 40 * 1024,
    ) = CameraFrame(
        bytes = ByteArray(bytes),
        format = CameraFrame.Format.JPEG,
        width = width,
        height = height,
        rotationDegrees = 0,
        timestampMillis = 0L,
    )

    @Test
    fun `成功時回報解析度大小與耗時`() = runTest {
        val source = FakeFrameSource(AppResult.Success(frame()))
        var clock = 1_000L
        val useCase = CameraSelfTestUseCase(source) { clock.also { clock += 120 } }

        val report = useCase.execute()

        assertThat(report).isInstanceOf(CameraSelfTestUseCase.Report.Success::class.java)
        val success = report as CameraSelfTestUseCase.Report.Success
        assertThat(success.width).isEqualTo(640)
        assertThat(success.height).isEqualTo(480)
        assertThat(success.kilobytes).isEqualTo(40)
        assertThat(success.elapsedMillis).isEqualTo(120)
    }

    @Test
    fun `成功的播報內容包含使用者需要的三個數字`() = runTest {
        val source = FakeFrameSource(AppResult.Success(frame()))
        var clock = 0L
        val useCase = CameraSelfTestUseCase(source) { clock.also { clock += 85 } }

        val spoken = useCase.execute().spoken

        assertThat(spoken).contains("640")
        assertThat(spoken).contains("480")
        assertThat(spoken).contains("40")
        assertThat(spoken).contains("85")
        assertThat(spoken).contains("相機正常")
    }

    @Test
    fun `沒有權限時說人話而不是唸錯誤碼`() = runTest {
        val source = FakeFrameSource(
            AppResult.Failure(AppError.PermissionDenied("android.permission.CAMERA")),
        )
        val useCase = CameraSelfTestUseCase(source) { 0L }

        val spoken = useCase.execute().spoken

        assertThat(spoken).contains("沒有相機權限")
        assertThat(spoken).doesNotContain("android.permission")
        assertThat(spoken).doesNotContain("Exception")
    }

    @Test
    fun `相機不可用時給明確說法`() = runTest {
        val source = FakeFrameSource(
            AppResult.Failure(AppError.CapabilityUnavailable("camera", "no camera on device")),
        )
        val useCase = CameraSelfTestUseCase(source) { 0L }

        val spoken = useCase.execute().spoken

        assertThat(spoken).contains("相機無法使用")
        assertThat(spoken).doesNotContain("no camera on device")
    }

    @Test
    fun `未知錯誤也不會洩漏技術訊息`() = runTest {
        val source = FakeFrameSource(AppResult.Failure(AppError.Unknown("NPE at line 42")))
        val useCase = CameraSelfTestUseCase(source) { 0L }

        val spoken = useCase.execute().spoken

        assertThat(spoken).isEqualTo("相機測試失敗")
        assertThat(spoken).doesNotContain("NPE")
    }

    @Test
    fun `預設參數與障礙物偵測的實際設定一致`() = runTest {
        val source = FakeFrameSource(AppResult.Success(frame()))
        val useCase = CameraSelfTestUseCase(source) { 0L }

        useCase.execute()

        val request = source.lastRequest!!
        assertThat(request.longEdgePixels).isEqualTo(640)
        assertThat(request.outputFormat).isEqualTo(CameraFrame.Format.JPEG)
        assertThat(request.jpegQuality).isEqualTo(80)
    }

    @Test
    fun `失敗時也會回報耗時`() = runTest {
        val source = FakeFrameSource(AppResult.Failure(AppError.NoResult()))
        var clock = 0L
        val useCase = CameraSelfTestUseCase(source) { clock.also { clock += 3_000 } }

        val report = useCase.execute() as CameraSelfTestUseCase.Report.Failure

        assertThat(report.elapsedMillis).isEqualTo(3_000)
    }
}
