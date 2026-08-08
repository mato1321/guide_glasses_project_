# ai-tts-offline

APK 內建的離線語音合成。**這是 Rokid Glasses 上唯一能發出聲音的途徑，已在眼鏡上實測會出聲。**

---

## 為什麼需要這個模組

眼鏡上 Android 的 `TextToSpeech` 綁定失敗：

```
E TextToSpeech: System service is not available!
E TextToSpeech: Failed to bind to com.github.jing332.tts_server_android
```

### 🔴 已證實：框架端的 TTS system service 根本不存在

2026-08-08 在眼鏡上實測：

```bash
adb shell service list | wc -l                        # 204
adb shell service list | grep -c activity             # 2（對照組，指令有效）
adb shell service list | grep -iE "tts|speech|voice"  # 一個都沒有
```

**204 個系統服務裡沒有任何語音相關的。** 這不是設定問題，是這個 YodaOS 精簡版
把整層 TTS 框架拿掉了。所以「sideload 另一顆 TTS 引擎 APK」確定沒用 ——
任何引擎都得透過這層框架才能被綁定。

### 關鍵區別：綁不上 TTS ≠ 不能出聲

眼鏡的**音訊輸出本身是好的**。解法是把合成引擎當成函式庫：

```
文字 → VITS (ONNX 推論) → PCM float → AudioTrack → 眼鏡喇叭
```

整條路徑不碰 `TextToSpeech`，也就繞開了被拿掉的那一層。

---

## 📊 眼鏡實測數據（2026-08-08）

裝置：Rokid Glasses，4 核 @ 2.0GHz，1.8GB RAM。

| 指標 | 實測 |
|---|---:|
| 模型載入 | **4.7 秒** |
| 起播延遲（現場合成） | **0.4–1.0 秒** |
| RTF（合成時間 ÷ 音長） | **約 1.05** |
| 快取命中的額外開銷 | **60–130ms** |

### 為什麼選 aishell3 而不是音質更好的 piper

兩顆都在眼鏡上實測過：

| | `vits-icefall-zh-aishell3`（採用） | `vits-piper-zh_CN-xiao_ya-medium-int8` |
|---|---:|---:|
| 模型載入 | **4.7 s** | 12.4 s |
| 起播延遲 | **0.4–1.0 s** | 2.1–2.5 s |
| RTF | **1.05** | 2.20 |
| 取樣率 | 8000 Hz（電話音質） | **22050 Hz（清晰）** |
| 模型大小 | 30 MB | **18.6 MB** |

**對導盲裝置，延遲比音質重要。** RTF 2.2 代表合成永遠追不上播放，音訊會持續
underrun（實測一句話 10 次），聽起來斷斷續續；RTF 1.05 則勉強跟得上。
8000Hz 是電話音質，仍然完全可辨識。

> 要換回 piper（或任何其他模型）：把檔案放進 `assets/tts/<名字>/`，
> 改 `SherpaOfflineTtsAnnouncer` 的 `ASSET_DIR` 與 `MODEL_FILE` 兩個常數即可，
> 程式邏輯不用動。快取目錄跟著 `ASSET_DIR` 走，所以換模型會自動失效舊音訊。

### 🔴 這顆 CPU 達不到障礙物播報的 300ms 預算

起播 0.4–1.0 秒是這顆 CPU 跑神經 TTS 的硬限制，不是實作問題。
**只有走快取才進得了 300ms。** 因此加了磁碟快取：合成過的句子存成裸 PCM，
下次直接播。導盲用語重複性極高（「前面沒有偵測到障礙物」每次都一樣），
命中率會很高，但**每句話的第一次仍然要付合成延遲**。

---

## 內容物

| 檔案 | 大小 | 說明 |
|---|---:|---|
| `libs/com/k2fsa/.../sherpa-onnx-static-link-onnxruntime-1.13.4.aar` | 35.9 MB | 推論引擎（只有 arm64-v8a 進 APK，23.6 MB） |
| `src/main/assets/tts/zh-aishell3/model.onnx` | 30.5 MB | VITS 中文，8000Hz |
| `src/main/assets/tts/zh-aishell3/lexicon.txt` | 2.0 MB | 中文 G2P 詞典 |
| `src/main/assets/tts/zh-aishell3/*.fst` | 0.2 MB | 數字/日期/破音字正規化 |

**APK 約 170 MB**（原本約 106 MB）。模型 29 MB ＋ 引擎 .so 22.5 MB 是主要增量。

> ⚠️ 原始 tarball 裡還有一個 **180MB 的 `rule.far`**，那是 `.fst` 規則的替代品，
> 不需要，**不要放進來**，否則 APK 會爆到 300MB 以上。

---

## 怎麼接進系統

`SherpaOfflineTtsAnnouncer` 實作 domain 的 `Announcer`，由 `AssistantModule`
放進 `FallbackAnnouncer` 候選鏈：

| 順位 | 實作 | 什麼時候輪到它 |
|---|---|---|
| 1 | `AndroidTtsAnnouncer` | 一般 Android 手機。系統引擎既省資源又支援多語言 |
| 2 | **`SherpaOfflineTtsAnnouncer`** | **眼鏡走這條**（第 1 順位在眼鏡上永遠不可用） |
| 3 | `LogOnlyAnnouncer` | 前兩個都失敗。不會有聲音，只把該唸的話寫進 log |

---

## ⚠️ 三個踩過的坑

### 1. 回呼不能寫成 lambda，否則整個 App 當場 abort

```
JNI DETECTED ERROR IN APPLICATION: JNI NewFloatArray called with pending exception
java.lang.NoSuchMethodError: no non-static method
  "L...$$ExternalSyntheticLambda6;.invoke([F)Ljava/lang/Integer;"
```

sherpa 的 JNI 用**特化簽章** `invoke([F)Ljava/lang/Integer;` 去找回呼。
Kotlin 2.x 預設把 lambda 編成 invokedynamic，D8 生成的合成類別**只有**泛型橋接
`invoke(Object)Object`。而且失敗方式是 **JNI abort，不是丟例外** ——
`FallbackAnnouncer` 攔不住，整個行程直接死。

解法是寫成具名的 `object : Function1<FloatArray, Int>`。改完可以驗證：

```bash
javap -p '.../SherpaOfflineTtsAnnouncer$synthesizeAndPlay$onSamples$1.class'
#   public java.lang.Integer invoke(float[]);   ← 要有這行
#   public java.lang.Object invoke(java.lang.Object);
```

### 2. 無障礙音訊串流的音量是獨立的，預設只有 8/15

我們走 `USAGE_ASSISTANCE_ACCESSIBILITY`（stream 10），它**跟媒體音量分開**。
眼鏡上預設只有 8/15，聽起來很小聲。而 Android 的音量鍵只調「當下正在播的那條
串流」，所以**必須在播放期間按**才調得到：

```bash
adb shell am broadcast -a com.guideglasses.DEBUG --es cmd SENSOR_TEST
sleep 2 && for i in $(seq 1 10); do adb shell input keyevent KEYCODE_VOLUME_UP; done
adb shell dumpsys audio | grep -A6 "^- STREAM_ACCESSIBILITY"
```

### 3. 相依關係為什麼寫得這麼彆扭

1. **sherpa-onnx 不在 Maven Central。** 網路文章流傳的
   `com.k2fsa.sherpa.onnx:sherpa-onnx-android` 座標是錯的，那個 group 不存在。
2. **一定要用 static-link 版 AAR。** 一般版內含自己的 `libonnxruntime.so`，
   會與 `ai-face` / `ai-vision` 用的 onnxruntime-android 撞名。
   app 模組另外排除 `lib/x86/**` —— static-link 版唯獨 x86 那顆仍帶著它，
   而 `abiFilters` 擋不住（合併發生在 ABI 過濾之前）。
3. **不能用 `files("....aar")`，也不要用 `flatDir`。** 前者被 AGP 直接擋；
   後者產生的座標沒有 group，lint 的 `GradleDetector` 會丟 `InvalidPathException`。
   本地 maven 佈局 + `metadataSources { artifact() }` 是唯一乾淨的走法。

---

## 怎麼在眼鏡上驗證

```bash
adb shell am set-inactive com.guideglasses false
adb shell svc power stayon true
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.guideglasses/.MainActivity
sleep 8 && adb logcat -d | grep OfflineTts
#   期待：「離線 TTS 就緒，取樣率 8000Hz，耗時 4692ms」

# ⚠️ 每次觸發前都要先把 Activity 拉回前景，否則 App 會被系統殺掉（見下）
adb shell am start -n com.guideglasses/.MainActivity && sleep 1
adb shell am broadcast -a com.guideglasses.DEBUG --es cmd SENSOR_TEST
adb logcat -d | grep OfflineTts
#   「合成完畢…｜起播 493ms｜合成 7779ms｜音長 7718ms｜RTF 1.01」
#   第二次同一句會變成「快取命中…」
```

確認音訊真的送到喇叭（不必靠耳朵）：

```bash
adb shell dumpsys media.audio_flinger | sed -n '/AudioOut_D/,/^$/p' | grep -A20 Master
# 會看到 -44.5 -40.8 -42.8 ⋯ 這種起伏的 dB 軌跡 = 真的有語音訊號
# 全是 -100 以下或沒有數列 = 沒有輸出
```

---

## 🔴 尚未解決：App 一退到背景就被殺

```
I ActivityManager: Killing 12242:com.guideglasses/u0a87 (adj 900): cached #1
```

實測 App 退到背景後 **2.4 秒**就被系統回收，播報中途直接消失。
這跟 `TASKS.md` A23「idle UID 擋相機」是同一個根因，但影響更廣 ——
**語音路徑也活不下來**。在做出 Foreground Service 之前，
所有眼鏡上的測試都必須確保 Activity 在前景。
