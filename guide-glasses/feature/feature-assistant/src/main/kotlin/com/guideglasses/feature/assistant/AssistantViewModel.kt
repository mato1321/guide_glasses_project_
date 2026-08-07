package com.guideglasses.feature.assistant

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.announce.Announcement
import com.guideglasses.core.domain.announce.AnnouncementManager
import com.guideglasses.core.domain.announce.AnnouncementPriority
import com.guideglasses.core.domain.assistant.AssistantIntent
import com.guideglasses.core.domain.assistant.IntentRouter
import com.guideglasses.core.domain.assistant.RoutedIntent
import com.guideglasses.core.domain.translate.TargetLanguage
import com.guideglasses.core.domain.readiness.ReadinessCheckUseCase
import com.guideglasses.core.domain.translate.PrepareLanguagesUseCase
import com.guideglasses.core.domain.translate.TranslateUseCase
import com.guideglasses.core.domain.face.IdentifyPersonUseCase
import com.guideglasses.core.domain.face.RegisterFaceUseCase
import com.guideglasses.core.domain.face.SyncPeopleUseCase
import com.guideglasses.core.domain.glasses.CameraSelfTestUseCase
import com.guideglasses.core.domain.motion.MotionSensorGateway
import com.guideglasses.core.domain.obstacle.DetectObstaclesUseCase
import com.guideglasses.core.domain.ocr.OcrMode
import com.guideglasses.core.domain.ocr.ReadTextUseCase
import com.guideglasses.core.domain.ocr.ReadingSession
import com.guideglasses.core.domain.speech.SpeechCapability
import com.guideglasses.core.domain.speech.SpeechEvent
import com.guideglasses.core.domain.speech.SpeechRecognitionGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 助理中樞的 ViewModel。
 *
 * 完整流程：語音 → ASR → 意圖路由 → 分派 → 播報。
 * 所有輸出一律經過 [AnnouncementManager]，這個類別不持有任何播放器。
 */
@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val speechGateway: SpeechRecognitionGateway,
    private val intentRouter: IntentRouter,
    private val announcementManager: AnnouncementManager,
    private val cameraSelfTest: CameraSelfTestUseCase,
    private val readText: ReadTextUseCase,
    private val identifyPerson: IdentifyPersonUseCase,
    private val registerFace: RegisterFaceUseCase,
    private val motionSensors: MotionSensorGateway,
    private val translateText: TranslateUseCase,
    private val syncPeople: SyncPeopleUseCase,
    private val readinessCheck: ReadinessCheckUseCase,
    private val prepareLanguages: PrepareLanguagesUseCase,
    private val detectObstacles: DetectObstaclesUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    private var listeningJob: Job? = null

    /** 上一次播報的內容，供「再說一次」使用。 */
    private var lastSpoken: String? = null

    /** 目前進行中的朗讀。長文分段之後，使用者可以說「下一段」「上一段」。 */
    private var readingSession: ReadingSession? = null

    /**
     * 上一次 OCR 讀到的完整文字，供「翻成英文」使用。
     *
     * 這是 OCR 與翻譯的交叉整合點：使用者說「唸給我聽」聽到中文菜單，
     * 接著說「翻成英文」就能把同一份內容翻譯出來，**不需要再拍一次**。
     * 存整份而不是當前段落 —— 使用者要的是整張紙的翻譯。
     */
    private var lastReadText: String? = null

    /**
     * 使用者觸發助理（點畫面、或按下眼鏡的 AI 實體鍵）。
     *
     * 若正在聆聽則視為取消 —— 使用者按錯了要能反悔，
     * 而且不該逼他等到 ASR 逾時。
     */
    fun onAssistantTriggered() {
        if (_state.value.phase == Phase.LISTENING) {
            cancelListening()
            return
        }

        if (!speechGateway.isAvailable) {
            announce(MESSAGE_NO_ASR, AnnouncementPriority.USER_RESPONSE)
            return
        }

        // 使用者開口時，先讓正在播的內容安靜下來。
        announcementManager.clearAtOrBelow(AnnouncementPriority.NAVIGATION)

        listeningJob?.cancel()
        listeningJob = viewModelScope.launch {
            _state.update { it.copy(phase = Phase.LISTENING, transcript = "", lastReply = "") }

            speechGateway.listen().collect { event ->
                when (event) {
                    SpeechEvent.ReadyForSpeech,
                    SpeechEvent.SpeechStarted,
                    -> Unit

                    is SpeechEvent.PartialResult ->
                        _state.update { it.copy(transcript = event.text) }

                    is SpeechEvent.FinalResult -> {
                        _state.update { it.copy(transcript = event.text, phase = Phase.THINKING) }
                        handleUtterance(event.text)
                    }

                    is SpeechEvent.Failed -> {
                        _state.update { it.copy(phase = Phase.IDLE) }
                        announce(messageFor(event.error), AnnouncementPriority.USER_RESPONSE)
                    }
                }
            }
        }
    }

    /**
     * 開發用：不經語音直接分派一個 intent。
     *
     * **存在的理由：Rokid Glasses 上沒有任何語音辨識服務**
     * （見 `docs/DEVICE_FINDINGS.md` §3），所以無法用說話觸發任何功能。
     * 少了這個入口，OCR／人臉／翻譯／障礙物的邏輯在眼鏡上完全無法驗證。
     *
     * 只由 debug build 的廣播接收器呼叫，release 不會有任何呼叫端。
     *
     * ```bash
     * adb shell am broadcast -a com.guideglasses.DEBUG --es cmd READ_TEXT
     * adb shell am broadcast -a com.guideglasses.DEBUG --es cmd TRANSLATE --es target_language ja
     * ```
     */
    fun debugDispatch(intentName: String, arguments: Map<String, String> = emptyMap()) {
        val intent = AssistantIntent.entries
            .firstOrNull { it.name.equals(intentName, ignoreCase = true) }
        if (intent == null) {
            android.util.Log.w("AssistantVM", "debugDispatch: 找不到 intent「$intentName」")
            return
        }
        android.util.Log.i("AssistantVM", "debugDispatch → $intent args=$arguments")
        dispatch(RoutedIntent(intent, arguments, RoutedIntent.Source.LOCAL_FAST_PATH))
    }

    fun onStopRequested() {
        cancelListening()
        announcementManager.stopAll()
        _state.update { it.copy(phase = Phase.IDLE) }
    }

    private fun cancelListening() {
        listeningJob?.cancel()
        listeningJob = null
        speechGateway.cancel()
        _state.update { it.copy(phase = Phase.IDLE) }
    }

    private suspend fun handleUtterance(utterance: String) {
        val routed = intentRouter.route(utterance)
        _state.update { it.copy(phase = Phase.IDLE, routedFrom = routed.source) }
        dispatch(routed)
    }

    private fun dispatch(routed: RoutedIntent) {
        when (routed.intent) {
            AssistantIntent.STOP -> onStopRequested()

            AssistantIntent.REPEAT_LAST -> {
                val previous = lastSpoken
                if (previous.isNullOrBlank()) {
                    announce(MESSAGE_NOTHING_TO_REPEAT, AnnouncementPriority.USER_RESPONSE)
                } else {
                    // 重播不更新 lastSpoken，也不套用去抖動 ——
                    // 使用者是刻意要求再聽一次的。
                    announcementManager.announce(
                        Announcement(previous, AnnouncementPriority.USER_RESPONSE),
                    )
                }
            }

            AssistantIntent.CAMERA_TEST -> runCameraSelfTest()

            AssistantIntent.SENSOR_TEST ->
                announce(
                    motionSensors.capabilities.spokenSummary,
                    AnnouncementPriority.USER_RESPONSE,
                )

            AssistantIntent.READ_TEXT -> startReading(OcrMode.DOCUMENT)

            AssistantIntent.READ_SIGN -> startReading(OcrMode.SIGN)

            AssistantIntent.READING_NEXT -> readNextSegment()

            AssistantIntent.READING_PREVIOUS -> readPreviousSegment()

            AssistantIntent.IDENTIFY_PERSON -> identifyPersonAhead()

            AssistantIntent.SYNC_PEOPLE -> syncPeople()

            AssistantIntent.READINESS_CHECK -> checkReadiness()

            AssistantIntent.PREPARE_TRANSLATION -> prepareTranslation()

            AssistantIntent.REGISTER_FACE -> registerPerson(
                name = routed.arguments["name"].orEmpty(),
                relation = routed.arguments["relation"],
            )

            AssistantIntent.CHAT ->
                announce(
                    routed.spokenReply ?: MESSAGE_GENERIC_FAILURE,
                    AnnouncementPriority.USER_RESPONSE,
                )

            AssistantIntent.TRANSLATE -> translate(
                text = routed.arguments[IntentRouter.ARG_TEXT],
                targetLanguage = routed.arguments[IntentRouter.ARG_TARGET_LANGUAGE],
            )

            AssistantIntent.DETECT_OBSTACLES -> detectObstacles()

            // 導航仍缺 BFF、定位與狀態機。明確說「還在開發中」，
            // 而不是靜默無回應 —— 對看不見畫面的使用者，沒有聲音等於系統當掉。
            AssistantIntent.NAVIGATE -> announce(
                "「${routed.intent.description}」這個功能還在開發中",
                AnnouncementPriority.USER_RESPONSE,
            )
        }
    }

    /**
     * 相機自我檢測。
     *
     * 先講「正在測試相機」再開始擷取 —— 擷取可能要一兩秒，
     * 中間完全沒有聲音會讓看不見畫面的使用者以為系統當掉了。
     */
    private fun runCameraSelfTest() {
        announce(MESSAGE_CAMERA_TESTING, AnnouncementPriority.USER_RESPONSE)
        viewModelScope.launch {
            val report = cameraSelfTest.execute()
            announce(report.spoken, AnnouncementPriority.USER_RESPONSE)
        }
    }

    // ===== OCR 朗讀 =====

    private fun startReading(mode: OcrMode) {
        announce(MESSAGE_READING_CAPTURING, AnnouncementPriority.USER_RESPONSE)
        readingSession = null
        lastReadText = null

        viewModelScope.launch {
            when (val outcome = readText.execute(mode)) {
                is ReadTextUseCase.Outcome.Success -> {
                    readingSession = outcome.session
                    // 記下整份內容，讓接下來的「翻成英文」不必再拍一次。
                    lastReadText = outcome.session.fullText
                    announceReadingStart(outcome.session)
                }

                ReadTextUseCase.Outcome.NoTextFound ->
                    announce(MESSAGE_NO_TEXT, AnnouncementPriority.USER_RESPONSE)

                is ReadTextUseCase.Outcome.Failed ->
                    announce(messageFor(outcome.error), AnnouncementPriority.USER_RESPONSE)
            }
        }
    }

    /**
     * 先說共幾段，再唸第一段。
     *
     * 看得見的人一眼就知道這份文件有多長，看不見的人需要被告知 ——
     * 否則他不知道該準備聽三十秒還是三分鐘。
     */
    private fun announceReadingStart(session: ReadingSession) {
        if (session.total > 1) {
            // 一定要講「說下一段繼續」。唸完第一段就安靜下來，而使用者不知道
            // 有這個指令的話，他會以為 OCR 只讀到這麼多 —— 對看不見畫面的人，
            // 沒有下一步的沉默和系統當掉沒有分別。
            announce(
                "共 ${session.total} 段，說下一段繼續",
                AnnouncementPriority.USER_RESPONSE,
            )
        }
        speakSegment(session.next())
    }

    private fun readNextSegment() {
        val session = readingSession
        if (session == null) {
            announce(MESSAGE_NOTHING_TO_READ, AnnouncementPriority.USER_RESPONSE)
            return
        }

        val segment = session.next()
        if (segment == null) {
            announce(MESSAGE_READING_FINISHED, AnnouncementPriority.USER_RESPONSE)
            return
        }
        speakSegment(segment)
    }

    private fun readPreviousSegment() {
        val session = readingSession
        if (session == null) {
            announce(MESSAGE_NOTHING_TO_READ, AnnouncementPriority.USER_RESPONSE)
            return
        }
        speakSegment(session.previous())
    }

    /**
     * 朗讀一段。
     *
     * 用 AMBIENT 而不是 USER_RESPONSE —— 長文朗讀應該讓路給導航提示與
     * 危險警示。`resumable = true` 讓它被打斷後能續播。
     */
    private fun speakSegment(segment: String?) {
        if (segment == null) {
            announce(MESSAGE_READING_FINISHED, AnnouncementPriority.USER_RESPONSE)
            return
        }
        lastSpoken = segment
        _state.update { it.copy(lastReply = segment) }
        announcementManager.announce(
            Announcement(segment, AnnouncementPriority.AMBIENT, resumable = true),
        )
    }

    // ===== 障礙物 =====

    /**
     * 回應「前面有什麼」。
     *
     * 先講一句再開始 —— 拍照加推論要一兩秒，中間完全沒有聲音會讓看不見畫面
     * 的使用者以為系統當掉。與 OCR、人臉同步同一個處理方式。
     */
    private fun detectObstacles() {
        announce(MESSAGE_SCANNING_AHEAD, AnnouncementPriority.USER_RESPONSE)

        viewModelScope.launch {
            when (val outcome = detectObstacles.execute()) {
                is DetectObstaclesUseCase.Outcome.Detected -> {
                    Log.i(
                        TAG,
                        "障礙物 ${outcome.detections.size} 個：" +
                            outcome.detections.joinToString {
                                "${it.type.name}@${"%.2f".format(it.confidence)}"
                            },
                    )
                    announce(outcome.spoken, AnnouncementPriority.USER_RESPONSE)
                }

                DetectObstaclesUseCase.Outcome.NothingDetected ->
                    announce(MESSAGE_NOTHING_AHEAD, AnnouncementPriority.USER_RESPONSE)

                DetectObstaclesUseCase.Outcome.Unavailable ->
                    announce(MESSAGE_OBSTACLE_UNAVAILABLE, AnnouncementPriority.USER_RESPONSE)

                is DetectObstaclesUseCase.Outcome.Failed ->
                    announce(messageFor(outcome.error), AnnouncementPriority.USER_RESPONSE)
            }
        }
    }

    // ===== 出門前準備 =====

    /**
     * 出門前檢查。
     *
     * 眼鏡沒有 SIM，出了 Wi-Fi 範圍就沒有網路。這個檢查回答一個問題：
     * **現在拔掉網路，還有哪些功能能用？**
     */
    private fun checkReadiness() {
        viewModelScope.launch {
            val report = readinessCheck.execute()
            announce(report.spoken, AnnouncementPriority.USER_RESPONSE)
        }
    }

    /**
     * 預先下載翻譯語言包。
     *
     * 每種語言約 30MB，下載可能要幾十秒，所以先講一句再開始。
     */
    private fun prepareTranslation() {
        announce(MESSAGE_PREPARING_TRANSLATION, AnnouncementPriority.USER_RESPONSE)

        viewModelScope.launch {
            when (val outcome = prepareLanguages.execute()) {
                is PrepareLanguagesUseCase.Outcome.Finished ->
                    announce(outcome.spoken, AnnouncementPriority.USER_RESPONSE)

                PrepareLanguagesUseCase.Outcome.Unavailable ->
                    announce(MESSAGE_TRANSLATE_UNAVAILABLE, AnnouncementPriority.USER_RESPONSE)
            }
        }
    }

    // ===== 翻譯 =====

    /**
     * 翻譯。
     *
     * 兩個來源，優先序刻意如此：
     *
     * 1. LLM 帶來的 `text` 參數（「把『謝謝』翻成日文」）
     * 2. **上一次 OCR 讀到的內容** —— 這是不用 BFF 就能用的路徑，
     *    也是最實用的組合：說「唸給我聽」聽菜單，再說「翻成英文」。
     *
     * @param targetLanguage 語言碼或中文名稱。null／無法解析時套用預設（英文）。
     */
    private fun translate(text: String?, targetLanguage: String?) {
        val source = text?.takeIf { it.isNotBlank() } ?: lastReadText

        if (source.isNullOrBlank()) {
            announce(MESSAGE_NOTHING_TO_TRANSLATE, AnnouncementPriority.USER_RESPONSE)
            return
        }

        val target = targetLanguage?.let { TargetLanguage.fromCodeOrName(it) }

        viewModelScope.launch {
            val outcome = translateText.execute(
                text = source,
                target = target,
                // 語言包首次下載可能要幾十秒。不先講一句，使用者會以為當掉了。
                onPreparing = { language ->
                    announce(
                        "正在準備${language.spokenName}翻譯，第一次使用需要下載，請稍等",
                        AnnouncementPriority.USER_RESPONSE,
                    )
                },
            )

            when (outcome) {
                is TranslateUseCase.Outcome.Translated -> {
                    if (outcome.truncated) {
                        announce(MESSAGE_TRANSLATE_TRUNCATED, AnnouncementPriority.USER_RESPONSE)
                    }
                    lastSpoken = outcome.spoken
                    _state.update { it.copy(lastReply = outcome.spoken) }
                    // languageTag 是關鍵 —— 沒有它，TTS 會用中文語音唸英文，
                    // 結果幾乎聽不懂。
                    announcementManager.announce(
                        Announcement(
                            text = outcome.spoken,
                            priority = AnnouncementPriority.USER_RESPONSE,
                            languageTag = outcome.target.code,
                        ),
                    )
                }

                TranslateUseCase.Outcome.NothingToTranslate ->
                    announce(MESSAGE_NOTHING_TO_TRANSLATE, AnnouncementPriority.USER_RESPONSE)

                TranslateUseCase.Outcome.Unavailable ->
                    announce(MESSAGE_TRANSLATE_UNAVAILABLE, AnnouncementPriority.USER_RESPONSE)

                is TranslateUseCase.Outcome.Failed ->
                    announce(translateFailureFor(outcome.error), AnnouncementPriority.USER_RESPONSE)
            }
        }
    }

    // ===== 人臉辨識 =====

    private fun identifyPersonAhead() {
        viewModelScope.launch {
            when (val outcome = identifyPerson.execute()) {
                is IdentifyPersonUseCase.Outcome.Identified -> {
                    // 相似度是校正閾值的唯一依據，但播報裡只有「是」「可能是」
                    // 「不認識」三種說法 —— 不印出來就沒有人知道差多少，
                    // 也就無從判斷閾值該不該調。眼鏡戴在頭上拿不到畫面，
                    // 這個數字只能靠 logcat。
                    Log.i(
                        TAG,
                        "辨識結果 similarity=${"%.3f".format(outcome.match.similarity)} " +
                            "band=${outcome.match::class.simpleName} source=${outcome.source}",
                    )
                    lastSpoken = outcome.spoken
                    _state.update { it.copy(lastReply = outcome.spoken) }
                    // 用 dedupeKey 讓同一個人連續被辨識到時不會一直重複播報。
                    announcementManager.announce(
                        Announcement(
                            text = outcome.spoken,
                            priority = AnnouncementPriority.USER_RESPONSE,
                            dedupeKey = outcome.dedupeKey,
                        ),
                    )
                }

                IdentifyPersonUseCase.Outcome.NoFaceDetected ->
                    announce(MESSAGE_NO_FACE, AnnouncementPriority.USER_RESPONSE)

                is IdentifyPersonUseCase.Outcome.Failed ->
                    announce(messageFor(outcome.error), AnnouncementPriority.USER_RESPONSE)
            }
        }
    }

    /**
     * 從註冊工具同步人臉。
     *
     * 同步幾十張照片可能要十幾秒，中間完全沒有聲音會讓看不見畫面的使用者
     * 以為系統當掉，所以先講一句再開始。
     */
    private fun syncPeople() {
        announce(MESSAGE_SYNC_STARTED, AnnouncementPriority.USER_RESPONSE)

        viewModelScope.launch {
            when (val outcome = syncPeople.execute()) {
                is SyncPeopleUseCase.Outcome.Completed -> {
                    // coherence 只在低於門檻時才會被播報出來，但它是校正辨識閾值的
                    // 基準線 —— 同一個人不同照片能拿到多少分，決定了「認得出來」
                    // 應該設在哪。正常時也要留下紀錄。
                    Log.i(
                        TAG,
                        "同步完成 people=${outcome.people} photos=${outcome.photos} " +
                            "skipped=${outcome.skippedPhotos} coherence=${outcome.coherence}",
                    )
                    announce(outcome.spoken, AnnouncementPriority.USER_RESPONSE)
                }

                SyncPeopleUseCase.Outcome.SourceUnavailable ->
                    announce(MESSAGE_SYNC_NO_SOURCE, AnnouncementPriority.USER_RESPONSE)

                SyncPeopleUseCase.Outcome.ModelUnavailable ->
                    announce(MESSAGE_SYNC_NO_MODEL, AnnouncementPriority.USER_RESPONSE)

                SyncPeopleUseCase.Outcome.NothingToSync ->
                    announce(MESSAGE_SYNC_EMPTY, AnnouncementPriority.USER_RESPONSE)

                is SyncPeopleUseCase.Outcome.Failed ->
                    announce(messageFor(outcome.error), AnnouncementPriority.USER_RESPONSE)
            }
        }
    }

    /**
     * 把眼前的人記起來。
     *
     * **人臉是生物特徵，未經同意建檔在臺灣涉及個資法。** 因此註冊前一定
     * 先播報一句提示，讓當事人知道正在發生什麼事 —— 這不只是法律要求，
     * 也是基本的尊重。
     */
    private fun registerPerson(name: String, relation: String?) {
        if (name.isBlank()) {
            announce(MESSAGE_REGISTER_NEED_NAME, AnnouncementPriority.USER_RESPONSE)
            return
        }

        announce(MESSAGE_REGISTER_CONSENT, AnnouncementPriority.USER_RESPONSE)

        viewModelScope.launch {
            when (val outcome = registerFace.execute(name, relation)) {
                is RegisterFaceUseCase.Outcome.Registered ->
                    announce(outcome.spoken, AnnouncementPriority.USER_RESPONSE)

                RegisterFaceUseCase.Outcome.NoFaceDetected ->
                    announce(MESSAGE_NO_FACE, AnnouncementPriority.USER_RESPONSE)

                is RegisterFaceUseCase.Outcome.MultipleFaces ->
                    announce(
                        "看到 ${outcome.count} 個人，請確認只有一個人在鏡頭前",
                        AnnouncementPriority.USER_RESPONSE,
                    )

                RegisterFaceUseCase.Outcome.InvalidName ->
                    announce(MESSAGE_REGISTER_NEED_NAME, AnnouncementPriority.USER_RESPONSE)

                is RegisterFaceUseCase.Outcome.Failed ->
                    announce(messageFor(outcome.error), AnnouncementPriority.USER_RESPONSE)
            }
        }
    }

    private fun announce(text: String, priority: AnnouncementPriority) {
        lastSpoken = text
        _state.update { it.copy(lastReply = text) }
        announcementManager.announce(Announcement(text, priority))
    }

    /**
     * 翻譯失敗的說法。
     *
     * 不能沿用 [messageFor] —— 它把 [AppError.NoResult] 一律翻成
     * 「我沒有聽到，請再說一次」，那是給語音辨識用的。翻譯引擎失敗時
     * 播這句，使用者會以為是自己講得不夠大聲，然後一直重講一句
     * 其實已經被正確聽到的話。
     */
    private fun translateFailureFor(error: AppError): String = when (error) {
        is AppError.NoNetwork -> MESSAGE_TRANSLATE_NEEDS_NETWORK
        is AppError.NoResult -> MESSAGE_TRANSLATE_NOT_PREPARED
        is AppError.CapabilityUnavailable -> MESSAGE_TRANSLATE_UNAVAILABLE
        else -> MESSAGE_TRANSLATE_NOT_PREPARED
    }

    /**
     * 把領域錯誤翻成使用者聽得懂的話。絕不播報錯誤碼或例外訊息。
     *
     * [AppError.CapabilityUnavailable] 要再依 `capability` 分岔：
     * 「沒有語音辨識服務」和「有服務但缺中文語音資料」對使用者是完全
     * 不同的兩件事 —— 前者無解，後者去設定下載就好。全部收斂成同一句
     * 等於沒有告訴他任何事。
     */
    private fun messageFor(error: AppError): String = when (error) {
        is AppError.NoResult -> MESSAGE_NOT_HEARD
        is AppError.NoNetwork -> MESSAGE_NO_NETWORK
        is AppError.PermissionDenied -> MESSAGE_NO_MIC_PERMISSION
        is AppError.CapabilityUnavailable -> when (error.capability) {
            SpeechCapability.LANGUAGE_PACK -> MESSAGE_NO_SPEECH_LANGUAGE
            SpeechCapability.BUSY -> MESSAGE_RECOGNIZER_BUSY
            SpeechCapability.MICROPHONE -> MESSAGE_MIC_BUSY
            else -> MESSAGE_NO_ASR
        }
        else -> MESSAGE_GENERIC_FAILURE
    }

    override fun onCleared() {
        super.onCleared()
        listeningJob?.cancel()
        speechGateway.shutdown()
    }

    enum class Phase { IDLE, LISTENING, THINKING }

    data class AssistantUiState(
        val phase: Phase = Phase.IDLE,
        val transcript: String = "",
        val lastReply: String = "",
        val routedFrom: RoutedIntent.Source? = null,
    )

    private companion object {
        const val TAG = "Assistant"

        const val MESSAGE_NOT_HEARD = "我沒有聽到，請再說一次"
        const val MESSAGE_NO_NETWORK = "目前沒有網路"
        const val MESSAGE_NO_MIC_PERMISSION = "需要麥克風權限才能聽你說話，請到設定中開啟"
        const val MESSAGE_NO_ASR = "這台裝置沒有可用的語音辨識服務"
        const val MESSAGE_NO_SPEECH_LANGUAGE =
            "這台裝置沒有中文語音辨識資料。請到系統設定的語音輸入中下載中文，再試一次"
        const val MESSAGE_RECOGNIZER_BUSY = "我還在處理上一句，請稍等一下再說"
        const val MESSAGE_MIC_BUSY = "麥克風好像正被其他程式使用中"
        const val MESSAGE_GENERIC_FAILURE = "我現在無法處理，請再試一次"
        const val MESSAGE_NOTHING_TO_REPEAT = "目前沒有可以重複的內容"
        const val MESSAGE_CAMERA_TESTING = "正在測試相機"
        const val MESSAGE_READING_CAPTURING = "正在辨識文字"
        const val MESSAGE_NO_TEXT = "沒有看到文字，請調整角度或靠近一點"
        const val MESSAGE_NOTHING_TO_READ = "目前沒有正在朗讀的內容"
        const val MESSAGE_READING_FINISHED = "已經唸完了"
        const val MESSAGE_NO_FACE = "前方沒有偵測到人"
        const val MESSAGE_REGISTER_NEED_NAME = "請告訴我要記成什麼名字"
        const val MESSAGE_REGISTER_CONSENT = "正在記住這個人的臉，請確認對方同意"
        const val MESSAGE_NOTHING_TO_TRANSLATE =
            "沒有可以翻譯的內容。你可以先說「唸給我聽」，再說「翻成英文」"
        const val MESSAGE_TRANSLATE_UNAVAILABLE = "翻譯功能目前不可用"
        const val MESSAGE_TRANSLATE_NEEDS_NETWORK =
            "翻譯的語言包還沒下載完成，需要網路。請連上網路後說「準備翻譯」"
        const val MESSAGE_TRANSLATE_NOT_PREPARED =
            "翻譯失敗，語言包可能不完整。請說「準備翻譯」重新下載後再試一次"
        const val MESSAGE_TRANSLATE_TRUNCATED = "內容較長，只翻譯前面的部分"
        const val MESSAGE_SYNC_STARTED = "正在同步人臉，請稍等"
        const val MESSAGE_SYNC_NO_SOURCE = "還沒設定註冊工具的位址"
        const val MESSAGE_SYNC_NO_MODEL = "缺少人臉模型檔，無法同步"
        const val MESSAGE_SYNC_EMPTY = "註冊工具上還沒有任何人"
        const val MESSAGE_PREPARING_TRANSLATION = "正在下載語言包，需要網路，請稍等"
        const val MESSAGE_SCANNING_AHEAD = "正在看前面"
        const val MESSAGE_NOTHING_AHEAD = "前面沒有偵測到障礙物"
        const val MESSAGE_OBSTACLE_UNAVAILABLE = "障礙物偵測目前不可用，缺少模型檔"
    }
}
