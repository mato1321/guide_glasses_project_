package com.guideglasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 播報音量開到最大。
 *
 * 播報與提示音走「導航語音」（`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`），對應的是媒體音量。
 * 以前走無障礙串流（`STREAM_ACCESSIBILITY`），但眼鏡上那條固定 8/15，而且只有無障礙服務
 * 能調 —— adb 與 App 都改不動（2026-09-30 實測），播報最大就只有一半音量。
 *
 * 每次開 App、回到畫面都拉回最大；導盲模式運作中（[holdAtMax]）被調小也會馬上拉回：
 * 導盲播報聽不清楚比太大聲危險。
 */
internal object AnnouncementVolume {

    private const val TAG = "AnnouncementVolume"

    /**
     * 系統每次改音量都會發的廣播（`AudioManager.VOLUME_CHANGED_ACTION`，隱藏 API，
     * 沒有公開常數可用）。是 protected broadcast，只有系統發得出來。
     */
    private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"

    /**
     * 鎖住最大音量：之後只要被調小（按音量鍵、別的 App 改）就馬上拉回去，
     * 直到回傳的 [AutoCloseable] 被關掉。
     *
     * 由前景服務持有 —— 只在導盲模式運作中鎖，App 關掉或別的版本接管
     * （[SensorHandoff]）就放開，不影響眼鏡上的其他 App。
     *
     * 不看廣播帶的是哪一條串流，一律重查媒體音量：已經是最大就什麼都不做，
     * 所以自己拉回最大時觸發的那一次廣播也不會造成迴圈。
     */
    fun holdAtMax(context: Context): AutoCloseable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = maximize(context)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_VOLUME_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        maximize(context)
        return AutoCloseable { context.unregisterReceiver(receiver) }
    }

    fun maximize(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val stream = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream)
        val before = audio.getStreamVolume(stream)
        if (before == max) return

        runCatching { audio.setStreamVolume(stream, max, 0) }
            .onFailure { Log.w(TAG, "無法調整播報音量", it) }
        Log.i(TAG, "播報音量 $before → ${audio.getStreamVolume(stream)}/$max")
    }
}
