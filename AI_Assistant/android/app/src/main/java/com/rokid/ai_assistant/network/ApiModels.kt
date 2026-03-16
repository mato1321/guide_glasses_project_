package com.example.rokidglasses_project.network

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