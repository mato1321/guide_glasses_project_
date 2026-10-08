package com.guideglasses

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * 播報音量開到最大。
 *
 * 播報與提示音走「導航語音」（`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`），對應的是媒體音量。
 * 以前走無障礙串流（`STREAM_ACCESSIBILITY`），但眼鏡上那條固定 8/15，而且只有無障礙服務
 * 能調 —— adb 與 App 都改不動（2026-09-30 實測），播報最大就只有一半音量。
 *
 * 每次開 App、回到畫面都拉回最大：導盲播報聽不清楚比太大聲危險。
 */
internal object AnnouncementVolume {

    private const val TAG = "AnnouncementVolume"

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
