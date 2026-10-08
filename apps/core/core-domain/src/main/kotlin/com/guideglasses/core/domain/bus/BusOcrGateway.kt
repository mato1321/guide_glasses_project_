package com.guideglasses.core.domain.bus

import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.glasses.CameraFrame

/**
 * 拍照核對公車車號的抽象。
 *
 * 影像一律來自眼鏡既有的 [com.guideglasses.core.domain.glasses.FrameSource]，
 * 這個介面只負責把已經拍好的一張影像送去比對，不重複定義擷取邏輯。
 */
interface BusOcrGateway {

    val isAvailable: Boolean

    suspend fun confirmBus(
        frame: CameraFrame,
        busNumber: String,
        stopId: String,
        direction: Int,
    ): AppResult<BusOcrResult>
}
