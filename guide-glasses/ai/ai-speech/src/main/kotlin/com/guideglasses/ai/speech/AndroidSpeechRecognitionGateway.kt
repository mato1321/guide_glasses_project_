package com.guideglasses.ai.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.speech.SpeechEvent
import com.guideglasses.core.domain.speech.SpeechRecognitionGateway
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

/**
 * 以 Android 內建 SpeechRecognizer 實作的語音辨識。
 *
 * 取代舊做法（錄整段 m4a 上傳到 FastAPI 再打 OpenAI Whisper）：
 * 本機辨識免費、延遲低、可離線，而且是串流式的。
 *
 * SpeechRecognizer 必須在主執行緒建立與呼叫，這是 Android 的硬性要求，
 * 因此整個 Flow 綁在 [Dispatchers.Main]。
 */
class AndroidSpeechRecognitionGateway(
    context: Context,
) : SpeechRecognitionGateway {

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    override val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    override fun listen(preferOffline: Boolean): Flow<SpeechEvent> = callbackFlow {
        if (!isAvailable) {
            trySend(
                SpeechEvent.Failed(
                    AppError.CapabilityUnavailable("speech-recognition", "裝置沒有語音辨識服務"),
                ),
            )
            close()
            return@callbackFlow
        }

        val instance = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = instance

        instance.setRecognitionListener(object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                trySend(SpeechEvent.ReadyForSpeech)
            }

            override fun onBeginningOfSpeech() {
                trySend(SpeechEvent.SpeechStarted)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                firstResult(partialResults)?.let { trySend(SpeechEvent.PartialResult(it)) }
            }

            override fun onResults(results: Bundle?) {
                val text = firstResult(results)
                if (text.isNullOrBlank()) {
                    trySend(SpeechEvent.Failed(AppError.NoResult("辨識結果為空")))
                } else {
                    trySend(SpeechEvent.FinalResult(text))
                }
                close()
            }

            override fun onError(error: Int) {
                trySend(SpeechEvent.Failed(mapError(error)))
                close()
            }

            override fun onEndOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        instance.startListening(buildIntent(preferOffline))

        awaitClose {
            runCatching {
                instance.stopListening()
                instance.destroy()
            }.onFailure { Log.w(TAG, "釋放 SpeechRecognizer 失敗", it) }
            if (recognizer === instance) recognizer = null
        }
    }.flowOn(Dispatchers.Main)

    override fun cancel() {
        runCatching { recognizer?.cancel() }
            .onFailure { Log.w(TAG, "取消辨識失敗", it) }
    }

    override fun shutdown() {
        runCatching { recognizer?.destroy() }
            .onFailure { Log.w(TAG, "關閉辨識器失敗", it) }
        recognizer = null
    }

    private fun buildIntent(preferOffline: Boolean) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE_TAG)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

            // 離線優先：導盲場景常在地下道、騎樓這類收訊不佳的地方。
            if (preferOffline && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()

    /**
     * 把 SpeechRecognizer 的錯誤碼轉成領域錯誤。
     *
     * 重點是**不要把錯誤碼直接播報出去**。使用者聽不懂「錯誤 7」，
     * 上層會依這裡的分類挑一句人話。
     */
    private fun mapError(code: Int): AppError = when (code) {
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> AppError.NoNetwork("speech recognizer network error $code")

        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> AppError.NoResult("沒有聽到內容 (code=$code)")

        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            AppError.PermissionDenied("android.permission.RECORD_AUDIO")

        else -> AppError.Unknown("speech recognizer error $code")
    }

    private companion object {
        const val TAG = "SpeechGateway"
        const val LANGUAGE_TAG = "zh-TW"
    }
}
