package com.guideglasses.ai.agent

import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.assistant.AssistantIntent
import com.guideglasses.core.domain.assistant.ConversationHistory
import com.guideglasses.core.domain.assistant.LlmIntentGateway
import com.guideglasses.core.domain.assistant.RoutedIntent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * 透過 BFF 呼叫 LLM 做 function calling。
 *
 * 逾時刻意設得短。導盲助理如果十秒後才回答「前面有什麼」，那個答案
 * 已經沒有意義了 —— 使用者早就往前走了。寧可快速降級到「目前無法理解，
 * 你可以說前面有什麼」也不要讓他站在原地空等。
 */
class RemoteLlmIntentGateway(
    private val endpoint: String,
    private val client: OkHttpClient = defaultClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LlmIntentGateway {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun route(
        utterance: String,
        history: List<ConversationHistory.Turn>,
        tools: List<AssistantIntent>,
    ): AppResult<RoutedIntent> = withContext(ioDispatcher) {
        val payload = RouteRequest(
            utterance = utterance,
            history = history.map { it.toWire() },
            tools = tools.map { it.toWire() },
        )

        val request = Request.Builder()
            .url(endpoint)
            .post(json.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext AppResult.Failure(
                        AppError.Remote(
                            statusCode = response.code,
                            debugMessage = "route failed: HTTP ${response.code}",
                        ),
                    )
                }

                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    return@withContext AppResult.Failure(
                        AppError.Remote(response.code, "empty response body"),
                    )
                }

                parse(body)
            }
        } catch (e: UnknownHostException) {
            AppResult.Failure(AppError.NoNetwork(e.message ?: "unknown host"))
        } catch (e: SocketTimeoutException) {
            AppResult.Failure(AppError.NoNetwork(e.message ?: "timeout"))
        } catch (e: IOException) {
            AppResult.Failure(AppError.NoNetwork(e.message ?: "io failure"))
        }
    }

    private fun parse(body: String): AppResult<RoutedIntent> {
        val decoded = runCatching { json.decodeFromString<RouteResponse>(body) }
            .getOrElse { error ->
                return AppResult.Failure(
                    AppError.Remote(debugMessage = "malformed response: ${error.message}"),
                )
            }

        val invocation = decoded.tool
        if (invocation != null) {
            val intent = AssistantIntent.fromToolName(invocation.name)
                ?: return AppResult.Failure(
                    // LLM 幻想出一個不存在的工具名稱。不要硬猜，降級處理比亂執行安全。
                    AppError.Remote(debugMessage = "unknown tool: ${invocation.name}"),
                )

            return AppResult.Success(
                RoutedIntent(
                    intent = intent,
                    arguments = invocation.arguments.toStringArguments(),
                    source = RoutedIntent.Source.LLM,
                ),
            )
        }

        val reply = decoded.reply
        if (reply.isNullOrBlank()) {
            return AppResult.Failure(
                AppError.Remote(debugMessage = "response contained neither tool nor reply"),
            )
        }

        return AppResult.Success(
            RoutedIntent(
                intent = AssistantIntent.CHAT,
                source = RoutedIntent.Source.LLM,
                spokenReply = reply,
            ),
        )
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/**
 * 尚未設定 BFF 位址時使用。
 *
 * 一律回報無網路，讓 [com.guideglasses.core.domain.assistant.IntentRouter]
 * 走降級路徑。這樣即使後端還沒建好，App 也是可用的 ——
 * 本地快捷指令（停 / 前面有什麼 / 這是誰 / 唸給我聽）全部照常運作。
 */
class OfflineLlmIntentGateway : LlmIntentGateway {

    override suspend fun route(
        utterance: String,
        history: List<ConversationHistory.Turn>,
        tools: List<AssistantIntent>,
    ): AppResult<RoutedIntent> =
        AppResult.Failure(AppError.NoNetwork("尚未設定 LLM 後端位址"))
}
