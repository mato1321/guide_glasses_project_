package com.guideglasses.core.domain.assistant

import com.guideglasses.core.domain.text.SpokenText

/**
 * 本地快捷指令比對。
 *
 * 這一層只處理「高頻、且不能等雲端」的指令。使用者說「停」的時候，
 * 不該先跑一趟 LLM 才安靜下來 —— 那可能要一兩秒，而那一兩秒
 * 正是他想聽清楚環境聲音的時候。
 *
 * 刻意**只比對使用者說的話**。舊專案把 AI 的回覆一起丟進關鍵字比對
 * （`shouldSwitchToFaceRecognition(replyText, userText)`），導致助理只要
 * 在回答中提到「人臉辨識」四個字就會誤觸發功能切換。
 *
 * 需要參數的指令（導航、翻譯、記住某人）一律不在這裡處理 ——
 * 從自由語句中抽取目的地或人名，本地規則做不可靠，交給 LLM。
 */
class LocalCommandMatcher {

    /**
     * @return 命中的 intent，未命中則為 null（交給 LLM）。
     */
    fun match(utterance: String): AssistantIntent? {
        val normalised = normalise(utterance)
        if (normalised.isEmpty()) return null

        // 依序比對，順序即優先級：STOP 必須最先，否則
        // 「停，前面有什麼」會被誤判成障礙物查詢而繼續播報。
        for ((intent, phrases) in ORDERED_RULES) {
            if (phrases.any { normalised.contains(it) }) return intent
        }
        return null
    }

    /**
     * 正規化：去掉標點與空白、全形轉半形、英文轉小寫。
     *
     * ASR 產生的文字標點很不穩定（同一句話可能是「停」「停。」「停，」），
     * 不正規化就會漏判。實作抽到 [SpokenText] 共用，因為翻譯的語言解析
     * 需要完全一致的正規化 —— 兩份實作若不同步會出現「同一句話在這裡有效、
     * 在那裡無效」這種很難查的問題。
     */
    private fun normalise(raw: String): String = SpokenText.normalise(raw)

    private companion object {

        /**
         * 片語一律以正規化後的形式書寫（無標點、無空白、英文小寫）。
         *
         * 這些片語來自「視障使用者實際會怎麼說」，而不是功能名稱。
         * 例如沒有人會對助理說「啟用人臉辨識」，他們會說「這是誰」。
         */
        val ORDERED_RULES: List<Pair<AssistantIntent, List<String>>> = listOf(
            AssistantIntent.STOP to listOf(
                "停止", "停下", "別說了", "不要說了", "安靜", "閉嘴", "取消",
                "stop", "quiet",
                // 單字「停」放在最後，避免比長片語更早命中而吃掉語意
                "停",
            ),

            // 放在 REPEAT_LAST 之前：「再拍一張」同時含有「再」與「拍一張」，
            // 使用者的意思是拍照而不是重播。
            AssistantIntent.SENSOR_TEST to listOf(
                "測試感測器", "感測器測試", "檢查感測器", "測試動作",
                "有沒有羅盤", "sensortest",
            ),

            AssistantIntent.CAMERA_TEST to listOf(
                "測試相機", "相機測試", "檢查相機", "相機正常嗎", "拍一張",
                "cameratest", "testcamera",
            ),

            AssistantIntent.REPEAT_LAST to listOf(
                "再說一次", "再講一次", "重複", "重覆", "剛剛說什麼", "剛才說什麼",
                "沒聽清楚", "再念一次", "再唸一次",
                "repeat", "again",
            ),

            // 必須排在 IDENTIFY_PERSON 之前：「同步人臉」含有「人臉」，
            // 而辨識的片語裡沒有「同步」，順序反了會被誤判成問「這是誰」。
            AssistantIntent.SYNC_PEOPLE to listOf(
                "同步人臉", "同步照片", "更新人臉", "重新同步", "同步名單",
                "syncfaces", "syncpeople",
            ),

            AssistantIntent.IDENTIFY_PERSON to listOf(
                "這是誰", "這個人是誰", "那是誰", "前面是誰", "前方是誰",
                "誰在前面", "誰在我前面", "他是誰", "她是誰", "認得他嗎", "認識他嗎",
                "whoisthis", "whoisthat",
            ),

            // 朗讀控制必須排在 READ_TEXT 之前 ——「唸下一段」同時含有
            // 「唸」與「下一段」，使用者要的是往下不是重拍。
            AssistantIntent.READING_NEXT to listOf(
                "下一段", "繼續唸", "繼續念", "繼續讀", "繼續",
                "next",
            ),

            AssistantIntent.READING_PREVIOUS to listOf(
                "上一段", "前一段", "回上一段", "退回去",
                "previous", "back",
            ),

            /**
             * 翻譯是唯一「需要參數但仍放在本地」的例外。
             *
             * 目標語言的說法是封閉集合（「翻成英文」「翻譯成日文」），本地解析
             * 很可靠 —— 見 [com.guideglasses.core.domain.translate.TargetLanguage.fromSpoken]。
             * 因此翻譯**不需要 BFF 就能完整運作**，這是刻意的設計。
             *
             * 必須排在 READ_SIGN / READ_TEXT 之前：「翻譯這上面寫什麼」同時含有
             * 「翻譯」與「上面寫什麼」，使用者要的是翻譯而不是重新 OCR。
             *
             * 刻意不收「怎麼說」「怎麼講」這種太寬的說法 —— 「前面那個東西怎麼說」
             * 想問的是描述而不是翻譯，寧可漏判讓它落到 LLM，也不要誤觸發。
             */
            AssistantIntent.TRANSLATE to listOf(
                "翻譯", "翻成", "翻作", "translate",
            ),

            AssistantIntent.READ_SIGN to listOf(
                "這是哪裡", "這是什麼地方", "招牌寫什麼", "門牌", "這站是哪",
                "什麼店",
            ),

            AssistantIntent.READ_TEXT to listOf(
                "唸給我聽", "念給我聽", "讀給我聽", "唸出來", "念出來", "讀出來",
                "上面寫什麼", "這寫什麼", "這是什麼字", "寫了什麼", "幫我看字",
                "readthis", "readit",
            ),

            AssistantIntent.DETECT_OBSTACLES to listOf(
                "前面有什麼", "前方有什麼", "看看前面", "看看前方", "看一下前面",
                "有障礙物嗎", "有沒有障礙", "前面安全嗎", "可以走嗎", "路況如何",
                "有車嗎", "有人嗎", "描述環境", "周圍有什麼",
                "whatsahead", "whatisahead",
            ),
        )
    }
}
