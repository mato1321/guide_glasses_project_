package com.example.rokidglasses_project.utils

import android.view.View
import android.widget.Button
import android.widget.TextView

/**
 * 無障礙輔助類
 * 為視障用戶提供語音反饋
 */
class AccessibilityHelper {

    companion object {

        /**
         * 設置按鈕的無障礙描述
         */
        fun setupButtonAccessibility(
            button: Button,
            label: String,
            description: String
        ) {
            button.contentDescription = "$label，$description"
        }

        /**
         * 設置文字視圖的無障礙描述
         */
        fun setupTextViewAccessibility(
            textView: TextView,
            description: String
        ) {
            textView.contentDescription = description
        }

        /**
         * 宣佈狀態變化（用於視障用戶）
         */
        fun announceStatus(view: View, message: String) {
            view.announceForAccessibility(message)
        }
    }
}

