package com.guideglasses.core.domain.announce

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 依序嘗試多個 [Announcer]，用第一個當下真的能出聲的那個。
 *
 * 存在的理由是同一份程式要跑在兩種硬體上：
 *
 * | 裝置 | 實際狀況 |
 * |---|---|
 * | 一般 Android 手機 | Android `TextToSpeech` 可用，直接用它最省資源 |
 * | Rokid Glasses | `TextToSpeech` 綁定失敗，必須改用 APK 內建的離線合成引擎 |
 *
 * 若寫死其中一種，手機上會白白背著一顆用不到的離線模型，眼鏡上則完全沒有聲音。
 * 選擇的時機是**每次播報前**而不是建構時 —— Android TTS 初始化是非同步的，
 * 建構當下問到的答案還不算數。
 *
 * ### 這個類別最重要的職責：保證 `onDone` 恰好被呼叫一次
 *
 * [AnnouncementManager] 靠 `onDone` 推動佇列。少呼叫一次，佇列從此停住，
 * 使用者再也聽不到任何提示（包含危險警示）；多呼叫一次，佇列會跳號漏掉一則。
 * 所以這裡即使在「全部都不可用」或「底層直接丟例外」的情況下，
 * 也必須恰好回報一次完成。
 *
 * ### 已知限制
 *
 * 只擋得住**同步**的失敗（`isAvailable` 為 false、或 `speak` 當場丟例外）。
 * 若某個實作宣稱可用、`speak` 也正常返回，卻從此不回呼 `onDone`（Rokid 的
 * 服務就有這種靜默失敗的前例），這裡不會發現。要擋那種情況需要一個逾時看門狗，
 * 而那需要注入時鐘與排程器，目前刻意不做 —— 但這是已知的缺口，不是沒想到。
 *
 * 純 Kotlin，可完整以單元測試驗證。
 *
 * @param candidates 依偏好排序，前面的優先。不可為空。
 * @param onFailure 某個實作丟例外時的通知。`core-domain` 不依賴任何
 *   log 框架，所以把記錄這件事交給呼叫端。
 */
class FallbackAnnouncer(
    private val candidates: List<Announcer>,
    private val onFailure: (Announcer, Throwable) -> Unit = { _, _ -> },
) : Announcer {

    init {
        require(candidates.isNotEmpty()) { "至少要有一個 Announcer" }
    }

    /**
     * 目前這則播報交給了誰。
     *
     * 記著它是為了讓 [stop] 只停正在講話的那一個 —— 對所有候選者都呼叫
     * `stop()` 看似無害，實際上會清掉它們各自的待處理回呼。
     */
    @Volatile
    private var active: Announcer? = null

    override val isAvailable: Boolean
        get() = candidates.any { it.isAvailable }

    override val isSpeaking: Boolean
        get() = active?.isSpeaking == true

    override fun speak(announcement: Announcement, onDone: () -> Unit) {
        val reported = AtomicBoolean(false)
        fun reportOnce() {
            if (reported.compareAndSet(false, true)) onDone()
        }

        for (candidate in candidates) {
            if (!candidate.isAvailable) continue

            active = candidate
            try {
                candidate.speak(announcement) {
                    // 這則已經被 stop 換掉時就不要動 active，否則會把
                    // 後來接手的那一個誤清成 null。
                    if (active === candidate) active = null
                    reportOnce()
                }
                return
            } catch (e: Exception) {
                active = null
                onFailure(candidate, e)

                // 它已經回報過完成才丟例外 —— 這時再找下一個接手，
                // 會讓同一則播報產生第二次 onDone，佇列就會跳號。
                if (reported.get()) return
            }
        }

        // 一個能用的都沒有。仍然要回報完成，否則佇列從此不再前進。
        active = null
        reportOnce()
    }

    override fun stop() {
        active?.stop()
        active = null
    }

    override fun shutdown() {
        active = null
        candidates.forEach { candidate ->
            // 其中一個關閉失敗，不該害得其他的關不掉。
            try {
                candidate.shutdown()
            } catch (e: Exception) {
                onFailure(candidate, e)
            }
        }
    }
}
