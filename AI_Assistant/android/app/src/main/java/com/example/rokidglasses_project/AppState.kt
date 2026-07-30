package com.example.rokidglasses_project

enum class AppMode {
    AI_CHAT,           // AI 對話模式
    FACE_RECOGNITION,  // 人臉辨識模式
}

data class AppUIState(
    val mode: AppMode = AppMode.AI_CHAT,
    val message: String = "等待輸入或語音...",
    val isRecording: Boolean = false,
    val isProcessing: Boolean = false,
)