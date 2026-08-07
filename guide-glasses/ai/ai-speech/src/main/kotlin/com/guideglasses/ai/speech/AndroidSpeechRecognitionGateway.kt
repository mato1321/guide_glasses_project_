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
import com.guideglasses.core.domain.speech.SpeechCapability
import com.guideglasses.core.domain.speech.SpeechEvent
import com.guideglasses.core.domain.speech.SpeechRecognitionGateway
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
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

    /**
     * 這台裝置沒有 [LANGUAGE_TAG] 的離線語音包。
     *
     * 第一次失敗就記住，之後直接走線上。否則**每一句話**都要先付一次注定
     * 失敗的離線嘗試（實測小米機上是 error 12 加上重啟辨識的緩衝），
     * 而導盲助理的延遲是使用者站在路口等的時間。
     *
     * 只存在記憶體中，不落地：使用者去系統設定裝好語音包之後，
     * 重開 App 就會重新嘗試離線 —— 那才是我們想要的最終狀態。
     */
    @Volatile
    private var offlinePackUnavailable = false

    override val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    override fun listen(preferOffline: Boolean): Flow<SpeechEvent> = callbackFlow {
        if (!isAvailable) {
            trySend(
                SpeechEvent.Failed(
                    AppError.CapabilityUnavailable(
                        SpeechCapability.RECOGNITION,
                        "裝置沒有語音辨識服務",
                    ),
                ),
            )
            close()
            return@callbackFlow
        }

        val instance = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = instance

        // 已知這台裝置沒有離線包時，第一次就直接走線上，不再白跑一趟。
        val useOffline = preferOffline && !offlinePackUnavailable

        /** 已經因為缺離線語音包改走線上，避免無限重試。 */
        var retriedOnline = false

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

            /**
             * 錯誤碼一定要寫進 log。
             *
             * 使用者聽到的是一句人話（「沒有中文語音資料」），但排查的人需要
             * 知道底下到底是 12 還是 8 —— 少了這一行，`adb logcat -s SpeechGateway:*`
             * 在辨識失敗時什麼都印不出來。
             */
            override fun onError(error: Int) {
                Log.w(TAG, "辨識失敗 code=$error useOffline=$useOffline retried=$retriedOnline")

                // 裝置沒有 zh-TW 的離線語音包（Google SODA 回 12／13）。
                // 這不是「不能辨識」，只是「不能離線辨識」—— 有網路時改走線上
                // 再試一次。導盲場景離線優先是對的，但不該因此在有網路時也啞掉。
                if (!retriedOnline && useOffline && error in LANGUAGE_PACK_ERRORS) {
                    retriedOnline = true
                    offlinePackUnavailable = true
                    Log.i(TAG, "缺少離線語音包，改用線上辨識重試（後續直接走線上）")
                    launch {
                        // 辨識器剛回報錯誤，立刻重啟部分裝置會回 ERROR_RECOGNIZER_BUSY。
                        delay(RETRY_DELAY_MILLIS)
                        runCatching { instance.startListening(buildIntent(preferOffline = false)) }
                            .onFailure { failure ->
                                Log.w(TAG, "線上重試失敗", failure)
                                trySend(SpeechEvent.Failed(mapError(error)))
                                close()
                            }
                    }
                    return
                }

                trySend(SpeechEvent.Failed(mapError(error)))
                close()
            }

            override fun onEndOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        instance.startListening(buildIntent(useOffline))

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

        // 裝置上沒有這個語言的辨識資料。線上重試也失敗才會走到這裡，
        // 使用者要做的事很明確：去系統設定下載語音包。
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        -> AppError.CapabilityUnavailable(
            SpeechCapability.LANGUAGE_PACK,
            "缺少 $LANGUAGE_TAG 語音資料 (code=$code)",
        )

        // 上一輪還沒收乾淨，或短時間內請求太多次。等一下就好，不是壞掉。
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
        -> AppError.CapabilityUnavailable(SpeechCapability.BUSY, "辨識器忙碌 (code=$code)")

        // 麥克風被其他 App 佔用時最常見。
        SpeechRecognizer.ERROR_AUDIO ->
            AppError.CapabilityUnavailable(SpeechCapability.MICROPHONE, "音訊錯誤 (code=$code)")

        else -> AppError.Unknown("speech recognizer error $code")
    }

    private companion object {
        const val TAG = "SpeechGateway"
        const val LANGUAGE_TAG = "zh-TW"

        /** 缺離線語音包時改走線上的錯誤碼。 */
        private val LANGUAGE_PACK_ERRORS = setOf(
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        )

        /** 重啟辨識前的緩衝，太快會撞上 ERROR_RECOGNIZER_BUSY。 */
        private const val RETRY_DELAY_MILLIS = 150L
    }
}
