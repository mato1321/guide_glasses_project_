package com.guideglasses.core.domain.assistant

import com.google.common.truth.Truth.assertThat
import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import kotlinx.coroutines.test.runTest
import org.junit.Test

class IntentRouterTest {

    private class FakeLlmGateway(
        var response: AppResult<RoutedIntent> = AppResult.Success(
            RoutedIntent(AssistantIntent.CHAT, source = RoutedIntent.Source.LLM, spokenReply = "好的"),
        ),
    ) : LlmIntentGateway {

        var callCount = 0
        var lastHistory: List<ConversationHistory.Turn> = emptyList()
        var lastTools: List<AssistantIntent> = emptyList()

        override suspend fun route(
            utterance: String,
            history: List<ConversationHistory.Turn>,
            tools: List<AssistantIntent>,
        ): AppResult<RoutedIntent> {
            callCount++
            lastHistory = history
            lastTools = tools
            return response
        }
    }

    private fun router(
        gateway: FakeLlmGateway = FakeLlmGateway(),
        history: ConversationHistory = ConversationHistory(),
    ) = IntentRouter(LocalCommandMatcher(), gateway, history)

    @Test
    fun `本地命中時完全不呼叫 LLM`() = runTest {
        val gateway = FakeLlmGateway()

        val result = router(gateway).route("停")

        assertThat(result.intent).isEqualTo(AssistantIntent.STOP)
        assertThat(result.source).isEqualTo(RoutedIntent.Source.LOCAL_FAST_PATH)
        assertThat(gateway.callCount).isEqualTo(0)
    }

    @Test
    fun `本地未命中時交給 LLM`() = runTest {
        val gateway = FakeLlmGateway(
            AppResult.Success(
                RoutedIntent(
                    intent = AssistantIntent.NAVIGATE,
                    arguments = mapOf("destination" to "台北101"),
                    source = RoutedIntent.Source.LLM,
                ),
            ),
        )

        val result = router(gateway).route("帶我去台北101")

        assertThat(gateway.callCount).isEqualTo(1)
        assertThat(result.intent).isEqualTo(AssistantIntent.NAVIGATE)
        assertThat(result.arguments["destination"]).isEqualTo("台北101")
        assertThat(result.source).isEqualTo(RoutedIntent.Source.LLM)
    }

    @Test
    fun `送給 LLM 的工具清單不含 CHAT`() = runTest {
        val gateway = FakeLlmGateway()

        router(gateway).route("幫我看看行事曆")

        assertThat(gateway.lastTools).doesNotContain(AssistantIntent.CHAT)
        assertThat(gateway.lastTools).containsAtLeast(
            AssistantIntent.NAVIGATE,
            AssistantIntent.TRANSLATE,
        )
    }

    @Test
    fun `沒有網路時降級成人聽得懂的訊息並提示可用的離線指令`() = runTest {
        val gateway = FakeLlmGateway(AppResult.Failure(AppError.NoNetwork()))

        val result = router(gateway).route("帶我去台北101")

        assertThat(result.source).isEqualTo(RoutedIntent.Source.FALLBACK)
        assertThat(result.intent).isEqualTo(AssistantIntent.CHAT)
        // 不可以把技術錯誤唸出來給看不見螢幕的使用者聽。
        assertThat(result.spokenReply).doesNotContain("Exception")
        assertThat(result.spokenReply).doesNotContain("host")
        assertThat(result.spokenReply).contains("沒有網路")
        // 必須告訴使用者「現在還能做什麼」。
        assertThat(result.spokenReply).contains("前面有什麼")
    }

    @Test
    fun `沒有網路時本地指令仍然可用`() = runTest {
        val gateway = FakeLlmGateway(AppResult.Failure(AppError.NoNetwork()))
        val subject = router(gateway)

        assertThat(subject.route("停").intent).isEqualTo(AssistantIntent.STOP)
        assertThat(subject.route("這是誰").intent).isEqualTo(AssistantIntent.IDENTIFY_PERSON)
        assertThat(subject.route("唸給我聽").intent).isEqualTo(AssistantIntent.READ_TEXT)
        assertThat(gateway.callCount).isEqualTo(0)
    }

    @Test
    fun `遠端錯誤與未知錯誤各自給不同的說法`() = runTest {
        val remote = router(FakeLlmGateway(AppResult.Failure(AppError.Remote(statusCode = 503))))
            .route("隨便問一句")
        assertThat(remote.spokenReply).contains("忙碌")

        val unknown = router(FakeLlmGateway(AppResult.Failure(AppError.Unknown("boom"))))
            .route("隨便問一句")
        assertThat(unknown.spokenReply).doesNotContain("boom")
    }

    @Test
    fun `空白輸入不會呼叫 LLM 並請使用者重說`() = runTest {
        val gateway = FakeLlmGateway()

        val result = router(gateway).route("   ")

        assertThat(gateway.callCount).isEqualTo(0)
        assertThat(result.source).isEqualTo(RoutedIntent.Source.FALLBACK)
        assertThat(result.spokenReply).contains("再說一次")
    }

    @Test
    fun `對話歷史會累積並送給 LLM`() = runTest {
        val gateway = FakeLlmGateway()
        val subject = router(gateway)

        subject.route("今天天氣如何")
        subject.route("那明天呢")

        assertThat(gateway.lastHistory.map { it.content })
            .containsAtLeast("今天天氣如何", "那明天呢")
    }

    @Test
    fun `對話歷史有上限不會無限成長`() = runTest {
        val history = ConversationHistory(maxTurns = 4)
        val gateway = FakeLlmGateway()
        val subject = router(gateway, history)

        repeat(20) { subject.route("問題 $it") }

        assertThat(history.size).isAtMost(4)
    }

    @Test
    fun `本地命中的指令也會被記入歷史`() = runTest {
        val history = ConversationHistory()
        val subject = router(history = history)

        subject.route("這是誰")

        assertThat(history.snapshot().map { it.content }).contains("這是誰")
    }
}
