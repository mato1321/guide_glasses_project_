# Rokid Glasses 實機發現

> **2026-08-08 首次在 Rokid Glasses（`RG-glasses`, Android 12）上實際執行。**
>
> 這份文件記錄的是**用 adb 實際查出來的事實**，不是規格書、不是推論。
> 每一項都附上取得方式，任何人都可以重跑驗證。
>
> 最後更新：2026-08-08（新增 §9 GPS、§10 Glass3 SDK、§11 Sprite TTS）

---

## 0. 一句話總結

**這台眼鏡沒有可用的 Android 語音堆疊。** STT 完全不存在，TTS 綁定失敗。
專案原本「語音一律走 Android 原生、50ms、離線可用」的前提**在這台硬體上不成立**。

---

## 1. 裝置基本資料

```bash
adb shell getprop ro.product.model      # RG-glasses
adb shell getprop ro.product.manufacturer  # Rokid
adb shell getprop ro.build.version.release # 12
adb shell getprop ro.product.locale     # zh-CN
```

| 項目 | 值 |
|---|---|
| 型號 | `RG-glasses` |
| Android 版本 | **12**（與先前推論一致） |
| 系統語系 | **zh-CN**（不是 zh-TW） |

---

## 2. 🔴 沒有 Google Play Services（`TASKS.md` A9 已解答）

```bash
adb shell pm list packages | grep -E "com.google.android.gms|com.android.vending"
# 沒有任何輸出
```

**Play Services 與 Play Store 都不存在。** 整台機器只有兩個 Google 套件：

```
com.google.android.apps.maps
com.google.android.inputmethod.latin   （Gboard）
```

### 影響

| 元件 | 是否受影響 | 說明 |
|---|---|---|
| ML Kit **bundled**（OCR 中文、人臉偵測） | ✅ 不受影響 | 模型打包在 APK 裡。**當初選 bundled 版是對的** |
| ML Kit **翻譯** | 🟡 待驗證 | `com.google.mlkit:translate` 是 standalone，理論上不需 Play Services，但語言包下載機制未實測 |
| ONNX Runtime（人臉、障礙物） | ✅ 不受影響 | 純原生函式庫 |
| `SpeechRecognizer` | ❌ **完全不可用** | 見 §3 |

---

## 3. 🔴 沒有任何語音辨識服務（STT 完全不可用）

```bash
adb shell settings get secure voice_recognition_service
# null

adb shell "cmd package query-services --brief -a android.speech.RecognitionService"
# No services found
```

第二個指令用的是 **shell 自己的套件可見性**，不受我們 App 的 `<queries>` 影響 ——
所以這不是可見性問題，是**系統上真的一個都沒有**。

一般 Android 手機由 Google App（`com.google.android.googlequicksearchbox`）
提供 `RecognitionService`，這台沒裝。

### 目前的行為

`SpeechRecognizer.isRecognitionAvailable()` 回 `false`，
`AndroidSpeechRecognitionGateway.isAvailable` 因此為 false，
助理播報「這台裝置沒有可用的語音辨識服務」。

**這是正確的行為，不是 bug。** 但它代表**語音輸入在這台眼鏡上目前完全不能用**。

---

## 4. 🔴 TTS 綁定失敗

```bash
adb shell settings get secure tts_default_synth
# com.github.jing332.tts_server_android
```

系統上**唯一**的 TTS 引擎是第三方的
[`tts_server_android`](https://github.com/jing332/tts-server-android)
（v1.25.071413，2026-07-03 安裝），而且它已經被設為預設引擎。

### 實際的錯誤

```
E TextToSpeech: System service is not available!
E TextToSpeech: Failed to bind to com.github.jing332.tts_server_android
E TtsAnnouncer: TTS 初始化失敗，status=-1
```

取得方式：

```bash
adb logcat -c && adb shell am force-stop com.guideglasses
adb shell am start -n com.guideglasses/.MainActivity
adb logcat -d | grep -iE "tts|texttospeech"
```

### 排除掉的可能原因

| 假設 | 查核結果 |
|---|---|
| 缺少 `<queries>`（Android 11+ 套件可見性） | ❌ **不是**。系統已自動合併，`dumpsys package com.guideglasses` 顯示 `queriesIntents=[RecognitionService, TTS_SERVICE]` |
| 引擎被停用 | ❌ **不是**。`enabled=0` 是 `COMPONENT_ENABLED_STATE_DEFAULT`（正常啟用），不是 DISABLED |
| 引擎未安裝 | ❌ **不是**。`installed=true`，且宣告了 `SystemTtsService` |

### 目前最可能的原因

`System service is not available!` 來自 Android 框架本身，指向兩種可能：

1. **這個 YodaOS 精簡版缺少完整的 TTS system service**
2. **`tts_server_android` 是代理型 App**，本身不含語音，需要在它的介面內
   設定語音來源（線上 API 或本機引擎）。未設定就無法被綁定

無 root，讀不到它的設定檔（`/data/data/...` permission denied），
**需要在眼鏡上手動開啟該 App 確認**。

---

## 5. 組員早就遇到並繞過了

`AI_Assistant/android/.../FaceRecognitionFragment.kt` 的 `speakOut()`：

```kotlin
if (ttsReady) {
    val speakResult = tts.speak(...)
    if (speakResult == TextToSpeech.ERROR) {
        speakWithMediaPlayer(message)      // 備用
    }
} else {
    speakWithMediaPlayer(message)          // TTS 沒起來就直接走這條
}
```

**他們的 TTS 同樣起不來，所以實際上一律走 `speakWithMediaPlayer`**
（打 Google 翻譯的非公開 TTS 端點）。這就是為什麼他們的 App 在眼鏡上會講話。

`AI_Assistant/python/function/` 的 `stt.py` 與 `tts.py` 都是打 **OpenAI**：

```
錄音 → 上傳 FastAPI → OpenAI Whisper 轉文字
                    → OpenAI TTS 合成 mp3 → 下載 → MediaPlayer 播放
```

> ⚠️ Google 翻譯的 TTS 端點是**非公開 API**，隨時可能停止服務，
> 不應用於正式產品。這一點 `ROADMAP.md` 早已記載。

---

## 6. 眼鏡上已安裝的相關 App

```bash
adb shell pm list packages -3
```

| 套件 | 對應 |
|---|---|
| `com.example.ocr` | Text_Recognition |
| **`com.example.gps`** | ⭐ **有人在測 GPS —— 值得問組員 A10 的答案** |
| `com.example.translate_glasses` / `_phone` | 翻譯 |
| `com.example.rokidglasses` / `_project` | AI_Assistant |
| `com.rokid.facerecognition` | Face_Recognition |
| `com.guideglasses` | **本專案** |
| `com.github.jing332.tts_server_android` | 第三方 TTS 引擎 |

### Rokid / 系統套件

```
com.rokid.os.sprite.assistserver     助理服務
com.rokid.os.sprite.launcher         啟動器
com.rokid.os.sprite.live / snapflow
com.rokid.cxrservice                 CXR 服務
com.rokid.sysconfig
com.qti.pasrservice                  Qualcomm PASR
```

`com.rokid.os.sprite.assistserver` 是 YodaOS 自己的語音堆疊。它**沒有**宣告
標準的 `android.intent.action.TTS_SERVICE`（所以 Android 的 `TextToSpeech`
看不到它），但**有自己的 action** —— 詳見 §11。

---

## 7. 這推翻了什麼

`ARCHITECTURE.md` 與 `DEVELOPER_GUIDE.md` 都寫著：

> 「語音一律本機。Android 原生 TTS 約 50ms，雲端 TTS 要 2–3 秒。
> 以步行速度 1.4 m/s，2 秒延遲的『前方有車』等於車已經到了。」

**這個推理本身沒錯，但它的前提（眼鏡有可用的原生 TTS/STT）在這台硬體上不成立。**

我當初把組員的「伺服器 STT/TTS」換成「Android 原生」，理由是延遲與離線能力 ——
那是基於「眼鏡是標準 Android 12 裝置，標準 API 都可用」的推論。
**實機證明這個推論在語音這一塊是錯的。**

---

## 8. 待決策：語音要怎麼辦

| 方案 | 延遲 | 離線 | 工作量 | 現況 |
|---|---|---|---|---|
| ~~A. 設定 `tts_server_android`~~ | — | — | — | 🔴 **已排除**，見下方查核 |
| **B. Sideload 離線 TTS 引擎 APK** | ~50ms | ✅ | 小 | 🟢 可行。裝好之後「本地TTS」就有東西可選。**但 STT 仍無解** |
| **C. 伺服器 STT/TTS**（沿用組員做法） | 2–3 秒 | ❌ | 中 | 🟡 能動。需 OpenAI 金鑰；**違反「斷網不能等於失明」** |
| **D. 改用非語音輸入** | — | ✅ | 中 | 🟡 眼鏡實體鍵觸發固定功能，放棄自由語句 |
| ~~E. Glass3 企業版 SDK~~ | — | — | — | 🔴 **已排除**，見 §10 |
| **F. YodaOS Sprite 原生服務** | ? | ? | ? | 🟢 **最有希望但需要介面文件**，見 §11 |

### 🔴 方案 A 已排除（2026-08-08 用 uiautomator 逐頁查核）

用 `adb shell uiautomator dump` 一路點進去看，三個地方**全部是空的**：

| 位置 | 狀態 |
|---|---|
| 系統TTS → 配置清單 | 空 |
| 系統TTS → ⋮ → 插件管理 | 空 |
| 新增配置 → 添加本地TTS → TTS引擎下拉選單 | **空**（沒有其他引擎可包裝） |

App 自己的日誌（`日誌` 分頁）從**安裝當天**就寫著：

```
2026-07-03 17:49:50  E  无可用的TTS配置，请检查是否启用。
```

「本地TTS」的作用是**包裝系統上其他的 TTS 引擎**，而這台唯一的引擎就是它自己
→ 清單必然為空。「外掛程式TTS」需要先匯入外掛，插件管理也是空的。

**這不是設定漏了，是這台裝置上這個 App 沒有任何可用的語音來源。**

### 建議順序

1. **向 Rokid 索取 Sprite 語音服務的介面文件**（方案 F）—— 這是唯一能同時解決
   TTS 與 STT、且維持離線的路
2. 同時**試 sideload 離線 TTS 引擎**（方案 B）—— 至少讓「能出聲」先成立，
   可以開始驗證 OCR／人臉／障礙物
3. STT 短期只能走 C 或 D

> ⚠️ 在語音問題解決之前，**眼鏡上無法做任何端到端測試** ——
> 沒有輸入也沒有輸出。OCR、人臉、翻譯、障礙物的程式碼可能都是對的，
> 但目前無法在眼鏡上驗證。可以先用 `adb logcat` 觀察邏輯是否正確。

> ⚠️ 在語音問題解決之前，**眼鏡上無法做任何端到端測試** ——
> 沒有輸入也沒有輸出。OCR、人臉、翻譯、障礙物的程式碼可能都是對的，
> 但目前無法在眼鏡上驗證。

---

## 9. 🔴 GPS：宣告有，實際沒有（TASKS A10 已解答）

**和 TTS 完全相同的陷阱：API 說有，實作不存在。**

```bash
adb shell pm list features | grep location
# feature:android.hardware.location.gps      ← 宣告有 GPS

adb shell dumpsys location
# Location Providers:
#     passive provider:
#     fused provider:                        ← 只有這兩個，沒有 gps provider
```

| 查核項 | 結果 |
|---|---|
| `pm list features` 宣告 `location.gps` | ✅ 有 |
| 實際註冊的 provider | ❌ **只有 `passive` 與 `fused`** |
| `dumpsys location` 的 GNSS 段落 | ❌ 完全不存在 |
| `/vendor/lib64/hw/` 的 GNSS HAL | ❌ 不存在 |

`fused` provider 是聚合 `gps` + `network` 的，兩個來源都沒有 → **拿不到座標**。

**結論：眼鏡沒有可用的 GPS。** `ARCHITECTURE.md` §5.3 決定的
「手機 companion 提供定位」是對的，現在有實據。
`LocationProvider` 應綁 `PhoneCompanionLocationProvider`。

---

## 10. Rokid Glass3 SDK：存在但這台裝置用不了

官方文件（企業版）：
<https://x-docs.rokid.com/docs/terminal-sdk/api-reference/>

SDK 是**公開可下載**的：

```
https://maven.rokid.com/repository/maven-public/com/rokid/security/glass3.open.sdk/
最新 release: 2.5.1-P    最新版: 2.6.3-P-SNAPSHOT
```

`build.gradle`：

```groovy
implementation('com.rokid.security:glass3.open.sdk:2.5.1-P') { exclude group: "org.slf4j" }
```

### 它提供的服務（`GlassSdk` 的公開方法）

```
getGlassTtsService()               ITtsService          線上 TTS
getGlassOfflineTtsService()        IOfflineTtsService   ★ 離線 TTS
getGlassAsrService()               IAsrService          ★ ASR
getGlassTranslateService()         ITranslateService    翻譯
getGlassAiChatService()            IAiChatService       AI 對話
getGlassOfflineRecService()        IOfflineRecServer    離線辨識
getGlassOfflineFeatureRecService() 離線特徵辨識（可能是人臉）
getGlassMediaService() / DeviceService / NotificationService / FileSystemService ⋯
```

**這幾乎涵蓋本專案從零實作的全部功能。**

### 🔴 但它綁不到

AAR 的 `AndroidManifest.xml`：

```xml
<queries>
    <package android:name="com.rokid.security.system.server" />
</queries>
```

```bash
adb shell pm list packages | grep com.rokid.security
# （無輸出）
```

**這台眼鏡上沒有 `com.rokid.security.system.server`。**
那是**企業版佈建**才會預裝的後端服務。SDK 能下載、能編譯，但
`GlassSdk.bindSecurityService()` 會連不上。

眼鏡上實際有的 Rokid 套件只有：

```
com.rokid.cxrservice          com.rokid.os.sprite.assistserver
com.rokid.facerecognition     com.rokid.os.sprite.launcher
com.rokid.glass.ota           com.rokid.os.sprite.live
com.rokid.sysconfig           com.rokid.os.sprite.snapflow
com.rokid.os.master.screenstream
```

> 💡 **值得問 Rokid**：消費版眼鏡有沒有對應的 SDK 或佈建方式。
> 如果能取得，專案可以大幅簡化。

---

## 11. 🟢 還有一條路：YodaOS Sprite 的原生 TTS

眼鏡上**確實有** Rokid 自己的 TTS 服務，只是不是企業版那套：

```bash
adb shell dumpsys package com.rokid.os.sprite.assistserver | grep -A3 TTS_SERVICE
```

```
com.rokid.os.sprite.assistserver/com.rokid.os.sprite.tts.TtsService
  Action: "com.rokid.os.sprite.tts.TTS_SERVICE"
```

`assistserver` 對外宣告的 action 一覽：

| Action | 可能用途 |
|---|---|
| `com.rokid.os.sprite.tts.TTS_SERVICE` | ★ **語音合成** |
| `com.rokid.os.sprite.assist.MasterAssistService` | 助理主服務 |
| `com.rokid.os.sprite.assist.instruct.InstructService` | 指令（可能是 ASR 入口） |
| `com.rokid.os.sprite.assist.system.SystemFuncService` | 系統功能 |
| `com.rokid.os.sprite.assist.media.SpriteMediaService` | 媒體 |
| `com.rokid.os.sprite.assist.wifi.SpriteWifiService` | Wi-Fi 控制 |
| `com.rokid.os.sprite.js.ai.JsaiService` | JS AI |

**它有自己的 action，代表可以被 bind。** 但公開 SDK 裡**沒有它的 AIDL
介面定義**，所以目前不知道方法簽章。

三個取得方式：

1. **向 Rokid 索取 Sprite 的介面文件**（最正規）
2. 反編譯 `assistserver` 抽出 AIDL（技術可行，法律灰色）
3. 試著用簡單 `Intent` + extra 送文字（成本最低，先試這個）

---

## 12. 組員沒人用過眼鏡端 SDK

| 專案 | Rokid 依賴 |
|---|---|
| `AI_Assistant` | `com.rokid.cxr:client-m:1.0.1-20250812.080117-2` ← **手機端** CXR-M |
| Face / Obstacle / Audio / Text | 僅套件命名用 `com.rokid.*`，**無任何 SDK** |

兩個專案的 `settings.gradle.kts` 都已設好 Rokid maven repo，可直接取用。

---

## 13. ✅ Glass3 SDK 實測結論：確定不能用

**不是推論，是實際跑過。** 加入依賴、寫探針、裝上眼鏡執行：

```
I GlassSdkProbe: 1. 目標套件 com.rokid.security.system.server 是否安裝：false
I GlassSdkProbe: 2. bindSecurityService 已呼叫，等待回呼⋯
I GlassSdkProbe: 3. 5 秒後 GlassSdk.isReady() = false
W GlassSdkProbe: 3. ❌ 結論：這台消費版眼鏡無法使用 Glass3 SDK
```

`bindSecurityService()` **不會拋例外**，只是永遠不回呼 —— 這種靜默失敗
如果沒有 5 秒後的 `isReady()` 檢查就看不出來。

探針與 SDK 依賴測完已移除（不留無用的 350KB 依賴在 APK 裡）。
要重跑就照 §10 的座標重新加回去。

**結案：消費版眼鏡不要再考慮 Glass3 SDK。**

---

## 14. 🔴 相機：又一個「宣告有、實際沒有」

### 症狀

```
W CameraValidator: Camera LENS_FACING_FRONT verification failed
W CameraValidator: java.lang.IllegalArgumentException: No available camera can be found
W CameraX: CameraIdListIncorrectException: Expected camera missing from device.
```

**CameraX 初始化整個失敗 → OCR、人臉、障礙物全都拿不到影像。**

### 原因

```bash
adb shell dumpsys media.camera | grep -E "Number of camera|Facing"
# Number of camera devices: 1
#     Facing: Back

adb shell pm list features | grep camera
# feature:android.hardware.camera.front      ← 假的，實際沒有前鏡頭
```

CameraX 的 `CameraValidator` 照著特徵旗標去驗證前鏡頭，找不到就整個 init 失敗。

### 解法（已修）

`GuideGlassesApplication` 實作 `CameraXConfig.Provider`：

```kotlin
override fun getCameraXConfig(): CameraXConfig =
    CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
        .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)
        .build()
```

修正後 `CameraValidator` 只驗證 `lensFacingInteger: 1`（後鏡頭），例外消失。

---

## 15. 🔴 相機還有第二關：idle UID 會被擋

修好上面那個之後仍然拍不到，log 是：

```
E CameraService: Access Denial: can't use the camera from an idle UID pid=9094, uid=10087
E Camera2CameraImpl: Error observed on open (or opening) camera device 0: ERROR_CAMERA_DISABLED
```

Android **禁止 idle 狀態的 App 使用相機**。而眼鏡的**螢幕逾時只有 5 秒**
（`settings get system screen_off_timeout` → `5000`），App 很快就進入 idle。

### 測試時的解法

```bash
adb shell am set-inactive com.guideglasses false
adb shell svc power stayon true
```

### ⚠️ 這對產品是個真問題

實際使用時，使用者戴著眼鏡、螢幕關閉、App 在背景 —— **相機會被系統擋掉**。
障礙物偵測需要持續用相機，這條路目前走不通。

可能的解法（未實作）：

| 方案 | 說明 |
|---|---|
| **Foreground Service** | 標準做法。App 在前景服務中就不會 idle |
| 加長螢幕逾時 | 治標，且耗電（眼鏡只有 210mAh） |
| 電池最佳化白名單 | 已加（`dumpsys deviceidle whitelist +com.guideglasses`），但**不足以**解除 idle UID 的相機限制 |

---

## 16. ✅ 實測通過的功能（2026-08-08）

用 debug 廣播入口逐一觸發（沒有 STT，只能這樣測）：

```bash
adb shell am set-inactive com.guideglasses false
adb shell svc power stayon true
adb shell am start -n com.guideglasses/.MainActivity
adb shell am broadcast -a com.guideglasses.DEBUG --es cmd <INTENT名稱>
adb logcat -d | grep TtsAnnouncer
```

> TTS 失敗時會把「本來要唸的話」印進 log，所以**聽不到但看得到**。

| 指令 | 結果 | 判定 |
|---|---|---|
| `READINESS_CHECK` | 「還沒完全準備好。人臉資料庫是空的⋯目前離線可用的有：OCR 朗讀、翻譯」 | ✅ 正確 |
| `PREPARE_TRANSLATION` | **「英文語言包下載完成，現在離線也能翻譯」** | ✅ **成功** |
| `SENSOR_TEST` | 「動作感測正常。可以偵測走路。可以追蹤轉向。**沒有電子羅盤**。」 | ✅ 見 §17 |
| `CAMERA_TEST` | **「相機正常。解析度 480 乘 640，影像 22 KB，耗時 930 毫秒」** | ✅ 見 §18 |
| `READ_TEXT` | 「沒有看到文字，請調整角度或靠近一點」 | ✅ 管線完整（鏡頭前無字） |
| `DETECT_OBSTACLES` | 「前面沒有偵測到障礙物」 | ✅ **YOLO ONNX 有載入並推論**（不可用會回不同訊息） |

### 🎉 兩個重要的正面結果

1. **ML Kit 翻譯在沒有 Play Services 的眼鏡上可以下載語言包並運作。**
   當初選 standalone 而非 play-services 版是對的。

2. **YOLOv8 ONNX 在 2GB RAM 的眼鏡上載入並推論成功，沒有 OOM。**

---

## 17. A3 解答：沒有電子羅盤

```
動作感測正常。可以偵測走路。可以追蹤轉向。沒有電子羅盤。
```

| 感測能力 | 有無 |
|---|---|
| 偵測走路（加速度計） | ✅ |
| 追蹤**相對**轉向（陀螺儀） | ✅ |
| **絕對方位（磁力計／電子羅盤）** | ❌ **沒有** |

### 對導航的影響

沒有絕對方位 → **不知道使用者面向哪個方位**。
即使手機提供了 GPS 座標，也算不出「往左轉還是往右轉」——
`Geo.relativeTurn()` 需要 `headingDegrees`，而那個值拿不到。

可能的替代：
- 由**移動軌跡**推算朝向（需要持續移動，靜止時無效）
- 手機的羅盤（但手機在口袋裡，朝向與頭部不同）
- 陀螺儀積分（會漂移，需要定期校正）

**這比「沒有 GPS」更難繞過。** 導航的難度顯著高於原本評估。

---

## 18. A4 解答：相機延遲 930 毫秒

```
相機正常。解析度 480 乘 640，影像 22 KB，耗時 930 毫秒
```

| 項目 | 文件原本估計 | **實機實測** |
|---|---|---|
| 擷取 + 轉檔耗時 | ~145 ms | **930 ms** |
| 解析度 | 640 / 1280（依功能） | **480 × 640** |

**慢了約 6 倍。** 這是所有視覺功能的延遲基線，影響很大：

- 障礙物偵測的延遲預算是 **<300ms**（1.4 m/s 下 = 42cm）
  → **光是擷取就已經超支 3 倍**，還沒算推論
- 2fps 連續偵測 = 每 500ms 一張，而單張擷取要 930ms → **做不到 2fps**

> ⚠️ `ARCHITECTURE.md` 裡所有基於「擷取約 100ms」的延遲推算都要重算。
> 需要進一步量測：是 CameraX 初始化慢（首次），還是每次都這麼慢？

---

## 19. Debug 廣播入口

因為眼鏡上沒有 STT，**說話這條路完全走不通**，加了一個 debug-only 的廣播入口
（`MainActivity.registerDebugTrigger()`，`BuildConfig.DEBUG` 才註冊）：

```bash
adb shell am broadcast -a com.guideglasses.DEBUG --es cmd READ_TEXT
adb shell am broadcast -a com.guideglasses.DEBUG --es cmd TRANSLATE --es target_language ja
```

支援全部 `AssistantIntent`，可帶 `target_language` / `text` / `name` 參數。

**沒有這個入口，眼鏡上除了看 log 之外沒有任何辦法驗證功能。**

---

## 20. 重跑這些檢查

全部指令彙整，任何人都可以重跑：

```bash
adb shell getprop ro.product.model
adb shell pm list packages | grep -E "com.google.android.gms|com.android.vending"
adb shell settings get secure voice_recognition_service
adb shell settings get secure tts_default_synth
adb shell "cmd package query-services --brief -a android.speech.RecognitionService"
adb shell pm list packages -3
adb shell pm list packages | grep com.rokid
```

GPS：

```bash
adb shell pm list features | grep location
adb shell dumpsys location | head -20
```

Rokid Sprite 的服務：

```bash
adb shell dumpsys package com.rokid.os.sprite.assistserver | grep -E "^\s+Action:"
```

> ⚠️ 眼鏡螢幕逾時只有 **5 秒**，用 uiautomator 看畫面前要先
> `adb shell svc power stayon true`，看完記得設回 `false`。

觸發 TTS 並看錯誤：

```bash
adb logcat -c && adb shell am force-stop com.guideglasses && adb shell am start -n com.guideglasses/.MainActivity && sleep 5 && adb logcat -d | grep -iE "tts|texttospeech"
```
