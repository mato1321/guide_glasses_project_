package com.example.rokidglasses_project.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.rokidglasses_project.R
import com.example.rokidglasses_project.network.ApiClient
import com.example.rokidglasses_project.network.RecognizeResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import retrofit2.Response
import androidx.camera.core.resolutionselector.*
import android.util.Size
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf

class FaceRecognitionFragment : Fragment(), TextToSpeech.OnInitListener {

    private lateinit var previewView: PreviewView
    private lateinit var tvResult: TextView
    private lateinit var btnBackToChat: Button

    private lateinit var tts: TextToSpeech
    private var cameraExecutor: ExecutorService? = null

    // 自動偵測（每 5 秒）
    private val handler = Handler(Looper.getMainLooper())
    private val detectIntervalMs = 3000L

    // 防止同時送多個請求
    private val isProcessing = AtomicBoolean(false)
    private val shouldCaptureNextFrame = AtomicBoolean(false)

    // TTS 播報冷卻（10 秒）
    private var lastAnnounceName = ""
    private var lastAnnounceTime = 0L
    private val announceCooldown = 10000L

    private var ttsReady = false
    private var currentMediaPlayer: MediaPlayer? = null

    // 【關鍵】回調給 MainActivity - 當用戶想返回 AI 對話時
    var onBackToChatRequested: (() -> Unit)? = null

    private val TAG = "FaceRecognition"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_face_recognition, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 初始化 UI
        previewView = view.findViewById(R.id.previewView) // 相機預覽
        tvResult = view.findViewById(R.id.tvResult) // 結果文字
        btnBackToChat = view.findViewById(R.id.btnBackToChat) // 返回按鈕

        btnBackToChat.setOnClickListener {

            // 要顯示並播放的訊息
            val message = "已回到 AI 助理功能，可以繼續提問"
            // 傳回給 ChatFragment（key = "from_face"）
            parentFragmentManager.setFragmentResult("from_face", bundleOf(
                "message" to message,
                "playTts" to true   // 是否要播放 TTS（ChatFragment 會讀取此欄位）
            ))
            onBackToChatRequested?.invoke()
        }

        // 初始化 TTS 和相機執行器
        tts = TextToSpeech(requireContext(), this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // 【改進】檢查相機權限（新方法）
        handler.postDelayed({
            Log.d(TAG, "⏰ 延遲後開始檢查權限")
            checkAndRequestCameraPermission()// 新增一個檢查函數
        }, 500)  // 500 毫秒延遲
    }


    private fun ensureCameraExecutor() {
        try {
            if (cameraExecutor == null || cameraExecutor?.isShutdown == true) {
                cameraExecutor = Executors.newSingleThreadExecutor()
                Log.d(TAG, "cameraExecutor created/re-created")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ensureCameraExecutor failed", e)
        }
    }

    // 【新增】權限檢查函數
    private fun checkAndRequestCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                // 已有權限
                Log.d(TAG, "✅ 已有相機權限")
                startCamera()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                // 用戶之前拒絕過，顯示說明
                Log.w(TAG, "⚠️ 用戶之前拒絕過相機權限")
                showPermissionRationaleDialog()
            }
            else -> {
                // 第一次請求
                Log.d(TAG, "📋 首次請求相機權限")
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }
    // 【新增】顯示權限說明對話框
    private fun showPermissionRationaleDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("需要相機權限")
            .setMessage("人臉辨識功能需要使用您的相機。請在接下來的對話框中允許權限。")
            .setPositiveButton("允許") { _, _ ->
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            .setNegativeButton("取消") { _, _ ->
                tvResult.text = "❌ 被用戶拒絕"
                Toast.makeText(requireContext(), "人臉辨識功能已停用", Toast.LENGTH_LONG).show()
            }
            .show()
    }

    // ===== 相機初始化 =====

    private fun startCamera() {
        ensureCameraExecutor()
        val executor = cameraExecutor ?: Executors.newSingleThreadExecutor().also { cameraExecutor = it }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // 預覽配置
            val preview = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(
                            AspectRatioStrategy(
                                AspectRatio.RATIO_4_3,
                                AspectRatioStrategy.FALLBACK_RULE_AUTO
                            )
                        )
                        .build()
                )
                .build()
                .also { it.setSurfaceProvider(previewView.getSurfaceProvider()) }

            // 圖像分析配置（用於臉部偵測）
            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(
                            AspectRatioStrategy(
                                AspectRatio.RATIO_4_3,
                                AspectRatioStrategy.FALLBACK_RULE_AUTO
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                try {
                    // 【🔑 記憶體優化】只有需要時才轉檔成 JPEG
                    if (shouldCaptureNextFrame.compareAndSet(true, false)) {
                        val bytes = rgbaToJpeg(imageProxy)
                        if (bytes != null) {
                            sendToServer(bytes)
                        } else {
                            isProcessing.set(false)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "影像轉換失敗：${e.message}")
                    isProcessing.set(false)
                } finally {
                    imageProxy.close()
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )
                startAutoDetection()
            } catch (e: Exception) {
                tvResult.text = "相機啟動失敗"
                Log.e(TAG, "相機失敗", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // ===== RGBA to JPEG 轉換 =====

    private fun rgbaToJpeg(imageProxy: ImageProxy): ByteArray? {
        return try {
            val plane = imageProxy.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val width = imageProxy.width
            val height = imageProxy.height

            // 1️⃣ 創建 Bitmap（ARGB_8888 格式）
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            if (rowStride == width * pixelStride) {
                // 快速路徑：內存布局連續
                buffer.rewind()
                bitmap.copyPixelsFromBuffer(buffer)
            } else {
                // 慢速路徑：需要逐行處理
                val rowBuffer = ByteArray(rowStride)
                val pixels = IntArray(width)

                for (y in 0 until height) {
                    buffer.position(y * rowStride)
                    buffer.get(rowBuffer, 0, minOf(rowStride, buffer.remaining()))

                    for (x in 0 until width) {
                        val offset = x * pixelStride
                        val r = rowBuffer[offset].toInt() and 0xFF
                        val g = rowBuffer[offset + 1].toInt() and 0xFF
                        val b = rowBuffer[offset + 2].toInt() and 0xFF
                        val a = rowBuffer[offset + 3].toInt() and 0xFF
                        pixels[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    bitmap.setPixels(pixels, 0, width, 0, y, width, 1)
                }
            }

            // 處理旋轉
            val rotation = imageProxy.imageInfo.rotationDegrees
            val finalBitmap = if (rotation != 0) {
                val matrix = Matrix()
                matrix.postRotate(rotation.toFloat())
                val rotated = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                )
                bitmap.recycle()
                rotated
            } else {
                bitmap
            }

            // 轉成 JPEG
            val stream = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
            finalBitmap.recycle()
            val jpegBytes = stream.toByteArray()
            stream.close()
            jpegBytes
        } catch (e: Exception) {
            Log.e(TAG, "RGBA→JPEG 失敗：${e.message}")
            null
        }
    }

    // ===== 自動偵測（每 5 秒一次） =====

    private val autoDetectRunnable = object : Runnable {
        override fun run() {
            captureAndRecognize() // 擷取一幀並辨識
            handler.postDelayed(this, detectIntervalMs) // 5000ms 後再執行一次
        }
    }

    private fun startAutoDetection() {
        tvResult.text = "偵測中..."
        handler.post(autoDetectRunnable) // 開始定時任務
    }

    private fun stopAutoDetection() {
        handler.removeCallbacks(autoDetectRunnable)
    }

    private fun captureAndRecognize() {
        // 使用 AtomicBoolean 來防止同時送多個請求
        if (!isProcessing.compareAndSet(false, true)) return
        shouldCaptureNextFrame.set(true) // 標記：下一幀要被擷取
    }

    // ===== 發送到後端 =====

    private fun sendToServer(jpegBytes: ByteArray) {
        lifecycleScope.launch {
            try {

                // 1️⃣ 包裝成 multipart 格式
                val requestBody = jpegBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("file", "capture.jpg", requestBody)

                // 2️⃣ 呼叫 API（發送到 FastAPI 後端）
                val response = withContext(Dispatchers.IO) {
                    ApiClient.api.recognize(part)
                }

                // 3️⃣ 處理回覆
                if (response.isSuccessful) {
                    val result = response.body()
                    if (result != null) {
                        displayResult(result)
                    } else {
                        Log.e(TAG, "Response body 為空")
                    }
                }else {
                    Log.e(TAG, "API 錯誤: ${response.code()} ${response.errorBody()?.string()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "連線失敗", e)
            } finally {
                isProcessing.set(false)
            }
        }
    }

    // ===== 顯示結果 + 語音播報 =====

    private fun displayResult(result: RecognizeResponse) {
        if (result.faces.isEmpty()) {
            tvResult.text = "未偵測到人臉"
            return
        }

        // 選擇信心度最高的那個人
        val bestFace = result.faces.maxByOrNull { it.confidence } ?: return
        val now = System.currentTimeMillis()

        if (bestFace.name != "unknown") {
            // 辨識成功
            val confidence = (bestFace.confidence * 100).toInt()
            tvResult.text = "${bestFace.name}  ${confidence}%"

            // 冷卻時間檢查（10 秒內不重複播報同一個人）
            if (bestFace.name != lastAnnounceName || now - lastAnnounceTime > announceCooldown) {
                lastAnnounceName = bestFace.name
                lastAnnounceTime = now
                val message = "你面前的人是${bestFace.name}"
                Log.d(TAG, "🔊 播報：$message")
                speakOut(message)
            }
        } else {
            // 未知人物
            val confidence = (bestFace.confidence * 100).toInt()
            tvResult.text = "未知人物  ${confidence}%"

            if ("unknown" != lastAnnounceName || now - lastAnnounceTime > announceCooldown) {
                lastAnnounceName = "unknown"
                lastAnnounceTime = now
                val message = "前方有未知人物"
                Log.d(TAG, "🔊 播報：$message")
                speakOut(message)
            }
        }
    }

    // ===== 語音播報 =====

    private fun speakOut(message: String) {
        if (ttsReady) {
            // 優先使用 TextToSpeech
            val speakResult = tts.speak(message, TextToSpeech.QUEUE_ADD, null, message)
            if (speakResult == TextToSpeech.ERROR) {
                // 備用：使用 MediaPlayer + Google 翻譯 TTS
                speakWithMediaPlayer(message)
            }
        } else {
            // TTS 未初始化，使用備用方案
            speakWithMediaPlayer(message)
        }
    }

    // ===== TTS 初始化 =====

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            var result = tts.setLanguage(Locale.TRADITIONAL_CHINESE)

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                result = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
            }
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                result = tts.setLanguage(Locale.US)
            }

            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                ttsReady = true
                tts.setSpeechRate(1.0f)
                tts.setPitch(1.0f)
                Log.d(TAG, "✅ TTS 初始化成功")
            } else {
                Log.e(TAG, "❌ TTS 沒有可用語言")
            }
        } else {
            Log.e(TAG, "❌ TTS 初始化失敗")
        }
    }

    // ===== 備用語音播報（MediaPlayer） =====

    private fun speakWithMediaPlayer(text: String) {
        try {
            currentMediaPlayer?.stop()
            currentMediaPlayer?.release()

            // 使用 Google 翻譯的 TTS API
            val url = "https://translate.google.com/translate_tts?ie=UTF-8&tl=zh-TW&client=tw-ob&q=${
                java.net.URLEncoder.encode(text, "UTF-8")
            }"

            currentMediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .build()
                )
                setDataSource(url)
                setOnPreparedListener { it.start() } // 準備好後自動播放
                setOnCompletionListener {
                    it.release()
                    currentMediaPlayer = null
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer 播報失敗：${e.message}")
        }
    }

    // ===== 權限處理 =====

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->

            if (isGranted) {
                startCamera()
            } else {
                Toast.makeText(requireContext(), "需要相機權限", Toast.LENGTH_LONG).show()
            }
        }

    // ===== 清理資源 =====

    override fun onDestroy() {
        super.onDestroy()
        stopAutoDetection()
        try {
            cameraExecutor?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "cameraExecutor shutdown failed", e)
        }
        cameraExecutor = null
        tts.shutdown()
        currentMediaPlayer?.stop()
        currentMediaPlayer?.release()
        currentMediaPlayer = null
    }

    override fun onStop() {
        super.onStop()
        stopAutoDetection()
        isProcessing.set(false)
        Log.d(TAG, "Fragment 進入背景，停止偵測")
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "Fragment 回到前景，恢復偵測")
        ensureCameraExecutor()
        checkAndRequestCameraPermission()
    }

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
    }
}