package com.guideglasses.ai.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.guideglasses.core.domain.navigation.Coordinate
import com.guideglasses.core.domain.navigation.LocationProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 裝置本身的 GPS，直接向系統的 `LocationManager` 要 `GPS_PROVIDER`。
 *
 * `docs/ARCHITECTURE.md` §5.3 定義的 `GlassesGpsLocationProvider`：
 * 「A10 實測發現眼鏡有 `GPS_PROVIDER` → 手機層直接不需要」。Rokid Glasses
 * 目前確認沒有（`docs/DEVICE_FINDINGS.md` A10），但**用一般手機單機測試**
 * 或未來換到有 GPS 的裝置時，這支就是正確的實作 —— 不需要另外裝
 * 手機 companion、也不需要後端中繼座標。
 *
 * ### 為什麼不用 FusedLocationProviderClient
 *
 * Fused 是 Google Play Services 的一部分，而眼鏡**沒有** Play Services
 * （`DEVICE_FINDINGS` §2）：呼叫不會丟例外，只會回一個失敗的 Task，
 * 結果就是永遠收不到座標、而且沒有任何錯誤訊息。這個模組會被打包進
 * 眼鏡 APK，所以只能用系統內建的 API。`play-services-location`
 * 只允許出現在手機 companion-app。
 *
 * 用 `LocationManagerCompat` 而不是直接傳 lambda 給 `LocationManager`：
 * API 29 以下的 `LocationListener` 還沒有 `onStatusChanged` 等方法的預設實作，
 * lambda 在系統回呼那些方法時會丟 `AbstractMethodError`。
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

        val request = LocationRequestCompat.Builder(updateIntervalMillis)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .build()

        val listener = LocationListenerCompat { location ->
            trySend(
                Coordinate(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracy,
                    timestampMillis = location.time,
                ),
            )
        }

        LocationManagerCompat.requestLocationUpdates(
            locationManager,
            LocationManager.GPS_PROVIDER,
            request,
            listener,
            context.mainLooper,
        )

        awaitClose { LocationManagerCompat.removeUpdates(locationManager, listener) }
    }

    private fun hasGpsProvider(): Boolean =
        runCatching { locationManager.allProviders.contains(LocationManager.GPS_PROVIDER) }
            .getOrDefault(false)

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
