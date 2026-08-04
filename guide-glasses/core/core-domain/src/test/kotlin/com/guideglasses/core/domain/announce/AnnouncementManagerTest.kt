package com.guideglasses.core.domain.announce

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnnouncementManagerTest {

    /**
     * 假的語音輸出。刻意手動控制「播完」的時機，
     * 才能測到真實世界中「回呼比打斷還晚到」的競態。
     */
    private class FakeAnnouncer : Announcer {

        val spoken = mutableListOf<String>()
        var stopCount = 0
        private var pendingDone: (() -> Unit)? = null

        override var isSpeaking: Boolean = false
            private set

        override fun speak(announcement: Announcement, onDone: () -> Unit) {
            spoken += announcement.text
            isSpeaking = true
            pendingDone = onDone
        }

        override fun stop() {
            stopCount++
            isSpeaking = false
        }

        override fun shutdown() = Unit

        /** 模擬目前這則播報自然播完。 */
        fun finishCurrent() {
            val done = pendingDone ?: return
            pendingDone = null
            isSpeaking = false
            done()
        }

        /**
         * 模擬「已經被打斷、卻延遲送達」的完成回呼。
         * 真實的 TextToSpeech.UtteranceProgressListener 就會這樣。
         */
        fun deliverStaleCallback(done: () -> Unit) = done()

        fun captureCurrentCallback(): (() -> Unit)? = pendingDone
    }

    private fun announcement(
        text: String,
        priority: AnnouncementPriority,
        dedupeKey: String? = null,
        resumable: Boolean = false,
    ) = Announcement(text, priority, dedupeKey, resumable = resumable)

    @Test
    fun `提交後會實際發聲`() = runTest {
        val announcer = FakeAnnouncer()
        val manager = AnnouncementManager(announcer, CoroutineScope(UnconfinedTestDispatcher(testScheduler))) { 0L }

        manager.announce(announcement("前方有車", AnnouncementPriority.CRITICAL))

        assertThat(announcer.spoken).containsExactly("前方有車")
    }

    @Test
    fun `危險警示會先讓目前的播報閉嘴再開口`() = runTest {
        val announcer = FakeAnnouncer()
        val manager = AnnouncementManager(announcer, CoroutineScope(UnconfinedTestDispatcher(testScheduler))) { 0L }

        manager.announce(announcement("很長的文件內容", AnnouncementPriority.AMBIENT))
        assertThat(announcer.stopCount).isEqualTo(0)

        manager.announce(announcement("停，右方有車", AnnouncementPriority.CRITICAL))

        assertThat(announcer.stopCount).isEqualTo(1)
        assertThat(announcer.spoken).containsExactly("很長的文件內容", "停，右方有車").inOrder()
    }

    @Test
    fun `播完一則會自動接著播佇列中的下一則`() = runTest {
        val announcer = FakeAnnouncer()
        val manager = AnnouncementManager(announcer, CoroutineScope(UnconfinedTestDispatcher(testScheduler))) { 0L }

        manager.announce(announcement("第一則", AnnouncementPriority.NAVIGATION))
        manager.announce(announcement("第二則", AnnouncementPriority.NAVIGATION))
        assertThat(announcer.spoken).containsExactly("第一則")

        announcer.finishCurrent()

        assertThat(announcer.spoken).containsExactly("第一則", "第二則").inOrder()
    }

    @Test
    fun `過期的完成回呼不會讓佇列跳號`() = runTest {
        val announcer = FakeAnnouncer()
        val manager = AnnouncementManager(announcer, CoroutineScope(UnconfinedTestDispatcher(testScheduler))) { 0L }

        // 長文開始播，抓住它的完成回呼。
        manager.announce(
            announcement("長篇文件", AnnouncementPriority.AMBIENT, resumable = true),
        )
        val staleCallback = announcer.captureCurrentCallback()!!

        // 危險警示把它打斷。
        manager.announce(announcement("危險", AnnouncementPriority.CRITICAL))
        assertThat(announcer.spoken).containsExactly("長篇文件", "危險").inOrder()

        // 長文那則遲來的完成回呼現在才送達 —— 不該推動佇列。
        announcer.deliverStaleCallback(staleCallback)

        assertThat(announcer.spoken).containsExactly("長篇文件", "危險").inOrder()

        // 危險警示自己播完之後，長文才應該續播。
        announcer.finishCurrent()

        assertThat(announcer.spoken)
            .containsExactly("長篇文件", "危險", "長篇文件").inOrder()
    }

    @Test
    fun `說停會清空佇列並靜音`() = runTest {
        val announcer = FakeAnnouncer()
        val manager = AnnouncementManager(announcer, CoroutineScope(UnconfinedTestDispatcher(testScheduler))) { 0L }

        manager.announce(announcement("正在播", AnnouncementPriority.AMBIENT))
        manager.announce(announcement("排隊中", AnnouncementPriority.AMBIENT))

        manager.stopAll()

        assertThat(announcer.stopCount).isEqualTo(1)
        assertThat(announcer.spoken).containsExactly("正在播")
    }

    @Test
    fun `說停之後遲來的回呼不會讓被清掉的內容復活`() = runTest {
        val announcer = FakeAnnouncer()
        val manager = AnnouncementManager(announcer, CoroutineScope(UnconfinedTestDispatcher(testScheduler))) { 0L }

        manager.announce(announcement("正在播", AnnouncementPriority.AMBIENT))
        manager.announce(announcement("排隊中", AnnouncementPriority.AMBIENT))
        val staleCallback = announcer.captureCurrentCallback()!!

        manager.stopAll()

        announcer.deliverStaleCallback(staleCallback)

        assertThat(announcer.spoken).containsExactly("正在播")
    }

    @Test
    fun `去抖動在管理層一樣生效`() = runTest {
        val announcer = FakeAnnouncer()
        var clock = 0L
        val manager = AnnouncementManager(announcer, CoroutineScope(UnconfinedTestDispatcher(testScheduler))) { clock }

        manager.announce(
            announcement("前方是王老師", AnnouncementPriority.USER_RESPONSE, dedupeKey = "face:王老師"),
        )
        announcer.finishCurrent()

        clock = 3_000L
        manager.announce(
            announcement("前方是王老師", AnnouncementPriority.USER_RESPONSE, dedupeKey = "face:王老師"),
        )

        assertThat(announcer.spoken).containsExactly("前方是王老師")
    }

    @Test
    fun `clearAtOrBelow 保留更高優先級且不誤觸靜音`() = runTest {
        val announcer = FakeAnnouncer()
        val manager = AnnouncementManager(announcer, CoroutineScope(UnconfinedTestDispatcher(testScheduler))) { 0L }

        manager.announce(announcement("危險警示", AnnouncementPriority.CRITICAL))
        manager.announce(announcement("導航提示", AnnouncementPriority.NAVIGATION))

        manager.clearAtOrBelow(AnnouncementPriority.NAVIGATION)

        // 危險警示還在播，不該被靜音。
        assertThat(announcer.stopCount).isEqualTo(0)

        announcer.finishCurrent()

        // 導航提示已被清掉，不會接著播。
        assertThat(announcer.spoken).containsExactly("危險警示")
    }
}
