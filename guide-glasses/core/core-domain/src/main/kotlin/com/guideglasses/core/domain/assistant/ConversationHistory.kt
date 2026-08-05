package com.guideglasses.core.domain.assistant

/**
 * 有上限的對話歷史。
 *
 * 舊後端用的是模組層級的全域 `ConversationBufferMemory`，有兩個問題：
 * 所有使用者共用同一段記憶（同時使用會互相污染），且長度無上限
 * （token 成本無上限成長，最終超過 context window 直接失敗）。
 *
 * 這裡改成有界的環狀緩衝，並且是可注入的實例而非全域單例。
 */
class ConversationHistory(
    private val maxTurns: Int = DEFAULT_MAX_TURNS,
) {
    init {
        require(maxTurns > 0) { "maxTurns 必須大於 0" }
    }

    private val turns = ArrayDeque<Turn>()

    val size: Int get() = turns.size

    fun record(turn: Turn) {
        turns.addLast(turn)
        while (turns.size > maxTurns) {
            turns.removeFirst()
        }
    }

    fun snapshot(): List<Turn> = turns.toList()

    fun clear() = turns.clear()

    data class Turn(val role: Role, val content: String) {
        enum class Role { USER, ASSISTANT }
    }

    companion object {
        /**
         * 導盲場景的對話幾乎都是單輪的指令（「前面有什麼」「這是誰」），
         * 保留太多輪只會增加 token 成本與延遲，對意圖判斷沒有幫助。
         */
        const val DEFAULT_MAX_TURNS: Int = 10
    }
}
