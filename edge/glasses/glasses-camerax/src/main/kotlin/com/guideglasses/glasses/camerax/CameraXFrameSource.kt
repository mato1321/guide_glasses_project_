package com.guideglasses.glasses.camerax

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.glasses.CameraFrame
import com.guideglasses.core.domain.glasses.CaptureRequest
import com.guideglasses.core.domain.glasses.FrameRateLimiter
import com.guideglasses.core.domain.glasses.FrameSource
import com.guideglasses.core.domain.glasses.GlassesCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * 用標準 Android CameraX 取得眼鏡的相機影像。
 *
 * Rokid Glasses 執行 YodaOS-Sprite（Android 12 / API 32），APK 直接安裝執行，
 * 因此 CameraX 就是完整的連續影像串流 —— 不需要 CXR SDK、不需要 Root、
 * 不需要任何 hack。這條路徑已被 `Face_Recognition/` 在實機上驗證過。
 *
 * 同一個實作在手機上也能跑，開發時眼鏡不在手邊照樣能做事。
 */
class CameraXFrameSource(
    context: Context,
    private val cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
) : FrameSource {

    private val appContext = context.applicationContext

    override fun frames(request: CaptureRequest): Flow<AppResult<CameraFrame>> = callbackFlow {
        if (!hasCameraPermission()) {
            trySend(AppResult.Failure(AppError.PermissionDenied(Manifest.permission.CAMERA)))
            close()
            return@callbackFlow
        }

        val provider = runCatching { awaitCameraProvider() }.getOrElse { error ->
            trySend(
                AppResult.Failure(
                    AppError.CapabilityUnavailable("camera", error.message ?: "無法取得相機"),
                ),
            )
            close()
            return@callbackFlow
        }

        // CameraX 需要一個 LifecycleOwner 才能綁定。這個 FrameSource 不屬於任何
        // 畫面 —— 它的生命週期就是這條 Flow 的生命週期，所以自己管一個。
        val owner = FlowLifecycleOwner()
        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        val limiter = FrameRateLimiter(request.targetFps)

        val analysis = buildAnalysis(request).apply {
            setAnalyzer(executor) { proxy ->
                handleFrame(proxy, request, limiter) { frame -> trySend(frame) }
            }
        }

        val bindResult = runCatching {
            // bindToLifecycle 必須在主執行緒
            withMainThread {
                provider.unbindAll()
                provider.bindToLifecycle(owner, cameraSelector, analysis)
                owner.start()
            }
        }

        if (bindResult.isFailure) {
            val error = bindResult.exceptionOrNull()
            Log.e(TAG, "相機綁定失敗", error)
            trySend(
                AppResult.Failure(
                    AppError.CapabilityUnavailable("camera", error?.message ?: "相機綁定失敗"),
                ),
            )
            executor.shutdown()
            close()
            return@callbackFlow
        }

        awaitClose {
            runCatching {
                withMainThread {
                    analysis.clearAnalyzer()
                    provider.unbind(analysis)
                    owner.stop()
                }
            }.onFailure { Log.w(TAG, "釋放相機失敗", it) }
            executor.shutdown()
        }
    }

    /**
     * 取得單張影像。
     *
     * 直接沿用 [frames] 取第一張，而不是另外綁一個 `ImageCapture` —— 少一條
     * 需要各自維護的程式路徑，而且 `ImageAnalysis` 的第一幀通常比
     * `ImageCapture` 的完整拍照流程更快。
     */
    override suspend fun captureOnce(request: CaptureRequest): AppResult<CameraFrame> =
        frames(request.copy(targetFps = SINGLE_SHOT_FPS)).first()

    /** 這個實作實際具備的能力。上層據此決定要跑行進模式還是查詢模式。 */
    val capabilities: GlassesCapabilities
        get() = GlassesCapabilities(
            frameMode = if (hasCameraPermission()) {
                GlassesCapabilities.FrameMode.STREAM
            } else {
                GlassesCapabilities.FrameMode.NONE
            },
            // CameraX 本身可到 30fps，但眼鏡只有 2GB RAM 與 210mAh。
            // 這裡回報的是「建議上限」而不是「硬體上限」—— 上層不該把它拉滿。
            maxFramesPerSecond = RECOMMENDED_MAX_FPS,
            canSpeakOnGlasses = true,
            canDisplayText = false,
            canCaptureAudio = true,
        )

    private fun buildAnalysis(request: CaptureRequest): ImageAnalysis =
        ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(request.longEdgePixels, request.longEdgePixels),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            // 處理不及時就丟掉舊的。導盲場景中，三秒前的畫面沒有價值。
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

    private inline fun handleFrame(
        proxy: ImageProxy,
        request: CaptureRequest,
        limiter: FrameRateLimiter,
        emit: (AppResult<CameraFrame>) -> Unit,
    ) {
        try {
            val now = SystemClock.elapsedRealtime()
            // 節流放在轉檔之前 —— 轉檔才是花時間的部分，被丟棄的幀不該付這個成本。
            if (!limiter.shouldAccept(now)) return

            val frame = ImageProxyConverter.convert(proxy, request, now)
            emit(AppResult.Success(frame))
        } catch (e: Throwable) {
            Log.e(TAG, "影像轉換失敗", e)
            emit(AppResult.Failure(AppError.Unknown("frame conversion failed: ${e.message}")))
        } finally {
            // 一定要 close，否則相機會停止送新的幀。
            proxy.close()
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private suspend fun awaitCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(appContext)
            future.addListener(
                {
                    runCatching { future.get() }
                        .onSuccess { continuation.resume(it) }
                        .onFailure { continuation.cancel(it) }
                },
                ContextCompat.getMainExecutor(appContext),
            )
        }

    private fun withMainThread(block: () -> Unit) {
        val mainExecutor = ContextCompat.getMainExecutor(appContext)
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            val latch = java.util.concurrent.CountDownLatch(1)
            var thrown: Throwable? = null
            mainExecutor.execute {
                runCatching(block).onFailure { thrown = it }
                latch.countDown()
            }
            latch.await()
            thrown?.let { throw it }
        }
    }

    /**
     * 只為這條 Flow 存在的 LifecycleOwner。
     *
     * Flow 開始時進入 RESUMED，Flow 收掉時進入 DESTROYED，CameraX 會跟著
     * 自動解除綁定。這樣相機的生命週期就等於「有沒有人在收影像」，
     * 不會發生「畫面關了相機還開著」這種耗電問題。
     */
    private class FlowLifecycleOwner : LifecycleOwner {

        private val registry = LifecycleRegistry(this)

        override val lifecycle: Lifecycle get() = registry

        fun start() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private companion object {
        const val TAG = "CameraXFrameSource"

        /** 單張擷取時不需要節流，設一個高值讓第一幀立刻通過。 */
        const val SINGLE_SHOT_FPS = 60f

        /**
         * 建議的幀率上限。
         *
         * 不是硬體上限（CameraX 可到 30fps），而是考量 2GB RAM 與 210mAh
         * 之後的建議值。步行 1.4 m/s 之下，5fps 等於每 28 公分判斷一次。
         */
        const val RECOMMENDED_MAX_FPS = 5f
    }
}
