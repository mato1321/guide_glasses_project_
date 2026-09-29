package com.guideglasses.core.domain.motion

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 把「目前朝向」與「該朝的方向」的差距，翻成可以聽懂的轉向指示。
 *
 * 沒有 GPS 的情況下，這是導航能給的最實用資訊之一：
 * 系統說「往右轉 30 度」，使用者轉完之後系統確認「方向對了」。
 */
object HeadingGuidance {

    /**
     * 把任意角度正規化到 -180..180。
     *
     * 沒有這一步，「從 350 度轉到 10 度」會算成「左轉 340 度」而不是
     * 「右轉 20 度」—— 對使用者是完全錯誤的指示。
     */
    fun normalise(degrees: Float): Float {
        var value = degrees % 360f
        if (value > 180f) value -= 360f
        if (value < -180f) value += 360f
        // -180 與 180 等價，統一成 180 避免測試與播報不一致
        return if (value == -180f) 180f else value
    }

    /**
     * @param currentHeading 目前朝向（相對基準，度）
     * @param targetHeading 應該朝的方向（相對基準，度）
     * @param deadbandDegrees 容許誤差。小於這個角度就算對準了 ——
     *   不設容許值的話，使用者頭稍微動一下就會被唸一次，非常煩人。
     */
    fun instruct(
        currentHeading: Float,
        targetHeading: Float,
        deadbandDegrees: Float = DEFAULT_DEADBAND_DEGREES,
    ): Instruction {
        require(deadbandDegrees >= 0f) { "容許誤差不可為負" }

        val delta = normalise(targetHeading - currentHeading)
        if (abs(delta) <= deadbandDegrees) return Instruction.OnCourse

        // 接近 180 度時「左轉」與「右轉」都對，但「請轉身」更好懂。
        if (abs(delta) >= TURN_AROUND_THRESHOLD_DEGREES) return Instruction.TurnAround

        return if (delta > 0f) {
            Instruction.TurnRight(delta.roundToDegrees())
        } else {
            Instruction.TurnLeft((-delta).roundToDegrees())
        }
    }

    /**
     * 角度取整到 5 度。
     *
     * 報「往右轉 37 度」是假精確 —— 沒有人能轉出 37 度，而且感測器本身
     * 就有誤差。取整到 5 度既誠實又好唸。
     */
    private fun Float.roundToDegrees(): Int =
        ((this / DEGREE_ROUNDING).roundToInt() * DEGREE_ROUNDING.toInt())
            .coerceAtLeast(DEGREE_ROUNDING.toInt())

    sealed interface Instruction {
        val spoken: String

        data object OnCourse : Instruction {
            override val spoken: String get() = "方向對了，繼續直走"
        }

        data class TurnLeft(val degrees: Int) : Instruction {
            override val spoken: String get() = "往左轉 $degrees 度"
        }

        data class TurnRight(val degrees: Int) : Instruction {
            override val spoken: String get() = "往右轉 $degrees 度"
        }

        data object TurnAround : Instruction {
            override val spoken: String get() = "請轉身，方向相反了"
        }
    }

    /** 容許誤差。步行時頭部自然晃動大約在這個範圍內。 */
    const val DEFAULT_DEADBAND_DEGREES = 15f

    /** 超過這個角度就說「轉身」而不是報左右。 */
    const val TURN_AROUND_THRESHOLD_DEGREES = 150f

    private const val DEGREE_ROUNDING = 5f
}

/**
 * 用步數估計走了多遠。
 *
 * 沒有 GPS 時這是唯一的距離線索。誤差不小（步幅因人而異、上下樓梯更不準），
 * 但「再走大約 20 步」對使用者仍然比「再走 15 公尺」好用 ——
 * 步數是他可以自己數的。
 */
class StepDistanceEstimator(
    /** 平均步幅（公尺）。成人約 0.7 公尺，視障者使用導盲杖時通常略短。 */
    private val strideMeters: Float = DEFAULT_STRIDE_METERS,
) {
    init {
        require(strideMeters > 0f) { "步幅必須為正" }
    }

    fun metersFor(steps: Long): Float = steps * strideMeters

    fun stepsFor(meters: Float): Int =
        (meters / strideMeters).roundToInt().coerceAtLeast(0)

    /**
     * 剩餘距離的口語描述。
     *
     * 刻意用步數而不是公尺 —— 使用者無法目測 15 公尺，但可以數 20 步。
     */
    fun describeRemaining(meters: Float): String = when {
        meters <= ARRIVED_METERS -> "到了"
        meters <= CLOSE_METERS -> "快到了，再走幾步"
        else -> "再走大約 ${stepsFor(meters)} 步"
    }

    companion object {
        const val DEFAULT_STRIDE_METERS = 0.65f
        const val ARRIVED_METERS = 2f
        const val CLOSE_METERS = 5f
    }
}
