package com.guideglasses.core.domain.obstacle

import com.guideglasses.core.domain.announce.Announcement
import com.guideglasses.core.domain.motion.CameraModeController
import com.guideglasses.core.domain.motion.MotionSensorGateway
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * 用 IMU 自動決定「現在該不該持續偵測障礙物」。
 *
 * [WatchObstaclesUseCase] 只負責「開著的時候怎麼判斷危險」，不負責「什麼時候
 * 該開」。這個類別補上那一半：走路 → 開始偵測；站住 → 自動關掉相機省電。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoWatchObstaclesUseCase(
    private val motionSensors: MotionSensorGateway,
    private val watchObstacles: WatchObstaclesUseCase,
    private val cameraModeController: CameraModeController = CameraModeController(),
) {

    /**
     * 訂閱走路狀態，狀態一變就切換相機模式。
     *
     * 用 [flatMapLatest]：每次走路狀態改變，前一個模式底下還在跑的
     * [WatchObstaclesUseCase.watch] 會被自動取消（連同相機串流一起關掉），
     * 換成新模式對應的那一條 —— 不用自己手動管理 Job。
     */
    fun run(): Flow<Announcement> =
        motionSensors.walkingState()
            .map { walking ->
                val mode = cameraModeController.decide(walkingState = walking)
                println("AutoWatch 除錯：walking=$walking → mode=$mode")  // 測完記得刪掉這行
                mode
            }
            .distinctUntilChanged()
            .flatMapLatest { mode ->
                val request = mode.toCaptureRequest()
                if (request != null) watchObstacles.watch(request) else emptyFlow()
            }
}