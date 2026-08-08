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
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.guideglasses.feature.assistant.AssistantViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 助理主畫面。
 *
 * 設計取向和一般 App 相反：畫面資訊是給陪同者與低視力使用者看的，
 * 主要使用者靠的是語音。因此觸發區做成整片可點擊的大按鈕 ——
 * 看不見的人不必尋找按鈕在哪，點畫面任何地方都有效。
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: AssistantViewModel by viewModels()

    private lateinit var tvStatus: TextView
    private lateinit var tvTranscript: TextView
    private lateinit var tvReply: TextView
    private lateinit var btnTalk: Button
    private lateinit var btnStop: Button

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
            viewModel.onAssistantTriggered()
        } else {
            tvStatus.text = getString(R.string.status_need_mic)
            tvStatus.announceForAccessibility(getString(R.string.status_need_mic))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvTranscript = findViewById(R.id.tvTranscript)
        tvReply = findViewById(R.id.tvReply)
        btnTalk = findViewById(R.id.btnTalk)
        btnStop = findViewById(R.id.btnStop)

        btnTalk.setOnClickListener { triggerAssistant() }
        btnStop.setOnClickListener { viewModel.onStopRequested() }

        observeState()
        registerDebugTrigger()

        // 眼鏡上 App 退到背景 2.4 秒就會被系統回收，而導盲的使用情境
        // 本來就是螢幕關著的。沒有這一行，整套系統在真實情境下等於不存在。
        GuideGlassesForegroundService.start(this)
    }

    /**
     * 開發用廣播入口。**只在 debug build 註冊。**
     *
     * Rokid Glasses 上沒有語音辨識服務，說話這條路完全走不通
     * （`docs/DEVICE_FINDINGS.md` §3）。沒有這個入口，眼鏡上除了「看 log」
     * 之外沒有任何辦法驗證功能是否正確。
     *
     * ```bash
     * adb shell am broadcast -a com.guideglasses.DEBUG --es cmd READ_TEXT
     * adb shell am broadcast -a com.guideglasses.DEBUG --es cmd TRANSLATE --es target_language ja
     * ```
     *
     * ⚠️ **相機相關的指令要先讓 App 離開 idle**，否則 Android 會擋：
     * `Access Denial: can't use the camera from an idle UID`
     *
     * ```bash
     * adb shell am set-inactive com.guideglasses false
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
        val missing = REQUIRED_PERMISSIONS.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) !=
                PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            viewModel.onAssistantTriggered()
        } else {
            requestPermissions.launch(missing.toTypedArray())
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

        // 按鈕文字隨狀態改變，TalkBack 才會唸出「停止聆聽」而不是永遠唸「說話」。
        btnTalk.text = when (state.phase) {
            AssistantViewModel.Phase.LISTENING -> getString(R.string.action_cancel_listening)
            else -> getString(R.string.action_talk)
        }
        btnTalk.contentDescription = btnTalk.text

        tvTranscript.text = state.transcript
        tvTranscript.visibility = if (state.transcript.isBlank()) View.GONE else View.VISIBLE

        tvReply.text = state.lastReply
        tvReply.visibility = if (state.lastReply.isBlank()) View.GONE else View.VISIBLE
    }

    private companion object {
        const val DEBUG_ACTION = "com.guideglasses.DEBUG"
        const val DEBUG_TAG = "DebugTrigger"

        val REQUIRED_PERMISSIONS = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
        )
    }
}
