package com.guideglasses.core.domain.announce

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 這組測試守護的是安全行為，不只是資料結構。
 * 「危險警示能不能打斷正在朗讀的長文」對視障使用者而言是攸關安危的。
 */
class AnnouncementQueueTest {

    private val queue = AnnouncementQueue()

    private fun announcement(
        text: String,
        priority: AnnouncementPriority,
        dedupeKey: String? = null,
        resumable: Boolean = false,
    ) = Announcement(
        text = text,
        priority = priority,
        dedupeKey = dedupeKey,
        resumable = resumable,
    )

    @Test
    fun `佇列為空時直接播報`() {
        val result = queue.submit(announcement("前方有車", AnnouncementPriority.CRITICAL), 0L)

        assertThat(result).isInstanceOf(AnnouncementQueue.SubmitOutcome.Speaking::class.java)
        assertThat(queue.currentAnnouncement?.text).isEqualTo("前方有車")
    }

    @Test
    fun `危險警示可以打斷一般朗讀`() {
        queue.submit(announcement("這是一段很長的文件內容", AnnouncementPriority.AMBIENT), 0L)

        val result = queue.submit(
            announcement("停，右方有車", AnnouncementPriority.CRITICAL),
            100L,
        )

        assertThat(result).isInstanceOf(AnnouncementQueue.SubmitOutcome.Speaking::class.java)
        val speaking = result as AnnouncementQueue.SubmitOutcome.Speaking
        assertThat(speaking.announcement.text).isEqualTo("停，右方有車")
        assertThat(speaking.interrupted?.text).isEqualTo("這是一段很長的文件內容")
        assertThat(queue.currentAnnouncement?.text).isEqualTo("停，右方有車")
    }

    @Test
    fun `一般朗讀不能打斷危險警示`() {
        queue.submit(announcement("前方有車", AnnouncementPriority.CRITICAL), 0L)

        val result = queue.submit(announcement("今天天氣不錯", AnnouncementPriority.AMBIENT), 100L)

        assertThat(result).isEqualTo(AnnouncementQueue.SubmitOutcome.Queued)
        assertThat(queue.currentAnnouncement?.text).isEqualTo("前方有車")
        assertThat(queue.pendingCount).isEqualTo(1)
    }

    @Test
    fun `可續播的內容被打斷後會排回最前面`() {
        queue.submit(
            announcement("長篇文件", AnnouncementPriority.AMBIENT, resumable = true),
            0L,
        )
        queue.submit(announcement("危險", AnnouncementPriority.CRITICAL), 100L)

        val next = queue.onCompleted()

        assertThat(next?.text).isEqualTo("長篇文件")
    }

    @Test
    fun `不可續播的內容被打斷後就丟棄`() {
        queue.submit(
            announcement("前方三十公尺右轉", AnnouncementPriority.NAVIGATION, resumable = false),
            0L,
        )
        queue.submit(announcement("危險", AnnouncementPriority.CRITICAL), 100L)

        val next = queue.onCompleted()

        assertThat(next).isNull()
    }

    @Test
    fun `相同 dedupeKey 在時間窗內不重複播報`() {
        val first = queue.submit(
            announcement("前方是王老師", AnnouncementPriority.USER_RESPONSE, dedupeKey = "face:王老師"),
            0L,
        )
        queue.onCompleted()

        val second = queue.submit(
            announcement("前方是王老師", AnnouncementPriority.USER_RESPONSE, dedupeKey = "face:王老師"),
            5_000L,
        )

        assertThat(first).isInstanceOf(AnnouncementQueue.SubmitOutcome.Speaking::class.java)
        assertThat(second).isEqualTo(AnnouncementQueue.SubmitOutcome.Suppressed)
    }

    @Test
    fun `超過時間窗後同一則可以再次播報`() {
        queue.submit(
            announcement("前方是王老師", AnnouncementPriority.USER_RESPONSE, dedupeKey = "face:王老師"),
            0L,
        )
        queue.onCompleted()

        val second = queue.submit(
            announcement("前方是王老師", AnnouncementPriority.USER_RESPONSE, dedupeKey = "face:王老師"),
            Announcement.DEFAULT_DEDUPE_WINDOW_MILLIS + 1,
        )

        assertThat(second).isInstanceOf(AnnouncementQueue.SubmitOutcome.Speaking::class.java)
    }

    @Test
    fun `不同人的辨識結果不會互相抑制`() {
        queue.submit(
            announcement("前方是王老師", AnnouncementPriority.USER_RESPONSE, dedupeKey = "face:王老師"),
            0L,
        )
        queue.onCompleted()

        val second = queue.submit(
            announcement("前方是媽媽", AnnouncementPriority.USER_RESPONSE, dedupeKey = "face:媽媽"),
            100L,
        )

        assertThat(second).isInstanceOf(AnnouncementQueue.SubmitOutcome.Speaking::class.java)
    }

    @Test
    fun `同優先級先到先播不互相打斷`() {
        queue.submit(announcement("第一則", AnnouncementPriority.NAVIGATION), 0L)
        val result = queue.submit(announcement("第二則", AnnouncementPriority.NAVIGATION), 100L)

        assertThat(result).isEqualTo(AnnouncementQueue.SubmitOutcome.Queued)
        assertThat(queue.currentAnnouncement?.text).isEqualTo("第一則")
        assertThat(queue.onCompleted()?.text).isEqualTo("第二則")
    }

    @Test
    fun `使用者說停會清空所有播報`() {
        queue.submit(announcement("正在播", AnnouncementPriority.AMBIENT), 0L)
        queue.submit(announcement("排隊中", AnnouncementPriority.AMBIENT), 100L)

        queue.clear()

        assertThat(queue.currentAnnouncement).isNull()
        assertThat(queue.pendingCount).isEqualTo(0)
    }

    @Test
    fun `clearAtOrBelow 會保留更高優先級的內容`() {
        queue.submit(announcement("危險警示", AnnouncementPriority.CRITICAL), 0L)
        queue.submit(announcement("導航提示", AnnouncementPriority.NAVIGATION), 100L)
        queue.submit(announcement("閒聊", AnnouncementPriority.AMBIENT), 200L)

        queue.clearAtOrBelow(AnnouncementPriority.NAVIGATION)

        assertThat(queue.currentAnnouncement?.text).isEqualTo("危險警示")
        assertThat(queue.pendingCount).isEqualTo(0)
    }

    @Test
    fun `播報完成後依序取出佇列內容`() {
        queue.submit(announcement("第一", AnnouncementPriority.AMBIENT), 0L)
        queue.submit(announcement("第二", AnnouncementPriority.AMBIENT), 100L)
        queue.submit(announcement("第三", AnnouncementPriority.AMBIENT), 200L)

        assertThat(queue.currentAnnouncement?.text).isEqualTo("第一")
        assertThat(queue.onCompleted()?.text).isEqualTo("第二")
        assertThat(queue.onCompleted()?.text).isEqualTo("第三")
        assertThat(queue.onCompleted()).isNull()
    }

    @Test
    fun `空白播報內容會被拒絕`() {
        val error = runCatching {
            Announcement(text = "   ", priority = AnnouncementPriority.AMBIENT)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `優先級排序符合設計`() {
        assertThat(
            AnnouncementPriority.CRITICAL.canInterrupt(AnnouncementPriority.USER_RESPONSE),
        ).isTrue()
        assertThat(
            AnnouncementPriority.USER_RESPONSE.canInterrupt(AnnouncementPriority.NAVIGATION),
        ).isTrue()
        assertThat(
            AnnouncementPriority.NAVIGATION.canInterrupt(AnnouncementPriority.AMBIENT),
        ).isTrue()
        assertThat(
            AnnouncementPriority.AMBIENT.canInterrupt(AnnouncementPriority.CRITICAL),
        ).isFalse()
        assertThat(
            AnnouncementPriority.NAVIGATION.canInterrupt(AnnouncementPriority.NAVIGATION),
        ).isFalse()
    }
}
