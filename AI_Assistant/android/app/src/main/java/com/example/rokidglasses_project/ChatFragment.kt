package com.example.rokidglasses_project.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import com.github.houbb.opencc4j.util.ZhConverterUtil
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Switch
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rokidglasses_project.R
import com.example.rokidglasses_project.audio.AudioPlayer
import com.example.rokidglasses_project.audio.AudioRecorder
import com.example.rokidglasses_project.model.Message
import com.example.rokidglasses_project.network.ApiClient
import com.example.rokidglasses_project.network.ChatRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.File
import java.util.Locale
import android.media.MediaPlayer
import androidx.core.os.bundleOf

@Suppress("DEPRECATION")
class ChatFragment : Fragment(){

    private lateinit var rvMessages: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnVoice: ImageButton
    private lateinit var btnSend: ImageButton
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private lateinit var adapter: MessageAdapter
    private val messages = mutableListOf<Message>()

    private lateinit var audioRecorder: AudioRecorder // 錄音工具
    private lateinit var audioPlayer: AudioPlayer // 播放音檔工具

    private var recordingFile: File? = null // 存儲錄音的檔案
    private var isRecording = false  // 是否正在錄音

    // 【關鍵】回調給 MainActivity - 當偵測到「人臉辨識」關鍵字時
    var onFaceRecognitionRequested: (() -> Unit)? = null
    private var isPlaying = false
    private var autoSpeak = true

    // 本機播放用 MediaPlayer（讓它在整個 fragment 內可用）
    private var localMediaPlayer: MediaPlayer? = null


    // 功能名稱對應到 raw resource id
    private val featureAudioMap = mapOf(
        "face_recognition" to R.raw.face_recognition_start,
        "greeting" to R.raw.greeting,
        "record_and_stop" to R.raw.record_and_stop,
        "record_finish" to R.raw.record_finish,
        "play_finish" to R.raw.playfinish,
        "back_to_ai" to R.raw.back_to_ai
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_chat, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 初始化 UI
        rvMessages = view.findViewById(R.id.rvMessages)
        etInput = view.findViewById(R.id.etInput)
        btnVoice = view.findViewById(R.id.btnVoice)
        btnSend = view.findViewById(R.id.btnSend)


        adapter = MessageAdapter(messages)
        rvMessages.adapter = adapter
        rvMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }

        // 初始化錄音和播放組件
        audioRecorder = AudioRecorder(requireContext())
        audioPlayer = AudioPlayer(requireContext())



        // 註冊從人臉頁面回來的結果
        parentFragmentManager.setFragmentResultListener("from_face", viewLifecycleOwner) { _, bundle ->
            val msg = bundle.getString("message") ?: return@setFragmentResultListener
            // 顯示並（預設）播放
            addBotMessage(msg, playTts = false)
            val resId = featureAudioMap["back_to_ai"]
            if(resId !=null){
                playLocalAudio(resId)
            }

        }



        // Send button
        btnSend.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isNotEmpty()) {
                postUserMessage(text)
                etInput.setText("")
            }
        }

        btnVoice.setOnClickListener {

            // 如果目前有正在播放的音訊，先停止播放並直接開始錄音（讓使用者馬上提問）
            val isLocalPlaying = localMediaPlayer?.isPlaying == true
            val isBackendPlaying = try { ::audioPlayer.isInitialized && audioPlayer.isPlaying() } catch (_: Exception) { false }

            if ((isLocalPlaying || isBackendPlaying) && !isRecording) {
                // 停止所有播放並開始錄音
                stopAllPlayback()

                startRecording()
                isRecording = true
                return@setOnClickListener
            }


            if (!hasRecordPermission()) {
                requestPermissions(
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    REQ_RECORD_AUDIO
                )
                return@setOnClickListener
            }

            if (!isRecording) {
                // 第一次按下：開始錄音
                startRecording()
                isRecording = true



            } else {
                // 第二次按下：停止錄音並上傳
                stopRecordingAndUpload()
                isRecording = false


            }
        }
        // 初始歡迎訊息
        addBotMessage("哈囉！我是你的ai助理。請按麥克風說話。" , playTts = false)
        val resId = featureAudioMap["greeting"]
        if(resId !=null){
            playLocalAudio(resId)
        }

    }



    private fun stopAllPlayback(){
        try {
            // 停本機 MediaPlayer
            localMediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            localMediaPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            // 停你自定義的 audioPlayer（你已有 isPlaying() / stop()）
            if (::audioPlayer.isInitialized) {
                try {
                    if (audioPlayer.isPlaying()) {
                        audioPlayer.stop()
                    }
                } catch (_: Exception) { /* 忽略 */ }
            }
        } catch (_: Exception) {}
    }

    private fun playLocalAudio(resId: Int, onComplete: (() -> Unit)? = null) {
        try {
            localMediaPlayer?.stop()
            localMediaPlayer?.release()
            localMediaPlayer = null

            localMediaPlayer = MediaPlayer.create(requireContext(), resId)
            localMediaPlayer?.setOnCompletionListener {
                it.release()
                localMediaPlayer = null
                onComplete?.invoke()
            }
            localMediaPlayer?.setOnErrorListener { mp, what, extra ->
                mp.release()
                localMediaPlayer = null
                onComplete?.invoke()
                true
            }
            localMediaPlayer?.start()
        } catch (e: Exception) {
            e.printStackTrace()
            onComplete?.invoke()
        }
    }



    // 新增使用者訊息並呼叫文字 chat API
    private fun postUserMessage(text: String){
        addUserMessage(text)
        lifecycleScope.launch {
            try{
                val resp = ApiClient.api.chat(ChatRequest(message = text))
                withContext((Dispatchers.Main)){
                    addBotMessage(resp.reply)
                    announceForAccessibility(resp.reply)
                    //若需要由 AI 指示切換人臉辨識，可在這裡檢查 resp.reply 與 text
                    if (shouldSwitchToFaceRecognition(resp.reply , text)){
                        onFaceRecognitionRequested?.invoke()
                }

                }
            }catch (e: Exception){
                e.printStackTrace()
                withContext((Dispatchers.Main)){
                    addBotMessage("發生錯誤: ${e.message}")
                }
            }
        }

    }

    // 開始錄音（使用現有 AudioRecorder）
    private fun startRecording(){
        try{
            recordingFile = audioRecorder.startRecording()
            isRecording = true
            addBotMessage("錄音中... 再按一次麥克風即可停止錄音", playTts = false)
            val resId = featureAudioMap["record_and_stop"]
            if(resId !=null){
                playLocalAudio(resId)
            }
        }catch (e: Exception){
            isRecording = false
            addBotMessage("開始錄音失敗: ${e.message}")
        }
    }


    private fun requestTtsAndPlay(text: String) {
        // 若使用者關閉自動朗讀就不呼叫
        if (!autoSpeak) return

        // 檢查系統 TalkBack，如果啟用通常不用再自動播放（避免重複）
        val am = requireContext().getSystemService(AccessibilityManager::class.java)
        val talkBackEnabled = am?.isEnabled == true
        if (talkBackEnabled) {
            // 如果你希望即使 TalkBack 開啟也播放，可移除這個 return
            return
        }

        // 若 AudioPlayer 正在播放，先停止它（避免重疊）
        try {
            if (audioPlayer.isPlaying()) {
                audioPlayer.stop()
            }
        } catch (e: Exception) {
            // 忽略停止錯誤，但 log 出來
            e.printStackTrace()
        }

        lifecycleScope.launch {
            try {
                // 呼叫後端 /tts
                val req = ChatRequest(message = text)
                val resp = ApiClient.api.tts(req)

                if (!resp.isSuccessful) {
                    // 顯示錯誤訊息（或 log）
                    addBotMessage("語音合成失敗（HTTP ${resp.code()}）")
                    return@launch
                }

                val body = resp.body()
                if (body == null) {
                    addBotMessage("語音合成沒有回傳內容")
                    return@launch
                }

                val bytes = body.bytes()
                if (bytes.isEmpty()) {
                    addBotMessage("空的語音檔")
                    return@launch
                }

                // 播放回傳的音檔
                audioPlayer.play(bytes,
                    onComplete = {
                        // 可在播放完成後做些 UI 提示（非必要）
                        lifecycleScope.launch(Dispatchers.Main) {
                            // 例如：addBotMessage("語音播放完成")
                            // 若你不想在訊息列產生額外 item，可用 log
                        }
                    },
                    onError = { err ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            addBotMessage("播放語音失敗: $err")
                        }
                    }
                )

            } catch (e: Exception) {
                e.printStackTrace()
                lifecycleScope.launch(Dispatchers.Main) {
                    addBotMessage("呼叫語音合成發生錯誤: ${e.message}")
                }
            }
        }
    }

    private fun stopRecordingAndUpload(){
        val file = audioRecorder.stopRecording()
        isRecording = false
        if(file==null){
            addBotMessage("錄音未成功")
            return
        }
        addBotMessage(("錄音完成，上傳中...") , playTts = false)
        val resId = featureAudioMap["record_finish"]
        if(resId !=null){
            playLocalAudio(resId)
        }
        lifecycleScope.launch {
            uploadAudioFile(file)
        }

    }

    // 上傳錄音到 /chat_audio（保留你原有的解析流程）
    private suspend fun uploadAudioFile(file: File) {
        withContext((Dispatchers.IO)){
            try{
                val part = MultipartBody.Part.createFormData(
                    "file" , file.name , file.asRequestBody(("audio/*".toMediaTypeOrNull()))
                )

                val response: Response<ResponseBody> = ApiClient.api.chatAudio((part))

                if(!response.isSuccessful){
                    with(Dispatchers.Main){
                        addBotMessage("後端上傳失敗: ${response.code()}")
                    }
                    return@withContext
                }

                val audioData = extractAudioResponse(response)?: return@withContext


                withContext(Dispatchers.Main){

                    //若要切換人臉辨識******************
                    val triggered = shouldSwitchToFaceRecognition(audioData.replyText, audioData.userText)
                    if (triggered) {
                        // 顯示由助理的啟動提示（會被轉為繁體處理的話可在 addBotMessage 做）
                        addUserMessage(audioData.userText)
                        addBotMessage("正在啟動人臉辨識功能..." , playTts = false)
                        // 停止任何正在播放的音訊（避免重疊）
                        try { if (audioPlayer.isPlaying()) audioPlayer.stop() } catch (_: Exception) {}
                        // 先播放本機音檔（若存在），音檔播放完成後再切換功能
                        val resId = featureAudioMap["face_recognition"]
                        if (resId != null) {
                            playLocalAudio(resId) {
                                onFaceRecognitionRequested?.invoke()
                            }
                        } else {
                            // 沒本機音檔就直接切換
                            onFaceRecognitionRequested?.invoke()
                        }
                        // 不顯示後端回覆或播放後端音檔，結束處理
                        return@withContext
                    }

                    //顯示 user text 與 bot 回覆
                    addUserMessage(audioData.userText)
                    addBotMessage(audioData.replyText , playTts = audioData.audioBytes.isEmpty())
                    announceForAccessibility(audioData.replyText)



                    // 如果有音檔則播放，否則用 TTS
                    if(audioData.audioBytes.isNotEmpty()){
                        audioPlayer.play(audioData.audioBytes,
                                onComplete = {
                                    lifecycleScope.launch(Dispatchers.Main) {
                                        addBotMessage("播放完成！可以繼續提問囉！" , playTts = false)
                                        val resId = featureAudioMap["play_finish"]
                                        if(resId !=null){
                                            playLocalAudio(resId)
                                        }
                                    }
                                },

                                onError = { err ->
                                    lifecycleScope.launch(Dispatchers.Main) {
                                        addBotMessage("播放失敗: $err")
                                }
                                }
                        )
                    }else{
                        return@withContext
                    }

                }

            }catch (e: Exception){
                e.printStackTrace()
                withContext(Dispatchers.Main){
                    addBotMessage("音檔上傳或處理失敗：${e.message}")
                }
            }
        }
    }

    // 解碼 header（Base64）
    private fun decodeHeader(encoded: String?): String {
        return encoded?.let {
            try {
                String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Exception) {
                "解析失敗"
            }
        } ?: ""
    }

    // 從 Response 提取 userText、replyText、audioBytes
    private fun extractAudioResponse(response: Response<ResponseBody>): AudioResponse?{
        val userText = decodeHeader(response.headers()["X-User-Text"])
        val replyText = decodeHeader(response.headers()["X-Reply-Text"])
        val audioBytes = response.body()?.bytes()

        if (audioBytes == null || audioBytes.isEmpty()) {
            // 仍允許沒有音檔但有文字：回傳空 bytes（上層會用 TTS）
            return AudioResponse(userText, replyText, ByteArray(0))
        }
        return AudioResponse(userText, replyText, audioBytes)
    }


    private data class AudioResponse(
        val userText: String,
        val replyText: String,
        val audioBytes: ByteArray
    )


    // 決定是否切換到人臉辨識
    private fun shouldSwitchToFaceRecognition(replyText: String, userText: String): Boolean{
        val keywords = listOf(
            "人臉辨識",
            "臉部識別",
            "啟用人臉",
            "開啟人臉",
            "face recognition",
            "recognize"
        )
        val combined = (replyText + " " + userText).lowercase(Locale.getDefault())
        return keywords.any { combined.contains(it.lowercase(Locale.getDefault())) }
    }


    // 輔助：新增 bot/user 訊息
    private fun addBotMessage(text: String , playTts: Boolean = true) {

        // 轉繁體（發生錯誤則回傳原文）
        val textTrad = try {
            ZhConverterUtil.toTraditional(text)
        } catch (e: Exception) {
            e.printStackTrace()
            text
        }
        val botMsg = Message(from = Message.From.BOT, text = textTrad)
        adapter.addMessage(botMsg)
        scrollToBottom()

        // 只有在 playTts = true 時才呼叫後端 TTS（或原本的 requestTtsAndPlay）
        if (playTts) {
            requestTtsAndPlay(textTrad)
        }

    }

    private fun addUserMessage(text: String) {

        // 轉繁體（發生錯誤則回傳原文）
        val textTrad = try {
            ZhConverterUtil.toTraditional(text)
        } catch (e: Exception) {
            e.printStackTrace()
            text
        }
        val userMsg = Message(from = Message.From.USER, text = textTrad)
        adapter.addMessage(userMsg)
        scrollToBottom()
    }

    private fun scrollToBottom() {
        rvMessages.post { rvMessages.smoothScrollToPosition(adapter.itemCount - 1) }
    }


    // 使用 TalkBack announce
    private fun announceForAccessibility(text: String) {
        rvMessages.announceForAccessibility(text)
    }


    private fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val REQ_RECORD_AUDIO = 1001
    }

}