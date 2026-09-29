package com.guideglasses.core.domain.bus

import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.glasses.CameraFrame
import com.guideglasses.core.domain.glasses.CaptureRequest
import com.guideglasses.core.domain.glasses.FrameSource

/**
 * 拍照確認眼前的公車是否是要搭乘的班次。
 *
 * 沿用眼鏡既有的相機管線（[FrameSource]），不是舊原型 `bus_ocr.java`
 * 那樣自己重寫一份 Camera2 —— 端側擷取邏輯只該有一份，見
 * [com.guideglasses.core.domain.obstacle.DetectObstaclesUseCase] 同樣的形狀。
 */
class ConfirmBusUseCase(
    private val frameSource: FrameSource,
    private val ocrGateway: BusOcrGateway,
) {

    suspend fun execute(plan: BusPlan, eta: BusEta): Outcome {
        if (!ocrGateway.isAvailable) return Outcome.Unavailable

        val frame = when (val result = frameSource.captureOnce(CAPTURE_REQUEST)) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return Outcome.Failed(result.error)
        }

        return when (
            val result = ocrGateway.confirmBus(
                frame = frame,
                busNumber = plan.busNumber,
                stopId = eta.stopId,
                direction = eta.direction,
            )
        ) {
            is AppResult.Success -> Outcome.Recognized(result.data)
            is AppResult.Failure -> Outcome.Failed(result.error)
        }
    }

    sealed interface Outcome {
        data class Recognized(val result: BusOcrResult) : Outcome
        data object Unavailable : Outcome
        data class Failed(val error: AppError) : Outcome
    }

    companion object {
        /** 車頭號碼／LED 顯示文字較細，用 OCR 等級解析度而非障礙物的 640。 */
        val CAPTURE_REQUEST = CaptureRequest(
            targetFps = 1f,
            longEdgePixels = 1280,
            outputFormat = CameraFrame.Format.JPEG,
            jpegQuality = 90,
        )
    }
}
