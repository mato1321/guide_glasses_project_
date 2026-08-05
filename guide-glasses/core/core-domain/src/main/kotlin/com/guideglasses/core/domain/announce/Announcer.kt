package com.guideglasses.core.domain.announce

/**
 * 語音輸出的抽象。
 *
 * 實作可能是 Android TextToSpeech（手機喇叭）或 CXR-M sendTtsContent（眼鏡喇叭）。
 * 上層永遠不該直接碰任一種，否則就會重演舊專案「三套播放器互相蓋台」的問題。
 */
interface Announcer {

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
