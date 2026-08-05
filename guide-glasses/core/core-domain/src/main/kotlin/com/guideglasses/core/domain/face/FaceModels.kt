package com.guideglasses.core.domain.face

/**
 * 畫面中偵測到的一張臉。
 *
 * 座標一律用**相對比例**（0..1）而不是像素 —— 這樣同一組判斷邏輯在
 * 640×480 與 1280×960 之下行為一致，也讓 domain 層不必知道影像多大。
 */
data class DetectedFace(
    /** 左邊界，佔畫面寬度的比例。 */
    val left: Float,
    /** 上邊界，佔畫面高度的比例。 */
    val top: Float,
    /** 寬度，佔畫面寬度的比例。 */
    val width: Float,
    /** 高度，佔畫面高度的比例。 */
    val height: Float,
) {
    init {
        require(width > 0f && height > 0f) { "臉部尺寸必須為正" }
    }

    /** 水平中心點，0 = 最左，1 = 最右。 */
    val centerX: Float get() = left + width / 2f

    /** 面積比例。同時有多張臉時用來挑「最靠近的那一張」。 */
    val area: Float get() = width * height
}

/**
 * 人臉特徵向量。
 *
 * 這是**生物特徵**，屬《個人資料保護法》的特種個資。整個系統的設計前提是
 * 它永遠不離開裝置 —— 沒有任何一條路徑會把它上傳。
 */
data class FaceEmbedding(val values: FloatArray) {

    init {
        require(values.isNotEmpty()) { "特徵向量不可為空" }
    }

    val dimension: Int get() = values.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceEmbedding) return false
        return values.contentEquals(other.values)
    }

    override fun hashCode(): Int = values.contentHashCode()
}

/**
 * 已註冊的人。
 *
 * @param embedding 這個人的代表性特徵。多張照片註冊時取平均。
 * @param photoCount 註冊時用了幾張照片。愈多通常愈穩定。
 */
data class KnownPerson(
    val id: Long,
    val name: String,
    val relation: String? = null,
    val embedding: FaceEmbedding,
    val photoCount: Int = 1,
) {
    /** 播報時的稱呼。有關係就用關係，例如「媽媽」比「王美華」更自然。 */
    val spokenName: String get() = relation?.takeIf { it.isNotBlank() } ?: name
}

/** 辨識結果。 */
sealed interface FaceMatch {

    /** 高信心，直接說出是誰。 */
    data class Confident(val person: KnownPerson, val similarity: Float) : FaceMatch

    /**
     * 中等信心 —— 播報時要帶不確定性。
     *
     * 「可能是王老師」比「是王老師」誠實。認錯人對使用者是很尷尬的事，
     * 系統寧可表達不確定，也不要讓他喊錯名字。
     */
    data class Tentative(val person: KnownPerson, val similarity: Float) : FaceMatch

    /** 資料庫裡沒有這個人。 */
    data class Unknown(val bestSimilarity: Float) : FaceMatch
}
