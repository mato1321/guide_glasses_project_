package com.guideglasses.core.domain.backend

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackendUrlTest {

    @Test
    fun `cloudflared 印出的網址直接貼上就能用`() {
        assertThat(BackendUrl.normalize("  https://quiet-river-example.trycloudflare.com  "))
            .isEqualTo("https://quiet-river-example.trycloudflare.com")
    }

    @Test
    fun `結尾多的斜線與 route 會拿掉`() {
        assertThat(BackendUrl.normalize("https://abc.trycloudflare.com/")).isEqualTo("https://abc.trycloudflare.com")
        assertThat(BackendUrl.normalize("https://abc.trycloudflare.com/route")).isEqualTo("https://abc.trycloudflare.com")
        assertThat(BackendUrl.normalize("http://192.168.1.5:8001/route/")).isEqualTo("http://192.168.1.5:8001")
    }

    @Test
    fun `不是 http 網址或帶了其他路徑就拒絕`() {
        assertThat(BackendUrl.normalize("abc.trycloudflare.com")).isNull()
        assertThat(BackendUrl.normalize("ftp://abc.com")).isNull()
        assertThat(BackendUrl.normalize("https://abc.com/bus-plans")).isNull()
        assertThat(BackendUrl.normalize("https://abc.com\"evil")).isNull()
        assertThat(BackendUrl.normalize("")).isNull()
    }

    @Test
    fun `有設定就用設定的`() {
        assertThat(BackendUrl.effective("https://new.com", "https://old.com", "https://old.com"))
            .isEqualTo("https://new.com")
    }

    @Test
    fun `沒設定就用內建的`() {
        assertThat(BackendUrl.effective(null, null, "https://built.com")).isEqualTo("https://built.com")
    }

    @Test
    fun `重新建置換了內建網址之後，舊設定作廢`() {
        assertThat(BackendUrl.effective("https://stale.com", "https://old.com", "https://rebuilt.com"))
            .isEqualTo("https://rebuilt.com")
    }
}
