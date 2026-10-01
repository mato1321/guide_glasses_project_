package com.guideglasses.core.domain.bus

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 「確認公車」播報的句子，見 [ConfirmBusUseCase.spokenResult]。 */
class ConfirmBusSpokenTest {

    // 後端實際回傳的 message（2026-09-30 實測），不能被唸出來。
    private val backendMessage = "辨識成功，確認為 310，StopID=，Direction=-1"

    private fun result(matched: Boolean, text: String) =
        BusOcrResult(matched = matched, message = backendMessage, recognizedText = text)

    @Test
    fun `號碼相符`() {
        assertThat(ConfirmBusUseCase.spokenResult(result(true, "310"), "310"))
            .isEqualTo("辨識成功，車頭號碼與 310 路公車 相符。")
    }

    @Test
    fun `號碼不同`() {
        assertThat(ConfirmBusUseCase.spokenResult(result(false, "307"), "310"))
            .startsWith("車頭號碼看起來不是 310 路公車。")
    }

    @Test
    fun `什麼都沒讀到時請使用者對準車頭，而不是說不是這班`() {
        assertThat(ConfirmBusUseCase.spokenResult(result(false, " "), "310"))
            .startsWith("看不清楚車頭號碼")
    }

    @Test
    fun `完整名稱的路線不加「路公車」`() {
        assertThat(ConfirmBusUseCase.spokenResult(result(true, "敦化幹線"), "敦化幹線"))
            .isEqualTo("辨識成功，車頭號碼與 敦化幹線 相符。")
    }

    @Test
    fun `不唸後端的除錯訊息`() {
        listOf(result(true, "310"), result(false, "307"), result(false, "")).forEach {
            val spoken = ConfirmBusUseCase.spokenResult(it, "310")
            assertThat(spoken).doesNotContain("StopID")
            assertThat(spoken).doesNotContain("Direction")
        }
    }
}
