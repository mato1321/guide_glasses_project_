package com.guideglasses.di

import android.content.Context
import com.guideglasses.core.domain.backend.BackendUrl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * 眼鏡在執行期設定的後端網址，不必重新建置（見 [BackendUrl]）。
 *
 * 兩個來源：
 * - **從手機同步**：眼鏡直連問位置時，手機會附上它目前用的後端網址
 *   （`PhoneCompanionLocationProvider.onBackendUrl`）。通道網址換了，在手機 App 上貼新的就好
 * - **adb**：連不到手機直連時（例如兩支都連在別人的熱點上），用 debug 廣播 `SET_BACKEND`
 *
 * @param buildTimeUrl 建置時的後端網址（`BuildConfig.BUS_API_ENDPOINT`）。
 */
class BackendUrlOverride(context: Context, buildTimeUrl: String) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val buildTime: String = buildTimeUrl.trimEnd('/')

    @Volatile
    private var current: String = BackendUrl.effective(
        prefs.getString(KEY_URL, null),
        prefs.getString(KEY_FOR_BUILD, null),
        buildTime,
    )

    /** 目前要用的後端網址。每個打後端的請求都會讀，所以快取在記憶體。 */
    fun effective(): String = current

    /**
     * 換成新網址。手機每秒都會送一次，所以跟現在一樣就什麼都不做。
     *
     * @return 真的換了回 true；格式不對或跟現在一樣回 false。
     */
    @Synchronized
    fun set(raw: String): Boolean {
        val url = BackendUrl.normalize(raw) ?: return false
        if (url == current) return false
        val editor = prefs.edit()
        if (url == buildTime) {
            editor.remove(KEY_URL).remove(KEY_FOR_BUILD)
        } else {
            editor.putString(KEY_URL, url).putString(KEY_FOR_BUILD, buildTime)
        }
        persist(editor)
        current = url
        return true
    }

    /** 改回建置時的網址。 */
    @Synchronized
    fun reset() {
        persist(prefs.edit().remove(KEY_URL).remove(KEY_FOR_BUILD))
        current = buildTime
    }

    /**
     * 同步寫入（commit 而不是 apply）。apply 是背景寫磁碟，實測設定完 App 馬上被關掉，
     * 重開後網址又變回內建的。這裡很少寫（網址換了才寫），同步寫的成本可以忽略。
     */
    private fun persist(editor: android.content.SharedPreferences.Editor) {
        if (!editor.commit()) android.util.Log.w("BackendUrl", "後端網址沒有存進去")
    }

    private companion object {
        const val PREFS = "backend"
        const val KEY_URL = "url"
        const val KEY_FOR_BUILD = "for_build"
    }
}

/**
 * 打到「建置時後端網址」的請求，改送到 [BackendUrlOverride] 目前的網址。
 *
 * 各個閘道（LLM、公車、步行路線、OCR、定位轉傳）建構時拿的都是建置時的網址字串，
 * 在這裡統一換網域（scheme＋主機＋埠），路徑與查詢字串不動 —— 閘道本身完全不用改。
 * 不是後端的請求（手機直連、人臉照片）網域不同，原樣送出。
 */
class BackendRedirectInterceptor(private val override: BackendUrlOverride) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val target = rewrite(request.url, override.buildTime, override.effective())
        return chain.proceed(if (target == request.url) request else request.newBuilder().url(target).build())
    }

    companion object {
        /** [url] 的網域是 [from] 的話換成 [to]；其他一律原樣回傳。 */
        fun rewrite(url: HttpUrl, from: String, to: String): HttpUrl {
            val source = from.toHttpUrlOrNull() ?: return url
            val target = to.toHttpUrlOrNull() ?: return url
            val sameOrigin = url.scheme == source.scheme && url.host == source.host && url.port == source.port
            if (!sameOrigin) return url
            return url.newBuilder().scheme(target.scheme).host(target.host).port(target.port).build()
        }

        /** 在閘道原本的 client 上加網址改寫 —— 跟 withApiKey 一樣保留各自的逾時設定。 */
        fun OkHttpClient.withBackendRedirect(override: BackendUrlOverride): OkHttpClient =
            newBuilder().addInterceptor(BackendRedirectInterceptor(override)).build()
    }
}
