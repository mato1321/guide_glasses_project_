package com.guideglasses.ai.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.guideglasses.core.domain.announce.Announcement
import com.guideglasses.core.domain.announce.Announcer
import com.k2fsa.sherpa.onnx.OfflineTts
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * APK 內建的離線語音合成，完全不經過 Android 的 `TextToSpeech` 框架。
 *
 * ### 為什麼需要這個
 *
 * Rokid Glasses 上 `TextToSpeech` 綁定失敗（`System service is not available!`）。
 * 實測 `service list` 的 204 個系統服務裡 `tts` / `speech` / `voice`
 * **一個都沒有** —— 這個 YodaOS 精簡版把整層 TTS 框架拿掉了，
 * 所以 sideload 任何 TTS 引擎 APK 也沒用，它們都得透過那層框架被綁定。
 *
 * 但**眼鏡的音訊輸出本身是好的**。所以改成把合成引擎當函式庫：
 * 文字 → VITS(ONNX) → PCM → [AudioTrack]，整條路徑不碰 `TextToSpeech`。
 *
 * ### 支援的語言
 *
 * 中文與英文各一顆模型（見 [OfflineVoice]）。中文開機就載入，
 * **英文延遲到第一次真的要唸英文才載入** —— 多數使用者不會用翻譯，
 * 沒必要讓每個人都付那 19MB 的記憶體與載入時間。
 *
 * ### 延遲（2026-08-08 眼鏡實測，中文）
 *
 * | 指標 | 實測 |
 * |---|---|
 * | 模型載入 | 4.7 秒（期間 [isAvailable] 為 false，聽不到任何東西） |
 * | 起播延遲 | 0.4–1.0 秒 |
 * | RTF | 約 1.0 |
 * | 快取命中 | 額外開銷約 60–150ms |
 *
 * **RTF 約等於 1 代表合成剛好追上播放**。障礙物播報的 300ms 預算
 * 只有走快取才達得到 —— 這是這顆 CPU 的硬限制，不是實作問題。
 *
 * @param onReady 中文模型載入結束時回報成功與否，方便開機時就知道有沒有聲音。
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
     * 在只有 1.8GB RAM 的眼鏡上還可能同時開兩份推論記憶體。
     * 反正 `AnnouncementManager` 一次也只會讓一則播報進來。
     */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "offline-tts").apply { isDaemon = true }
    }

    /** 已載入的引擎。英文是用到才載，所以這裡是動態成長的。 */
    private val engines = ConcurrentHashMap<OfflineVoice, OfflineTts>()

    /** 載入失敗過的語言。記著才不會每次播報都重試一次昂貴的載入。 */
    private val failedVoices = ConcurrentHashMap.newKeySet<OfflineVoice>()

    private val speaking = AtomicBoolean(false)

    /** 使用者說了「停」。合成回呼看到它就中止，不必等整句合成完。 */
    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var currentTrack: AudioTrack? = null

    init {
        worker.execute {
            val engine = loadVoice(OfflineVoice.CHINESE)
            onReady(engine != null)
        }
    }

    /** 中文可用就算可用 —— 那是絕大多數播報。 */
    override val isAvailable: Boolean
        get() = engines.containsKey(OfflineVoice.CHINESE)

    override val isSpeaking: Boolean
        get() = speaking.get()

    override fun speak(announcement: Announcement, onDone: () -> Unit) {
        val voice = OfflineVoice.forLanguageTag(announcement.languageTag)
        if (voice == null || voice in failedVoices) {
            // 這裡刻意用例外而不是靜默略過：FallbackAnnouncer 會接住它並
            // 換下一個候選，至少讓這句話進得了 log。用中文模型硬唸日文
            // 只會產生一串聽不懂的音節，比沒有聲音更糟。
            throw UnsupportedOperationException(
                "離線 TTS 不支援 ${announcement.languageTag}（目前只有中文與英文）",
            )
        }

        cancelled.set(false)
        speaking.set(true)
        worker.execute { synthesizeAndPlay(voice, announcement.text, onDone) }
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
            engines.values.forEach { engine -> runCatching { engine.release() } }
            engines.clear()
        }
        worker.shutdown()
    }

    /**
     * 取得某個語言的引擎，需要的話當場載入。
     *
     * 只在 [worker] 上呼叫，所以不需要額外同步。
     */
    private fun loadVoice(voice: OfflineVoice): OfflineTts? {
        engines[voice]?.let { return it }
        if (voice in failedVoices) return null

        val started = System.currentTimeMillis()
        val engine = voice.createEngine(appContext)

        if (engine == null) {
            // 記下來，否則每一則播報都會重試一次載入，反而更慢。
            failedVoices += voice
            Log.e(TAG, "${voice.name} 語音不可用")
            return null
        }

        engines[voice] = engine
        Log.i(
            TAG,
            "${voice.name} 語音就緒，取樣率 ${engine.sampleRate()}Hz，" +
                "耗時 ${System.currentTimeMillis() - started}ms",
        )
        return engine
    }

    /**
     * 整個播報的單一出口 —— 確保無論走快取、走合成還是失敗，
     * `speaking` 與 `onDone` 都只被收拾一次。
     */
    private fun synthesizeAndPlay(voice: OfflineVoice, text: String, onDone: () -> Unit) {
        try {
            val engine = loadVoice(voice)
            if (engine == null) {
                // 已經在 worker 上了，不能再丟例外給 FallbackAnnouncer 接。
                // 至少讓這句話留在 log 裡 —— 眼鏡上這是唯一的觀察手段。
                Log.w(TAG, "${voice.name} 語音載入失敗，本來要唸：$text")
                return
            }

            val sampleRate = engine.sampleRate()
            if (!playFromCache(voice, text, sampleRate)) {
                synthesizeFresh(voice, engine, text, sampleRate)
            }
        } catch (e: Exception) {
            Log.e(TAG, "播報失敗：$text", e)
        } finally {
            speaking.set(false)
            // 契約：無論成功與否都要恰好回報一次，否則佇列從此不再前進。
            onDone()
        }
    }

    private fun synthesizeFresh(
        voice: OfflineVoice,
        engine: OfflineTts,
        text: String,
        sampleRate: Int,
    ) {
        var track: AudioTrack? = null
        var samplesWritten = 0L

        // 合成完要寫進快取，所以邊播邊留一份。導盲用語重複性高，
        // 第二次講同一句就不必再付合成延遲。
        val collected = ArrayList<FloatArray>()

        // 這些數字是判斷「能不能拿來當導盲裝置」的依據，不是除錯殘留：
        // 障礙物播報的安全預算是 300ms，而 firstChunkAt 就是實際的起播延遲。
        val startedAt = System.currentTimeMillis()
        var firstChunkAt = 0L

        // 增益前的峰值。留著它才知道 GAIN 該調多少。
        var rawPeak = 0f

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
             * 改動後可以用 javap 確認位元組碼有
             * `public java.lang.Integer invoke(float[])`。
             */
            val onSamples = object : Function1<FloatArray, Int> {
                override fun invoke(samples: FloatArray): Int {
                    if (cancelled.get()) return STOP_GENERATING
                    if (firstChunkAt == 0L) firstChunkAt = System.currentTimeMillis()

                    // 快取存原始樣本，播放才套增益 —— 兩條路徑因此永遠一樣大聲。
                    collected += samples.copyOf()
                    rawPeak = maxOf(rawPeak, samples.maxOf { abs(it) })

                    val amplified = amplify(samples, voice.gain)
                    track.write(amplified, 0, amplified.size, AudioTrack.WRITE_BLOCKING)
                    samplesWritten += amplified.size
                    return KEEP_GENERATING
                }
            }
            engine.generateWithCallback(text, callback = onSamples)

            val synthesisMs = System.currentTimeMillis() - startedAt
            val audioMs = samplesWritten * 1000 / sampleRate
            Log.i(
                TAG,
                "${voice.name} 合成完畢「$text」｜起播 ${firstChunkAt - startedAt}ms｜" +
                    "合成 ${synthesisMs}ms｜音長 ${audioMs}ms｜" +
                    "原始峰值 ${"%.3f".format(rawPeak)}（增益後 ${
                        "%.3f".format((rawPeak * voice.gain).coerceAtMost(1f))
                    }）｜" +
                    // RTF < 1 代表合成比播放快，也就是串流播放不會斷。
                    "RTF ${
                        "%.2f".format(if (audioMs > 0) synthesisMs.toDouble() / audioMs else 0.0)
                    }",
            )

            drain(track, samplesWritten)

            // 被使用者打斷的內容只有半截，存進去下次會播出殘缺的句子。
            if (!cancelled.get() && samplesWritten > 0) {
                writeCache(voice, text, collected.flatten())
            }
        } finally {
            currentTrack = null
            track?.runCatching {
                stop()
                release()
            }
        }
    }

    /**
     * 直接播放已經快取好的 PCM。
     *
     * 這條路徑不做任何推論，延遲只有讀檔加上 AudioTrack 起播 ——
     * 這是唯一能讓危險警示接近即時的方式（現場合成起播要 0.5 秒以上）。
     *
     * @return 是否命中快取。false 代表要走合成。
     */
    private fun playFromCache(voice: OfflineVoice, text: String, sampleRate: Int): Boolean {
        val file = cacheFileFor(voice, text)
        if (!file.isFile || file.length() < BYTES_PER_SAMPLE) return false

        val startedAt = System.currentTimeMillis()
        var track: AudioTrack? = null
        try {
            val samples = FloatArray((file.length() / BYTES_PER_SAMPLE).toInt())
            DataInputStream(file.inputStream().buffered()).use { input ->
                for (i in samples.indices) samples[i] = input.readFloat()
            }

            // 快取存的是原始樣本，增益在播放時才套 —— 這樣調整 GAIN
            // 之後舊快取立刻跟著變大聲，不必清快取或重新合成。
            val amplified = amplify(samples, voice.gain)

            track = createTrack(sampleRate)
            currentTrack = track
            track.play()

            var offset = 0
            while (offset < amplified.size && !cancelled.get()) {
                val count = minOf(PLAYBACK_CHUNK_SAMPLES, amplified.size - offset)
                track.write(amplified, offset, count, AudioTrack.WRITE_BLOCKING)
                offset += count
            }
            drain(track, offset.toLong())

            Log.i(
                TAG,
                "${voice.name} 快取命中「$text」｜音長 ${samples.size * 1000L / sampleRate}ms｜" +
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
    private fun writeCache(voice: OfflineVoice, text: String, samples: FloatArray) {
        val target = cacheFileFor(voice, text)
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

    /**
     * 快取路徑按語言分開。
     *
     * 目錄名帶著模型名：換模型時舊音訊必須失效，否則會用舊聲音播新內容
     * —— 而且取樣率不同的話會變成怪腔怪調。
     */
    private fun cacheFileFor(voice: OfflineVoice, text: String): File {
        val dir = File(appContext.cacheDir, "tts-cache/${voice.assetDir.substringAfterLast('/')}")
            .apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
        return File(dir, "$digest.pcm")
    }

    /** 超過上限就從最舊的開始刪。導盲用語重複性高，命中率不太受影響。 */
    private fun trimCache() {
        val root = File(appContext.cacheDir, "tts-cache")
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension == "pcm" }
            .sortedBy { it.lastModified() }
            .toList()

        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= MAX_CACHE_BYTES) return
            total -= file.length()
            file.delete()
        }
    }

    /**
     * 放大音量。
     *
     * 把眼鏡上快取的 PCM 拉回來量過：**峰值只有 0.21（-13.6 dBFS）**。
     * 系統音效通常做到接近 0 dBFS，所以播報聽起來就是小一截 ——
     * 使用者回報的「比系統聲音小」不是錯覺。
     *
     * 拉高音訊而不是叫使用者調音量，是因為導盲提示走
     * `STREAM_ACCESSIBILITY`，那條串流的音量獨立於媒體音量，
     * 而且眼鏡上預設只有 8/15 —— 不能假設使用者會去調它。
     *
     * 用固定增益而非逐句正規化：串流合成拿不到整句峰值（樣本一塊一塊來），
     * 逐句算會讓同一句話「現場合成」與「快取播放」不一樣大聲。
     *
     * 但**倍率是每個語音各自的**（[OfflineVoice.gain]）—— 實測中文峰值 0.21、
     * 英文 0.58，共用一個倍率必然有一邊出問題。
     */
    private fun amplify(samples: FloatArray, gain: Float): FloatArray =
        FloatArray(samples.size) { (samples[it] * gain).coerceIn(-1f, 1f) }

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

    private companion object {
        const val TAG = "OfflineTts"

        /** `generateWithCallback` 的回傳值：1 繼續合成，0 中止。 */
        const val KEEP_GENERATING = 1
        const val STOP_GENERATING = 0

        const val BUFFER_MULTIPLIER = 4
        const val MIN_BUFFER_BYTES = 16 * 1024
        const val DRAIN_POLL_MILLIS = 20L

        /** 快取是裸的 float32 PCM，沒有檔頭 —— 取樣率由模型決定，不會變。 */
        const val BYTES_PER_SAMPLE = 4L

        /** 播快取時一次寫多少 sample。太大起播會慢，太小 write 呼叫太頻繁。 */
        const val PLAYBACK_CHUNK_SAMPLES = 4096

        /** 32MB 大約放得下六分鐘的語音。 */
        const val MAX_CACHE_BYTES = 32L * 1024 * 1024
    }
}
