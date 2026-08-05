package com.guideglasses.core.domain.text

/**
 * ASR 產出文字的正規化。
 *
 * 語音辨識的標點極不穩定 —— 同一句「停」可能回來是「停」「停。」「停，」，
 * 英文可能是全形也可能是半形、大寫也可能小寫。任何要拿 ASR 結果做片語比對的
 * 地方都必須先過這一關，否則會安靜地漏判。
 *
 * 抽成共用是因為現在有兩個地方需要它（快捷指令比對、翻譯目標語言解析），
 * 兩份實作若不同步就會出現「講同一句話在 A 有效在 B 無效」這種難查的問題。
 */
object SpokenText {

    /**
     * 去掉標點與空白、全形英數轉半形、英文轉小寫。
     *
     * 只保留字母與數字 —— 中文字本身是 letter，會被保留。
     */
    fun normalise(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            val c = when (ch) {
                // 全形英數轉半形（Ａ-Ｚ、ａ-ｚ、０-９ 與半形差 0xFEE0）
                in 'Ａ'..'Ｚ', in 'ａ'..'ｚ', in '０'..'９' ->
                    (ch.code - 0xFEE0).toChar()

                else -> ch
            }
            if (c.isLetterOrDigit()) append(c.lowercaseChar())
        }
    }
}
