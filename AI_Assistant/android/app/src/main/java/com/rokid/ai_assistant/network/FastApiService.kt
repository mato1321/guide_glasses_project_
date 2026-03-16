package com.example.rokidglasses_project.network

import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Multipart
import okhttp3.MultipartBody
import retrofit2.http.Part
import okhttp3.ResponseBody
import retrofit2.Response

interface FastApiService {
    @POST("/chat")
    suspend fun chat(@Body body: ChatRequest): ChatResponse

    @Multipart
    @POST("/chat_audio")
    suspend fun chatAudio(@Part file: MultipartBody.Part): Response<ResponseBody>
}