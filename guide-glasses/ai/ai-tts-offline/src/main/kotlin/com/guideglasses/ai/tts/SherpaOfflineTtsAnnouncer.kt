package com.guideglasses.ai.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.guideglasses.core.domain.announce.Announcement
import com.guideglasses.core.domain.announce.Announcer
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * APK 內建的離線語音合成，完全不經過 Android 的 `TextToSpeech` 框架。
 *
 * ### 為什麼需要這個
 *
 * Rokid Glasses 上 `TextToSpeech` 綁定失敗（`System service is not available!`），
 * 而系統上唯一的引擎是一個沒有任何語音來源的第三方 App。已實測排除的路：
 *
 * | 走法 | 結果 |
 * |---|---|
 * | 設定既有的 `tts_server_android` | 🔴 三處設定頁全空，無語音來源可選 |
 * | Rokid Glass3 企業版 SDK | 🔴 缺 `com.rokid.security.system.server`，`isReady()` 永遠 false |
 * | sideload 另一顆 TTS 引擎 APK | 🟡 未試，但錯誤訊息指向框架本身壞掉，任何引擎都要透過它 |
 *
 * 所以這裡改成**把合成引擎當函式庫**：VITS 模型 → ONNX 推論 → PCM →
 * [AudioTrack]。整條路徑不碰 `TextToSpeech`，也就繞開了壞掉的那一層。
 * 眼鏡的音訊輸出本身是好的 —— 組員的 App 用 `MediaPlayer` 播 mp3 會出聲，
 * 壞的只有框架。
 *
 * ### 延遲
 *
 * 用 `generateWithCallback` **邊合成邊播**，而不是等整句合成完才出聲。
 * 對「前方有車」這種安全播報，先聽到前兩個字就已經達到目的了。
 *
 * ### 已知限制
 *
 * - **只有中文。** 模型是 zh_CN 單語系。帶著其他 [Announcement.languageTag]
 *   的內容（例如翻譯結果）會丟例外，交由
 *   [com.guideglasses.core.domain.announce.FallbackAnnouncer] 往下一個候選找。
 *   要讓翻譯結果有聲音，得再加一個對應語言的模型。
 * - **首次載入慢**：要讀進約 19MB 的 ONNX。因此在背景執行緒載入，
 *   載入完成前 [isAvailable] 是 false。
 *
 * @param onReady 模型載入結束時回報成功與否，方便開機時就知道有沒有聲音。
 */
class SherpaOfflineTtsAnnouncer(
    context: Context,
    private val onReady: (Boolean) -> Unit = {},
) : Announcer {

    private val appContext = context.applicationContext

    /**
     * 單一執行緒：模型載入與所有合成都排在這裡。
     *
     * 用單執行緒而不是執行緒池是刻意的 —— 兩則播報同時合成會搶 CPU，
     * 在只有 2GB RAM 的眼鏡上還可能同時開兩份推論記憶體。
     * 反正 `AnnouncementManager` 一次也只會讓一則播報進來。
     */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "offline-tts").apply { isDaemon = true }
    }

    private val ready = AtomicBoolean(false)
    private val speaking = AtomicBoolean(false)

    /** 使用者說了「停」。合成回呼看到它就中止，不必等整句合成完。 */
    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var tts: OfflineTts? = null

    @Volatile
    private var currentTrack: AudioTrack? = null

    init {
        worker.execute { load() }
    }

    override val isAvailable: Boolean
        get() = ready.get()

    override val isSpeaking: Boolean
        get() = speaking.get()

    override fun speak(announcement: Announcement, onDone: () -> Unit) {
        val engine = tts
        if (engine == null || !ready.get()) {
            // 呼叫端沒先看 isAvailable。仍要回報完成，否則佇列會卡死。
            Log.w(TAG, "離線 TTS 尚未就緒，略過：${announcement.text}")
            onDone()
            return
        }

        val tag = announcement.languageTag
        if (tag != null && !isChinese(tag)) {
            // 這裡刻意用例外而不是靜默略過：FallbackAnnouncer 會接住它並
            // 換下一個候選，至少讓這句話進得了 log。用中文模型硬唸英文
            // 只會產生一串聽不懂的音節，比沒有聲音更糟。
            throw UnsupportedOperationException("離線 TTS 只支援中文，不支援 $tag")
        }

        cancelled.set(false)
        speaking.set(true)
        worker.execute { synthesizeAndPlay(engine, announcement.text, onDone) }
    }

    override fun stop() {
        cancelled.set(true)
        // 立刻丟掉已經排進去的音訊，否則使用者說「停」之後還會聽到
        // 緩衝區裡剩下的一兩秒。
        currentTrack?.runCatching {
            pause()
            flush()
        }
        speaking.set(false)
    }

    override fun shutdown() {
        stop()
        worker.execute {
            runCatching { tts?.release() }
            tts = null
            ready.set(false)
        }
        worker.shutdown()
    }

    private fun load() {
        val started = System.currentTimeMillis()

        // rule FST 負責把「30」唸成「三十」、把日期與電話唸成人話。
        // 導盲播報滿是數字（「前方 3 公尺」「耗時 930 毫秒」），少了它們
        // 這些字可能整個被跳過。
        val ruleFsts = listOf("date.fst", "number.fst", "phone.fst")
            .joinToString(",") { "$ASSET_DIR/$it" }

        val engine = createEngine(ruleFsts)
            // rule FST 是否吃得到 assets 路徑沒辦法在這台機器上先驗證，
            // 所以失敗時退回「沒有正規化」再試一次 —— 數字唸得不漂亮，
            // 總比完全沒有聲音好。
            ?: createEngine(ruleFsts = "").also {
                if (it != null) Log.w(TAG, "rule FST 載入失敗，數字與日期不會被正規化")
            }

        tts = engine
        ready.set(engine != null)

        if (engine != null) {
            Log.i(TAG, "離線 TTS 就緒，取樣率 ${engine.sampleRate()}Hz，" +
                "耗時 ${System.currentTimeMillis() - started}ms")
        }
        onReady(engine != null)
    }

    private fun createEngine(ruleFsts: String): OfflineTts? = runCatching {
        OfflineTts(
            assetManager = appContext.assets,
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "$ASSET_DIR/$MODEL_FILE",
                        lexicon = "$ASSET_DIR/lexicon.txt",
                        tokens = "$ASSET_DIR/tokens.txt",
                    ),
                    // 眼鏡是 2GB RAM 的低階機。開太多執行緒會與 YOLO、
                    // 人臉推論互相搶 CPU，反而更慢。
                    numThreads = 2,
                ),
                ruleFsts = ruleFsts,
            ),
        )
    }.onFailure { error ->
        Log.e(TAG, "離線 TTS 模型載入失敗（ruleFsts=${ruleFsts.ifEmpty { "無" }}）", error)
    }.getOrNull()

    private fun synthesizeAndPlay(engine: OfflineTts, text: String, onDone: () -> Unit) {
        val sampleRate = engine.sampleRate()
        var track: AudioTrack? = null
        var samplesWritten = 0L

        try {
            track = createTrack(sampleRate)
            currentTrack = track
            track.play()

            engine.generateWithCallback(text) { samples ->
                if (cancelled.get()) {
                    STOP_GENERATING
                } else {
                    track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                    samplesWritten += samples.size
                    KEEP_GENERATING
                }
            }

            drain(track, samplesWritten)
        } catch (e: Exception) {
            Log.e(TAG, "離線合成失敗：$text", e)
        } finally {
            currentTrack = null
            track?.runCatching {
                stop()
                release()
            }
            speaking.set(false)
            // 契約：無論成功與否都要恰好回報一次，否則佇列從此不再前進。
            onDone()
        }
    }

    private fun createTrack(sampleRate: Int): AudioTrack {
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        // getMinBufferSize 給的是「不斷音的最小值」。合成速度在低階機上會抖，
        // 抓大一點換取穩定，代價只是多幾十毫秒的起播延遲。
        val bufferBytes = (minBytes * BUFFER_MULTIPLIER).coerceAtLeast(MIN_BUFFER_BYTES)

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // 與 AndroidTtsAnnouncer 一致：走無障礙通道，
                    // 使用者把媒體音量調低時導盲提示仍然聽得見。
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    /**
     * 等緩衝區裡的音訊真的播完。
     *
     * `generateWithCallback` 返回只代表**寫完**，不代表**播完** ——
     * 少了這一步，`onDone` 會提早觸發，下一則播報就會蓋掉還在播的這一則。
     */
    private fun drain(track: AudioTrack, samplesWritten: Long) {
        while (!cancelled.get() && track.playbackHeadPosition < samplesWritten) {
            Thread.sleep(DRAIN_POLL_MILLIS)
        }
    }

    /** 目前的模型只有中文。`zh`、`zh-TW`、`zh-CN` 都算。 */
    private fun isChinese(languageTag: String): Boolean =
        languageTag.substringBefore('-').equals("zh", ignoreCase = true)

    private companion object {
        const val TAG = "OfflineTts"

        /** 相對於 assets 根目錄。 */
        const val ASSET_DIR = "tts/zh"
        const val MODEL_FILE = "zh_CN-xiao_ya-medium.onnx"

        /** `generateWithCallback` 的回傳值：1 繼續合成，0 中止。 */
        const val KEEP_GENERATING = 1
        const val STOP_GENERATING = 0

        const val BUFFER_MULTIPLIER = 4
        const val MIN_BUFFER_BYTES = 16 * 1024
        const val DRAIN_POLL_MILLIS = 20L
    }
}
