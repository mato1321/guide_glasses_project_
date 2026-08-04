package com.guideglasses.core.domain.speech

import com.guideglasses.core.domain.AppError
import kotlinx.coroutines.flow.Flow

/**
 * 語音辨識的抽象。
 *
 * 舊做法是「按一下開始錄 → 再按一下停止 → 上傳整段音檔到後端 →
 * Whisper 轉文字 → 等回傳」，來回至少三到五秒，而且完全依賴網路。
 *
 * 改用串流式辨識之後，使用者說到一半就有部分結果，而且能自動偵測說完，
 * 不必記得再按一次。對看不見按鈕的使用者，少一次操作差別很大。
 */
interface SpeechRecognitionGateway {

    /** 裝置上是否有可用的語音辨識服務。 */
    val isAvailable: Boolean

    /**
     * 開始聆聽。Flow 結束代表這一輪辨識結束。
     *
     * @param preferOffline 優先使用裝置端離線模型。導盲場景常在
     *   收訊不佳的地方（地下道、騎樓），離線可用是基本要求。
     */
    fun listen(preferOffline: Boolean = true): Flow<SpeechEvent>

    /** 提前中止目前這一輪辨識。 */
    fun cancel()

    fun shutdown()
}

sealed interface SpeechEvent {

    /** 已就緒，可以開始說話了。UI 應在此時給出提示音。 */
    data object ReadyForSpeech : SpeechEvent

    /** 偵測到使用者開始說話。 */
    data object SpeechStarted : SpeechEvent

    /** 邊說邊出的部分結果，可用於即時顯示，但不可據此執行動作。 */
    data class PartialResult(val text: String) : SpeechEvent

    /** 這一輪的最終結果。 */
    data class FinalResult(val text: String) : SpeechEvent

    /** 辨識失敗。 */
    data class Failed(val error: AppError) : SpeechEvent
}
