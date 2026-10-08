package com.guideglasses.core.domain.motion

import kotlinx.coroutines.flow.Flow

/**
 * 動作感測的抽象。
 *
 * Rokid Glasses **沒有 GPS**，但有 IMU。這讓 IMU 的角色比一般手機重要得多 ——
 * 它是眼鏡上唯一能感知「使用者在做什麼」的東西。
 *
 * 三個直接的用途：
 *
 * 1. **相機模式自動切換** —— 停下來時不需要 2fps 一直偵測障礙物。
 *    這是 210mAh 電池下最有效的省電手段。
 * 2. **方位修正** —— 使用者轉頭之後，「右前方」指的方向就變了。
 * 3. **步態導航輔助** —— 沒有 GPS 的情況下，步數與相對轉向是唯一
 *    能提供的位置線索。
 */
interface MotionSensorGateway {

    /** 這台裝置實際具備的感測能力。**必須實測，不要假設。** */
    val capabilities: SensorCapabilities

    /** 走路狀態。用於相機模式切換。 */
    fun walkingState(): Flow<WalkingState>

    /**
     * 相對方位（度）。
     *
     * 相對於 [resetHeadingReference] 呼叫時的方向，順時針為正，範圍 -180..180。
     *
     * **這是相對值不是羅盤方位。** 沒有磁力計就沒有絕對北方，
     * 而且純陀螺儀積分會隨時間漂移。
     */
    fun relativeHeading(): Flow<Float>

    /** 把目前朝向設為 0 度的基準。每次給新的方向指示時呼叫。 */
    fun resetHeadingReference()

    /** 累積步數。裝置若無計步器則為 null。 */
    fun stepCount(): Flow<Long>?
}

/**
 * 感測器能力。
 *
 * 每一項都應該來自 `SensorManager.getDefaultSensor()` 的實測結果，
 * 而不是規格書 —— 規格書上寫有的東西，Android 層不一定開放。
 */
data class SensorCapabilities(
    val hasAccelerometer: Boolean,
    val hasGyroscope: Boolean,
    /** 有磁力計才有絕對方位（羅盤）。6 軸 IMU 沒有。 */
    val hasMagnetometer: Boolean,
    /** 硬體計步器。有的話比自己從加速度算省電非常多。 */
    val hasStepDetector: Boolean,
    val hasStepCounter: Boolean,
    /** 陀螺儀 + 加速度的融合方位，不需要磁力計，但只有相對值。 */
    val hasGameRotationVector: Boolean,
    /** 含磁力計的融合方位，可提供絕對方位。 */
    val hasRotationVector: Boolean,
) {
    /** 能不能判斷「使用者正在走路」。 */
    val canDetectWalking: Boolean
        get() = hasStepDetector || hasStepCounter || hasAccelerometer

    /** 能不能追蹤相對轉向（「你往右轉了 30 度」）。 */
    val canTrackRelativeHeading: Boolean
        get() = hasGameRotationVector || hasRotationVector || hasGyroscope

    /** 能不能提供絕對方位（「往北走」）。**沒有磁力計就不行。** */
    val canProvideAbsoluteHeading: Boolean
        get() = hasMagnetometer && hasRotationVector

    /** 給使用者聽的能力摘要。 */
    val spokenSummary: String
        get() = buildString {
            if (!hasAccelerometer && !hasGyroscope) {
                append("這台裝置沒有動作感測器")
                return@buildString
            }
            append("動作感測正常。")
            append(if (canDetectWalking) "可以偵測走路。" else "無法偵測走路。")
            append(if (canTrackRelativeHeading) "可以追蹤轉向。" else "無法追蹤轉向。")
            append(if (canProvideAbsoluteHeading) "有電子羅盤。" else "沒有電子羅盤。")
        }

    companion object {
        val NONE = SensorCapabilities(
            hasAccelerometer = false,
            hasGyroscope = false,
            hasMagnetometer = false,
            hasStepDetector = false,
            hasStepCounter = false,
            hasGameRotationVector = false,
            hasRotationVector = false,
        )
    }
}

enum class WalkingState { STILL, WALKING }
