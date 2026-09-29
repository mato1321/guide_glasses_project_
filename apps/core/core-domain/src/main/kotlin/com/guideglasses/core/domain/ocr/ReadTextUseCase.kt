package com.guideglasses.core.domain.ocr

import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.glasses.CameraFrame
import com.guideglasses.core.domain.glasses.CaptureRequest
import com.guideglasses.core.domain.glasses.FrameSource

/**
 * 拍照 → 辨識 → 切段。
 *
 * 實作 `docs/03` 規劃的三層漸進策略的前兩層：
 *
 * 1. **端側 ML Kit** —— 約 100ms、免費、離線可用。90% 的日常情境
 *    （藥袋、菜單、門牌）到這裡就夠了。
 * 2. **雲端 OCR** —— 只在端側結果看起來不可靠、且雲端可用時才呼叫。
 *
 * 第三層（Vision LLM 理解式辨識）尚未實作。
 */
class ReadTextUseCase(
    private val frameSource: FrameSource,
    private val onDeviceRecognizer: TextRecognizer,
    private val cloudRecognizer: TextRecognizer? = null,
    private val segmenter: SpeechSegmenter = SpeechSegmenter(),
) {

    suspend fun execute(mode: OcrMode = OcrMode.DOCUMENT): Outcome {
        val frame = when (val result = frameSource.captureOnce(CAPTURE_REQUEST)) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return Outcome.Failed(result.error)
        }

        val recognized = recognizeWithFallback(frame)
            ?: return Outcome.Failed(AppError.NoResult("OCR 沒有回傳結果"))

        if (recognized.isEmpty) return Outcome.NoTextFound

        val text = when (mode) {
            OcrMode.DOCUMENT -> recognized.text
            OcrMode.SIGN -> recognized.largestText() ?: recognized.text
        }

        val segments = segmenter.segment(text)
        if (segments.isEmpty()) return Outcome.NoTextFound

        return Outcome.Success(
            session = ReadingSession(segments),
            source = recognized.source,
        )
    }

    /**
     * 端側優先，不可靠才往雲端。
     *
     * 端側失敗（例如模型還沒下載完）時直接嘗試雲端，而不是放棄 ——
     * 使用者只想聽到藥袋上寫什麼，不在乎是誰算出來的。
     */
    private suspend fun recognizeWithFallback(frame: CameraFrame): RecognizedText? {
        val onDevice = if (onDeviceRecognizer.isAvailable) {
            onDeviceRecognizer.recognize(frame).getOrNull()
        } else {
            null
        }

        if (onDevice != null && !onDevice.looksUnreliable()) return onDevice

        val cloud = cloudRecognizer
            ?.takeIf { it.isAvailable }
            ?.recognize(frame)
            ?.getOrNull()

        // 雲端也不行就退回端側的結果 —— 有東西總比沒有好。
        return cloud ?: onDevice
    }

    /**
     * 招牌模式：只取畫面中最大的那塊字。
     *
     * 站在路口時，畫面裡可能同時有店招、廣告、車牌、告示。全部唸出來
     * 只會讓人更困惑；使用者要的是「這裡是哪裡」。
     */
    private fun RecognizedText.largestText(): String? =
        blocks.maxByOrNull { it.heightRatio }?.text?.takeIf { it.isNotBlank() }

    sealed interface Outcome {

        data class Success(
            val session: ReadingSession,
            val source: RecognizedText.Source,
        ) : Outcome

        /** 拍到了，但畫面裡沒有文字。 */
        data object NoTextFound : Outcome

        data class Failed(val error: AppError) : Outcome
    }

    companion object {
        /**
         * OCR 用的擷取參數。
         *
         * 解析度刻意比障礙物偵測（640）高 —— 文字比車輛小得多，
         * 640 的長邊常常讓小字糊掉。1280 是準確率與延遲的折衷。
         * 品質 90 也比一般用途高，JPEG 壓縮痕跡會直接傷害 OCR。
         */
        val CAPTURE_REQUEST = CaptureRequest(
            targetFps = 1f,
            longEdgePixels = 1280,
            outputFormat = CameraFrame.Format.JPEG,
            jpegQuality = 90,
        )
    }
}
