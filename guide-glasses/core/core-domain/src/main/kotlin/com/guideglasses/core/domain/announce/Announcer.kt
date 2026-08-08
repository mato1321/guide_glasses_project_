package com.guideglasses.core.domain.announce

/**
 * 語音輸出的抽象。
 *
 * 實作可能是 Android TextToSpeech（手機喇叭）或 CXR-M sendTtsContent（眼鏡喇叭）。
 * 上層永遠不該直接碰任一種，否則就會重演舊專案「三套播放器互相蓋台」的問題。
 */
interface Announcer {

    /**
     * 這個實作**此刻**是否真的能發出聲音。
     *
     * 會有這個屬性，是因為 Rokid Glasses 上 Android 的 `TextToSpeech` 綁定失敗
     * （`System service is not available!`）—— 也就是說「有實作」不等於「有聲音」。
     * 在此之前上層無從得知這件事，只會靜默地什麼都沒發生，而對只靠聽覺的
     * 使用者來說，沒有聲音等同於系統當掉。
     *
     * 這是**動態**的：Android TTS 的初始化是非同步的，開機後幾百毫秒內
     * 都還是 false。因此 [FallbackAnnouncer] 每次播報前都會重新問一次，
     * 而不是在建構時決定用誰。
     *
     * 預設 true —— 多數實作（例如純記錄用的 [LogOnlyAnnouncer]）永遠可用。
     */
    val isAvailable: Boolean get() = true

    /** 是否正在發聲。 */
    val isSpeaking: Boolean

    /**
     * 開始播報。
     *
     * @param onDone 播完時呼叫。實作必須保證這個 callback 一定會被呼叫一次
     *   （即使失敗），否則 [AnnouncementQueue] 會卡住不再取下一則。
     */
    fun speak(announcement: Announcement, onDone: () -> Unit)

    /** 立刻停止發聲。 */
    fun stop()

    fun shutdown()
}
