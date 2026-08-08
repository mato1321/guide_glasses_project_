package com.guideglasses.core.domain.assistant

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WakeWordTest {

    @Test
    fun `標準說法會被叫醒`() {
        assertThat(WakeWord.matches("呼叫盲狗")).isTrue()
    }

    @Test
    fun `簡體輸出也會被叫醒`() {
        // 眼鏡上的辨識模型是 zh-CN 訓練的，輸出簡體。
        assertThat(WakeWord.matches("呼叫盲狗")).isTrue()
        assertThat(WakeWord.matches("叫盲犬")).isTrue()
    }

    @Test
    fun `前後多講幾個字仍然算`() {
        // 使用者不會剛好只講那四個字。
        assertThat(WakeWord.matches("欸，呼叫盲狗")).isTrue()
        assertThat(WakeWord.matches("呼叫盲狗，幫我看一下前面")).isTrue()
    }

    @Test
    fun `近音誤判也要接受`() {
        // 「盲狗」不是常見詞，通用模型很容易聽成同音字。
        // 不收這些的話，使用者會覺得「我明明喊了它卻沒反應」。
        listOf("呼叫忙狗", "呼叫芒果", "呼叫盲購", "叫芒狗").forEach { heard ->
            assertThat(WakeWord.matches(heard)).isTrue()
        }
    }

    @Test
    fun `單獨的盲狗不會誤觸`() {
        // 「這隻導盲狗好可愛」不該把助理叫醒 —— 前面必須有「呼叫」或「叫」。
        assertThat(WakeWord.matches("這隻導盲狗好可愛")).isFalse()
        assertThat(WakeWord.matches("盲狗")).isFalse()
    }

    @Test
    fun `一般對話不會誤觸`() {
        listOf("前面有什麼", "這是誰", "今天天氣不錯", "").forEach { utterance ->
            assertThat(WakeWord.matches(utterance)).isFalse()
        }
    }

    @Test
    fun `標點與空白不影響`() {
        assertThat(WakeWord.matches("呼叫、盲狗！")).isTrue()
    }
}
