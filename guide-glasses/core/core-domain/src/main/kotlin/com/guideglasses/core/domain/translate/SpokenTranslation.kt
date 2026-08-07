package com.guideglasses.core.domain.translate

import com.guideglasses.core.domain.text.SpokenText

/**
 * 從一整句話裡切出「要翻譯的內容」。
 *
 * 支援使用者直接口述要翻譯的內容：
 *
 * ```
 * 「今天天氣很好，翻成英文」  → 翻譯「今天天氣很好」
 * 「幫我把我肚子餓了翻成日文」 → 翻譯「我肚子餓了」
 * 「翻成英文」               → 沒有內容，改翻上一次 OCR 讀到的東西
 * 「這句話翻成英文」          → 「這句話」是指涉詞，同樣改翻上一次 OCR
 * ```
 *
 * ## 為什麼這件事可以留在本地
 *
 * 一般原則是「需要抽參數就交給 LLM」，因為目的地、人名這類**開放集合**
 * 本地規則抽不可靠。但這裡要抽的不是某個欄位的值，而是
 * **「觸發詞之前的整段話」** —— 邊界由固定說法（翻成／翻譯成／翻作）決定，
 * 是封閉集合。跟 [TargetLanguage.fromSpoken] 是同一個道理，
 * 所以翻譯仍然是唯一完全不依賴 BFF 就能運作的功能。
 *
 * ## 一律在正規化後的文字上運作
 *
 * ASR 的標點極不穩定，同一句話可能有逗號也可能沒有。用
 * [SpokenText.normalise] 之後比對位置才穩定；代價是翻譯的來源文字沒有標點，
 * 對翻譯品質影響很小（ML Kit 本來就不依賴標點）。
 */
object SpokenTranslation {

    /**
     * 觸發詞。**長的必須排在前面**，否則「翻譯成英文」會先被「翻譯」切開，
     * 剩下「成英文」，語言還是解析得出來但邊界是錯的。
     */
    private val TRIGGERS = listOf(
        "翻譯成", "翻譯為", "翻譯到", "翻成", "翻作", "翻譯",
        "translateinto", "translateto", "translate",
    )

    /**
     * 開頭的客套詞。
     *
     * **只用來判斷後面是不是指涉詞，絕不從要翻譯的內容裡刪掉。**
     *
     * 曾經真的拿它去剝內容，結果「我要去車站翻成英文」被剝成「去車站」——
     * 使用者聽到的英文少了半句，而他**看不到畫面，永遠不會發現**。
     * 這類詞的歧義是本質的：「幫我」在「幫我把這句翻成英文」是客套，
     * 在「幫我開門翻成英文」卻是內容。既然分不出來，就不要賭。
     */
    private val LEADING_FILLERS = listOf(
        "幫我", "幫忙", "請你", "請幫我", "請", "麻煩", "可以", "我要", "我想",
        "把", "將", "helpme", "please",
    )

    /**
     * 指涉「剛才那份內容」的說法。
     *
     * 使用者說「這句話翻成英文」時，要翻的是上一次 OCR 讀到的東西，
     * 而不是「這句話」這四個字本身。少了這層判斷，他會聽到
     * 「this sentence」然後完全不知道發生什麼事。
     */
    private val REFERENTIAL = setOf(
        "這句", "這句話", "這段", "這段話", "這個", "這些", "這些字", "這張", "這裡",
        "那句", "那句話", "那段", "那段話", "那個", "那些",
        "上面", "上面的", "上面的字", "上面寫的", "上面那些",
        "剛才", "剛剛", "剛才的", "剛剛的", "剛才那句", "剛剛那句", "剛才說的",
        "內容", "文字", "以上", "它", "他", "她", "this", "that", "it",
    )

    /**
     * 這句話是不是「⋯⋯翻成某語言」的形式。
     *
     * [com.guideglasses.core.domain.assistant.LocalCommandMatcher] 用它來讓
     * 翻譯壓過其他指令：使用者口述的內容裡可能剛好含有「繼續」「重複」
     * 這類指令詞，那是要翻譯的**內容**，不是指令。
     */
    fun endsWithRequest(utterance: String): Boolean {
        val normalised = SpokenText.normalise(utterance)
        val split = splitAtTrigger(normalised) ?: return false
        // 觸發詞前面要有東西，後面要指得出語言 —— 兩者都成立才是這個形式。
        return split.before.isNotBlank() && TargetLanguage.fromSpoken(split.after) != null
    }

    /**
     * 抽出要翻譯的內容。
     *
     * @return null 代表這句話沒有自帶內容，呼叫端應改用上一次 OCR 的結果。
     */
    fun extractText(utterance: String): String? {
        val normalised = SpokenText.normalise(utterance)
        val split = splitAtTrigger(normalised) ?: return null

        val body = split.before
        if (body.isNotBlank()) {
            // 「這句話翻成英文」「幫我把上面的字翻成英文」—— 指的是上一次 OCR
            // 的內容，不是這幾個字本身。剝掉客套詞只是為了認出這種說法，
            // 認不出來就把**原句原封不動**交出去，寧可多翻幾個字也不要少翻。
            return if (body.stripLeadingFillers() in REFERENTIAL) null else body
        }

        // 「翻成英文今天天氣很好」這種把內容放後面的說法。
        return split.after.removeLanguageAlias().stripLeadingFillers().takeIf { it.isNotBlank() }
    }

    private fun String.stripLeadingFillers(): String {
        var result = this
        var changed = true
        while (changed) {
            changed = false
            for (filler in LEADING_FILLERS) {
                // 剝到只剩贅詞就停手 —— 「把翻成英文」的「把」不該被吃掉之後
                // 變成空字串再去撈別的東西。
                if (result.length > filler.length && result.startsWith(filler)) {
                    result = result.substring(filler.length)
                    changed = true
                }
            }
        }
        return result
    }

    /** 去掉句尾指定語言的那幾個字，剩下的才可能是內容。 */
    private fun String.removeLanguageAlias(): String {
        val language = TargetLanguage.fromSpoken(this) ?: return this
        for (alias in language.aliases.sortedByDescending { it.length }) {
            val normalisedAlias = SpokenText.normalise(alias)
            val index = indexOf(normalisedAlias)
            if (index >= 0) return removeRange(index, index + normalisedAlias.length)
        }
        return this
    }

    /**
     * 在最後一個觸發詞處切開。
     *
     * 取**最後**一個而不是第一個 —— 要翻譯的內容裡本身就可能出現「翻譯」
     * 兩個字（「這份翻譯很爛，翻成英文」）。
     */
    private fun splitAtTrigger(normalised: String): Split? {
        var bestIndex = -1
        var bestTrigger = ""

        for (trigger in TRIGGERS) {
            val index = normalised.lastIndexOf(trigger)
            if (index < 0) continue
            // 同一個位置有多個觸發詞命中時取最長的，邊界才會對。
            if (index > bestIndex || (index == bestIndex && trigger.length > bestTrigger.length)) {
                bestIndex = index
                bestTrigger = trigger
            }
        }

        if (bestIndex < 0) return null
        return Split(
            before = normalised.substring(0, bestIndex),
            after = normalised.substring(bestIndex + bestTrigger.length),
        )
    }

    private data class Split(val before: String, val after: String)
}
