package com.guideglasses.core.domain.assistant

/**
 * 直接從聲音偵測到的指令。
 *
 * ### 為什麼不經過語音辨識
 *
 * 原本的流程是「喚醒詞 → 語音辨識轉文字 → 比對片語 → 執行」，
 * 使用者要等的是這一整串：喚醒偵測、提示音、再講一次、辨識 2–3 秒。
 * 實測下來每一段都在扣分，而使用者的結論很直接 ——
 * 「呼叫他好像沒有什麼用」。
 *
 * 但導盲的指令是**封閉集合**，就那十幾句。既然如此，
 * 關鍵詞偵測可以直接把「指令本身」當成要偵測的詞：
 * 聽到「前面有什麼」就直接做，中間那些步驟一個都不需要。
 *
 * | | 舊流程 | 現在 |
 * |---|---|---|
 * | 步驟 | 喚醒 → 嗶 → 再講 → 辨識 → 執行 | 講 → 執行 |
 * | 延遲 | 喚醒偵測 ＋ 2–3 秒辨識 | 只有偵測 |
 * | 失敗點 | 喚醒沒中、辨識聽錯、片語沒對上 | 偵測沒中 |
 *
 * 開放式的輸入（要翻譯的整段內容、「帶我去哪裡」）仍然需要語音辨識，
 * 那時候再走原本那條路。
 *
 * ### 關鍵詞怎麼加
 *
 * 兩個地方要一起改，缺一個就會安靜地沒反應：
 * 1. `ai-asr-offline/src/main/assets/kws/keywords.txt` —— 加拼音與漢字
 * 2. 這裡的 [MAPPING] —— 把漢字對到要執行的功能
 */
object VoiceCommand {

    /**
     * 偵測到的關鍵詞 → 要執行的功能。
     *
     * 鍵必須與 `keywords.txt` 裡 `@` 後面的漢字**完全一致**。
     */
    private val MAPPING: Map<String, AssistantIntent> = mapOf(
        "前面有沒有人" to AssistantIntent.IDENTIFY_PERSON,
        "前面有什麼" to AssistantIntent.DETECT_OBSTACLES,
        "這個人是誰" to AssistantIntent.IDENTIFY_PERSON,
        "這是誰" to AssistantIntent.IDENTIFY_PERSON,
        "上面寫什麼" to AssistantIntent.READ_TEXT,
        "唸給我聽" to AssistantIntent.READ_TEXT,
        "這是哪裡" to AssistantIntent.READ_SIGN,
        "翻成英文" to AssistantIntent.TRANSLATE,
        "下一段" to AssistantIntent.READING_NEXT,
        "測試相機" to AssistantIntent.CAMERA_TEST,
        "出門前檢查" to AssistantIntent.READINESS_CHECK,
        "同步人臉" to AssistantIntent.SYNC_PEOPLE,
    )

    /** 這個關鍵詞對應到哪個功能。認不得就回 null。 */
    fun intentFor(keyword: String): AssistantIntent? = MAPPING[keyword.trim()]

    /** 目前支援的所有語音指令，供播報「你可以說⋯」使用。 */
    val ALL: Set<String> = MAPPING.keys
}
