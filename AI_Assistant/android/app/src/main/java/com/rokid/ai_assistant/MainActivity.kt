package com.example.rokidglasses_project

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import android.widget.TextView
import android.widget.EditText
import android.widget.Button
import androidx.lifecycle.lifecycleScope
import com.example.rokidglasses_project.network.ApiClient
import com.example.rokidglasses_project.network.ChatRequest
import kotlinx.coroutines.*
import android.content.pm.PackageManager
import com.example.rokidglasses_project.audio.AudioRecorder
import com.example.rokidglasses_project.audio.AudioPlayer
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import android.Manifest
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import android.util.Base64
private const val REQ_RECORD_AUDIO = 1001
class MainActivity : AppCompatActivity() {

    private lateinit var tvReply: TextView
    private lateinit var btnStartRecord: Button
    private lateinit var btnStopRecord: Button

    private lateinit var audioRecorder: AudioRecorder
    private var recordingFile: File?=null
    private var isRecording = false

    private lateinit var audioPlayer: AudioPlayer


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        // 初始化 Views
        tvReply = findViewById(R.id.tvReply)
        btnStartRecord = findViewById(R.id.btnMic)
        btnStopRecord = findViewById(R.id.btnStopRecord)

        // 初始化錄音器
        audioRecorder = AudioRecorder(this)

        audioPlayer = AudioPlayer(this)

        // 設置按鈕
        setupButtons()

        // 初始顯示
        tvReply.text = "等待輸入或語音..."



    }


    private fun setupButtons(){
        //開始錄音按鈕
        btnStartRecord.text = "開始錄音"

        btnStartRecord.setOnClickListener{
            if(!ensureAudioPermission()){
                tvReply.text = "❌ 需要麥克風權限"
                return@setOnClickListener
            }

            if (isRecording) {
                tvReply.text = "⚠️ 已經在錄音中"
                return@setOnClickListener
            }

            try {
                recordingFile = audioRecorder.startRecording()
                isRecording = true
                tvReply.text = "🎤 錄音中...\n請對著手機說話"

                // 更新按鈕狀態
                btnStartRecord.isEnabled = false
                btnStopRecord.isEnabled = true

            } catch (e: Exception) {
                tvReply.text = "❌ 開始錄音失敗: ${e.message}"
                isRecording = false
            }
        }


        // 停止錄音按鈕
        btnStopRecord.text = "停止錄音並上傳"
        btnStopRecord.isEnabled = false

        btnStopRecord.setOnClickListener {

            if(!isRecording){
                tvReply.text = "沒有在錄音"
            }

            try {
                val file = audioRecorder.stopRecording()
                isRecording = false

                //更新按鈕狀態
                btnStartRecord.isEnabled = true
                btnStopRecord.isEnabled = false

                if(file == null || !file.exists()){
                    tvReply.text = "錄音檔不存在"
                    return@setOnClickListener
                }

                if (file.length() == 0L) {
                    tvReply.text = "❌ 錄音檔為空（可能沒有收到聲音）"
                    return@setOnClickListener
                }
                tvReply.text = "⏳ 錄音完成，上傳中...\n檔案大小: ${file.length() / 1024}KB"
                uploadAudioToFastApi(file)
            }catch (e: Exception){
                tvReply.text = "❌ 停止錄音失敗: ${e.message}"
                isRecording = false
                btnStartRecord.isEnabled = true
                btnStopRecord.isEnabled = false
            }
        }
    }


    private fun ensureAudioPermission(): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQ_RECORD_AUDIO
            )
        }
        return granted
    }



    private fun uploadAudioToFastApi(file: File){
        lifecycleScope.launch {


            try {
                // 1) 包裝成 multipart
                val requestBody = file.asRequestBody("audio/*".toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("file", file.name, requestBody)


                // 2) 呼叫 API
                val response = ApiClient.api.chatAudio(part)

                // 3) 檢查錯誤
                if (!response.isSuccessful) {
                    tvReply.text = "❌ 後端錯誤:\n${response.code()}"
                    return@launch
                }
                // 從 header 取得文字
                val userTextEncoded = response.headers()["X-User-Text"] ?: "無法取得"
                val replyTextEncoded = response.headers()["X-Reply-Text"] ?: "無法取得"

                val userText = userTextEncoded?.let {
                    String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8)
                } ?: "無法取得"

                val replyText = replyTextEncoded?.let {
                    String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8)
                } ?: "無法取得"

                // 取得音檔數據
                val audioBytes = response.body()?.bytes()

                if (audioBytes == null || audioBytes.isEmpty()) {
                    tvReply.text = "❌ 未收到音檔\n\n請重試"
                    return@launch
                }

                // 4) 顯示結果
                val resultText = """
                    🎤 你說：
                    $userText
                    
                    ━━━━━━━━━━━━━━━━
                    
                    💬 AI 回覆：
                    $replyText
                    
                    ━━━━━━━━━━━━━━━━
                    
                    🔊 正在播放語音...
                """.trimIndent()

                tvReply.text = resultText

                // 播放音檔
                audioPlayer.play(
                    audioData = audioBytes,
                    onComplete = {
                        runOnUiThread {
                            tvReply.text = resultText.replace(
                                "🔊 正在播放語音...",
                                "✅ 播放完成！可以繼續提問"
                            )
                        }
                    },
                    onError = { error ->
                        runOnUiThread {
                            tvReply.text = "$resultText\n\n❌ 播放失敗: $error"
                        }
                    }
                )
            } catch (e: Exception) {
                tvReply.text = """
                    ❌ 處理失敗
                    
                    錯誤：${e.message}
                    
                    請檢查網路連線和 FastAPI
                """.trimIndent()

                e.printStackTrace()
            }

        }
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if(requestCode == REQ_RECORD_AUDIO){
            val ok = grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
            tvReply.text = if (ok) {
                "✅ 麥克風權限已允許\n請按「開始錄音」"
            } else {
                "❌ 需要麥克風權限才能語音輸入"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::audioRecorder.isInitialized) {
            audioRecorder.release()

        }
    }
}

