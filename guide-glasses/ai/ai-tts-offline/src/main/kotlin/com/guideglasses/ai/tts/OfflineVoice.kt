package com.guideglasses.ai.tts

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * 一個離線語音（一種語言 ＝ 一顆模型）。
 *
 * 中文與英文的模型差異比想像中大，不只是換個檔名：
 *
 * | | 中文（aishell3） | 英文（piper amy） |
 * |---|---|---|
 * | 文字轉音素 | `lexicon.txt` 詞典查表 | **espeak-ng**，需要一整個資料目錄 |
 * | 取樣率 | 8000 Hz | 22050 Hz |
 * | 數字正規化 | rule FST | espeak 自己處理 |
 * | 資料放哪 | 直接讀 assets | **必須先複製到檔案系統**（見 [espeakDataDir]） |
 *
 * 把這些差異收在這個 enum 裡，[SherpaOfflineTtsAnnouncer] 就只需要
 * 「依語言挑一個 voice」而不必知道任何模型細節。
 *
 * @param languagePrefix BCP-47 的主語言碼，用來比對 `Announcement.languageTag`。
 * @param assetDir 相對於 assets 根目錄。同時也是快取目錄名 ——
 *   換模型時舊音訊必須失效，否則會用舊聲音播新內容。
 * @param gain 播放前的增益，**每個模型不一樣**。實測中文模型峰值只有 0.21
 *   而英文是 0.58 —— 用同一個倍率，不是中文太小聲就是英文削波失真。
 *   數值取「峰值 × gain ≈ 0.85」，留一點餘裕給比較響的句子。
 *   要調整先看 log 裡的「原始峰值」，別憑感覺加。
 */
internal enum class OfflineVoice(
    val languagePrefix: String,
    val assetDir: String,
    val gain: Float,
    private val modelFile: String,
    private val lexiconFile: String,
    private val ruleFstNames: List<String>,
    private val needsEspeakData: Boolean,
) {

    CHINESE(
        languagePrefix = "zh",
        assetDir = "tts/zh-aishell3",
        // 實測峰值 0.19–0.23。×4.0 之後約 0.76–0.92，仍留一點餘裕。
        // 使用者回報 3.5 倍時「比系統聲音小」—— 8kHz 的內容聽感本來就偏悶，
        // 響度要壓到接近滿刻度才跟得上系統音效。
        gain = 4.0f,
        modelFile = "model.onnx",
        lexiconFile = "lexicon.txt",
        // 導盲播報滿是數字（「前方 3 公尺」），少了這些「30」可能整個被跳過。
        ruleFstNames = listOf("date.fst", "number.fst", "phone.fst", "new_heteronym.fst"),
        needsEspeakData = false,
    ),

    /**
     * 英文。**翻譯功能非它不可** —— 在這之前翻譯結果會落到
     * `LogOnlyAnnouncer`，也就是使用者按了翻譯之後完全沒有聲音。
     */
    ENGLISH(
        languagePrefix = "en",
        assetDir = "tts/en-amy",
        // 實測峰值 0.579 —— 比中文響快三倍。沿用 3.5 會變成 2.03 被削到 1.0，
        // 聽起來就是破音。×1.5 約 0.87。
        gain = 1.5f,
        modelFile = "en_US-amy-medium.onnx",
        // piper 走 espeak 音素化，不用詞典。
        lexiconFile = "",
        ruleFstNames = emptyList(),
        needsEspeakData = true,
    ),
    ;

    /**
     * 建立引擎。失敗回 null（呼叫端會退到候選鏈的下一個）。
     *
     * ⚠️ **不要用 fp16 模型**：實測 piper 的 fp16 版在這台眼鏡上
     * `OfflineTts_newFromAsset` 直接 SIGABRT —— 原生訊號，`runCatching`
     * 攔不住，整個行程死。int8 與 fp32 都正常。
     */
    fun createEngine(context: Context): OfflineTts? {
        val dataDir = if (needsEspeakData) espeakDataDir(context) ?: return null else ""
        val ruleFsts = ruleFstNames.joinToString(",") { "$assetDir/$it" }

        // rule FST 若載入失敗就退回沒有正規化再試一次 ——
        // 數字唸得不漂亮，總比完全沒有聲音好。
        return build(context, dataDir, ruleFsts)
            ?: build(context, dataDir, ruleFsts = "")?.also {
                Log.w(TAG, "$name：rule FST 載入失敗，數字與日期不會被正規化")
            }
    }

    private fun build(context: Context, dataDir: String, ruleFsts: String): OfflineTts? =
        runCatching {
            OfflineTts(
                assetManager = context.assets,
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = "$assetDir/$modelFile",
                            lexicon = if (lexiconFile.isEmpty()) "" else "$assetDir/$lexiconFile",
                            tokens = "$assetDir/tokens.txt",
                            dataDir = dataDir,
                        ),
                        /*
                         * 眼鏡是 4 核 2.0GHz。實測 2 → 4 執行緒**沒有差別**
                         * （RTF 2.27 → 2.24），合成期間 CPU 佔用約 206%，
                         * 也就是模型推論本來就吃不滿四核。
                         * 留 4 是因為不會更差，但別指望調它能救延遲。
                         */
                        numThreads = 4,
                    ),
                    ruleFsts = ruleFsts,
                ),
            )
        }.onFailure { error ->
            Log.e(TAG, "$name 模型載入失敗（ruleFsts=${ruleFsts.ifEmpty { "無" }}）", error)
        }.getOrNull()

    /**
     * 把 espeak 資料從 assets 複製到檔案系統，回傳絕對路徑。
     *
     * 為什麼非複製不可：espeak-ng 是 C 函式庫，它用 `fopen` 讀資料目錄，
     * **不認得 Android 的 assets**（那不是真的檔案）。中文模型走詞典查表
     * 沒有這個問題，所以只有英文需要這一步。
     *
     * 只複製一次；已存在就直接用。原始的 espeak-ng-data 含全部語言的字典
     * 共 18MB，這裡只放了英文需要的部分（約 1MB）。
     */
    private fun espeakDataDir(context: Context): String? {
        val target = File(context.filesDir, ESPEAK_DIR)
        val marker = File(target, "phontab")
        if (marker.isFile) return target.absolutePath

        return runCatching {
            copyAssetDir(context, "$assetDir/$ESPEAK_DIR", target)
            Log.i(TAG, "espeak 資料已複製到 ${target.absolutePath}")
            target.absolutePath
        }.onFailure { error ->
            Log.e(TAG, "espeak 資料複製失敗，英文語音不可用", error)
            // 複製到一半失敗留下的殘缺目錄，下次會被當成完整的用 —— 砍掉。
            target.deleteRecursively()
        }.getOrNull()
    }

    private fun copyAssetDir(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            // 沒有子項目代表這是檔案而不是目錄。
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use(input::copyTo)
            }
            return
        }

        target.mkdirs()
        children.forEach { child ->
            copyAssetDir(context, "$assetPath/$child", File(target, child))
        }
    }

    internal companion object {
        private const val TAG = "OfflineTts"
        private const val ESPEAK_DIR = "espeak-ng-data"

        /**
         * 依 `Announcement.languageTag` 挑語音。null 代表系統預設（中文）。
         *
         * 找不到對應語言時回 null，呼叫端會丟例外讓
         * `FallbackAnnouncer` 換下一個候選 —— 用中文模型硬唸日文
         * 只會產生一串聽不懂的音節，比沒有聲音更糟。
         */
        fun forLanguageTag(languageTag: String?): OfflineVoice? {
            if (languageTag.isNullOrBlank()) return CHINESE
            val prefix = languageTag.substringBefore('-').lowercase()
            return entries.firstOrNull { it.languagePrefix == prefix }
        }
    }
}
