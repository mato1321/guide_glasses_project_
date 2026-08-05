package com.guideglasses.core.domain.assistant

import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult

/**
 * LLM 意圖解析的抽象。
 *
 * 實作會呼叫雲端的 function calling API。放在 domain 層當介面，
 * 是為了讓 [IntentRouter] 的降級行為能在純 JVM 測試中驗證 ——
 * 「沒網路的時候助理會怎麼回應」對導盲系統來說是必須測到的路徑。
 */
interface LlmIntentGateway {

    suspend fun route(
        utterance: String,
        history: List<ConversationHistory.Turn>,
        tools: List<AssistantIntent>,
    ): AppResult<RoutedIntent>
}

/**
 * 雙層意圖路由。
 *
 * 第一層本地片語比對，命中即回（<100ms、離線可用）；
 * 第二層才交給 LLM 做 function calling。
 *
 * 舊專案用的是單層字串比對 `combined.contains(keyword)`，其中 combined
 * 還把 AI 的回覆一起串進去 —— 既漏判（「這個人是誰」不含關鍵字），
 * 又誤判（助理回覆提到「人臉辨識」就切換功能）。
 */
class IntentRouter(
    private val localMatcher: LocalCommandMatcher,
    private val llmGateway: LlmIntentGateway,
    private val history: ConversationHistory,
) {

    suspend fun route(utterance: String): RoutedIntent {
        if (utterance.isBlank()) {
            return RoutedIntent(
                intent = AssistantIntent.CHAT,
                source = RoutedIntent.Source.FALLBACK,
                spokenReply = MESSAGE_NOT_HEARD,
            )
        }

        localMatcher.match(utterance)?.let { intent ->
            history.record(ConversationHistory.Turn(ConversationHistory.Turn.Role.USER, utterance))
            return RoutedIntent(intent = intent, source = RoutedIntent.Source.LOCAL_FAST_PATH)
        }

        history.record(ConversationHistory.Turn(ConversationHistory.Turn.Role.USER, utterance))

        val result = llmGateway.route(
            utterance = utterance,
            history = history.snapshot(),
            tools = AssistantIntent.callableTools,
        )

        return when (result) {
            is AppResult.Success -> result.data.also { routed ->
                routed.spokenReply?.let {
                    history.record(
                        ConversationHistory.Turn(
                            ConversationHistory.Turn.Role.ASSISTANT,
                            it,
                        ),
                    )
                }
            }

            is AppResult.Failure -> fallbackFor(result.error)
        }
    }

    /**
     * LLM 不可用時的降級。
     *
     * 關鍵在於**必須說人話**。舊專案在這裡會播報
     * 「發生錯誤: Unable to resolve host ...」—— 對看不見螢幕、
     * 只能靠聽的使用者而言，這等於什麼都沒說。
     */
    private fun fallbackFor(error: AppError): RoutedIntent {
        val message = when (error) {
            is AppError.NoNetwork -> MESSAGE_NO_NETWORK
            is AppError.Remote -> MESSAGE_SERVICE_BUSY
            else -> MESSAGE_GENERIC_FAILURE
        }
        return RoutedIntent(
            intent = AssistantIntent.CHAT,
            source = RoutedIntent.Source.FALLBACK,
            spokenReply = message,
        )
    }

    private companion object {
        const val MESSAGE_NOT_HEARD = "抱歉，我沒有聽清楚，可以再說一次嗎"
        const val MESSAGE_NO_NETWORK =
            "目前沒有網路，無法理解這句話。你仍然可以說「前面有什麼」、「這是誰」或「唸給我聽」"
        const val MESSAGE_SERVICE_BUSY = "服務暫時忙碌，請稍後再試一次"
        const val MESSAGE_GENERIC_FAILURE = "我現在無法處理這個要求，請再說一次"
    }
}
