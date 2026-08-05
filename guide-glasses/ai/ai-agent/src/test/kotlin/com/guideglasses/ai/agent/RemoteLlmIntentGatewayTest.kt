package com.guideglasses.ai.agent

import com.google.common.truth.Truth.assertThat
import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.assistant.AssistantIntent
import com.guideglasses.core.domain.assistant.ConversationHistory
import com.guideglasses.core.domain.assistant.RoutedIntent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test

class RemoteLlmIntentGatewayTest {

    private lateinit var server: MockWebServer
    private lateinit var gateway: RemoteLlmIntentGateway

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        gateway = RemoteLlmIntentGateway(server.url("/route").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private suspend fun route(utterance: String = "帶我去台北101") = gateway.route(
        utterance = utterance,
        history = emptyList(),
        tools = AssistantIntent.callableTools,
    )

    private fun enqueueJson(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    @Test
    fun `解析工具呼叫與參數`() = runTest {
        enqueueJson(
            """{"tool":{"name":"navigate_to","arguments":{"destination":"台北101"}}}""",
        )

        val result = route()

        assertThat(result).isInstanceOf(AppResult.Success::class.java)
        val routed = (result as AppResult.Success).data
        assertThat(routed.intent).isEqualTo(AssistantIntent.NAVIGATE)
        assertThat(routed.arguments["destination"]).isEqualTo("台北101")
        assertThat(routed.source).isEqualTo(RoutedIntent.Source.LLM)
    }

    @Test
    fun `解析一般對話回覆`() = runTest {
        enqueueJson("""{"reply":"今天台北是晴天，氣溫二十八度"}""")

        val routed = (route() as AppResult.Success).data

        assertThat(routed.intent).isEqualTo(AssistantIntent.CHAT)
        assertThat(routed.spokenReply).isEqualTo("今天台北是晴天，氣溫二十八度")
    }

    @Test
    fun `非字串型別的參數也能取到值`() = runTest {
        // LLM 常常把數字當成 JSON number 回傳，直接當字串取會拿到 null。
        enqueueJson("""{"tool":{"name":"navigate_to","arguments":{"destination":101}}}""")

        val routed = (route() as AppResult.Success).data

        assertThat(routed.arguments["destination"]).isEqualTo("101")
    }

    @Test
    fun `未知的工具名稱視為失敗而不是亂猜`() = runTest {
        enqueueJson("""{"tool":{"name":"launch_missiles","arguments":{}}}""")

        val result = route()

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat((result as AppResult.Failure).error).isInstanceOf(AppError.Remote::class.java)
    }

    @Test
    fun `既沒有工具也沒有回覆視為失敗`() = runTest {
        enqueueJson("""{}""")

        assertThat(route()).isInstanceOf(AppResult.Failure::class.java)
    }

    @Test
    fun `HTTP 錯誤碼會被帶進領域錯誤`() = runTest {
        enqueueJson("""{"error":"overloaded"}""", code = 503)

        val error = (route() as AppResult.Failure).error

        assertThat(error).isInstanceOf(AppError.Remote::class.java)
        assertThat((error as AppError.Remote).statusCode).isEqualTo(503)
    }

    @Test
    fun `格式錯誤的 JSON 不會讓程式崩潰`() = runTest {
        enqueueJson("這不是 JSON")

        val result = route()

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat((result as AppResult.Failure).error).isInstanceOf(AppError.Remote::class.java)
    }

    @Test
    fun `連線中斷視為無網路`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val result = route()

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat((result as AppResult.Failure).error).isInstanceOf(AppError.NoNetwork::class.java)
    }

    @Test
    fun `送出的請求包含 utterance 歷史與工具清單`() = runTest {
        enqueueJson("""{"reply":"好"}""")

        gateway.route(
            utterance = "那明天呢",
            history = listOf(
                ConversationHistory.Turn(ConversationHistory.Turn.Role.USER, "今天天氣如何"),
                ConversationHistory.Turn(ConversationHistory.Turn.Role.ASSISTANT, "晴天"),
            ),
            tools = AssistantIntent.callableTools,
        )

        val recorded = server.takeRequest()
        val sent = Json { ignoreUnknownKeys = true }
            .decodeFromString<RouteRequest>(recorded.body.readUtf8())

        assertThat(recorded.path).isEqualTo("/route")
        assertThat(sent.utterance).isEqualTo("那明天呢")
        assertThat(sent.history.map { it.content })
            .containsExactly("今天天氣如何", "晴天").inOrder()
        assertThat(sent.history.map { it.role }).containsExactly("user", "assistant").inOrder()
        assertThat(sent.tools.map { it.name }).contains("navigate_to")
        assertThat(sent.tools.map { it.name }).doesNotContain("chat")
        assertThat(sent.locale).isEqualTo("zh-TW")
    }

    @Test
    fun `工具規格帶著參數名稱一起送出`() = runTest {
        enqueueJson("""{"reply":"好"}""")

        route()

        val sent = Json { ignoreUnknownKeys = true }
            .decodeFromString<RouteRequest>(server.takeRequest().body.readUtf8())
        val navigate = sent.tools.first { it.name == "navigate_to" }

        assertThat(navigate.parameters).containsExactly("destination")
        assertThat(navigate.description).isNotEmpty()
    }

    @Test
    fun `未設定後端時一律回報無網路讓上層降級`() = runTest {
        val offline = OfflineLlmIntentGateway()

        val result = offline.route("任何話", emptyList(), AssistantIntent.callableTools)

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat((result as AppResult.Failure).error).isInstanceOf(AppError.NoNetwork::class.java)
    }
}
