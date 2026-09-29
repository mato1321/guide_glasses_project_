package com.guideglasses.core.domain.motion

import com.guideglasses.core.domain.glasses.CameraFrame
import com.guideglasses.core.domain.glasses.CaptureRequest

/**
 * 依使用者狀態決定相機該跑多快。
 *
 * 這是 210mAh 電池下最有效的省電手段：**停下來的時候不需要一直偵測障礙物**。
 * 站在原地講電話的十分鐘裡，2fps 的偵測完全沒有價值，卻照樣吃電。
 *
 * 有 IMU 才做得到自動切換 —— 沒有動作感測就只能讓使用者手動說
 * 「開始走路模式」，實務上他不會記得。
 *
 * 純 Kotlin，可完整以單元測試驗證。
 */
class CameraModeController(
    private val lowBatteryThresholdPercent: Int = DEFAULT_LOW_BATTERY_PERCENT,
) {

    /**
     * @param walkingState 走路狀態。無法偵測時傳 null，會退回保守設定。
     * @param batteryPercent 電量。未知時傳 null。
     * @param userRequestedContinuous 使用者明確要求持續偵測（例如過馬路）。
     */
    fun decide(
        walkingState: WalkingState?,
        batteryPercent: Int? = null,
        userRequestedContinuous: Boolean = false,
    ): Mode {
        val lowBattery = batteryPercent != null && batteryPercent <= lowBatteryThresholdPercent

        return when {
            // 使用者明確要求時最優先 —— 他可能正要過馬路，
            // 這種時候省電不該凌駕安全。
            userRequestedContinuous && lowBattery -> Mode.POWER_SAVING
            userRequestedContinuous -> Mode.WALKING

            lowBattery -> Mode.POWER_SAVING

            walkingState == WalkingState.WALKING -> Mode.WALKING

            walkingState == WalkingState.STILL -> Mode.STANDBY

            // 偵測不到走路狀態時退回待命 —— 讓使用者主動查詢，
            // 總比在不知道他有沒有在動的情況下一直開著相機好。
            else -> Mode.STANDBY
        }
    }

    enum class Mode(
        val fps: Float,
        val description: String,
    ) {
        /** 待命：相機關閉，只等語音指令。 */
        STANDBY(fps = 0f, description = "待命"),

        /** 省電：低頻偵測，只看最危險的類別。 */
        POWER_SAVING(fps = 1f, description = "省電"),

        /** 行進：正常偵測頻率。 */
        WALKING(fps = 3f, description = "行進"),
        ;

        val isCameraActive: Boolean get() = fps > 0f

        /**
         * 轉成擷取參數。
         *
         * 640 長邊與障礙物偵測一致 —— 這個模式下相機的唯一消費者就是它。
         * 用 RGBA 而非 JPEG：端側推論要的是像素，編碼再解碼是純浪費。
         */
        fun toCaptureRequest(): CaptureRequest? {
            if (!isCameraActive) return null
            return CaptureRequest(
                targetFps = fps,
                longEdgePixels = 640,
                outputFormat = CameraFrame.Format.RGBA_8888,
            )
        }
    }

    companion object {
        const val DEFAULT_LOW_BATTERY_PERCENT = 20
    }
}

/**
 * 走路狀態的去抖動。
 *
 * 原始的步態偵測很跳 —— 等紅綠燈時挪一下腳、轉身看人，都會產生
 * 零星的步數。若直接拿來切換相機模式，會在待命與行進之間反覆橫跳，
 * 反而比一直開著更耗電（相機反覆啟停的成本很高）。
 *
 * 純 Kotlin，時鐘由呼叫端傳入。
 */
class WalkingStateDebouncer(
    /** 連續偵測到步伐多久才算「開始走路」。 */
    private val startDelayMillis: Long = DEFAULT_START_DELAY_MILLIS,
    /** 多久沒有步伐才算「停下來」。 */
    private val stopDelayMillis: Long = DEFAULT_STOP_DELAY_MILLIS,
) {
    init {
        require(startDelayMillis >= 0 && stopDelayMillis >= 0) { "延遲不可為負" }
    }

    private var state: WalkingState = WalkingState.STILL
    private var firstStepAt: Long? = null
    private var lastStepAt: Long? = null

    val currentState: WalkingState get() = state

    /** 偵測到一步時呼叫。 */
    fun onStep(nowMillis: Long) {
        lastStepAt = nowMillis
        if (firstStepAt == null) firstStepAt = nowMillis

        if (state == WalkingState.STILL) {
            val since = nowMillis - (firstStepAt ?: nowMillis)
            if (since >= startDelayMillis) state = WalkingState.WALKING
        }
    }

    /** 定期呼叫以偵測「停下來」。 */
    fun onTick(nowMillis: Long) {
        if (state != WalkingState.WALKING) return
        val last = lastStepAt ?: return
        if (nowMillis - last >= stopDelayMillis) {
            state = WalkingState.STILL
            firstStepAt = null
        }
    }

    fun reset() {
        state = WalkingState.STILL
        firstStepAt = null
        lastStepAt = null
    }

    companion object {
        /** 走三四步（約 1.5 秒）才算真的在走。 */
        const val DEFAULT_START_DELAY_MILLIS = 1_500L

        /** 停兩秒才算停下來 —— 等紅燈時的短暫停頓不該切模式。 */
        const val DEFAULT_STOP_DELAY_MILLIS = 2_000L
    }
}
