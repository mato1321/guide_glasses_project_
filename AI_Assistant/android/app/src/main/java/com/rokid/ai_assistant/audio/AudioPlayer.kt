package com.example.rokidglasses_project.audio

import android.content.Context
import android.media.MediaPlayer
import com.example.rokidglasses_project.network.ChatAudioResponse
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class AudioPlayer (private val context: Context){

    private val TAG = "AudioPlayer"
    private var mediaPlayer: MediaPlayer? = null

    /**
     * 播放音檔
     * @param audioData 音檔的 ByteArray
     * @param onComplete 播放完成的回調
     * @param onError 播放錯誤的回調
     */

    fun play(
        audioData: ByteArray,
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {}
    ){
        try {
            // 釋放舊的 MediaPlayer
            release()

            // 儲存到臨時檔案
            val tempFile = File(context.cacheDir, "temp_tts_${System.currentTimeMillis()}.mp3")
            FileOutputStream(tempFile).use { fos ->
                fos.write(audioData)
            }

            Log.i(TAG, "開始播放音檔: ${tempFile.absolutePath}, 大小: ${audioData.size} bytes")

            // 建立 MediaPlayer
            mediaPlayer = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()

                setOnCompletionListener {
                    Log.i(TAG, "播放完成")
                    release()
                    tempFile.delete()
                    onComplete()
                }

                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "播放錯誤: what=$what, extra=$extra")
                    onError("播放錯誤: $what")
                    release()
                    tempFile.delete()
                    true
                }

                start()
            }

        } catch (e: Exception) {
            Log.e(TAG, "播放失敗", e)
            onError("播放失敗: ${e.message}")
        }
    }


    /**
     * 停止播放
     */
    fun stop() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "停止播放失敗", e)
        }
    }

    /**
     * 釋放資源
     */
    fun release() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "釋放資源失敗", e)
        }
    }

    /**
     * 是否正在播放
     */
    fun isPlaying(): Boolean {
        return try {
            mediaPlayer?.isPlaying ?: false
        } catch (e: Exception) {
            false
        }
    }


}