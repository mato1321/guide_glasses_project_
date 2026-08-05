package com.guideglasses.core.domain.motion

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HeadingGuidanceTest {

    @Test
    fun `角度正規化到正負 180`() {
        assertThat(HeadingGuidance.normalise(0f)).isEqualTo(0f)
        assertThat(HeadingGuidance.normalise(90f)).isEqualTo(90f)
        assertThat(HeadingGuidance.normalise(190f)).isEqualTo(-170f)
        assertThat(HeadingGuidance.normalise(-190f)).isEqualTo(170f)
        assertThat(HeadingGuidance.normalise(360f)).isEqualTo(0f)
        assertThat(HeadingGuidance.normalise(720f)).isEqualTo(0f)
    }

    @Test
    fun `跨越零度時給出最短轉向而不是繞一大圈`() {
        // 從 350 度轉到 10 度：正確答案是右轉 20 度，不是左轉 340 度
        val instruction = HeadingGuidance.instruct(currentHeading = 350f, targetHeading = 370f)

        assertThat(instruction).isInstanceOf(HeadingGuidance.Instruction.TurnRight::class.java)
        assertThat((instruction as HeadingGuidance.Instruction.TurnRight).degrees).isEqualTo(20)
    }

    @Test
    fun `方向正確時說繼續直走`() {
        val instruction = HeadingGuidance.instruct(currentHeading = 0f, targetHeading = 0f)

        assertThat(instruction).isEqualTo(HeadingGuidance.Instruction.OnCourse)
        assertThat(instruction.spoken).contains("直走")
    }

    @Test
    fun `小幅偏移在容許範圍內不會一直唸`() {
        // 步行時頭部自然晃動。沒有容許值的話使用者會被唸到受不了。
        listOf(5f, 10f, 14f, -5f, -14f).forEach { offset ->
            assertThat(HeadingGuidance.instruct(offset, 0f))
                .isEqualTo(HeadingGuidance.Instruction.OnCourse)
        }
    }

    @Test
    fun `超過容許值才給轉向指示`() {
        val instruction = HeadingGuidance.instruct(currentHeading = -30f, targetHeading = 0f)

        assertThat(instruction).isInstanceOf(HeadingGuidance.Instruction.TurnRight::class.java)
    }

    @Test
    fun `左轉右轉方向正確`() {
        // 目標在右邊（順時針）
        assertThat(HeadingGuidance.instruct(0f, 45f))
            .isInstanceOf(HeadingGuidance.Instruction.TurnRight::class.java)
        // 目標在左邊（逆時針）
        assertThat(HeadingGuidance.instruct(0f, -45f))
            .isInstanceOf(HeadingGuidance.Instruction.TurnLeft::class.java)
    }

    @Test
    fun `接近相反方向時說轉身`() {
        val instruction = HeadingGuidance.instruct(currentHeading = 0f, targetHeading = 175f)

        assertThat(instruction).isEqualTo(HeadingGuidance.Instruction.TurnAround)
        assertThat(instruction.spoken).contains("轉身")
    }

    @Test
    fun `角度取整到五度`() {
        // 沒有人能轉出 37 度，而且感測器本身就有誤差
        val instruction = HeadingGuidance.instruct(0f, 37f)

        assertThat((instruction as HeadingGuidance.Instruction.TurnRight).degrees % 5).isEqualTo(0)
    }

    @Test
    fun `取整後不會變成零度`() {
        // 16 度取整到 5 的倍數是 15，不能變成 0 否則指示會變成「轉 0 度」
        val instruction = HeadingGuidance.instruct(0f, 16f)

        assertThat((instruction as HeadingGuidance.Instruction.TurnRight).degrees).isGreaterThan(0)
    }

    @Test
    fun `容許值可自訂`() {
        // 導航精細階段可以收緊
        val strict = HeadingGuidance.instruct(0f, 10f, deadbandDegrees = 5f)

        assertThat(strict).isInstanceOf(HeadingGuidance.Instruction.TurnRight::class.java)
    }

    @Test
    fun `負的容許值會被拒絕`() {
        val error = runCatching {
            HeadingGuidance.instruct(0f, 10f, deadbandDegrees = -1f)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `播報內容可以直接唸出來`() {
        assertThat(HeadingGuidance.Instruction.TurnLeft(30).spoken).isEqualTo("往左轉 30 度")
        assertThat(HeadingGuidance.Instruction.TurnRight(45).spoken).isEqualTo("往右轉 45 度")
    }
}

class StepDistanceEstimatorTest {

    private val estimator = StepDistanceEstimator()

    @Test
    fun `步數轉距離`() {
        assertThat(estimator.metersFor(10)).isWithin(0.01f).of(6.5f)
    }

    @Test
    fun `距離轉步數`() {
        assertThat(estimator.stepsFor(6.5f)).isEqualTo(10)
    }

    @Test
    fun `到達時說到了`() {
        assertThat(estimator.describeRemaining(1f)).isEqualTo("到了")
    }

    @Test
    fun `很近時不報數字`() {
        assertThat(estimator.describeRemaining(4f)).contains("快到了")
    }

    @Test
    fun `距離用步數描述而不是公尺`() {
        // 使用者無法目測 15 公尺，但可以數 20 步
        val description = estimator.describeRemaining(13f)

        assertThat(description).contains("步")
        assertThat(description).doesNotContain("公尺")
    }

    @Test
    fun `步幅可自訂`() {
        val shortStride = StepDistanceEstimator(strideMeters = 0.5f)

        assertThat(shortStride.metersFor(10)).isWithin(0.01f).of(5f)
    }

    @Test
    fun `非法步幅會被拒絕`() {
        assertThat(
            runCatching { StepDistanceEstimator(strideMeters = 0f) }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
    }
}

class CameraModeControllerTest {

    private val controller = CameraModeController()

    @Test
    fun `走路時進入行進模式`() {
        val mode = controller.decide(WalkingState.WALKING)

        assertThat(mode).isEqualTo(CameraModeController.Mode.WALKING)
        assertThat(mode.isCameraActive).isTrue()
    }

    @Test
    fun `停下來時關閉相機`() {
        // 站在原地講電話的十分鐘裡，2fps 偵測沒有價值卻照樣吃電
        val mode = controller.decide(WalkingState.STILL)

        assertThat(mode).isEqualTo(CameraModeController.Mode.STANDBY)
        assertThat(mode.isCameraActive).isFalse()
        assertThat(mode.toCaptureRequest()).isNull()
    }

    @Test
    fun `低電量時降到省電模式`() {
        val mode = controller.decide(WalkingState.WALKING, batteryPercent = 15)

        assertThat(mode).isEqualTo(CameraModeController.Mode.POWER_SAVING)
        assertThat(mode.fps).isLessThan(CameraModeController.Mode.WALKING.fps)
    }

    @Test
    fun `使用者主動要求時即使靜止也持續偵測`() {
        // 他可能正要過馬路
        val mode = controller.decide(WalkingState.STILL, userRequestedContinuous = true)

        assertThat(mode).isEqualTo(CameraModeController.Mode.WALKING)
    }

    @Test
    fun `低電量時使用者要求仍會降頻但不關閉`() {
        val mode = controller.decide(
            WalkingState.STILL,
            batteryPercent = 10,
            userRequestedContinuous = true,
        )

        assertThat(mode).isEqualTo(CameraModeController.Mode.POWER_SAVING)
        assertThat(mode.isCameraActive).isTrue()
    }

    @Test
    fun `無法偵測走路狀態時退回待命`() {
        val mode = controller.decide(walkingState = null)

        assertThat(mode).isEqualTo(CameraModeController.Mode.STANDBY)
    }

    @Test
    fun `行進模式用 RGBA 避免多一次編解碼`() {
        val request = CameraModeController.Mode.WALKING.toCaptureRequest()!!

        assertThat(request.outputFormat.name).isEqualTo("RGBA_8888")
        assertThat(request.longEdgePixels).isEqualTo(640)
    }
}

class WalkingStateDebouncerTest {

    @Test
    fun `初始狀態是靜止`() {
        assertThat(WalkingStateDebouncer().currentState).isEqualTo(WalkingState.STILL)
    }

    @Test
    fun `走幾步之後才切換到走路`() {
        val debouncer = WalkingStateDebouncer(startDelayMillis = 1_500L)

        debouncer.onStep(0L)
        assertThat(debouncer.currentState).isEqualTo(WalkingState.STILL)

        debouncer.onStep(500L)
        assertThat(debouncer.currentState).isEqualTo(WalkingState.STILL)

        debouncer.onStep(1_600L)
        assertThat(debouncer.currentState).isEqualTo(WalkingState.WALKING)
    }

    @Test
    fun `零星的一兩步不會誤判成走路`() {
        // 等紅綠燈時挪一下腳
        val debouncer = WalkingStateDebouncer()

        debouncer.onStep(0L)
        debouncer.onStep(300L)
        debouncer.onTick(5_000L)

        assertThat(debouncer.currentState).isEqualTo(WalkingState.STILL)
    }

    @Test
    fun `停下來一段時間才切回靜止`() {
        val debouncer = WalkingStateDebouncer(startDelayMillis = 0L, stopDelayMillis = 2_000L)

        debouncer.onStep(0L)
        assertThat(debouncer.currentState).isEqualTo(WalkingState.WALKING)

        debouncer.onTick(1_000L)
        assertThat(debouncer.currentState).isEqualTo(WalkingState.WALKING)

        debouncer.onTick(2_500L)
        assertThat(debouncer.currentState).isEqualTo(WalkingState.STILL)
    }

    @Test
    fun `短暫停頓不會切回靜止`() {
        // 等紅燈的三秒停頓不該讓相機反覆啟停 —— 那比一直開著更耗電
        val debouncer = WalkingStateDebouncer(startDelayMillis = 0L, stopDelayMillis = 2_000L)

        debouncer.onStep(0L)
        debouncer.onTick(1_500L)
        debouncer.onStep(1_800L)
        debouncer.onTick(3_000L)

        assertThat(debouncer.currentState).isEqualTo(WalkingState.WALKING)
    }

    @Test
    fun `reset 回到初始狀態`() {
        val debouncer = WalkingStateDebouncer(startDelayMillis = 0L)
        debouncer.onStep(0L)

        debouncer.reset()

        assertThat(debouncer.currentState).isEqualTo(WalkingState.STILL)
    }
}

class SensorCapabilitiesTest {

    private fun caps(
        accelerometer: Boolean = false,
        gyroscope: Boolean = false,
        magnetometer: Boolean = false,
        stepDetector: Boolean = false,
        stepCounter: Boolean = false,
        gameRotation: Boolean = false,
        rotation: Boolean = false,
    ) = SensorCapabilities(
        hasAccelerometer = accelerometer,
        hasGyroscope = gyroscope,
        hasMagnetometer = magnetometer,
        hasStepDetector = stepDetector,
        hasStepCounter = stepCounter,
        hasGameRotationVector = gameRotation,
        hasRotationVector = rotation,
    )

    @Test
    fun `六軸 IMU 沒有絕對方位`() {
        // Rokid Glasses 若用的是 ICM-4x6xx（6 軸），就是這個情形
        val sixAxis = caps(accelerometer = true, gyroscope = true, gameRotation = true)

        assertThat(sixAxis.canTrackRelativeHeading).isTrue()
        assertThat(sixAxis.canProvideAbsoluteHeading).isFalse()
    }

    @Test
    fun `九軸 IMU 才有電子羅盤`() {
        val nineAxis = caps(
            accelerometer = true, gyroscope = true, magnetometer = true, rotation = true,
        )

        assertThat(nineAxis.canProvideAbsoluteHeading).isTrue()
    }

    @Test
    fun `有加速度計就能推測走路`() {
        assertThat(caps(accelerometer = true).canDetectWalking).isTrue()
    }

    @Test
    fun `硬體計步器也算能偵測走路`() {
        assertThat(caps(stepDetector = true).canDetectWalking).isTrue()
    }

    @Test
    fun `什麼都沒有時能力全為假`() {
        assertThat(SensorCapabilities.NONE.canDetectWalking).isFalse()
        assertThat(SensorCapabilities.NONE.canTrackRelativeHeading).isFalse()
        assertThat(SensorCapabilities.NONE.canProvideAbsoluteHeading).isFalse()
    }

    @Test
    fun `沒有感測器時的播報明確告知`() {
        assertThat(SensorCapabilities.NONE.spokenSummary).contains("沒有動作感測器")
    }

    @Test
    fun `播報摘要涵蓋三項能力`() {
        val summary = caps(
            accelerometer = true, gyroscope = true, gameRotation = true, stepDetector = true,
        ).spokenSummary

        assertThat(summary).contains("可以偵測走路")
        assertThat(summary).contains("可以追蹤轉向")
        assertThat(summary).contains("沒有電子羅盤")
    }
}
