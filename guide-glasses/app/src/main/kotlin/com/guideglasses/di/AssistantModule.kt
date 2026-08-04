package com.guideglasses.di

import android.content.Context
import com.guideglasses.BuildConfig
import com.guideglasses.ai.agent.OfflineLlmIntentGateway
import com.guideglasses.ai.agent.RemoteLlmIntentGateway
import com.guideglasses.ai.speech.AndroidSpeechRecognitionGateway
import com.guideglasses.ai.speech.AndroidTtsAnnouncer
import com.guideglasses.core.common.DispatcherProvider
import com.guideglasses.core.domain.announce.AnnouncementManager
import com.guideglasses.core.domain.announce.Announcer
import com.guideglasses.core.domain.assistant.ConversationHistory
import com.guideglasses.core.domain.assistant.IntentRouter
import com.guideglasses.core.domain.assistant.LlmIntentGateway
import com.guideglasses.core.domain.assistant.LocalCommandMatcher
import com.guideglasses.core.domain.speech.SpeechRecognitionGateway
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AssistantModule {

    /**
     * 播報要活得比任何畫面久 —— 使用者可能在導航途中切到別的 App，
     * 危險警示仍然必須播出來。因此用 application 層級的 scope，
     * 不綁 ViewModel 生命週期。
     */
    @Provides
    @Singleton
    fun provideAnnouncementScope(dispatchers: DispatcherProvider): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatchers.main)

    @Provides
    @Singleton
    fun provideAnnouncer(@ApplicationContext context: Context): Announcer =
        AndroidTtsAnnouncer(context)

    @Provides
    @Singleton
    fun provideAnnouncementManager(
        announcer: Announcer,
        scope: CoroutineScope,
    ): AnnouncementManager = AnnouncementManager(announcer, scope)

    @Provides
    @Singleton
    fun provideSpeechGateway(
        @ApplicationContext context: Context,
    ): SpeechRecognitionGateway = AndroidSpeechRecognitionGateway(context)

    /**
     * 未設定 BFF 位址時退回離線閘道。
     *
     * 這是刻意的設計：後端還沒建好時 App 仍然完全可用 ——
     * 本地快捷指令（停 / 前面有什麼 / 這是誰 / 唸給我聽）不需要網路。
     *
     * 設定方式：在 local.properties 或 ~/.gradle/gradle.properties 加入
     *   guideglasses.llmEndpoint=https://your-bff.run.app/route
     */
    @Provides
    @Singleton
    fun provideLlmGateway(): LlmIntentGateway {
        val endpoint = BuildConfig.LLM_ENDPOINT
        return if (endpoint.isBlank()) {
            OfflineLlmIntentGateway()
        } else {
            RemoteLlmIntentGateway(endpoint)
        }
    }

    @Provides
    @Singleton
    fun provideConversationHistory(): ConversationHistory = ConversationHistory()

    @Provides
    @Singleton
    fun provideIntentRouter(
        llmGateway: LlmIntentGateway,
        history: ConversationHistory,
    ): IntentRouter = IntentRouter(LocalCommandMatcher(), llmGateway, history)
}
