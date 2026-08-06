package com.guideglasses.di

import android.content.Context
import com.guideglasses.BuildConfig
import com.guideglasses.ai.agent.OfflineLlmIntentGateway
import com.guideglasses.ai.agent.RemoteLlmIntentGateway
import com.guideglasses.ai.speech.AndroidSpeechRecognitionGateway
import com.guideglasses.ai.speech.AndroidTtsAnnouncer
import com.guideglasses.ai.face.MlKitFaceDetector
import com.guideglasses.ai.face.RemoteFaceIdentification
import com.guideglasses.ai.face.TfLiteFaceEmbedder
import com.guideglasses.ai.ocr.MlKitTextRecognizer
import com.guideglasses.glasses.camerax.CameraXFrameSource
import com.guideglasses.glasses.sensors.AndroidMotionSensorGateway
import com.guideglasses.core.common.DispatcherProvider
import com.guideglasses.core.domain.announce.AnnouncementManager
import com.guideglasses.core.domain.announce.Announcer
import com.guideglasses.core.domain.assistant.ConversationHistory
import com.guideglasses.core.domain.assistant.IntentRouter
import com.guideglasses.core.domain.assistant.LlmIntentGateway
import com.guideglasses.core.domain.assistant.LocalCommandMatcher
import com.guideglasses.core.domain.glasses.CameraSelfTestUseCase
import com.guideglasses.core.database.PersonStorageFactory
import com.guideglasses.core.domain.face.FaceDetector
import com.guideglasses.core.domain.face.CompositeFaceIdentification
import com.guideglasses.core.domain.face.FaceEmbedder
import com.guideglasses.core.domain.face.FaceIdentificationStrategy
import com.guideglasses.core.domain.face.OnDeviceFaceIdentification
import com.guideglasses.core.domain.face.IdentifyPersonUseCase
import com.guideglasses.core.domain.face.PersonRepository
import com.guideglasses.core.domain.face.RegisterFaceUseCase
import com.guideglasses.core.domain.face.PhotoSource
import com.guideglasses.core.domain.face.SyncPeopleUseCase
import com.guideglasses.ai.face.HttpPhotoSource
import com.guideglasses.ai.face.OnnxFaceEmbedder
import com.guideglasses.core.domain.translate.TargetLanguage
import com.guideglasses.core.domain.readiness.ReadinessCheckUseCase
import com.guideglasses.core.domain.translate.PrepareLanguagesUseCase
import com.guideglasses.core.domain.translate.TranslateUseCase
import com.guideglasses.core.domain.translate.Translator
import com.guideglasses.ai.translate.MlKitTranslator
import com.guideglasses.core.domain.glasses.FrameSource
import com.guideglasses.core.domain.motion.MotionSensorGateway
import com.guideglasses.core.domain.ocr.ReadTextUseCase
import com.guideglasses.core.domain.ocr.SpeechSegmenter
import com.guideglasses.core.domain.ocr.TextRecognizer
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

    /**
     * 影像來源。
     *
     * Rokid Glasses 執行 Android 12，標準 CameraX 直接可用 —— 不需要 CXR SDK。
     * 同一個實作在手機上也能跑，眼鏡不在手邊照樣能開發。
     */
    @Provides
    @Singleton
    fun provideFrameSource(@ApplicationContext context: Context): FrameSource =
        CameraXFrameSource(context)

    @Provides
    @Singleton
    fun provideCameraSelfTest(frameSource: FrameSource): CameraSelfTestUseCase =
        CameraSelfTestUseCase(frameSource)

    /**
     * 端側 OCR。
     *
     * bundled ML Kit 中文模型隨 APK 安裝，不依賴 Google Play Services ——
     * Rokid Glasses 是否預裝 Play Services 無法確認，這樣最保險。
     */
    @Provides
    @Singleton
    fun provideTextRecognizer(): TextRecognizer = MlKitTextRecognizer()

    @Provides
    @Singleton
    fun provideReadTextUseCase(
        frameSource: FrameSource,
        recognizer: TextRecognizer,
    ): ReadTextUseCase = ReadTextUseCase(
        frameSource = frameSource,
        onDeviceRecognizer = recognizer,
        // 雲端 fallback 需要 BFF，目前不存在。端側結果不佳時會直接沿用，
        // 有東西總比沒有好。
        cloudRecognizer = null,
        segmenter = SpeechSegmenter(),
    )

    // ===== 翻譯 =====

    /**
     * 端側翻譯。
     *
     * 和 OCR 不同，翻譯的語言包**沒有 bundled 版** —— ML Kit 一律在執行期
     * 下載（每種語言約 30MB）。首次使用某語言需要網路，之後完全離線。
     * 只下載使用者實際要的語言，不預先抓十種塞滿 2GB RAM 的裝置。
     */
    @Provides
    @Singleton
    fun provideTranslator(): Translator = MlKitTranslator()

    @Provides
    @Singleton
    fun provideTranslateUseCase(translator: Translator): TranslateUseCase =
        TranslateUseCase(
            translator = translator,
            // 沒有 BFF 時抽不出「翻成日文」的語言參數，但本地已能解析固定說法；
            // 真的都解析不到才用英文 —— 語言包最小，且最可能是溝通對象的語言。
            defaultTarget = TargetLanguage.DEFAULT,
        )

    /**
     * 預先下載語言包。
     *
     * 眼鏡沒有 SIM，出門就沒網路。翻譯的語言包必須事前抓好，
     * 否則到了戶外會發現翻譯不能用。
     */
    @Provides
    @Singleton
    fun providePrepareLanguagesUseCase(translator: Translator): PrepareLanguagesUseCase =
        PrepareLanguagesUseCase(translator)

    // ===== 出門前檢查 =====

    /**
     * 回答「現在拔掉網路，還有哪些功能能用」。
     *
     * 實測最常見的失敗不是程式壞掉，而是忘記同步人臉或下載語言包就出門。
     */
    @Provides
    @Singleton
    fun provideReadinessCheckUseCase(
        embedder: FaceEmbedder,
        people: PersonRepository,
        translator: Translator,
        photoSource: PhotoSource,
    ): ReadinessCheckUseCase = ReadinessCheckUseCase(
        embedder = embedder,
        people = people,
        translator = translator,
        photoSource = photoSource,
    )

    // ===== 人臉辨識 =====

    @Provides
    @Singleton
    fun provideFaceDetector(): FaceDetector = MlKitFaceDetector()

    /**
     * 人臉特徵抽取。
     *
     * **需要模型檔才能運作** —— 見 `ai/ai-face/src/main/assets/README.md`。
     * 沒有模型時兩者的 isAvailable 都是 false，辨識自動退回遠端後端，
     * 其他功能不受影響。
     *
     * ONNX 優先：InsightFace 匯出的 `.onnx` 可以**直接執行不需轉檔**。
     * 轉成 tflite 要做 NCHW→NHWC 重排，弄錯不會報錯只會安靜地認不出人 ——
     * 少一次轉換就少一個出錯的機會。
     */
    @Provides
    @Singleton
    fun provideFaceEmbedder(@ApplicationContext context: Context): FaceEmbedder {
        val onnx = OnnxFaceEmbedder(context)
        return if (onnx.isAvailable) onnx else TfLiteFaceEmbedder(context)
    }

    // ===== 人臉同步 =====

    /**
     * 註冊工具的照片來源。
     *
     * 眼鏡無法自行註冊（語音抽人名需要 BFF），所以人是在瀏覽器上建檔的。
     * 位址設定方式：啟動 `tools/face_enroll_server.py`，它會印出要貼哪一行。
     */
    @Provides
    @Singleton
    fun providePhotoSource(): PhotoSource = HttpPhotoSource(BuildConfig.PHOTO_ENDPOINT)

    @Provides
    @Singleton
    fun provideSyncPeopleUseCase(
        photoSource: PhotoSource,
        detector: FaceDetector,
        embedder: FaceEmbedder,
        repository: PersonRepository,
    ): SyncPeopleUseCase = SyncPeopleUseCase(
        photoSource = photoSource,
        detector = detector,
        embedder = embedder,
        repository = repository,
    )

    /** 人臉資料只存在裝置上，且特徵向量以 Keystore 金鑰加密。 */
    @Provides
    @Singleton
    fun providePersonRepository(@ApplicationContext context: Context): PersonRepository =
        PersonStorageFactory.create(context)

    /**
     * 人臉辨識策略：端側優先，遠端備援。
     *
     * 端側需要 .tflite 模型檔；沒有模型時自動改走遠端（團隊既有的
     * InsightFace 後端），所以**放不放模型都能運作**。
     *
     * 遠端位址設定方式（與 LLM 相同）：在 local.properties 或
     * ~/.gradle/gradle.properties 加入
     *   guideglasses.faceEndpoint=http://192.168.1.100:8000/recognize
     */
    @Provides
    @Singleton
    fun provideFaceIdentification(
        embedder: FaceEmbedder,
        repository: PersonRepository,
    ): FaceIdentificationStrategy = CompositeFaceIdentification(
        listOfNotNull(
            OnDeviceFaceIdentification(embedder, repository),
            BuildConfig.FACE_ENDPOINT
                .takeIf { it.isNotBlank() }
                ?.let { RemoteFaceIdentification(it) },
        ),
    )

    @Provides
    @Singleton
    fun provideIdentifyPersonUseCase(
        frameSource: FrameSource,
        detector: FaceDetector,
        identification: FaceIdentificationStrategy,
    ): IdentifyPersonUseCase = IdentifyPersonUseCase(
        frameSource = frameSource,
        detector = detector,
        identification = identification,
    )

    @Provides
    @Singleton
    fun provideRegisterFaceUseCase(
        frameSource: FrameSource,
        detector: FaceDetector,
        embedder: FaceEmbedder,
        repository: PersonRepository,
    ): RegisterFaceUseCase = RegisterFaceUseCase(
        frameSource = frameSource,
        detector = detector,
        embedder = embedder,
        repository = repository,
    )

    /**
     * 動作感測。
     *
     * 眼鏡沒有 GPS，IMU 因此是唯一能感知「使用者在做什麼」的東西 ——
     * 相機模式自動切換（省電）、方位修正、步態導航輔助都靠它。
     * 實際能力以裝置回報為準，可用語音指令「測試感測器」確認。
     */
    @Provides
    @Singleton
    fun provideMotionSensorGateway(
        @ApplicationContext context: Context,
    ): MotionSensorGateway = AndroidMotionSensorGateway(context)
}
