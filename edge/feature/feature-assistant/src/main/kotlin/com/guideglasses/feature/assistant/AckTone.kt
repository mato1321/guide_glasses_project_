package com.guideglasses.feature.assistant

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

/**
 * 被喚醒詞叫醒時的提示音。
 *
 * ### 為什麼不用語音說「我在」
 *
 * 眼鏡上合成一句話要一秒以上（實測 RTF 約 1.5），而那一秒全部算在
 * 使用者感受到的「怎麼還沒反應」裡。提示音是立即的。
 *
 * 而且對只靠聽覺的使用者，一個固定的短音比一句話更明確地表示
 * 「換你講了」—— 不必等它把話講完才知道可以開口。
 *
 * 語音留給真正有內容要傳達的時候。
 *
 * ### 為什麼走無障礙音訊通道
 *
 * 與所有導盲播報一致（`USAGE_ASSISTANCE_ACCESSIBILITY`）。使用者把媒體
 * 音量調低時，提示音仍然聽得見 —— 聽不到提示音就等於不知道它在聽了。
 */
internal class AckTone {

    private var generator: ToneGenerator? = null

    fun play() {
        val tone = generator ?: create()?.also { generator = it } ?: return
        runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP, DURATION_MILLIS) }
    }

    fun release() {
        runCatching { generator?.release() }
        generator = null
    }

    private fun create(): ToneGenerator? = runCatching {
        ToneGenerator(AudioManager.STREAM_ACCESSIBILITY, VOLUME)
    }.onFailure { error ->
        // 沒有提示音不影響功能，只是使用者少一個「可以講了」的訊號。
        Log.w(TAG, "提示音不可用", error)
    }.getOrNull()

    private companion object {
        const val TAG = "AckTone"
        const val VOLUME = ToneGenerator.MAX_VOLUME
        const val DURATION_MILLIS = 120
    }
}
