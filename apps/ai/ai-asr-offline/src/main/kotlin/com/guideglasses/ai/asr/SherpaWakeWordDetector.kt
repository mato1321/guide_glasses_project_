package com.guideglasses.ai.asr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import com.guideglasses.core.domain.speech.WakeWordDetector
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 用 sherpa-onnx 的關鍵詞偵測模型監聽喚醒詞。
 *
 * ### 為什麼換掉「一般辨識 + 字串比對」
 *
 * 那個做法實測命中率只有一半：它要求模型先把整句正確轉成文字，
 * 而喚醒詞是罕見詞，四個音節全部被聽壞是常態
 * （呼→胡/哭/不、叫→照/啸、盲→忙/芒/猛、狗→够）。
 * 我追著誤判放寬了兩版比對規則，命中率仍然上不去 —— 那是追不完的。
 *
 * 這個模型**不轉文字**，直接判斷聲音裡有沒有出現指定的詞。
 * 而且只有 4.6MB（一般辨識模型是 26MB），可以一直開著而不跟語音合成搶 CPU。
 *
 * ### 喚醒詞怎麼加
 *
 * 改 `assets/kws/keywords.txt`，一行一個：
 *
 * ```
 * h ū j iào m áng g ǒu @呼叫盲狗
 * ```
 *
 * 左邊是拼音（聲母 ＋ 帶聲調的韻母），右邊是顯示用的漢字。
 * 所有拼音單元都必須出現在 `tokens.txt` 裡，否則模型認不得。
 * **四音節的詞比三音節穩** —— 音節越多越不容易被環境音誤觸。
 */
class SherpaWakeWordDetector(
    context: Context,
    /**
     * 麥克風持續被佔用（重建錄音後仍被系統靜音，`true`）或恢復（`false`）時呼叫，
     * 只在狀態改變時呼叫一次，見 [listenLoop]。在主執行緒呼叫。
     */
    private val onMicrophoneSilenced: (Boolean) -> Unit = {},
) : WakeWordDetector {

    private val appContext = context.applicationContext

    @Volatile
    private var spotter: KeywordSpotter? = null

    @Volatile
    private var modelFailed = false

    /** 已經通知上層「麥克風被佔用」了，恢復時要再通知一次。 */
    private val reportedSilenced = AtomicBoolean(false)

    override val isAvailable: Boolean
        get() = !modelFailed && hasMicPermission()

    /**
     * 所有收集者共用同一條錄音。
     *
     * 實機上看到過這個症狀：Activity 反覆被重建，每次都開一條新的監聽，
     * log 裡「喚醒詞監聽中」每隔幾秒出現一次、執行緒編號一直換 ——
     * **多個 AudioRecord 在搶同一支麥克風**，結果每一條都收不好。
     *
     * 改成單一常駐迴圈之後，重複啟動只是多一個收集者，錄音永遠只有一份。
     */
    private val events = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var loopJob: Job? = null

    override fun detections(): Flow<String> = events.onSubscription { ensureLoopRunning() }

    @Synchronized
    private fun ensureLoopRunning() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { listenLoop() }
    }

    /**
     * 常駐監聽。錄音收到的全是 0（被系統靜音）時重建 [AudioRecord]，見 [recordOnce]。
     *
     * ### 為什麼看資料，不看 `AudioRecordingConfiguration.isClientSilenced()`
     *
     * 麥克風被別的 App 佔走時，Android **不會讓 `read()` 失敗**，而是把資料填成 0 ——
     * 監聽照跑、永遠聽不到指令，log 也一片安靜。系統另外有 `isClientSilenced()`
     * 旗標可查，但 **Rokid 眼鏡上這個旗標不可信**（2026-09-29 實測）：
     * MicrophoneProbe 開的每一條錄音都被標成 `silenced:true`，讀到的卻是真實聲音
     * （MIC 峰值 0.0092、DEFAULT 0.0060，不是 0）。照旗標重建的話，每 1.5 秒就把
     * 一條正常的錄音拆掉，關鍵詞偵測永遠聽不完一句話。
     *
     * 真的麥克風就算在安靜的房間也有底噪（實測峰值 0.0017 ≈ 55 LSB），
     * 不會連續 1.5 秒每個樣本都剛好是 0 —— 所以看資料不會誤判。
     */
    private suspend fun listenLoop() {
        if (!hasMicPermission()) {
            Log.w(TAG, "沒有麥克風權限，語音指令監聽無法啟動")
            return
        }

        val engine = ensureSpotter() ?: return

        // 連續幾輪錄音從頭到尾都被靜音。拿到過乾淨的麥克風就重新算起。
        var silencedRounds = 0
        while (currentCoroutineContext().isActive) {
            val round = recordOnce(engine) ?: return
            silencedRounds = if (round.everClean) 1 else silencedRounds + 1

            // 重建過還是被靜音，才當成真的被佔用而說出來 —— 正常的版本交接時，
            // 對方約 160ms 就放掉麥克風，第一次重建通常就拿得到，不必驚動使用者。
            if (silencedRounds >= 2) reportSilenced(true)

            val wait = SILENCED_RETRY_MILLIS[minOf(silencedRounds, SILENCED_RETRY_MILLIS.size) - 1]
            Log.w(TAG, "錄音被靜音，${wait}ms 後重建（連續第 $silencedRounds 輪）")
            delay(wait)
        }
    }

    /**
     * 開一條錄音聽指令，直到被取消、失敗，或連續 [SILENCED_RESTART_MILLIS] 收到的全是 0。
     *
     * @return 因為全是 0 而結束時回傳這一輪的狀況，讓 [listenLoop] 重建；
     *   被取消、失敗或開不了錄音時回傳 null，不再重試。
     */
    private suspend fun recordOnce(engine: KeywordSpotter): SilencedRound? {
        val record = createAudioRecord() ?: return null
        val stream = engine.createStream("")
        val buffer = ShortArray(CHUNK_SAMPLES)
        var lastSoundMillis = SystemClock.elapsedRealtime()
        var everHeard = false

        try {
            record.startRecording()
            Log.i(TAG, "語音指令監聽中")

            while (currentCoroutineContext().isActive) {
                val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue

                val now = SystemClock.elapsedRealtime()
                if (hasSound(buffer, read)) {
                    lastSoundMillis = now
                    if (!everHeard) {
                        everHeard = true
                        Log.i(TAG, "錄音有收到聲音（非 0 樣本）")
                        reportSilenced(false)
                    }
                } else if (now - lastSoundMillis >= SILENCED_RESTART_MILLIS) {
                    Log.w(TAG, "錄音連續 ${SILENCED_RESTART_MILLIS}ms 全是 0 —— 被系統靜音")
                    return SilencedRound(everClean = everHeard)
                }

                stream.acceptWaveform(
                    FloatArray(read) { buffer[it] / SHORT_FULL_SCALE },
                    SAMPLE_RATE,
                )
                while (engine.isReady(stream)) engine.decode(stream)

                val keyword = engine.getResult(stream).keyword
                if (keyword.isNotEmpty()) {
                    Log.i(TAG, "偵測到語音指令：「$keyword」")
                    // 不重置的話同一次發話會連續觸發好幾遍。
                    engine.reset(stream)
                    events.emit(keyword)
                }
            }
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "語音指令監聽失敗", e)
            return null
        } finally {
            runCatching {
                record.stop()
                record.release()
            }
            runCatching { stream.release() }
        }
    }

    /** 這段資料裡有沒有任何一個非 0 的樣本。被系統靜音的錄音每個樣本都是 0。 */
    private fun hasSound(buffer: ShortArray, length: Int): Boolean {
        for (i in 0 until length) {
            if (buffer[i].toInt() != 0) return true
        }
        return false
    }

    /**
     * 通知上層「麥克風被佔用／恢復」。只在狀態改變時通知 ——
     * 重建迴圈每幾秒就重開一次錄音，不擋的話會一直重複播報。
     */
    private fun reportSilenced(silenced: Boolean) {
        if (reportedSilenced.getAndSet(silenced) == silenced) return
        if (silenced) {
            Log.w(
                TAG,
                "🔴 麥克風持續收到全 0，重建錄音也沒用 —— 聽不到語音指令。" +
                    "原因通常是其他 App 佔用，或背景限制讓前景服務被降級、失去麥克風能力" +
                    "（adb shell cmd appops get <套件名> RUN_ANY_IN_BACKGROUND）",
            )
        } else {
            Log.i(TAG, "麥克風有收到聲音")
        }
        ContextCompat.getMainExecutor(appContext).execute { onMicrophoneSilenced(silenced) }
    }

    /** 一輪因為被靜音而結束的錄音。 */
    private class SilencedRound(val everClean: Boolean)

    override fun shutdown() {
        loopJob?.cancel()
        loopJob = null
        runCatching { spotter?.release() }
        spotter = null
    }

    @Synchronized
    private fun ensureSpotter(): KeywordSpotter? {
        spotter?.let { return it }
        if (modelFailed) return null

        val started = System.currentTimeMillis()
        val engine = runCatching {
            KeywordSpotter(
                assetManager = appContext.assets,
                config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = "$ASSET_DIR/encoder.int8.onnx",
                            decoder = "$ASSET_DIR/decoder.onnx",
                            joiner = "$ASSET_DIR/joiner.int8.onnx",
                        ),
                        tokens = "$ASSET_DIR/tokens.txt",
                        // 這個模型只有 3.3M 參數，而且要一直開著 ——
                        // 佔滿 CPU 會讓同時進行的語音合成更容易斷續。
                        numThreads = 1,
                    ),
                    keywordsFile = "$ASSET_DIR/keywords.txt",
                ),
            )
        }.onFailure { error ->
            Log.e(TAG, "語音指令模型載入失敗", error)
            modelFailed = true
        }.getOrNull()

        if (engine != null) {
            Log.i(TAG, "語音指令模型就緒，耗時 ${System.currentTimeMillis() - started}ms")
        }
        spotter = engine
        return engine
    }

    /**
     * 與 [SherpaSpeechRecognitionGateway] 用同一個音訊來源。
     *
     * ⚠️ 這台眼鏡上 `VOICE_RECOGNITION` 是啞的（回傳純靜音），
     * 只有 `MIC` 等其他來源收得到聲音。詳見那個類別的說明。
     */
    @SuppressLint("MissingPermission") // 呼叫端已確認權限
    private fun createAudioRecord(): AudioRecord? {
        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBytes <= 0) return null

        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBytes * BUFFER_MULTIPLIER,
            )
        }.getOrNull()

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "喚醒詞監聽的 AudioRecord 初始化失敗")
            runCatching { record?.release() }
            return null
        }
        return record
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "WakeWord"
        const val ASSET_DIR = "kws"
        const val SAMPLE_RATE = 16_000
        const val FEATURE_DIM = 80
        const val CHUNK_SAMPLES = SAMPLE_RATE / 10
        const val BUFFER_MULTIPLIER = 2
        const val SHORT_FULL_SCALE = 32768f

        /**
         * 被靜音多久就放掉重開。版本交接時對方約 160ms 就釋放麥克風（眼鏡實測），
         * 1.5 秒足以避開交接當下的短暫靜音，又不會讓使用者等太久。
         */
        const val SILENCED_RESTART_MILLIS = 1_500L

        /** 連續被靜音時，第 1、2、3、4 輪以後重建前各等多久。 */
        val SILENCED_RETRY_MILLIS = longArrayOf(500L, 2_000L, 5_000L, 10_000L)
    }
}
