package com.guideglasses.core.domain.announce

/**
 * 播報優先級。
 *
 * 這是整個系統最重要的一個領域概念。舊專案裡 ChatFragment 有兩個播放器
 * （localMediaPlayer 與 audioPlayer），FaceRecognitionFragment 又有第三個
 * （tts 與 currentMediaPlayer），彼此不知道對方的存在。當六個功能同時運作時，
 * 語音會互相蓋台。
 *
 * 對視障使用者來說，聲音是唯一的輸出通道 —— 蓋台等於失去資訊。
 * 因此所有播報都必須經過單一仲裁點，並依危險程度排序。
 *
 * [ordinal] 越小越優先。
 */
enum class AnnouncementPriority {

    /**
     * 立即危險。例如兩公尺內接近中的車輛、腳邊的高低落差。
     * 打斷一切正在播報的內容，且不排隊 —— 遲到的危險警示沒有意義。
     */
    CRITICAL,

    /**
     * 使用者主動查詢的回應。例如「前面有什麼」「這是誰」。
     * 使用者正在等這個答案，優先於任何系統主動發起的播報。
     */
    USER_RESPONSE,

    /**
     * 導航提示。例如「前方三十公尺右轉」「下一站下車」。
     * 有時效性，但不到危及安全的程度。
     */
    NAVIGATION,

    /**
     * 一般內容。例如 OCR 長文朗讀、閒聊回覆。
     * 可以被上面任何一級打斷，並且應該支援續播。
     */
    AMBIENT,
    ;

    /** 是否可以打斷 [other]。同級不互相打斷，先到先播。 */
    fun canInterrupt(other: AnnouncementPriority): Boolean = ordinal < other.ordinal
}

/**
 * 一則待播報的訊息。
 *
 * @param text 要唸出來的文字。
 * @param priority 優先級。
 * @param dedupeKey 去抖動用的識別鍵。相同 key 的訊息在 [dedupeWindowMillis]
 *   內不會重複播報 —— 例如同一個人的臉連續被辨識到十次，只播一次。
 *   舊專案的 `lastAnnounceName` + `announceCooldown` 就是這個機制，
 *   此處把它一般化到所有功能。
 * @param dedupeWindowMillis 去抖動時間窗。
 * @param resumable 被打斷後是否應該續播。長篇 OCR 朗讀應為 true，
 *   過期的導航提示應為 false。
 */
data class Announcement(
    val text: String,
    val priority: AnnouncementPriority,
    val dedupeKey: String? = null,
    val dedupeWindowMillis: Long = DEFAULT_DEDUPE_WINDOW_MILLIS,
    val resumable: Boolean = false,
) {
    init {
        require(text.isNotBlank()) { "播報內容不可為空" }
    }

    companion object {
        const val DEFAULT_DEDUPE_WINDOW_MILLIS: Long = 10_000L
    }
}
