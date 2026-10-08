package com.guideglasses

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.guideglasses.ai.asr.MicrophoneProbe
import com.guideglasses.core.domain.backend.BackendUrl
import com.guideglasses.di.BackendUrlOverride
import com.guideglasses.feature.assistant.AssistantViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * 助理主畫面。
 *
 * 設計取向和一般 App 相反：畫面資訊是給陪同者與低視力使用者看的，
 * 主要使用者靠的是語音。所以畫面上**沒有任何按鈕** —— 戴著眼鏡要先在觸控板上
 * 滑到按鈕再點，看不見的人做不到。一切都用說的（見 `VoiceCommand`），
 * 觸控板點一下是吵雜環境下的備用方式（[onKeyUp]），不必先滑到哪裡。
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: AssistantViewModel by viewModels()

    /** 執行期的後端網址，debug 廣播 SET_BACKEND 會改它，見 [registerDebugTrigger]。 */
    @Inject
    lateinit var backendUrl: BackendUrlOverride

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView

    /** 開發用廣播接收器，只在 debug build 存在。 */
    private var debugReceiver: BroadcastReceiver? = null

    /**
     * 麥克風與相機一起要。
     *
     * 分兩次問會讓看不見畫面的使用者連續面對兩個對話框，體驗很差；
     * 而且這兩個權限本來就是「用這套系統」的最低門檻。
     */
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val micGranted = results[Manifest.permission.RECORD_AUDIO] == true

        // 沒有相機只是視覺功能不能用，語音助理仍可運作，所以不擋。
        if (micGranted) {
            viewModel.startWakeWordListening()
        } else {
            tvStatus.text = getString(R.string.status_need_mic)
            tvStatus.announceForAccessibility(getString(R.string.status_need_mic))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 眼鏡上可能同時裝著其他版本（Cloudflare／AWS）。使用者叫出的是這一版，
        // 就由這一版接管相機與麥克風 —— 要在 onStart 開始聽喚醒詞之前先通知對方讓出來。
        SensorHandoff.claim(this)
        watchForHandoff()

        /*
         * 眼鏡的螢幕逾時只有 5 秒，一暗掉 Activity 就不再是前景，
         * 連帶影響觸控、除錯廣播與麥克風的 appop（RECORD_AUDIO 是
         * foreground 模式）。
         *
         * 用視窗旗標而不是改系統的 screen_off_timeout —— 這樣只在本 App
         * 在前景時生效，關掉 App 就恢復裝置原本的行為，不留下副作用。
         *
         * 耗電是刻意接受的：使用者明確表示會外接行動電源，而螢幕一直暗掉
         * 造成的操作中斷比續航更痛。
         *
         * portable 曾把這行註解掉，眼鏡實測的後果（2026-09-29）：每 5 秒螢幕一暗，
         * App 瞬間掉到背景（procState 10、capability `----`），錄音在那一瞬間被
         * 靜音，而且之後不會自動解除 —— 語音指令從此聽不到。背景限制
         * （RUN_ANY_IN_BACKGROUND）沒解除時前景服務也撐不住，只剩這一道防線。
         */
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
        observeState()
        registerDebugTrigger()
        // 重建（例如權限對話框還開著時被旋轉）不要再問一次，對話框會疊起來。
        if (savedInstanceState == null) requestMissingPermissions()

        // 眼鏡上 App 退到背景 2.4 秒就會被系統回收，而導盲的使用情境
        // 本來就是螢幕關著的。沒有這一行，整套系統在真實情境下等於不存在。
        //
        // portable 曾把這行註解掉：當時缺 ASR/KWS 模型檔，sherpa-onnx 在原生層崩潰，
        // 被誤判成裝置不相容。模型補回後恢復。多版本同時安裝時，被接管的一方會
        // 在 SensorHandoff 的回呼裡停掉服務，START_STICKY 不會讓它回來搶。
        GuideGlassesForegroundService.start(this)
    }

    /**
     * 別的版本接管時關閉畫面。
     *
     * 用 `finishAndRemoveTask` 而不是只退到背景：Activity 銷毀 → ViewModel 清掉 →
     * 喚醒監聽、持續偵測、導航的 job 全部取消，麥克風與相機在各自的清理流程釋放。
     * 只退到背景的話那些 job 還活著，會繼續拿著麥克風（被靜音）與相機。
     *
     * 不用 `repeatOnLifecycle`：被接管時這一版多半已經不在前景（STOPPED），
     * 那樣會收不到。
     */
    private fun watchForHandoff() {
        lifecycleScope.launch {
            SensorHandoff.releaseRequests.collect { claimant ->
                Log.i(TAG, "$claimant 接管了，關閉畫面")
                finishAndRemoveTask()
            }
        }
    }

    /**
     * 開發用廣播入口。**只在 debug build 註冊。**
     *
     * Rokid Glasses 上沒有語音辨識服務，說話這條路完全走不通
     * （`docs/DEVICE_FINDINGS.md` §3）。沒有這個入口，眼鏡上除了「看 log」
     * 之外沒有任何辦法驗證功能是否正確。
     *
     * action 是 `<applicationId>.DEBUG`，每個版本只收自己的廣播。
     * 以前寫死 `com.guideglasses.DEBUG`，三版同時裝在眼鏡上時，一次廣播會讓
     * 三個 App 同時動作（而且都想搶相機與麥克風）。AWS 版把 `.cloudflare` 換成 `.aws`：
     *
     * ```bash
     * adb shell am broadcast -a com.guideglasses.cloudflare.DEBUG --es cmd READ_TEXT
     * adb shell am broadcast -a com.guideglasses.cloudflare.DEBUG --es cmd TRANSLATE --es target_language ja
     * adb shell am broadcast -a com.guideglasses.cloudflare.DEBUG --es cmd SET_BACKEND --es url https://xxxx.trycloudflare.com
     * ```
     *
     * ⚠️ **相機相關的指令要先讓 App 離開 idle**，否則 Android 會擋：
     * `Access Denial: can't use the camera from an idle UID`
     *
     * ```bash
     * adb shell am set-inactive com.guideglasses.cloudflare false
     * adb shell svc power stayon true
     * ```
     */
    private fun registerDebugTrigger() {
        if (!BuildConfig.DEBUG) return

        debugReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (val cmd = intent.getStringExtra("cmd").orEmpty()) {
                    "" -> Log.w(DEBUG_TAG, "缺少 --es cmd")

                    /*
                     * 開始聆聽。
                     *
                     * 其他指令都是「直接執行某個功能」，只有這個是走
                     * 完整的「聽 → 辨識 → 路由」流程，用來驗證離線 ASR。
                     *
                     * 需要獨立指令是因為眼鏡的螢幕逾時只有 5 秒，
                     * 睡著之後 `input tap` 點不到按鈕，而且 launcher
                     * 會把焦點搶走 —— UI 點擊在這台機器上不是可靠的測試方式。
                     */
                    "LISTEN" -> {
                        Log.i(DEBUG_TAG, "LISTEN：開始聆聽")
                        triggerAssistant()
                    }

                    /*
                     * 逐一測試每種音訊來源。
                     *
                     * 眼鏡上 VOICE_RECOGNITION 收到的全是 0，而權限、appop、
                     * 佔用、靜音、行程狀態全部正常 —— 廠商只實作部分音訊來源
                     * 是很常見的事，只能一個一個試。測試時要持續說話。
                     */
                    "MIC_TEST" -> Thread { MicrophoneProbe.runAll() }.start()

                    /*
                     * 換後端網址（Cloudflare 通道重開了），不必重新建置，見 BackendUrlOverride。
                     * 連得到手機直連的話在手機 App 上改就好，眼鏡會自己同步；這個給連不到手機時用。
                     * `--es url reset` 改回建置時的網址。
                     */
                    "SET_BACKEND" -> {
                        val url = intent.getStringExtra("url").orEmpty()
                        when {
                            url == "reset" -> backendUrl.reset()
                            BackendUrl.normalize(url) == null -> Log.w(DEBUG_TAG, "SET_BACKEND：網址格式不對「$url」")
                            else -> backendUrl.set(url)
                        }
                        Log.i(DEBUG_TAG, "後端網址：${backendUrl.effective()}")
                    }

                    /*
                     * 把一句話當成語音辨識結果送進去（一般聊天、LLM 意圖解析），
                     * 不必真的對眼鏡說話。見 AssistantViewModel.debugUtterance。
                     */
                    "ASK" -> {
                        val text = intent.getStringExtra("text").orEmpty()
                        if (text.isBlank()) Log.w(DEBUG_TAG, "ASK：缺少 --es text") else viewModel.debugUtterance(text)
                    }

                    else -> {
                        val args = buildMap {
                            intent.getStringExtra("target_language")?.let { put("target_language", it) }
                            intent.getStringExtra("text")?.let { put("text", it) }
                            intent.getStringExtra("name")?.let { put("name", it) }
                        }
                        viewModel.debugDispatch(cmd, args)
                    }
                }
            }
        }

        ContextCompat.registerReceiver(
            this,
            debugReceiver,
            IntentFilter(DEBUG_ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )
        Log.i(DEBUG_TAG, "已註冊。用法：adb shell am broadcast -a $DEBUG_ACTION --es cmd <INTENT名稱>")
    }

    override fun onDestroy() {
        super.onDestroy()
        debugReceiver?.let { runCatching { unregisterReceiver(it) } }
        debugReceiver = null
    }

    private fun triggerAssistant() {
        val missing = missingPermissions()

        if (missing.isEmpty()) {
            viewModel.onAssistantTriggered()
        } else {
            requestPermissions.launch(missing.toTypedArray())
        }
    }

    /**
     * 缺權限就一開 App 馬上要。
     *
     * 以前只在按「說話」按鈕時才要，按鈕拿掉之後就沒有地方會要了 ——
     * 新裝的 App 會安靜地什麼都聽不到。用 `adb install -g` 安裝時權限已經給了，
     * 不會跳出任何對話框；否則系統的對話框仍要按一次「允許」。
     */
    private fun requestMissingPermissions() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) requestPermissions.launch(missing.toTypedArray())
    }

    private fun missingPermissions(): List<String> = REQUIRED_PERMISSIONS.filter { permission ->
        ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
    }

    /**
     * 觸控板點一下＝開始聆聽（聆聽中再點一下＝取消），跟說「我要說話」一樣。
     *
     * 畫面上沒有能取得焦點的按鈕，按鍵事件直接落到這裡，不必先滑動選到哪裡。
     * Rokid 觸控板點一下送出哪個按鍵代碼還沒在實機確認過，先接受常見的「確認」鍵，
     * 並把每個按鍵記進 log，上機時對照（`adb logcat -s MainActivity`）。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        Log.i(TAG, "按鍵：${KeyEvent.keyCodeToString(keyCode)}")
        if (keyCode in TAP_KEYS) {
            // 要追蹤才能在 onKeyUp 分辨「點一下」與被取消的按鍵。
            event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in TAP_KEYS && event.isTracking && !event.isCanceled) {
            Log.i(TAG, "觸控板點一下 → 開始聆聽")
            triggerAssistant()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * 有麥克風權限就開始聽喚醒詞。
     *
     * 放在 [onStart] 而不是 [onCreate]：使用者從別的 App 切回來時
     * 要能重新開始聽 —— 離開時 ViewModel 會把麥克風讓出去。
     */
    override fun onStart() {
        super.onStart()
        // 使用者可能按音量鍵調小過；回到畫面就拉回最大，見 AnnouncementVolume。
        AnnouncementVolume.maximize(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.startWakeWordListening()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: AssistantViewModel.AssistantUiState) {
        val statusText = when (state.phase) {
            AssistantViewModel.Phase.IDLE -> getString(R.string.status_idle)
            AssistantViewModel.Phase.LISTENING -> getString(R.string.status_listening)
            AssistantViewModel.Phase.THINKING -> getString(R.string.status_thinking)
        }
        tvStatus.text = statusText

        // 對話記錄。新內容在最下面，所以自動捲到底 ——
        // 使用者要看的永遠是「剛剛發生了什麼」。
        // 「畫面沒有文字」有兩種完全不同的原因：狀態沒傳到這裡，
        // 或是傳到了但看不見。沒有這行就只能靠猜 —— 這台裝置的
        // uiautomator 抓不到畫面內容，猜是很貴的。
        Log.d(TAG, "render：${state.log.size} 行記錄，phase=${state.phase}")

        val text = state.log.joinToString(separator = System.lineSeparator())
        if (tvLog.text.toString() != text) {
            tvLog.text = text
            scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private companion object {
        const val TAG = "MainActivity"
        // 跟著 applicationId 走（com.guideglasses.cloudflare.DEBUG ⋯），見 registerDebugTrigger。
        const val DEBUG_ACTION = BuildConfig.APPLICATION_ID + ".DEBUG"
        const val DEBUG_TAG = "DebugTrigger"

        /** 觸控板「點一下」可能送出的按鍵，見 [onKeyUp]。 */
        val TAP_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)

        val REQUIRED_PERMISSIONS = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            // 沒有這個，GlassesGpsLocationProvider.isAvailable 永遠是 false——
            // 就算裝置本身有 GPS，也會被迫一律退回手機 companion。
            // 見 docs/ARCHITECTURE.md §5.3 的 LocationProvider 綁定順序。
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }
}
