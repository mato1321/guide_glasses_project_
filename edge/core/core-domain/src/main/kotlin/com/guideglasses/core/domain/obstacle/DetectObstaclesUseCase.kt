package com.guideglasses.core.domain.obstacle

import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.glasses.CameraFrame
import com.guideglasses.core.domain.glasses.CaptureRequest
import com.guideglasses.core.domain.glasses.FrameSource

/**
 * 拍照 → 偵測 → 組成一句話。
 *
 * 回應「前面有什麼」。與 OCR 的 [com.guideglasses.core.domain.ocr.ReadTextUseCase]
 * 同一個形狀：UseCase 只負責串接與決策，影像與推論都在介面後面。
 *
 * ## 為什麼解析度只有 640
 *
 * OCR 用 1280 是因為文字筆畫細，640 會糊掉。障礙物是車輛、行人這種大目標，
 * 640 綽綽有餘，而且**延遲直接影響安全** —— 使用者在走路，一秒的差別
 * 就是一公尺多。模型本身也是 640 輸入，送更大的圖只是多花時間縮放。
 */
class DetectObstaclesUseCase(
    private val frameSource: FrameSource,
    private val detector: ObstacleDetector,
    private val composer: ObstacleAnnouncementComposer = ObstacleAnnouncementComposer(),
) {

    suspend fun execute(): Outcome {
        if (!detector.isAvailable) return Outcome.Unavailable

        val frame = when (val result = frameSource.captureOnce(CAPTURE_REQUEST)) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return Outcome.Failed(result.error)
        }

        val detections = when (val result = detector.detect(frame)) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return Outcome.Failed(result.error)
        }

        if (detections.isEmpty()) return Outcome.NothingDetected

        return Outcome.Detected(
            detections = detections,
            spoken = composer.composeSummary(detections),
        )
    }

    sealed interface Outcome {

        data class Detected(
            val detections: List<Detection>,
            val spoken: String,
        ) : Outcome

        /** 拍到了，但畫面裡沒有任何一類目標。 */
        data object NothingDetected : Outcome

        /** 沒有模型檔。播報「功能不可用」而不是靜默。 */
        data object Unavailable : Outcome

        data class Failed(val error: AppError) : Outcome
    }

    companion object {
        /**
         * 障礙物用的擷取參數。
         *
         * 與 OCR（1280 / 品質 90）刻意不同，理由見類別註解。
         */
        val CAPTURE_REQUEST = CaptureRequest(
            targetFps = 1f,
            longEdgePixels = 640,
            outputFormat = CameraFrame.Format.JPEG,
            jpegQuality = 85,
        )
    }
}
