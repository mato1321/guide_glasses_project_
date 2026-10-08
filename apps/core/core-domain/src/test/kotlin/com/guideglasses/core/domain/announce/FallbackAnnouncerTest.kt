package com.guideglasses.core.domain.announce

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [FallbackAnnouncer] 的重點全部圍繞同一件事：
 * **`onDone` 必須恰好被呼叫一次**，否則使用者會從此聽不到任何提示。
 */
class FallbackAnnouncerTest {

    /**
     * 可以隨意擺佈的假實作。
     *
     * [available] 是 `var`，因為真實世界裡它會變 —— Android TTS 初始化
     * 是非同步的，開機瞬間問到的是 false，幾百毫秒後才變 true。
     */
    private class FakeAnnouncer(
        val name: String,
        var available: Boolean = true,
        var throwOnSpeak: Boolean = false,
        /** 丟例外前先偷偷回報完成，模擬「講完才炸」的實作。 */
        var reportDoneBeforeThrowing: Boolean = false,
    ) : Announcer {

        val spoken = mutableListOf<String>()
        var stopCount = 0
        var shutdownCount = 0
        private var pendingDone: (() -> Unit)? = null

        override val isAvailable: Boolean get() = available

        override var isSpeaking: Boolean = false
            private set

        override fun speak(announcement: Announcement, onDone: () -> Unit) {
            if (throwOnSpeak) {
                if (reportDoneBeforeThrowing) onDone()
                throw IllegalStateException("$name 壞了")
            }
            spoken += announcement.text
            isSpeaking = true
            pendingDone = onDone
        }

        override fun stop() {
            stopCount++
            isSpeaking = false
            pendingDone = null
        }

        override fun shutdown() {
            shutdownCount++
        }

        fun finishCurrent() {
            val done = pendingDone ?: return
            pendingDone = null
            isSpeaking = false
            done()
        }
    }

    private fun announcement(text: String = "前方有車") = Announcement(
        text = text,
        priority = AnnouncementPriority.CRITICAL,
    )

    private fun countingDone(): Pair<() -> Unit, () -> Int> {
        var count = 0
        return ({ count++; Unit }) to ({ count })
    }

    @Test
    fun `優先用清單裡第一個可用的`() {
        val first = FakeAnnouncer("first")
        val second = FakeAnnouncer("second")
        val announcer = FallbackAnnouncer(listOf(first, second))

        announcer.speak(announcement()) {}

        assertThat(first.spoken).containsExactly("前方有車")
        assertThat(second.spoken).isEmpty()
    }

    @Test
    fun `第一個不可用時換下一個`() {
        val unavailable = FakeAnnouncer("android-tts", available = false)
        val offline = FakeAnnouncer("offline")
        val announcer = FallbackAnnouncer(listOf(unavailable, offline))

        announcer.speak(announcement()) {}

        assertThat(unavailable.spoken).isEmpty()
        assertThat(offline.spoken).containsExactly("前方有車")
    }

    @Test
    fun `全部都不可用時仍然要回報完成`() {
        // 這是最重要的一個測試：少了這個保證，AnnouncementManager 的佇列
        // 會永遠停在第一則，使用者從此聽不到任何東西。
        val a = FakeAnnouncer("a", available = false)
        val b = FakeAnnouncer("b", available = false)
        val (onDone, doneCount) = countingDone()

        FallbackAnnouncer(listOf(a, b)).speak(announcement(), onDone)

        assertThat(doneCount()).isEqualTo(1)
    }

    @Test
    fun `丟例外的實作會被跳過並換下一個`() {
        val broken = FakeAnnouncer("broken", throwOnSpeak = true)
        val working = FakeAnnouncer("working")
        val failures = mutableListOf<String>()
        val announcer = FallbackAnnouncer(listOf(broken, working)) { who, _ ->
            failures += (who as FakeAnnouncer).name
        }

        announcer.speak(announcement()) {}

        assertThat(failures).containsExactly("broken")
        assertThat(working.spoken).containsExactly("前方有車")
    }

    @Test
    fun `已經回報完成才丟例外時不會再找下一個`() {
        // 否則同一則播報會產生兩次 onDone，佇列跳號 —— 使用者會漏聽一則，
        // 而那則可能正是危險警示。
        val broken = FakeAnnouncer(
            "broken",
            throwOnSpeak = true,
            reportDoneBeforeThrowing = true,
        )
        val next = FakeAnnouncer("next")
        val (onDone, doneCount) = countingDone()

        FallbackAnnouncer(listOf(broken, next)).speak(announcement(), onDone)

        assertThat(doneCount()).isEqualTo(1)
        assertThat(next.spoken).isEmpty()
    }

    @Test
    fun `底層重複呼叫完成回呼也只會往上報一次`() {
        val flaky = FakeAnnouncer("flaky")
        val (onDone, doneCount) = countingDone()
        FallbackAnnouncer(listOf(flaky)).speak(announcement(), onDone)

        flaky.finishCurrent()
        // 真實的 TextToSpeech 就出現過 onDone 與 onError 都被呼叫的情形。
        flaky.finishCurrent()

        assertThat(doneCount()).isEqualTo(1)
    }

    @Test
    fun `每次播報都重新判斷可用性`() {
        // Android TTS 初始化是非同步的：第一則播報時還沒好，第二則就好了。
        val lateReady = FakeAnnouncer("android-tts", available = false)
        val offline = FakeAnnouncer("offline")
        val announcer = FallbackAnnouncer(listOf(lateReady, offline))

        announcer.speak(announcement("第一則")) {}
        lateReady.available = true
        announcer.speak(announcement("第二則")) {}

        assertThat(offline.spoken).containsExactly("第一則")
        assertThat(lateReady.spoken).containsExactly("第二則")
    }

    @Test
    fun `stop 只停正在講話的那一個`() {
        val unavailable = FakeAnnouncer("android-tts", available = false)
        val offline = FakeAnnouncer("offline")
        val announcer = FallbackAnnouncer(listOf(unavailable, offline))

        announcer.speak(announcement()) {}
        announcer.stop()

        assertThat(offline.stopCount).isEqualTo(1)
        assertThat(unavailable.stopCount).isEqualTo(0)
    }

    @Test
    fun `isSpeaking 反映目前接手的那一個`() {
        val offline = FakeAnnouncer("offline")
        val announcer = FallbackAnnouncer(listOf(offline))

        assertThat(announcer.isSpeaking).isFalse()
        announcer.speak(announcement()) {}
        assertThat(announcer.isSpeaking).isTrue()
        offline.finishCurrent()
        assertThat(announcer.isSpeaking).isFalse()
    }

    @Test
    fun `任何一個可用就算可用`() {
        val a = FakeAnnouncer("a", available = false)
        val b = FakeAnnouncer("b", available = true)

        assertThat(FallbackAnnouncer(listOf(a, b)).isAvailable).isTrue()

        b.available = false
        assertThat(FallbackAnnouncer(listOf(a, b)).isAvailable).isFalse()
    }

    @Test
    fun `shutdown 會關掉全部，其中一個失敗也不影響其他`() {
        val broken = object : Announcer {
            override val isSpeaking = false
            override fun speak(announcement: Announcement, onDone: () -> Unit) = Unit
            override fun stop() = Unit
            override fun shutdown(): Unit = throw IllegalStateException("關不掉")
        }
        val healthy = FakeAnnouncer("healthy")

        FallbackAnnouncer(listOf(broken, healthy)).shutdown()

        assertThat(healthy.shutdownCount).isEqualTo(1)
    }

    @Test
    fun `候選清單不可為空`() {
        val error = runCatching { FallbackAnnouncer(emptyList()) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `LogOnlyAnnouncer 會把該唸的話交出去並立刻回報完成`() {
        // 眼鏡上聽不到聲音，這些字是唯一的觀察手段。
        val captured = mutableListOf<String>()
        val (onDone, doneCount) = countingDone()

        LogOnlyAnnouncer { captured += it.text }.speak(announcement(), onDone)

        assertThat(captured).containsExactly("前方有車")
        assertThat(doneCount()).isEqualTo(1)
    }
}
