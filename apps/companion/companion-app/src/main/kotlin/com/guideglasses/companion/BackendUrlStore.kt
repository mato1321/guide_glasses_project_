package com.guideglasses.companion

import android.content.Context
import com.guideglasses.core.domain.backend.BackendUrl

/**
 * 手機上設定的後端網址（見 [BackendUrl]）。
 *
 * 通道網址換了，在 App 畫面上貼新的就好，不必重新建置。這個網址同時用在兩個地方：
 * - 手機把位置 POST 給後端（[LocationReportService]）
 * - 眼鏡直連問位置時一併告訴眼鏡（[LocationHttpResponder] 的 `backend_url`），
 *   眼鏡就跟著換 —— 只要在手機上改一次
 */
internal class BackendUrlStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val buildTime = BuildConfig.BUS_API_ENDPOINT.trimEnd('/')

    /** 目前要用的後端網址；沒設定過就是建置時的（可能是空字串＝沒有後端）。 */
    fun effective(): String =
        BackendUrl.effective(prefs.getString(KEY_URL, null), prefs.getString(KEY_FOR_BUILD, null), buildTime)

    /** 存新網址。回傳正規化後的網址；格式不對回 null，什麼都不改。 */
    fun save(raw: String): String? {
        val url = BackendUrl.normalize(raw) ?: return null
        if (url == buildTime) {
            reset()
        } else {
            // commit 而不是 apply：apply 是背景寫磁碟，按完儲存馬上把 App 滑掉的話可能沒寫進去
            // （眼鏡端實測過同樣的情況）。
            prefs.edit().putString(KEY_URL, url).putString(KEY_FOR_BUILD, buildTime).commit()
        }
        return url
    }

    /** 改回建置時的網址。 */
    fun reset() {
        prefs.edit().remove(KEY_URL).remove(KEY_FOR_BUILD).commit()
    }

    private companion object {
        const val PREFS = "backend"
        const val KEY_URL = "url"
        const val KEY_FOR_BUILD = "for_build"
    }
}
