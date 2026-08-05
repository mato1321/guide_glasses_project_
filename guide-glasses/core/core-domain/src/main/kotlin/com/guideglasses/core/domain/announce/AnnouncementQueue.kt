package com.guideglasses.core.domain.announce

/**
 * 播報仲裁的核心邏輯。
 *
 * 刻意寫成純 Kotlin、無任何 Android 依賴、且不自己讀時鐘 —— 時間由呼叫端傳入。
 * 這樣才能用單元測試完整驗證「危險警示是否真的能打斷長文朗讀」這類
 * 攸關安全的行為，而不必啟動模擬器。
 *
 * 這個類別本身不是執行緒安全的，必須由單一 coroutine 或 actor 存取。
 */
class AnnouncementQueue {

    private val pending = ArrayDeque<Announcement>()
    private val lastAnnouncedAt = mutableMapOf<String, Long>()

    private var current: Announcement? = null

    /** 目前正在播報的內容，沒有則為 null。 */
    val currentAnnouncement: Announcement? get() = current

    /** 等待播報的數量。 */
    val pendingCount: Int get() = pending.size

    /**
     * 提交一則播報。
     *
     * @param nowMillis 現在時間，由呼叫端提供以利測試。
     * @return 這次提交造成的結果。
     */
    fun submit(announcement: Announcement, nowMillis: Long): SubmitOutcome {
        val key = announcement.dedupeKey
        if (key != null) {
            val last = lastAnnouncedAt[key]
            if (last != null && nowMillis - last < announcement.dedupeWindowMillis) {
                return SubmitOutcome.Suppressed
            }
        }

        val active = current

        // 沒有東西在播 -> 直接播。
        if (active == null) {
            return startNow(announcement, nowMillis, interrupted = null)
        }

        // 優先級不足以打斷 -> 排隊。
        if (!announcement.priority.canInterrupt(active.priority)) {
            pending.addLast(announcement)
            return SubmitOutcome.Queued
        }

        // 可以打斷。若被打斷的內容是可續播的，把它排回佇列最前面。
        if (active.resumable) {
            pending.addFirst(active)
        }
        return startNow(announcement, nowMillis, interrupted = active)
    }

    /**
     * 目前這則播報完成時呼叫，取出下一則。
     *
     * @return 接下來要播的內容，佇列空了則為 null。
     */
    fun onCompleted(): Announcement? {
        current = pending.removeFirstOrNull()
        return current
    }

    /**
     * 清空所有播報（使用者說「停」）。
     *
     * 不清除去抖動紀錄 —— 使用者叫停之後，不該立刻又被同一則訊息轟炸。
     */
    fun clear() {
        pending.clear()
        current = null
    }

    /**
     * 只清除某個優先級以下的內容。
     * 用於「導航結束了，但正在播的危險警示要留著」這類情境。
     */
    fun clearAtOrBelow(priority: AnnouncementPriority) {
        pending.removeAll { it.priority.ordinal >= priority.ordinal }
        if (current?.priority?.ordinal?.let { it >= priority.ordinal } == true) {
            current = null
        }
    }

    private fun startNow(
        announcement: Announcement,
        nowMillis: Long,
        interrupted: Announcement?,
    ): SubmitOutcome {
        current = announcement
        announcement.dedupeKey?.let { lastAnnouncedAt[it] = nowMillis }
        return SubmitOutcome.Speaking(announcement, interrupted)
    }

    sealed interface SubmitOutcome {

        /** 立刻開始播報。[interrupted] 是被打斷的內容（若有）。 */
        data class Speaking(
            val announcement: Announcement,
            val interrupted: Announcement?,
        ) : SubmitOutcome

        /** 已排入佇列，等前面播完。 */
        data object Queued : SubmitOutcome

        /** 因為去抖動而被抑制，不會播報。 */
        data object Suppressed : SubmitOutcome
    }
}
