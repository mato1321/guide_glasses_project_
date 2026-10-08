package com.guideglasses.core.domain.obstacle

import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.announce.Announcement
import com.guideglasses.core.domain.announce.AnnouncementPriority
import com.guideglasses.core.domain.face.BearingResolver
import com.guideglasses.core.domain.glasses.CameraFrame
import com.guideglasses.core.domain.glasses.CaptureRequest
import com.guideglasses.core.domain.glasses.FrameSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 持續盯著前方，有危險才開口。
 *
 * ## 跟 [DetectObstaclesUseCase] 的差別
 *
 * [DetectObstaclesUseCase] 是「使用者問一次 → 拍一張 → 回一句」。對看不見的
 * 使用者來說，得先開口問才知道前面有沒有車，這並不友善 —— 危險不會等人發問。
 *
 * 這個 UseCase 是「開著就一直跑」：相機串流的每一張都推論，經過距離估計、
 * 危險分級、去抖動之後，**只有值得講的**才變成一則 [Announcement] 送出來。
 *
 * ## 為什麼回傳 Flow
 *
 * 呼叫端（ViewModel）這樣收：
 * ```
 * job = scope.launch { watch().collect { announcementManager.announce(it) } }
 * ```
 * 要關掉時 `job.cancel()`，這條 Flow 收束、[FrameSource] 的串流跟著關、
 * 相機跟著停 —— 生命週期只有一個地方管，不會發生「畫面關了相機還開著」。
 *
 * ## 為什麼這裡不會踩到 930ms 的相機延遲
 *
 * 那個延遲來自 [FrameSource.captureOnce]：它每次呼叫都重新綁定一次 CameraX。
 * 這裡用的是 [FrameSource.frames]，串流只綁定**一次**，之後每張影像都是熱的。
 *
 * 純 Kotlin，可用單元測試完整驗證（見 `WatchObstaclesUseCaseTest`）。
 */
class WatchObstaclesUseCase(
    private val frameSource: FrameSource,
    private val detector: ObstacleDetector,
    private val distanceEstimator: ObstacleDistanceEstimator = ObstacleDistanceEstimator(),
    private val classifier: DangerClassifier = DangerClassifier(),
    private val composer: ObstacleAnnouncementComposer = ObstacleAnnouncementComposer(),
    private val debouncer: ObstacleDebouncer = ObstacleDebouncer(),
    private val pathAlignmentDebouncer: PathAlignmentDebouncer = PathAlignmentDebouncer(),
) {

    /**
     * 開始持續偵測。
     *
     * 模型不可用時回傳一條**立即結束**的空 Flow —— 由呼叫端決定要不要
     * 播「障礙物偵測不可用」，這個類別本身不出聲。
     */
    fun watch(request: CaptureRequest = CONTINUOUS_REQUEST): Flow<Announcement> = flow {
        if (!detector.isAvailable) return@flow

        frameSource.frames(request).collect { frameResult ->
            val frame = (frameResult as? AppResult.Success)?.data ?: return@collect

            val detections = when (val result = detector.detect(frame)) {
                is AppResult.Success -> result.data
                // 單張推論失敗就跳過這張，串流繼續 —— 不要因為一張壞掉就整個停。
                is AppResult.Failure -> return@collect
            }

            for (detection in rank(detections)) {
                val announcement = evaluate(detection) ?: continue
                emit(announcement)
            }

            evaluatePathAlignment(detections)?.let { emit(it) }
        }
    }

    /**
     * 導引物（斑馬線、導盲磚、人行道）在畫面中是否置中 —— 回答使用者
     * 「我現在走偏了嗎，該往哪邊靠」，跟上面 hazard 的「有沒有危險」是
     * 兩件不同的事，刻意分開判斷、分開播報優先級。
     *
     * 同一張影格可能同時偵測到多種導引物（例如導盲磚與人行道），
     * 只挑畫面佔比最大（=離使用者最近、最該參考）的那一個當基準，
     * 不然多個判斷互相矛盾反而讓使用者無所適從。
     */
    private fun evaluatePathAlignment(detections: List<Detection>): Announcement? {
        val guide = detections.filter { !it.type.isHazard }.maxByOrNull { it.area } ?: run {
            // 這一張沒看到任何導引物——不代表使用者走偏了，可能只是導盲磚
            // 剛好被路過的行人擋住一瞬間。不清空 debouncer，避免因為單張
            // 漏偵測就讓下一次判斷又從頭報一次「偏了」。
            return null
        }

        val bearing = BearingResolver.resolve(guide.centerX)
        val message = when (bearing) {
            // 導引物出現在畫面左側 → 物理上在使用者左邊 → 使用者已經偏到
            // 它的右邊，該往左靠才能回到路徑上。
            BearingResolver.Bearing.LEFT -> "${guide.type.spoken}在您左邊，請往左靠一點"
            BearingResolver.Bearing.RIGHT -> "${guide.type.spoken}在您右邊，請往右靠一點"
            // 置中代表走在路徑上，安靜不講——跟障礙物偵測同一個「安全時沉默」原則。
            BearingResolver.Bearing.AHEAD -> {
                pathAlignmentDebouncer.reset()
                return null
            }
        }

        if (!pathAlignmentDebouncer.shouldAnnounce(bearing)) return null

        return Announcement(
            text = message,
            priority = AnnouncementPriority.NAVIGATION,
            dedupeKey = "path-align-$bearing",
        )
    }

    /**
     * 一筆偵測 → 要不要播、播什麼。回傳 null 代表「這筆不用講」。
     *
     * 三道關卡，任何一道沒過就 null：
     *  1. 只理會危險類（車、人、機車…）。斑馬線、導盲磚是導引資訊，
     *     不走這個「有沒有危險」的判斷，而是走 [evaluatePathAlignment]
     *     那條「有沒有置中」的判斷——兩者問的問題不同，分開處理。
     *  2. 估得出距離、且落在會播報的範圍（5 公尺內，見 [DangerClassifier]）。
     *     估不出距離的通用「障礙物」類別在這個模式先跳過 ——
     *     寧可不講，也不要報一個錯的公尺數讓使用者撞上去。
     *  3. 同一個物體在時間窗內沒講過（[ObstacleDebouncer]）。
     */
    private fun evaluate(detection: Detection): Announcement? {
        if (!detection.type.isHazard) return null

        val distance = distanceEstimator.estimateMeters(detection) ?: return null
        val priority = classifier.priorityFor(detection, distance, userAsked = false)
            ?: return null

        if (!debouncer.shouldAnnounce(detection)) return null

        return Announcement(
            text = composer.compose(detection),
            priority = priority,
            // AnnouncementManager 的第二層去重 —— 萬一 debouncer 被重建，
            // 這裡還能再擋一次同一類的連續播報。
            dedupeKey = "obstacle-${detection.type.name}",
            dedupeWindowMillis = DEDUPE_WINDOW_MILLIS,
        )
    }

    /** 危險的排前面、大的排前面（大約等於近的），一張最多處理 [MAX_PER_FRAME] 個。 */
    private fun rank(detections: List<Detection>): List<Detection> =
        detections
            .sortedWith(
                compareByDescending<Detection> { it.type.isHazard }
                    .thenByDescending { it.area },
            )
            .take(MAX_PER_FRAME)

    companion object {
        /**
         * 持續偵測的擷取參數。
         *
         * - 2 fps：走路 1.4 m/s 下約每 70 公分判斷一次，對「遠方接近的危險」
         *   夠用，而 210mAh 的電池撐不起更高的頻率。
         * - RGBA 而非 JPEG：端側推論要的是像素，編碼再解碼是純浪費。
         * - 640 長邊：模型輸入就是 640，送更大只是多花時間縮放。
         */
        val CONTINUOUS_REQUEST = CaptureRequest(
            targetFps = 2f,
            longEdgePixels = 640,
            outputFormat = CameraFrame.Format.RGBA_8888,
        )

        /** 一張影像最多播報幾個障礙物，避免一次冒出一長串語音。 */
        const val MAX_PER_FRAME = 2

        /** 同一類障礙物在這段時間內只播一次（與 [ObstacleDebouncer] 的預設一致）。 */
        const val DEDUPE_WINDOW_MILLIS = 5_000L
    }
}

/**
 * 路徑置中提醒的去抖動——跟 [ObstacleDebouncer] 邏輯不同，不能共用。
 *
 * [ObstacleDebouncer] 問的是「同一個物體多久沒講過」；這裡問的是
 * 「使用者現在的狀態（偏左／偏右）有沒有變化」。使用者持續偏左走十秒，
 * 應該每隔幾秒提醒一次（不是完全不講，也不是每張影格都講），
 * 但一回到路徑中央就立刻靜音，不等冷卻時間——安全相關的「好消息」
 * 不該被延遲。
 */
class PathAlignmentDebouncer(
    private val reminderIntervalMillis: Long = DEFAULT_REMINDER_INTERVAL_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var lastBearing: BearingResolver.Bearing? = null
    private var lastAnnouncedAt: Long = 0L

    /** @return true 代表現在可以播報。 */
    fun shouldAnnounce(bearing: BearingResolver.Bearing): Boolean {
        val timestamp = now()
        val changed = bearing != lastBearing
        val elapsed = timestamp - lastAnnouncedAt

        if (!changed && elapsed < reminderIntervalMillis) return false

        lastBearing = bearing
        lastAnnouncedAt = timestamp
        return true
    }

    /** 使用者回到路徑中央時呼叫——下一次偏移要能立刻提醒，不能還在冷卻。 */
    fun reset() {
        lastBearing = null
    }

    companion object {
        /** 持續偏移時多久提醒一次。太短像疲勞轟炸，太長使用者會一直走偏。 */
        const val DEFAULT_REMINDER_INTERVAL_MILLIS = 6_000L
    }
}
