package com.guideglasses.core.domain.translate

import com.guideglasses.core.domain.AppResult

/**
 * 翻譯能力的抽象。
 *
 * 和 [com.guideglasses.core.domain.ocr.TextRecognizer] 一樣放在 domain 當介面，
 * 讓 [TranslateUseCase] 的降級行為（語言包還沒下載、沒網路）能用純 JVM 測試驗證。
 *
 * 這個介面刻意把「準備語言包」和「翻譯」分成兩步。ML Kit 的離線翻譯需要
 * **每個語言各下載一次語言包**（約 30MB），首次使用必須有網路。對看不見畫面的
 * 使用者，那段等待一定要有語音提示，所以上層需要知道「現在是在下載還是在翻譯」。
 */
interface Translator {

    /** 這個翻譯器本身可用嗎（函式庫就緒）。 */
    val isAvailable: Boolean

    /** 指定語言的語言包是否已在本機，可離線翻譯。 */
    suspend fun isReady(target: TargetLanguage): Boolean

    /**
     * 確保語言包就緒。已下載過則立即成功。
     *
     * 首次下載需要網路；沒網路時回傳 [com.guideglasses.core.domain.AppError.NoNetwork]。
     */
    suspend fun prepare(target: TargetLanguage): AppResult<Unit>

    /**
     * 翻譯。呼叫前應先確保 [prepare] 成功。
     *
     * 來源語言由實作決定 —— 目前是啟發式判斷（見實作註解），
     * 尚未接語言偵測。
     */
    suspend fun translate(text: String, target: TargetLanguage): AppResult<String>
}
