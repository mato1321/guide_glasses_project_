package com.guideglasses.glasses.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.guideglasses.core.domain.motion.MotionSensorGateway
import com.guideglasses.core.domain.motion.SensorCapabilities
import com.guideglasses.core.domain.motion.WalkingState
import com.guideglasses.core.domain.motion.WalkingStateDebouncer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 以 Android `SensorManager` 實作的動作感測。
 *
 * **能力一律以實測為準。** 規格書上寫有的感測器，Android 層不一定開放；
 * 而且 Rokid Glasses 的 IMU 是 6 軸還是 9 軸，官方資料與社群資料說法不一。
 * 因此 [capabilities] 直接問 `SensorManager`，並提供語音自我檢測讓
 * 使用者在實機上確認。
 */
class AndroidMotionSensorGateway(
    context: Context,
) : MotionSensorGateway {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** 方位基準。呼叫 [resetHeadingReference] 時記下當下的角度。 */
    @Volatile
    private var headingReferenceDegrees: Float? = null

    override val capabilities: SensorCapabilities by lazy {
        val manager = sensorManager ?: return@lazy SensorCapabilities.NONE
        SensorCapabilities(
            hasAccelerometer = manager.has(Sensor.TYPE_ACCELEROMETER),
            hasGyroscope = manager.has(Sensor.TYPE_GYROSCOPE),
            hasMagnetometer = manager.has(Sensor.TYPE_MAGNETIC_FIELD),
            hasStepDetector = manager.has(Sensor.TYPE_STEP_DETECTOR),
            hasStepCounter = manager.has(Sensor.TYPE_STEP_COUNTER),
            hasGameRotationVector = manager.has(Sensor.TYPE_GAME_ROTATION_VECTOR),
            hasRotationVector = manager.has(Sensor.TYPE_ROTATION_VECTOR),
        )
    }

    override fun walkingState(): Flow<WalkingState> = callbackFlow {
        val manager = sensorManager
        if (manager == null || !capabilities.canDetectWalking) {
            close()
            return@callbackFlow
        }

        val debouncer = WalkingStateDebouncer()
        trySend(debouncer.currentState)

        // 優先用硬體計步器 —— 它跑在感測器中樞上，比讓 CPU 一直讀加速度
        // 省電非常多，對 210mAh 的電池是關鍵差異。
        val stepSensor = manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val listener = object : SensorEventListener {
            private var lastPeakAt = 0L

            override fun onSensorChanged(event: SensorEvent) {
                val now = SystemClock.elapsedRealtime()

                when (event.sensor.type) {
                    Sensor.TYPE_STEP_DETECTOR -> debouncer.onStep(now)

                    Sensor.TYPE_ACCELEROMETER -> {
                        // 沒有硬體計步器時的退路：偵測加速度總量的尖峰。
                        // 準確度遠不如硬體計步器，但足以判斷「有沒有在動」。
                        val magnitude = sqrt(
                            event.values[0] * event.values[0] +
                                event.values[1] * event.values[1] +
                                event.values[2] * event.values[2],
                        )
                        val deviation = abs(magnitude - SensorManager.GRAVITY_EARTH)
                        if (deviation > STEP_ACCELERATION_THRESHOLD &&
                            now - lastPeakAt > MIN_STEP_INTERVAL_MILLIS
                        ) {
                            lastPeakAt = now
                            debouncer.onStep(now)
                        }
                    }
                }

                debouncer.onTick(now)
                trySend(debouncer.currentState)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val registered = when {
            stepSensor != null ->
                manager.registerListener(listener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)

            accelerometer != null ->
                manager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)

            else -> false
        }

        if (!registered) {
            Log.w(TAG, "無法註冊動作感測器")
            close()
            return@callbackFlow
        }

        awaitClose { manager.unregisterListener(listener) }
    }.distinctUntilChanged()

    override fun relativeHeading(): Flow<Float> = callbackFlow {
        val manager = sensorManager
        if (manager == null || !capabilities.canTrackRelativeHeading) {
            close()
            return@callbackFlow
        }

        // GAME_ROTATION_VECTOR 不使用磁力計，所以在 6 軸 IMU 上也能用。
        // 代價是它只有相對方位，而且會慢慢漂移 —— 這正是為什麼
        // resetHeadingReference() 必須在每次給新指示時呼叫。
        val sensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: run {
                close()
                return@callbackFlow
            }

        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)

                val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                val reference = headingReferenceDegrees ?: azimuth.also {
                    headingReferenceDegrees = it
                }
                trySend(normalise(azimuth - reference))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        if (!manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)) {
            Log.w(TAG, "無法註冊方位感測器")
            close()
            return@callbackFlow
        }

        awaitClose { manager.unregisterListener(listener) }
    }

    override fun resetHeadingReference() {
        headingReferenceDegrees = null
    }

    override fun stepCount(): Flow<Long>? {
        val manager = sensorManager ?: return null
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null

        return callbackFlow {
            // TYPE_STEP_COUNTER 回傳的是開機以來的累計值，所以要記下起點。
            var baseline: Long? = null

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val total = event.values.firstOrNull()?.toLong() ?: return
                    val start = baseline ?: total.also { baseline = it }
                    trySend((total - start).coerceAtLeast(0L))
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }

            if (!manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) {
                close()
                return@callbackFlow
            }

            awaitClose { manager.unregisterListener(listener) }
        }
    }

    private fun SensorManager.has(type: Int): Boolean = getDefaultSensor(type) != null

    private fun normalise(degrees: Float): Float {
        var value = degrees % 360f
        if (value > 180f) value -= 360f
        if (value < -180f) value += 360f
        return value
    }

    private companion object {
        const val TAG = "MotionSensors"

        /** 加速度偏離重力多少視為一步。純經驗值，只用於沒有硬體計步器時。 */
        const val STEP_ACCELERATION_THRESHOLD = 2.5f

        /** 兩步之間的最短間隔。人類步頻上限大約每秒三步。 */
        const val MIN_STEP_INTERVAL_MILLIS = 300L
    }
}
