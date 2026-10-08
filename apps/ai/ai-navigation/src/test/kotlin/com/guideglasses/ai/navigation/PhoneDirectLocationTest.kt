package com.guideglasses.ai.navigation

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test

/**
 * 定位來源的先後：先問手機（熱點區網直連），失敗才經由後端。
 *
 * 用兩台 MockWebServer 分別扮演手機上的 LocalLocationServer 與後端，
 * 兩邊回應格式相同。
 */
class PhoneDirectLocationTest {

    private val phone = MockWebServer().apply { start() }
    private val backend = MockWebServer().apply { start() }
    private val sources = mutableListOf<String>()
    private var clock = 0L

    @After
    fun tearDown() {
        phone.shutdown()
        backend.shutdown()
    }

    private fun location(lat: Double, ageMs: Long) =
        MockResponse().setBody("""{"success":true,"lat":$lat,"lng":121.56,"accuracy_m":10.0,"age_ms":$ageMs}""")

    private fun provider(directBase: String? = phone.url("/").toString().trimEnd('/')) = PhoneCompanionLocationProvider(
        endpoint = backend.url("/").toString().trimEnd('/'),
        ioDispatcher = Dispatchers.IO,
        directEndpoint = { directBase },
        onSourceChanged = { sources += it },
        monotonicMillis = { clock },
    )

    private fun PhoneCompanionLocationProvider.next() = runBlocking { locations().first() }

    @Test
    fun `手機直連有新座標就用直連，不必問後端`() {
        phone.enqueue(location(lat = 25.01, ageMs = 200))

        val coordinate = provider().next()

        assertThat(coordinate.latitude).isEqualTo(25.01)
        assertThat(backend.requestCount).isEqualTo(0)
        assertThat(sources).containsExactly(PhoneCompanionLocationProvider.SOURCE_DIRECT)
    }

    @Test
    fun `直連連續 3 次連不上就改問後端，10 秒內不再浪費時間試直連`() {
        repeat(3) { phone.enqueue(MockResponse().setResponseCode(500)) }
        repeat(5) { backend.enqueue(location(lat = 25.02, ageMs = 800)) }
        val provider = provider()

        repeat(4) { assertThat(provider.next().latitude).isEqualTo(25.02) }
        // 前 3 次都有先問手機，第 4 次已經暫停直連。
        assertThat(phone.requestCount).isEqualTo(3)

        // 10 秒後再給直連一次機會。
        clock += PhoneCompanionLocationProvider.DIRECT_RETRY_MILLIS
        phone.enqueue(location(lat = 25.03, ageMs = 100))
        assertThat(provider.next().latitude).isEqualTo(25.03)
        assertThat(phone.requestCount).isEqualTo(4)
        assertThat(sources).containsExactly(
            PhoneCompanionLocationProvider.SOURCE_RELAY,
            PhoneCompanionLocationProvider.SOURCE_DIRECT,
        ).inOrder()
    }

    @Test
    fun `偶發一次連不上不會讓直連停擺 —— 後端剛好也斷時，導航才不會說收不到定位`() {
        phone.enqueue(MockResponse().setResponseCode(500))
        phone.enqueue(location(lat = 25.07, ageMs = 300))
        backend.enqueue(MockResponse().setResponseCode(502)) // 後端停了
        val provider = provider()

        assertThat(provider.next().latitude).isEqualTo(25.07)
        assertThat(phone.requestCount).isEqualTo(2)
    }

    @Test
    fun `直連連得上但座標太舊不算失敗，下一輪照樣先問手機`() {
        // 手機的 App 還在、但定位暫時沒更新 —— 這不是連線問題。
        repeat(2) {
            phone.enqueue(location(lat = 25.04, ageMs = 20_000))
            backend.enqueue(location(lat = 25.05, ageMs = 1_000))
        }
        val provider = provider()

        assertThat(provider.next().latitude).isEqualTo(25.05)
        assertThat(provider.next().latitude).isEqualTo(25.05)
        assertThat(phone.requestCount).isEqualTo(2)
    }

    @Test
    fun `手機直連附上的後端網址會交給呼叫端，後端轉傳的不採用`() {
        val urls = mutableListOf<String>()
        phone.enqueue(
            MockResponse().setBody(
                """{"success":true,"lat":25.08,"lng":121.56,"accuracy_m":10.0,"age_ms":100,"backend_url":"https://new.trycloudflare.com"}""",
            ),
        )
        backend.enqueue(
            MockResponse().setBody(
                """{"success":true,"lat":25.09,"lng":121.56,"accuracy_m":10.0,"age_ms":100,"backend_url":"https://evil.example"}""",
            ),
        )
        val withUrl = { directBase: String? ->
            PhoneCompanionLocationProvider(
                endpoint = backend.url("/").toString().trimEnd('/'),
                ioDispatcher = Dispatchers.IO,
                directEndpoint = { directBase },
                onBackendUrl = { urls += it },
                monotonicMillis = { clock },
            )
        }

        assertThat(withUrl(phone.url("/").toString().trimEnd('/')).next().latitude).isEqualTo(25.08)
        assertThat(withUrl(null).next().latitude).isEqualTo(25.09)
        assertThat(urls).containsExactly("https://new.trycloudflare.com")
    }

    @Test
    fun `沒有手機位址（不是連手機熱點）時只問後端`() {
        backend.enqueue(location(lat = 25.06, ageMs = 500))

        assertThat(provider(directBase = null).next().latitude).isEqualTo(25.06)
        assertThat(phone.requestCount).isEqualTo(0)
    }
}
