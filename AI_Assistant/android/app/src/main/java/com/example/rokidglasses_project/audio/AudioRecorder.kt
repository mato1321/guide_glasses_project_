package com.example.rokidglasses_project.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import retrofit2.http.Tag
import java.io.File
import android.util.Log


class AudioRecorder (private val context: Context){

    private var recorder: MediaRecorder?=null
    private var outputFile: File?=null
    private val TAG =  "AudioRecorder"


    /**
     * 開始錄音
     * @return 錄音檔案
     */


    fun startRecording(): File{


        outputFile = File(context.cacheDir, "recording_${System.currentTimeMillis()}.m4a")

        Log.i(TAG, "開始錄音: ${outputFile!!.absolutePath}")


        recorder = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        }else{
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(outputFile!!.absolutePath)
            prepare()
            start()
        }

        return outputFile!!

    }

    /**
     * 停止錄音
     * @return 錄音檔案（如果成功）
     */
    fun stopRecording(): File? {
        return try {
            recorder?.apply {
                stop()
                release()
            }
            recorder = null

            if (outputFile?.exists() == true && outputFile!!.length() > 0) {
                Log.i(TAG, "錄音完成: ${outputFile!!.absolutePath}, 大小: ${outputFile!!.length()} bytes")
                outputFile
            } else {
                Log.e(TAG, "錄音檔不存在或檔案為空")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "停止錄音失敗", e)
            recorder?.release()
            recorder = null
            null
        }
    }

    /**
     * 釋放資源
     */
    fun release() {
        try {
            recorder?.release()
            recorder = null
        } catch (e: Exception) {
            Log.e(TAG, "釋放錄音器失敗", e)
        }
    }
}