package com.guideglasses.core.domain.face

import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.glasses.CameraFrame
import com.guideglasses.core.domain.glasses.CaptureRequest
import com.guideglasses.core.domain.glasses.FrameSource

/** 人臉偵測。回傳畫面中所有臉的相對座標。 */
interface FaceDetector {
    val isAvailable: Boolean
    suspend fun detect(frame: CameraFrame): AppResult<List<DetectedFace>>
}

/** 人臉特徵抽取。輸入一張臉的區域，輸出特徵向量。 */
interface FaceEmbedder {
    val isAvailable: Boolean
    val dimension: Int
    suspend fun embed(frame: CameraFrame, face: DetectedFace): AppResult<FaceEmbedding>
}

/** 已註冊人物的儲存。**實作必須把資料留在裝置上。** */
interface PersonRepository {
    suspend fun all(): List<KnownPerson>
    suspend fun add(name: String, relation: String?, embedding: FaceEmbedding): KnownPerson
    suspend fun update(person: KnownPerson)
    suspend fun deleteById(id: Long)
    suspend fun deleteAll()
    suspend fun count(): Int
}

/**
 * 辨識前方的人是誰。
 *
 * 完整流程：拍照 → 偵測 → 抽特徵 → 比對 → 產生含方位與距離的描述。
 *
 * **全程在裝置上完成，影像與特徵都不離開裝置。** 舊做法是每 3-5 秒把
 * 整張畫面上傳到伺服器，那等於把使用者一整天遇到的所有路人的臉都送上網。
 */
class IdentifyPersonUseCase(
    private val frameSource: FrameSource,
    private val detector: FaceDetector,
    private val identification: FaceIdentificationStrategy,
    private val distanceEstimator: FaceDistanceEstimator = FaceDistanceEstimator(),
) {

    suspend fun execute(): Outcome {
        if (!detector.isAvailable) {
            return Outcome.Failed(
                AppError.CapabilityUnavailable("face-detector", "人臉偵測不可用"),
            )
        }
        if (!identification.isAvailable) {
            return Outcome.Failed(
                AppError.CapabilityUnavailable(
                    "face-identification",
                    "人臉辨識不可用：端側缺模型檔，且未設定遠端後端",
                ),
            )
        }

        val frame = when (val result = frameSource.captureOnce(CAPTURE_REQUEST)) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return Outcome.Failed(result.error)
        }

        val faces = when (val result = detector.detect(frame)) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return Outcome.Failed(result.error)
        }

        if (faces.isEmpty()) return Outcome.NoFaceDetected

        // 只處理最大的那張臉 —— 面積最大通常代表最靠近，
        // 那也是使用者正在互動的對象。把畫面中每個人都唸出來只會很吵。
        val face = faces.maxByOrNull { it.area } ?: return Outcome.NoFaceDetected

        val match = when (val result = identification.identify(frame, face)) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return Outcome.Failed(result.error)
        }

        return Outcome.Identified(
            match = match,
            bearing = BearingResolver.resolve(face.centerX),
            distanceDescription = distanceEstimator.describe(face.width),
            source = identification.name,
        )
    }

    sealed interface Outcome {

        data class Identified(
            val match: FaceMatch,
            val bearing: BearingResolver.Bearing,
            val distanceDescription: String?,
            /** 哪一條路徑判斷出來的（端側／遠端），供觀測與除錯。 */
            val source: String,
        ) : Outcome {

            /**
             * 播報內容。
             *
             * 組合順序是「方位 → 距離 → 是誰」，因為方位最急著知道 ——
             * 使用者要先轉向，才有辦法互動。
             */
            val spoken: String
                get() {
                    val where = buildString {
                        append(bearing.spoken)
                        distanceDescription?.let { append("，$it") }
                    }
                    return when (match) {
                        is FaceMatch.Confident -> "$where，是${match.person.spokenName}"
                        is FaceMatch.Tentative -> "$where，可能是${match.person.spokenName}，不太確定"
                        is FaceMatch.Unknown -> "${where}有一個人，我不認識"
                    }
                }

            /**
             * 去抖動用的鍵。
             *
             * 同一個人連續被辨識到十次只該播報一次。認出誰就用誰當鍵，
             * 不認識的人共用一個鍵 —— 不然每個路過的陌生人都會觸發一次播報。
             */
            val dedupeKey: String
                get() = when (match) {
                    is FaceMatch.Confident -> "face:${match.person.id}"
                    is FaceMatch.Tentative -> "face:${match.person.id}"
                    is FaceMatch.Unknown -> "face:unknown"
                }
        }

        data object NoFaceDetected : Outcome

        data class Failed(val error: AppError) : Outcome
    }

    companion object {
        /**
         * 人臉辨識的擷取參數。
         *
         * 960 介於障礙物（640）與 OCR（1280）之間。臉比文字大、比車輛小，
         * 而且特徵模型的輸入通常只有 112×112，過高的解析度只是浪費。
         */
        val CAPTURE_REQUEST = CaptureRequest(
            targetFps = 1f,
            longEdgePixels = 960,
            outputFormat = CameraFrame.Format.JPEG,
            jpegQuality = 85,
        )
    }
}

/**
 * 把眼前的人記起來。
 *
 * **必須在當事人在場且同意的情況下使用。** 人臉是生物特徵，未經同意
 * 建檔在臺灣涉及《個人資料保護法》。UI 層有責任先取得口頭同意。
 */
class RegisterFaceUseCase(
    private val frameSource: FrameSource,
    private val detector: FaceDetector,
    private val embedder: FaceEmbedder,
    private val repository: PersonRepository,
) {

    suspend fun execute(name: String, relation: String? = null): Outcome {
        if (name.isBlank()) return Outcome.InvalidName

        if (!detector.isAvailable || !embedder.isAvailable) {
            return Outcome.Failed(
                AppError.CapabilityUnavailable("face", "人臉功能不可用"),
            )
        }

        val frame = when (val result = frameSource.captureOnce(
            IdentifyPersonUseCase.CAPTURE_REQUEST,
        )) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return Outcome.Failed(result.error)
        }

        val faces = when (val result = detector.detect(frame)) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return Outcome.Failed(result.error)
        }

        return when {
            faces.isEmpty() -> Outcome.NoFaceDetected

            // 註冊時有多張臉是危險的 —— 可能把旁邊的路人存成這個名字。
            // 寧可要求使用者確認只有一個人在鏡頭前。
            faces.size > 1 -> Outcome.MultipleFaces(faces.size)

            else -> {
                val embedding = when (val result = embedder.embed(frame, faces.first())) {
                    is AppResult.Success -> result.data
                    is AppResult.Failure -> return Outcome.Failed(result.error)
                }
                Outcome.Registered(repository.add(name.trim(), relation?.trim(), embedding))
            }
        }
    }

    sealed interface Outcome {
        data class Registered(val person: KnownPerson) : Outcome {
            val spoken: String get() = "已經記住${person.spokenName}了"
        }

        data object NoFaceDetected : Outcome
        data class MultipleFaces(val count: Int) : Outcome
        data object InvalidName : Outcome
        data class Failed(val error: AppError) : Outcome
    }
}
