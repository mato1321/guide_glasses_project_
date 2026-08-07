> 🔴 **2026-08-08 更新：本文件的規劃多數已實作完成。**
> 翻譯、障礙物、人臉同步都已完成並（除人臉外）在眼鏡實測通過。
> 現況見 [`STATUS.md`](STATUS.md)，實機限制見 [`DEVICE_FINDINGS.md`](DEVICE_FINDINGS.md)。

# 各功能實作規劃與整合方式

每個功能怎麼做、怎麼接進 guide-glasses。已完成的部分也寫進來 ——
它們是後續功能的範本。

> 目前做到哪裡見 [`STATUS.md`](STATUS.md)，逐項待辦見 [`TASKS.md`](TASKS.md)。

最後更新：2026-08-05

---

## 目錄

| # | 功能 | 狀態 |
|---|---|---|
| 0 | [整體架構與模組關係](#0-整體架構與模組關係) | — |
| 1 | [新增一個功能的通用流程](#1-新增一個功能的通用流程) | 📖 範本 |
| 2 | [AI 語音助理](#2-ai-語音助理已完成) | ✅ |
| 3 | [OCR 朗讀](#3-ocr-朗讀已完成) | ✅ |
| 4 | [人臉辨識](#4-人臉辨識已完成) | ✅ |
| 5 | [障礙物偵測](#5-障礙物偵測規劃中) | ⏸ 等模型 |
| 6 | [導航](#6-導航需先做架構決策) | 🔴 等決策 |
| 7 | [翻譯](#7-翻譯規劃中無阻塞) | ✅ **已完成** |

---

## 0. 整體架構與模組關係

```mermaid
graph TB
    subgraph APP["app（組裝層）"]
        MA["MainActivity"]
        DI["Hilt Modules"]
    end

    subgraph FEATURE["feature（功能）"]
        FA["feature-assistant<br/>AssistantViewModel"]
    end

    subgraph DOMAIN["core-domain（純 Kotlin，無 Android 依賴）"]
        direction LR
        D1["assistant/<br/>意圖路由"]
        D2["announce/<br/>播報仲裁"]
        D3["glasses/<br/>影像來源"]
        D4["ocr/<br/>辨識與分段"]
        D5["face/<br/>比對與方位"]
        D6["motion/<br/>步態與方位"]
    end

    subgraph IMPL["實作層"]
        G1["glasses-camerax<br/>CameraX"]
        G2["glasses-sensors<br/>SensorManager"]
        A1["ai-speech<br/>STT / TTS"]
        A2["ai-agent<br/>LLM BFF"]
        A3["ai-ocr<br/>ML Kit"]
        A4["ai-face<br/>ML Kit + TFLite + 遠端"]
        DB["core-database<br/>Room + Keystore"]
    end

    MA --> FA
    DI -.注入.-> FA
    FA --> DOMAIN
    IMPL -.實作介面.-> DOMAIN
    DI -.綁定.-> IMPL
    A4 --> DB

    style DOMAIN fill:#e8f5e9
    style IMPL fill:#e3f2fd
    style APP fill:#fff3e0
```

**單向依賴**：`app` → `feature` → `core-domain` ← `實作層`

實作層依賴 domain 的介面，domain 不知道實作的存在。這讓「換掉端側模型」
或「加一條遠端路徑」都不會波及上層。

---

## 1. 新增一個功能的通用流程

**每個功能都照這六步走。** 前三步是純 Kotlin，可以在沒有裝置的情況下完成
並用單元測試驗證；後三步才碰 Android。

```mermaid
flowchart TD
    S1["1. 在 core-domain 定義介面與資料模型<br/>純 Kotlin，不 import android.*"]
    S2["2. 在 core-domain 寫 UseCase 與純邏輯<br/>時鐘、隨機數一律由建構子注入"]
    S3["3. 寫單元測試<br/>./gradlew :core:core-domain:test"]
    S4["4. 建立實作模組 ai-xxx 或 glasses-xxx<br/>只有這裡能 import Android API"]
    S5["5. 在 AssistantIntent 加意圖<br/>+ LocalCommandMatcher 加片語"]
    S6["6. ViewModel 分派 + Hilt 接線<br/>./gradlew build"]

    S1 --> S2 --> S3 --> S4 --> S5 --> S6

    S3 -.測試不過就回到 2.-> S2

    style S1 fill:#e8f5e9
    style S2 fill:#e8f5e9
    style S3 fill:#c8e6c9
    style S4 fill:#e3f2fd
    style S5 fill:#e3f2fd
    style S6 fill:#fff3e0
```

### 每一步的具體做法

**Step 1 — Domain 介面**

```kotlin
// core-domain/src/main/kotlin/.../xxx/XxxGateway.kt
interface XxxGateway {
    val isAvailable: Boolean            // 幾乎每個介面都要有這個
    suspend fun doSomething(): AppResult<XxxResult>
}
```

`isAvailable` 不是可有可無 —— 它讓上層能在能力缺失時**播報人話**而不是靜默失敗。
這是這套系統反覆出現的模式（缺模型檔、沒有語音服務、沒有磁力計）。

**Step 2 — UseCase**

```kotlin
class XxxUseCase(
    private val frameSource: FrameSource,
    private val gateway: XxxGateway,
    private val now: () -> Long = System::currentTimeMillis,  // ← 注入時鐘
) {
    suspend fun execute(): Outcome { ... }

    sealed interface Outcome {
        data class Success(...) : Outcome { val spoken: String get() = "..." }
        data object NothingFound : Outcome
        data class Failed(val error: AppError) : Outcome
    }
}
```

**`spoken` 屬性放在 Outcome 裡**，讓「該說什麼話」成為領域決策而不是 UI 決策 ——
這樣播報內容也能被單元測試涵蓋。

**Step 3 — 測試**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd guide-glasses && ./gradlew :core:core-domain:test
```

**Step 4 — 實作模組**

`settings.gradle.kts` 加 `include(":ai:ai-xxx")`，
`build.gradle.kts` 照抄 `ai-ocr` 的即可。

**Step 5 — 語音指令**

```kotlin
// AssistantIntent.kt
XXX(toolName = "xxx", description = "給 LLM 看的說明"),

// LocalCommandMatcher.kt —— 注意順序即優先級
AssistantIntent.XXX to listOf("片語一", "片語二"),
```

⚠️ **順序很重要。** 「唸下一段」同時含有「唸」與「下一段」，
`READING_NEXT` 必須排在 `READ_TEXT` 之前。加新片語時先想清楚會不會被
既有規則吃掉。

**Step 6 — 接線**

```kotlin
// AssistantViewModel.kt
AssistantIntent.XXX -> runXxx()

// AssistantModule.kt
@Provides @Singleton
fun provideXxxGateway(@ApplicationContext context: Context): XxxGateway = ...
```

### 播報時要選對優先級

| 優先級 | 用在 | 例子 |
|---|---|---|
| `CRITICAL` | 立即危險 | 2m 內的車、地面落差 |
| `USER_RESPONSE` | 使用者主動問的 | 「這是誰」的答案 |
| `NAVIGATION` | 有時效的提示 | 轉彎、到站 |
| `AMBIENT` | 可以被打斷的 | OCR 長文（記得 `resumable = true`） |

**任何模組都不得自己持有 `TextToSpeech` 或 `MediaPlayer`** ——
一律走 `AnnouncementManager`，否則會重演舊專案三套播放器互相蓋台的問題。

---

## 2. AI 語音助理（已完成）

系統中樞。所有功能都由它分派。

```mermaid
sequenceDiagram
    participant U as 使用者
    participant VM as AssistantViewModel
    participant S as SpeechRecognizer
    participant L as LocalCommandMatcher
    participant R as RemoteLlmIntentGateway
    participant M as AnnouncementManager

    U->>VM: 點畫面
    VM->>M: clearAtOrBelow(NAVIGATION)
    Note over M: 使用者要說話，先讓低優先內容安靜
    VM->>S: listen(preferOffline=true)
    U->>S: 「這是誰」
    S-->>VM: FinalResult

    VM->>L: match("這是誰")
    alt 本地命中（<100ms，離線可用）
        L-->>VM: IDENTIFY_PERSON
    else 未命中
        L-->>VM: null
        VM->>R: POST /route {utterance, history, tools}
        alt 有網路
            R-->>VM: tool_use + 參數
        else 無網路
            R-->>VM: 降級訊息「目前沒有網路，你仍然可以說…」
        end
    end

    VM->>VM: dispatch(intent)
    VM->>M: announce(結果, USER_RESPONSE)
```

**設計要點**

- 雙層路由：「停」不能等雲端，本地比對 <100ms
- 只比對**使用者說的話**，不比對 AI 的回覆（舊專案的誤觸發成因）
- 沒網路時降級成人話，並主動提示還有哪些離線指令可用

**整合方式**：新功能只要在 `AssistantIntent` 加一個 enum、在
`LocalCommandMatcher` 加片語、在 ViewModel 加一個 `when` 分支，就自動接上。

---

## 3. OCR 朗讀（已完成）

```mermaid
flowchart TD
    A["「唸給我聽」/「這是哪裡」"] --> B["FrameSource.captureOnce<br/>1280 長邊、JPEG 90"]
    B --> C["MlKitTextRecognizer<br/>中文 bundled、約 100ms、離線"]
    C --> D{"looksUnreliable？<br/>字太少 / 大量單字元區塊"}
    D -->|否| F["採用端側結果"]
    D -->|是| E["Cloud fallback<br/>（介面已備，BFF 未建）"]
    E -->|不可用| F
    F --> G{"OcrMode"}
    G -->|DOCUMENT| H["完整文字"]
    G -->|SIGN| I["只取 heightRatio 最大的區塊"]
    H --> J["SpeechSegmenter<br/>斷句、標題標記、80 字分塊"]
    I --> J
    J --> K["ReadingSession<br/>下一段 / 上一段 / 重聽"]
    K --> L["AnnouncementManager<br/>AMBIENT + resumable"]

    style C fill:#c8e6c9
    style J fill:#c8e6c9
```

**兩個關鍵設計**

`SpeechSegmenter` 移植自 `Text_Recognition` 並修了兩個 bug（標題判斷時機、
超長單句不拆）。它處理的不是「怎麼切字串」而是「怎麼唸才聽得懂」。

`ReadingSession` 是長文能不能用的關鍵 —— 一份公文可能切成三十段，
沒有「上一段」使用者聽漏一句就只能從頭再來，實務上他會放棄這個功能。

**未完成**：Cloud fallback（需 BFF）、Vision LLM 第三層、朗讀速度調整。

---

## 4. 人臉辨識（已完成）

```mermaid
flowchart TD
    A["「這是誰」"] --> B["captureOnce<br/>960 長邊、JPEG 85"]
    B --> C["MlKitFaceDetector<br/>bundled、約 5ms"]
    C --> D{"有臉？"}
    D -->|否| E["「前方沒有偵測到人」"]
    D -->|是| F["取面積最大的那張<br/>= 最靠近 = 互動對象"]

    F --> G{"CompositeFaceIdentification<br/>依序試"}
    G -->|端側可用| H["TfLiteFaceEmbedder<br/>需 .tflite"]
    G -->|端側不可用/失敗| I["RemoteFaceIdentification<br/>裁切後的臉 3-8KB"]

    H --> J["FaceMatcher<br/>與本地 Room 加密資料庫比對"]
    I --> K["POST /recognize<br/>沿用既有 InsightFace 後端"]

    J --> L{"相似度"}
    K --> L
    L -->|">= 0.6"| M["「右前方，大約 2 公尺，是小明」"]
    L -->|"0.45-0.6"| N["「…可能是小明，不太確定」"]
    L -->|"< 0.45"| O["「…有一個人，我不認識」"]

    M --> P["AnnouncementManager<br/>dedupeKey = face:id"]
    N --> P
    O --> P

    style I fill:#fff3e0
    style N fill:#fff9c4
```

**雙策略是刻意的** —— 端側隱私與延遲較好但需要模型檔；遠端沿用團隊既有後端，
今天就能用。DI 自動挑，兩條都沒有才播報「人臉辨識不可用」。

**三段式信心** 取代舊後端的單一閾值 0.4。0.41 時它會信誓旦旦地喊錯名字 ——
認錯人的代價比漏認高，所以中間那段要帶不確定性。

**未完成**：多張照片註冊（提升穩定度）、相機視角校正、端側模型檔。

---

## 5. 障礙物偵測（規劃中）

### 5.1 需要 Obstacle_Recognition 先交付

| 項目 | 說明 |
|---|---|
| `.tflite` | INT8 量化 |
| 類別索引對照 | `data.yaml` 的 `names`，8 類的順序 |
| 輸入尺寸 | 通常 640×640 |
| 前處理規格 | 正規化方式（`[0,1]` 還是 `[-1,1]`）、RGB 還是 BGR |
| 後處理規格 | 輸出張量形狀、NMS 是否已內建 |
| 驗證集 mAP | 用來設信心閾值 |

⚠️ **前處理規格特別重要。** 弄錯不會有任何錯誤訊息，模型照樣輸出數字，
只是那些數字沒有意義 —— 會安靜地什麼都偵測不到。這個坑在 `ai-face` 的
模型 README 也警告過。

### 5.2 資料流

```mermaid
flowchart TD
    subgraph TRIGGER["觸發"]
        T1["「前面有什麼」<br/>單張查詢"]
        T2["行進模式<br/>IMU 偵測到走路"]
    end

    T1 --> CM["CameraModeController<br/>已完成"]
    T2 --> CM
    CM --> MODE{"模式"}
    MODE -->|STANDBY| X["相機關閉"]
    MODE -->|WALKING 3fps| C["FrameSource.frames<br/>RGBA 640"]
    MODE -->|POWER_SAVING 1fps| C

    C --> Y["YoloDetector<br/>TFLite + NNAPI/GPU"]
    Y --> Z["8 類偵測結果"]

    Z --> H1["危險類<br/>car / motorcycle / bicycle<br/>people / obstacle"]
    Z --> H2["導引類<br/>crosswalk / guidebrick / sidewalk"]

    H1 --> DIST["ObstacleDistanceEstimator<br/>已知尺寸反推 + 地平面假設"]
    DIST --> BEAR["BearingResolver<br/>已完成，可直接重用"]
    BEAR --> RANK["DangerClassifier<br/>類別 × 距離 → 優先級"]

    RANK --> P0{"危險等級"}
    P0 -->|"<2m 車輛"| C1["CRITICAL<br/>「停！右方有車」"]
    P0 -->|"2-5m"| C2["NAVIGATION<br/>「前方三公尺有機車」"]
    P0 -->|">5m"| C3["不播報<br/>避免疲勞轟炸"]

    H2 --> C4["導引播報<br/>「導盲磚在左側」"]

    C1 --> AM["AnnouncementManager<br/>dedupeKey 去抖動"]
    C2 --> AM
    C4 --> AM

    style CM fill:#c8e6c9
    style BEAR fill:#c8e6c9
    style C1 fill:#ffcdd2
    style C3 fill:#e0e0e0
```

綠色是**已完成可直接重用**的部分。

### 5.3 實作步驟

**Step 1 — domain（純 Kotlin，可先做）**

```kotlin
// core-domain/.../vision/ObstacleModels.kt
enum class ObstacleClass(val spoken: String, val isHazard: Boolean) {
    CAR("汽車", true), MOTORCYCLE("機車", true), BICYCLE("腳踏車", true),
    PEOPLE("行人", true), OBSTACLE("障礙物", true),
    CROSSWALK("斑馬線", false), GUIDEBRICK("導盲磚", false), SIDEWALK("人行道", false),
}

data class Detection(
    val obstacleClass: ObstacleClass,
    val confidence: Float,
    val left: Float, val top: Float, val width: Float, val height: Float,  // 相對比例
)

interface ObstacleDetector {
    val isAvailable: Boolean
    suspend fun detect(frame: CameraFrame): AppResult<List<Detection>>
}
```

**Step 2 — 距離、分級、播報（純邏輯，全部可測）**

```kotlin
class ObstacleDistanceEstimator(horizontalFovDegrees: Float = 66f) {
    // 已知寬度：汽車 1.8m、機車 0.8m、行人 0.45m
    fun estimateMeters(obstacleClass: ObstacleClass, widthRatio: Float): Float?
}

class DangerClassifier {
    fun classify(detection: Detection, meters: Float?): AnnouncementPriority?
    // 回傳 null 代表不播報
}

class ObstacleAnnouncementComposer {
    // 「右前方三公尺有機車」
    fun compose(detection: Detection, bearing: Bearing, meters: Float?): String
}
```

**這一步就能寫完整的單元測試**，不需要模型也不需要裝置。

**Step 3 — TFLite 實作**

`ai/ai-vision` 模組，照 `ai-face` 的 `TfLiteFaceEmbedder` 寫。
記得 `androidResources { noCompress += "tflite" }`。

**Step 4 — 接上相機模式**

`CameraModeController` 已完成，把 `MotionSensorGateway.walkingState()` 接上去即可
自動切換 STANDBY / WALKING / POWER_SAVING。

**Step 5 — 語音指令**

`DETECT_OBSTACLES` 的 intent 與片語已存在，目前回「開發中」，
把那個分支改成呼叫新的 UseCase 就好。

### 5.4 幾個必須想清楚的取捨

**播報疲勞。** 走在馬路邊每秒都有車。**不能看到什麼就唸什麼。**
建議：只播報「新出現的」與「距離縮短到危險範圍的」，用 `dedupeKey`
（例如 `obstacle:car:right`）搭配時間窗抑制重複。

**導引類與危險類的節奏不同。** 「導盲磚在左側」這種資訊變化慢，
播報頻率應該遠低於車輛警示。

**產品定位。** 導盲杖已經很有效地處理近距離地面障礙。這套系統的增量價值
主要在杖子碰不到的地方 —— 遠距離接近的車輛、頭部高度的障礙。
設計時應該偏重這些。

---

## 6. 導航（需先做架構決策）

### 6.1 🔴 阻塞：眼鏡沒有 GPS（2026-08-08 實測確認），**而且沒有電子羅盤**

> 實測結果：`dumpsys location` 只有 `passive` / `fused`，沒有 `gps` 也沒有
> `network` provider。方案 C（網路定位）因此**也不成立**。
> 更麻煩的是**沒有磁力計** —— 即使手機給了座標，仍算不出朝向。

```mermaid
flowchart TD
    Q["導航需要絕對位置"] --> A{"眼鏡有 GPS？"}
    A -->|沒有| B{"眼鏡有網路定位？<br/>LocationManager 的 provider"}
    B -->|"❌ 實測沒有"| C["方案 C：網路定位<br/>🔴 已排除"]
    B -->|沒有| D{"選架構"}

    C --> C1["⚠️ 對步行導航精度不足<br/>且通常需 Play Services"]

    D --> E["方案 A<br/>手機 companion 送 GPS 給眼鏡"]
    D --> F["方案 B<br/>導航跑手機、感測跑眼鏡"]

    E --> E1["代價：多一個 App<br/>+ 一條通訊管道"]
    F --> F1["代價：播報仲裁要跨裝置<br/>會重新引入蓋台問題"]

    style A fill:#ffcdd2
    style C1 fill:#fff9c4
    style E1 fill:#fff9c4
    style F1 fill:#ffcdd2
```

**先實測 B 那一格**（在眼鏡上列出 `LocationManager.getAllProviders()`），
再決定 A 或 B。**決策之前不要開工。**

### 6.2 IMU 能補什麼、補不了什麼

| IMU 能給（已完成） | IMU 給不了 |
|---|---|
| 相對轉向 `HeadingGuidance` | 我在哪裡 |
| 走了幾步 `StepDistanceEstimator` | 目的地在哪個方向 |
| 有沒有在走 `WalkingStateDebouncer` | 有沒有偏離路線 |

**IMU 可以做「跟著走」，做不到「知道在哪」。**

### 6.3 假設架構決策完成後的流程

```mermaid
stateDiagram-v2
    [*] --> 規劃中
    規劃中 --> 步行往站牌: Directions API<br/>優先選單一路線站牌
    步行往站牌 --> 站牌等車: 到達站牌
    步行往站牌 --> 重新規劃: 偏離 >30m 持續 10s
    站牌等車 --> 準備上車: TDX 顯示 <1 分鐘
    準備上車 --> 人工確認: 提示音 +<br/>「307 即將進站，請向司機確認」
    人工確認 --> 車上: 使用者按鍵或說「上車了」
    人工確認 --> 站牌等車: 超時
    車上 --> 準備下車: 剩 2 站
    準備下車 --> 步行往目的地: 確認已下車
    準備下車 --> 錯過站: 已過站
    錯過站 --> 重新規劃
    步行往目的地 --> 抵達: 距離 <20m
    抵達 --> [*]

    note right of 人工確認
        MVP：不自動辨識車號
        視障者看不到車頭號碼
        這是過渡方案不是完成功能
    end note
```

### 6.4 實作順序建議

| 階段 | 內容 | 前提 |
|---|---|---|
| **6a** | 純 IMU 的「跟著走」—— 使用者自己知道方向，系統負責提醒偏離與計步 | 無，現在就能做 |
| **6b** | 步行導航（Directions API + 定位） | 架構決策 |
| **6c** | 公車整合（TDX） | 6b 先驗證播報體驗 |

**6a 值得先做** —— 它不需要 GPS，價值也真實：使用者請人指路之後，
系統可以幫他維持方向、告訴他走了幾步。

### 6.5 已知難題

**「哪一輛公車進站」目前沒有可靠解法。** 團隊 MVP 是詢問司機或挑單一路線
站牌。系統仍能提供到站倒數、進站提醒、上下車確認、站數倒數。

一個值得做的：**路線規劃時優先選停靠路線少的站牌** —— 把人工妥協變成產品特性。

**Google Directions 的 transit 模式不回傳即時到站時間**，必須另接 TDX。

**都市 GPS 精度**：台北高樓區誤差 15–30m，「偏離 30m 重新規劃」的閾值
需實地調校，否則會不停誤報 —— 對視障者是災難。

---

## 7. 翻譯（規劃中，無阻塞）

最簡單的一個，約三天。**可以立刻開工。**

```mermaid
flowchart LR
    A["「把這句翻成英文」"] --> B["LLM 抽參數<br/>text + target_language"]
    B --> C{"ML Kit 語言包<br/>已下載？"}
    C -->|是| D["MlKitTranslator<br/>離線、免費"]
    C -->|否| E["下載語言包<br/>播報「正在準備翻譯」"]
    E --> D
    D --> F["AnnouncementManager<br/>USER_RESPONSE"]

    G["「翻譯這個」<br/>+ OCR"] -.未來.-> H["ai-ocr 取得文字"]
    H -.-> D

    style D fill:#c8e6c9
```

### 實作步驟

**Step 1** — domain 介面

```kotlin
// core-domain/.../translate/Translator.kt
interface Translator {
    val isAvailable: Boolean
    suspend fun ensureModel(targetLanguage: String): AppResult<Unit>
    suspend fun translate(text: String, targetLanguage: String): AppResult<String>
}
```

**Step 2** — `ai/ai-translate` 模組，用
`com.google.mlkit:translate`（離線，語言包首次使用時下載）

**Step 3** — `AssistantIntent.TRANSLATE` 已存在，把 ViewModel 那個
「開發中」分支換掉

### 兩個要注意的地方

**語言包下載要有回饋。** 首次翻譯某語言時要下載幾十 MB，
不播報「正在準備翻譯」的話使用者會以為當機了。

**目標語言的抽取交給 LLM。** 「翻成英文」「translate to Japanese」
「這個日文是什麼意思」都要對應到正確的語言代碼，本地規則做不可靠。
因此**沒有 BFF 時翻譯只能用預設語言**（建議英文）。

---

## 8. 交叉整合的機會

功能之間可以互相加值，這些都還沒做：

```mermaid
graph LR
    OCR["OCR"] -->|翻譯看到的文字| TR["翻譯"]
    OCR -->|辨識公車車頭號碼| NAV["導航"]
    FACE["人臉"] -->|「你認識的人在附近」| OBS["障礙物"]
    IMU["IMU"] -->|走路才開相機| OBS
    IMU -->|轉向修正方位描述| OBS
    IMU -->|轉向修正方位描述| FACE
    OBS -->|偵測到斑馬線| NAV

    style IMU fill:#c8e6c9
```

**已完成的只有 IMU → 相機模式那一條**（`CameraModeController`）。

其中 **IMU → 方位修正**值得早點做：使用者轉頭之後，「右前方」指的方向就變了。
現在的 `BearingResolver` 只看畫面中的 x 座標，沒有考慮頭部姿態。

---

## 9. 開發時的檢查清單

每完成一個功能，確認這些：

- [ ] `core-domain` 沒有 import `android.*`（不會編譯過，但要有意識）
- [ ] UseCase 的時鐘 / 隨機數是注入的，不是直接呼叫 `System.currentTimeMillis()`
- [ ] `Outcome` 有 `spoken` 屬性，播報內容被測試涵蓋
- [ ] 錯誤訊息是人話，不含錯誤碼、例外訊息、英文技術術語
- [ ] 能力缺失時**明確播報**而不是靜默（對看不見畫面的人，沒聲音等於當機）
- [ ] 播報優先級選對，長內容記得 `resumable = true`
- [ ] 有 `dedupeKey`，避免同一件事被播報十次
- [ ] 新片語不會被 `LocalCommandMatcher` 既有規則吃掉
- [ ] `./gradlew build` 通過（含 lint 與全部測試）
- [ ] 只修改了 `guide-glasses/`
