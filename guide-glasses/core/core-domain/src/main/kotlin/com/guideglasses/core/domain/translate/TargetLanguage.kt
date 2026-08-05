package com.guideglasses.core.domain.translate

import com.guideglasses.core.domain.text.SpokenText

/**
 * 翻譯的目標語言。
 *
 * 語言清單刻意不是「ML Kit 支援的全部 50 幾種」，而是**導盲情境實際會用到的**：
 * 觀光客問路（英日韓）、外籍移工與看護溝通（越南、泰、印尼）、
 * 以及少數常見歐語。清單短的好處是 [fromSpoken] 誤判機率低，
 * 而且每種語言的語言包都要下載，列出用不到的只會讓使用者誤以為能用。
 *
 * @param code ML Kit 的 BCP-47 語言碼。
 * @param spokenName 播報用的名稱（「已翻譯成英文」）。
 * @param aliases 使用者可能怎麼說。**只放不會誤命中的詞** —— 兩字母
 *   語言碼（en / ja）刻意不放，否則像 "seven" 這種字裡的 "en" 會誤判成英文。
 */
enum class TargetLanguage(
    val code: String,
    val spokenName: String,
    val aliases: List<String>,
) {
    ENGLISH("en", "英文", listOf("英文", "英語", "english")),
    JAPANESE("ja", "日文", listOf("日文", "日語", "日本話", "japanese")),
    KOREAN("ko", "韓文", listOf("韓文", "韓語", "korean")),
    CHINESE("zh", "中文", listOf("中文", "國語", "華語", "chinese", "mandarin")),
    VIETNAMESE("vi", "越南文", listOf("越南文", "越南語", "越南話", "vietnamese")),
    THAI("th", "泰文", listOf("泰文", "泰語", "泰國話", "thai")),
    INDONESIAN("id", "印尼文", listOf("印尼文", "印尼語", "indonesian")),
    SPANISH("es", "西班牙文", listOf("西班牙文", "西班牙語", "spanish")),
    FRENCH("fr", "法文", listOf("法文", "法語", "french")),
    GERMAN("de", "德文", listOf("德文", "德語", "german")),
    ;

    companion object {

        /**
         * 沒有指定語言時的預設。
         *
         * 選英文的理由：導盲使用者最可能遇到的外語溝通對象是外國觀光客或
         * 英文標示，而且英文語言包最小、下載最快。
         */
        val DEFAULT = ENGLISH

        /** 從 LLM 回傳的參數解析（可能是語言碼也可能是中文名稱）。 */
        fun fromCodeOrName(raw: String): TargetLanguage? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            entries.firstOrNull { it.code.equals(trimmed, ignoreCase = true) }?.let { return it }
            return fromSpoken(trimmed)
        }

        /**
         * 從整句話裡找出目標語言。
         *
         * **取最後出現的那個**，而不是第一個。中文的語序把目標語言放在
         * 「翻成」之後，所以「把這句英文翻成日文」要的是日文；
         * 取第一個命中會答錯成英文。
         *
         * @return 找不到任何語言時回傳 null，由呼叫端決定要不要套用 [DEFAULT]。
         */
        fun fromSpoken(utterance: String): TargetLanguage? {
            val normalised = SpokenText.normalise(utterance)
            if (normalised.isEmpty()) return null

            var best: TargetLanguage? = null
            var bestIndex = -1

            for (language in entries) {
                for (alias in language.aliases) {
                    val index = normalised.lastIndexOf(SpokenText.normalise(alias))
                    if (index > bestIndex) {
                        bestIndex = index
                        best = language
                    }
                }
            }
            return best
        }
    }
}
