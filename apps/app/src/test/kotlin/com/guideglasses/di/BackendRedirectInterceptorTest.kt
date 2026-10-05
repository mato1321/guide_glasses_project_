package com.guideglasses.di

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

/** 通道網址換了，所有打後端的請求跟著換網域，路徑不變（見 [BackendRedirectInterceptor]）。 */
class BackendRedirectInterceptorTest {

    private val built = "https://old-tunnel.trycloudflare.com"
    private val fresh = "https://new-tunnel.trycloudflare.com"

    private fun rewrite(url: String, to: String = fresh) =
        BackendRedirectInterceptor.rewrite(url.toHttpUrl(), built, to).toString()

    @Test
    fun `後端的各個端點都換到新網域，路徑與查詢字串不動`() {
        assertThat(rewrite("$built/route")).isEqualTo("$fresh/route")
        assertThat(rewrite("$built/bus-plans?origin=25.0%2C121.5&dest_name=%E5%8F%B0")).isEqualTo("$fresh/bus-plans?origin=25.0%2C121.5&dest_name=%E5%8F%B0")
        assertThat(rewrite("$built/current-location")).isEqualTo("$fresh/current-location")
    }

    @Test
    fun `可以換成區網的 http 位址與埠`() {
        assertThat(rewrite("$built/eta?bus=310", to = "http://192.168.1.5:8001"))
            .isEqualTo("http://192.168.1.5:8001/eta?bus=310")
    }

    @Test
    fun `不是後端的請求原樣送出`() {
        // 手機直連與人臉照片是別的服務，不能被改到後端去。
        assertThat(rewrite("http://172.20.10.3:8765/current-location")).isEqualTo("http://172.20.10.3:8765/current-location")
        assertThat(rewrite("http://127.0.0.1:8100/manifest")).isEqualTo("http://127.0.0.1:8100/manifest")
    }

    @Test
    fun `沒有新網址或格式不對就不改`() {
        assertThat(rewrite("$built/route", to = built)).isEqualTo("$built/route")
        assertThat(rewrite("$built/route", to = "")).isEqualTo("$built/route")
        assertThat(BackendRedirectInterceptor.rewrite("$built/route".toHttpUrl(), "", fresh).toString()).isEqualTo("$built/route")
    }
}
