package com.guideglasses.core.domain.glasses

import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult

/**
 * 相機自我檢測。
 *
 * 存在的理由很實際：眼鏡戴在頭上，出門實測時拿不到 logcat。這個 UseCase
 * 擷取一張影像，把「通不通、多大、多快」直接用語音講出來。
 *
 * 它同時也是 `docs/08` 建議的延遲量測手段的第一段 —— 先量到「擷取 + 轉檔」
 * 要多久，才知道後面的網路與推論該不該優化。
 */
class CameraSelfTestUseCase(
    private val frameSource: FrameSource,
    private val now: () -> Long = System::currentTimeMillis,
) {

    suspend fun execute(request: CaptureRequest = DEFAULT_REQUEST): Report {
        val startedAt = now()
        val result = frameSource.captureOnce(request)
        val elapsed = now() - startedAt

        return when (result) {
            is AppResult.Success -> Report.Success(
                width = result.data.width,
                height = result.data.height,
                byteCount = result.data.bytes.size,
                format = result.data.format,
                elapsedMillis = elapsed,
            )

            is AppResult.Failure -> Report.Failure(result.error, elapsed)
        }
    }

    sealed interface Report {

        /** 給使用者聽的一句話。刻意不含技術術語與錯誤碼。 */
        val spoken: String

        data class Success(
            val width: Int,
            val height: Int,
            val byteCount: Int,
            val format: CameraFrame.Format,
            val elapsedMillis: Long,
        ) : Report {
            val kilobytes: Int get() = byteCount / 1024

            override val spoken: String
                get() = "相機正常。解析度 $width 乘 $height，" +
                    "影像 $kilobytes KB，耗時 $elapsedMillis 毫秒"
        }

        data class Failure(
            val error: AppError,
            val elapsedMillis: Long,
        ) : Report {
            override val spoken: String
                get() = when (error) {
                    is AppError.PermissionDenied -> "沒有相機權限，請到設定中開啟"
                    is AppError.CapabilityUnavailable -> "這台裝置的相機無法使用"
                    is AppError.NoResult -> "相機沒有回傳影像"
                    else -> "相機測試失敗"
                }
        }
    }

    companion object {
        /**
         * 自我檢測用的預設參數。
         *
         * 640 長邊、JPEG 80 —— 刻意與障礙物偵測的實際設定一致，
         * 這樣量到的耗時才代表真實情況，而不是一個漂亮但無意義的數字。
         */
        val DEFAULT_REQUEST = CaptureRequest(
            targetFps = 1f,
            longEdgePixels = 640,
            outputFormat = CameraFrame.Format.JPEG,
            jpegQuality = 80,
        )
    }
}
