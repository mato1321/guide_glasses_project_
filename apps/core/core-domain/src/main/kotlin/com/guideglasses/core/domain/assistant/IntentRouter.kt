package com.guideglasses.core.domain.assistant

import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.translate.SpokenTranslation
import com.guideglasses.core.domain.translate.TargetLanguage

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

    companion object {
        /**
         * [AppError.CapabilityUnavailable.capability]：這個 build 沒有設定 BFF 位址。
         *
         * 和「有設定但連不上」（[AppError.NoNetwork]）是**完全不同的兩件事**。
         * 混為一談會讓使用者聽到「目前沒有網路」，然後跑去檢查一個根本不存在
         * 的網路問題 —— 手機明明連著 Wi-Fi。錯誤要翻成人話，但翻錯方向
         * 比不翻更糟。
         */
        const val CAPABILITY_LLM_BACKEND = "llm-backend"
    }
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
            return RoutedIntent(
                intent = intent,
                arguments = localArguments(intent, utterance),
                source = RoutedIntent.Source.LOCAL_FAST_PATH,
            )
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
     * 本地路徑的參數抽取。
     *
     * 一般原則是「需要參數就交給 LLM」，翻譯是刻意的例外：目標語言的說法是
     * 封閉集合，本地解析可靠，而且這讓翻譯**完全不依賴 BFF**。
     *
     * 解析不出語言時回傳空 map，由 UseCase 套用預設語言（英文），
     * 而不是在這裡填死 —— 預設值屬於領域決策，不屬於路由。
     */
    private fun localArguments(
        intent: AssistantIntent,
        utterance: String,
    ): Map<String, String> = when (intent) {
        AssistantIntent.TRANSLATE -> buildMap {
            TargetLanguage.fromSpoken(utterance)?.let { put(ARG_TARGET_LANGUAGE, it.code) }

            // 使用者自己口述要翻譯的內容（「今天天氣很好翻成英文」）。
            // 抽不到就不放，上層會改用上一次 OCR 讀到的內容 ——
            // 這兩種用法都要能走，而且都不需要 BFF。
            SpokenTranslation.extractText(utterance)?.let { put(ARG_TEXT, it) }
        }

        else -> emptyMap()
    }

    /**
     * LLM 不可用時的降級。
     *
     * 關鍵在於**必須說人話**。舊專案在這裡會播報
     * 「發生錯誤: Unable to resolve host ...」—— 對看不見螢幕、
     * 只能靠聽的使用者而言，這等於什麼都沒說。
     */
    private fun fallbackFor(error: AppError): RoutedIntent {
        val message = when {
            // 這個 build 根本沒有 BFF。不要謊稱是網路問題 —— 使用者查不出所以然，
            // 只會浪費他的時間。直接告訴他「我還不會」以及現在能做什麼。
            error is AppError.CapabilityUnavailable &&
                error.capability == LlmIntentGateway.CAPABILITY_LLM_BACKEND ->
                MESSAGE_NO_LLM_BACKEND

            error is AppError.NoNetwork -> MESSAGE_NO_NETWORK
            error is AppError.Remote -> MESSAGE_SERVICE_BUSY
            else -> MESSAGE_GENERIC_FAILURE
        }
        return RoutedIntent(
            intent = AssistantIntent.CHAT,
            source = RoutedIntent.Source.FALLBACK,
            spokenReply = message,
        )
    }

    companion object {
        /** 翻譯目標語言的參數名，與 [AssistantIntent.TRANSLATE] 的 parameters 一致。 */
        const val ARG_TARGET_LANGUAGE = "target_language"

        /** 要翻譯的文字。LLM 路徑會帶，本地路徑不會（改用上一次 OCR 的內容）。 */
        const val ARG_TEXT = "text"

        const val MESSAGE_NOT_HEARD = "抱歉，我沒有聽清楚，可以再說一次嗎"
        const val MESSAGE_NO_NETWORK =
            "目前沒有網路，無法理解這句話。你仍然可以說「前面有什麼」、「這是誰」或「唸給我聽」"

        /**
         * 尚未接上 BFF 時的降級。
         *
         * 刻意只列**現在真的會動**的指令。「前面有什麼」雖然本地片語會命中，
         * 但障礙物模型還沒交付，播出來是「開發中」—— 建議使用者去說一句
         * 會落空的話，等於再騙他一次。
         */
        const val MESSAGE_NO_LLM_BACKEND =
            "我還不會回答這種問題。你可以說「唸給我聽」、「這是誰」、「翻成英文」或「停」"
        const val MESSAGE_SERVICE_BUSY = "服務暫時忙碌，請稍後再試一次"
        const val MESSAGE_GENERIC_FAILURE = "我現在無法處理這個要求，請再說一次"
    }
}
