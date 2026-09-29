package com.guideglasses

import android.app.Application
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import dagger.hilt.android.HiltAndroidApp

/**
 * @param CameraXConfig.Provider 讓 CameraX 只驗證後鏡頭。
 *
 * ## 為什麼需要這個
 *
 * Rokid Glasses **只有一顆後鏡頭**（`dumpsys media.camera` → `Number of camera
 * devices: 1, Facing: Back`），但系統**錯誤地宣告了前鏡頭特徵**：
 *
 * ```
 * adb shell pm list features | grep camera
 * feature:android.hardware.camera.front      ← 實際上沒有
 * ```
 *
 * CameraX 的 `CameraValidator` 會照著特徵旗標去驗證前鏡頭，然後失敗：
 *
 * ```
 * W CameraValidator: Camera LENS_FACING_FRONT verification failed
 * W CameraValidator: java.lang.IllegalArgumentException: No available camera can be found
 * W CameraX: CameraIdListIncorrectException: Expected camera missing from device.
 * ```
 *
 * 結果是 CameraX 初始化失敗 → **OCR、人臉、障礙物全部拿不到影像**。
 *
 * `setAvailableCamerasLimiter` 告訴 CameraX 只需要後鏡頭，跳過那個不存在的
 * 前鏡頭驗證。這是 Google 官方針對「裝置相機清單與宣告不符」的解法。
 *
 * > 這是這台眼鏡第三個「API 宣告有、實際沒有」的陷阱
 * > （另外兩個是 GPS 與 TTS，見 `docs/DEVICE_FINDINGS.md`）。
 */
@HiltAndroidApp
class GuideGlassesApplication : Application(), CameraXConfig.Provider {

    override fun getCameraXConfig(): CameraXConfig =
        CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)
            .build()
}
