package com.guideglasses.core.domain.ocr

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpeechSegmenterTest {

    private val segmenter = SpeechSegmenter()

    @Test
    fun `空輸入回傳空清單`() {
        assertThat(segmenter.segment(null)).isEmpty()
        assertThat(segmenter.segment("")).isEmpty()
        assertThat(segmenter.segment("   \n\n  ")).isEmpty()
    }

    @Test
    fun `短行會被標記為標題`() {
        val result = segmenter.segment("內科門診")

        assertThat(result).hasSize(1)
        assertThat(result[0]).startsWith("標題，")
        assertThat(result[0]).contains("內科門診")
    }

    @Test
    fun `以句號結尾的短句不會被當成標題`() {
        val result = segmenter.segment("請按鈴。")

        assertThat(result[0].startsWith("標題，")).isFalse()
    }

    @Test
    fun `長句不會被當成標題`() {
        val long = "這是一段超過二十個字的內文用來確認它不會被誤判成一個標題行"
        val result = segmenter.segment(long)

        assertThat(result[0].startsWith("標題，")).isFalse()
    }

    @Test
    fun `標點後會補空白讓 TTS 有停頓`() {
        val result = segmenter.segment("每日三次，飯後服用。請勿空腹。")

        val joined = result.joinToString(" ")
        assertThat(joined).contains("，")
        // 逗號後應該有空白
        assertThat(Regex("，\\S").containsMatchIn(joined)).isFalse()
    }

    @Test
    fun `段落各自獨立分段`() {
        val text = "第一段內容。\n\n第二段內容。"

        val result = segmenter.segment(text)

        assertThat(result).hasSize(2)
        assertThat(result[0]).contains("第一段")
        assertThat(result[1]).contains("第二段")
    }

    @Test
    fun `多個換行視為一個段落分隔`() {
        val result = segmenter.segment("甲。\n\n\n\n乙。")

        assertThat(result).hasSize(2)
    }

    @Test
    fun `CRLF 與 CR 都能正確處理`() {
        val result = segmenter.segment("第一段內容。\r\n第二段內容。\r第三段內容。")

        assertThat(result).hasSize(3)
    }

    @Test
    fun `每一段都不超過長度上限`() {
        val sentence = "這是一個測試用的句子總共大約二十個字。"
        val text = sentence.repeat(20)

        val result = segmenter.segment(text)

        result.forEach { segment ->
            assertThat(segment.length).isAtMost(SpeechSegmenter.DEFAULT_MAX_SEGMENT_LENGTH + 4)
        }
    }

    @Test
    fun `原版的缺陷已修正 —— 超長單句會被拆開`() {
        // 原版 splitTextForSpeech 只在「加入前」檢查長度，
        // 因此單一句子超過 80 字時會整句塞進一塊。
        val overlong = "這份文件詳細說明了本項藥物的正確使用方式，其中包含每次的建議劑量、" +
            "每日的服用頻率、服藥前後的注意事項、可能出現的各種副作用、不適用的禁忌症狀、" +
            "以及藥品的保存方式，還有萬一發生嚴重不良反應時應該立即採取的完整處理步驟說明。"

        assertThat(overlong.length).isGreaterThan(SpeechSegmenter.DEFAULT_MAX_SEGMENT_LENGTH)

        val result = segmenter.segment(overlong)

        assertThat(result.size).isGreaterThan(1)
        result.forEach { segment ->
            assertThat(segment.length).isAtMost(SpeechSegmenter.DEFAULT_MAX_SEGMENT_LENGTH)
        }
    }

    @Test
    fun `沒有任何標點的超長字串也會被切開`() {
        val noPunctuation = "字".repeat(250)

        val result = segmenter.segment(noPunctuation)

        assertThat(result.size).isAtLeast(3)
        result.forEach { segment ->
            assertThat(segment.length).isAtMost(SpeechSegmenter.DEFAULT_MAX_SEGMENT_LENGTH)
        }
    }

    @Test
    fun `不會產生空白片段`() {
        val result = segmenter.segment("甲。\n \n\t\n乙。\n\n\n")

        assertThat(result).hasSize(2)
        result.forEach { assertThat(it.trim()).isNotEmpty() }
    }

    @Test
    fun `全形空白也會被正規化`() {
        val result = segmenter.segment("這是　　　一段　文字內容測試用途需要超過二十個字。")

        assertThat(result[0]).doesNotContain("　　")
    }

    @Test
    fun `藥袋這類真實內容的分段結果合理`() {
        val medicineLabel = """
            台北市立聯合醫院

            姓名：王小明
            藥品名稱：普拿疼 500mg

            用法用量：每次一顆，每日三次，飯後服用。
            注意事項：服藥期間請勿飲酒。如有不適請立即回診。
        """.trimIndent()

        val result = segmenter.segment(medicineLabel)

        assertThat(result).isNotEmpty()
        // 短行被標記為標題
        assertThat(result.any { it.contains("標題") && it.contains("台北市立聯合醫院") }).isTrue()
        // 內容都在
        val joined = result.joinToString("")
        assertThat(joined).contains("王小明")
        assertThat(joined).contains("普拿疼")
        assertThat(joined).contains("飯後服用")
        assertThat(joined).contains("請勿飲酒")
    }

    @Test
    fun `句子邊界不會被切斷`() {
        val text = "第一句話結束了。第二句話也結束了。第三句話同樣結束了。"

        val result = segmenter.segment(text)

        // 每一段都應該以句末標點結尾（不會切在句子中間）
        result.forEach { segment ->
            assertThat(segment.last()).isIn(listOf('。', '！', '？', '；'))
        }
    }

    @Test
    fun `可自訂長度上限`() {
        val shortSegmenter = SpeechSegmenter(maxSegmentLength = 20)
        val text = "第一句話。第二句話。第三句話。第四句話。第五句話。"

        val result = shortSegmenter.segment(text)

        assertThat(result.size).isGreaterThan(1)
        result.forEach { assertThat(it.length).isAtMost(24) }
    }

    @Test
    fun `非法參數會被拒絕`() {
        assertThat(
            runCatching { SpeechSegmenter(maxSegmentLength = 0) }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)

        assertThat(
            runCatching { SpeechSegmenter(headingLengthThreshold = -1) }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
    }
}
