package com.guideglasses.companion

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

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
 *
 * 實際回報在 [LocationReportService]（定位型前景服務），這個畫面只負責
 * 要權限、開始／停止、顯示狀態 —— 手機放進口袋、螢幕關掉之後回報仍會繼續。
 */
class MainActivity : ComponentActivity() {

    private lateinit var statusView: TextView
    private lateinit var toggleButton: Button

    /**
     * 定位與通知一起要。通知權限（Android 13+）被拒絕時服務照樣會跑，
     * 只是通知列看不到 —— 所以只有定位是必要的。
     */
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // 看實際狀態而不是這次的結果 —— 只補要通知權限時，結果裡根本沒有定位那一項。
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            if (!LocationReportService.isRunning.value) LocationReportService.start(this)
        } else {
            statusView.text = "定位權限遭拒絕，無法把手機位置提供給眼鏡"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusView = findViewById(R.id.statusView)
        toggleButton = findViewById(R.id.toggleButton)

        // 沒有後端位址也能用：眼鏡可以透過熱點直接跟手機拿位置（LocalLocationServer）。
        // 只是公車、步行路線這些要後端的功能不能用，所以只提醒、不擋。
        if (BuildConfig.BUS_API_ENDPOINT.isBlank()) {
            statusView.text = "尚未設定後端位址，只能提供眼鏡直連\n" +
                "（要用公車與路線功能，請在 local.properties 加入 guideglasses.${BuildConfig.FLAVOR}.busApiEndpoint）"
        }

        toggleButton.setOnClickListener {
            if (LocationReportService.isRunning.value) {
                LocationReportService.stop(this)
            } else {
                startReporting()
            }
        }
        observeService()

        // 打開 App 就開始回報，跟以前一樣 —— 使用者多半看不到畫面，不該要他再按一次。
        if (!LocationReportService.isRunning.value) startReporting()
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startReporting() {
        val missing = requiredPermissions().filterNot(::hasPermission)
        if (Manifest.permission.ACCESS_FINE_LOCATION in missing) {
            requestPermissions.launch(missing.toTypedArray())
        } else {
            LocationReportService.start(this)
            if (missing.isNotEmpty()) requestPermissions.launch(missing.toTypedArray())
        }
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun observeService() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { LocationReportService.status.collect { statusView.text = it } }
                launch {
                    LocationReportService.isRunning.collect { running ->
                        toggleButton.text = if (running) "停止回報" else "開始回報"
                    }
                }
            }
        }
    }
}
