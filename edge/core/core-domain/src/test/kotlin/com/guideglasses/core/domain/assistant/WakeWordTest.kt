package com.guideglasses.core.domain.assistant

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WakeWordTest {

    @Test
    fun `推薦說法會被叫醒`() {
        assertThat(WakeWord.matches(WakeWord.CANONICAL)).isTrue()
    }

    @Test
    fun `所有支援的說法都會被叫醒`() {
        listOf("小幫手", "小助手", "你好眼鏡", "嘿眼鏡", "呼叫盲狗").forEach { say ->
            assertThat(WakeWord.matches(say)).isTrue()
        }
    }

    @Test
    fun `常見同音誤判也要接受`() {
        // 這幾個是同音字替換，模型很常這樣轉。
        listOf("小邦手", "小帮守", "眼竟", "演镜").forEach { heard ->
            assertThat(WakeWord.matches(heard)).isTrue()
        }
    }

    @Test
    fun `命中時要說得出是哪一組`() {
        // log 需要這個資訊才能判斷哪一組說法最穩、該不該淘汰其他的。
        assertThat(WakeWord.match("小幫手")).isEqualTo("幫手")
        assertThat(WakeWord.match("你好眼鏡")).isEqualTo("眼鏡")
        assertThat(WakeWord.match("呼叫盲狗")).isEqualTo("叫狗")
        assertThat(WakeWord.match("前面有什麼")).isNull()
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
            "叫狗",       // 只剩兩個字
            "胡照猛狗",   // 連「叫」都被聽成「照」
            "呼啸狗",     // 「叫」→「啸」
            "不道狗",     // 「呼叫」整個變成「不道」
            "忙够",       // 連「狗」都變成同音的「够」
            "芒够",       // 盲→芒、狗→够
        ).forEach { heard ->
            assertThat(WakeWord.matches(heard)).isTrue()
        }
    }

    /**
     * 這些是喚醒監聽在**沒人叫它時**實際收到的環境語音。
     * 規則放寬到只靠「狗」之後，它們仍然必須被擋掉，
     * 否則助理會在旁邊有人講話時亂插嘴。
     */
    @Test
    fun `實機收到的環境語音不會誤觸`() {
        listOf(
            "怎么走",
            "因为现在不",
            "是谢谢谢谢谢",
            "家叫一个然后让这个",
            "进",
            "那昨前方那昨天方没一轮呢几",
        ).forEach { noise ->
            assertThat(WakeWord.matches(noise)).isFalse()
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
    fun `日常提到狗不會誤觸`() {
        // 擋掉的依據是「長度」，不是有沒有「叫」——
        // 因為實測發現「叫」本身就常被聽壞（照、啸）。
        assertThat(WakeWord.matches("這隻導盲狗好可愛")).isFalse()
        assertThat(WakeWord.matches("我想養一隻狗")).isFalse()
    }

    @Test
    fun `單獨講盲狗算是在叫它`() {
        /*
         * 這是刻意放行的。第一版擋掉「盲狗」，理由是怕日常對話誤觸；
         * 但實測發現「呼叫」兩個字幾乎每次都被聽壞（胡叫、哭叫、不道、呼啸），
         * 真正留得住的只有「狗」。
         *
         * 而且會單獨蹦出「盲狗」兩個字的場合，本來就幾乎只有在叫它。
         * 誤觸的代價是回一聲「我在」；漏聽的代價是使用者對著沒反應的眼鏡
         * 一直喊 —— 後者明顯更糟。長句子的誤觸則由長度上限擋住。
         */
        assertThat(WakeWord.matches("盲狗")).isTrue()
    }

    @Test
    fun `距離太遠不算`() {
        // 「叫」與「狗」隔太多字多半是別的句子，例如「他叫我去買熱狗」。
        assertThat(WakeWord.matches("他叫我去買熱狗")).isFalse()
    }

    @Test
    fun `長句子裡出現狗不算`() {
        // 只靠「狗」的那條規則有長度上限，否則日常對話會一直把助理叫醒。
        assertThat(WakeWord.matches("我昨天在公園看到一隻狗")).isFalse()
        assertThat(WakeWord.matches("這隻導盲狗好可愛")).isFalse()
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
