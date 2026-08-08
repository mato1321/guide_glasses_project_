package com.guideglasses.core.domain.announce

/**
 * 不發出聲音，只把「本來要唸的話」交給 [sink]。
 *
 * 這是 [FallbackAnnouncer] 候選清單裡的**最後一道防線**，用途有兩個：
 *
 * 1. **保證佇列不會卡死。** 它永遠可用、永遠立刻回報完成，所以無論前面
 *    幾個實作壞成什麼樣，[AnnouncementManager] 都還會繼續前進。
 * 2. **保留眼鏡上唯一的觀察手段。** Rokid Glasses 沒有 STT 也沒有 TTS，
 *    目前驗證任何功能都靠 `adb logcat` 看這些字 —— 聽不到但看得到。
 *    這條除錯路徑一旦消失，眼鏡上就完全沒辦法知道程式在做什麼。
 *
 * 注意它**不會**讓使用者聽到東西。它排在清單最後，代表「真的沒救了」，
 * 而不是一個可接受的正常狀態。
 */
class LogOnlyAnnouncer(
    private val sink: (Announcement) -> Unit,
) : Announcer {

    override val isAvailable: Boolean = true

    /** 從不真的發聲，所以永遠不是「正在講話」。 */
    override val isSpeaking: Boolean = false

    override fun speak(announcement: Announcement, onDone: () -> Unit) {
        sink(announcement)
        onDone()
    }

    override fun stop() = Unit

    override fun shutdown() = Unit
}
