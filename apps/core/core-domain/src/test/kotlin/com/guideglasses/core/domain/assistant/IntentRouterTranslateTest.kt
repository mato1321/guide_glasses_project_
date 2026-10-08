package com.guideglasses.core.domain.assistant

import com.google.common.truth.Truth.assertThat
import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.translate.TargetLanguage
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 本地路徑的翻譯參數抽取。
 *
 * 這條路徑的價值在於**完全不需要網路**：使用者說「翻成日文」，語言在本地
 * 就解析出來了，不必等 LLM。所以這裡刻意用一個永遠失敗的 gateway ——
 * 若實作不小心走到 LLM，測試就會抓到。
 */
class IntentRouterTranslateTest {

    private class AlwaysFailingGateway : LlmIntentGateway {
        var called = false
        override suspend fun route(
            utterance: String,
            history: List<ConversationHistory.Turn>,
            tools: List<AssistantIntent>,
        ): AppResult<RoutedIntent> {
            called = true
            return AppResult.Failure(AppError.NoNetwork("不該走到這裡"))
        }
    }

    private fun router(gateway: LlmIntentGateway) =
        IntentRouter(LocalCommandMatcher(), gateway, ConversationHistory())

    @Test
    fun `本地解析出目標語言且不呼叫 LLM`() = runTest {
        val gateway = AlwaysFailingGateway()

        val routed = router(gateway).route("翻成日文")

        assertThat(routed.intent).isEqualTo(AssistantIntent.TRANSLATE)
        assertThat(routed.source).isEqualTo(RoutedIntent.Source.LOCAL_FAST_PATH)
        assertThat(routed.arguments[IntentRouter.ARG_TARGET_LANGUAGE])
            .isEqualTo(TargetLanguage.JAPANESE.code)
        assertThat(gateway.called).isFalse()
    }

    /**
     * 沒說語言時參數留空，由 UseCase 套預設 —— 預設值是領域決策，不屬於路由。
     */
    @Test
    fun `沒指定語言時不填參數`() = runTest {
        val routed = router(AlwaysFailingGateway()).route("翻譯")

        assertThat(routed.intent).isEqualTo(AssistantIntent.TRANSLATE)
        assertThat(routed.arguments).doesNotContainKey(IntentRouter.ARG_TARGET_LANGUAGE)
    }

    @Test
    fun `其他本地指令不會被塞入翻譯參數`() = runTest {
        val routed = router(AlwaysFailingGateway()).route("這是誰")

        assertThat(routed.intent).isEqualTo(AssistantIntent.IDENTIFY_PERSON)
        assertThat(routed.arguments).isEmpty()
    }
}
