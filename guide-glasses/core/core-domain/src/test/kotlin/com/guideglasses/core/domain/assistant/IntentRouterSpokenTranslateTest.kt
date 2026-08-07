package com.guideglasses.core.domain.assistant

import com.google.common.truth.Truth.assertThat
import com.guideglasses.core.domain.AppResult
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 「口述一段中文＋翻成英文」這條路徑。
 *
 * 重點是它**完全走本地**：`callCount` 必須是 0。翻譯是唯一不依賴 BFF
 * 就能完整運作的功能，這個特性不可以因為新增用法而被破壞。
 */
class IntentRouterSpokenTranslateTest {

    private class NeverCalledGateway : LlmIntentGateway {
        var callCount = 0
        override suspend fun route(
            utterance: String,
            history: List<ConversationHistory.Turn>,
            tools: List<AssistantIntent>,
        ): AppResult<RoutedIntent> {
            callCount++
            return AppResult.Failure(com.guideglasses.core.domain.AppError.NoNetwork())
        }
    }

    private fun router(gateway: LlmIntentGateway) =
        IntentRouter(LocalCommandMatcher(), gateway, ConversationHistory())

    @Test
    fun `口述中文加翻成英文會帶著內容與語言且不呼叫LLM`() = runTest {
        val gateway = NeverCalledGateway()

        val routed = router(gateway).route("今天天氣很好翻成英文")

        assertThat(routed.intent).isEqualTo(AssistantIntent.TRANSLATE)
        assertThat(routed.source).isEqualTo(RoutedIntent.Source.LOCAL_FAST_PATH)
        assertThat(routed.arguments[IntentRouter.ARG_TEXT]).isEqualTo("今天天氣很好")
        assertThat(routed.arguments[IntentRouter.ARG_TARGET_LANGUAGE]).isEqualTo("en")
        assertThat(gateway.callCount).isEqualTo(0)
    }

    @Test
    fun `只說翻成英文時不帶內容改用上一次OCR`() = runTest {
        val routed = router(NeverCalledGateway()).route("翻成英文")

        assertThat(routed.intent).isEqualTo(AssistantIntent.TRANSLATE)
        assertThat(routed.arguments[IntentRouter.ARG_TEXT]).isNull()
        assertThat(routed.arguments[IntentRouter.ARG_TARGET_LANGUAGE]).isEqualTo("en")
    }

    /**
     * 口述的內容裡剛好含有別的指令片語。內容是要翻譯的東西，不是指令 ——
     * 沒有這層保護，「你先繼續走翻成英文」會被判成 READING_NEXT。
     */
    @Test
    fun `口述內容含有其他指令片語時仍然是翻譯`() = runTest {
        val routed = router(NeverCalledGateway()).route("你先繼續走翻成英文")

        assertThat(routed.intent).isEqualTo(AssistantIntent.TRANSLATE)
        assertThat(routed.arguments[IntentRouter.ARG_TEXT]).isEqualTo("你先繼續走")
    }

    /** 安全相關的 STOP 在任何情況下都不讓路。 */
    @Test
    fun `STOP 不會被翻譯搶走`() = runTest {
        val routed = router(NeverCalledGateway()).route("停止翻成英文")

        assertThat(routed.intent).isEqualTo(AssistantIntent.STOP)
    }

    @Test
    fun `指定其他語言也能運作`() = runTest {
        val routed = router(NeverCalledGateway()).route("我肚子餓了翻成日文")

        assertThat(routed.intent).isEqualTo(AssistantIntent.TRANSLATE)
        assertThat(routed.arguments[IntentRouter.ARG_TEXT]).isEqualTo("我肚子餓了")
        assertThat(routed.arguments[IntentRouter.ARG_TARGET_LANGUAGE]).isEqualTo("ja")
    }

    /** 「幫我把上面的字翻成英文」要落回上一次 OCR，不是翻「上面的字」四個字。 */
    @Test
    fun `客套詞加指涉詞仍然落回上一次OCR`() = runTest {
        val routed = router(NeverCalledGateway()).route("幫我把上面的字翻成英文")

        assertThat(routed.intent).isEqualTo(AssistantIntent.TRANSLATE)
        assertThat(routed.arguments[IntentRouter.ARG_TEXT]).isNull()
    }
}
