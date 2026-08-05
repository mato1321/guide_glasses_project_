package com.guideglasses.core.domain.assistant

/**
 * 助理可以執行的動作。
 *
 * 這是系統的「工具清單」—— LLM 的 function calling schema 由此產生，
 * 本地快捷指令也對應到同一組 intent，兩條路徑不會分岔。
 */
enum class AssistantIntent(
    /** 給 LLM 的工具名稱。 */
    val toolName: String,
    /** 給 LLM 的工具說明。 */
    val description: String,
    /** 這個 intent 需要的參數名稱。 */
    val parameters: List<String> = emptyList(),
) {
    STOP(
        toolName = "stop",
        description = "立刻停止目前所有語音播報",
    ),

    DETECT_OBSTACLES(
        toolName = "detect_obstacles",
        description = "偵測並描述使用者前方的障礙物、行人、車輛與路況",
    ),

    IDENTIFY_PERSON(
        toolName = "identify_person",
        description = "辨識使用者前方的人是誰",
    ),

    READ_TEXT(
        toolName = "read_text",
        description = "拍攝並朗讀鏡頭前的文字，例如藥袋、菜單、信件、公文",
    ),

    /** 招牌模式：只唸畫面中最大的那塊字。門牌、店名、站牌、路標。 */
    READ_SIGN(
        toolName = "read_sign",
        description = "只唸出鏡頭前最大的字，用於門牌、店名、站牌、路標",
    ),

    /** 朗讀下一段。長文分成很多段，這是繼續往下聽。 */
    READING_NEXT(
        toolName = "reading_next",
        description = "朗讀下一段",
    ),

    /** 朗讀上一段。漏聽時往回一段。 */
    READING_PREVIOUS(
        toolName = "reading_previous",
        description = "回到上一段重新朗讀",
    ),

    NAVIGATE(
        toolName = "navigate_to",
        description = "導航到指定地點，支援步行與搭乘大眾運輸",
        parameters = listOf("destination"),
    ),

    TRANSLATE(
        toolName = "translate",
        description = "把一段文字翻譯成指定語言",
        parameters = listOf("text", "target_language"),
    ),

    REGISTER_FACE(
        toolName = "register_face",
        description = "把眼前這個人記起來，之後就能認出他",
        parameters = listOf("name"),
    ),

    REPEAT_LAST(
        toolName = "repeat_last",
        description = "重複剛才播報的內容",
    ),

    /**
     * 相機自我檢測。
     *
     * 這不是給一般使用者的功能，是給開發與實機驗證用的 ——
     * 擷取一張影像並播報解析度、位元組數與端到端耗時。
     * 在拿不到 logcat 的情況下（眼鏡戴在頭上），用聽的就能知道相機通不通、多快。
     */
    CAMERA_TEST(
        toolName = "camera_test",
        description = "測試相機是否正常，並回報解析度與耗時",
    ),

    /**
     * 感測器自我檢測。
     *
     * 眼鏡的 IMU 規格說法不一（6 軸還是 9 軸），而有沒有磁力計會決定
     * 能不能提供絕對方位。與其猜，不如讓裝置自己講。
     */
    SENSOR_TEST(
        toolName = "sensor_test",
        description = "測試動作感測器，回報可用的能力",
    ),

    /** 不對應任何工具，交給 LLM 一般對話回覆。 */
    CHAT(
        toolName = "chat",
        description = "一般對話，不需要使用任何工具",
    ),
    ;

    companion object {
        /** 可以提供給 LLM 的工具（不含 CHAT，它是「不呼叫工具」的情形）。 */
        val callableTools: List<AssistantIntent> get() = entries.filter { it != CHAT }

        fun fromToolName(name: String): AssistantIntent? =
            entries.firstOrNull { it.toolName.equals(name, ignoreCase = true) }
    }
}

/**
 * 路由結果。
 *
 * @param intent 判定出的動作。
 * @param arguments 參數，鍵為 [AssistantIntent.parameters] 中的名稱。
 * @param source 這個判定是從哪條路徑來的，用於觀測與除錯。
 * @param spokenReply 當 [intent] 是 [AssistantIntent.CHAT] 時，LLM 的文字回覆。
 */
data class RoutedIntent(
    val intent: AssistantIntent,
    val arguments: Map<String, String> = emptyMap(),
    val source: Source,
    val spokenReply: String? = null,
) {
    enum class Source {
        /** 本地片語比對，延遲 <100ms，離線可用。 */
        LOCAL_FAST_PATH,

        /** 雲端 LLM function calling。 */
        LLM,

        /** LLM 不可用時的降級處理。 */
        FALLBACK,
    }
}
