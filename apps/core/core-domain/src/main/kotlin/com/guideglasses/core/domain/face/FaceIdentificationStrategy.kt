package com.guideglasses.core.domain.face

import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.glasses.CameraFrame

/**
 * 「這張臉是誰」的判斷策略。
 *
 * 抽成介面是為了讓兩條路並存：
 *
 * - **端側**（[OnDeviceFaceIdentification]）—— 隱私、離線、低延遲，但需要
 *   `.tflite` 模型檔。
 * - **遠端**（`RemoteFaceIdentification`，在 ai-face 模組）—— 沿用團隊既有的
 *   InsightFace 後端，**今天就能用**。
 *
 * 兩者都用同一套人臉偵測（ML Kit，端側）先找到臉再處理。這一點很重要：
 * 沒偵測到臉就完全不上傳，而且上傳的是裁切後的臉（幾 KB）而不是整張畫面。
 */
interface FaceIdentificationStrategy {

    val isAvailable: Boolean

    /** 給 log 與觀測用的名稱。 */
    val name: String

    suspend fun identify(frame: CameraFrame, face: DetectedFace): AppResult<FaceMatch>
}

/**
 * 端側判斷：抽特徵 → 與本地資料庫比對。
 *
 * 特徵向量永遠不離開裝置。
 */
class OnDeviceFaceIdentification(
    private val embedder: FaceEmbedder,
    private val repository: PersonRepository,
    private val matcher: FaceMatcher = FaceMatcher(),
) : FaceIdentificationStrategy {

    override val isAvailable: Boolean get() = embedder.isAvailable

    override val name: String = "on-device"

    override suspend fun identify(
        frame: CameraFrame,
        face: DetectedFace,
    ): AppResult<FaceMatch> = when (val result = embedder.embed(frame, face)) {
        is AppResult.Success -> AppResult.Success(
            matcher.match(result.data, repository.all()),
        )

        is AppResult.Failure -> result
    }
}

/**
 * 依序嘗試多個策略，第一個可用的勝出。
 *
 * 順序即優先級。建議把端側排前面 —— 它比較快也比較保護隱私；
 * 遠端當作「模型檔還沒放進去」時的實際可用路徑。
 */
class CompositeFaceIdentification(
    private val strategies: List<FaceIdentificationStrategy>,
) : FaceIdentificationStrategy {

    override val isAvailable: Boolean get() = strategies.any { it.isAvailable }

    override val name: String
        get() = strategies.firstOrNull { it.isAvailable }?.name ?: "none"

    override suspend fun identify(
        frame: CameraFrame,
        face: DetectedFace,
    ): AppResult<FaceMatch> {
        var lastFailure: AppResult.Failure? = null

        for (strategy in strategies) {
            if (!strategy.isAvailable) continue
            when (val result = strategy.identify(frame, face)) {
                is AppResult.Success -> return result
                // 這個策略當下失敗（例如遠端斷線），換下一個試試。
                is AppResult.Failure -> lastFailure = result
            }
        }

        return lastFailure ?: AppResult.Failure(
            com.guideglasses.core.domain.AppError.CapabilityUnavailable(
                "face-identification",
                "沒有可用的人臉辨識方式",
            ),
        )
    }
}
