# guide-glasses 現況快照

> **這份文件每次有進度就更新。** 想知道「做到哪裡了」看這份就好。
> 需要交接給新對話時，直接把 §6 貼過去。

| | |
|---|---|
| 最後更新 | 2026-08-08 |
| `main` HEAD | `1c7066c` |
| 整體完成度 | **約 90%** |
| 單元測試 | **325 個，全過**（36 個測試類，純 JVM） |
| 模組數 | 15 |
| Kotlin 行數 | 8,535（主程式）+ 3,799（測試） |
| 建置狀態 | ✅ `./gradlew build` 通過，lint 無錯誤（AGP 9.3.1 / Gradle 9.5.0） |
| APK | debug **288 MB**（中文 TTS 92 ＋ 英文 TTS 19 ＋ 中文 ASR 26 ＋ 引擎 .so 23 ＋ 人臉 13 ＋ 障礙物 13 ＋ ORT 17） |
| clone 後可直接建置 | ✅ 模型已進版控，只需自補 `local.properties` |
| 實機驗證 | ✅ **相機／OCR／障礙物／翻譯／感測器／TTS 全部在眼鏡上實測通過**（TTS 起播 0.48s、RTF 1.00）；🔴 **STT 仍不可用**；🔴 **App 退到背景 2.4 秒就被系統殺掉**。見 [`DEVICE_FINDINGS.md`](DEVICE_FINDINGS.md) |

---

## 1. 專案架構

```
guide-glasses/
├── app/                        組裝層  508 行
│   ├── MainActivity.kt             單一 Activity，整片畫面是按鈕
│   ├── GuideGlassesApplication.kt  @HiltAndroidApp
│   └── di/
│       ├── CoreModule.kt           DispatcherProvider
│       └── AssistantModule.kt      全部功能的接線
│
├── core/
│   ├── core-domain/            4,184 行 + 3,532 行測試  ★ 純 Kotlin
│   │   ├── AppResult.kt            型別化的結果與錯誤
│   │   ├── announce/               播報優先級仲裁
│   │   ├── assistant/              意圖路由、對話歷史
│   │   ├── glasses/                影像來源、幀率節流、相機自我檢測
│   │   ├── ocr/                    辨識介面、斷句、朗讀進度
│   │   ├── face/                   比對、方位、距離、辨識策略、照片同步
│   │   ├── motion/                 步態、轉向指示、相機模式
│   │   ├── speech/                 ASR 介面
│   │   ├── translate/              目標語言解析、翻譯、語言包預下載
│   │   ├── obstacle/               八類、距離、危險分級、去抖動、偵測 UseCase
│   │   ├── navigation/             ★框架：定位抽象、球面幾何
│   │   ├── readiness/              出門前檢查
│   │   └── text/                   ASR 文字正規化（共用）
│   │
│   ├── core-common/               25 行   DispatcherProvider
│   └── core-database/            317 行   Room + Keystore 加密
│
├── glasses/                    眼鏡硬體
│   ├── glasses-camerax/          364 行   CameraX 影像來源
│   └── glasses-sensors/          214 行   IMU 感測
│
├── ai/                         AI 能力
│   ├── ai-speech/                466 行   SpeechRecognizer / TextToSpeech
│   ├── ai-tts-offline/           550 行   ★ APK 內建離線合成（中文＋英文）
│   ├── ai-asr-offline/           240 行   ★ APK 內建離線辨識（眼鏡唯一輸入途徑）
│   ├── ai-agent/                 251 行 + 193 行測試   LLM BFF 協定
│   ├── ai-ocr/                   124 行   ML Kit 中文（bundled）
│   ├── ai-face/                  860 行   ML Kit + ONNX/TFLite + 遠端 + 同步
│   ├── ai-translate/             179 行   ML Kit 翻譯（語言包執行期下載）
│   └── ai-vision/                385 行 + 74 行測試   YOLOv8 障礙物偵測
│
├── feature/
│   └── feature-assistant/        658 行   AssistantViewModel
│
└── tools/                      開發工具（不進 APK）
    ├── face_enroll_server.py     瀏覽器上傳照片＋標人名，零依賴
    └── README.md
```

**依賴方向**：`app` → `feature` → `core-domain` ← `glasses/* + ai/* + core-database`

`core-domain` 只套用 `kotlin.jvm`，任何 `android.*` 的 import 都會編譯失敗 ——
這是建置層面強制的架構約束，也是為什麼 3,532 行測試可以純 JVM 秒級跑完。

**部署形態**：單一 APK，直接裝在 Rokid Glasses 上執行。手機目前**不在必要路徑上**。
完整的分層決策與未來的手機 companion 設計見
[`ARCHITECTURE.md`](ARCHITECTURE.md)。

---

## 2. 功能進度

| 功能 | 完成度 | 狀態 |
|---|---:|---|
| 專案地基（多模組、Hilt、version catalog） | 95% | ✅ 缺 CI |
| 播報優先級仲裁 | 100% | ✅ |
| AI 助理中樞（雙層意圖路由） | 85% | ✅ 缺 BFF、眼鏡 AI 鍵 |
| 語音**合成**（TTS） | 90% | ✅ **眼鏡實測會出聲**。起播 0.48s、RTF 1.00、快取命中 +73ms。只有中文、8kHz |
| 語音**辨識**（STT） | 85% | ✅ **眼鏡實測可用**：「前面有什麼」正確辨識、5.5 秒。踩過兩個坑：`VOICE_RECOGNITION` 音訊來源是啞的、模型輸出簡體 |
| 相機（CameraX） | 85% | ✅ **眼鏡實測通過**（修掉假前鏡頭旗標）。⚠️ 擷取 **930ms**，比估計慢 6 倍 |
| OCR 朗讀 | 85% | ✅ **眼鏡實測管線完整** |
| 人臉辨識 | 95% | ✅ 端側可用，瀏覽器註冊＋語音同步 |
| IMU 動作感測 | 75% | ✅ 眼鏡實測。⚠️ **沒有電子羅盤**，導航拿不到絕對方位 |
| 翻譯 | 90% | ✅ **眼鏡實測：語言包下載成功**（無 Play Services 也能用） |
| 障礙物偵測 | 80% | ✅ **眼鏡實測：YOLO ONNX 載入並推論成功**，2GB RAM 沒 OOM |
| 導航 | 15% | 🔴 **難度上修**：無 GPS **且無電子羅盤**，連「往哪轉」都算不出來 |

---

## 3. 目前可用的語音指令

| 說法 | 動作 | 需要什麼 |
|---|---|---|
| 停 / 安靜 / 別說了 | 立刻靜音 | — |
| 再說一次 / 剛剛說什麼 | 重播上一則 | — |
| **測試相機** | 回報解析度與耗時 | 相機權限 |
| **測試感測器** | 回報實際可用的感測能力 | — |
| 唸給我聽 / 上面寫什麼 | OCR 文件朗讀 | 相機權限 |
| 這是哪裡 / 招牌寫什麼 | OCR 招牌模式（只唸最大的字） | 相機權限 |
| 下一段 / 上一段 / 繼續唸 | 朗讀控制 | 進行中的朗讀 |
| 這是誰 | 人臉辨識，含方位與距離 | 模型檔**或**遠端後端 |
| **同步人臉** | 從註冊工具抓照片、重算特徵 | `photoEndpoint` ＋ 模型檔 |
| **出門前檢查** | 回報離線可用狀態與該補什麼 | — |
| **準備翻譯** | 預先下載語言包 | 網路 |
| 翻成英文 / 翻譯 | 翻譯上一次 OCR 的內容 | 首次該語言需網路下載語言包 |
| **⋯⋯翻成英文** | 直接翻譯口述內容 | 同上 |
| **前面有什麼** | 障礙物偵測（YOLOv8 八類） | 相機權限 |
| 帶我去⋯ | 導航 | ⏸ 回「開發中」，且需 BFF |

**最實用的組合**：「唸給我聽」→ 聽到中文 → 「翻成英文」，同一份內容不必再拍一次。

**哪些指令不需要網路**：除了「翻成⋯」的首次語言包下載、以及需要抽開放集合參數的
指令（「帶我去台北101」）之外，**全部離線可用**。翻譯的目標語言是封閉集合，
在本地解析，所以翻譯本身不需要 BFF。

---

## 4. 阻塞項目

| # | 卡在什麼 | 誰能解 | 影響 |
|---|---|---|---|
| 1 | ✅ ~~App 退到背景就被殺~~ | Foreground Service 已完成 | ⚠️ 但每台眼鏡要跑一次 `adb shell cmd appops set com.guideglasses RUN_ANY_IN_BACKGROUND allow`，否則系統會**靜默**拒絕（`DEVICE_FINDINGS.md` §21） |
| 1b | 🟡 **STT 已實作，待驗證辨識率** | 需要有人對眼鏡說話（見 `ai/ai-asr-offline/README.md`） | 驗證前不能宣稱語音輸入可用 |
| 2 | ~~障礙物模型未交付~~ | ✅ **已解決** | YOLOv8n-seg 八類已接上並進版控 |
| 3 | ~~端側人臉模型檔~~ | ✅ **已解決** | 用 InsightFace 的 `w600k_mbf.onnx`，直接執行不需轉檔 |

~~導航架構未定~~ → **已於 2026-08-06 決策**：手機 companion 只當
「GPS 感測器 + 網路閘道」，播報仲裁一律留在眼鏡上。理由與被否決的方案見
[`ARCHITECTURE.md`](ARCHITECTURE.md) §5。導航現在可以開工。

詳見 [`ROADMAP.md`](ROADMAP.md) §2。

---

## 4.5 clone 下來怎麼跑起來

**模型與所有建置必需檔案都已進版控，clone 完只差一個 SDK 路徑。**

```bash
git clone https://github.com/mato1321/guide_glasses_project_.git
```

建立 `guide-glasses/local.properties`（這是**唯一**需要自己補的檔案，
因為 SDK 路徑每台機器不同。反斜線與冒號都要跳脫）：

```
sdk.dir=C\:\\Users\\<你的帳號>\\AppData\\Local\\Android\\Sdk
```

> 或者設好 `ANDROID_HOME` 環境變數也可以，AGP 會自動找到。

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd guide-glasses && ./gradlew build
```

需要 JDK 17+（JDK 11 不行）與 Android SDK Platform 36。

**哪些功能 clone 完就能測**：

| 功能 | 額外需要什麼 |
|---|---|
| 語音助理、STT / TTS、播報仲裁 | — |
| 相機、感測器自我檢測 | — |
| OCR 朗讀（文件／招牌） | — |
| 人臉辨識（端側） | 先跑註冊工具建幾個人，見 [`tools/README.md`](../guide-glasses/tools/README.md) |
| 翻譯 | 首次該語言需連網下載語言包 |
| 障礙物偵測 | — |
| 導航 | ⏸ 尚未實作，會播報「開發中」 |

`local.properties` 可選的兩行（不設也能跑）：

```
guideglasses.photoEndpoint=http://<你的IP>:8100     # 人臉同步來源
guideglasses.faceEndpoint=http://<你的IP>:8000/recognize   # 遠端人臉備援
```

---

## 5. 出門實測怎麼準備

> **眼鏡沒有 SIM 卡。** 出了 Wi-Fi 範圍就完全沒有網路。
> 四個核心功能本身都是端側的，但有**兩件事必須出門前用網路做完**。

### 出門前（在有 Wi-Fi 的地方）

| 步驟 | 說什麼 | 為什麼 |
|---|---|---|
| 1 | 上傳照片到註冊工具 | 見 [`tools/README.md`](../guide-glasses/tools/README.md) |
| 2 | 「**同步人臉**」 | 沒同步＝出門後誰都認不出來 |
| 3 | 「**準備翻譯**」 | 語言包約 30MB，出門後下載不了 |
| 4 | 「**出門前檢查**」 | 一句話確認上面兩項都好了 |

第 4 步會聽到其中之一：

> ✅「可以出門了。認得 5 個人，英文翻譯已就緒」
> ❌「還沒完全準備好。人臉資料庫是空的，請先說同步人臉。目前離線可用的有：OCR 朗讀」

**實測最常見的失敗不是程式壞掉，而是忘記同步就出門。** 這個檢查就是為此存在。

### 出門後（無網路）

| 功能 | 離線可用 |
|---|---|
| 助理本地指令（停、再說一次、測試相機／感測器） | ✅ |
| OCR 朗讀（文件／招牌／分段控制） | ✅ |
| 人臉辨識（同步過之後） | ✅ |
| 翻譯（語言包下載過之後） | ✅ |
| 「帶我去⋯」「把他記起來」 | ❌ 需 BFF，會播報離線提示 |

**所以這四個功能出門完全不需要網路，也不需要 BFF。**

---

## 5.5 最重要的待辦：裝上 Rokid Glasses

### 目前的驗證狀態

| 平台 | 狀態 |
|---|---|
| 小米 Android 手機 | 🟡 **已跑過**，並因此找出三個真實 bug（見 §7） |
| **Rokid Glasses** | ❌ **從未執行過** |

**手機通過不代表眼鏡會通過。** 兩者差異大的地方：

| 項目 | 手機 | Rokid Glasses |
|---|---|---|
| 相機視角 | 一般廣角 | **官方未載明**，距離估計靠它 |
| 續航 | 4000mAh+ | **210mAh，開相機可能 <1.5 小時** |
| RAM | 6–12 GB | **2 GB**，三個 ONNX 模型同時載入未驗證 |
| Google App / Play Services | 有 | **未知**，STT 與 ML Kit 都可能受影響 |
| GPS | 有 | **推定沒有** |

### 上機後先做這三件事

1. ~~「停」→ 確認有沒有聲音~~ → ✅ **已做，沒有聲音**。原因見
   [`DEVICE_FINDINGS.md`](DEVICE_FINDINGS.md) §4
2. ~~「測試感測器」~~ → ✅ 已用 debug 廣播做完（沒有羅盤）
3. ~~「測試相機」~~ → ✅ 已用 debug 廣播做完（930ms）

> 🟡 **輸出端已有解法待驗證。** 下次上機第一件事：確認 `ai-tts-offline`
> 真的會出聲（步驟見該模組 `README.md`）。這一關過了，所有功能才終於
> 能做端到端測試。
>
> 🔴 **輸入端仍然無解。** 眼鏡上沒有任何語音辨識服務，
> 目前只能靠 debug 廣播觸發功能。

完整清單見 [`TASKS.md`](TASKS.md) §A。

> ⚠️ 請把第一次裝上眼鏡當成**探勘**而不是驗收。手機上已經找出三個
> 只有實跑才會發現的 bug，眼鏡上大機率還有第四個。

---

## 6. 交接給新對話用

> 需要開新對話時，把下面整段貼過去。

```text
接手 Rokid AI 導盲眼鏡專案（guide-glasses）。

## 先讀這五份
1. docs/DEVICE_FINDINGS.md   ← 🔴 最重要：眼鏡實測發現，很多「規格」是假的
2. docs/STATUS.md            ← 現況快照
3. docs/TASKS.md             ← 待辦清單
4. docs/DEVELOPER_GUIDE.md   ← 新手總覽：建置、架構、所有功能、Debug、FAQ
5. docs/ARCHITECTURE.md      ← 分層決策

## 環境
- 建置需要 JDK 17+（JDK 11 不行）。若 Android Studio 更新過導致
  jbr 壞掉，改用 "Android Studio1/jbr"：
    export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
    cd guide-glasses && ./gradlew build
- AGP 已升到 9.3.1，靠 gradle.properties 的 android.builtInKotlin=false
  與 android.newDsl=false 兩個相容開關，**不要拿掉那兩行**。
- adb 在 PATH：
  /c/Users/mato/AppData/Local/Microsoft/WinGet/Packages/Google.PlatformTools_*/platform-tools

## 眼鏡上的硬事實（全部 adb 實測，別再假設）
- ❌ 沒有 Google Play Services、沒有 Play Store
- ❌ 沒有任何語音辨識服務 → STT 完全不可用
- ❌ Android TTS 框架綁定失敗 → 已改走 APK 內建離線引擎（`ai-tts-offline`），**待上機驗證**
- 💡 但**音訊輸出本身是好的** —— 綁不上 TTS ≠ 不能出聲，這個區別是解法的關鍵
- ❌ 沒有 GPS provider、沒有電子羅盤（磁力計）
- ⚠️ 相機擷取 930ms（文件原本估 145ms）
- ⚠️ 螢幕逾時 5 秒，App idle 時 Android 會擋掉相機
- 🔴 反覆出現的陷阱：pm list features 宣告有，實際上沒有。
  GPS、前鏡頭、TTS 都中過。**任何硬體能力都要實測，不能看 API 宣告。**

## 眼鏡上怎麼測
  # 🔴 每台眼鏡第一次要跑這行，否則前景服務會被系統「靜默」擋掉
  adb shell cmd appops set com.guideglasses RUN_ANY_IN_BACKGROUND allow
  adb shell am set-inactive com.guideglasses false
  adb shell svc power stayon true
  adb shell am start -n com.guideglasses/.MainActivity
  adb shell am broadcast -a com.guideglasses.DEBUG --es cmd CAMERA_TEST
  adb logcat -d | grep TtsAnnouncer
TTS 失敗時會把「本來要唸的話」印進 log —— 聽不到但看得到。
cmd 可用任何 AssistantIntent 名稱，可帶 --es target_language / text / name。

## 工作規則
- 只在 guide-glasses/ 開發。AI_Assistant/、Face_Recognition/、
  Obstacle_Recognition/、Audio_Navigation/、Text_Recognition/ 是五位組員
  各自的工作區，不得修改。需要引用時複製過來重構。
- Git 只保留 main，直接 git push origin HEAD:main，不開 PR。
- 每完成一項就重新編譯、跑測試、更新 docs/STATUS.md 與 docs/TASKS.md。
- 一律用繁體中文回覆。

接下來要做：<寫你要的>
```

**「接下來要做」可以填什麼**

| 你的狀況 | 填什麼 |
|---|---|
| **想讓翻譯有聲音** | 「加英文 TTS 模型 —— 翻譯結果目前落到 LogOnly，完全沒聲音」 |
| **想解決產品化** | 「研究 Device Owner，讓背景限制不必靠 adb 解除」 |
| 想解決語音**輸入** | 「用 sherpa-onnx 的 ASR 接 STT —— .so 已經在 APK 裡了，只差模型」 |
| 想繼續做功能 | 「接上 CameraModeController」—— 走路才開相機，眼鏡續航很吃這個 |
| 想驗證人臉 | 「跑註冊工具建幾個人，在眼鏡上測同步與辨識」 |
| 想推進導航 | 「實作 FollowHeadingUseCase」—— 純 IMU，但注意沒有羅盤 |

---

## 7. 變更歷史

| 日期 | 進度 | 內容 |
|---|---:|---|
| 2026-08-09 | 93% | **拿掉喚醒詞，改成常駐聽指令**：關鍵詞偵測模型直接把指令當關鍵詞，跳過語音辨識。修掉多個 AudioRecord 搶麥克風 |
| 2026-08-09 | 92% | **喚醒詞「呼叫盲狗」**（常駐監聽、不必按按鈕）、螢幕常亮。人臉/翻譯/OCR 使用者實測可用 |
| 2026-08-08 | 91% | **修掉助理聽到自己講話而重複執行指令**；中文換成 22050Hz 的 matcha（使用者聽過三顆模型後選的）；每顆模型各自校增益 |
| 2026-08-08 | 90% | **STT 眼鏡實測可用**。修掉兩個坑：`VOICE_RECOGNITION` 音訊來源回傳純靜音（改用 `MIC`）、模型輸出簡體而片語是繁體（加繁簡摺疊，+10 測試） |
| 2026-08-08 | 88% | **英文語音**（翻譯結果終於有聲音）、**離線 STT**（`ai-asr-offline`，模型載入 6.0s）、**佈建文件**（`PROVISIONING.md`）。修正每個語音各自的增益 —— 英文峰值 0.58 沿用中文的 3.5 倍會削波 |
| 2026-08-08 | 86% | **Foreground Service 完成並實測**：背景 40 秒存活、背景開相機成功。發現 YodaOS 預設把每個 App 背景限制（連 Maps 也是），`startForeground` 被**靜默拒絕** —— 程式已加主動查證。修正播報音量（+11dB） |
| 2026-08-08 | 84% | **眼鏡實測離線 TTS 會出聲**：修掉 JNI lambda 導致的行程 abort、換成 aishell3（RTF 2.2→1.0、起播 2.3s→0.48s）、加合成快取（+73ms）。發現 **App 退到背景 2.4 秒就被殺** |
| 2026-08-08 | 82% | **繞開眼鏡壞掉的 TTS 框架**：新增 `ai-tts-offline`（sherpa-onnx + VITS 中文模型），`FallbackAnnouncer` 候選鏈讓手機用系統 TTS、眼鏡自動落到離線引擎（+13 測試）。⚠️ 未上機驗證 |
| 2026-08-08 | 80% | **眼鏡實測：相機／OCR／障礙物／翻譯／感測器全通過**；修掉假前鏡頭旗標；A3/A4 解答 |
| 2026-08-08 | 78% | 實測確認 Glass3 SDK 在消費版眼鏡不可用（`isReady()=false`）；排除 Glass3 企業版 SDK（缺 `com.rokid.security.system.server`）；找到 Sprite 原生 TTS action |
| 2026-08-08 | 78% | **A10 解答：眼鏡宣告有 GPS 但沒有 provider**，確認須走手機 companion |
| 2026-08-08 | 78% | **首次在 Rokid Glasses 執行**：發現無 Play Services、無 STT、TTS 綁定失敗（`DEVICE_FINDINGS.md`） |
| 2026-08-07 | 78% | YOLOv8 障礙物偵測接上；類別索引按名稱對照（與 ordinal 有 6 處不一致） |
| 2026-08-07 | 75% | 口述內容直接翻譯；修正 ML Kit `isReady()` 漏檢來源語言（實機 100% 失敗） |
| 2026-08-07 | 73% | **小米手機實測**：修正 OCR 逐行被切碎、以及謊稱沒有網路 |
| 2026-08-07 | 72% | **小米手機實測**：缺離線語音包時退回線上辨識（原本按下說話一律失敗） |
| 2026-08-07 | 70% | 升級 AGP 9.3.1 / Gradle 9.5.0，用兩個相容開關解掉先前認為無解的衝突 |
| 2026-08-06 | 70% | 新增 `DEVELOPER_GUIDE.md`：新手從零接手的完整文件（11 張 Mermaid） |
| 2026-08-06 | 70% | 出門前檢查、語言包預下載；障礙物與導航的 domain 框架（不需模型即可測試） |
| 2026-08-06 | 65% | 人臉模型改為隨 repo 附上，clone 完即可測試；`face_photos/` 加入忽略 |
| 2026-08-06 | 65% | 端側人臉打通：ONNX Runtime、瀏覽器註冊工具、語音「同步人臉」 |
| 2026-08-06 | 60% | 三層架構決策（`ARCHITECTURE.md`）：導航走手機 companion A 案，導航解除阻塞 |
| 2026-08-05 | 60% | `ai-translate`：ML Kit 離線翻譯、OCR→翻譯串接、TTS 逐句切換語言 |
| 2026-08-05 | 55% | 修正 `local.properties` 設定靜默失效（`providers.gradleProperty` 不讀該檔） |
| 2026-08-05 | 55% | 遠端人臉辨識、docs 整併（9→4 份）、實作規劃文件 |
| 2026-08-05 | 53% | `glasses-sensors`：IMU 感測、相機模式自動切換 |
| 2026-08-05 | 48% | `ai-face`：端側人臉辨識、Keystore 加密儲存 |
| 2026-08-05 | 38% | `ai-ocr`：ML Kit 中文、分段朗讀、朗讀控制 |
| 2026-08-05 | 27% | `glasses-camerax`：CameraX 影像來源、相機自我檢測 |
| 2026-08-05 | 18% | Phase 2：AI 助理中樞、Android 原生 STT/TTS |
| 2026-08-05 | 10% | Phase 1：多模組地基、播報仲裁 |

> 更新這份文件時，順手在這裡加一行。
