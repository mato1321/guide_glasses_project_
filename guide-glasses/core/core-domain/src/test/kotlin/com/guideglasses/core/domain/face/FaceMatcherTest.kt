package com.guideglasses.core.domain.face

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FaceMatcherTest {

    private val matcher = FaceMatcher()

    private fun embedding(vararg values: Float) = FaceEmbedding(values)

    private fun person(id: Long, name: String, embedding: FaceEmbedding, relation: String? = null) =
        KnownPerson(id = id, name = name, relation = relation, embedding = embedding)

    @Test
    fun `完全相同的特徵是高信心`() {
        val e = embedding(1f, 0f, 0f)
        val result = matcher.match(e, listOf(person(1, "王老師", e)))

        assertThat(result).isInstanceOf(FaceMatch.Confident::class.java)
        assertThat((result as FaceMatch.Confident).person.name).isEqualTo("王老師")
        assertThat(result.similarity).isWithin(0.001f).of(1f)
    }

    @Test
    fun `完全不同的特徵是未知`() {
        val probe = embedding(1f, 0f, 0f)
        val stored = embedding(0f, 1f, 0f)

        val result = matcher.match(probe, listOf(person(1, "王老師", stored)))

        assertThat(result).isInstanceOf(FaceMatch.Unknown::class.java)
    }

    @Test
    fun `中等相似度回傳 Tentative 而不是硬說是誰`() {
        // 舊後端單一閾值 0.4：0.41 就會信誓旦旦地喊名字。
        // 認錯人的代價比漏認高，所以這一段要帶不確定性。
        val probe = embedding(1f, 0f)
        val stored = embedding(0.55f, 0.835f) // cos ≈ 0.55

        val result = matcher.match(probe, listOf(person(1, "王老師", stored)))

        assertThat(result).isInstanceOf(FaceMatch.Tentative::class.java)
    }

    @Test
    fun `空資料庫回傳未知`() {
        val result = matcher.match(embedding(1f, 0f), emptyList())

        assertThat(result).isInstanceOf(FaceMatch.Unknown::class.java)
        assertThat((result as FaceMatch.Unknown).bestSimilarity).isEqualTo(0f)
    }

    @Test
    fun `會挑相似度最高的那個人`() {
        val probe = embedding(1f, 0f, 0f)
        val candidates = listOf(
            person(1, "甲", embedding(0f, 1f, 0f)),
            person(2, "乙", embedding(0.99f, 0.14f, 0f)),
            person(3, "丙", embedding(0.5f, 0.87f, 0f)),
        )

        val result = matcher.match(probe, candidates)

        assertThat((result as FaceMatch.Confident).person.name).isEqualTo("乙")
    }

    @Test
    fun `維度不同的特徵會被跳過而不是給出隨機結果`() {
        // 換模型之後很容易發生。靜默比對會產生無意義的相似度。
        val probe = embedding(1f, 0f, 0f)
        val candidates = listOf(person(1, "舊模型的人", embedding(1f, 0f)))

        val result = matcher.match(probe, candidates)

        assertThat(result).isInstanceOf(FaceMatch.Unknown::class.java)
    }

    @Test
    fun `有關係時播報用關係稱呼`() {
        val e = embedding(1f, 0f)
        val mother = person(1, "王美華", e, relation = "媽媽")

        assertThat(mother.spokenName).isEqualTo("媽媽")
    }

    @Test
    fun `沒有關係時播報用名字`() {
        val teacher = person(1, "王老師", embedding(1f, 0f))

        assertThat(teacher.spokenName).isEqualTo("王老師")
    }

    @Test
    fun `閾值可自訂`() {
        val strict = FaceMatcher(confidentThreshold = 0.95f, tentativeThreshold = 0.9f)
        val probe = embedding(1f, 0f)
        val stored = embedding(0.8f, 0.6f) // cos = 0.8

        assertThat(strict.match(probe, listOf(person(1, "甲", stored))))
            .isInstanceOf(FaceMatch.Unknown::class.java)
    }

    @Test
    fun `tentative 閾值不可高於 confident`() {
        val error = runCatching {
            FaceMatcher(confidentThreshold = 0.5f, tentativeThreshold = 0.8f)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `cosine 相似度計算正確`() {
        assertThat(FaceMatcher.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f)))
            .isWithin(0.001f).of(1f)
        assertThat(FaceMatcher.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)))
            .isWithin(0.001f).of(0f)
        assertThat(FaceMatcher.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f)))
            .isWithin(0.001f).of(-1f)
    }

    @Test
    fun `零向量不會造成除以零`() {
        assertThat(FaceMatcher.cosineSimilarity(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f)))
            .isEqualTo(0f)
    }

    @Test
    fun `多張照片取平均`() {
        val averaged = FaceMatcher.average(
            listOf(embedding(1f, 0f), embedding(0f, 1f)),
        )

        assertThat(averaged.dimension).isEqualTo(2)
        // 兩個正交單位向量的平均應該落在中間
        assertThat(averaged.values[0]).isWithin(0.01f).of(averaged.values[1])
    }

    @Test
    fun `平均前會先正規化避免被長向量主導`() {
        val short = embedding(1f, 0f)
        val long = embedding(0f, 100f) // 長度是 short 的 100 倍

        val averaged = FaceMatcher.average(listOf(short, long))

        // 若沒有先正規化，第二維會遠大於第一維
        assertThat(averaged.values[0]).isWithin(0.01f).of(averaged.values[1])
    }

    @Test
    fun `平均要求維度一致`() {
        val error = runCatching {
            FaceMatcher.average(listOf(embedding(1f, 0f), embedding(1f, 0f, 0f)))
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }
}

class BearingResolverTest {

    @Test
    fun `左側`() {
        assertThat(BearingResolver.resolve(0.1f)).isEqualTo(BearingResolver.Bearing.LEFT)
        assertThat(BearingResolver.resolve(0.3f)).isEqualTo(BearingResolver.Bearing.LEFT)
    }

    @Test
    fun `正前方`() {
        assertThat(BearingResolver.resolve(0.5f)).isEqualTo(BearingResolver.Bearing.AHEAD)
        assertThat(BearingResolver.resolve(0.4f)).isEqualTo(BearingResolver.Bearing.AHEAD)
    }

    @Test
    fun `右側`() {
        assertThat(BearingResolver.resolve(0.8f)).isEqualTo(BearingResolver.Bearing.RIGHT)
        assertThat(BearingResolver.resolve(0.95f)).isEqualTo(BearingResolver.Bearing.RIGHT)
    }

    @Test
    fun `播報文字是可以直接唸的`() {
        assertThat(BearingResolver.Bearing.LEFT.spoken).isEqualTo("左前方")
        assertThat(BearingResolver.Bearing.AHEAD.spoken).isEqualTo("正前方")
        assertThat(BearingResolver.Bearing.RIGHT.spoken).isEqualTo("右前方")
    }
}

class FaceDistanceEstimatorTest {

    private val estimator = FaceDistanceEstimator()

    @Test
    fun `臉佔畫面愈大距離愈近`() {
        val near = estimator.estimateMeters(0.3f)!!
        val far = estimator.estimateMeters(0.05f)!!

        assertThat(near).isLessThan(far)
    }

    @Test
    fun `估計值落在合理範圍`() {
        // 臉佔畫面寬度 10% 時，直覺上大約在一到兩公尺
        val meters = estimator.estimateMeters(0.1f)!!

        assertThat(meters).isGreaterThan(0.5f)
        assertThat(meters).isLessThan(3f)
    }

    @Test
    fun `非法比例回傳 null`() {
        assertThat(estimator.estimateMeters(0f)).isNull()
        assertThat(estimator.estimateMeters(-0.1f)).isNull()
        assertThat(estimator.estimateMeters(1.5f)).isNull()
    }

    @Test
    fun `很近時說就在眼前而不報數字`() {
        val description = estimator.describe(0.6f)

        assertThat(description).isEqualTo("就在眼前")
    }

    @Test
    fun `描述不含小數點`() {
        // 這個估計本身有 20-30% 誤差，報小數是假精確，
        // 而且「三點二公尺」在聽覺上比「三公尺」難處理。
        val description = estimator.describe(0.05f)!!

        assertThat(description).doesNotContain(".")
    }

    @Test
    fun `視角可校正`() {
        val wide = FaceDistanceEstimator(horizontalFovDegrees = 100f)
        val narrow = FaceDistanceEstimator(horizontalFovDegrees = 40f)

        // 同樣的臉部比例，廣角鏡代表物體更近
        assertThat(wide.estimateMeters(0.1f)!!).isLessThan(narrow.estimateMeters(0.1f)!!)
    }

    @Test
    fun `非法視角會被拒絕`() {
        assertThat(
            runCatching { FaceDistanceEstimator(horizontalFovDegrees = 0f) }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)

        assertThat(
            runCatching { FaceDistanceEstimator(horizontalFovDegrees = 200f) }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
    }
}
