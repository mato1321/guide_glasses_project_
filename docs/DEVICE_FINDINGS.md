# Rokid Glasses 實機發現

> **2026-08-08 首次在 Rokid Glasses（`RG-glasses`, Android 12）上實際執行。**
>
> 這份文件記錄的是**用 adb 實際查出來的事實**，不是規格書、不是推論。
> 每一項都附上取得方式，任何人都可以重跑驗證。
>
> 最後更新：2026-08-08（語音輸出入雙雙打通、§21 背景被殺、§22 音訊診斷）

---

## 0. 一句話總結

**這台眼鏡沒有可用的 Android 語音堆疊** —— 204 個系統服務裡連一個語音相關的都沒有。
專案原本「語音一律走 Android 原生、50ms、離線可用」的前提**在這台硬體上不成立**。

**但兩邊都用同一招解決了**：把引擎當函式庫嵌進 APK，繞開整層框架。
輸出實測會出聲、起播 0.48 秒（§8）；輸入實測能辨識（§3）。

⚠️ **每台眼鏡要先跑一次 `appops` 解除背景限制**，否則螢幕一關 App 就被回收
（§21、`docs/PROVISIONING.md`）。

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

**這是正確的行為，不是 bug。**

### ✅ 已用 APK 內建模型繞過（2026-08-08）

跟 TTS 同樣的解法：把辨識引擎當函式庫，不走 `SpeechRecognizer`。
模組 `guide-glasses/ai/ai-asr-offline`，串流 zipformer CTC（26MB，中文）。

| 項目 | 狀態 |
|---|---|
| 模型載入 | ✅ 6.0 秒 |
| 麥克風 | ✅ `AUDIO_SOURCE_MIC`、16kHz、16-bit（兩者都是踩過坑才定下來的） |
| 逾時邏輯 | ✅ 8 秒無人聲會正確回報 |
| 音訊來源 | 🔴 **`VOICE_RECOGNITION` 在這台是啞的**，見下 |
| **辨識準確度** | ✅ **實測可用**：「前面有什麼」→「前面有什么」，5.5 秒 |

#### 🔴 模型輸出簡體，指令片語是繁體

實機辨識完全正確，系統卻回「我還不會回答這種問題」：

```
D OfflineAsr: 部分結果：前面
D OfflineAsr: 部分結果：前面有什
I OfflineAsr: 辨識完成：「前面有什么」（耗時 5471ms）
I OfflineTts: 快取命中「我還不會回答這種問題⋯」
```

模型是 zh-CN 訓練的，輸出 **什么**；片語寫的是 **什麼**。
這種失敗特別難查 —— 辨識明明是對的，看起來卻像功能壞掉。

已在 `SpokenText.forMatching` 加繁簡摺疊，比對前兩邊都摺。
**方向是「繁 → 簡」**：繁轉簡多半多對一（後/后 → 后）方向明確，
簡轉繁一對多要看上下文，猜錯會製造新的比對失敗。
摺疊逐字進行、長度不變，所以翻譯路徑仍能「用摺疊過的找位置、用原字串取內容」，
使用者要翻譯的內容不會被改成簡體。

#### 🔴 又一個「宣告有、實際沒有」：VOICE_RECOGNITION 音訊來源

用 `MediaRecorder.AudioSource.VOICE_RECOGNITION` 錄音**收到的全是 0**，
而權限、appop、麥克風佔用、全域靜音、行程狀態（TOP）全部正常。
逐一探測每種來源：

```
VOICE_RECOGNITION：0.0000  ← 靜音
MIC：              0.0387
DEFAULT：          0.0227
VOICE_COMMUNICATION：0.0287
CAMCORDER：        0.0439
UNPROCESSED：      0.0086
```

**唯一沒接的來源剛好是最該用的那一個**，而且不報錯、只是靜靜回傳靜音。
已改用 `MIC`。探針保留在 repo 裡：

```bash
adb shell am broadcast -a com.guideglasses.DEBUG --es cmd MIC_TEST
```

音量基準：來源沒接 = 乾淨的 0.0000｜環境底噪 ≈ 0.005｜正常說話 ≈ 0.039。

#### 🔴 `adb install` 回報 Success 但裝的是舊版

追「完全沒有反應」追了很久，最後發現裝置上的 APK 是 **106 MB**，
而本機建置是 288 MB —— 那是這次對話最開始、還沒有任何語音模型的版本。
中間每一次 `adb install -r` 都回報 `Success`。

**所以「新功能好像都不存在」是字面上的真相。**

之後每次安裝都要核對：

```bash
adb shell stat -c '%s' $(adb shell pm path com.guideglasses | sed 's/package://')
```

這是這台裝置上第六次「回報成功但沒生效」（前五次：`bindSecurityService`
不回呼、`startForeground` 被靜默拒絕、`VOICE_RECOGNITION` 回傳靜音、
`pm list features` 假宣告、以及程式裡自己的早退檢查）。

#### 🔴 喇叭與麥克風只隔幾公分：助理會聽到自己

實機 log：

```
01:12:26 播報「我還不會回答這種問題。你可以說「唸給我聽」、「這是誰」⋯」
01:12:31 辨識完成：「这是谁」      ← 播報還沒結束就聽到自己
```

**播報內容本身含有指令詞**，於是同一則回應被觸發第二次，
使用者聽到的是「同一句話講兩遍」。這不是可以之後再修的細節 ——
它會讓助理陷入自問自答。

已修：`SherpaSpeechRecognitionGateway` 接收 `isSpeaking`，
播報期間的音訊整段丟棄並重置辨識串流（不重置的話，前半句真人語音
會跟後半句自己的聲音黏成一句）。丟掉的時間不計入逾時，
否則助理話還沒講完，聆聽就先因為「沒聽到人聲」放棄了。

#### 🔴 沒有台灣腔的中文 TTS 模型

sherpa-onnx 的中文模型**全部是 zh-CN**（aishell3、piper zh_CN、fanchen、
melo、matcha-baker⋯），一個 zh-TW 都沒有。使用者回報「不是台灣腔、很難聽」
是真的，而且**換模型解決不了** —— 這是整個開源生態的缺口，不是選型失誤。

可能的方向（都沒做）：自己用台灣語料微調、找商用 TTS、或接受大陸腔。

觸發方式（眼鏡螢幕 5 秒就睡，`input tap` 點不到按鈕）：

```bash
adb shell am broadcast -a com.guideglasses.DEBUG --es cmd LISTEN
```

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
| ~~B. Sideload 離線 TTS 引擎 APK~~ | — | — | — | 🟡 **大機率也沒用**，見下方「B 的問題」 |
| **C. 伺服器 STT/TTS**（沿用組員做法） | 2–3 秒 | ❌ | 中 | 🟡 能動。需 OpenAI 金鑰；**違反「斷網不能等於失明」** |
| **D. 改用非語音輸入** | — | ✅ | 中 | 🟡 眼鏡實體鍵觸發固定功能，放棄自由語句 |
| ~~E. Glass3 企業版 SDK~~ | — | — | — | 🔴 **已排除**，見 §10 |
| **F. YodaOS Sprite 原生服務** | ? | ? | ? | 🟡 可能可行但需要介面文件，見 §11 |
| **G. 把合成引擎當函式庫嵌進 APK** | 起播 0.5 秒 | ✅ | 中 | ✅ **已實作並在眼鏡上實測會出聲（2026-08-08）** |

### 🔴 一個被忽略的關鍵區別：綁不上 TTS ≠ 不能出聲

上面 A–F 全部預設「眼鏡發不出聲音」，但那是錯的。**眼鏡的音訊輸出本身是好的** ——
§5 已經記載組員的 App 用 `MediaPlayer` 播 mp3 在眼鏡上會講話。
壞掉的只有 Android `TextToSpeech` 這一層框架。

這推出兩件事：

**B 的問題（🔴 已實測證實）**：錯誤是 `System service is not available!`，
那是**框架端**的問題，而任何 TTS 引擎 APK 都得透過同一個框架被綁定。

```bash
adb shell service list | wc -l                        # 204
adb shell service list | grep -c activity             # 2（對照組，指令有效）
adb shell service list | grep -iE "tts|speech|voice"  # 一個都沒有
```

**204 個系統服務裡沒有任何語音相關的。** 這個 YodaOS 精簡版把整層 TTS 框架
拿掉了 —— 所以 sideload 引擎 APK **確定沒用**，不是「大概沒用」。

**方案 G**：把離線合成引擎當成**函式庫**，而不是當成系統 TTS 引擎 ——

```
文字 → VITS (ONNX 推論) → PCM float → AudioTrack → 眼鏡喇叭
```

整條路徑不碰 `TextToSpeech`，也就繞開了壞掉的那一層。
專案本來就有 ONNX Runtime（人臉、YOLO 都在用），增量成本主要是模型體積。

### ✅ 方案 G 已實作並實測（2026-08-08）

- 模組：`guide-glasses/ai/ai-tts-offline`（詳見該模組的 `README.md`）
- 引擎：sherpa-onnx 1.13.4（**static-link 版**，否則 `libonnxruntime.so` 會撞名）
- 模型：中文 `matcha-icefall-zh-baker`（22050Hz）＋ 英文 `vits-piper-en_US-amy`（22050Hz）
- 接法：`FallbackAnnouncer` 候選鏈 —— 手機用系統 TTS，眼鏡自動落到離線引擎
- 代價：APK 從約 106MB → **288MB**

**眼鏡實測（4 核 @2.0GHz，1.8GB RAM）**：

| 指標 | 實測 |
|---|---:|
| 模型載入 | 4.7 秒 |
| 起播延遲 | **0.48 秒** |
| RTF | **1.00** |
| 快取命中的額外開銷 | 73ms |
| 合成輸出峰值 | 0.21（-13.6 dBFS）→ 播放前套 3.5 倍增益 |

**音量**：合成輸出只有 -13.6 dBFS，比系統音效小一截（使用者實際回報過）。
播放前套 3.5 倍增益後，輸出電平從 -21~-30dB 提升到 -7.5~-17dB。
注意眼鏡的 `STREAM_ACCESSIBILITY` 預設只有 8/15，且與媒體音量獨立。

**⚠️ fp16 模型會 SIGABRT**：試過 piper 的 fp16 版，
`OfflineTts_newFromAsset` 當場 abort（原生訊號，攔不住）。這台跑不了 fp16。

音訊確實送到喇叭（`dumpsys media.audio_flinger` 有真實的 dB 訊號軌跡）。

### 換過模型：延遲比音質重要

先用 `vits-piper-zh_CN-xiao_ya-medium-int8`（22050Hz、音質好），
實測 **RTF 2.2、起播 2.3 秒、一句話 10 次 underrun** —— 合成永遠追不上播放，
聽起來斷斷續續。換成 aishell3 後 RTF 降到 1.0、起播 0.48 秒，
代價是取樣率只有 8000Hz（電話音質，仍完全可辨識）。

### 🔴 這顆 CPU 達不到障礙物的 300ms 預算

起播 0.48 秒是硬限制，不是實作問題。因此加了**磁碟快取**：
合成過的句子存成裸 PCM，命中時額外開銷只有 73ms。導盲用語重複性極高，
但**每句話的第一次仍要付合成延遲**。

> ⚠️ 模型授權：aishell3 的資料集授權值得查證；先前的 piper 模型
> 明載 non-commercial（data-baker）。畢專可用，商用前要確認。

> **STT 也走同一條路解決了**（見 §3）—— sherpa-onnx 的 ASR 用同一顆 `.so`，
> 只差一個模型檔。辨識準確度尚待驗證。

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

### 建議順序（2026-08-08 更新）

1. ~~方案 B~~ → 改走**方案 G**，已實作。**先把眼鏡接上驗證它真的會出聲**，
   這一步通過之後 OCR／人臉／障礙物才終於能做端到端測試
2. **STT 接著也走同一條路** —— sherpa-onnx 的 ASR 用的是同一顆 `.so`，
   只差一個模型檔，邊際成本比 TTS 低得多
3. 方案 F（向 Rokid 索取 Sprite 介面文件）降為備案 —— G 若驗證通過就不需要了

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


---

## 21. ✅ App 退到背景就被殺 —— 已解決，但需要一次性佈建

**這曾是最根本的阻塞。已用 Foreground Service 解決，但有一個裝置層級的前提。**

實測：觸發一則播報後把 Activity 切到背景，2.4 秒後 ——

```
08-08 18:28:04.033 I AssistantVM: debugDispatch → READINESS_CHECK
08-08 18:28:06.467 I ActivityManager: Killing 12242:com.guideglasses/u0a87 (adj 900): cached #1
```

`adj 900` = `CACHED_APP`，也就是「使用者看不到、可以隨時回收」。
播報講到一半整個行程直接消失。

### 為什麼這比想像中嚴重

| 情境 | 結果 |
|---|---|
| 使用者戴著眼鏡走路，螢幕關閉 | **App 被殺，什麼功能都沒有** |
| 障礙物偵測 | 早就知道會被擋（idle UID，§15），現在連行程都留不住 |
| 語音播報 | **同樣活不下來** —— 先前以為只有相機受影響 |

### 這解釋了先前的測試假象

早期測試會成功，只是因為 `MainActivity` 剛好在前景。
一旦 launcher 或其他 App 跳到前景（眼鏡的 launcher 會自動輪播開啟其他 App），
我們的行程就沒了。**在眼鏡上測試時，每次觸發前都要先把 Activity 拉回前景：**

```bash
adb shell am start -n com.guideglasses/.MainActivity && sleep 1
adb shell am broadcast -a com.guideglasses.DEBUG --es cmd SENSOR_TEST
```

### 🔴 解法有個陷阱：YodaOS 預設把每個 App 都背景限制

先做了 `GuideGlassesForegroundService`，log 顯示 `startForeground()` 成功 ——
**但那是假的**：

```
W ActivityManager: Service.startForeground() not allowed due to bg restriction:
                   service com.guideglasses/.GuideGlassesForegroundService
```

系統**靜默拒絕**了：不丟例外、不回傳值。行程仍然是 `oom adj 905`（cached），
`dumpsys activity services` 裡也沒有 `isForeground`。
這是這台裝置反覆出現的模式，跟 `bindSecurityService` 不回呼、
`pm list features` 假宣告完全同一類。

原因：

```bash
adb shell cmd appops get com.guideglasses RUN_ANY_IN_BACKGROUND
# RUN_ANY_IN_BACKGROUND: ignore

# 對照組 —— 不是只有我們：
adb shell cmd appops get com.example.ocr RUN_ANY_IN_BACKGROUND          # ignore
adb shell cmd appops get com.google.android.apps.maps RUN_ANY_IN_BACKGROUND  # ignore
```

**YodaOS 預設把每一個 App 都設成背景限制，連 Google Maps 也是。**
電池最佳化白名單（`deviceidle whitelist`，本專案早就在裡面）**解不了這個** ——
那是另一層限制。

### ✅ 佈建指令（每台眼鏡執行一次）

```bash
adb shell cmd appops set com.guideglasses RUN_ANY_IN_BACKGROUND allow
```

執行後實測：

| 項目 | 解除前 | 解除後 |
|---|---|---|
| `oom adj` | 905（cached） | **200（fg-service）** |
| `isForeground` | 無 | **true** |
| 背景 40 秒 | 被殺 | **存活** |
| 背景開相機 | `Access Denial: idle UID` | **成功**（480×640、1476ms） |
| 背景播報 | 行程消失 | **正常** |

### 程式端已主動查證

`startForeground()` 不丟例外不代表成功，所以服務啟動後用
`getRunningServices` 查自己有沒有 `foreground` 旗標，被擋時會在 log 印出

```
E GuideService: 🔴 前景服務被系統擋下（背景限制=true）。螢幕關閉後 App 會被回收⋯
```

而不是謊報成功。

> ⚠️ **產品化的待解問題**：這條指令要靠 adb。量產時需要 Device Owner
> 或 MDM 佈建（`TASKS.md` A13）。使用者自己在設定裡關掉背景限制，
> 對全盲使用者不現實。

---

## 22. 音訊：怎麼在沒有耳朵的情況下確認有沒有出聲

眼鏡戴在頭上，遠端開發時聽不到。兩個客觀確認方式：

```bash
# 1. AudioFlinger 的輸出訊號軌跡
adb shell dumpsys media.audio_flinger | sed -n '/AudioOut_D/,/^$/p' | grep -A20 Master
#   -86.8 -81.4 -56.6 -44.5 -40.8 ⋯  ← 有起伏的 dB 數列 = 真的有語音訊號
#   沒有數列或全部低於 -100          = 沒有輸出

# 2. 無障礙串流的音量（跟媒體音量是分開的！）
adb shell dumpsys audio | grep -A6 "^- STREAM_ACCESSIBILITY"
```

⚠️ **眼鏡上 `STREAM_ACCESSIBILITY` 預設只有 8/15**，而我們的播報走的正是這條
（`USAGE_ASSISTANCE_ACCESSIBILITY`）。聽起來很小聲時先查這個。
Android 的音量鍵只調「當下正在播的那條串流」，所以**必須在播放期間按**：

```bash
adb shell am broadcast -a com.guideglasses.DEBUG --es cmd SENSOR_TEST
sleep 2 && for i in $(seq 1 10); do adb shell input keyevent KEYCODE_VOLUME_UP; done
```
