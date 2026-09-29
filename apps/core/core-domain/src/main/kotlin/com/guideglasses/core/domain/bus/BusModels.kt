package com.guideglasses.core.domain.bus

import com.guideglasses.core.domain.navigation.Coordinate

/**
 * 短指令固定的目的地。
 *
 * 「查公車路線」是短指令（見 [com.guideglasses.core.domain.assistant.VoiceCommand]），
 * 沒有畫面也沒有 LLM 可以抽取自由目的地，因此目的地由設定檔固定下來 ——
 * 與 [com.guideglasses.core.domain.assistant.AssistantIntent.NAVIGATE]
 * （需要 LLM 抽取 `destination` 參數）是刻意不同的兩條路。
 */
data class BusStopTarget(
    val latitude: Double,
    val longitude: Double,
    val label: String,
)

/** 一個候選的搭車方案。 */
data class BusPlan(
    val busNumber: String,
    val boardingStop: String,
    val alightingStop: String,
    val boardingStopCoordinate: Coordinate,
    val walkToBoardingMinutes: Int,
    val walkToBoardingSeconds: Int,
    val totalMinutes: Int,
)

/** 到站時間與辨識所需的站牌資訊。 */
data class BusEta(
    val firstArrival: String,
    val secondArrival: String,
    val message: String,
    val stationId: String,
    val stopId: String,
    val direction: Int,
)

/** 拍照核對車號的結果。 */
data class BusOcrResult(
    val matched: Boolean,
    val message: String,
    val recognizedText: String,
)
