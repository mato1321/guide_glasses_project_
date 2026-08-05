# 第四部：新架構設計 + Edge/Cloud 配置 + 系統流程圖

---

## 1. Edge vs Cloud 配置決策

### 1.1 三層配置總表

| 功能 | 眼鏡 | 手機 | 雲端 | 理由 |
|---|---|---|---|---|
| 相機拍攝 | ✅ | (fallback) | — | 唯一的第一人稱視角 |
| 麥克風收音 | ✅ | (fallback) | — | 靠近嘴部，收音品質好 |
| 語音播報輸出 | ✅ | (fallback) | — | 不佔用耳朵（骨傳導 / 開放式） |
| HUD 顯示 | ✅ 低優先 | — | — | 視障者無用，僅供陪同者 |
| **障礙物偵測** | ❌ | ✅ **必須** | ❌ | 延遲 <300ms；斷網不能失效 |
| **人臉辨識** | ❌ | ✅ **必須** | ❌ | 生物特徵不得離開裝置 |
| **ASR** | ❌ | ✅ | 🟡 fallback | 本機免費、低延遲 |
| **TTS** | ❌ | ✅ | 🟡 高品質時 | 本機 ~50ms |
| **OCR** | ❌ | ✅ 90% | 🟡 10% | ML Kit 離線可用 |
| **翻譯** | ❌ | ✅ ML Kit | 🟡 長句 | 離線可用 |
| **意圖理解 (LLM)** | ❌ | 🟡 本地快捷指令 | ✅ **主要** | 手機跑不動夠好的 LLM |
| **路線規劃** | ❌ | 🟡 快取 | ✅ **必須** | 需要即時路網資料 |
| **即時公車** | ❌ | ❌ | ✅ **必須** | TDX 資料只在雲端 |
| **人臉資料庫** | ❌ | ✅ **必須** | ❌ | 隱私 |
| 使用者偏好 / 常用地點 | ❌ | ✅ | 🟡 選擇性同步 | — |

### 1.2 為什麼「不要全部 Cloud」（你的直覺是對的）

| 面向 | 全 Cloud 的問題 | Edge 優先的結果 |
|---|---|---|
| **延遲** | 影像上傳 (200-500ms) + 推論 (100ms) + 下載 (50ms) = **350–650ms**。障礙物警示需要 <300ms | 手機端 YOLO26-n **30–60ms** |
| **離線** | 地下道、電梯、山區、國外漫遊 → **系統完全失效**。對導盲產品這是安全問題 | 核心安全功能永遠可用 |
| **成本** | 連續 5fps 影像上傳 × 8 小時/天 = 每人每月數十美元。**規模化後無法承擔** | 邊緣運算成本 = 0 |
| **隱私** | 使用者一整天的第一人稱影像上傳到伺服器 —— 包含所有路人的臉、住家、醫院診間 | 影像不離開裝置 |
| **行動網路** | 上傳頻寬受限、費用高、走在路上訊號不穩 | 大幅減少流量 |
| **耗電** | 持續 4G/5G 上傳非常耗電 | 本地推論 + NPU 更省電 |

### 1.3 資源預算（手機端）

| 資源 | 預算 | 說明 |
|---|---|---|
| **RAM** | < 500 MB | YOLO26-n INT8 ~10MB + MobileFaceNet ~5MB + ML Kit ~30MB + App |
| **CPU/NPU** | 障礙物 5fps → NPU 佔用 ~25% | 透過 NNAPI/GPU delegate |
| **耗電** | 目標：手機 <15%/小時 | 需實測。**用「事件驅動」而非「常時偵測」大幅降低** |
| **儲存** | < 200 MB | 模型 + 離線地圖快取 + 人臉庫 |
| **流量** | < 50 MB/天 | 只有 LLM 文字 + 導航 API + 少量 OCR |

### 1.4 🔴 耗電是真正的產品限制

- **眼鏡：210 mAh / 4 小時**（不開相機）。持續拍照傳輸下**可能 <1.5 小時**
- **手機：** 持續 NPU 推論 + GPS + 藍牙 + 螢幕關閉，樂觀估計 6–8 小時

**必須設計「省電模式」：**

| 模式 | 相機 | 觸發 |
|---|---|---|
| **待命** | 關閉 | 預設。只等語音指令與 AI 鍵 |
| **查詢** | 單張拍照 | 使用者問「前面有什麼」 |
| **行進** | 2–5 fps | 使用者說「開始走路模式」或導航中 |
| **省電** | 1 fps + 只偵測人/車 | 電量 <20% 自動切換 |

**絕不要預設常時 30fps 偵測。**

---

## 2. Cloud 供應商比較

### 2.1 你列的選項評估

| 供應商 | 成本 | 延遲（台灣） | 可靠性 | 可維護性 | 適合本專案 |
|---|---|---|---|---|---|
| **Firebase**（Auth + Firestore + Functions） | 免費額度大 | 中（asia-east1 台灣機房） | 高 | **極高**（Android SDK 原生整合） | ⭐ **推薦：Auth + 遠端設定** |
| **Cloud Run** | 用多少付多少，可縮到 0 | **低**（asia-east1） | 高 | 高（容器化） | ⭐ **推薦：主要 API Gateway** |
| Cloud Functions | 冷啟動較慢 | 中 | 高 | 高 | 🟡 輕量事件用 |
| Google Cloud（Maps / Vision / TTS） | 依用量 | 低 | 極高 | 高 | ⭐ **必用**（Maps 無替代品） |
| **Supabase** | 免費額度佳 | **中高**（最近區域為新加坡） | 中高 | 高（Postgres 直覺） | 🟡 適合快速原型 |
| Azure | 中 | 中 | 高 | 中 | 🟡 已用 Google 生態則無必要 |
| AWS | 中 | 低（ap-northeast-1） | 極高 | 中（設定複雜） | 🟡 團隊已熟悉才選 |

### 2.2 建議組合

```mermaid
graph TB
    subgraph "手機 App"
        A["GuideGlasses App"]
    end

    subgraph "Google Cloud - asia-east1 台灣"
        B["Cloud Run<br/>BFF / API Gateway<br/>金鑰保管 + 節流 + 快取"]
        C["Firebase Auth<br/>使用者身分"]
        D["Firestore<br/>偏好 / 常用地點<br/>（不存人臉）"]
        E["Cloud Vision<br/>OCR fallback"]
        F["Maps Platform<br/>Directions / Places"]
    end

    subgraph "第三方"
        G["Anthropic API<br/>Claude Haiku 4.5 / Sonnet 5"]
        H["TDX 交通部<br/>即時公車"]
    end

    A -->|HTTPS + Firebase ID Token| B
    A --> C
    B --> D
    B --> E
    B --> F
    B --> G
    B --> H

    style B fill:#c8e6c9
```

**為什麼要一個 BFF（Backend For Frontend）而不是 App 直連各家 API：**

1. 🔴 **金鑰安全** —— 目前 `.env` 已經外洩過一次。App 內嵌 API Key 是**必然被反編譯取出**的。所有金鑰只能放在後端。
2. **節流與配額** —— Google Maps 每次呼叫都要錢，需要在後端控管
3. **快取** —— 同一路線、同一站牌的查詢可共用快取，大幅降低成本
4. **供應商可替換** —— 換 LLM 或換 OCR 供應商不需要發新版 App
5. **可觀測性** —— 集中記錄、追蹤成本

**選 Cloud Run 而非 Cloud Functions 的理由：** Cloud Run 可以維持常駐執行個體（避免冷啟動），支援 gRPC/串流，且同一個容器可以本地開發測試 —— 對延遲敏感的導盲應用比較安全。可縮到 0 個執行個體，沒人用時不花錢。

**明確不建議：** 目前這種「跑在開發者筆電上的 FastAPI + 區網 IP」的模式，**不能上正式產品**。

---

## 3. 新架構：Clean Architecture + MVVM

### 3.1 為什麼選這個組合

| 決策 | 理由 |
|---|---|
| **Clean Architecture** | 你有 6 個功能、多個資料來源（眼鏡 SDK / 手機感測器 / 雲端 API / 本地 DB）、且 SDK 有不確定性（第二部 §3.3 的待驗證項目）。**分層讓你可以在不知道 `takeGlassPhoto()` 到底多快之前，就先把 domain 層寫完**，之後換資料來源不影響上層 |
| **MVVM** | Android 官方推薦，`ViewModel` 撐過設定變更（旋轉、深色模式），與 `StateFlow` 搭配自然 |
| **Repository Pattern** | 障礙物偵測要能在「眼鏡相機」與「手機相機」間切換，OCR 要能在「ML Kit」與「Cloud Vision」間切換 —— 這正是 Repository 存在的意義 |
| **Hilt（DI）** | 沒有 DI 就無法把 SDK 換成假物件測試。**眼鏡不在手邊時要能開發**，這是務實的必要條件 |
| **多 module Gradle** | 5 個獨立專案 → 1 個專案多模組。編譯加速、強制邊界、功能可獨立開發 |

### 3.2 模組拆分

```
guide-glasses/                          ← 單一 Gradle 專案
├── app/                                ← 組裝層：Application, MainActivity, Hilt Modules, Navigation
│
├── core/
│   ├── core-common/                    ← Result, DispatcherProvider, 擴充函式
│   ├── core-domain/                    ← 【純 Kotlin】跨功能的 Entity 與 UseCase 介面
│   ├── core-data/                      ← Repository 實作基底、DataStore
│   ├── core-database/                  ← Room + 向量索引
│   ├── core-network/                   ← Retrofit / OkHttp / 攔截器
│   ├── core-ui/                        ← 無障礙元件、放大字體主題、Compose 基礎
│   └── core-testing/                   ← 測試工具
│
├── glasses/                            ← 【隔離 Rokid SDK 的關鍵層】
│   ├── glasses-api/                    ← 純介面：GlassesGateway, CameraSource, AudioSink…
│   ├── glasses-cxr/                    ← CXR-M SDK 實作（唯一 import com.rokid.cxr 的地方）
│   └── glasses-fallback/               ← 手機 CameraX / 手機 TTS 實作（無眼鏡時可開發、可 demo）
│
├── ai/
│   ├── ai-vision/                      ← YOLO26 TFLite, MediaPipe, 距離估計
│   ├── ai-face/                        ← MobileFaceNet, 向量比對
│   ├── ai-ocr/                         ← ML Kit + Cloud fallback
│   ├── ai-speech/                      ← SpeechRecognizer, TextToSpeech, ML Kit Translate
│   └── ai-agent/                       ← LLM Function Calling, TaskRouter, ToolRegistry
│
├── feature/
│   ├── feature-assistant/              ← 功能三：AI 助理（主入口）
│   ├── feature-obstacle/               ← 功能一
│   ├── feature-face/                   ← 功能二
│   ├── feature-navigation/             ← 功能四
│   ├── feature-translate/              ← 功能五
│   └── feature-ocr/                    ← 功能六
│
└── announce/                           ← 【全域播報仲裁】
    └── AnnouncementManager             ← P0-P3 優先級、打斷、佇列、音訊焦點
```

### 3.3 為什麼要有 `glasses-api` / `glasses-cxr` / `glasses-fallback` 三層

這是**針對本專案最大風險的直接對策**。

第二部 §3.3 列出六個「待驗證」的 SDK 能力。如果把 `CxrApi` 直接寫進 feature 層，一旦驗證結果是「連續影像串流做不到」，你要改的地方會遍布整個專案。

有了這層抽象：

```kotlin
// glasses-api（純介面，無任何 Rokid 依賴）
interface FrameSource {
    fun frames(config: CaptureConfig): Flow<CameraFrame>
    suspend fun captureOnce(): CameraFrame
    val capability: FrameCapability   // 回報實際能力：單張? 串流? 最高 fps?
}

// glasses-cxr（實作 A：真眼鏡）
class CxrFrameSource @Inject constructor(...) : FrameSource { ... }

// glasses-fallback（實作 B：手機相機，眼鏡不在手邊時用）
class PhoneCameraFrameSource @Inject constructor(...) : FrameSource { ... }
```

**好處：**
1. 眼鏡還沒到／借給同學了 → 用 `glasses-fallback` 照常開發
2. `takeGlassPhoto()` 實測太慢 → 只改 `glasses-cxr`，上層零改動
3. `FrameCapability` 讓上層**動態降級**：串流可用就跑 5fps 行進模式，只有單張就自動切成查詢模式
4. 未來換成 Glass 3 企業版 → 新增一個 `glasses-sprite` 模組即可

### 3.4 資料流原則

```
UI (Compose/View)
   ↕ StateFlow / Event
ViewModel
   ↕ UseCase（suspend fun / Flow）
Domain（純 Kotlin，無 Android 依賴）
   ↕ Repository 介面
Data（Repository 實作）
   ↕
DataSource（Local: Room/TFLite ／ Remote: Retrofit ／ Glasses: CXR）
```

**鐵則：**
- Domain 層**不得** import `android.*` 或 `com.rokid.*`
- DTO 只存在於 data 層，跨層一律用 domain model（解決現況「DTO 洩漏到 UI」的問題）
- ViewModel 只依賴 UseCase，不直接碰 Repository
- 所有錯誤用 `Result<T>` 包裝，不讓 exception 穿透層級

---

## 4. 系統流程圖

### 4.1 整體架構圖

```mermaid
graph TB
    subgraph GL["🕶️ Rokid Glasses"]
        GLC["12MP 相機"]
        GLM["麥克風"]
        GLS["喇叭"]
        GLK["AI 實體鍵"]
        GLD["Micro-LED HUD"]
    end

    subgraph APP["📱 Android App（Clean Architecture）"]
        direction TB
        subgraph L1["Presentation 層"]
            VM["ViewModels + Compose UI<br/>無障礙優先"]
        end
        subgraph L2["Domain 層（純 Kotlin）"]
            UC["UseCases<br/>DetectObstacle / IdentifyPerson<br/>Navigate / ReadText / Translate"]
            ENT["Entities"]
        end
        subgraph L3["Data 層"]
            REPO["Repositories"]
        end
        subgraph L4["基礎設施"]
            GW["glasses-api<br/>FrameSource / AudioSink"]
            AI["ai-vision / ai-face<br/>ai-ocr / ai-speech"]
            AG["ai-agent<br/>TaskRouter"]
            DB["Room + 向量索引"]
            NET["core-network"]
        end
        ANN["🔊 AnnouncementManager<br/>P0-P3 優先級仲裁"]
    end

    subgraph CX["CXR-M SDK"]
        CXR["CxrApi"]
    end

    subgraph CLOUD["☁️ 雲端"]
        BFF["Cloud Run BFF"]
        LLM["Claude API"]
        MAPS["Google Maps"]
        TDX["TDX 公車"]
        CV["Cloud Vision"]
    end

    GLC --> CXR
    GLM --> CXR
    GLK --> CXR
    CXR --> GLS
    CXR --> GLD
    CXR <--> GW

    GW --> AI
    AI --> REPO
    AG --> UC
    REPO --> UC
    UC --> VM
    UC --> ANN
    ANN --> GW
    DB <--> REPO
    NET <--> REPO

    NET <--> BFF
    BFF --> LLM
    BFF --> MAPS
    BFF --> TDX
    BFF --> CV

    style GL fill:#e8f5e9
    style APP fill:#e3f2fd
    style CLOUD fill:#fff3e0
    style ANN fill:#ffcdd2
```

### 4.2 資料流程圖

```mermaid
sequenceDiagram
    participant U as 使用者
    participant G as 眼鏡
    participant GW as glasses-api
    participant AI as ai-* 模組
    participant UC as UseCase
    participant VM as ViewModel
    participant AN as AnnouncementManager

    U->>G: 按 AI 鍵 / 說話
    G->>GW: onAiKeyDown()
    GW->>VM: GlassesEvent.AiKeyDown
    VM->>UC: StartListeningUseCase
    UC->>AI: SpeechRecognizer 開始
    AI-->>UC: 部分結果（串流）
    UC-->>VM: StateFlow 更新
    VM-->>GW: sendAsrContent（眼鏡顯示）

    AI->>UC: 最終文字
    UC->>AI: ai-agent 意圖解析
    AI-->>UC: ToolCall（含參數）
    UC->>UC: TaskRouter 分派到對應 UseCase
    UC->>AI: 執行（YOLO / FaceNet / OCR…）
    AI-->>UC: 結果
    UC->>AN: announce(P1, "右前方三公尺是王老師")
    AN->>AN: 檢查優先級 & 音訊焦點
    AN->>GW: TTS 播報
    GW->>G: sendTtsContent()
    G->>U: 語音輸出
```

### 4.3 AI 助理（意圖路由）流程

```mermaid
flowchart TD
    A["語音輸入"] --> B["Android SpeechRecognizer<br/>串流辨識"]
    B --> C{"本地快捷指令<br/>比對"}
    C -->|"停 / 重複 / 前面有什麼<br/>這是誰 / 唸給我聽"| D["直接執行<br/>⚡ <100ms"]
    C -->|未命中| E["組裝 LLM 請求<br/>+ Tool Schemas + 對話歷史"]
    E --> F{"有網路？"}
    F -->|否| G["降級：本地擴充比對<br/>失敗則告知需要網路"]
    F -->|是| H["Claude Haiku 4.5<br/>Function Calling"]
    H --> I{"回傳型態"}
    I -->|tool_use| J["TaskRouter"]
    I -->|text| K["一般對話回覆"]
    J --> L1["detect_obstacles"]
    J --> L2["identify_person"]
    J --> L3["navigate_to"]
    J --> L4["read_text"]
    J --> L5["translate"]
    J --> L6["register_face"]
    D --> M["AnnouncementManager"]
    L1 --> M
    L2 --> M
    L3 --> M
    L4 --> M
    L5 --> M
    L6 --> M
    K --> M
    G --> M
    M --> N["Android TTS / sendTtsContent"]

    style D fill:#c8e6c9
    style H fill:#fff3e0
    style M fill:#ffcdd2
```

### 4.4 導航流程

```mermaid
flowchart TD
    A["「帶我去台北101」"] --> B["LLM 抽取目的地"]
    B --> C["Places API 解析座標"]
    C --> D["Directions API<br/>mode=transit"]
    D --> E["建立 RoutePlan<br/>步行段 + 公車段 + 步行段"]
    E --> F["啟動 NavigationForegroundService"]

    F --> G["步行段"]
    G --> H{"FusedLocation 監測"}
    H -->|接近轉彎 30m| I["「前方 30 公尺右轉」"]
    H -->|偏離 >30m 持續 10s| J["「偏離路線，重新規劃」"]
    J --> D
    H -->|抵達站牌| K["公車段"]

    K --> L["每 20 秒輪詢 TDX"]
    L --> M{"到站時間"}
    M -->|">3 分鐘"| N["「307 還有 5 分鐘」"]
    N --> L
    M -->|"<1 分鐘"| O["「307 即將進站，請準備」+ 提示音"]
    O --> P{"確認上車"}
    P -->|"OCR 辨識車頭號碼<br/>或使用者按鍵確認"| Q["車上段"]
    P -->|超時| L

    Q --> R["GPS 比對站點序列"]
    R --> S{"剩幾站"}
    S -->|2 站| T["「還有 2 站」"]
    S -->|1 站| U["「下一站下車，請按鈴」+ 提示音"]
    S -->|已過站| V["「已過站，重新規劃」"]
    V --> D
    U --> W["確認下車"]
    W --> X["最後步行段"]
    X --> Y["「已抵達，目的地在正前方」"]

    style O fill:#fff3e0
    style U fill:#fff3e0
    style J fill:#ffcdd2
    style V fill:#ffcdd2
```

### 4.5 OCR 流程

```mermaid
flowchart TD
    A["「唸給我聽」"] --> B["openGlassCamera + setPhotoParams<br/>1920x1080"]
    B --> C["takeGlassPhoto"]
    C --> D{"影像品質檢查<br/>模糊 / 過暗"}
    D -->|不合格| E["「光線不足，請靠近一點」<br/>重拍"]
    E --> C
    D -->|合格| F["ML Kit Text Recognition v2<br/>中文，離線 ~100ms"]
    F --> G{"信心度 & 字數"}
    G -->|良好| H["文字結果"]
    G -->|"偏低 + 有網路"| I["Cloud Vision<br/>document_text_detection"]
    I --> J{"仍不佳"}
    J -->|是| K["Gemini / Claude Vision<br/>理解式辨識"]
    J -->|否| H
    K --> H
    G -->|"偏低 + 無網路"| L["「辨識不清楚，請調整角度」"]

    H --> M{"OCR 模式"}
    M -->|文件| N["splitTextForSpeech<br/>依標點斷句，80 字一段"]
    M -->|招牌| O["只取最大字級"]
    M -->|標籤/藥袋| P["LLM 抽重點<br/>藥名/劑量/用法"]
    N --> Q["分段朗讀<br/>支援 暫停/重播/上下段/加速"]
    O --> Q
    P --> Q

    style F fill:#c8e6c9
    style Q fill:#e3f2fd
```

### 4.6 障礙物偵測流程

```mermaid
flowchart TD
    A{"觸發來源"} -->|"「前面有什麼」"| B["查詢模式<br/>單張"]
    A -->|"行進模式 / 導航中"| C["連續模式<br/>2-5 fps"]
    A -->|"電量 <20%"| D["省電模式<br/>1 fps，只偵測人/車"]

    B --> E["取得影像"]
    C --> E
    D --> E
    E --> F{"FrameCapability<br/>眼鏡能力偵測"}
    F -->|支援串流| G["CXR 串流"]
    F -->|僅單張| H["takeGlassPhoto 輪詢"]
    F -->|眼鏡未連線| I["手機 CameraX fallback"]

    G --> J["YOLO26-n INT8<br/>NNAPI/GPU，~40ms"]
    H --> J
    I --> J

    J --> K["偵測結果 bbox + class"]
    K --> L["距離估計<br/>已知尺寸反推 + 地平面假設"]
    K --> M["方位判定<br/>bbox 中心 x → 左/中/右"]
    L --> N["危險分級"]
    M --> N

    N --> O{"危險等級"}
    O -->|"P0：<2m 車輛/落差"| P["立即打斷一切<br/>提示音 + 「停！右方有車」"]
    O -->|"P2：2-5m 障礙"| Q["「前方三公尺有電線桿」"]
    O -->|"P3：>5m / 導引資訊"| R["「導盲磚在左側」"]
    O -->|無變化| S["不播報<br/>避免疲勞轟炸"]

    P --> T["AnnouncementManager"]
    Q --> T
    R --> T
    T --> U["TTS 輸出"]

    style P fill:#ffcdd2
    style S fill:#e0e0e0
    style J fill:#c8e6c9
```

### 4.7 人臉辨識流程

```mermaid
flowchart TD
    A["「這是誰」 或 行進模式自動"] --> B["取得影像"]
    B --> C["MediaPipe Face Detector<br/>~5ms"]
    C --> D{"偵測到人臉？"}
    D -->|否| E["「前方沒有偵測到人」"]
    D -->|是| F["人臉對齊 + 裁切<br/>使用 6 個關鍵點"]
    F --> G["MobileFaceNet TFLite<br/>512 維 embedding，~15ms"]
    G --> H["本地向量比對<br/>Room 記憶體 或 ObjectBox HNSW"]
    H --> I{"cosine 相似度"}
    I -->|"≥ 0.6 高信心"| J["已知人物"]
    I -->|"0.45-0.6 中信心"| K["「可能是王老師，不太確定」"]
    I -->|"< 0.45"| L["未知人物"]

    J --> M["方位判定<br/>bbox 中心 x"]
    M --> N["距離估計<br/>臉寬反推"]
    N --> O{"播報冷卻<br/>同一人 10 秒內"}
    O -->|冷卻中| P["不播報"]
    O -->|可播報| Q["「右前方三公尺，是王老師」"]

    L --> R{"使用者要註冊？"}
    R -->|"「把他記起來，叫小明」"| S["取得當事人同意"]
    S --> T["存入本地加密資料庫"]
    R -->|否| U["「前方有一位不認識的人」"]

    Q --> V["AnnouncementManager P1"]
    K --> V
    U --> V
    E --> V

    W["🔒 影像用完立即丟棄<br/>絕不落地、絕不上傳"]

    style W fill:#ffcdd2
    style H fill:#c8e6c9
    style G fill:#c8e6c9
```

→ 續讀 [`05_ROADMAP_AND_FEASIBILITY.md`](05_ROADMAP_AND_FEASIBILITY.md)
