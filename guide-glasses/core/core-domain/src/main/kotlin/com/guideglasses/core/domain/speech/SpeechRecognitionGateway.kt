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

/**
 * 語音相關的 [AppError.CapabilityUnavailable] 識別字。
 *
 * 實作端（`ai-speech`）與播報端（`feature-assistant`）都引用這裡。
 * 兩邊各自寫字串常值的話，打錯不會編譯失敗，只會讓使用者聽到
 * 一句沒有資訊量的「無法處理」—— 而那正是最難查的一種錯。
 */
object SpeechCapability {

    /** 裝置上根本沒有語音辨識服務。 */
    const val RECOGNITION = "speech-recognition"

    /** 有辨識服務，但缺少該語言的語音資料。使用者需要去系統設定下載。 */
    const val LANGUAGE_PACK = "speech-language-pack"

    /** 辨識器忙碌，稍後重試即可。 */
    const val BUSY = "speech-recognizer-busy"

    /** 麥克風不可用，多半被其他 App 佔用。 */
    const val MICROPHONE = "speech-microphone"
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
