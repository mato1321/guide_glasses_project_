package com.guideglasses.core.domain.navigation

import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.announce.Announcement
import com.guideglasses.core.domain.announce.AnnouncementPriority
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
 * v1 只做「接近該轉彎點就講下一句」，不做偏離路線偵測／自動重新規劃——
 * 使用者走偏了目前只能自己說「停」取消，之後可以再加。
 */
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
        emit(Announcement(steps.first().instruction, AnnouncementPriority.NAVIGATION, dedupeKey = "nav-step-0"))

        for (index in 1 until steps.size) {
            val step = steps[index]
            locationProvider.locations().first { current ->
                Geo.distanceMeters(current, step.location) <= ARRIVAL_RADIUS_METERS
            }
            emit(Announcement(step.instruction, AnnouncementPriority.NAVIGATION, dedupeKey = "nav-step-$index"))
        }

        emit(Announcement(MESSAGE_ARRIVED, AnnouncementPriority.NAVIGATION))
    }

    private companion object {
        const val LOCATION_TIMEOUT_MS = 8_000L

        /** 走到轉彎點這個範圍內就播報——太小容易因 GPS 誤差永遠觸發不到。 */
        const val ARRIVAL_RADIUS_METERS = 20.0

        const val MESSAGE_UNAVAILABLE = "即時導航目前不可用，還沒設定後端位址"
        const val MESSAGE_NO_LOCATION = "目前拿不到定位，請確認手機同伴 App 已開啟並連上網路"
        const val MESSAGE_NO_ROUTE = "查不到步行路線"
        const val MESSAGE_ARRIVED = "已經抵達目的地附近"
    }
}
