# ai-tts-offline

APK 內建的離線語音合成。**這是 Rokid Glasses 上唯一能發出聲音的途徑。**

---

## 為什麼需要這個模組

眼鏡上 Android 的 `TextToSpeech` 綁定失敗：

```
E TextToSpeech: System service is not available!
E TextToSpeech: Failed to bind to com.github.jing332.tts_server_android
```

系統上唯一的 TTS 引擎是一個第三方 App，而它的三個設定頁全是空的 ——
沒有任何語音來源可選。詳細查核見 [`docs/DEVICE_FINDINGS.md`](../../../docs/DEVICE_FINDINGS.md) §4、§8。

### 關鍵區別：綁不上 TTS ≠ 不能出聲

眼鏡的**音訊輸出本身是好的** —— 組員的 App 用 `MediaPlayer` 播 mp3 就會出聲。
壞掉的只有 Android TTS 那一層框架。

所以解法不是「把語音搬到手機」，而是**把合成引擎當成函式庫**：

```
文字 → VITS (ONNX 推論) → PCM float → AudioTrack → 眼鏡喇叭
```

整條路徑不碰 `TextToSpeech`，也就繞開了壞掉的那一層。

> 這也順帶說明為什麼 sideload 一顆 TTS 引擎 APK（`DEVICE_FINDINGS.md` §8 方案 B）
> 大機率沒用：錯誤訊息指向框架本身，而任何引擎 APK 都得透過同一個框架被綁定。
> 這是推論不是實測，一行就能驗證：`adb shell service list | grep -i tts`

---

## 內容物

| 檔案 | 大小 | 說明 |
|---|---:|---|
| `libs/com/k2fsa/sherpa-onnx-static-link-onnxruntime/1.13.4/*.aar` | 35.9 MB | 推論引擎（只有 arm64-v8a 會進 APK） |
| `src/main/assets/tts/zh/zh_CN-xiao_ya-medium.onnx` | 18.6 MB | 小雅，中文女聲，int8 量化 |
| `src/main/assets/tts/zh/lexicon.txt` | 2.0 MB | 中文 G2P 詞典（含破音字） |
| `src/main/assets/tts/zh/{date,number,phone}.fst` | 0.2 MB | 把「30」唸成「三十」的正規化規則 |

**APK 因此從約 106MB 變成 143MB。**

### ⚠️ 授權：模型是「非商業使用」

`MODEL_CARD` 寫明訓練資料集是 [data-baker](https://www.data-baker.com/data/index/TNtts/)，
授權為 **non-commercial use**。畢業專題沒問題，**要商用就得換模型**。

替代選項（都在 [sherpa-onnx 的 tts-models release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models)）：

| 模型 | 大小 | 備註 |
|---|---:|---|
| `vits-icefall-zh-aishell3` | 30 MB | 資料集是 AISHELL-3，授權**值得查證**，可能比較寬鬆 |
| `vits-melo-tts-zh_en` | 159 MB | 中英雙語，可順便解掉下面的「只有中文」限制，但 2GB RAM 要實測 |

換模型只需替換 `assets/tts/zh/` 底下的檔案並改
`SherpaOfflineTtsAnnouncer.MODEL_FILE`，程式邏輯不用動。

---

## 怎麼接進系統

`SherpaOfflineTtsAnnouncer` 實作 domain 的 `Announcer` 介面，
由 `AssistantModule` 放進 `FallbackAnnouncer` 的候選鏈：

| 順位 | 實作 | 什麼時候輪到它 |
|---|---|---|
| 1 | `AndroidTtsAnnouncer` | 一般 Android 手機。系統引擎既省資源又支援多語言 |
| 2 | **`SherpaOfflineTtsAnnouncer`** | **眼鏡走這條**。第 1 順位在眼鏡上永遠不可用 |
| 3 | `LogOnlyAnnouncer` | 前兩個都失敗。不會有聲音，只把該唸的話寫進 log |

選擇發生在**每次播報前**而不是建構時 —— Android TTS 初始化是非同步的，
建構當下問到的答案還不算數。

---

## 已知限制

| 限制 | 影響 | 怎麼解 |
|---|---|---|
| **只有中文** | 翻譯結果（英文、日文）在眼鏡上**沒有聲音**，會落到第 3 順位只進 log | 加對應語言的模型，或換 `vits-melo-tts-zh_en` |
| **首次載入慢** | 要讀進 18.6MB ONNX。載入完成前 `isAvailable` 是 false | 已在背景執行緒載入，不擋 UI |
| **合成速度未實測** | 2GB RAM 的眼鏡上 RTF 未知 | 見下方待驗證 |

---

## 🔴 尚未在眼鏡上驗證

**程式編譯通過、APK 打包正確，但從未在真機上跑過。** 眼鏡目前不在手邊。

上機後依序確認：

```bash
# 0. 先確認 ABI 是不是 arm64-v8a（不是的話要改 app 的 abiFilters）
adb shell getprop ro.product.cpu.abilist

# 1. 裝上去，看模型載入有沒有成功
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am set-inactive com.guideglasses false
adb shell svc power stayon true
adb shell am start -n com.guideglasses/.MainActivity
adb logcat -d | grep -E "OfflineTts|TtsAnnouncer"
#   期待：「離線 TTS 就緒，取樣率 22050Hz，耗時 ____ms」
#   若看到「模型載入失敗」，錯誤堆疊會一起印出來

# 2. 觸發一則播報，確認真的有聲音
adb shell am broadcast -a com.guideglasses.DEBUG --es cmd SENSOR_TEST
```

| 要記錄的數字 | 為什麼重要 |
|---|---|
| 模型載入耗時 | 超過 3–4 秒的話開機後那段時間是啞的，可能要考慮預載提示 |
| **從觸發到第一個字出聲的延遲** | 障礙物播報的安全預算是 <300ms。串流合成就是為了這個 |
| 記憶體有沒有爆 | YOLO + 人臉 + TTS 三個模型同時載入，2GB RAM 是真的緊 |
| 數字有沒有唸對 | 說「測試相機」，聽它怎麼唸「480」。唸成「四八零」代表 rule FST 沒生效 |

> rule FST 能不能吃 assets 路徑無法在沒有機器的情況下確認，
> 所以載入時做了兩段式：先帶 FST 試，失敗就退回不帶 FST 再試一次
> （數字唸得不漂亮，總比完全沒聲音好）。log 會寫明走了哪一條。

---

## 為什麼相依關係寫得這麼彆扭

`build.gradle.kts` 裡是 `api("com.k2fsa:sherpa-onnx-static-link-onnxruntime:1.13.4@aar")`，
而 repository 宣告在 `settings.gradle.kts`。三件事值得記著：

1. **sherpa-onnx 不在 Maven Central。** 網路文章流傳的
   `com.k2fsa.sherpa.onnx:sherpa-onnx-android` 座標是錯的，那個 group 不存在。
   官方只提供 GitHub release 的 AAR，所以檔案直接進版控。
2. **一定要用 static-link 版本。** 一般版 AAR 內含自己的 `libonnxruntime.so`，
   會與 `ai-face` / `ai-vision` 用的 onnxruntime-android **撞名**。
3. **不能用 `files("....aar")`，也不要用 `flatDir`。** 前者被 AGP 直接擋下
   （library module 不支援）；後者能編譯，但產生的座標沒有 group，
   lint 的 `GradleDetector` 會拿它去組路徑然後丟 `InvalidPathException`。
   本地 maven 佈局 + `metadataSources { artifact() }` 是唯一乾淨的走法。

app 模組另外排除了 `lib/x86/**` —— static-link 版唯獨 x86 那顆仍帶著
`libonnxruntime.so`，而 `abiFilters` 擋不住它（合併發生在 ABI 過濾之前）。
