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
| 快取命中的額外開銷 | **60–150ms** |
| 合成輸出峰值 | 0.20–0.24（**播放前套 3.5 倍增益**，見下） |

### 中文模型換過兩次，這是三顆的實測

| | `matcha-icefall-zh-baker`（採用） | `vits-icefall-zh-aishell3` | `vits-piper-zh_CN-xiao_ya-int8` |
|---|---:|---:|---:|
| 取樣率 | **22050 Hz** | 8000 Hz（悶） | 22050 Hz |
| RTF | 1.47–1.84 | **1.00** | 2.20 |
| 模型載入 | 11.4 s | **4.7 s** | 12.4 s |
| 資產大小 | 92 MB | **30 MB** | 19 MB |
| 播放前增益 | ×1.3 | ×4.0 | ×3.5 |

**使用者實際聽過三顆之後選了 matcha**：8000Hz 被回報「很模糊」，
而 piper 雖然也是 22050Hz 但 RTF 2.2 斷得太厲害。

RTF 1.5 代表新句子合成跟不上播放、會斷續，但**快取命中完全不受影響** ——
導盲用語重複性極高，實際使用時多數播報是快取，只有每句話的第一次會頓。
這個取捨是使用者自己聽過後決定的。

> ⚠️ **每顆模型的輸出響度差很多**，換模型一定要重新校 `gain`：
> matcha 峰值 0.33–0.66、aishell3 0.19–0.23、piper 0.20–0.24。
> 沿用別顆的倍率，不是小聲到聽不見就是整段削波破音（實測 matcha
> 沿用 4.0 會頂到 1.000）。log 裡有每則的「原始峰值」，照著調。

> 換模型只要把檔案放進 `assets/tts/<名字>/` 並改 `OfflineVoice` 的常數。
> aishell3 的資產已從 repo 移除以省 30MB，需要時從 git 歷史取回。

### 🔊 為什麼播放前要放大 3.5 倍

使用者回報「聽得到，但比系統聲音小」。把眼鏡上快取的 PCM 拉回來量：

```
峰值 0.2097 (-13.6 dBFS)｜RMS 0.0388 (-28.2 dBFS)
```

系統音效通常做到接近 0 dBFS —— **我們白白浪費了 13.6 dB**。
套 3.5 倍增益後實測輸出電平從 -21~-30dB 提升到 -7.5~-17dB。

兩個設計決定：

- **拉高音訊而不是叫使用者調音量**：導盲提示走 `STREAM_ACCESSIBILITY`，
  那條串流的音量獨立於媒體音量，眼鏡上預設只有 8/15，不能假設使用者會去調。
- **快取存原始樣本，播放時才套增益**：調整 `GAIN` 之後舊快取立刻跟著變大聲，
  也保證同一句話「現場合成」與「快取播放」一樣大聲。

不用逐句正規化，是因為串流合成拿不到整句峰值（樣本一塊一塊來），
逐句算會讓兩條路徑不一樣大聲。log 會記每則的原始峰值，要調 `GAIN` 時有數據可依。

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
| `src/main/assets/tts/zh-matcha/model-steps-3.onnx` | 75 MB | Matcha 聲學模型，22050Hz |
| `src/main/assets/tts/zh-matcha/hifigan_v2.onnx` | 3.7 MB | 聲碼器 |
| `src/main/assets/tts/zh-matcha/dict/` | 14 MB | jieba 斷詞（**必須複製到檔案系統**，同 espeak） |
| `src/main/assets/tts/zh-matcha/lexicon.txt` | 1.4 MB | 中文 G2P 詞典 |
| `src/main/assets/tts/en-amy/` | 19 MB | 英文 piper amy，22050Hz |

**APK 約 288 MB**（原本約 106 MB）。中文 92 ＋ 英文 19 ＋ ASR 26 ＋ 引擎 .so 22.5 是主要增量。

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
