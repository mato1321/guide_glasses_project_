package com.guideglasses.core.domain.ocr

/**
 * 把 OCR 出來的文字切成適合朗讀的片段。
 *
 * 這段邏輯移植自 `Text_Recognition/.../MainActivity.java` 的 `splitTextForSpeech()`。
 * 那是原始 repository 中最懂視障使用者的一段程式碼 —— 它處理的不是「怎麼切字串」，
 * 而是「怎麼唸才聽得懂」：
 *
 *  - 標點後補空白，讓 TTS 有停頓
 *  - 短行視為標題，前面加「標題，」讓聽的人知道結構
 *  - 依句子邊界分塊，而不是硬切固定長度 —— 句子被切斷聽起來會很怪
 *  - 一塊約 80 字，太長聽不完會忘記前面說什麼
 *
 * 移植時修正了原版的一個問題：**單一句子超過 80 字時，原版會整句塞進一塊**
 * （因為只在「加入前」檢查長度）。這裡改成超長句子會再依逗號切開，
 * 真的沒有逗號才硬切。
 */
class SpeechSegmenter(
    private val maxSegmentLength: Int = DEFAULT_MAX_SEGMENT_LENGTH,
    private val headingLengthThreshold: Int = DEFAULT_HEADING_LENGTH_THRESHOLD,
) {
    init {
        require(maxSegmentLength > 0) { "maxSegmentLength 必須大於 0" }
        require(headingLengthThreshold >= 0) { "headingLengthThreshold 不可為負" }
    }

    fun segment(rawText: String?): List<String> {
        val text = rawText?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()

        val normalised = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(HORIZONTAL_WHITESPACE, " ")

        return normalised
            .split(PARAGRAPH_BREAK)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { segmentParagraph(it) }
    }

    private fun segmentParagraph(rawParagraph: String): List<String> {
        // 短行通常是標題、招牌、欄位名稱。明確講出「標題」讓聽的人知道
        // 這不是內文 —— 看得見的人靠字級和排版判斷，看不見的人只能靠這個。
        //
        // 這個判斷必須在補空白**之前**做。原版先補空白再判斷，導致
        // 「請按鈴。」補完變成「請按鈴。 」（尾端有空白），endsWith("。")
        // 於是為 false，一句正常的句子被誤標成標題。
        var paragraph = if (
            rawParagraph.length <= headingLengthThreshold && !rawParagraph.endsWith("。")
        ) {
            "標題，$rawParagraph。"
        } else {
            rawParagraph
        }

        // 標點後補一個空白，TTS 才會有自然的停頓。
        for (mark in PAUSE_MARKS) {
            paragraph = paragraph.replace(mark.toString(), "$mark ")
        }

        val sentences = splitAfterSentenceEnd(paragraph)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val segments = mutableListOf<String>()
        val chunk = StringBuilder()

        fun flush() {
            val text = chunk.toString().trim()
            if (text.isNotEmpty()) segments += text
            chunk.setLength(0)
        }

        for (sentence in sentences) {
            // 單一句子就超過上限 —— 原版會整句塞進去。這裡先把目前的塊送出，
            // 再把超長句子拆開。
            if (sentence.length > maxSegmentLength) {
                flush()
                segments += splitOverlongSentence(sentence)
                continue
            }

            if (chunk.length + sentence.length > maxSegmentLength) flush()

            chunk.append(sentence).append(' ')
        }

        flush()
        return segments
    }

    /**
     * 拆開超過上限的單一句子。
     *
     * 優先在逗號、頓號這類次要停頓處切 —— 那是自然的呼吸點。
     * 真的沒有任何停頓符號才硬切，硬切至少保證不會有一塊長到聽不完。
     */
    private fun splitOverlongSentence(sentence: String): List<String> {
        val parts = splitAfterAny(sentence, SOFT_BREAK_MARKS)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val result = mutableListOf<String>()
        val chunk = StringBuilder()

        fun flush() {
            val text = chunk.toString().trim()
            if (text.isNotEmpty()) result += text
            chunk.setLength(0)
        }

        for (part in parts) {
            if (part.length > maxSegmentLength) {
                flush()
                part.chunked(maxSegmentLength).forEach { result += it }
                continue
            }
            if (chunk.length + part.length > maxSegmentLength) flush()
            chunk.append(part)
        }

        flush()
        return result
    }

    /** 在句末標點之後切開，標點本身留在前一段。 */
    private fun splitAfterSentenceEnd(text: String): List<String> =
        splitAfterAny(text, SENTENCE_END_MARKS)

    private fun splitAfterAny(text: String, marks: Set<Char>): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()

        for (ch in text) {
            current.append(ch)
            if (ch in marks) {
                result += current.toString()
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    companion object {
        /**
         * 一段朗讀的字數上限。
         *
         * 80 字沿用原版的值。太長聽的人會忘記開頭，太短則會被停頓切得破碎。
         */
        const val DEFAULT_MAX_SEGMENT_LENGTH = 80

        /** 短於這個長度且不以句號結尾的行，視為標題。 */
        const val DEFAULT_HEADING_LENGTH_THRESHOLD = 20

        private val HORIZONTAL_WHITESPACE = Regex("[ \t　]+")
        private val PARAGRAPH_BREAK = Regex("\n+")

        /** 這些標點後面補空白，讓 TTS 有停頓。 */
        private val PAUSE_MARKS = charArrayOf('。', '，', '！', '？', '：', '；')

        /** 句末標點 —— 在這裡切開最自然。 */
        private val SENTENCE_END_MARKS = setOf('。', '！', '？', '；')

        /** 次要停頓 —— 只有句子超長時才用它們來切。 */
        private val SOFT_BREAK_MARKS = setOf('，', '、', '：', ',')
    }
}
