package com.guideglasses.ai.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.guideglasses.core.domain.announce.Announcement
import com.guideglasses.core.domain.announce.AnnouncementPriority
import com.guideglasses.core.domain.announce.Announcer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 以 Android 內建 TextToSpeech 實作的語音輸出。
 *
 * 取代舊做法：「文字 → 上傳後端 → OpenAI tts-1 合成 mp3 → 下載 →
 * MediaPlayer 播放」。那條路徑對一句「前方有車」要兩到三秒 ——
 * 以正常步行速度計算，車早就到了。本機合成約 50 毫秒，而且離線可用、零成本。
 *
 * 舊專案還用 `translate.google.com/translate_tts` 當備援。那是非公開 API，
 * 隨時可能停止服務，不能用於正式產品，此處不予保留。
 */
class AndroidTtsAnnouncer(
    context: Context,
    private val onReady: (Boolean) -> Unit = {},
) : Announcer {

    private val appContext = context.applicationContext
    private val ready = AtomicBoolean(false)
    private val speaking = AtomicBoolean(false)
    private val utteranceSeq = AtomicLong(0)

    /** utteranceId -> 完成回呼。TTS 的回呼在別的執行緒，需要併發安全的容器。 */
    private val pendingCallbacks = ConcurrentHashMap<String, () -> Unit>()

    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            val ok = status == TextToSpeech.SUCCESS && configureLanguage()
            ready.set(ok)
            if (!ok) Log.e(TAG, "TTS 初始化失敗，status=$status")
            onReady(ok)
        }
    }

    override val isSpeaking: Boolean
        get() = speaking.get()

    override fun speak(announcement: Announcement, onDone: () -> Unit) {
        val engine = tts
        if (engine == null || !ready.get()) {
            // 契約要求 onDone 一定要被呼叫一次，否則 AnnouncementManager 會卡住
            // 不再取下一則 —— 那會讓使用者從此聽不到任何提示。
            Log.e(TAG, "TTS 尚未就緒，略過：${announcement.text}")
            onDone()
            return
        }

        val utteranceId = "gg-${utteranceSeq.incrementAndGet()}"
        pendingCallbacks[utteranceId] = onDone
        speaking.set(true)

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        // 佇列策略由 AnnouncementManager 決定，這裡一律 FLUSH：
        // 它送過來的每一則都已經是「現在就該播的那一則」。
        val result = engine.speak(
            announcement.text,
            TextToSpeech.QUEUE_FLUSH,
            params,
            utteranceId,
        )

        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "TTS speak 失敗：${announcement.text}")
            finish(utteranceId)
        }
    }

    override fun stop() {
        tts?.stop()
        speaking.set(false)
        // 被 stop 的內容不會收到 onDone，這裡不主動觸發 ——
        // AnnouncementManager 的 token 機制會忽略它們。
        pendingCallbacks.clear()
    }

    override fun shutdown() {
        pendingCallbacks.clear()
        speaking.set(false)
        ready.set(false)
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    /** 依優先級調整語速。危險警示講快一點，長文朗讀慢一點比較好聽懂。 */
    fun applyRateFor(priority: AnnouncementPriority) {
        val rate = when (priority) {
            AnnouncementPriority.CRITICAL -> 1.15f
            AnnouncementPriority.USER_RESPONSE -> 1.0f
            AnnouncementPriority.NAVIGATION -> 1.0f
            AnnouncementPriority.AMBIENT -> 0.9f
        }
        tts?.setSpeechRate(rate)
    }

    private fun configureLanguage(): Boolean {
        val engine = tts ?: return false

        val locales = listOf(Locale.TAIWAN, Locale.TRADITIONAL_CHINESE, Locale.SIMPLIFIED_CHINESE)
        val supported = locales.firstOrNull { locale ->
            val result = engine.setLanguage(locale)
            result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        }

        if (supported == null) {
            Log.e(TAG, "找不到可用的中文語音資料")
            return false
        }

        engine.setSpeechRate(1.0f)
        engine.setPitch(1.0f)

        // 走無障礙音訊通道，這樣即使使用者把媒體音量調低，
        // 導盲提示仍然聽得見。
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                finish(utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS 播報錯誤 id=$utteranceId")
                finish(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS 播報錯誤 id=$utteranceId code=$errorCode")
                finish(utteranceId)
            }
        })

        return true
    }

    private fun finish(utteranceId: String?) {
        speaking.set(false)
        val callback = utteranceId?.let { pendingCallbacks.remove(it) } ?: return
        callback()
    }

    private companion object {
        const val TAG = "TtsAnnouncer"
    }
}
