package com.guideglasses.ai.face

import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.face.DetectedFace
import com.guideglasses.core.domain.face.FaceDetector
import com.guideglasses.core.domain.glasses.CameraFrame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 人臉偵測，用 ML Kit Face Detection 的 bundled 版。
 *
 * 與 `ai-ocr` 一致，選 bundled 而非 play-services 版 ——
 * **Rokid Glasses 是否預裝 Google Play Services 無法確認**。
 *
 * 這一層只負責「畫面裡有幾張臉、在哪裡」，不做身分判斷。
 * 身分是 [TfLiteFaceEmbedder] 加上 domain 層的 `FaceMatcher` 的事。
 */
class MlKitFaceDetector(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : FaceDetector {

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                // FAST 而非 ACCURATE —— 導盲場景要的是即時，
                // 而且後面還有特徵比對這一關，偵測階段不需要極致精度。
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                // 太小的臉多半是遠處路人，辨識也不會準，直接濾掉省算力。
                .setMinFaceSize(MIN_FACE_SIZE_RATIO)
                .build(),
        )
    }

    override val isAvailable: Boolean = true

    override suspend fun detect(frame: CameraFrame): AppResult<List<DetectedFace>> =
        withContext(ioDispatcher) {
            val bitmap = frame.toBitmapOrNull()
                ?: return@withContext AppResult.Failure(
                    AppError.Unknown("無法解析影像，format=${frame.format}"),
                )

            try {
                val faces = awaitDetection(InputImage.fromBitmap(bitmap, 0))
                    ?: return@withContext AppResult.Failure(
                        AppError.Unknown("ML Kit 人臉偵測失敗"),
                    )

                val width = bitmap.width.toFloat()
                val height = bitmap.height.toFloat()

                AppResult.Success(
                    faces.mapNotNull { face ->
                        val box = face.boundingBox
                        if (box.width() <= 0 || box.height() <= 0) return@mapNotNull null

                        // 轉成相對比例，domain 層不需要知道影像多大。
                        DetectedFace(
                            left = (box.left / width).coerceIn(0f, 1f),
                            top = (box.top / height).coerceIn(0f, 1f),
                            width = (box.width() / width).coerceIn(0.001f, 1f),
                            height = (box.height() / height).coerceIn(0.001f, 1f),
                        )
                    },
                )
            } catch (e: Throwable) {
                Log.e(TAG, "人臉偵測失敗", e)
                AppResult.Failure(AppError.Unknown("face detection failed: ${e.message}"))
            } finally {
                bitmap.recycle()
            }
        }

    private suspend fun awaitDetection(
        image: InputImage,
    ): List<com.google.mlkit.vision.face.Face>? = suspendCancellableCoroutine { continuation ->
        detector.process(image)
            .addOnSuccessListener { continuation.resume(it) }
            .addOnFailureListener {
                Log.e(TAG, "ML Kit process 失敗", it)
                continuation.resume(null)
            }
    }

    fun close() {
        runCatching { detector.close() }
            .onFailure { Log.w(TAG, "關閉偵測器失敗", it) }
    }

    private companion object {
        const val TAG = "MlKitFaceDetector"

        /** 臉部至少要佔畫面寬度的這個比例才處理。 */
        const val MIN_FACE_SIZE_RATIO = 0.1f
    }
}
