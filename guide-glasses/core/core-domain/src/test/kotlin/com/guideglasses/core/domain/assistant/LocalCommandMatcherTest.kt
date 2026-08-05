package com.guideglasses.core.domain.assistant

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocalCommandMatcherTest {

    private val matcher = LocalCommandMatcher()

    @Test
    fun `停止指令一律命中`() {
        listOf("停", "停止", "停下來", "別說了", "不要說了", "安靜", "閉嘴", "stop", "Stop！")
            .forEach { utterance ->
                assertThat(matcher.match(utterance)).isEqualTo(AssistantIntent.STOP)
            }
    }

    @Test
    fun `停止的優先級高於其他指令`() {
        // 使用者先喊停再問問題，安全起見必須先停下來。
        assertThat(matcher.match("停，前面有什麼")).isEqualTo(AssistantIntent.STOP)
        assertThat(matcher.match("安靜，這是誰")).isEqualTo(AssistantIntent.STOP)
    }

    @Test
    fun `舊版關鍵字比對會漏掉的說法現在能命中`() {
        // 舊專案要求字面出現「人臉辨識」四個字，
        // 但真實使用者只會這樣講：
        listOf("這是誰", "這個人是誰", "前面是誰", "誰在我前面", "他是誰")
            .forEach { utterance ->
                assertThat(matcher.match(utterance)).isEqualTo(AssistantIntent.IDENTIFY_PERSON)
            }
    }

    @Test
    fun `朗讀文字的各種說法`() {
        listOf("唸給我聽", "念給我聽", "讀給我聽", "上面寫什麼", "這寫什麼", "幫我看字")
            .forEach { utterance ->
                assertThat(matcher.match(utterance)).isEqualTo(AssistantIntent.READ_TEXT)
            }
    }

    @Test
    fun `障礙物查詢的各種說法`() {
        listOf("前面有什麼", "看看前面", "有障礙物嗎", "可以走嗎", "前面安全嗎", "周圍有什麼")
            .forEach { utterance ->
                assertThat(matcher.match(utterance)).isEqualTo(AssistantIntent.DETECT_OBSTACLES)
            }
    }

    @Test
    fun `重複播報的各種說法`() {
        listOf("再說一次", "重複", "剛剛說什麼", "沒聽清楚")
            .forEach { utterance ->
                assertThat(matcher.match(utterance)).isEqualTo(AssistantIntent.REPEAT_LAST)
            }
    }

    @Test
    fun `標點與空白不影響比對`() {
        assertThat(matcher.match("停。")).isEqualTo(AssistantIntent.STOP)
        assertThat(matcher.match("前面 有 什麼？")).isEqualTo(AssistantIntent.DETECT_OBSTACLES)
        assertThat(matcher.match("這是誰！！")).isEqualTo(AssistantIntent.IDENTIFY_PERSON)
    }

    @Test
    fun `全形英文與大小寫都能處理`() {
        assertThat(matcher.match("ＳＴＯＰ")).isEqualTo(AssistantIntent.STOP)
        assertThat(matcher.match("Repeat")).isEqualTo(AssistantIntent.REPEAT_LAST)
    }

    @Test
    fun `相機自我檢測的各種說法`() {
        listOf("測試相機", "相機測試", "檢查相機", "相機正常嗎", "拍一張")
            .forEach { utterance ->
                assertThat(matcher.match(utterance)).isEqualTo(AssistantIntent.CAMERA_TEST)
            }
    }

    @Test
    fun `再拍一張是拍照不是重播`() {
        // 「再拍一張」同時含有「再」與「拍一張」。
        // CAMERA_TEST 的規則排在 REPEAT_LAST 之前，確保語意正確。
        assertThat(matcher.match("再拍一張")).isEqualTo(AssistantIntent.CAMERA_TEST)
    }

    @Test
    fun `需要參數的指令不在本地處理而是交給 LLM`() {
        // 從自由語句抽取目的地或人名，本地規則做不可靠。
        assertThat(matcher.match("帶我去台北101")).isNull()
        assertThat(matcher.match("把這句翻成英文")).isNull()
        assertThat(matcher.match("把他記起來，他叫小明")).isNull()
    }

    @Test
    fun `一般閒聊不會誤觸發功能`() {
        listOf("今天天氣如何", "你好", "現在幾點", "我肚子餓了")
            .forEach { utterance ->
                assertThat(matcher.match(utterance)).isNull()
            }
    }

    @Test
    fun `空字串與純標點回傳 null`() {
        assertThat(matcher.match("")).isNull()
        assertThat(matcher.match("   ")).isNull()
        assertThat(matcher.match("？？？")).isNull()
    }

    @Test
    fun `每個 intent 的工具名稱唯一`() {
        val names = AssistantIntent.entries.map { it.toolName }
        assertThat(names).containsNoDuplicates()
    }

    @Test
    fun `可透過工具名稱反查 intent`() {
        assertThat(AssistantIntent.fromToolName("detect_obstacles"))
            .isEqualTo(AssistantIntent.DETECT_OBSTACLES)
        assertThat(AssistantIntent.fromToolName("NAVIGATE_TO"))
            .isEqualTo(AssistantIntent.NAVIGATE)
        assertThat(AssistantIntent.fromToolName("不存在的工具")).isNull()
    }

    @Test
    fun `callableTools 不包含 CHAT`() {
        assertThat(AssistantIntent.callableTools).doesNotContain(AssistantIntent.CHAT)
        assertThat(AssistantIntent.callableTools).contains(AssistantIntent.NAVIGATE)
    }
}
