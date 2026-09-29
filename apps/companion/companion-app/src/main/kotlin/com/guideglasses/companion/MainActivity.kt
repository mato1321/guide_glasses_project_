package com.guideglasses.companion

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/**
 * 導盲眼鏡的手機 companion —— 只做一件事：把手機 GPS 座標回報給後端。
 *
 * 對應 `docs/ARCHITECTURE.md` §5.2「播報仲裁留在眼鏡」／§5.3
 * `PhoneCompanionLocationProvider`：這支 App **刻意不含**任何語音、地圖或
 * 導航決策邏輯 —— 那些全部留在眼鏡端的 `PlanBusRouteUseCase` /
 * `ConfirmBusUseCase`，這裡只當 GPS 感測器＋網路閘道。
 *
 * 舊原型 `GPS_phone` 會自己輪詢導航目標、自己開 Google Maps、自己判斷
 * 要不要重新導航；這支刻意拿掉那兩件事，避免手機和眼鏡各自對使用者
 * 講話、互相蓋台。
 */
class MainActivity : ComponentActivity() {

    private lateinit var statusView: TextView
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient()

    private var sendingStarted = false

    private val locationRunnable = object : Runnable {
        override fun run() {
            reportLocation()
            handler.postDelayed(this, REPORT_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusView = findViewById(R.id.statusView)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        if (BuildConfig.BUS_API_ENDPOINT.isBlank()) {
            statusView.text = "尚未設定後端位址\n請在 local.properties 加入\nguideglasses.${BuildConfig.FLAVOR}.busApiEndpoint"
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startSending()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST,
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != LOCATION_PERMISSION_REQUEST) return

        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startSending()
        } else {
            statusView.text = "定位權限遭拒絕，無法把手機位置提供給眼鏡"
        }
    }

    private fun startSending() {
        if (sendingStarted) return
        sendingStarted = true
        handler.post(locationRunnable)
    }

    @SuppressLint("MissingPermission")
    private fun reportLocation() {
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location == null) {
                    statusView.text = "尚未取得 GPS，請確認手機定位已開啟"
                    return@addOnSuccessListener
                }
                send(location.latitude, location.longitude)
            }
            .addOnFailureListener { e ->
                statusView.text = "GPS 取得失敗：${e.message}"
            }
    }

    private fun send(lat: Double, lng: Double) {
        val payload = JSONObject().apply {
            put("lat", lat)
            put("lng", lng)
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("${BuildConfig.BUS_API_ENDPOINT}/update-location")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    statusView.text = "座標：$lat, $lng\n傳送失敗：${e.message}"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val ok = response.isSuccessful
                response.close()
                runOnUiThread {
                    statusView.text = "座標：$lat, $lng\n" +
                        if (ok) "已送出到後端" else "後端回傳錯誤"
                }
            }
        })
    }

    override fun onDestroy() {
        handler.removeCallbacks(locationRunnable)
        sendingStarted = false
        super.onDestroy()
    }

    private companion object {
        const val LOCATION_PERMISSION_REQUEST = 100

        // 與眼鏡端 PhoneCompanionLocationProvider 的輪詢間隔搭配，
        // 5 秒回報一次對步行導航的更新頻率已經足夠，也比較省電。
        const val REPORT_INTERVAL_MS = 5_000L

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
