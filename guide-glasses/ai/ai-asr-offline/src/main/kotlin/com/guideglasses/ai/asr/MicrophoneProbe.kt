package com.guideglasses.ai.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.abs

/**
 * 逐一嘗試每一種音訊來源，回報各自實際收到的音量。
 *
 * ### 為什麼需要這個
 *
 * 眼鏡上用 `VOICE_RECOGNITION` 錄音時**收到的全是 0**，而權限、appop、
 * 麥克風佔用、全域靜音、行程狀態（TOP）全部正常，換成 16-bit 也一樣。
 * 也就是說錄音「成功」了，只是沒有聲音 —— 又是這台裝置的招牌失敗方式：
 * **不報錯，只是靜靜地什麼都不給**。
 *
 * 這種情況下唯一的辦法是把每個音訊來源都試一遍，看哪一個真的有訊號。
 * 廠商只實作部分來源是很常見的事，尤其是這種客製化的 Android。
 *
 * 用法：
 * ```bash
 * adb shell am broadcast -a com.guideglasses.DEBUG --es cmd MIC_TEST
 * adb logcat -d | grep MicProbe
 * ```
 *
 * **測試時請對著眼鏡持續說話**，否則每個來源都會是靜音，測不出差別。
 */
object MicrophoneProbe {

    private const val TAG = "MicProbe"
    private const val SAMPLE_RATE = 16_000
    private const val PROBE_MILLIS = 1_500L
    private const val SHORT_FULL_SCALE = 32768f

    /** 依「導盲情境下的偏好」排序：前面的有降噪與 AGC，比較適合戶外。 */
    private val SOURCES = listOf(
        "VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
        "MIC" to MediaRecorder.AudioSource.MIC,
        "DEFAULT" to MediaRecorder.AudioSource.DEFAULT,
        "VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        "CAMCORDER" to MediaRecorder.AudioSource.CAMCORDER,
        "UNPROCESSED" to MediaRecorder.AudioSource.UNPROCESSED,
    )

    /** 逐一試，把每個來源的峰值印進 log。呼叫端要確保已有錄音權限。 */
    fun runAll() {
        Log.i(TAG, "開始探測 ${SOURCES.size} 種音訊來源，請持續說話⋯")
        val results = SOURCES.map { (name, source) -> name to probe(name, source) }

        val best = results.filter { it.second > 0f }.maxByOrNull { it.second }
        if (best == null) {
            Log.e(TAG, "🔴 全部音訊來源都是靜音 —— 這台裝置的麥克風擷取可能整個不可用")
        } else {
            Log.i(TAG, "✅ 最佳來源：${best.first}（峰值 ${"%.4f".format(best.second)}）")
        }
    }

    @SuppressLint("MissingPermission") // 呼叫端已確認權限
    private fun probe(name: String, source: Int): Float {
        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBytes <= 0) {
            Log.w(TAG, "$name：取不到緩衝區大小")
            return 0f
        }

        var record: AudioRecord? = null
        return try {
            record = AudioRecord(
                source,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBytes * 2,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "$name：初始化失敗")
                return 0f
            }

            record.startRecording()
            val buffer = ShortArray(SAMPLE_RATE / 10)
            var peak = 0f
            val until = System.currentTimeMillis() + PROBE_MILLIS

            while (System.currentTimeMillis() < until) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                for (i in 0 until read) {
                    peak = maxOf(peak, abs(buffer[i] / SHORT_FULL_SCALE))
                }
            }

            Log.i(TAG, "$name：峰值 ${"%.4f".format(peak)}${if (peak == 0f) "  ← 靜音" else ""}")
            peak
        } catch (e: Exception) {
            Log.w(TAG, "$name：例外 ${e.message}")
            0f
        } finally {
            runCatching {
                record?.stop()
                record?.release()
            }
        }
    }
}
