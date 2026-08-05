package com.guideglasses.core.domain.ocr

import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.glasses.CameraFrame

/**
 * 文字辨識的抽象。
 *
 * 分層的目的是支援「三層漸進策略」：先用端側（免費、離線、~100ms），
 * 信心不足才往上送雲端。上層只認得這個介面，不知道底下是哪一層。
 */
interface TextRecognizer {

    /** 這個辨識器目前可用嗎（模型下載完成、有網路等）。 */
    val isAvailable: Boolean

    suspend fun recognize(frame: CameraFrame): AppResult<RecognizedText>
}

/**
 * 辨識結果。
 *
 * @param text 完整文字。
 * @param blocks 文字區塊，含在畫面中的位置。用於「只唸最大的字」這類招牌模式。
 * @param source 這個結果是哪一層產生的，用於觀測與成本分析。
 */
data class RecognizedText(
    val text: String,
    val blocks: List<TextBlock> = emptyList(),
    val source: Source,
) {
    val isEmpty: Boolean get() = text.isBlank()

    /**
     * 品質評估 —— 決定要不要往上送雲端。
     *
     * ML Kit 不提供整體信心度，所以用可觀察的訊號來推估：
     * 字太少、或大量單字元區塊（常見於辨識破碎），就視為品質不佳。
     */
    fun looksUnreliable(): Boolean {
        if (isEmpty) return true
        if (text.length < MIN_TRUSTWORTHY_LENGTH) return true
        if (blocks.isEmpty()) return false

        val fragmented = blocks.count { it.text.trim().length <= 1 }
        return fragmented.toFloat() / blocks.size > FRAGMENT_RATIO_THRESHOLD
    }

    enum class Source {
        /** 端側 ML Kit，離線、免費。 */
        ON_DEVICE,

        /** 雲端 OCR，準確度較高但要錢也要網路。 */
        CLOUD,
    }

    companion object {
        /** 短於這個長度的結果通常是誤判，例如把雜訊當成一兩個字。 */
        const val MIN_TRUSTWORTHY_LENGTH = 2

        /** 單字元區塊超過這個比例，視為辨識破碎。 */
        const val FRAGMENT_RATIO_THRESHOLD = 0.6f
    }
}

/**
 * 一個文字區塊。
 *
 * @param text 區塊內的文字。
 * @param heightRatio 區塊高度佔畫面高度的比例。招牌模式用它挑出「最大的字」。
 */
data class TextBlock(
    val text: String,
    val heightRatio: Float,
)

/**
 * OCR 的使用情境。不同情境要唸的東西完全不同。
 */
enum class OcrMode {
    /** 文件：完整朗讀。信件、公文、書本。 */
    DOCUMENT,

    /** 招牌：只唸最大的字。門牌、店名、站牌、路標。 */
    SIGN,
}
