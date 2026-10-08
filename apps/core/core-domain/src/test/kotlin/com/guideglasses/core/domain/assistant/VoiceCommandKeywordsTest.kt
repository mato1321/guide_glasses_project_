package com.guideglasses.core.domain.assistant

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * `keywords.txt`（關鍵詞偵測聽什麼）與 [VoiceCommand]（聽到之後做什麼）必須一致。
 *
 * 兩邊漏改一邊都不會有任何錯誤訊息：只在 keywords.txt 的詞，偵測到了卻沒有對應的功能；
 * 只在 [VoiceCommand] 的詞，永遠不會被偵測到 —— 使用者講了就是沒反應。
 */
class VoiceCommandKeywordsTest {

    // 單元測試的工作目錄是模組資料夾（apps/core/core-domain）。
    private val kwsDir = File("../../ai/ai-asr-offline/src/main/assets/kws")

    /** keywords.txt 每一行：`拼音 拼音 ⋯ @漢字`。 */
    private val lines = File(kwsDir, "keywords.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }

    private val keywords = lines.map { it.substringAfter('@').trim() }

    @Test
    fun `關鍵詞檔的每個詞都有對應的動作`() {
        val orphans = keywords.filter { VoiceCommand.intentFor(it) == null && !VoiceCommand.isStartListening(it) }
        assertThat(orphans).isEmpty()
    }

    @Test
    fun `每個語音指令都在關鍵詞檔裡`() {
        assertThat(keywords).containsAtLeastElementsIn(VoiceCommand.ALL)
    }

    @Test
    fun `我要說話是進入聆聽，不是某個功能`() {
        assertThat(keywords).contains(VoiceCommand.START_LISTENING)
        assertThat(VoiceCommand.intentFor(VoiceCommand.START_LISTENING)).isNull()
        assertThat(VoiceCommand.isStartListening(" 我要說話 ")).isTrue()
        assertThat(VoiceCommand.isStartListening("前面有什麼")).isFalse()
    }

    @Test
    fun `每個拼音都在模型的字表裡`() {
        // 字表外的拼音會讓那個詞永遠偵測不到（或讓模型載入失敗）。
        val tokens = File(kwsDir, "tokens.txt").readLines().map { it.trim().substringBefore(' ') }.toSet()
        val unknown = lines.flatMap { line ->
            line.substringBefore('@').trim().split(Regex("\\s+")).filterNot { it in tokens }.map { "$it（${line.substringAfter('@')}）" }
        }
        assertThat(unknown).isEmpty()
    }
}
