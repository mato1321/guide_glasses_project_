package com.guideglasses.ai.asr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.speech.SpeechEvent
import com.guideglasses.core.domain.speech.SpeechRecognitionGateway
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicBoolean

/**
 * APK 內建的離線語音辨識。**這是 Rokid Glasses 上唯一的語音輸入途徑。**
 *
 * ### 為什麼需要這個
 *
 * 眼鏡上完全沒有語音辨識服務：
 *
 * ```bash
 * adb shell settings get secure voice_recognition_service   # null
 * adb shell "cmd package query-services --brief -a android.speech.RecognitionService"
 * # No services found
 * ```
 *
 * 這不是套件可見性的問題（該指令用的是 shell 自己的可見性），
 * 是系統上真的一個都沒有。所以 `SpeechRecognizer` 那條路完全走不通，
 * 只能像 TTS 一樣**把辨識引擎當函式庫**：
 *
 * ```
 * 麥克風 → AudioRecord → 串流 zipformer CTC (ONNX) → 文字
 * ```
 *
 * ### 為什麼用串流模型而不是整段上傳
 *
 * 舊做法是「按一下開始錄 → 再按一下停止 → 上傳整段 → 等回傳」。
 * 串流辨識讓使用者說到一半就有部分結果，而且**能自動偵測說完**
 * （endpoint detection），不必記得再按一次 —— 對看不見按鈕的使用者，
 * 少一次操作差別很大。
 *
 * ### 已知限制
 *
 * - **只有中文**。模型是 zh 單語系。
 * - 這台眼鏡的 CPU 跑 TTS 的 RTF 就已經接近 1，ASR 的即時性尚未實測。
 */
class SherpaSpeechRecognitionGateway(
    context: Context,
) : SpeechRecognitionGateway {

    private val appContext = context.applicationContext

    /** 模型很大（26MB），只在第一次真的要聽的時候載入。 */
    @Volatile
    private var recognizer: OnlineRecognizer? = null

    @Volatile
    private var modelFailed = false

    /** 使用者或上層要求中止這一輪。 */
    private val cancelled = AtomicBoolean(false)

    /**
     * 模型打包在 APK 裡，所以只要拿得到麥克風權限就算可用。
     *
     * 不在這裡載入模型 —— 這個屬性會在 UI 執行緒被讀到。
     */
    override val isAvailable: Boolean
        get() = !modelFailed && hasMicPermission()

    override fun listen(preferOffline: Boolean): Flow<SpeechEvent> = flow {
        if (!hasMicPermission()) {
            emit(SpeechEvent.Failed(AppError.PermissionDenied(Manifest.permission.RECORD_AUDIO)))
            return@flow
        }

        val engine = ensureRecognizer()
        if (engine == null) {
            emit(SpeechEvent.Failed(AppError.CapabilityUnavailable(ERROR_MODEL)))
            return@flow
        }

        val record = createAudioRecord()
        if (record == null) {
            emit(SpeechEvent.Failed(AppError.CapabilityUnavailable(ERROR_MIC)))
            return@flow
        }

        cancelled.set(false)
        val stream = engine.createStream("")
        val buffer = FloatArray(CHUNK_SAMPLES)
        var lastPartial = ""
        var heardSpeech = false
        val startedAt = System.currentTimeMillis()

        try {
            record.startRecording()
            Log.i(TAG, "開始聆聽")
            emit(SpeechEvent.ReadyForSpeech)

            while (currentCoroutineContext().isActive && !cancelled.get()) {
                val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue

                stream.acceptWaveform(buffer.copyOf(read), SAMPLE_RATE)
                while (engine.isReady(stream)) engine.decode(stream)

                val text = engine.getResult(stream).text.trim()
                if (text.isNotEmpty() && text != lastPartial) {
                    if (!heardSpeech) {
                        heardSpeech = true
                        emit(SpeechEvent.SpeechStarted)
                    }
                    lastPartial = text
                    // 眼鏡上看不到畫面，這行是唯一能確認「有沒有聽到」的方式。
                    Log.d(TAG, "部分結果：$text")
                    emit(SpeechEvent.PartialResult(text))
                }

                // 模型自己判斷「這句講完了」。這正是不必按第二次按鈕的關鍵。
                if (engine.isEndpoint(stream)) {
                    engine.reset(stream)
                    if (lastPartial.isNotEmpty()) {
                        Log.i(TAG, "辨識完成：「$lastPartial」（耗時 ${System.currentTimeMillis() - startedAt}ms）")
                        emit(SpeechEvent.FinalResult(lastPartial))
                        return@flow
                    }
                }

                if (System.currentTimeMillis() - startedAt > timeoutFor(heardSpeech)) {
                    // 逾時也要給出結果 —— 對看不見的使用者，靜默等待
                    // 跟當機沒有區別。
                    if (lastPartial.isNotEmpty()) {
                        Log.i(TAG, "逾時但有結果：「$lastPartial」")
                        emit(SpeechEvent.FinalResult(lastPartial))
                    } else {
                        Log.w(TAG, "逾時，完全沒聽到人聲")
                        emit(SpeechEvent.Failed(AppError.CapabilityUnavailable(ERROR_NO_SPEECH)))
                    }
                    return@flow
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "辨識失敗", e)
            emit(SpeechEvent.Failed(AppError.CapabilityUnavailable(ERROR_MIC)))
        } finally {
            runCatching {
                record.stop()
                record.release()
            }
            runCatching { stream.release() }
        }
    }.flowOn(Dispatchers.IO)

    override fun cancel() {
        cancelled.set(true)
    }

    override fun shutdown() {
        cancel()
        runCatching { recognizer?.release() }
        recognizer = null
    }

    /** 沒講話時等久一點；已經開始講就縮短，避免說完還乾等。 */
    private fun timeoutFor(heardSpeech: Boolean): Long =
        if (heardSpeech) MAX_UTTERANCE_MILLIS else SILENCE_TIMEOUT_MILLIS

    @Synchronized
    private fun ensureRecognizer(): OnlineRecognizer? {
        recognizer?.let { return it }
        if (modelFailed) return null

        val started = System.currentTimeMillis()
        val engine = runCatching {
            OnlineRecognizer(
                assetManager = appContext.assets,
                config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                    modelConfig = OnlineModelConfig(
                        zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = "$ASSET_DIR/model.int8.onnx"),
                        tokens = "$ASSET_DIR/tokens.txt",
                        // 與 TTS 同一顆 CPU，而且兩者可能同時在跑
                        // （使用者說話時上一則播報還沒結束）。不要開滿。
                        numThreads = 2,
                    ),
                    // 讓模型自己判斷句子結束，使用者不必按第二次。
                    enableEndpoint = true,
                    endpointConfig = EndpointConfig(),
                ),
            )
        }.onFailure { error ->
            Log.e(TAG, "ASR 模型載入失敗", error)
            modelFailed = true
        }.getOrNull()

        if (engine != null) {
            Log.i(TAG, "ASR 就緒，耗時 ${System.currentTimeMillis() - started}ms")
        }
        recognizer = engine
        return engine
    }

    /**
     * 建立錄音。
     *
     * 用 `VOICE_RECOGNITION` 音訊來源：它會套用系統的降噪與 AGC，
     * 而眼鏡是四麥克風陣列，戴在頭上、街上使用，這些處理很有價值。
     */
    @SuppressLint("MissingPermission") // 呼叫端已經檢查過 hasMicPermission()
    private fun createAudioRecord(): AudioRecord? {
        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBytes <= 0) {
            Log.e(TAG, "這台裝置不支援 16kHz float 錄音")
            return null
        }

        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                maxOf(minBytes * BUFFER_MULTIPLIER, CHUNK_SAMPLES * BYTES_PER_FLOAT),
            )
        }.getOrNull()

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 初始化失敗")
            runCatching { record?.release() }
            return null
        }
        return record
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "OfflineAsr"
        const val ASSET_DIR = "zh"

        /** 模型是用 16kHz 訓練的，不能改。 */
        const val SAMPLE_RATE = 16_000
        const val FEATURE_DIM = 80

        /** 每次讀 0.1 秒。太小會讓 JNI 呼叫過於頻繁，太大則部分結果會頓。 */
        const val CHUNK_SAMPLES = SAMPLE_RATE / 10
        const val BYTES_PER_FLOAT = 4
        const val BUFFER_MULTIPLIER = 2

        /** 一直沒聽到人聲就放棄，避免麥克風一直開著耗電。 */
        const val SILENCE_TIMEOUT_MILLIS = 8_000L

        /** 已經開始講話後的上限，防止環境噪音讓 endpoint 永遠不觸發。 */
        const val MAX_UTTERANCE_MILLIS = 20_000L

        const val ERROR_MODEL = "asr_model_unavailable"
        const val ERROR_MIC = "asr_microphone_unavailable"
        const val ERROR_NO_SPEECH = "asr_no_speech"
    }
}
