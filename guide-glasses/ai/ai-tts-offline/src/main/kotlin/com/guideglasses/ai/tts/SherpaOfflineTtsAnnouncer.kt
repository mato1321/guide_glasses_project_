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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
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
 * | sideload 另一顆 TTS 引擎 APK | 🔴 **確定沒用**：`service list` 全部 204 個系統服務裡，
 *   `tts` / `speech` / `voice` 一個都沒有 —— 框架端的 TTS system service
 *   根本不存在，任何引擎 APK 都得透過它才能被綁定 |
 *
 * 所以這裡改成**把合成引擎當函式庫**：VITS 模型 → ONNX 推論 → PCM →
 * [AudioTrack]。整條路徑不碰 `TextToSpeech`，也就繞開了壞掉的那一層。
 * 眼鏡的音訊輸出本身是好的 —— 組員的 App 用 `MediaPlayer` 播 mp3 會出聲，
 * 壞的只有框架。
 *
 * ### 延遲（2026-08-08 眼鏡實測）
 *
 * 用 `generateWithCallback` **邊合成邊播**，而不是等整句合成完才出聲。
 *
 * | 指標 | 實測 |
 * |---|---|
 * | 模型載入 | 4.7 秒（期間 [isAvailable] 為 false，聽不到任何東西） |
 * | 起播延遲 | 0.4–1.0 秒 |
 * | RTF | 1.05 左右 |
 * | 快取命中 | 額外開銷約 60–130ms |
 *
 * **RTF 約等於 1 代表合成速度剛好追上播放**，長句偶爾仍會有輕微斷續。
 * 障礙物播報的 300ms 預算只有走快取才達得到 —— 這是這顆 CPU 的硬限制，
 * 不是實作問題。曾試過 piper 的 22050Hz 模型，RTF 2.2、起播 2.3 秒，明顯更差。
 *
 * ### 已知限制
 *
 * - **只有中文。** 模型是 zh_CN 單語系。帶著其他 [Announcement.languageTag]
 *   的內容（例如翻譯結果）會丟例外，交由
 *   [com.guideglasses.core.domain.announce.FallbackAnnouncer] 往下一個候選找。
 *   要讓翻譯結果有聲音，得再加一個對應語言的模型。
 * - **首次載入慢**：要讀進 30MB 的 ONNX，實測 4.7 秒。因此在背景執行緒載入，
 *   載入完成前 [isAvailable] 是 false —— 開機後那幾秒是啞的。
 * - **8000Hz 電話音質**：模型自己宣告的取樣率就是 8000。清晰度換到了延遲。
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

    /**
     * 合成結果的磁碟快取。
     *
     * 實測（Rokid Glasses，4 核 2.0GHz）現場合成的 RTF 約 1.05，
     * 起播 0.4–1.0 秒 —— 仍然遠超障礙物播報的 300ms 預算。
     * 但導盲的播報重複性極高（「前面沒有偵測到障礙物」每次都是同一串字），
     * 快取命中時直接播 PCM，實測額外開銷只有約 60–130ms。
     *
     * 目錄名帶著模型名：換模型時舊音訊必須失效，否則會用舊聲音播新內容
     * ——而且取樣率不同的話會變成怪腔怪調。
     */
    private val cacheDir: File by lazy {
        File(appContext.cacheDir, "tts-cache/${ASSET_DIR.substringAfterLast('/')}")
            .apply { mkdirs() }
    }

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
        val ruleFsts = listOf("date.fst", "number.fst", "phone.fst", "new_heteronym.fst")
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
                    /*
                     * 眼鏡是 4 核 2.0GHz。實測 2 → 4 執行緒**沒有差別**
                     * （RTF 2.27 → 2.24），合成期間 CPU 佔用約 206%，
                     * 也就是這個模型的推論本來就吃不滿四核。
                     * 留 4 是因為不會更差，但別指望調它能救延遲。
                     */
                    numThreads = 4,
                ),
                ruleFsts = ruleFsts,
            ),
        )
    }.onFailure { error ->
        Log.e(TAG, "離線 TTS 模型載入失敗（ruleFsts=${ruleFsts.ifEmpty { "無" }}）", error)
    }.getOrNull()

    /**
     * 直接播放已經快取好的 PCM。
     *
     * 這條路徑不做任何推論，延遲只有讀檔加上 AudioTrack 起播 ——
     * 這是唯一能讓危險警示接近即時的方式（實測合成本身 RTF 約 2.2，
     * 光是「前方有車」就要兩秒才出得了聲）。
     *
     * @return 是否命中快取。false 代表要走合成。
     */
    private fun playFromCache(text: String, sampleRate: Int): Boolean {
        val file = cacheFileFor(text)
        if (!file.isFile || file.length() < BYTES_PER_SAMPLE) return false

        val startedAt = System.currentTimeMillis()
        var track: AudioTrack? = null
        try {
            val samples = FloatArray((file.length() / BYTES_PER_SAMPLE).toInt())
            DataInputStream(file.inputStream().buffered()).use { input ->
                for (i in samples.indices) samples[i] = input.readFloat()
            }

            track = createTrack(sampleRate)
            currentTrack = track
            track.play()

            var offset = 0
            while (offset < samples.size && !cancelled.get()) {
                val count = minOf(PLAYBACK_CHUNK_SAMPLES, samples.size - offset)
                track.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
                offset += count
            }
            drain(track, offset.toLong())

            Log.i(
                TAG,
                "快取命中「$text」｜音長 ${samples.size * 1000L / sampleRate}ms｜" +
                    "總耗時 ${System.currentTimeMillis() - startedAt}ms",
            )
            return true
        } catch (e: Exception) {
            // 快取壞掉不該讓使用者聽不到話 —— 刪掉重新合成就好。
            Log.w(TAG, "快取讀取失敗，改走合成：$text", e)
            runCatching { file.delete() }
            return false
        } finally {
            currentTrack = null
            track?.runCatching {
                stop()
                release()
            }
        }
    }

    /**
     * 把合成好的音訊寫進快取。
     *
     * 先寫暫存檔再 rename —— 中途被砍（眼鏡的省電機制很積極）留下的
     * 半截檔案，下次會被當成正常快取播出去，變成只唸一半的警示。
     */
    private fun writeCache(text: String, samples: FloatArray) {
        val target = cacheFileFor(text)
        val temp = File(target.parentFile, "${target.name}.tmp")
        try {
            DataOutputStream(temp.outputStream().buffered()).use { output ->
                samples.forEach(output::writeFloat)
            }
            if (!temp.renameTo(target)) temp.delete()
            trimCache()
        } catch (e: Exception) {
            Log.w(TAG, "快取寫入失敗（不影響播報）", e)
            runCatching { temp.delete() }
        }
    }

    /** 快取目錄帶著模型名 —— 換模型時舊的音訊必須失效，否則會用舊聲音播新內容。 */
    private fun cacheFileFor(text: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
        return File(cacheDir, "$digest.pcm")
    }

    /** 超過上限就從最舊的開始刪。導盲用語重複性高，命中率不太受影響。 */
    private fun trimCache() {
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= MAX_CACHE_BYTES) return
            total -= file.length()
            file.delete()
        }
    }

    /**
     * 整個播報的單一出口 —— 確保無論走快取還是走合成，
     * `speaking` 與 `onDone` 都只被收拾一次。
     */
    private fun synthesizeAndPlay(engine: OfflineTts, text: String, onDone: () -> Unit) {
        try {
            val sampleRate = engine.sampleRate()
            if (!playFromCache(text, sampleRate)) synthesizeFresh(engine, text, sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "播報失敗：$text", e)
        } finally {
            speaking.set(false)
            // 契約：無論成功與否都要恰好回報一次，否則佇列從此不再前進。
            onDone()
        }
    }

    private fun synthesizeFresh(engine: OfflineTts, text: String, sampleRate: Int) {
        var track: AudioTrack? = null
        var samplesWritten = 0L

        // 合成完要寫進快取，所以邊播邊留一份。導盲用語重複性高，
        // 第二次講同一句就不必再付兩秒的合成延遲。
        val collected = ArrayList<FloatArray>()

        // 這些數字是判斷「能不能拿來當導盲裝置」的依據，不是除錯殘留：
        // 障礙物播報的安全預算是 300ms，而 firstChunkAt 就是實際的起播延遲。
        val startedAt = System.currentTimeMillis()
        var firstChunkAt = 0L

        try {
            track = createTrack(sampleRate)
            currentTrack = track
            track.play()

            /*
             * ⚠️ 這裡**不能寫成 lambda**。
             *
             * sherpa 的 JNI 是用特化簽章 `invoke([F)Ljava/lang/Integer;`
             * 去找這個回呼。Kotlin 2.x 預設把 lambda 編成 invokedynamic，
             * D8 生成的合成類別只有泛型橋接 `invoke(Object)Object`，
             * 找不到特化方法 —— 而且失敗方式是 **JNI DETECTED ERROR 直接 abort**，
             * 不是丟例外，FallbackAnnouncer 攔不住，整個 App 會當場掛掉。
             *
             * 寫成具名的 Function1 物件，Kotlin 才會產生 JNI 要的那個方法。
             */
            val onSamples = object : Function1<FloatArray, Int> {
                override fun invoke(samples: FloatArray): Int {
                    if (cancelled.get()) return STOP_GENERATING
                    if (firstChunkAt == 0L) firstChunkAt = System.currentTimeMillis()
                    collected += samples.copyOf()
                    track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                    samplesWritten += samples.size
                    return KEEP_GENERATING
                }
            }
            engine.generateWithCallback(text, callback = onSamples)

            val synthesisMs = System.currentTimeMillis() - startedAt
            val audioMs = samplesWritten * 1000 / sampleRate
            Log.i(
                TAG,
                "合成完畢「$text」｜起播 ${firstChunkAt - startedAt}ms｜" +
                    "合成 ${synthesisMs}ms｜音長 ${audioMs}ms｜" +
                    // RTF < 1 代表合成比播放快，也就是串流播放不會斷。
                    "RTF ${"%.2f".format(if (audioMs > 0) synthesisMs.toDouble() / audioMs else 0.0)}",
            )

            drain(track, samplesWritten)

            // 被使用者打斷的內容只有半截，存進去下次會播出殘缺的句子。
            if (!cancelled.get() && samplesWritten > 0) {
                writeCache(text, collected.flatten())
            }
        } finally {
            currentTrack = null
            track?.runCatching {
                stop()
                release()
            }
        }
    }

    /** 把逐塊收到的樣本併成一段，寫檔用。 */
    private fun List<FloatArray>.flatten(): FloatArray {
        val out = FloatArray(sumOf { it.size })
        var offset = 0
        forEach { chunk ->
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
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
        const val ASSET_DIR = "tts/zh-aishell3"
        const val MODEL_FILE = "model.onnx"

        /** `generateWithCallback` 的回傳值：1 繼續合成，0 中止。 */
        const val KEEP_GENERATING = 1
        const val STOP_GENERATING = 0

        const val BUFFER_MULTIPLIER = 4
        const val MIN_BUFFER_BYTES = 16 * 1024
        const val DRAIN_POLL_MILLIS = 20L

        /** 快取是裸的 float32 PCM，沒有檔頭 —— 取樣率與聲道數由模型決定，不會變。 */
        const val BYTES_PER_SAMPLE = 4L

        /** 播快取時一次寫多少 sample。太大起播會慢，太小 write 呼叫太頻繁。 */
        const val PLAYBACK_CHUNK_SAMPLES = 4096

        /** 22050Hz 單聲道 float32 約 88KB/秒，32MB 大約放得下六分鐘的語音。 */
        const val MAX_CACHE_BYTES = 32L * 1024 * 1024
    }
}
