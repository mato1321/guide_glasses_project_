package com.guideglasses.core.domain.navigation

import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.announce.Announcement
import com.guideglasses.core.domain.announce.AnnouncementPriority
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 即時步行導航：邊走邊播報轉彎指示。
 *
 * 刻意不接 Google Maps App——所有播報都用 [Announcement] 送進
 * `AnnouncementManager`，這樣障礙物 CRITICAL 警告才能正確插話（見
 * `docs/ARCHITECTURE.md`「播報仲裁只能有一個節點」）。轉彎的實際判斷
 * （街道、單行道、天橋）交給 [WalkingRouteGateway] 拿到的 Google 逐步指示，
 * 這個類別只負責「現在該講哪一句」。
 *
 * ### 眼鏡沒有電子羅盤
 *
 * Google 的第一步常是「往西走信義路五段」，但使用者不知道哪邊是西。
 * 所以方位詞一律改成沿路直走（[spokenInstruction]），走出 [HEADING_CHECK_METERS]
 * 之後再用實際移動方向核對，走反了就提醒（[MESSAGE_WRONG_WAY]）——
 * 企畫書 P1「首步需要朝向的指示改寫」。
 *
 * 目前只核對一次方向，還不做持續的偏離路線偵測／自動重新規劃，
 * 使用者走偏了只能自己說「停」取消，之後可以再加。
 */
// Flow.timeout 仍標為 FlowPreview；它正好是「距離上一筆多久」的語意，自己寫容易寫錯。
@OptIn(FlowPreview::class)
class NavigateWalkingUseCase(
    private val locationProvider: LocationProvider,
    private val walkingRouteGateway: WalkingRouteGateway,
) {

    fun navigate(destination: Coordinate? = null, destinationName: String? = null): Flow<Announcement> = flow {
        if (!walkingRouteGateway.isAvailable) {
            emit(Announcement(MESSAGE_UNAVAILABLE, AnnouncementPriority.NAVIGATION))
            return@flow
        }
        if (!locationProvider.isAvailable) {
            emit(Announcement(MESSAGE_NO_LOCATION, AnnouncementPriority.NAVIGATION))
            return@flow
        }

        val origin = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            locationProvider.locations().first()
        } ?: run {
            emit(Announcement(MESSAGE_NO_LOCATION, AnnouncementPriority.NAVIGATION))
            return@flow
        }

        val fetchResult = if (destinationName.isNullOrBlank()) {
            walkingRouteGateway.fetchSteps(
                origin,
                requireNotNull(destination) { "destination 或 destinationName 至少要有一個" },
            )
        } else {
            walkingRouteGateway.fetchStepsByName(origin, destinationName)
        }

        val steps = when (fetchResult) {
            is AppResult.Success -> fetchResult.data
            is AppResult.Failure -> {
                emit(Announcement(MESSAGE_NO_ROUTE, AnnouncementPriority.NAVIGATION))
                return@flow
            }
        }

        if (steps.isEmpty()) {
            emit(Announcement(MESSAGE_NO_ROUTE, AnnouncementPriority.NAVIGATION))
            return@flow
        }

        // 第一步就在起點附近（Google 路線規劃的第一段指示），不用等位置更新才講。
        emit(Announcement(spokenInstruction(steps.first()), AnnouncementPriority.NAVIGATION, dedupeKey = "nav-step-0"))

        // 真正的終點：優先用呼叫端給的座標，其次是最後一步的終點。
        // 以前少了「走到終點」這一段：只有一步的路線，第一句講完就立刻說已抵達
        // （實機：離上車站還有 90 公尺就說「已經抵達目的地附近」）。
        val arrivalPoint = destination ?: steps.last().endLocation ?: steps.last().location
        val firstLegTarget = steps.getOrNull(1)?.location ?: arrivalPoint

        var lastKnown = origin
        // 第一段短到走不出核對距離時，方向對不對已經無所謂。
        var headingChecked = Geo.distanceMeters(origin, firstLegTarget) < HEADING_CHECK_METERS

        /**
         * 等到走進 [target] 的範圍內。途中順便做一次方向核對，走反了就提醒後繼續等；
         * 太久收不到新座標也說出來再繼續等（[MESSAGE_LOCATION_STALE]）。
         *
         * @return 走到了回傳 true；定位串流中斷回傳 false。
         */
        suspend fun reach(target: Coordinate): Boolean {
            while (true) {
                if (Geo.distanceMeters(lastKnown, target) <= ARRIVAL_RADIUS_METERS) return true

                var wrongWay = false
                val reached = try {
                    locationProvider.locations()
                        // 從「上一筆座標」起算，不是從開始等起算 —— 一直在走、只是
                        // 還沒走到轉彎點的人，不該被說成定位中斷。
                        .timeout(LOCATION_STALE_MILLIS.milliseconds)
                        .firstOrNull { current ->
                            lastKnown = current
                            if (!headingChecked && Geo.distanceMeters(origin, current) >= HEADING_CHECK_METERS) {
                                headingChecked = true
                                wrongWay = isWrongWay(origin, current, firstLegTarget)
                            }
                            wrongWay || Geo.distanceMeters(current, target) <= ARRIVAL_RADIUS_METERS
                        } ?: return false
                } catch (e: TimeoutCancellationException) {
                    null
                }

                when {
                    reached == null -> emit(
                        Announcement(
                            MESSAGE_LOCATION_STALE,
                            AnnouncementPriority.NAVIGATION,
                            dedupeKey = "nav-location-stale",
                            dedupeWindowMillis = LOCATION_STALE_REPEAT_MILLIS,
                        ),
                    )
                    wrongWay -> emit(Announcement(MESSAGE_WRONG_WAY, AnnouncementPriority.NAVIGATION, dedupeKey = "nav-wrong-way"))
                    else -> return true
                }
            }
        }

        for (index in 1 until steps.size) {
            if (!reach(steps[index].location)) {
                emit(Announcement(MESSAGE_LOCATION_LOST, AnnouncementPriority.NAVIGATION))
                return@flow
            }
            emit(Announcement(spokenInstruction(steps[index]), AnnouncementPriority.NAVIGATION, dedupeKey = "nav-step-$index"))
        }

        if (!reach(arrivalPoint)) {
            emit(Announcement(MESSAGE_LOCATION_LOST, AnnouncementPriority.NAVIGATION))
            return@flow
        }
        emit(Announcement(MESSAGE_ARRIVED, AnnouncementPriority.NAVIGATION))
    }

    /** 從 [origin] 走到 [current] 的方向，跟該走的方向差太多就是走反了。 */
    private fun isWrongWay(origin: Coordinate, current: Coordinate, target: Coordinate): Boolean {
        val walked = Geo.bearingDegrees(origin, current)
        val expected = Geo.bearingDegrees(origin, target)
        return abs(Geo.relativeTurn(walked, expected)) > WRONG_WAY_DEGREES
    }

    internal companion object {
        const val LOCATION_TIMEOUT_MS = 8_000L

        /** 走到轉彎點這個範圍內就播報——太小容易因 GPS 誤差永遠觸發不到。 */
        const val ARRIVAL_RADIUS_METERS = 20.0

        /**
         * 離開起點多遠才核對方向。GPS 在市區的誤差 5–15 公尺，
         * 太近的話站著不動也會因為飄移被判成「走反了」。
         */
        const val HEADING_CHECK_METERS = 20.0

        /** 實際走的方向跟該走的方向差超過這個角度，才算走反（容許斜走、繞過障礙物）。 */
        const val WRONG_WAY_DEGREES = 120.0

        /**
         * 多久收不到新座標就說出來。手機每秒回報、眼鏡丟掉超過 5 秒的舊座標
         * （`PhoneCompanionLocationProvider`），15 秒都沒有新的，代表手機那端出事了
         * （App 被關、沒網路、後端停了）。不說的話，使用者只會覺得導航突然不講話了。
         */
        const val LOCATION_STALE_MILLIS = 15_000L

        /** 一直收不到時，同一句提醒最多一分鐘講一次。 */
        const val LOCATION_STALE_REPEAT_MILLIS = 60_000L

        const val MESSAGE_UNAVAILABLE = "即時導航目前不可用，還沒設定後端位址"
        const val MESSAGE_NO_LOCATION = "目前拿不到定位，請確認手機同伴 App 已開啟並連上網路"
        const val MESSAGE_NO_ROUTE = "查不到步行路線"
        const val MESSAGE_ARRIVED = "已經抵達目的地附近"
        const val MESSAGE_WRONG_WAY = "方向好像相反了，請轉身往回走"
        const val MESSAGE_LOCATION_LOST = "定位中斷，導航暫停"
        const val MESSAGE_LOCATION_STALE = "暫時收不到手機定位，請確認手機的導盲定位還開著"

        /** 「往西走信義路五段」「向北前進」「朝東南方走」這類需要知道東西南北的開頭。 */
        private val CARDINAL = Regex("""^(往|向|朝)(東北|東南|西北|西南|東|西|南|北)方?(走|前進|行走)?(.*)$""")

        /** 「朝延平南路」「向信義路」：往某條路的方向走，不是沿著它走。 */
        private val TOWARD = Regex("""^[朝向](.+)$""")

        /** 「沿著忠孝東路」：本來就是沿路走。 */
        private val ALONG = Regex("""^沿著?(.+)$""")

        /** 「走上」「走到」的介系詞，改寫成「沿著」時要拿掉。 */
        private val ROAD_PREFIX = Regex("""^[上到往]""")

        /** 句尾的動詞，改寫後會自己補「走」，不拿掉會變成「前進走」。 */
        private val TRAILING_VERB = Regex("""(前進|行走|走)$""")

        /**
         * 要唸出來的指示。Google 的說法原樣保留，只改掉需要方位感的開頭：
         *
         * | Google | 唸出來 |
         * |---|---|
         * | 往西走信義路五段（90 公尺）| 沿著信義路五段走，約 90 公尺 |
         * | 往西走信義路五段⏎目的地在右邊 | 沿著信義路五段走，約 90 公尺。目的地在右邊 |
         * | 往北朝延平南路前進（39 公尺）| 往延平南路的方向走，約 39 公尺 |
         * | 往東沿著忠孝東路走（50 公尺）| 沿著忠孝東路走，約 50 公尺 |
         * | 往東南方走（30 公尺）| 直走，約 30 公尺 |
         * | 往北走，然後右轉進入中山北路 | 直走，然後右轉進入中山北路 |
         * | 向左轉進入基隆路一段 | （不變）|
         *
         * Google 的指示可能有好幾行（眼鏡實測：「往西走信義路五段\n目的地在右邊」），
         * 方位詞只會出現在第一行，後面幾行原樣接上。
         * 「朝⋯前進」也是眼鏡實測遇到的（曾被唸成「沿著朝延平南路前進走」）。
         */
        fun spokenInstruction(step: NavigationStep): String {
            val lines = step.instruction.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val first = lines.firstOrNull()?.let { rewriteCardinal(it, step.distanceMeters) }
                ?: return step.instruction
            return (listOf(first) + lines.drop(1)).joinToString("。")
        }

        /** 單行的方位詞改寫；這行不是方位詞開頭就回 null。 */
        private fun rewriteCardinal(line: String, distanceMeters: Int): String? {
            val match = CARDINAL.find(line) ?: return null
            val rest = match.groupValues[4].trim()
            if (rest.startsWith("，") || rest.startsWith(",")) return "直走$rest"

            val phrase = rest.replace(TRAILING_VERB, "").trim()
            val toward = TOWARD.find(phrase)?.groupValues?.get(1)?.trim()
            val along = ALONG.find(phrase)?.groupValues?.get(1)?.trim()
            val base = when {
                phrase.isEmpty() -> "直走"
                !toward.isNullOrEmpty() -> "往${toward}的方向走"
                !along.isNullOrEmpty() -> "沿著${along}走"
                else -> "沿著${phrase.replace(ROAD_PREFIX, "")}走"
            }
            return if (distanceMeters > 0) "$base，約 $distanceMeters 公尺" else base
        }
    }
}
