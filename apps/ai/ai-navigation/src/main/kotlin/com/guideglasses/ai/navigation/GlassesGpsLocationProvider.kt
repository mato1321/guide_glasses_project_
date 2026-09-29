package com.guideglasses.ai.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.guideglasses.core.domain.navigation.Coordinate
import com.guideglasses.core.domain.navigation.LocationProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 裝置本身的 GPS，透過 `FusedLocationProviderClient` 取得。
 *
 * `docs/ARCHITECTURE.md` §5.3 定義的 `GlassesGpsLocationProvider`：
 * 「A10 實測發現眼鏡有 `GPS_PROVIDER` → 手機層直接不需要」。Rokid Glasses
 * 目前確認沒有（`docs/DEVICE_FINDINGS.md` A10），但**用一般手機單機測試**
 * 或未來換到有 GPS 的裝置時，這支就是正確的實作 —— 不需要另外裝
 * 手機 companion、也不需要 Flask 中繼座標。
 *
 * [isAvailable] 同時檢查「裝置有沒有 GPS_PROVIDER」與「有沒有定位權限」，
 * 兩者缺一都會讓 DI 退回 [PhoneCompanionLocationProvider]，
 * 見 `AssistantModule.provideLocationProvider`。
 */
class GlassesGpsLocationProvider(
    private val context: Context,
    private val updateIntervalMillis: Long = 3_000L,
) : LocationProvider {

    private val locationManager: LocationManager
        get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(context) }

    override val isAvailable: Boolean
        get() = hasGpsProvider() && hasLocationPermission()

    // 精度由每筆 Location 自帶，這裡不預先假設一個固定值。
    override val accuracyMeters: Float? = null

    @SuppressLint("MissingPermission")
    override fun locations(): Flow<Coordinate> = callbackFlow {
        if (!isAvailable) {
            close()
            return@callbackFlow
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, updateIntervalMillis)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    trySend(
                        Coordinate(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracyMeters = location.accuracy,
                            timestampMillis = location.time,
                        ),
                    )
                }
            }
        }

        fusedClient.requestLocationUpdates(request, callback, context.mainLooper)

        awaitClose { fusedClient.removeLocationUpdates(callback) }
    }

    private fun hasGpsProvider(): Boolean =
        runCatching { locationManager.allProviders.contains(LocationManager.GPS_PROVIDER) }
            .getOrDefault(false)

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
