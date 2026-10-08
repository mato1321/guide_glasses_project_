package com.guideglasses

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 讓 App 在使用者沒盯著螢幕時仍然活著。
 *
 * ### 為什麼非要不可
 *
 * Rokid Glasses 上實測，App 一退到背景**2.4 秒**就被系統回收：
 *
 * ```
 * I ActivityManager: Killing 12242:com.guideglasses/u0a87 (adj 900): cached #1
 * ```
 *
 * `adj 900` 是 `CACHED_APP`。而導盲的使用情境**本來就是螢幕關著的** ——
 * 使用者戴著眼鏡走路，不會一直盯著那塊 480×398 的單色螢幕。
 * 沒有前景服務，這套系統在真實使用情境下等於不存在：
 *
 * | 功能 | 沒有前景服務時 |
 * |---|---|
 * | 障礙物偵測 | 行程被殺，而且更早之前就會撞上 `idle UID` 擋相機 |
 * | 語音播報 | 講到一半行程消失 |
 * | 感測器 | 一併停掉 |
 *
 * 順帶解掉另一個問題：Android 禁止 idle UID 開相機
 * （`Access Denial: can't use the camera from an idle UID`）。
 * 待在前景服務裡就不會進入 idle。
 *
 * ### 為什麼要在執行期算 foregroundServiceType
 *
 * Android 14 起，宣告 `camera` 型別的前景服務**必須**已經拿到 `CAMERA` 執行期權限，
 * 否則 `startForeground` 直接丟 `SecurityException`。但本專案的語音播報
 * 不需要相機也該能用，所以型別依「當下實際拿到哪些權限」決定 ——
 * 只給了麥克風就只宣告 `microphone`，什麼都沒有也還是要能活著播報。
 */
class GuideGlassesForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()
    }

    /**
     * `START_STICKY`：被系統殺掉後要自己回來。
     *
     * 眼鏡只有 2GB RAM，記憶體壓力下前景服務仍可能被回收 ——
     * 對導盲裝置而言「安靜地不再運作」是最糟的失效方式。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.foreground_service_title))
            .setContentText(getString(R.string.foreground_service_text))
            // 導盲提示本身就會出聲，通知不需要再叫一次。
            .setSilent(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        val type = currentServiceType()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            Log.e(TAG, "前景服務啟動失敗，App 退到背景後會被系統回收", e)
            stopSelf()
            return
        }

        verifyReallyForeground(type)
    }

    /**
     * 確認系統**真的**把我們登記成前景服務。
     *
     * 沒有例外不代表成功。眼鏡上實測過這個情形：
     *
     * ```
     * W ActivityManager: Service.startForeground() not allowed due to bg restriction:
     *                    service com.guideglasses/.GuideGlassesForegroundService
     * ```
     *
     * `startForeground()` 被系統**靜默拒絕** —— 不丟例外、不回傳值，
     * App 完全不知道自己還在 cached 狀態，退到背景照樣被殺。
     * 這是這台裝置反覆出現的模式（Rokid 的 `bindSecurityService`、
     * `pm list features` 的假宣告都是同一類），所以這裡主動查證。
     */
    private fun verifyReallyForeground(type: Int) {
        val isForeground = runningServiceIsForeground()

        if (isForeground) {
            Log.i(TAG, "前景服務已生效，type=$type")
            return
        }

        /*
         * 走到這裡代表 App 被背景限制擋住了。
         *
         * ⚠️ YodaOS 預設把**每一個**第三方 App 都設成背景限制
         * （`RUN_ANY_IN_BACKGROUND: ignore`，連 Google Maps 也是），
         * 而電池最佳化白名單解不了這個 —— 那是另一層限制。
         *
         * 佈建時要執行一次：
         *   adb shell cmd appops set com.guideglasses RUN_ANY_IN_BACKGROUND allow
         */
        Log.e(
            TAG,
            "🔴 前景服務被系統擋下（背景限制=${isBackgroundRestricted()}）。" +
                "螢幕關閉後 App 會被回收，相機與播報都會停止。" +
                "解法：adb shell cmd appops set $packageName RUN_ANY_IN_BACKGROUND allow",
        )
    }

    /**
     * 查自己的服務有沒有 `foreground` 旗標。
     *
     * `getRunningServices` 在 API 26 之後只回傳呼叫端自己的服務 ——
     * 對這個用途剛好夠用，而且是唯一能從 App 端查證的方式。
     */
    @Suppress("DEPRECATION")
    private fun runningServiceIsForeground(): Boolean =
        getSystemService(ActivityManager::class.java)
            ?.getRunningServices(Int.MAX_VALUE)
            ?.any { it.service.className == javaClass.name && it.foreground }
            ?: false

    private fun isBackgroundRestricted(): Boolean =
        getSystemService(ActivityManager::class.java)?.isBackgroundRestricted ?: false

    /** 依當下實際持有的權限決定型別，理由見類別註解。 */
    private fun currentServiceType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0

        var type = 0
        if (hasPermission(Manifest.permission.CAMERA)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return type
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.foreground_service_channel),
            // LOW：不出聲、不震動。使用者需要聽到的是導盲提示，不是通知音。
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.foreground_service_text)
            setShowBadge(false)
        }

        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "GuideService"
        private const val CHANNEL_ID = "guide_glasses_running"
        private const val NOTIFICATION_ID = 1

        /**
         * 啟動服務。重複呼叫是安全的 —— 已經在跑的話只會走一次
         * [onStartCommand]，不會重建。
         */
        fun start(context: Context) {
            val intent = Intent(context, GuideGlassesForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuideGlassesForegroundService::class.java))
        }
    }
}
