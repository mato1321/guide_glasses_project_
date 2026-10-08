package com.guideglasses.core.domain.assistant

import com.guideglasses.core.domain.text.SpokenText

/**
 * 喚醒詞比對。
 *
 * 使用者不必先摸到按鈕就能叫醒助理 —— 對戴在頭上、看不見按鈕在哪的人，
 * 這是差別最大的互動改進。
 *
 * ### 為什麼支援好幾個說法
 *
 * 眼鏡上的辨識模型是通用中文模型，**罕見詞會被聽得很爛**。
 * 「呼叫盲狗」實測四個音節全部不穩：
 *
 * ```
 * 呼 → 胡、哭、不        叫 → 照、啸、（消失）
 * 盲 → 忙、芒、猛、蒛     狗 → 够、（消失）
 * ```
 *
 * 命中率一直上不去，根因是「盲狗」在一般語料裡幾乎不出現，模型沒學過。
 * 硬調比對規則只是在追一個追不完的目標。
 *
 * 所以改成**同時支援數個常見詞**：「小幫手」「小助手」「你好眼鏡」都是
 * 語料裡很常見的組合，模型轉出來穩定得多。使用者用哪個順就用哪個，
 * log 會記下實際命中的是哪一組，之後可以據此收斂。
 *
 * ### 每一組都刻意寫得寬鬆
 *
 * 同音字替換是常態（幫/邦、鏡/竟、狗/够），所以字元類別而不是固定字串。
 * 誤觸的代價只是助理應一聲；漏聽的代價是使用者對著沒反應的眼鏡一直喊 ——
 * 後者明顯更糟，取捨往寬鬆那邊放。
 */
object WakeWord {

    /** 推薦說法。播報提示與文件都用這個。 */
    const val CANONICAL = "小幫手"

    /**
     * 一組喚醒說法。
     *
     * @param label 只用於 log —— 知道使用者實際喊的是哪一個、哪一組最穩，
     *   才有依據決定要不要淘汰某些說法。
     */
    private class Candidate(val label: String, val pattern: Regex)

    /**
     * 只靠單一關鍵字認人時，句子最多幾個字。
     *
     * 判準來自實測：喚醒嘗試都很短（2–4 字），而喚醒監聽在沒人叫它時
     * 收到的環境語音都比較長（「因为现在不」「家叫一个然后让这个」
     * 「那昨前方那昨天方没一轮呢几」）。
     */
    private const val SHORT_UTTERANCE_LIMIT = 5

    private val CANDIDATES = listOf(
        // 「小幫手」「小助手」—— 語料裡很常見，是目前推薦的說法。
        // 小→晓/消/削，幫→邦/帮，助→住/祝，手→守/受/首
        Candidate("幫手", Regex("[小晓消削][帮幫邦助住祝][手守受首]")),

        // 「你好眼鏡」「嘿眼鏡」—— 「眼鏡」兩字辨識度高且與情境相符。
        // 鏡→竟/静/劲
        Candidate("眼鏡", Regex("[眼演][镜鏡竟静劲]")),

        // 舊的「呼叫盲狗」家族。四個音節都不穩，靠「叫…狗」的結構撐著。
        Candidate("叫狗", Regex("叫.{0,2}[狗够犬]")),
    )

    /** 短句救援用的關鍵字：整句很短又出現這些字，多半就是在叫它。 */
    private val SHORT_ANCHORS = setOf('狗', '够', '犬')

    /**
     * 這句話是不是在叫助理。
     *
     * @param utterance ASR 的原始輸出，不需要先正規化。
     * @return 命中的說法名稱（供 log 用），沒中則為 null。
     */
    fun match(utterance: String): String? {
        val folded = SpokenText.forMatching(utterance)
        if (folded.isEmpty()) return null

        CANDIDATES.firstOrNull { it.pattern.containsMatchIn(folded) }?.let { return it.label }

        // 「叫」被聽壞時的救援 —— 靠「短」把日常對話擋在外面。
        if (folded.length <= SHORT_UTTERANCE_LIMIT && folded.any { it in SHORT_ANCHORS }) {
            return "短句狗"
        }
        return null
    }

    fun matches(utterance: String): Boolean = match(utterance) != null
}
