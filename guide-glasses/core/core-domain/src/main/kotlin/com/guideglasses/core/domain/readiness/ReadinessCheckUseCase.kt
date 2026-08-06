package com.guideglasses.core.domain.readiness

import com.guideglasses.core.domain.face.FaceEmbedder
import com.guideglasses.core.domain.face.PersonRepository
import com.guideglasses.core.domain.face.PhotoSource
import com.guideglasses.core.domain.translate.TargetLanguage
import com.guideglasses.core.domain.translate.Translator

/**
 * 出門前檢查：現在拔掉網路，還有哪些功能能用？
 *
 * ## 為什麼需要這個
 *
 * 眼鏡**沒有 SIM 卡**，出門之後 Wi-Fi 範圍外一律沒有網路。四個核心功能
 * （助理、OCR、人臉、翻譯）本身都是端側的，但其中兩件事**必須在出門前
 * 用網路做完**：
 *
 * 1. **人臉同步** —— 資料庫是空的就誰都認不出來
 * 2. **翻譯語言包** —— ML Kit 沒有 bundled 版，首次使用該語言一定要下載
 *
 * 實測最常見的失敗不是程式壞掉，而是「到了現場才發現忘記同步」。
 * 這個檢查把那件事變成出門前 10 秒就能確認的一句話。
 *
 * 刻意**只報告不修復** —— 修復需要網路，而使用者可能正好在沒網路的地方，
 * 這時能做的只有告訴他該回去做什麼。
 */
class ReadinessCheckUseCase(
    private val embedder: FaceEmbedder,
    private val people: PersonRepository,
    private val translator: Translator,
    private val photoSource: PhotoSource,
    /** 要檢查哪些語言的語言包。預設只檢查預設語言，避免播報過長。 */
    private val languages: List<TargetLanguage> = listOf(TargetLanguage.DEFAULT),
) {

    suspend fun execute(): Report {
        val ready = mutableListOf<TargetLanguage>()
        val missing = mutableListOf<TargetLanguage>()

        if (translator.isAvailable) {
            for (language in languages) {
                if (translator.isReady(language)) ready += language else missing += language
            }
        } else {
            missing += languages
        }

        return Report(
            faceModelReady = embedder.isAvailable,
            knownPeople = people.count(),
            readyLanguages = ready,
            missingLanguages = missing,
            photoSourceConfigured = photoSource.isAvailable,
        )
    }

    data class Report(
        val faceModelReady: Boolean,
        val knownPeople: Int,
        val readyLanguages: List<TargetLanguage>,
        val missingLanguages: List<TargetLanguage>,
        val photoSourceConfigured: Boolean,
    ) {

        /** 現在斷網也能認人嗎。 */
        val faceReadyOffline: Boolean get() = faceModelReady && knownPeople > 0

        /** 現在斷網也能翻譯嗎。 */
        val translateReadyOffline: Boolean get() = readyLanguages.isNotEmpty()

        val allReady: Boolean get() = faceReadyOffline && missingLanguages.isEmpty()

        /**
         * 播報內容。
         *
         * 先講結論再講細節 —— 使用者要的是「能不能出門」，
         * 不是一份逐項狀態清單。有問題時直接說**該做什麼**，
         * 而不只是說哪裡不對。
         */
        val spoken: String
            get() {
                if (allReady) {
                    val languageNames = readyLanguages.joinToString("、") { it.spokenName }
                    return "可以出門了。認得 $knownPeople 個人，$languageNames 翻譯已就緒"
                }

                val todo = buildList {
                    if (!faceModelReady) {
                        add("缺少人臉模型檔")
                    } else if (knownPeople == 0) {
                        add(
                            if (photoSourceConfigured) {
                                "人臉資料庫是空的，請先說同步人臉"
                            } else {
                                "人臉資料庫是空的，而且還沒設定註冊工具"
                            },
                        )
                    }
                    if (missingLanguages.isNotEmpty()) {
                        val names = missingLanguages.joinToString("、") { it.spokenName }
                        add("$names 語言包還沒下載，請先說準備翻譯")
                    }
                }

                val summary = buildString {
                    append("還沒完全準備好。")
                    append(todo.joinToString("；"))
                }

                // 就算有缺，也要說清楚哪些已經能用 —— 使用者可能只想測 OCR，
                // 那他現在就可以出門了。
                val usable = buildList {
                    add("OCR 朗讀")
                    if (faceReadyOffline) add("人臉辨識")
                    if (translateReadyOffline) add("翻譯")
                }
                return "$summary。目前離線可用的有：${usable.joinToString("、")}"
            }
    }
}
