package com.guideglasses.core.domain.announce

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 全系統唯一的語音出口。
 *
 * 六個功能都會想說話，但使用者只有一雙耳朵。所有播報一律經過這裡排序，
 * 任何模組都不該自己持有 TextToSpeech 或 MediaPlayer —— 那正是舊專案
 * 三套播放器互相蓋台的成因。
 *
 * 純 Kotlin，時鐘由建構子注入，因此可完整以單元測試驗證。
 */
class AnnouncementManager(
    private val announcer: Announcer,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val queue = AnnouncementQueue()
    private val mutex = Mutex()

    /**
     * 目前這則播報的識別序號。
     *
     * [Announcer] 的完成回呼可能在該則播報早已被打斷之後才姍姍來遲。
     * 若不比對序號就直接取下一則，會造成佇列跳號 —— 使用者會漏聽一則訊息，
     * 而那則可能正是危險警示。
     */
    private var speakingToken: Long = 0L

    /** 提交一則播報。呼叫端不需等待，實際播報在 [scope] 中進行。 */
    fun announce(announcement: Announcement) {
        scope.launch { submit(announcement) }
    }

    /** 使用者說「停」。清空佇列並立即靜音。 */
    fun stopAll() {
        scope.launch {
            mutex.withLock {
                queue.clear()
                speakingToken++
            }
            announcer.stop()
        }
    }

    /**
     * 清除某個優先級以下的播報，保留更緊急的內容。
     * 例如導航結束時清掉導航提示，但正在播的危險警示要留著。
     */
    fun clearAtOrBelow(priority: AnnouncementPriority) {
        scope.launch {
            val stillSpeaking = mutex.withLock {
                val hadCurrent = queue.currentAnnouncement
                queue.clearAtOrBelow(priority)
                val kept = queue.currentAnnouncement
                if (hadCurrent != null && kept == null) speakingToken++
                kept != null
            }
            if (!stillSpeaking) announcer.stop()
        }
    }

    private suspend fun submit(announcement: Announcement) {
        val started = mutex.withLock {
            when (val outcome = queue.submit(announcement, now())) {
                is AnnouncementQueue.SubmitOutcome.Speaking -> {
                    speakingToken++
                    outcome
                }

                AnnouncementQueue.SubmitOutcome.Queued,
                AnnouncementQueue.SubmitOutcome.Suppressed,
                -> null
            }
        } ?: return

        // 有東西被打斷時先讓 announcer 閉嘴，避免兩句話疊在一起。
        if (started.interrupted != null) {
            announcer.stop()
        }
        speakNow(started.announcement, speakingToken)
    }

    private fun speakNow(announcement: Announcement, token: Long) {
        announcer.speak(announcement) {
            scope.launch { onSpeakFinished(token) }
        }
    }

    private suspend fun onSpeakFinished(token: Long) {
        val next = mutex.withLock {
            // 這則早就被打斷了，它的完成回呼不該推動佇列。
            if (token != speakingToken) return@withLock null
            queue.onCompleted()?.also { speakingToken++ }
        } ?: return

        speakNow(next, speakingToken)
    }
}
