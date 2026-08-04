package com.guideglasses

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * 單一 Activity 入口。
 *
 * 目前是 Phase 1 的骨架，尚未掛上任何功能畫面。
 * 功能會在 Phase 2 起以 feature 模組的形式接入。
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
