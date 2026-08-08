package com.guideglasses.core.domain.assistant

import com.guideglasses.core.domain.text.SpokenText

/**
 * 喚醒詞比對。
 *
 * 使用者不必先按按鈕 —— 說一聲就開始聽指令。對戴在頭上、
 * 看不到按鈕在哪的使用者，這是差別最大的一個互動改進。
 *
 * ### 為什麼需要這麼多變體
 *
 * 「盲狗」不是一般語料裡常見的詞，通用中文辨識模型很容易聽成同音或近音字
 * （忙狗、芒狗、盲購⋯）。只比對標準寫法的話，使用者會覺得
 * 「我明明喊了它卻沒反應」，而那在 log 裡看起來完全正常 —— 辨識有結果，
 * 只是不等於那四個字。
 *
 * 所以這裡收一組容錯寫法。真實裝置上還會聽到什麼，只能靠實測補 ——
 * `AssistantViewModel` 會把喚醒模式下聽到的每一句都記進 log，
 * 就是為了讓這份清單有依據可以長大。
 *
 * ### 誤觸的取捨
 *
 * 變體放得越寬，越容易被日常對話誤觸。所以刻意**不收單獨的「盲狗」** ——
 * 前面要有「呼叫」或「叫」，這樣「這隻導盲狗好可愛」不會把助理叫醒。
 */
object WakeWord {

    /** 標準說法。播報提示與文件都用這個。 */
    const val CANONICAL = "呼叫盲狗"

    /**
     * 比對用的變體，全部以摺疊後的形式儲存。
     *
     * 用 `contains` 而不是完全相等 —— 使用者常常會在前後加東西
     * （「欸，呼叫盲狗」「呼叫盲狗幫我看一下」）。
     */
    private val VARIANTS: List<String> = listOf(
        // 標準與常見的近音誤判
        "呼叫盲狗", "呼叫忙狗", "呼叫芒狗", "呼叫盲購", "呼叫忙購",
        "呼叫盲果", "呼叫芒果", "呼叫盲構",
        // 少了「呼」的短說法
        "叫盲狗", "叫忙狗", "叫芒狗",
        // 把「盲狗」聽成「盲犬」「導盲犬」
        "呼叫盲犬", "叫盲犬",
    ).map(SpokenText::forMatching)

    /**
     * 這句話是不是在叫助理。
     *
     * @param utterance ASR 的原始輸出，不需要先正規化。
     */
    fun matches(utterance: String): Boolean {
        val folded = SpokenText.forMatching(utterance)
        if (folded.isEmpty()) return false
        return VARIANTS.any { folded.contains(it) }
    }
}
