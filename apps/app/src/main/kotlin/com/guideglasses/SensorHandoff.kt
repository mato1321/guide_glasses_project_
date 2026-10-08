package com.guideglasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 多個版本同時裝在眼鏡上時，相機與麥克風的交接。
 *
 * ### 規則
 *
 * **使用者叫到前景的那一版接管，其他版本自己釋放。** 接管的一方在
 * [MainActivity] 建立時呼叫 [claim]；其他版本收到後：
 *
 * 1. 停止播報（不然會跟新的那一版同時講話）
 * 2. 停掉前景服務（它是 `START_STICKY`，不主動停掉的話被殺之後還會自己回來再搶一次）
 * 3. 關閉 [MainActivity] —— ViewModel 跟著清掉，喚醒監聽、持續偵測、導航的 job
 *    全部取消，`AudioRecord` 與 CameraX 在各自的 `finally`／`awaitClose` 釋放
 *
 * ### 為什麼要自己交接
 *
 * Android 不會替我們排解，而且失敗時都不報錯：
 *
 * | | 被別的 App 搶走時 |
 * |---|---|
 * | 相機 | 背景那一版的 CameraX 進入錯誤狀態 |
 * | 麥克風 | 背景那一版被**靜音** —— `AudioRecord.read()` 照常回傳，只是全部是 0 |
 *
 * 對看不見畫面的使用者，這兩種都是「講了沒反應」，而且完全不知道為什麼。
 *
 * ### 為什麼用 signature 權限保護
 *
 * 收到這個廣播就會關掉導盲功能。沒有保護的話，眼鏡上任何一個 App 發一個廣播，
 * 就能讓使用者在走路時失去避障提示。[PERMISSION] 是 `protectionLevel="signature"`，
 * 只有用同一把共用簽章（`keystore.properties`）簽的版本能互相通知 ——
 * 發送端要持有它，接收端也要持有它。
 *
 * ⚠️ 端側版 v1（`com.guideglasses`）是另一把簽章、也沒有這段程式碼，**不會參與交接**。
 * 開連網版之前要先把它關掉，否則兩邊會搶麥克風。
 */
object SensorHandoff {

    const val PERMISSION = "com.guideglasses.permission.SENSOR_HANDOFF"

    private const val ACTION_CLAIM = "com.guideglasses.action.CLAIM_SENSORS"
    private const val EXTRA_CLAIMANT = "claimant"
    private const val TAG = "SensorHandoff"

    private val releaseEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /**
     * 別的版本接管了，值是接管者的套件名。
     *
     * [MainActivity] 收到就關閉自己。沒有 Activity 在收（只剩前景服務）時事件直接丟掉 ——
     * 那時也沒有 ViewModel 在用麥克風或相機，只需要 [register] 的回呼停掉服務。
     */
    val releaseRequests: SharedFlow<String> = releaseEvents.asSharedFlow()

    /** 宣告接管相機與麥克風，通知其他版本釋放。 */
    fun claim(context: Context) {
        Log.i(TAG, "${context.packageName} 接管相機與麥克風，通知其他版本釋放")
        context.sendBroadcast(
            Intent(ACTION_CLAIM).putExtra(EXTRA_CLAIMANT, context.packageName),
            PERMISSION,
        )
    }

    /**
     * 開始接收其他版本的接管通知。在 [android.app.Application.onCreate] 呼叫一次 ——
     * 行程活著（前景服務還在）就收得到，不依賴畫面是否開著。
     *
     * @param onRelease 別的版本接管時要做的釋放工作（停止播報、停掉前景服務）。
     */
    fun register(context: Context, onRelease: (claimant: String) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val claimant = intent.getStringExtra(EXTRA_CLAIMANT) ?: return
                if (claimant == context.packageName) return

                Log.i(TAG, "$claimant 接管了，釋放相機與麥克風")
                onRelease(claimant)
                releaseEvents.tryEmit(claimant)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_CLAIM),
            // 只接受持有 signature 權限的發送者，見類別說明。
            PERMISSION,
            null,
            // 發送者是另一個 App（另一個版本），必須 exported。
            ContextCompat.RECEIVER_EXPORTED,
        )
    }
}
