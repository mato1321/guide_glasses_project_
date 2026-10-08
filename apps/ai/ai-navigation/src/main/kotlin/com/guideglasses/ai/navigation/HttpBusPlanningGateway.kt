package com.guideglasses.ai.navigation

import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.bus.BusEta
import com.guideglasses.core.domain.bus.BusPlan
import com.guideglasses.core.domain.bus.BusPlanningGateway
import com.guideglasses.core.domain.navigation.Coordinate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * 公車路線／到站查詢，沿用團隊既有的公車查詢後端（`api/BUS_app.py`、
 * `api/GPS_app.py`，原型 `gps_connect.java` / `gps_walking.java` 呼叫的
 * 同一組端點）。
 *
 * 這是 `docs/TASKS.md` D4「公車整合（MVP，等 B9）」的過渡實作 ——
 * 正式規劃是 TDX 即時資料，但金鑰還沒申請下來。先接團隊自己的後端，
 * 讓短指令「查公車路線」現在就能示範；之後要換 TDX 時只需要換這個類別，
 * [com.guideglasses.core.domain.bus.PlanBusRouteUseCase] 不用動。
 */
class HttpBusPlanningGateway(
    private val endpoint: String,
    private val client: OkHttpClient = defaultClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BusPlanningGateway {

    private val json = Json { ignoreUnknownKeys = true }

    override val isAvailable: Boolean get() = endpoint.isNotBlank()

    override suspend fun fetchPlans(
        origin: Coordinate,
        destination: Coordinate,
    ): AppResult<List<BusPlan>> = fetchPlansInternal(origin) {
        addQueryParameter("dest", "${destination.latitude},${destination.longitude}")
    }

    override suspend fun fetchPlansByName(
        origin: Coordinate,
        destinationName: String,
    ): AppResult<List<BusPlan>> = fetchPlansInternal(origin) {
        addQueryParameter("dest_name", destinationName)
    }

    private suspend fun fetchPlansInternal(
        origin: Coordinate,
        destinationParam: okhttp3.HttpUrl.Builder.() -> okhttp3.HttpUrl.Builder,
    ): AppResult<List<BusPlan>> = withContext(ioDispatcher) {
        val url = "$endpoint/bus-plans".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("origin", "${origin.latitude},${origin.longitude}")
            ?.destinationParam()
            ?.build()
            ?: return@withContext AppResult.Failure(AppError.Unknown("bad bus-plans endpoint: $endpoint"))

        runCatching {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use AppResult.Failure(AppError.Remote(response.code, "bus-plans failed"))
                }
                val payload = response.body?.string()
                    ?: return@use AppResult.Failure(AppError.Remote(response.code, "empty body"))
                parsePlans(payload)
            }
        }.getOrElse { toNetworkFailure(it) }
    }

    override suspend fun fetchEta(plan: BusPlan): AppResult<BusEta> = withContext(ioDispatcher) {
        val url = "$endpoint/eta".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("lat", plan.boardingStopCoordinate.latitude.toString())
            ?.addQueryParameter("lng", plan.boardingStopCoordinate.longitude.toString())
            ?.addQueryParameter("bus", plan.busNumber)
            ?.addQueryParameter("walk_sec", plan.walkToBoardingSeconds.toString())
            ?.build()
            ?: return@withContext AppResult.Failure(AppError.Unknown("bad eta endpoint: $endpoint"))

        runCatching {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use AppResult.Failure(AppError.Remote(response.code, "eta failed"))
                }
                val payload = response.body?.string()
                    ?: return@use AppResult.Failure(AppError.Remote(response.code, "empty body"))
                parseEta(payload)
            }
        }.getOrElse { toNetworkFailure(it) }
    }

    private fun parsePlans(payload: String): AppResult<List<BusPlan>> {
        val decoded = runCatching { json.decodeFromString<PlansResponse>(payload) }
            .getOrElse {
                return AppResult.Failure(AppError.Remote(debugMessage = "malformed bus-plans: ${it.message}"))
            }

        if (!decoded.success) {
            return AppResult.Failure(
                AppError.Remote(debugMessage = decoded.message.ifBlank { "bus-plans returned failure" }),
            )
        }

        return AppResult.Success(
            decoded.plans.map { p ->
                BusPlan(
                    busNumber = p.busNo,
                    boardingStop = p.depStop,
                    alightingStop = p.arrStop,
                    boardingStopCoordinate = Coordinate(p.depLat, p.depLng),
                    walkToBoardingMinutes = p.walkToBusMin,
                    walkToBoardingSeconds = p.walkToBusSec,
                    totalMinutes = p.totalMin,
                )
            },
        )
    }

    private fun parseEta(payload: String): AppResult<BusEta> {
        val decoded = runCatching { json.decodeFromString<EtaResponse>(payload) }
            .getOrElse {
                return AppResult.Failure(AppError.Remote(debugMessage = "malformed eta: ${it.message}"))
            }

        return AppResult.Success(
            BusEta(
                firstArrival = decoded.eta1,
                secondArrival = decoded.eta2,
                message = decoded.message,
                stationId = decoded.stationId,
                stopId = decoded.stopId,
                direction = decoded.direction,
            ),
        )
    }

    private fun toNetworkFailure(error: Throwable): AppResult.Failure = when (error) {
        is UnknownHostException -> AppResult.Failure(AppError.NoNetwork(error.message ?: "unknown host"))
        is SocketTimeoutException -> AppResult.Failure(AppError.NoNetwork(error.message ?: "timeout"))
        is IOException -> AppResult.Failure(AppError.NoNetwork(error.message ?: "io failure"))
        else -> AppResult.Failure(AppError.Unknown(error.message ?: "unexpected failure"))
    }

    @Serializable
    private data class PlansResponse(
        val success: Boolean = false,
        val message: String = "",
        val plans: List<RemotePlan> = emptyList(),
    )

    @Serializable
    private data class RemotePlan(
        @SerialName("bus_no") val busNo: String = "",
        @SerialName("dep_stop") val depStop: String = "",
        @SerialName("arr_stop") val arrStop: String = "",
        @SerialName("dep_lat") val depLat: Double = 0.0,
        @SerialName("dep_lng") val depLng: Double = 0.0,
        @SerialName("walk_to_bus_min") val walkToBusMin: Int = 0,
        @SerialName("walk_to_bus_sec") val walkToBusSec: Int = 0,
        @SerialName("total_min") val totalMin: Int = 0,
    )

    @Serializable
    private data class EtaResponse(
        val eta1: String = "未知",
        val eta2: String = "未知",
        val message: String = "",
        @SerialName("station_id") val stationId: String = "",
        @SerialName("stop_id") val stopId: String = "",
        val direction: Int = -1,
    )

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
