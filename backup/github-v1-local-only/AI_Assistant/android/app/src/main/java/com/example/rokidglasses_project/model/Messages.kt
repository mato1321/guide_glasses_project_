package com.example.rokidglasses_project.model

data class Message(
    val id: Long = System.currentTimeMillis(),
    val from: From = From.BOT,
    val text: String,
    val time: String = java.text.SimpleDateFormat("HH:mm").format(java.util.Date())
) {
    enum class From { BOT, USER }
}