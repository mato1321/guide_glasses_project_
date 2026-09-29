package com.guideglasses.core.domain.face

import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult

/**
 * 把註冊工具上的人同步進眼鏡。
 *
 * 流程：取清單 → 逐張照片抓下來 → 偵測人臉 → **用眼鏡自己的模型**抽特徵
 * → 同一人多張取平均 → 寫入本地加密資料庫。
 *
 * 同步完成之後，「這是誰」就完全離線可用 —— 註冊工具關掉也沒關係。
 */
class SyncPeopleUseCase(
    private val photoSource: PhotoSource,
    private val detector: FaceDetector,
    private val embedder: FaceEmbedder,
    private val repository: PersonRepository,
) {

    /**
     * @param onProgress 每處理完一個人回報一次（已完成數, 總數）。
     *   同步幾十張照片可能要十幾秒，看不見畫面的使用者需要知道還在進行。
     */
    suspend fun execute(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Outcome {
        if (!photoSource.isAvailable) return Outcome.SourceUnavailable
        if (!detector.isAvailable || !embedder.isAvailable) return Outcome.ModelUnavailable

        val people = when (val result = photoSource.listPeople()) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return Outcome.Failed(result.error)
        }

        if (people.isEmpty()) return Outcome.NothingToSync

        val prepared = mutableListOf<PreparedPerson>()
        var skippedPhotos = 0

        for ((index, person) in people.withIndex()) {
            val embeddings = mutableListOf<FaceEmbedding>()

            for (reference in person.photoReferences) {
                when (val embedding = embedFromPhoto(reference)) {
                    null -> skippedPhotos++
                    else -> embeddings += embedding
                }
            }

            if (embeddings.isEmpty()) continue

            prepared += PreparedPerson(
                name = person.name,
                embedding = FaceMatcher.average(embeddings),
                photoCount = embeddings.size,
                coherence = selfCoherence(embeddings),
            )
            onProgress(index + 1, people.size)
        }

        // 一個都沒成功就**不要動資料庫**。網路中斷或模型壞掉時，
        // 保留舊資料遠比清空好 —— 至少使用者還認得出人。
        if (prepared.isEmpty()) {
            return Outcome.Failed(AppError.NoResult("所有照片都無法辨識出人臉"))
        }

        repository.deleteAll()
        for (person in prepared) {
            repository.add(person.name, relation = null, embedding = person.embedding)
                .let { stored ->
                    if (person.photoCount > 1) {
                        repository.update(stored.copy(photoCount = person.photoCount))
                    }
                }
        }

        val coherences = prepared.mapNotNull { it.coherence }
        return Outcome.Completed(
            people = prepared.size,
            photos = prepared.sumOf { it.photoCount },
            skippedPhotos = skippedPhotos,
            coherence = coherences.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
        )
    }

    /** 一張照片 → 特徵。取不到、沒偵測到臉、或有多張臉時回傳 null。 */
    private suspend fun embedFromPhoto(reference: String): FaceEmbedding? {
        val frame = when (val result = photoSource.loadPhoto(reference)) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return null
        }

        val faces = when (val result = detector.detect(frame)) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return null
        }

        // 一張照片裡有兩個人時無法判斷要記哪一個，寧可略過也不要把路人
        // 存成這個名字。註冊照片應該只有本人。
        val face = faces.singleOrNull() ?: return null

        return when (val result = embedder.embed(frame, face)) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> null
        }
    }

    /**
     * 同一個人各張照片之間的平均相似度。
     *
     * **這是整個同步流程最有價值的副產品。** 模型前處理不符時，特徵會變成
     * 接近隨機的向量，同一個人不同照片的相似度會塌到 0 附近 —— 但不會有
     * 任何錯誤訊息。有了這個數字，上層就能把「安靜地全部認不出來」變成
     * 一句聽得見的警告。
     *
     * @return 少於兩張照片時為 null（無法計算）。
     */
    private fun selfCoherence(embeddings: List<FaceEmbedding>): Float? {
        if (embeddings.size < 2) return null
        var total = 0f
        var pairs = 0
        for (i in embeddings.indices) {
            for (j in i + 1 until embeddings.size) {
                total += FaceMatcher.cosineSimilarity(
                    embeddings[i].values,
                    embeddings[j].values,
                )
                pairs++
            }
        }
        return if (pairs == 0) null else total / pairs
    }

    private data class PreparedPerson(
        val name: String,
        val embedding: FaceEmbedding,
        val photoCount: Int,
        val coherence: Float?,
    )

    sealed interface Outcome {

        data class Completed(
            val people: Int,
            val photos: Int,
            val skippedPhotos: Int,
            /** 同一人照片間的平均相似度。null 代表沒有人有兩張以上照片。 */
            val coherence: Float?,
        ) : Outcome {

            /** 相似度過低代表模型或前處理有問題，而不是照片不好。 */
            val modelLooksBroken: Boolean
                get() = coherence != null && coherence < SUSPICIOUS_COHERENCE

            val spoken: String
                get() = buildString {
                    append("同步完成，$people 人，$photos 張照片")
                    if (skippedPhotos > 0) append("，略過 $skippedPhotos 張沒有偵測到人臉的")
                    if (modelLooksBroken) {
                        append("。但同一個人的照片相似度只有 ")
                        append(((coherence ?: 0f) * 100).toInt())
                        append(" %，人臉模型可能不正確")
                    }
                }
        }

        /** 沒設定註冊工具的位址。 */
        data object SourceUnavailable : Outcome

        /** 缺人臉模型檔。 */
        data object ModelUnavailable : Outcome

        /** 註冊工具上還沒有任何人。 */
        data object NothingToSync : Outcome

        data class Failed(val error: AppError) : Outcome
    }

    companion object {
        /**
         * 同一人照片相似度低於此值就視為模型有問題。
         *
         * 正常的人臉模型下，同一個人不同照片的餘弦相似度通常在 0.6 以上。
         * 掉到 0.35 以下幾乎只可能是前處理不符（色彩順序、正規化、張量排列），
         * 而不是照片拍得不好。
         */
        const val SUSPICIOUS_COHERENCE = 0.35f
    }
}
