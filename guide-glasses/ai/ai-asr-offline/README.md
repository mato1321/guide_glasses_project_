# ai-asr-offline

APK 內建的離線語音辨識。**這是 Rokid Glasses 上唯一的語音輸入途徑。**

---

## 為什麼需要這個模組

眼鏡上完全沒有語音辨識服務：

```bash
adb shell settings get secure voice_recognition_service
# null

adb shell "cmd package query-services --brief -a android.speech.RecognitionService"
# No services found
```

第二個指令用的是 **shell 自己的套件可見性**，不受 App 的 `<queries>` 影響 ——
所以這不是可見性問題，是系統上真的一個都沒有。一般 Android 手機由
Google App 提供 `RecognitionService`，這台沒裝，也沒有 Play 商店可以裝。

所以跟 TTS 同樣的解法：**把辨識引擎當函式庫**。

```
麥克風 → AudioRecord(16kHz float) → 串流 zipformer CTC (ONNX) → 文字
```

---

## 為什麼用串流模型

組員原本的做法是「按一下開始錄 → 再按一下停止 → 上傳整段到 Whisper → 等回傳」，
來回三到五秒而且完全依賴網路。串流辨識有兩個關鍵好處：

1. **說到一半就有部分結果**，可以即時回饋。
2. **模型自己判斷句子結束**（endpoint detection）—— 使用者不必記得再按一次。
   對看不見按鈕的人，少一次操作差別很大。

---

## 內容物

| 檔案 | 大小 | 說明 |
|---|---:|---|
| `assets/zh/model.int8.onnx` | 26 MB | `sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01` |
| `assets/zh/tokens.txt` | 13 KB | 詞表 |

引擎 `.so` 與 `ai-tts-offline` 共用同一顆（static-link 版），不會重複打包。

> 原始 tarball 還有 `bbpe.model`（255KB），那是熱詞/關鍵詞用的，
> 一般解碼不需要，沒有放進來。

---

## 🔴 這台裝置最大的坑：VOICE_RECOGNITION 是啞的

一開始用 `MediaRecorder.AudioSource.VOICE_RECOGNITION`（對語音辨識而言
最合適的來源，系統會套降噪與 AGC）。結果**收到的全是 0**，而權限、appop、
麥克風佔用、全域靜音、行程狀態（連 TOP 都試過）全部正常，
換成 16-bit 也一樣 —— 錄音「成功」了，只是沒有聲音。

寫了 [`MicrophoneProbe`](src/main/kotlin/com/guideglasses/ai/asr/MicrophoneProbe.kt)
把每種來源都試一遍：

```
VOICE_RECOGNITION：0.0000  ← 靜音
MIC：              0.0387
DEFAULT：          0.0227
VOICE_COMMUNICATION：0.0287
CAMCORDER：        0.0439
UNPROCESSED：      0.0086
```

**這台裝置上唯一沒接的來源，剛好就是最該用的那一個。**
廠商沒實作它，而且不報錯、只是靜靜回傳靜音 —— 又是招牌失敗方式。
現在用 `MIC`。

哪天換裝置或韌體更新後又聽不到，先跑這個再猜：

```bash
adb shell am broadcast -a com.guideglasses.DEBUG --es cmd MIC_TEST
adb logcat -d | grep MicProbe   # 測試時要持續說話
```

### 實測的音量基準（眼鏡）

| 情況 | 峰值 |
|---|---|
| 音訊來源沒接 | **0.0000**（乾淨的零） |
| 環境底噪 | 約 0.005 |
| 正常說話 | 約 0.039 |

---

## 已知限制與尚未驗證

| 項目 | 狀態 |
|---|---|
| 模型載入 | ✅ 眼鏡實測 **6.0 秒** |
| 麥克風開啟 | ✅ 實測 `AUDIO_SOURCE_VOICE_RECOGNITION`、16kHz float |
| 逾時邏輯 | ✅ 實測 8 秒無人聲會正確回報 |
| 音訊來源 | ✅ 已修：`VOICE_RECOGNITION` 在這台是啞的，改用 `MIC`（見上） |
| **實際辨識準確度** | 🔴 **尚未驗證** —— 需要有人對著眼鏡說話 |
| 即時性（RTF） | 🔴 未實測。同一顆 CPU 跑 TTS 的 RTF 已接近 1 |
| 語言 | 只有中文 |

### 怎麼測

眼鏡的螢幕逾時只有 5 秒，睡著之後 `input tap` 點不到按鈕，
launcher 還會把焦點搶走 —— **UI 點擊在這台機器上不是可靠的測試方式**。
所以加了一個 debug 廣播直接觸發聆聽：

```bash
adb shell am start -n com.guideglasses/.MainActivity && sleep 1
adb shell am broadcast -a com.guideglasses.DEBUG --es cmd LISTEN
# 立刻對眼鏡說話，8 秒內
adb logcat -d | grep OfflineAsr
```

log 會出現其中之一：

```
I OfflineAsr: 開始聆聽
D OfflineAsr: 部分結果：前面有
I OfflineAsr: 辨識完成：「前面有什麼」（耗時 2340ms）
W OfflineAsr: 逾時，完全沒聽到人聲
```

---

## 設計上的兩個決定

**用 `VOICE_RECOGNITION` 音訊來源**：它會套用系統的降噪與 AGC。
眼鏡是四麥克風陣列、戴在頭上、在街上使用，這些處理很有價值。

**兩段式逾時**：還沒聽到人聲時 8 秒放棄（避免麥克風一直開著耗電）；
已經開始講話後放寬到 20 秒（防止環境噪音讓 endpoint 永遠不觸發）。
逾時一定會給出結果或錯誤 —— 對看不見的使用者，**靜默等待跟當機沒有區別**。
