package com.guideglasses.core.domain.text

import com.google.common.truth.Truth.assertThat
import com.guideglasses.core.domain.assistant.AssistantIntent
import com.guideglasses.core.domain.assistant.LocalCommandMatcher
import com.guideglasses.core.domain.translate.SpokenTranslation
import com.guideglasses.core.domain.translate.TargetLanguage
import org.junit.Test

/**
 * 眼鏡上的離線辨識模型是 **zh-CN** 訓練的，說「前面有什麼」回來的是
 * 「前面有什么」。而本專案的指令片語全部寫成繁體。
 *
 * 這在實機上造成過一個很難查的失敗：辨識明明完全正確，
 * 系統卻回「我還不會回答這種問題」—— 看起來像功能壞掉，其實只是字不一樣。
 *
 * 這組測試把兩邊的寫法差異鎖住。
 */
class SimplifiedSpeechTest {

    private val matcher = LocalCommandMatcher()

    // ===== 摺疊本身 =====

    @Test
    fun `繁體摺成簡體`() {
        assertThat(SpokenText.fold("前面有什麼")).isEqualTo("前面有什么")
        assertThat(SpokenText.fold("這是誰")).isEqualTo("这是谁")
        assertThat(SpokenText.fold("繼續唸")).isEqualTo("继续念")
    }

    @Test
    fun `摺疊不改變長度`() {
        // 索引可互換是 SpokenTranslation 能「用摺疊過的找位置、用原字串取內容」
        // 的前提。這個性質一旦被破壞，翻譯會安靜地切錯字。
        val samples = listOf("前面有什麼", "把這句話翻成英文", "abc123", "沒有繁體字")
        samples.forEach { text ->
            assertThat(SpokenText.fold(text).length).isEqualTo(text.length)
        }
    }

    @Test
    fun `已經是簡體的原樣不動`() {
        assertThat(SpokenText.fold("前面有什么")).isEqualTo("前面有什么")
    }

    @Test
    fun `forMatching 同時去標點與摺疊`() {
        assertThat(SpokenText.forMatching("前面，有什麼？")).isEqualTo("前面有什么")
    }

    // ===== 指令比對（實機上真正壞掉的那個） =====

    @Test
    fun `簡體的障礙物查詢要比對得到`() {
        // 實機 log：辨識完成：「前面有什么」→ 當時回了「我還不會回答這種問題」
        assertThat(matcher.match("前面有什么")).isEqualTo(AssistantIntent.DETECT_OBSTACLES)
    }

    @Test
    fun `繁簡兩種寫法結果一致`() {
        val pairs = listOf(
            "這是誰" to "这是谁",
            "測試相機" to "测试相机",
            "測試感測器" to "测试感测器",
            "再說一次" to "再说一次",
            "準備翻譯" to "准备翻译",
            "同步人臉" to "同步人脸",
            "下一段" to "下一段",
            "別說了" to "别说了",
        )

        pairs.forEach { (traditional, simplified) ->
            val expected = matcher.match(traditional)
            assertThat(expected).isNotNull()
            assertThat(matcher.match(simplified)).isEqualTo(expected)
        }
    }

    @Test
    fun `簡體的停止指令仍然最優先`() {
        // 安全相關，不能因為字體寫法不同就失效。
        assertThat(matcher.match("停")).isEqualTo(AssistantIntent.STOP)
        assertThat(matcher.match("别说了")).isEqualTo(AssistantIntent.STOP)
    }

    // ===== 翻譯 =====

    @Test
    fun `簡體也認得出目標語言`() {
        assertThat(TargetLanguage.fromSpoken("翻成英文")).isEqualTo(TargetLanguage.fromSpoken("翻成英文"))
        assertThat(TargetLanguage.fromSpoken("翻译成日文")).isNotNull()
        assertThat(TargetLanguage.fromSpoken("翻譯成日文"))
            .isEqualTo(TargetLanguage.fromSpoken("翻译成日文"))
    }

    @Test
    fun `翻譯內容不會被摺成簡體`() {
        // 摺疊只該影響比對。使用者要翻的內容必須原樣送出去 ——
        // 這裡若回傳簡體，翻譯結果雖然還是對的，但表示摺疊汙染了內容路徑。
        val extracted = SpokenTranslation.extractText("今天天氣很好翻成英文")

        assertThat(extracted).isNotNull()
        assertThat(extracted).contains("氣")
    }

    @Test
    fun `簡體說法也能切出翻譯內容`() {
        val extracted = SpokenTranslation.extractText("今天天气很好翻成英文")

        assertThat(extracted).isEqualTo("今天天气很好")
    }
}
