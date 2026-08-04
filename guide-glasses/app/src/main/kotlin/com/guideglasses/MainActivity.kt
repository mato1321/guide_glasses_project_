package com.guideglasses

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
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
    }

    private fun triggerAssistant() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            viewModel.onAssistantTriggered()
        } else {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
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
}
