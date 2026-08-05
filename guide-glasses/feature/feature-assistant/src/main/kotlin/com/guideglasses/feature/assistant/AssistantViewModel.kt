package com.guideglasses.feature.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guideglasses.core.domain.AppError
import com.guideglasses.core.domain.announce.Announcement
import com.guideglasses.core.domain.announce.AnnouncementManager
import com.guideglasses.core.domain.announce.AnnouncementPriority
import com.guideglasses.core.domain.assistant.AssistantIntent
import com.guideglasses.core.domain.assistant.IntentRouter
import com.guideglasses.core.domain.assistant.RoutedIntent
import com.guideglasses.core.domain.glasses.CameraSelfTestUseCase
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
) : ViewModel() {

    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    private var listeningJob: Job? = null

    /** 上一次播報的內容，供「再說一次」使用。 */
    private var lastSpoken: String? = null

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

            AssistantIntent.CHAT ->
                announce(
                    routed.spokenReply ?: MESSAGE_GENERIC_FAILURE,
                    AnnouncementPriority.USER_RESPONSE,
                )

            // 以下功能將在 Phase 3 起陸續實作。
            // 明確說「還在開發中」，而不是靜默無回應 ——
            // 對看不見畫面的使用者，沒有聲音等於系統當掉。
            AssistantIntent.DETECT_OBSTACLES,
            AssistantIntent.IDENTIFY_PERSON,
            AssistantIntent.READ_TEXT,
            AssistantIntent.NAVIGATE,
            AssistantIntent.TRANSLATE,
            AssistantIntent.REGISTER_FACE,
            -> announce(
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

    private fun announce(text: String, priority: AnnouncementPriority) {
        lastSpoken = text
        _state.update { it.copy(lastReply = text) }
        announcementManager.announce(Announcement(text, priority))
    }

    /** 把領域錯誤翻成使用者聽得懂的話。絕不播報錯誤碼或例外訊息。 */
    private fun messageFor(error: AppError): String = when (error) {
        is AppError.NoResult -> MESSAGE_NOT_HEARD
        is AppError.NoNetwork -> MESSAGE_NO_NETWORK
        is AppError.PermissionDenied -> MESSAGE_NO_MIC_PERMISSION
        is AppError.CapabilityUnavailable -> MESSAGE_NO_ASR
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
        const val MESSAGE_NOT_HEARD = "我沒有聽到，請再說一次"
        const val MESSAGE_NO_NETWORK = "目前沒有網路"
        const val MESSAGE_NO_MIC_PERMISSION = "需要麥克風權限才能聽你說話，請到設定中開啟"
        const val MESSAGE_NO_ASR = "這台裝置沒有可用的語音辨識服務"
        const val MESSAGE_GENERIC_FAILURE = "我現在無法處理，請再試一次"
        const val MESSAGE_NOTHING_TO_REPEAT = "目前沒有可以重複的內容"
        const val MESSAGE_CAMERA_TESTING = "正在測試相機"
    }
}
