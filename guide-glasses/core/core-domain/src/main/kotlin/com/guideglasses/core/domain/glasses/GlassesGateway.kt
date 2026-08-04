package com.guideglasses.core.domain.glasses

import kotlinx.coroutines.flow.Flow

/**
 * 眼鏡裝置的抽象介面。
 *
 * 這一層存在的唯一理由，是把「Rokid CXR-M SDK 到底能做到什麼」這個
 * 尚未完全確定的問題，隔離在單一實作模組裡。
 *
 * 已知的不確定性（詳見 docs/02_ROKID_SDK_ANALYSIS.md）：
 *  - CXR-M 官方文件只找到 takeGlassPhoto() 單張拍照，未見連續影像串流 API
 *  - sendTtsContent() 的音訊實際由眼鏡還是手機發聲，文件未明確說明
 *  - 眼鏡麥克風能否把原始音訊串到手機，未見文件
 *
 * 因此上層一律透過 [capabilities] 動態查詢能力再決定行為，
 * 而不是假設某個 API 一定存在。
 */
interface GlassesGateway {

    /** 連線狀態。 */
    val connectionState: Flow<GlassesConnectionState>

    /** 眼鏡事件（實體 AI 鍵等）。 */
    val events: Flow<GlassesEvent>

    /**
     * 目前這個實作實際支援的能力。
     *
     * 上層依此降級：支援串流就跑行進模式，只支援單張就退回查詢模式。
     */
    val capabilities: GlassesCapabilities

    suspend fun connect(): Boolean

    suspend fun disconnect()
}

enum class GlassesConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

sealed interface GlassesEvent {

    /** 使用者按下眼鏡上的 AI 實體鍵。 */
    data object AiKeyDown : GlassesEvent

    /** 使用者放開 AI 鍵。 */
    data object AiKeyUp : GlassesEvent

    /** 使用者退出 AI 場景。 */
    data object AiExit : GlassesEvent

    /** 眼鏡電量更新，[percent] 為 0..100。用於觸發省電模式。 */
    data class BatteryChanged(val percent: Int) : GlassesEvent
}

/**
 * 眼鏡實際具備的能力。
 *
 * @param frameMode 取得影像的方式。
 * @param maxFramesPerSecond 影像取得的實際上限。
 *   Rokid Glasses 只有 2GB RAM 與 210mAh 電池，這個值務必以實測為準，
 *   不要照抄規格書。
 * @param canSpeakOnGlasses 能否透過眼鏡喇叭播報。false 則退回手機喇叭。
 * @param canDisplayText 能否在眼鏡 HUD 顯示文字。
 *   對全盲使用者無意義，但對低視力使用者與陪同者有用。
 * @param canCaptureAudio 能否取得眼鏡麥克風的音訊。false 則用手機麥克風。
 */
data class GlassesCapabilities(
    val frameMode: FrameMode,
    val maxFramesPerSecond: Float,
    val canSpeakOnGlasses: Boolean,
    val canDisplayText: Boolean,
    val canCaptureAudio: Boolean,
) {
    /** 是否足以支撐「行進中即時警示」。低於 2fps 只能做查詢式描述。 */
    val supportsContinuousDetection: Boolean
        get() = frameMode == FrameMode.STREAM && maxFramesPerSecond >= 2f

    enum class FrameMode {
        /** 完全無法取得影像。 */
        NONE,

        /** 只能單張拍照。 */
        SINGLE_SHOT,

        /** 可連續取得影像串流。 */
        STREAM,
    }
}
