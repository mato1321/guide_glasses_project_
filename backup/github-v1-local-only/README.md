# Guide Glasses — Rokid AI 導盲眼鏡

一套跑在 **Rokid Glasses** 上的智慧導盲系統。使用者用語音下指令，系統用語音回答。
單一 Android APK，直接安裝在眼鏡上執行。

| | |
|---|---|
| 最後更新 | 2026-08-09 |
| 完成度 | 約 94% |
| 單元測試 | **333 個，全過**（純 JVM，秒級） |
| APK | debug 288 MB |
| 實機狀態 | ✅ **語音指令、TTS、OCR、人臉、翻譯、障礙物、前景服務全部在眼鏡上實測可用** |

**使用者直接對眼鏡講話就會執行**，不需要喚醒詞也不需要按鈕 ——
「前面有什麼」「這是誰」「唸給我聽」「翻成英文」等 14 句。

---

## 📖 先看哪一份文件

| 你要做什麼 | 看這份 |
|---|---|
| **第一次接手** | [`docs/DEVELOPER_GUIDE.md`](docs/DEVELOPER_GUIDE.md) —— 從零建置到接手開發 |
| **眼鏡上跑不起來 / 沒聲音** | [`docs/DEVICE_FINDINGS.md`](docs/DEVICE_FINDINGS.md) —— 實機診斷，全部指令可重跑 |
| **拿到一台新眼鏡** | [`docs/PROVISIONING.md`](docs/PROVISIONING.md) —— 一次性佈建，不做的話什麼都不會動 |
| 現況與交接 | [`docs/STATUS.md`](docs/STATUS.md) |
| 待辦清單 | [`docs/TASKS.md`](docs/TASKS.md) |
| 分層架構決策 | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| 做簡報 | [`docs/PRESENTATION.md`](docs/PRESENTATION.md) |

---

## 🔴 這台裝置的核心教訓

**任何 API 呼叫「沒有拋例外」都不等於成功。**

開發過程中中過**七次**「回報成功但實際沒生效」：Rokid 的 `bindSecurityService`
不回呼、`startForeground` 被靜默拒絕、`VOICE_RECOGNITION` 音訊來源回傳純靜音、
`pm list features` 假宣告、`adb install` 回報 Success 但裝的是舊版⋯

所以這個專案的預設寫法是**主動查證**：量麥克風音量、查 `isForeground` 旗標、
核對 APK 大小、加逾時檢查。詳見 [`docs/DEVICE_FINDINGS.md`](docs/DEVICE_FINDINGS.md)。

---

## 📁 專案結構

```
guide_glasses_project_/
├── guide-glasses/          ★ 最終整合系統（只在這裡開發）
├── docs/                   專案文件
├── AI_Assistant/           組員工作區 —— 不可修改
├── Face_Recognition/       組員工作區 —— 不可修改
├── Obstacle_Recognition/   組員工作區 —— 不可修改
├── Audio_Navigation/       組員工作區 —— 不可修改
└── Text_Recognition/       組員工作區 —— 不可修改
```

**五個功能資料夾是五位組員各自的工作區，`guide-glasses` 不修改它們。**
需要引用時複製過來重新整合。

---

## 🚀 快速開始

```bash
git clone https://github.com/mato1321/guide_glasses_project_.git
```

建立 `guide-glasses/local.properties`（**唯一**需要自己補的檔案，注意跳脫字元）：

```
sdk.dir=C\:\Users\<你的帳號>\AppData\Local\Android\Sdk
```

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd guide-glasses && ./gradlew build
```

需要 **JDK 17+**（JDK 11 不行）與 Android SDK Platform 36。
完整步驟見 [`docs/DEVELOPER_GUIDE.md`](docs/DEVELOPER_GUIDE.md) §3。

---

## ⚠️ 三個必須知道的硬體事實

全部由 adb 實測確認（見 [`DEVICE_FINDINGS.md`](docs/DEVICE_FINDINGS.md)）：

| 事實 | 影響 |
|---|---|
| **沒有 Google Play Services** | STT 完全不可用；ML Kit 必須用 bundled / standalone 版 |
| **沒有語音辨識服務、TTS 綁定失敗** | 🔴 **目前無法用語音操作**，需靠 debug 廣播觸發 |
| **沒有 GPS、沒有電子羅盤** | 導航需手機提供座標，且**算不出朝向** |

> 這台眼鏡有一個反覆出現的陷阱：**`pm list features` 宣告有，實際上沒有**。
> GPS、前鏡頭、TTS 都中過。**任何硬體能力都要實測，不能看 API 宣告。**

---

## 📝 目前功能狀態

| 功能 | 狀態 |
|---|---|
| 語音指令（關鍵詞偵測，14 句） | ✅ 眼鏡實測可用，直接講直接做 |
| 語音合成（中文＋英文，22050Hz） | ✅ 眼鏡實測可用 |
| 語音辨識（開放式輸入） | ✅ 可用，2–3.5 秒 |
| OCR 朗讀 | ✅ 使用者實測可用（約 3 秒） |
| 人臉辨識 | ✅ 使用者實測可用 |
| 翻譯（中↔英） | ✅ 使用者實測可用 |
| 障礙物偵測（YOLOv8 八類） | 🟡 推論可跑，偵測率未測 |
| 前景服務（背景存活） | ✅ 眼鏡實測可用 |
| 導航 | 🔴 無 GPS 且無電子羅盤 |

⚠️ **每台眼鏡要先做一次性佈建**，見 [`docs/PROVISIONING.md`](docs/PROVISIONING.md)。

---

## Rokid 眼鏡 Android 開發框架

---

## **Android 前端結構**

```
android/
├── app/                                    # 應用主模塊
│   ├── src/main/
│   │   ├── java/com/rokid/ai_assistant/
│   │   │   └── MainActivity.kt            # 應用入口，權限檢查和主邏輯
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   └── activity_main.xml      # 定義應用的 UI 布局 
│   │   │   ├── values/
│   │   │   │   ├── strings.xml            # 應用文字常量
│   │   │   │   └── colors.xml             # 顏色定義
│   │   │   ├── mipmap/                    # 應用圖標
│   │   │   └── drawable/                  # 圖片資源
│   │   └── AndroidManifest.xml            # 權限、SDK 版本配置
│   ├── build.gradle.kts                   # 項目依賴和編譯配置
│   └── proguard-rules.pro                 # 代碼混淆規則
│
├── gradle/
│   └── libs.versions.toml                 # 依賴版本管理
│
├── build.gradle.kts                       # 根項目配置
├── gradle.properties                      # Gradle 全局配置
├── settings.gradle.kts                    # 項目設置
├── gradlew                                # Gradle 包裝腳本
└── README.md                              # 本文件
```

---

## **主要文件**

### **1. MainActivity.kt**

**位置：** `app/src/main/java/com/rokid/ai_assistant/MainActivity.kt`

**用途：** 應用入口，包含：
- 應用初始化
- 權限檢查和請求
- 全螢幕沉浸模式 (可修改為自己要的鏡片設計)

---

### **2. activity_main.xml**

**位置：** `app/src/main/res/layout/activity_main.xml`

**用途：** 定義應用的 UI 布局

---

### **3. AndroidManifest.xml** 

**位置：** `app/src/main/AndroidManifest.xml`

**用途：** 配置應用權限和 SDK 版本

**包含的權限：**

| 權限 | 用途 |
|------|------|
| `CAMERA` | 相機訪問 |
| `USB` | USB 設備連接（Rokid 眼鏡） |
| `INTERNET` | 網路訪問 |
| `RECORD_AUDIO` | 麥克風訪問 |
| `READ_EXTERNAL_STORAGE` | 讀取存儲 |
| `WRITE_EXTERNAL_STORAGE` | 寫入存儲 |

**修改權限方法：**

如果需要添加新權限，在 `<manifest>` 標籤下加入：

```xml
<uses-permission android:name="android.permission.新權限" />
```

---

### **4. build.gradle.kts** 

**位置：** `app/build.gradle.kts`

**用途：** 配置項目編譯選項和依賴庫

**重要配置：**

```kotlin
android {
    namespace = "com.rokid.ai_assistant"  // 應用包名
    compileSdk = 36                       // 編譯 SDK 版本
    
    defaultConfig {
        applicationId = "com.rokid.ai_assistant"
        minSdk = 26        // Rokid眼鏡最低 SDK
        targetSdk = 36
        versionCode = 1    // 版本號（每次發佈增加）
        versionName = "1.0" // 版本名稱
    }
}

dependencies {
    // 依賴放在這裡
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
```

**如何添加新依賴：**

在 `dependencies {}` 中加入：

```kotlin
implementation("group:artifact:version")
```

例如添加 Retrofit（網路請求庫）：

```kotlin
implementation("com.squareup.retrofit2:retrofit:2.9.0")
```

---

### **5. libs.versions.toml**

**位置：** `gradle/libs.versions.toml`

**用途：** 統一管理所有依賴版本

**添加新依賴的方法：**

**Step 1 - 在 `[versions]` 中定義版本號**

```toml
[versions]
retrofit = "2.9.0"
```

**Step 2 - 在 `[libraries]` 中定義依賴**

```toml
[libraries]
retrofit = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
```

**Step 3 - 在 `build.gradle.kts` 中使用**

```kotlin
dependencies {
    implementation(libs.retrofit)
}
```

---

## **添加功能的完整流程**

### **例子：在ai_assistant資料夾添加網路請求功能**

#### **Step 1 - 添加依賴**

在 `gradle/libs.versions.toml` 中：

```toml
[versions]
retrofit = "2.9.0"

[libraries]
retrofit = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
retrofit-gson = { group = "com.squareup.retrofit2", name = "converter-gson", version.ref = "retrofit" }
```

在 `app/build.gradle.kts` 中：

```kotlin
dependencies {
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
}
```

#### **Step 2 - 創建 API Service**

在 `app/src/main/java/com/rokid/ai_assistant/` 下創建 `ApiService.kt`：

```kotlin
package com.rokid.ai_assistant

import retrofit2.Response
import retrofit2.http.GET

interface ApiService {
    @GET("/api/status")
    suspend fun getStatus(): Response<StatusResponse>
}

data class StatusResponse(
    val message: String,
    val status: String
)
```

#### **Step 3 - 在 MainActivity 中使用**

```kotlin
class MainActivity : ComponentActivity() {
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            CAMERA_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && 
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // 開始你的功能
                    makeNetworkRequest()
                }
            }
        }
    }

    private fun makeNetworkRequest() {
        // 你的網路請求邏輯
    }
}
```

---

## **調用 Rokid 眼鏡 SDK**

### **Rokid 眼鏡相機 SDK**

#### **添加依賴**

在 `gradle/libs.versions.toml` 中：

```toml
[versions]
uvcAndroid = "1.0.7"

[libraries]
uvc-android = { group = "com.herohan", name = "UVCAndroid", version.ref = "uvcAndroid" }
```

在 `app/build.gradle.kts` 中：

```kotlin
dependencies {
    implementation(libs.uvc.android)
}
```

#### **在 MainActivity 中初始化相機**

```kotlin
import com.herohan.uvc.CameraManager

class MainActivity : ComponentActivity() {
    
    private var cameraManager: CameraManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        checkCameraPermission()
    }

    private fun initCamera() {
        cameraManager = CameraManager(this)
        // 設置相機回調
        cameraManager?.setOnFrameCallback { data ->
            // 處理相機幀數據
            processCameraFrame(data)
        }
    }

    private fun processCameraFrame(data: ByteArray) {
        // 你的圖像處理邏輯
    }
}
```

---

### **語音識別 SDK**

#### **添加依賴**

在 `app/build.gradle.kts` 中：

```kotlin
dependencies {
    // Rokid 語音 SDK（需要從 Rokid 官方獲取）
    implementation("com.rokid.speech:speech-sdk:版本號")
}
```

#### **使用語音識別**

```kotlin
import com.rokid.speech.SpeechClient

class MainActivity : ComponentActivity() {

    private var speechClient: SpeechClient? = null

    private fun initSpeech() {
        speechClient = SpeechClient(this)
        speechClient?.startListening { text ->
            // 處理識別結果
            onSpeechRecognized(text)
        }
    }

    private fun onSpeechRecognized(text: String) {
        // 更新 UI
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        tvStatus.text = "識別結果：$text"
    }
}
```

---
## **常用 Android API**

### **UI 更新**

```kotlin
// 獲取 UI 元件
val tvStatus = findViewById<TextView>(R.id.tvStatus)

// 更新文本
tvStatus.text = "新文本"

// 修改顏色
tvStatus.setTextColor(resources.getColor(R.color.white))
```

### **Log 輸出**

```kotlin
import android.util.Log

Log.d("TAG", "調試信息")
Log.e("TAG", "錯誤信息", exception)
```

### **Toast 提示**

```kotlin
import android.widget.Toast

Toast.makeText(this, "提示信息", Toast.LENGTH_SHORT).show()
```

### **權限檢查**

```kotlin
if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
    == PackageManager.PERMISSION_GRANTED) {
    // 有權限
} else {
    // 沒有權限
}
```

---
- [Android 官方文檔](https://developer.android.com)
- [Kotlin 官方文檔](https://kotlinlang.org)
- [Rokid 開發者文檔](https://developer.rokid.com)
- [Gradle 文檔](https://gradle.org)
- 在 `MainActivity.kt` 中加邏輯
- 在 `activity_main.xml` 中設計 UI
- 在 `build.gradle.kts` 中添加依賴
- 在 `AndroidManifest.xml` 中添加權限
---
**各位加油！強大**