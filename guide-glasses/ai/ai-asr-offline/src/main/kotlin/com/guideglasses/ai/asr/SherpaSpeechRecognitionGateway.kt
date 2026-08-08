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
import com.guideglasses.core.domain.speech.SpeechCapability
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
import kotlin.math.abs
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
            emit(SpeechEvent.Failed(AppError.CapabilityUnavailable(SpeechCapability.RECOGNITION)))
            return@flow
        }

        val record = createAudioRecord()
        if (record == null) {
            emit(SpeechEvent.Failed(AppError.CapabilityUnavailable(SpeechCapability.MICROPHONE)))
            return@flow
        }

        cancelled.set(false)
        val stream = engine.createStream("")
        // 讀 16-bit，自己轉 float。理由見 createAudioRecord。
        val buffer = ShortArray(CHUNK_SAMPLES)
        var lastPartial = ""
        var heardSpeech = false
        val startedAt = System.currentTimeMillis()

        // 麥克風實際收到的音量。沒有這個數字，「沒聽到」會有兩種完全不同的
        // 原因無法區分：麥克風是啞的（收到一片 0），或收得到聲音但模型解不出來。
        var micPeak = 0f
        var loudChunks = 0

        try {
            record.startRecording()
            Log.i(TAG, "開始聆聽")
            emit(SpeechEvent.ReadyForSpeech)

            while (currentCoroutineContext().isActive && !cancelled.get()) {
                val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue

                val chunk = buffer.toFloatSamples(read)
                val chunkPeak = chunk.maxOf { abs(it) }
                micPeak = maxOf(micPeak, chunkPeak)
                if (chunkPeak > SPEECH_PEAK_THRESHOLD) loudChunks++

                stream.acceptWaveform(chunk, SAMPLE_RATE)
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
                        Log.w(
                            TAG,
                            "逾時，沒有辨識結果｜麥克風峰值 ${"%.4f".format(micPeak)}｜" +
                                "夠大聲的片段 $loudChunks 個｜" +
                                when {
                                    micPeak < SILENCE_PEAK_THRESHOLD ->
                                        "🔴 麥克風收到的幾乎是靜音 —— 問題在錄音，不是模型"
                                    loudChunks == 0 ->
                                        "🟡 收得到聲音但都很小 —— 講大聲一點或靠近一點"
                                    else ->
                                        "🟡 收得到人聲但模型解不出來 —— 問題在模型或設定"
                                },
                        )
                        emit(SpeechEvent.Failed(AppError.CapabilityUnavailable(SpeechCapability.NO_SPEECH)))
                    }
                    return@flow
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "辨識失敗", e)
            emit(SpeechEvent.Failed(AppError.CapabilityUnavailable(SpeechCapability.MICROPHONE)))
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

    /**
     * 16-bit PCM 轉成 sherpa 要的 [-1, 1] 浮點。
     *
     * `Short.MIN_VALUE` 是 -32768 而 `MAX_VALUE` 只有 32767，所以除以 32768
     * 才不會讓最負的那個樣本超出 -1.0。
     */
    private fun ShortArray.toFloatSamples(count: Int): FloatArray =
        FloatArray(count) { this[it] / SHORT_FULL_SCALE }

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
     * ### ⚠️ 為什麼不用 VOICE_RECOGNITION
     *
     * 那本來是最合適的來源（系統會套用降噪與 AGC，而眼鏡是四麥克風陣列、
     * 戴在頭上、在街上使用）。但 [MicrophoneProbe] 實測發現
     * **這台裝置上唯一收不到聲音的來源，剛好就是它**：
     *
     * ```
     * VOICE_RECOGNITION：0.0000  ← 靜音
     * MIC：              0.0387
     * DEFAULT：          0.0227
     * VOICE_COMMUNICATION：0.0287
     * CAMCORDER：        0.0439
     * UNPROCESSED：      0.0086
     * ```
     *
     * 廠商沒有實作那個來源，而且**不報錯、只是靜靜回傳靜音** ——
     * 又是這台裝置的招牌失敗方式。改用 `MIC`。
     *
     * 哪天換裝置或韌體更新後又聽不到，先跑：
     * `adb shell am broadcast -a com.guideglasses.DEBUG --es cmd MIC_TEST`
     *
     * ### ⚠️ 為什麼是 16-bit 而不是 float
     *
     * 一開始用 `ENCODING_PCM_FLOAT`（sherpa 直接吃 float，省一次轉換），
     * 結果眼鏡上**收到的全是 0** —— 實測麥克風峰值 0.0000，而權限、appop、
     * 麥克風佔用、全域靜音全都正常。線索在這行 error：
     *
     * ```
     * E AudioRecord: set(): sai Format 0x5 is linear
     * ```
     *
     * `0x5` 就是 `ENCODING_PCM_FLOAT`，而且是廠商改過的 AudioRecord 印的。
     * 這台的 HAL 對 float 擷取沒有正確實作，**靜靜回傳靜音而不是報錯** ——
     * 又是這台裝置的招牌失敗方式。
     *
     * 16-bit 是 Android 保證每台裝置都支援的格式，多一次除法換取確定性。
     */
    @SuppressLint("MissingPermission") // 呼叫端已經檢查過 hasMicPermission()
    private fun createAudioRecord(): AudioRecord? {
        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBytes <= 0) {
            Log.e(TAG, "這台裝置不支援 16kHz 單聲道錄音")
            return null
        }

        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBytes * BUFFER_MULTIPLIER, CHUNK_SAMPLES * BYTES_PER_SHORT),
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
        const val BYTES_PER_SHORT = 2

        /** 除以 32768（不是 32767）才能讓 Short.MIN_VALUE 落在 -1.0。 */
        const val SHORT_FULL_SCALE = 32768f
        const val BUFFER_MULTIPLIER = 2

        /**
         * 判定「這一小段有人在講話」的峰值門檻。
         *
         * 只用來診斷（分辨麥克風啞掉與模型解不出來），不參與辨識決策 ——
         * 端點偵測是模型的工作，不該用土砲的音量門檻取代。
         */
        const val SPEECH_PEAK_THRESHOLD = 0.02f

        /**
         * 低於這個值代表連環境底噪都沒有，也就是錄音本身壞掉。
         *
         * 眼鏡上實測：底噪約 0.005、正常說話約 0.039、
         * 音訊來源沒接時是**乾淨的 0.0000**。所以門檻放很低就夠了 ——
         * 目的是分辨「一片死寂」與「有聲音但太小」，不是判斷有沒有人講話。
         */
        const val SILENCE_PEAK_THRESHOLD = 0.002f

        /** 一直沒聽到人聲就放棄，避免麥克風一直開著耗電。 */
        const val SILENCE_TIMEOUT_MILLIS = 8_000L

        /** 已經開始講話後的上限，防止環境噪音讓 endpoint 永遠不觸發。 */
        const val MAX_UTTERANCE_MILLIS = 20_000L

    }
}
