package com.guideglasses.core.domain.translate

import com.guideglasses.core.domain.AppResult

/**
 * 預先下載翻譯語言包。
 *
 * 存在的理由：[TranslateUseCase] 只在「有東西要翻」時才會觸發下載，
 * 所以想預先準備就得先做一次 OCR 才能翻。出門前那個流程太麻煩，
 * 而且忘記做的代價是到了現場才發現翻譯不能用。
 *
 * 這個 UseCase 讓使用者直接說「準備翻譯」就把語言包抓好。
 *
 * **一定要在出門前用網路做完** —— 眼鏡沒有 SIM，出了 Wi-Fi 範圍就下載不了。
 */
class PrepareLanguagesUseCase(
    private val translator: Translator,
    private val defaultLanguages: List<TargetLanguage> = listOf(TargetLanguage.DEFAULT),
) {

    /**
     * @param languages 要準備的語言。空的時候用 [defaultLanguages]。
     * @param onProgress 每開始下載一種語言時呼叫。下載可能要幾十秒，
     *   沒有聲音使用者會以為當掉了。
     */
    suspend fun execute(
        languages: List<TargetLanguage> = emptyList(),
        onProgress: (TargetLanguage) -> Unit = {},
    ): Outcome {
        if (!translator.isAvailable) return Outcome.Unavailable

        val targets = languages.ifEmpty { defaultLanguages }
        if (targets.isEmpty()) return Outcome.Unavailable

        val alreadyReady = mutableListOf<TargetLanguage>()
        val downloaded = mutableListOf<TargetLanguage>()
        val failed = mutableListOf<TargetLanguage>()

        for (language in targets) {
            if (translator.isReady(language)) {
                alreadyReady += language
                continue
            }
            onProgress(language)
            when (translator.prepare(language)) {
                is AppResult.Success -> downloaded += language
                is AppResult.Failure -> failed += language
            }
        }

        return Outcome.Finished(alreadyReady, downloaded, failed)
    }

    sealed interface Outcome {

        data class Finished(
            val alreadyReady: List<TargetLanguage>,
            val downloaded: List<TargetLanguage>,
            val failed: List<TargetLanguage>,
        ) : Outcome {

            val allReady: Boolean get() = failed.isEmpty()

            val spoken: String
                get() {
                    val ok = alreadyReady + downloaded
                    return when {
                        failed.isEmpty() && downloaded.isEmpty() ->
                            "${ok.names()}翻譯本來就準備好了"

                        failed.isEmpty() ->
                            "${downloaded.names()}語言包下載完成，現在離線也能翻譯"

                        ok.isEmpty() ->
                            "${failed.names()}語言包下載失敗，請確認網路"

                        else ->
                            "${ok.names()}已就緒，但${failed.names()}下載失敗，請確認網路"
                    }
                }

            private fun List<TargetLanguage>.names() = joinToString("、") { it.spokenName }
        }

        data object Unavailable : Outcome
    }
}
