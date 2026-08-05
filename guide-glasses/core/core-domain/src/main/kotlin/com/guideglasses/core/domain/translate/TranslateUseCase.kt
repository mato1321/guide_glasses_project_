package com.guideglasses.core.domain.translate

import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult

/**
 * 把一段文字翻譯成目標語言。
 *
 * 兩種來源：
 *
 * 1. **接續 OCR** —— 使用者說「唸給我聽」聽到中文，再說「翻成英文」。
 *    這是最有價值的組合：看菜單、看藥袋、看公文之後直接得到翻譯。
 * 2. **明確給文字** —— LLM 從「把『謝謝』翻成日文」抽出 text 參數。
 *
 * 沒有 BFF 時第 2 種走不通，但第 1 種完全可用 —— 因為目標語言可以從
 * 「翻成英文」這種固定說法本地解析（見 [TargetLanguage.fromSpoken]）。
 * 這是刻意的：翻譯是目前唯一不依賴 BFF 就能完整運作的新功能。
 */
class TranslateUseCase(
    private val translator: Translator,
    private val defaultTarget: TargetLanguage = TargetLanguage.DEFAULT,
) {

    /**
     * @param text 要翻譯的文字。null 或空白代表沒有可翻譯的內容。
     * @param target 目標語言。null 時套用 [defaultTarget]。
     * @param onPreparing 語言包需要下載時，在開始下載**之前**呼叫。
     *   下載可能要好幾十秒，對看不見畫面的使用者，那段沉默等於系統當掉，
     *   所以上層必須有機會先播報「正在準備翻譯」。
     */
    suspend fun execute(
        text: String?,
        target: TargetLanguage? = null,
        onPreparing: (TargetLanguage) -> Unit = {},
    ): Outcome {
        if (!translator.isAvailable) return Outcome.Unavailable

        val source = text?.trim()
        if (source.isNullOrEmpty()) return Outcome.NothingToTranslate

        val language = target ?: defaultTarget

        // 過長的內容截斷。翻譯整份三十段的公文，延遲與 TTS 時間都不合理，
        // 而且使用者要的通常是「這張紙上寫什麼」的重點。
        val truncated = source.length > MAX_CHARS
        val payload = if (truncated) source.take(MAX_CHARS) else source

        if (!translator.isReady(language)) {
            onPreparing(language)
            when (val prepared = translator.prepare(language)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> return Outcome.Failed(prepared.error)
            }
        }

        return when (val result = translator.translate(payload, language)) {
            is AppResult.Success -> {
                val output = result.data.trim()
                if (output.isEmpty()) {
                    Outcome.Failed(AppError.NoResult("翻譯回傳空字串"))
                } else {
                    Outcome.Translated(text = output, target = language, truncated = truncated)
                }
            }

            is AppResult.Failure -> Outcome.Failed(result.error)
        }
    }

    sealed interface Outcome {

        /**
         * @param truncated 原文過長被截斷。上層應該告知使用者，
         *   否則他會以為聽到的是全部內容。
         */
        data class Translated(
            val text: String,
            val target: TargetLanguage,
            val truncated: Boolean = false,
        ) : Outcome {
            /** 翻譯結果本身就是要唸的內容，不加前綴 —— 使用者已經知道他要翻譯。 */
            val spoken: String get() = text
        }

        /** 沒有可翻譯的內容（沒先做 OCR，或 LLM 沒抽到 text）。 */
        data object NothingToTranslate : Outcome

        /** 翻譯功能本身不可用。 */
        data object Unavailable : Outcome

        data class Failed(val error: AppError) : Outcome
    }

    companion object {
        /**
         * 單次翻譯的字數上限。
         *
         * 1000 字約等於一頁 A4 的中文，用 TTS 唸完約三分鐘 —— 已經是
         * 使用者耐心的上限。超過的部分截斷並明確告知。
         */
        const val MAX_CHARS = 1000
    }
}
