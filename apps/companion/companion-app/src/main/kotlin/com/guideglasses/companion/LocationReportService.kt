package com.guideglasses.companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 在背景持續把手機定位回報給後端（`POST /update-location`）。
 *
 * ### 為什麼要前景服務
 *
 * 以前回報迴圈掛在 [MainActivity] 上。實測（2026-09-30，小米 220333QL／Android 13）：
 * 手機螢幕一鎖，App 就被系統凍結（procState 15），後端再也收不到座標 ——
 * 而實際使用時手機是放在口袋裡的，眼鏡的步行導航會停在最後一個位置空等。
 * 定位型前景服務讓系統知道「使用者正在用它」，螢幕關掉也繼續跑。
 *
 * ### 為什麼改用 requestLocationUpdates
 *
 * 舊版每 5 秒讀一次 `lastLocation` —— 那只是「系統最後一次知道的位置」，
 * 沒有任何 App 在要定位時它不會更新，可能是好幾分鐘前的舊座標。
 * 現在主動要求每秒一筆高精度定位（企畫書 P1：回報間隔 5 秒 → 1 秒，附時間戳）。
 *
 * ### 為什麼是 START_NOT_STICKY
 *
 * 定位型前景服務只有在使用者開著 App 時啟動，才拿得到「使用中」的定位權限。
 * 被系統殺掉後自己重啟屬於背景啟動，拿不到定位，只會變成一個掛著通知卻不回報的殼 ——
 * 比乾脆停掉更容易誤導。停了就讓使用者重新打開 App。
 */
class LocationReportService : Service() {

    private lateinit var fusedLocation: FusedLocationProviderClient

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    /** 上一筆還沒送完就丟掉這一筆 —— 網路慢時不讓請求越排越長，永遠送最新的位置。 */
    private val sending = AtomicBoolean(false)

    private var updatesRequested = false
    private var sentCount = 0

    /** 最近一筆定位，眼鏡直連時由 [localServer] 回給眼鏡。 */
    @Volatile
    private var latestFix: LocationHttpResponder.Fix? = null

    /**
     * 眼鏡直連用的區網伺服器，見 [LocalLocationServer]。埠依 flavor 不同
     * （Cloudflare 8765、AWS 8766），兩版同時開在同一支手機上也不會搶。
     */
    private val localServer = LocalLocationServer(
        port = BuildConfig.GLASSES_DIRECT_PORT,
        apiKey = BuildConfig.API_KEY,
        latestFix = { latestFix },
        // 眼鏡直連時順便告訴眼鏡後端網址，眼鏡就跟著換（見 BackendUrlStore）。
        backendUrl = { backend.effective() },
    )
    private var localServerStarted = false

    /** 後端網址：畫面上設定過的，否則建置時的。每次送出都重新讀，改了不必重開服務。 */
    private val backend by lazy { BackendUrlStore(this) }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            latestFix = LocationHttpResponder.Fix(
                lat = location.latitude,
                lng = location.longitude,
                accuracyM = location.accuracy,
                elapsedRealtimeNanos = location.elapsedRealtimeNanos,
            )
            if (backend.effective().isBlank()) {
                statusText.value = "未設定後端，只提供眼鏡直連\n${directStatus()}"
            } else {
                send(location)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!localServerStarted) localServerStarted = localServer.start()
        startLocationUpdates()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (updatesRequested) fusedLocation.removeLocationUpdates(callback)
        updatesRequested = false
        localServer.stop()
        localServerStarted = false
        running.value = false
        statusText.value = "已停止回報位置"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun enterForeground(): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            running.value = true
            true
        } catch (e: Exception) {
            // 最常見的是沒有定位權限（SecurityException），或不是從畫面啟動
            // （ForegroundServiceStartNotAllowedException）。
            Log.e(TAG, "前景服務啟動失敗", e)
            statusText.value = "無法在背景回報位置：${e.javaClass.simpleName}\n請打開 App 重試"
            false
        }
    }

    @SuppressLint("MissingPermission") // 下面先檢查了權限
    private fun startLocationUpdates() {
        if (updatesRequested) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            statusText.value = "沒有定位權限，無法把手機位置提供給眼鏡"
            stopSelf()
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, REPORT_INTERVAL_MS)
            .setMinUpdateIntervalMillis(REPORT_INTERVAL_MS)
            .build()
        fusedLocation.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { e -> statusText.value = "GPS 取得失敗：${e.message}" }
        updatesRequested = true
        statusText.value = "正在取得 GPS⋯（在室內可能要等一下）"
    }

    private fun directStatus(): String =
        if (localServerStarted) {
            "眼鏡直連（port ${BuildConfig.GLASSES_DIRECT_PORT}）已提供 ${localServer.servedCount.get()} 次"
        } else {
            "眼鏡直連未啟動（port ${BuildConfig.GLASSES_DIRECT_PORT} 可能被占用），眼鏡只能經由後端取得位置"
        }

    private fun send(location: Location) {
        if (!sending.compareAndSet(false, true)) return

        val payload = JSONObject().apply {
            put("lat", location.latitude)
            put("lng", location.longitude)
            put("accuracy_m", location.accuracy.toDouble())
            // 這筆定位「現在」多舊。用開機時間（elapsedRealtime）算，不用牆上時鐘 ——
            // 眼鏡的時鐘實測快了 4 小時多，跨裝置比對時間一定會錯。後端再加上
            // 自己持有的時間，眼鏡據此丟掉過舊的座標（PhoneCompanionLocationProvider）。
            put("fix_age_ms", fixAgeMillis(location))
            put("fix_time_ms", location.time)
        }
        val request = Request.Builder()
            .url("${backend.effective()}/update-location")
            // 後端除了 /health 一律驗證共用金鑰，沒帶會回 401（見 backend/app/main.py）。
            .apply { if (BuildConfig.API_KEY.isNotBlank()) header("X-Api-Key", BuildConfig.API_KEY) }
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                sending.set(false)
                statusText.value = "取得位置，但傳送到後端失敗：${e.message}\n${directStatus()}"
            }

            override fun onResponse(call: Call, response: Response) {
                val ok = response.isSuccessful
                response.close()
                sending.set(false)
                if (ok) sentCount++
                val backend = if (ok) {
                    "已送出 $sentCount 筆位置到後端\n" +
                        "最近一筆 ${TIME_FORMAT.format(Date())}，精度約 ${location.accuracy.toInt()} 公尺"
                } else when (response.code) {
                    401 -> "後端拒絕：金鑰錯誤\n請確認 guideglasses.${BuildConfig.FLAVOR}.apiKey 與後端的 GUIDEGLASSES_API_KEY 相同"
                    503 -> "後端還沒設定金鑰（GUIDEGLASSES_API_KEY）"
                    else -> "後端回傳錯誤：HTTP ${response.code}"
                }
                statusText.value = "$backend\n${directStatus()}"
            }
        })
    }

    private fun fixAgeMillis(location: Location): Long =
        ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0)

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle(getString(R.string.app_name))
        .setContentText("正在把手機位置提供給導盲眼鏡")
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .addAction(
            0,
            "停止",
            PendingIntent.getService(
                this, 1, Intent(this, LocationReportService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "定位轉傳", NotificationManager.IMPORTANCE_LOW).apply {
            description = "把手機位置提供給導盲眼鏡"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "LocationReport"
        private const val CHANNEL_ID = "location_report"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.guideglasses.companion.action.STOP"

        /**
         * 每秒一筆（企畫書 P1）。眼鏡在 20 公尺內就要講轉彎，步行 1.4 m/s 下，
         * 5 秒回報一次加上輪詢延遲，可能直接走過轉彎點才收到。
         */
        private const val REPORT_INTERVAL_MS = 1_000L

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.TAIWAN)

        private val running = MutableStateFlow(false)
        private val statusText = MutableStateFlow("尚未開始")

        /** 服務是否在跑，給畫面決定按鈕要顯示「開始」還是「停止」。 */
        val isRunning: StateFlow<Boolean> = running.asStateFlow()

        /** 給畫面顯示的最新狀態。 */
        val status: StateFlow<String> = statusText.asStateFlow()

        /** 必須在使用者看得到 App 畫面時呼叫，定位型前景服務才拿得到定位權限。 */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LocationReportService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationReportService::class.java))
        }
    }
}
