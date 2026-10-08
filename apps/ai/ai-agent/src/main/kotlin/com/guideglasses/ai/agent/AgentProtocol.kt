package com.guideglasses.ai.agent

import com.guideglasses.core.domain.assistant.AssistantIntent
import com.guideglasses.core.domain.assistant.ConversationHistory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 手機與後端 BFF 之間的意圖解析協定。
 *
 * 刻意**不直接呼叫 LLM 供應商的 API**。App 內嵌 API 金鑰必然會被反編譯取出
 * ——這個專案已經因為金鑰進版控被燒過一次。金鑰只放在 Cloud Run BFF，
 * App 端只認得這個協定。
 *
 * 換掉 LLM 供應商（Claude / Gemini / GPT）不需要改 App，也不需要發新版。
 */
@Serializable
data class RouteRequest(
    val utterance: String,
    val history: List<HistoryTurn>,
    val tools: List<ToolSpec>,
    val locale: String = "zh-TW",
)

@Serializable
data class HistoryTurn(
    val role: String,
    val content: String,
)

@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: List<String>,
)

/**
 * BFF 的回應。
 *
 * 兩種情形二選一：
 *  - [tool] 有值：LLM 判定要呼叫某個工具
 *  - [reply] 有值：一般對話回覆，不呼叫工具
 */
@Serializable
data class RouteResponse(
    val tool: ToolInvocation? = null,
    val reply: String? = null,
)

@Serializable
data class ToolInvocation(
    val name: String,
    @SerialName("arguments")
    val arguments: JsonObject = JsonObject(emptyMap()),
)

internal fun ConversationHistory.Turn.toWire() = HistoryTurn(
    role = when (role) {
        ConversationHistory.Turn.Role.USER -> "user"
        ConversationHistory.Turn.Role.ASSISTANT -> "assistant"
    },
    content = content,
)

internal fun AssistantIntent.toWire() = ToolSpec(
    name = toolName,
    description = description,
    parameters = parameters,
)

/**
 * 把 LLM 回傳的參數轉成字串 map。
 *
 * LLM 有時會把數字或布林值當成 JSON 原生型別回傳（`{"destination": 101}`），
 * 直接當字串取會拿到 null。這裡一律以「內容」取值，避免整個工具呼叫
 * 因為一個型別不合就失敗。
 */
internal fun JsonObject.toStringArguments(): Map<String, String> = buildMap {
    for ((key, value) in this@toStringArguments) {
        val text = when (value) {
            is JsonPrimitive -> value.contentOrNull
            else -> value.toString()
        }
        if (!text.isNullOrBlank()) put(key, text)
    }
}
