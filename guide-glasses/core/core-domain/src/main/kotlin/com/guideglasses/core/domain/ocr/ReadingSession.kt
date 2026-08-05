package com.guideglasses.core.domain.ocr

/**
 * 一次朗讀的進度。
 *
 * 存在的理由：**沒有這個，長文 OCR 就不能用。**
 *
 * 一份公文可能切成三十段。使用者聽到第十二段時漏聽了一句，如果只能
 * 「從頭再唸一次」，實務上他會直接放棄這個功能。看得見的人可以用眼睛
 * 跳回去，看不見的人需要「上一段」。
 *
 * 純 Kotlin，可完整以單元測試驗證。
 */
class ReadingSession(segments: List<String>) {

    private val segments: List<String> = segments.filter { it.isNotBlank() }

    /** 下一個要唸的索引。等於 [total] 代表已唸完。 */
    var cursor: Int = 0
        private set

    val total: Int get() = segments.size

    val isEmpty: Boolean get() = segments.isEmpty()

    val isFinished: Boolean get() = cursor >= total

    /**
     * 全部段落接回一整份文字。
     *
     * 給翻譯用 —— 使用者說「唸給我聽」之後再說「翻成英文」，要翻的是整張紙
     * 而不是當前那一段。不影響 [cursor]，純讀取。
     */
    val fullText: String get() = segments.joinToString(separator = "")

    /** 已唸完的段數，用於「還剩幾段」的播報。 */
    val remaining: Int get() = (total - cursor).coerceAtLeast(0)

    /** 取出下一段並前進。已到結尾則回傳 null。 */
    fun next(): String? {
        if (isFinished) return null
        return segments[cursor++]
    }

    /**
     * 回到上一段並取出。
     *
     * 注意游標語意：[cursor] 指的是「下一個要唸的」，所以剛唸完第 n 段時
     * cursor 是 n+1。使用者說「上一段」時，他要的是**再聽一次剛才那段**
     * 還是**再前一段**？實測上使用者的意思幾乎都是「剛才那段沒聽清楚」，
     * 所以這裡退兩格再取，也就是回到剛唸完的前一段。
     */
    fun previous(): String? {
        if (total == 0) return null
        cursor = (cursor - 2).coerceAtLeast(0)
        return segments[cursor++]
    }

    /** 重聽剛才那一段，不移動整體進度。 */
    fun repeatCurrent(): String? {
        if (total == 0) return null
        val index = (cursor - 1).coerceAtLeast(0)
        cursor = index + 1
        return segments[index]
    }

    /** 從頭開始。 */
    fun restart() {
        cursor = 0
    }

    /** 跳到結尾，等同放棄剩下的內容。 */
    fun skipToEnd() {
        cursor = total
    }

    /** 進度描述，例如「第 3 段，共 12 段」。 */
    fun progressDescription(): String {
        if (total == 0) return "沒有內容"
        val current = cursor.coerceIn(1, total)
        return "第 $current 段，共 $total 段"
    }
}
