package com.guideglasses.core.domain.face

import kotlin.math.sqrt

/**
 * 特徵比對。
 *
 * 舊後端 `FaceEngine.recognize()` 是單一閾值 0.4 —— 過了就說是誰，
 * 沒過就說「未知」。實務上這會造成兩種都很糟的結果：
 * 相似度 0.41 時它信誓旦旦地喊錯名字，0.39 時它完全不提。
 *
 * 這裡改成三段式：高信心直說、中信心帶「可能是」、低信心才算未知。
 * 認錯人對使用者是很尷尬的事，系統寧可表達不確定。
 */
class FaceMatcher(
    private val confidentThreshold: Float = DEFAULT_CONFIDENT_THRESHOLD,
    private val tentativeThreshold: Float = DEFAULT_TENTATIVE_THRESHOLD,
) {
    init {
        require(confidentThreshold in 0f..1f) { "confidentThreshold 必須介於 0 到 1" }
        require(tentativeThreshold in 0f..1f) { "tentativeThreshold 必須介於 0 到 1" }
        require(tentativeThreshold <= confidentThreshold) {
            "tentativeThreshold 不可高於 confidentThreshold"
        }
    }

    fun match(probe: FaceEmbedding, candidates: List<KnownPerson>): FaceMatch {
        if (candidates.isEmpty()) return FaceMatch.Unknown(0f)

        var best: KnownPerson? = null
        var bestSimilarity = -1f

        for (candidate in candidates) {
            // 維度不同代表是不同模型產生的特徵，比對沒有意義。
            // 這在換模型之後很容易發生，靜默比對會給出隨機結果。
            if (candidate.embedding.dimension != probe.dimension) continue

            val similarity = cosineSimilarity(probe.values, candidate.embedding.values)
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity
                best = candidate
            }
        }

        val person = best ?: return FaceMatch.Unknown(0f)
        val similarity = bestSimilarity.coerceAtLeast(0f)

        return when {
            similarity >= confidentThreshold -> FaceMatch.Confident(person, similarity)
            similarity >= tentativeThreshold -> FaceMatch.Tentative(person, similarity)
            else -> FaceMatch.Unknown(similarity)
        }
    }

    companion object {

        /**
         * 高信心閾值。
         *
         * 比舊後端的 0.4 高 —— 0.4 對 512 維的臉部特徵而言偏寬鬆，
         * 容易把長相接近的人認錯。認錯的代價（喊錯名字）比漏認高。
         */
        const val DEFAULT_CONFIDENT_THRESHOLD = 0.6f

        /** 中信心閾值。低於此值視為不認識。 */
        const val DEFAULT_TENTATIVE_THRESHOLD = 0.45f

        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return 0f

            var dot = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in a.indices) {
                dot += a[i].toDouble() * b[i]
                normA += a[i].toDouble() * a[i]
                normB += b[i].toDouble() * b[i]
            }
            if (normA == 0.0 || normB == 0.0) return 0f
            return (dot / (sqrt(normA) * sqrt(normB))).toFloat()
        }

        /**
         * 多張照片取平均，作為一個人的代表特徵。
         *
         * 平均前先各自正規化 —— 不然某張照片的向量長度特別大時，
         * 平均結果會被它主導。
         */
        fun average(embeddings: List<FaceEmbedding>): FaceEmbedding {
            require(embeddings.isNotEmpty()) { "至少需要一個特徵向量" }
            val dimension = embeddings.first().dimension
            require(embeddings.all { it.dimension == dimension }) {
                "所有特徵向量的維度必須相同"
            }

            val sum = FloatArray(dimension)
            for (embedding in embeddings) {
                val norm = sqrt(embedding.values.sumOf { it.toDouble() * it }).toFloat()
                val scale = if (norm > 0f) 1f / norm else 0f
                for (i in 0 until dimension) {
                    sum[i] += embedding.values[i] * scale
                }
            }
            for (i in 0 until dimension) {
                sum[i] /= embeddings.size
            }
            return FaceEmbedding(sum)
        }
    }
}
