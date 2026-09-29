package com.guideglasses.core.domain.face

import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.glasses.CameraFrame

/**
 * 已註冊人物的照片來源。
 *
 * 存在的理由：**眼鏡沒辦法自己註冊人臉。** 語音註冊要從自由語句抽人名
 * （開放集合參數），那需要 BFF，而 BFF 不存在。所以人是在瀏覽器上建檔的
 * （見 `tools/face_enroll_server.py`），眼鏡需要一條把他們同步進來的路。
 *
 * ## 為什麼同步「照片」而不是「特徵向量」
 *
 * 不同模型產生的特徵在不同的向量空間。若後端用 InsightFace ResNet50、
 * 眼鏡用 MobileFaceNet，直接搬特徵過來**不會報錯**，維度甚至可能剛好相同，
 * 但比對結果是隨機的 —— 系統會安靜地認不出任何人。
 *
 * 同步照片、讓眼鏡用自己的模型重算，特徵空間就永遠一致。
 * 代價是同步時要傳照片，但那是偶發動作而非即時路徑，
 * 而且照片用完即棄，**不會落地到眼鏡的儲存空間**。
 */
interface PhotoSource {

    val isAvailable: Boolean

    /** 列出所有人與各自的照片參照。 */
    suspend fun listPeople(): AppResult<List<PersonPhotos>>

    /**
     * 取一張照片並轉成可供偵測的影格。
     *
     * 回傳 [CameraFrame] 而不是原始位元組，是為了讓影像解碼（需要 Android
     * 的 BitmapFactory）留在實作層，domain 才能保持純 Kotlin、可純 JVM 測試。
     */
    suspend fun loadPhoto(reference: String): AppResult<CameraFrame>
}

/**
 * 一個人與他的照片。
 *
 * @param name 姓名。註冊工具以資料夾名稱為姓名，播報時就唸這個。
 * @param photoReferences 照片參照，格式由 [PhotoSource] 實作決定。
 */
data class PersonPhotos(
    val name: String,
    val photoReferences: List<String>,
)
