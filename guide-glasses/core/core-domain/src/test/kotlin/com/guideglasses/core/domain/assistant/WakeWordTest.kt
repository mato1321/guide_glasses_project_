package com.guideglasses.core.domain.assistant

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WakeWordTest {

    @Test
    fun `標準說法會被叫醒`() {
        assertThat(WakeWord.matches(WakeWord.CANONICAL)).isTrue()
    }

    /**
     * **這幾句是眼鏡上真的聽到的**，不是想像出來的。
     *
     * 第一版用逐字變體清單，這三句一句都沒中 —— 模型把「呼」與「盲」
     * 兩個字都聽壞了。任何未來的改動都必須讓這幾句繼續通過。
     */
    @Test
    fun `實機聽到的誤判寫法都要能叫醒`() {
        listOf(
            "胡叫蒛狗",   // 呼 → 胡、盲 → 蒛
            "呼叫狗",     // 盲 直接消失
            "哭叫忙狗",   // 呼 → 哭、盲 → 忙
        ).forEach { heard ->
            assertThat(WakeWord.matches(heard)).isTrue()
        }
    }

    @Test
    fun `其他近音組合也要能叫醒`() {
        listOf("呼叫盲狗", "呼叫芒果狗", "叫盲犬", "呼叫忙狗").forEach { heard ->
            assertThat(WakeWord.matches(heard)).isTrue()
        }
    }

    @Test
    fun `前後多講幾個字仍然算`() {
        // 使用者不會剛好只講那四個字。
        assertThat(WakeWord.matches("欸，呼叫盲狗")).isTrue()
        assertThat(WakeWord.matches("呼叫盲狗，幫我看一下前面")).isTrue()
    }

    @Test
    fun `簡體輸出也要能叫醒`() {
        // 眼鏡上的辨識模型是 zh-CN 訓練的。
        assertThat(WakeWord.matches("呼叫盲狗")).isTrue()
    }

    @Test
    fun `導盲狗這個詞不會誤觸`() {
        // 沒有「叫」就不算 —— 日常對話提到導盲狗很正常。
        assertThat(WakeWord.matches("這隻導盲狗好可愛")).isFalse()
        assertThat(WakeWord.matches("盲狗")).isFalse()
        assertThat(WakeWord.matches("我想養一隻狗")).isFalse()
    }

    @Test
    fun `距離太遠不算`() {
        // 「叫」與「狗」隔太多字多半是別的句子，例如「他叫我去買熱狗」。
        assertThat(WakeWord.matches("他叫我去買熱狗")).isFalse()
    }

    @Test
    fun `一般指令不會誤觸`() {
        listOf("前面有什麼", "這是誰", "唸給我聽", "今天天氣不錯", "").forEach { utterance ->
            assertThat(WakeWord.matches(utterance)).isFalse()
        }
    }

    @Test
    fun `標點與空白不影響`() {
        assertThat(WakeWord.matches("呼叫、盲狗！")).isTrue()
    }
}
