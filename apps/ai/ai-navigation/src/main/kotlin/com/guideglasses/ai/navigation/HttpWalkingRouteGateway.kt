package com.guideglasses.ai.navigation

import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.AppResult
import com.guideglasses.core.domain.navigation.Coordinate
import com.guideglasses.core.domain.navigation.NavigationStep
import com.guideglasses.core.domain.navigation.WalkingRouteGateway
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
 * 步行逐步轉彎指示，打後端的 `/walking-route`（`backend/app/navigation_logic.py`）。
 *
 * 跟 [HttpBusPlanningGateway] 同一個模式：地名／座標擇一，`dest_name` 交給
 * 後端的 Google Routes API 直接解析，這裡不做地理編碼。
 */
class HttpWalkingRouteGateway(
    private val endpoint: String,
    private val client: OkHttpClient = defaultClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WalkingRouteGateway {

    private val json = Json { ignoreUnknownKeys = true }

    override val isAvailable: Boolean get() = endpoint.isNotBlank()

    override suspend fun fetchSteps(
        origin: Coordinate,
        destination: Coordinate,
    ): AppResult<List<NavigationStep>> = fetchStepsInternal(origin) {
        addQueryParameter("dest", "${destination.latitude},${destination.longitude}")
    }

    override suspend fun fetchStepsByName(
        origin: Coordinate,
        destinationName: String,
    ): AppResult<List<NavigationStep>> = fetchStepsInternal(origin) {
        addQueryParameter("dest_name", destinationName)
    }

    private suspend fun fetchStepsInternal(
        origin: Coordinate,
        destinationParam: okhttp3.HttpUrl.Builder.() -> okhttp3.HttpUrl.Builder,
    ): AppResult<List<NavigationStep>> = withContext(ioDispatcher) {
        val url = "$endpoint/walking-route".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("origin", "${origin.latitude},${origin.longitude}")
            ?.destinationParam()
            ?.build()
            ?: return@withContext AppResult.Failure(AppError.Unknown("bad walking-route endpoint: $endpoint"))

        runCatching {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use AppResult.Failure(AppError.Remote(response.code, "walking-route failed"))
                }
                val payload = response.body?.string()
                    ?: return@use AppResult.Failure(AppError.Remote(response.code, "empty body"))
                parseSteps(payload)
            }
        }.getOrElse { toNetworkFailure(it) }
    }

    private fun parseSteps(payload: String): AppResult<List<NavigationStep>> {
        val decoded = runCatching { json.decodeFromString<WalkingRouteResponse>(payload) }
            .getOrElse {
                return AppResult.Failure(AppError.Remote(debugMessage = "malformed walking-route: ${it.message}"))
            }

        if (!decoded.success) {
            return AppResult.Failure(
                AppError.Remote(debugMessage = decoded.message.ifBlank { "walking-route returned failure" }),
            )
        }

        return AppResult.Success(
            decoded.steps.map { s ->
                NavigationStep(
                    instruction = s.instruction,
                    location = Coordinate(s.lat, s.lng),
                    distanceMeters = s.distanceM,
                )
            },
        )
    }

    private fun toNetworkFailure(error: Throwable): AppResult.Failure = when (error) {
        is UnknownHostException -> AppResult.Failure(AppError.NoNetwork(error.message ?: "unknown host"))
        is SocketTimeoutException -> AppResult.Failure(AppError.NoNetwork(error.message ?: "timeout"))
        is IOException -> AppResult.Failure(AppError.NoNetwork(error.message ?: "io failure"))
        else -> AppResult.Failure(AppError.Unknown(error.message ?: "unexpected failure"))
    }

    @Serializable
    private data class WalkingRouteResponse(
        val success: Boolean = false,
        val message: String = "",
        val steps: List<RemoteStep> = emptyList(),
    )

    @Serializable
    private data class RemoteStep(
        val instruction: String = "",
        val lat: Double = 0.0,
        val lng: Double = 0.0,
        @SerialName("distance_m") val distanceM: Int = 0,
    )

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
