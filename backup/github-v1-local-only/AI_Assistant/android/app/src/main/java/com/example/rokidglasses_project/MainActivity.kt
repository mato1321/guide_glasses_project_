package com.example.rokidglasses_project

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.example.rokidglasses_project.ui.ChatFragment
import com.example.rokidglasses_project.ui.FaceRecognitionFragment

/**
 * 主活動 - Fragment 協調器
 *
 * 職責：
 * 1. 管理 ChatFragment 和 FaceRecognitionFragment
 * 2. 處理兩個 Fragment 之間的切換
 * 3. 監聽來自各 Fragment 的回調
 */
class MainActivity : AppCompatActivity() {

    private lateinit var chatFragment: ChatFragment
    private lateinit var faceRecognitionFragment: FaceRecognitionFragment

    // 追蹤當前的模式
    private var currentMode = AppMode.AI_CHAT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // 如果是首次創建，初始化 Fragment
        if (savedInstanceState == null) {
            initializeFragments()
        }
    }

    // ===== Fragment 初始化 =====

    private fun initializeFragments() {
        // 1️⃣ 創建 ChatFragment
        chatFragment = ChatFragment().apply {
            // 設置回調：當偵測到人臉辨識關鍵字時
            onFaceRecognitionRequested = {
                switchToFaceRecognition()
            }
        }

        // 2️⃣ 創建 FaceRecognitionFragment
        faceRecognitionFragment = FaceRecognitionFragment().apply {
            // 設置回調：當用戶點擊返回按鈕時
            onBackToChatRequested = {
                switchToChat()
            }
        }

        // 3️⃣ 初始添加兩個 Fragment 到容器
        supportFragmentManager.beginTransaction().apply {
            // 添加 ChatFragment（預設顯示）
            add(R.id.fragment_container, chatFragment, CHAT_TAG)
            // 添加 FaceRecognitionFragment（但隱藏）
            add(R.id.fragment_container, faceRecognitionFragment, FACE_TAG)
            hide(faceRecognitionFragment)
            commit()
        }
    }

    // ===== 切換到人臉辨識模式 =====

    private fun switchToFaceRecognition() {
        currentMode = AppMode.FACE_RECOGNITION

        supportFragmentManager.beginTransaction().apply {
            // 隱藏 ChatFragment
            hide(chatFragment)
            // 顯示 FaceRecognitionFragment
            show(faceRecognitionFragment)
            // 添加到返回棧，方便用戶按返回鍵回到 AI 對話
            addToBackStack("face_recognition")
            commit()
        }
    }

    // ===== 切換回 AI 對話模式 =====

    private fun switchToChat() {
        currentMode = AppMode.AI_CHAT

        supportFragmentManager.beginTransaction().apply {
            // 隱藏 FaceRecognitionFragment
            hide(faceRecognitionFragment)
            // 顯示 ChatFragment
            show(chatFragment)
            commit()
        }
    }

    companion object {
        // Fragment 標籤（便於後續查找）
        private const val CHAT_TAG = "chat"
        private const val FACE_TAG = "face"
    }
}

