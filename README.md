# Rokid 眼鏡 Android 開發框架

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