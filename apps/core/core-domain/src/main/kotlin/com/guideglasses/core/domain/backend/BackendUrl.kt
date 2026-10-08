package com.guideglasses.core.domain.backend

/**
 * 後端網址可以在執行期更換，不必重新建置 App。
 *
 * Cloudflare 臨時通道（`cloudflared tunnel --url`）每次重開網址都會變。以前一變就要
 * 改 local.properties、重新建置、重新安裝眼鏡與手機兩個 App —— 拍攝當天通道一斷就沒救。
 * 現在：在手機 App 上貼新網址，眼鏡透過熱點從手機同步；連不到手機時用 adb 設定。
 *
 * 只換「網域」（scheme＋主機＋埠），路徑沿用 —— 後端的各個端點（/bus-plans、/route⋯）不變。
 */
object BackendUrl {

    private val ORIGIN = Regex("""^https?://[A-Za-z0-9.-]+(:\d{1,5})?$""")

    /**
     * 使用者貼上的網址 → 後端根網址（`https://主機[:埠]`）；不是 http(s) 網址回 null。
     *
     * 容許結尾多一個 `/` 或 `/route`：cloudflared 印出的網址、LLM 的網址常被整串貼上。
     */
    fun normalize(raw: String): String? {
        var url = raw.trim().trimEnd('/')
        if (url.endsWith("/route")) url = url.removeSuffix("/route").trimEnd('/')
        return url.takeIf { ORIGIN.matches(it) }
    }

    /**
     * 目前要用哪個網址。
     *
     * @param saved 執行期設定過的網址，沒有是 null。
     * @param savedForBuild 設定當時 App 內建的網址。跟現在內建的不同，代表之後重新建置換過網址 ——
     *   舊設定作廢，否則新建置的網址會一直被舊值蓋掉，而且完全看不出來。
     * @param buildTime 這次建置內建的網址（local.properties）。
     */
    fun effective(saved: String?, savedForBuild: String?, buildTime: String): String =
        if (saved != null && savedForBuild == buildTime) saved else buildTime
}
