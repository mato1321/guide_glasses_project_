package com.guideglasses.ai.translate

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.translate.TargetLanguage
import com.guideglasses.core.domain.translate.Translator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import com.google.mlkit.nl.translate.Translator as MlKitTranslatorClient

/**
 * 端側翻譯，用 ML Kit Translation。
 *
 * 下載過語言包之後**完全離線、零成本**，這對導盲場景很重要 ——
 * 使用者可能在地下街看菜單，那裡沒有訊號。
 *
 * ## 與 OCR 的關鍵差異：語言包必須下載
 *
 * OCR 的中文模型有 bundled 版可以打包進 APK，翻譯**沒有**。ML Kit 一律在
 * 執行期下載，每種語言約 30MB。所以：
 *
 * - 首次使用某語言需要網路
 * - 2GB RAM 的眼鏡上不該預先下載十種語言，只下載使用者真正要的那種
 * - 下載期間必須有語音提示，否則使用者以為系統當掉
 *
 * ## 來源語言目前是啟發式判斷
 *
 * ML Kit 需要明確的來源語言，但這裡還沒接語言偵測
 * （`com.google.mlkit:language-id`）。目前的規則：
 *
 * - 目標是中文 → 來源當作英文（使用者在看外文標示）
 * - 其他情況 → 來源當作中文（使用者在翻譯眼前的中文內容給外國人看）
 *
 * 這涵蓋導盲最常見的兩種情境，但「把日文菜單翻成英文」會判錯。
 * 接語言偵測是 `docs/TASKS.md` 的待辦。
 */
class MlKitTranslator(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Translator {

    private val modelManager by lazy { RemoteModelManager.getInstance() }

    /** 每個語言對一個 client。建立成本不低，重用。 */
    private val clients = mutableMapOf<String, MlKitTranslatorClient>()

    override val isAvailable: Boolean = true

    /**
     * 來源與目標的模型**都**要下載好才算就緒。
     *
     * 原本只檢查目標語言，而 ML Kit 以英文為樞紐、英文模型永遠回報「已下載」。
     * 於是「中文翻英文」會被誤判成就緒，[TranslateUseCase] 因此跳過下載，
     * 接著 `translate()` 直接拋
     * `Translation model files not found` —— 真正缺的是**中文**模型。
     *
     * 實機上這條路徑 100% 失敗，而且錯誤訊息完全指不到原因。
     */
    override suspend fun isReady(target: TargetLanguage): Boolean = withContext(ioDispatcher) {
        val tags = listOf(target.code, target.sourceLanguageTag()).distinct()
        val models = tags.mapNotNull { remoteModelFor(it) }
        if (models.size != tags.size) return@withContext false

        models.all { model -> awaitTask { modelManager.isModelDownloaded(model) } == true }
    }

    /**
     * 下載語言包。
     *
     * 刻意**不加** `requireWifi()`。導盲使用者在外面時通常只有行動網路，
     * 要求 Wi-Fi 等於這個功能在戶外永遠不能第一次使用。
     */
    override suspend fun prepare(target: TargetLanguage): AppResult<Unit> =
        withContext(ioDispatcher) {
            val client = clientFor(target)
                ?: return@withContext AppResult.Failure(
                    AppError.CapabilityUnavailable("translate", "不支援 ${target.spokenName}"),
                )

            val conditions = DownloadConditions.Builder().build()

            val error = awaitTaskError { client.downloadModelIfNeeded(conditions) }
            if (error == null) {
                AppResult.Success(Unit)
            } else {
                Log.e(TAG, "下載 ${target.code} 語言包失敗", error)
                // 下載失敗實務上幾乎都是網路問題。回 NoNetwork 讓上層播報
                // 「目前沒有網路」這種使用者能理解並自行處理的訊息。
                AppResult.Failure(
                    AppError.NoNetwork("無法下載 ${target.spokenName} 語言包：${error.message}"),
                )
            }
        }

    override suspend fun translate(
        text: String,
        target: TargetLanguage,
    ): AppResult<String> = withContext(ioDispatcher) {
        val client = clientFor(target)
            ?: return@withContext AppResult.Failure(
                AppError.CapabilityUnavailable("translate", "不支援 ${target.spokenName}"),
            )

        val translated = awaitTask { client.translate(text) }
            ?: return@withContext AppResult.Failure(AppError.NoResult("翻譯沒有回傳結果"))

        AppResult.Success(translated)
    }

    private fun clientFor(target: TargetLanguage): MlKitTranslatorClient? {
        val targetCode = TranslateLanguage.fromLanguageTag(target.code) ?: return null
        val sourceCode = TranslateLanguage.fromLanguageTag(target.sourceLanguageTag())
            ?: return null

        return clients.getOrPut(target.code) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(sourceCode)
                    .setTargetLanguage(targetCode)
                    .build(),
            )
        }
    }

    /** 見類別註解「來源語言目前是啟發式判斷」。 */
    private fun TargetLanguage.sourceLanguageTag(): String =
        if (this == TargetLanguage.CHINESE) TargetLanguage.ENGLISH.code else TargetLanguage.CHINESE.code

    private fun remoteModelFor(languageTag: String): TranslateRemoteModel? =
        TranslateLanguage.fromLanguageTag(languageTag)
            ?.let { TranslateRemoteModel.Builder(it).build() }

    /** 成功回傳結果，失敗回傳 null。 */
    private suspend fun <T> awaitTask(
        start: () -> com.google.android.gms.tasks.Task<T>,
    ): T? = suspendCancellableCoroutine { continuation ->
        start()
            .addOnSuccessListener { continuation.resume(it) }
            .addOnFailureListener {
                Log.e(TAG, "ML Kit task 失敗", it)
                continuation.resume(null)
            }
    }

    /**
     * 成功回傳 null，失敗回傳例外。
     *
     * 下載任務的結果是 `Void`，用 [awaitTask] 無法區分「成功」與「失敗回傳 null」，
     * 所以另外一個版本把錯誤本身帶回來 —— 上層需要它來判斷是不是網路問題。
     */
    private suspend fun awaitTaskError(
        start: () -> com.google.android.gms.tasks.Task<Void>,
    ): Throwable? = suspendCancellableCoroutine { continuation ->
        start()
            .addOnSuccessListener { continuation.resume(null) }
            .addOnFailureListener { continuation.resume(it) }
    }

    fun close() {
        clients.values.forEach { client ->
            runCatching { client.close() }
                .onFailure { Log.w(TAG, "關閉 translator 失敗", it) }
        }
        clients.clear()
    }

    private companion object {
        const val TAG = "MlKitTranslate"
    }
}
