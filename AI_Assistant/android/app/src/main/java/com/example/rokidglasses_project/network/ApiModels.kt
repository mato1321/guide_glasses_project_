package com.example.rokidglasses_project.network

// ===== AI 對話相關 =====

data class ChatRequest(
    val message: String
)

data class ChatResponse(
    val reply: String
)

data class ChatAudioResponse(
    val user_text: String,
    val reply: String,
    val error: String? = null
)


// ===== 人臉辨識相關（新增） =====
data class FaceResult(
    val name: String,
    val confidence: Double,
    val bbox: List<Double>
)

data class RecognizeResponse(
    val success: Boolean,
    val face_count: Int,
    val faces: List<FaceResult>
)

data class FaceListResponse(
    val faces: List<String>,
    val total: Int
)

data class StatusResponse(
    val status: String,
    val registered_faces: List<String>,
    val total_people: Int
)