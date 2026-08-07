# Rokid Glasses 實機發現

> **2026-08-08 首次在 Rokid Glasses（`RG-glasses`, Android 12）上實際執行。**
>
> 這份文件記錄的是**用 adb 實際查出來的事實**，不是規格書、不是推論。
> 每一項都附上取得方式，任何人都可以重跑驗證。

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

`com.rokid.os.sprite.assistserver` 與 `com.qti.pasrservice` 值得進一步研究 ——
它們可能是 YodaOS 自己的語音堆疊，但**都沒有透過標準 Android API 對外提供服務**
（查不到 `RecognitionService` 或 `TTS_SERVICE` 宣告）。

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

| 方案 | 延遲 | 離線 | 工作量 | 風險 |
|---|---|---|---|---|
| **A. 設定好 `tts_server_android`** | 看設定來源 | 看設定 | **0** | 只解決 TTS，STT 仍無解 |
| **B. Sideload 離線 TTS 引擎 APK** | ~50ms | ✅ | 小 | 要找不依賴 Play Services 的；且 STT 仍無解 |
| **C. 伺服器 STT/TTS**（沿用組員做法） | 2–3 秒 | ❌ | 中 | 需 OpenAI 金鑰；**違反「斷網不能等於失明」原則** |
| **D. 改用非語音輸入** | — | ✅ | 中 | 眼鏡實體鍵觸發固定功能，放棄自由語句 |
| **E. 研究 Rokid 自家語音堆疊** | ? | ? | ? | `assistserver` / `pasrservice` 未知，可能需要 Rokid 文件 |

### 建議順序

1. **先在眼鏡上手動開啟 `tts_server_android` 確認它缺什麼設定**（零成本）
2. **問組員 `com.example.gps` 的結果** —— 順便問他們的 App 在眼鏡上語音怎麼處理的
3. TTS 通了之後再處理 STT。**STT 是比較難的那一半**，因為系統上真的什麼都沒有

> ⚠️ 在語音問題解決之前，**眼鏡上無法做任何端到端測試** ——
> 沒有輸入也沒有輸出。OCR、人臉、翻譯、障礙物的程式碼可能都是對的，
> 但目前無法在眼鏡上驗證。

---

## 9. 重跑這些檢查

全部指令彙整，任何人都可以重跑：

```bash
adb shell getprop ro.product.model
adb shell pm list packages | grep -E "com.google.android.gms|com.android.vending"
adb shell settings get secure voice_recognition_service
adb shell settings get secure tts_default_synth
adb shell "cmd package query-services --brief -a android.speech.RecognitionService"
adb shell pm list packages -3
```

觸發 TTS 並看錯誤：

```bash
adb logcat -c && adb shell am force-stop com.guideglasses && adb shell am start -n com.guideglasses/.MainActivity && sleep 5 && adb logcat -d | grep -iE "tts|texttospeech"
```
