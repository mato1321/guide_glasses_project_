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
            is AppResult.Success -> Outcome.Recognized(result.data, spokenResult(result.data, plan.busNumber))
            is AppResult.Failure -> Outcome.Failed(result.error)
        }
    }

    sealed interface Outcome {
        /** @param spoken 要播報的句子，見 [spokenResult]。 */
        data class Recognized(val result: BusOcrResult, val spoken: String) : Outcome
        data object Unavailable : Outcome
        data class Failed(val error: AppError) : Outcome
    }

    companion object {
        /**
         * 比對結果 → 播報的句子。**不唸 [BusOcrResult.message]**。
         *
         * `message` 是後端給人看的除錯資訊，實測內容是
         * 「辨識成功，確認為 307，StopID=，Direction=-1」—— 照唸的話使用者會聽到
         * 「StopID 等於、Direction 等於負一」。跟 [PlanBusRouteUseCase] 不唸 `BusEta.message` 同一個理由。
         *
         * 用詞只說「號碼相符」：目前只比對車頭的路線號碼，沒有確認行駛方向，
         * 不能說成「這就是你要搭的那一班」。
         */
        fun spokenResult(result: BusOcrResult, busNumber: String): String {
            val bus = PlanBusRouteUseCase.spokenBusName(busNumber)
            return when {
                result.matched -> "辨識成功，車頭號碼與 $bus 相符。"
                result.recognizedText.isBlank() -> "看不清楚車頭號碼，請面向公車車頭，再說一次「確認公車」。"
                else -> "車頭號碼看起來不是 $bus。不確定的話，可以再說一次「確認公車」。"
            }
        }

        /** 車頭號碼／LED 顯示文字較細，用 OCR 等級解析度而非障礙物的 640。 */
        val CAPTURE_REQUEST = CaptureRequest(
            targetFps = 1f,
            longEdgePixels = 1280,
            outputFormat = CameraFrame.Format.JPEG,
            jpegQuality = 90,
        )
    }
}
