package com.example.rokidglasses_project.network

import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Multipart
import okhttp3.MultipartBody
import retrofit2.http.Part
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface FastApiService {
    @POST("/chat")
    suspend fun chat(@Body body: ChatRequest): ChatResponse

    @Multipart
    @POST("/chat_audio")
    suspend fun chatAudio(@Part file: MultipartBody.Part): Response<ResponseBody>


    @POST("/tts")
    suspend fun tts(@Body body: ChatRequest): Response<ResponseBody>

    @Multipart
    @POST("/recognize")
    suspend fun recognize(
        @Part file: MultipartBody.Part
    ): Response<RecognizeResponse>

    @GET("/faces")
    suspend fun listFaces(): Response<FaceListResponse>

    @GET("/")
    suspend fun getStatus(): Response<StatusResponse>

}