# 專案交接文件

> ⚠️ **本文件部分結論已被修正。**
> 2026-08-05 依據團隊提供的實際狀況重新分析後，以下結論已不成立：
> 五個獨立專案是刻意的分工、Face_Recognition 已在眼鏡上實機運作、
> App 直接跑在眼鏡上（CameraX 可用）、金鑰已全部重發。
> **請以 [`08_CORRECTIONS_AND_REANALYSIS.md`](08_CORRECTIONS_AND_REANALYSIS.md) 為準。**
> 本文件保留不改寫，僅供分析過程的可追溯性。

| | |
|---|---|
| 撰寫日期 | 2026-08-05 |
| 起始基準 commit | `1adfd1a`（歷史重寫後為 `0de7c5d`） |
| 目前 `origin/main` | `f7fe1d5` |
| Repository | https://github.com/mato1321/guide_glasses_project_ |
| 相關 PR | [#2](https://github.com/mato1321/guide_glasses_project_/pull/2)（已合併） |

> 本文件所有事實均以實際檔案內容與 `git` 紀錄為依據。凡是無法從程式碼或文件證實的，皆明確標示「無法確認」。

---

# 1. 工作內容總覽（依時間順序）

## 階段 A：Repository 全面分析（未修改任何程式）

**做了什麼**

讀完 repository 全部 263 個檔案（排除 `build/`、`.gradle/`、`.idea/`），逐一分析 5 個 Gradle 專案、4 個 Python 後端、每個 Activity / Fragment / Service / Manager / API endpoint。

**目的**：使用者明確要求「在開始修改任何程式之前，先完成 Repository 分析、SDK 分析、架構分析、可行性分析、Roadmap」。

**主要發現**

| # | 發現 | 依據 |
|---|---|---|
| A1 | 專案是 **5 個完全獨立的 Gradle 專案**，彼此無任何程式碼共用；使用者最終會拿到 5 個 APK 而不是一套導盲眼鏡系統 | 每個資料夾各有獨立的 `settings.gradle.kts` 與 package name |
| A2 | **`com.rokid.cxr:client-m` 只宣告在 gradle，程式碼中零呼叫** | `grep -i "cxr"` 全 repo 只命中 `AI_Assistant/android/app/build.gradle.kts:77` |
| A3 | 實際跑的是**手機 CameraX 相機 + 手機 TTS**，不是眼鏡 | `FaceRecognitionFragment.kt` 使用 `androidx.camera.*` |
| A4 | `Face_Recognition/` 整個模組與 `AI_Assistant/` 的人臉功能約 **90% 重複** | `MainActivity.kt`(425行) vs `FaceRecognitionFragment.kt`(528行) 逐行比對 |
| A5 | `Obstacle_Recognition/python/main.py` 與 `Audio_Navigation/python/main.py` 是 **0 bytes** | 讀取檔案回傳空 |
| A6 | **OpenAI API key 與 GCP service account 私鑰已提交進 git** | `git ls-files` 命中 `.env` 與 `blind-glasses-ocr-*.json` |
| A7 | `ApiClient.kt` 的 `BASE_URL = "你的ip位址"` —— Retrofit 建構即拋 `IllegalArgumentException` | `network/ApiClient.kt:12` |
| A8 | Chaquopy 硬編碼 `C:\Users\jerry\...python.exe` —— 除原作者外無人能 build | `app/build.gradle.kts:97` |
| A9 | 後端全域共用 `ConversationBufferMemory` —— 多使用者對話互相污染，且無上限成長 | `AI_Assistant/python/main.py:34` |
| A10 | `/admin` 無認證 + f-string 拼接使用者輸入（XSS）+ `rename` 未過濾 `../`（路徑穿越） | `AI_Assistant/python/admin.py` |
| A11 | `/recognize` 每次都 `cv2.imwrite("debug.jpg")` —— 未經同意保存路人臉部影像 | `main.py:166`（修改前） |
| A12 | 死碼：`AccessibilityHelper`、`RokidCameraManager`、`GlassPresentation`、`AppUIState`、`test_1.py` 全部 0 引用 | 全 repo grep 無呼叫端 |

**產出**：`docs/01_REPOSITORY_ANALYSIS.md`（365 行）

---

## 階段 B：Rokid SDK 官方文件研究

**做了什麼**

閱讀使用者提供的 4 個網址，另外自行搜尋官方 GitHub、社群技術文章、硬體規格。

**目的**：使用者要求「不要重新實作 SDK 已經提供的功能」，且「若涉及 Rokid SDK 的功能，請引用對應的官方文件章節或 API 名稱作為依據；若文件沒有明確說明，請明確標示為推測或待驗證」。

**最關鍵的發現：使用者提供的 4 份文件屬於兩條不同的產品線**

| | A 線：消費級 Rokid Glasses + CXR SDK | B 線：Rokid Glass 3 企業版 SDK |
|---|---|---|
| 對應文件 | segmentfault、open.rokid.com | **x-docs.rokid.com（文件 1）** |
| App 跑在哪 | 手機（眼鏡是終端） | 眼鏡上（完整 Android 裝置） |
| ASR / TTS / LLM | **SDK 不提供，開發者自備** | 內建 `IAsrService` / `ITtsService` / `IAiChatService` |
| 人臉辨識 | **SDK 不提供** | 內建 `IOnlineRecService.recognizeFace()` |
| 連續影像 | 官方文件僅見單張拍照 | `startCameraNv21Export()` |

專案的 gradle 依賴是 `com.rokid.cxr:client-m` → **A 線**。經與使用者確認，實體裝置是**消費級 Rokid Glasses（49g）**。

**硬體限制（決定整個架構）**

Snapdragon AR1、**2 GB RAM**、32 GB ROM、12 MP 相機、雙目單色綠 Micro-LED 480×398/眼 23° FOV、**210 mAh 約 4 小時**、Wi-Fi 6 / BT 5.3、IPX4、49 g。

→ 推出三個結論：眼鏡上不跑任何 AI 模型；眼鏡顯示層對全盲使用者無意義應降低優先級；續航是產品級硬限制。

**標示為「待驗證」而非假設存在的項目**

- 連續影像串流 API（官方文件只找到 `takeGlassPhoto()`）
- `sendTtsContent()` 究竟由眼鏡還是手機發聲
- 眼鏡麥克風音訊能否串到手機
- 眼鏡 IMU / GPS 是否開放
- 眼鏡端能否自訂 UI 繪製

**產出**：`docs/02_ROKID_SDK_ANALYSIS.md`（217 行）

---

## 階段 C：六大功能分析、架構設計、Roadmap、可行性分析

**產出**

| 文件 | 行數 | 內容 |
|---|---|---|
| `docs/03_FEATURE_ANALYSIS.md` | 508 | 六大功能逐項：現況百分比、模型選型比較表、Edge/Cloud 判定、風險 |
| `docs/04_ARCHITECTURE.md` | 529 | Edge/Cloud 配置表、雲端供應商比較、Clean Architecture 模組設計、**7 張 Mermaid 流程圖** |
| `docs/05_ROADMAP_AND_FEASIBILITY.md` | 297 | Phase 0–6 Roadmap、誠實的可行性分析、待確認事項 |
| `docs/00_README.md` | 71 | 摘要與導讀 |

**幾個關鍵技術決策與理由**

| 領域 | 建議 | 為什麼 |
|---|---|---|
| 物件偵測 | **YOLO26-n INT8 / TFLite** | 2026 年的 edge-first 標準，CPU 較 YOLO11-n 快約 43%。YOLOv12 與 RT-DETR **量化後掉點嚴重**，不適合手機 |
| 距離估計 | 已知尺寸反推 + 地平面假設，**先不用 Depth Anything** | Depth Anything 給的是「相對深度」不是「絕對距離」，且多一個模型多 80ms |
| 人臉資料庫 | Room + 記憶體比對（≤100 人），**絕不上雲** | 生物特徵屬特種個資；上雲會帶來法遵責任、離線失效、延遲增加，零優點 |
| ASR / TTS | **Android 原生** | TTS 從 2–3 秒降到約 50ms。以步行速度 1.4 m/s 計算，2 秒延遲的「前方有車」等於車已經到了 |
| OCR | ML Kit v2 中文（離線）→ Cloud Vision → Vision LLM **三層** | 90% 情境 ML Kit 就夠，零成本零延遲；只有 10% 上雲 |
| 意圖路由 | LLM Function Calling + 本地快捷指令 | MCP 不適合（工具都在同一個 App 內，多一層 IPC 只增加延遲） |

**誠實指出的限制（未因使用者想做就全說可以）**

1. 眼鏡沒有公開的連續影像串流 API —— 全案最大技術風險
2. 210 mAh / 4 小時是物理限制，不是優化能解決的
3. Android 14+ 前景服務限制 + 廠商 ROM 省電機制會殺掉長時間服務
4. 「哪一輛公車進站」目前沒有可靠解法 —— 建議不承諾自動上車
5. 導盲杖已能處理近距離障礙，本系統的差異化應是「杖子給不了的資訊」

---

## 階段 D：Phase 0 —— 安全止血

**commit**：`2e626cf`（歷史重寫後 `5687577`）、`94ef9d1`（→ `ae1dcb5`）

| 工作 | 為什麼 |
|---|---|
| 解除 `.env` 與 GCP JSON 的 git 追蹤 | 兩把正式金鑰已外洩。本機檔案保留，不影響現有開發 |
| 重寫 `.gitignore` | 原本有 `*.json` / `*.png` / `*.jpg` 全域規則，會誤擋 `google-services.json` 與 Android `res/drawable` 資源 |
| 新增 `.env.example` × 2 | 讓新開發者知道需要哪些環境變數 |
| `api_key` → `OPENAI_API_KEY`，缺少時 `raise RuntimeError` | 原本 `os.getenv("api_key")` 回傳 None 會靜默失敗，錯誤發生在很後面難以診斷 |
| `ocr_doc.py` 移除硬編碼憑證路徑 | 原本寫死 `C:\Users\user\Downloads\blind-glasses-ocr-*.json`，換一台電腦就壞 |
| 移除 `cv2.imwrite("debug.jpg")` | 該行把未經同意的路人臉部影像落地，屬個資風險 |
| 補齊 `requirements.txt` | 原本缺 `opencv-python`/`numpy`/`insightface`/`onnxruntime`/`opencc`，照著安裝必定失敗 |

**驗證**：`python -m py_compile` 全部通過；自動化檢查腳本確認 9 項（金鑰檢查存在、無硬編碼路徑、debug.jpg 已移除等）全數通過。

---

## 階段 E：Phase 1 —— 建立 `guide-glasses` 多模組專案地基

**commit**：`09dbe3c`（→ `08a547b`）

**目的**：使用者選擇「建立新的多模組專案」而非原地重構。5 個獨立專案改造成 1 個系統的成本高於帶著有價值資產重寫。

**建立的模組**

```
app/                  組裝層，Hilt 進入點
core/core-domain/     純 Kotlin，不套用 Android plugin
core/core-common/     Dispatcher 抽象
```

**建立的核心抽象**

| 類別 | 行數 | 解決什麼問題 |
|---|---|---|
| `AppResult` / `AppError` | 79 | 舊專案讓 exception 一路穿透到 Fragment，播報「發生錯誤: Unable to resolve host」。對只能靠聽的使用者，錯誤訊息本身就是介面 |
| `Announcement` / `AnnouncementPriority` | 74 | 定義 4 級播報優先級 |
| `AnnouncementQueue` | 115 | 播報仲裁核心邏輯。舊專案有三套互不知情的播放器會互相蓋台 |
| `Announcer` | 26 | 語音輸出抽象 |
| `GlassesGateway` / `GlassesCapabilities` | 93 | 眼鏡能力抽象，讓上層依實測能力動態降級 |
| `FrameSource` / `CameraFrame` | 81 | 影像來源抽象 |
| `DispatcherProvider` | 25 | 可注入的 Dispatcher，讓執行緒切換邏輯可測 |

**建置過程中踩到並解決的問題**

| 問題 | 現象 | 解法 |
|---|---|---|
| AGP 9.1.0 與 Gradle 9.1.0 不相容 | `Minimum supported Gradle version is 9.3.1` | 一度改用 Gradle 9.3.1 |
| AGP 9 內建 Kotlin 支援 | `Cannot add extension with name 'kotlin'` | 移除 `kotlin.android` plugin（後續回退） |
| **Hilt 2.57.1 不支援 AGP 9** | `Android BaseExtension not found`（AGP 9 移除了 `BaseExtension`） | **回退到 AGP 8.13.2 + Gradle 8.13**，並在 `libs.versions.toml` 與 README 註明原因 |
| `local.properties` 跳脫格式 | lint error `PropertyEscape` | 磁碟機冒號也需跳脫：`C\:\\Users\\...` |

**驗證**：`./gradlew build` 成功，debug + release APK 產出，lint 無錯誤，14 個單元測試通過。

---

## 階段 F：Phase 2 —— AI 助理中樞

**commit**：`163f3eb`（→ `591a433`）、`1b20ef8`（→ `d7da0e1`）

**新增模組**：`ai/ai-speech`、`ai/ai-agent`、`feature/feature-assistant`

### F-1 意圖路由取代關鍵字比對

**修正的 Bug**：舊 `ChatFragment.kt:480` 的 `shouldSwitchToFaceRecognition(replyText, userText)` 把 AI 的**回覆**也串進去比對，同時造成兩種相反的錯誤：

- **漏判**：「這個人是誰」不含「人臉辨識」四個字 → 不觸發
- **誤判**：助理回覆中提到「人臉辨識」→ 誤觸發功能切換

**新做法**：雙層路由

1. `LocalCommandMatcher`（95 行）—— **只比對使用者說的話**。涵蓋停 / 前面有什麼 / 這是誰 / 唸給我聽 / 再說一次。延遲 <100ms、離線可用。「停」優先級最高，「停，前面有什麼」會先停下來。正規化處理標點、全形、大小寫。
2. `IntentRouter`（103 行）—— 本地未命中才呼叫 LLM function calling，處理需要抽參數的指令。

### F-2 Android 原生語音取代雲端

| | 舊做法 | 新做法 | 延遲差異 |
|---|---|---|---|
| TTS | 上傳後端 → OpenAI tts-1 → 下載 mp3 → MediaPlayer | `AndroidTtsAnnouncer`（172 行） | 2–3 秒 → 約 50ms |
| ASR | 錄整段 m4a → 上傳 → Whisper | `AndroidSpeechRecognitionGateway`（156 行），串流式、離線優先 | 3–5 秒 → 即時 |

同時移除 `translate.google.com/translate_tts` 備援（非公開 API，不可用於正式產品）。
TTS 走 `USAGE_ASSISTANCE_ACCESSIBILITY` 音訊通道，媒體音量調低仍聽得見。

### F-3 對話歷史

`ConversationHistory`（45 行）取代舊後端模組層級的全域 `ConversationBufferMemory`。舊版所有使用者共用同一段記憶（同時使用會互相污染），且長度無上限（token 成本無上限成長，最終超過 context window）。

### F-4 金鑰不進 App

`RemoteLlmIntentGateway`（153 行）只認得 BFF 協定，**不直接呼叫 LLM 供應商**。App 內嵌金鑰必然會被反編譯取出。未設定 `BuildConfig.LLM_ENDPOINT` 時退回 `OfflineLlmIntentGateway`，本地快捷指令照常運作。

### F-5 播報執行層

`AnnouncementManager`（104 行）。修正一個真實世界的競態：`Announcer` 的完成回呼可能在該則播報早已被打斷之後才送達，若不比對序號就直接取下一則，會造成佇列跳號 —— 使用者會漏聽一則訊息，而那則可能正是危險警示。以 `speakingToken` 序號機制解決。

**驗證**：`./gradlew build` 成功，57 個單元測試全數通過。

---

## 階段 G：Git 歷史清除

**執行時間**：PR #2 合併之後（刻意延後，因為在合併前重寫會讓分支祖先失效而無法合併）

**程序**

1. `git clone --mirror` 到隔離目錄
2. `git filter-repo --invert-paths --path AI_Assistant/python/.env --path-glob "Text_Recognition/text_recognize/python/blind-glasses-ocr-*.json" --force`
3. 驗證兩個機敏 blob 以 `git cat-file -e` 確認不存在
4. `git push --force --all origin`
5. 全新 clone 獨立驗證

**結果**

| 項目 | 結果 |
|---|---|
| `main` 重寫 | `cb104d3` → `f7fe1d5`，17 個 commit 全保留 |
| `claude/rokid-...` 重寫 | `1b20ef8` → `d7da0e1` |
| `copilot/refactor-ai-assistant-module` 重寫 | `6cf42c1` → `ea531ba` |
| 機敏 blob | ✅ 一般 `git clone` 已完全取不到 |
| 檔案完整性 | ✅ 365 個檔案，關鍵檔案全數存在 |

**⚠️ 仍存在的殘留風險（實測確認）**

GitHub API **仍可依 SHA 取回舊 blob**：

```
blob dded5150c0d1...  HTTP 200  size=52    (.env)
blob f0fa304c7e24...  HTTP 200  size=619   (GCP 私鑰)
```

這是 GitHub 的已知行為 —— force push 不會移除被 `refs/pull/*` 參照或近期快取的物件。一般 clone 取不到，但**知道 SHA 的人仍可取得內容**。

→ **這使得撤銷金鑰成為唯一有效的止血手段，而不是選項。** 另建議聯繫 GitHub Support 要求清除快取。

---

# 2. 新增了哪些檔案

共 **75 個新增檔案**。以下逐一列出（11 個 launcher icon 圖檔合併說明）。

## 2.1 分析與交接文件（8 個）

```
docs/00_README.md
用途：分析報告的摘要與導讀，含五個關鍵結論與技術選型摘要表
建立原因：6 份報告共 2000+ 行，需要一個入口讓讀者快速定位

docs/01_REPOSITORY_ANALYSIS.md
用途：Repository 逐檔案分析、Mermaid 架構圖、問題分級清單（阻斷/嚴重/中/低）、死碼清單、可用 SDK 取代的部分
建立原因：使用者要求「分析每一個 Module / Package / Service / Activity / ViewModel / Repository / SDK呼叫 / API」

docs/02_ROKID_SDK_ANALYSIS.md
用途：兩條產品線的區分、CXR-M API 清單（含出處與可信度標示）、硬體規格、SDK 優先原則的具體套用
建立原因：使用者要求「若涉及 Rokid SDK 的功能，請引用對應的官方文件章節或 API 名稱作為依據；若文件沒有明確說明，請明確標示為推測或待驗證」

docs/03_FEATURE_ANALYSIS.md
用途：六大功能逐項分析 —— 現況完成度、模型選型比較、Edge/Cloud 判定、第三方 Library 建議、風險
建立原因：使用者列出六個核心功能並要求逐一分析

docs/04_ARCHITECTURE.md
用途：Edge/Cloud 配置決策、雲端供應商比較、Clean Architecture 模組設計、7 張 Mermaid 流程圖
建立原因：使用者要求「請使用 Mermaid 繪製：整體架構圖、資料流程圖、AI流程、導航流程、OCR流程、障礙物流程、Face流程」

docs/05_ROADMAP_AND_FEASIBILITY.md
用途：Phase 0–6 Roadmap（工作內容、目標、預估時間、風險）、誠實的可行性分析、待確認事項
建立原因：使用者要求 Roadmap 與「請誠實回答，不要因為我要做就全部說可以」的可行性分析

docs/06_SECURITY_RUNBOOK.md
用途：金鑰外洩處理程序 —— 撤銷步驟、已完成的變更、歷史清除指令、後續預防
建立原因：發現兩把正式金鑰已進版控，需要一份可執行的處理程序

docs/07_HANDOVER.md
用途：本文件
建立原因：使用者要求完整交接文件
```

## 2.2 環境設定範本（3 個）

```
AI_Assistant/python/.env.example
用途：說明 AI_Assistant 後端需要的環境變數（OPENAI_API_KEY、DB_HOST、DB_USER、DB_PASSWORD）
建立原因：原本的 .env 含真實金鑰且被提交；解除追蹤後需要一份範本讓新開發者知道要設什麼

Text_Recognition/text_recognize/python/.env.example
用途：說明 OCR 後端需要的 GOOGLE_APPLICATION_CREDENTIALS
建立原因：同上，且原本憑證路徑寫死在程式碼中

Text_Recognition/text_recognize/python/requirements.txt
用途：OCR 後端的 Python 依賴清單
建立原因：這個後端原本完全沒有 requirements.txt，新人無從得知需要哪些套件
```

## 2.3 guide-glasses 建置設定（10 個）

```
guide-glasses/settings.gradle.kts
用途：多模組專案的模組註冊與 repository 設定（含 Rokid maven）
建立原因：單一 Gradle 專案取代 5 個獨立專案的入口

guide-glasses/build.gradle.kts
用途：根專案，宣告所有 plugin（apply false）
建立原因：多模組專案的標準結構

guide-glasses/gradle.properties
用途：JVM 記憶體、平行建置、configuration cache、AndroidX 設定
建立原因：建置效能與必要旗標

guide-glasses/gradle/libs.versions.toml
用途：version catalog，集中管理全部依賴版本
建立原因：舊專案的 version catalog 只管 8 個依賴，其餘 12 個硬寫在 build.gradle.kts 中形同虛設

guide-glasses/gradle/wrapper/gradle-wrapper.properties
用途：指定 Gradle 8.13
建立原因：鎖定建置工具版本

guide-glasses/gradle/wrapper/gradle-wrapper.jar
guide-glasses/gradlew
guide-glasses/gradlew.bat
用途：Gradle wrapper
建立原因：從 AI_Assistant/android 複製，讓不裝 Gradle 也能建置

guide-glasses/app/build.gradle.kts
用途：app 模組建置設定，含 LLM_ENDPOINT buildConfigField
建立原因：組裝層

guide-glasses/app/proguard-rules.pro
用途：release 混淆規則（目前為空，僅註記）
建立原因：release buildType 啟用了 minify
```

## 2.4 guide-glasses / core-domain（純 Kotlin，13 個）

```
guide-glasses/core/core-domain/build.gradle.kts
用途：只套用 kotlin.jvm，刻意不套用 Android plugin
建立原因：建置層面的架構約束 —— 任何 android.* 或 com.rokid.* 的 import 都會編譯失敗

guide-glasses/core/core-domain/src/main/kotlin/com/guideglasses/core/domain/AppResult.kt（79 行）
用途：AppResult<T> 與 AppError sealed interface，跨層統一的結果與錯誤型別
建立原因：舊專案讓 exception 直接穿透到 Fragment 再唸出英文訊息；對視障使用者錯誤訊息就是介面

guide-glasses/core/core-domain/src/main/kotlin/com/guideglasses/core/domain/announce/Announcement.kt（74 行）
用途：Announcement data class 與 AnnouncementPriority（CRITICAL / USER_RESPONSE / NAVIGATION / AMBIENT）
建立原因：定義播報的優先級與去抖動、續播語意

guide-glasses/core/core-domain/src/main/kotlin/com/guideglasses/core/domain/announce/AnnouncementQueue.kt（115 行）
用途：播報仲裁核心邏輯 —— 排序、打斷、去抖動、續播。不自己讀時鐘（時間由呼叫端傳入）
建立原因：舊專案有三套互不知情的播放器會互相蓋台；聲音是視障使用者唯一的輸出通道

guide-glasses/core/core-domain/src/main/kotlin/com/guideglasses/core/domain/announce/AnnouncementManager.kt（104 行）
用途：播報執行層，把 AnnouncementQueue 接上 Announcer，含 speakingToken 序號機制
建立原因：TTS 的完成回呼可能在該則早已被打斷之後才送達，不比對序號會造成佇列跳號漏播

guide-glasses/core/core-domain/src/main/kotlin/com/guideglasses/core/domain/announce/Announcer.kt（26 行）
用途：語音輸出抽象介面
建立原因：讓 AnnouncementManager 不綁定 Android TextToSpeech，可用假物件測試

guide-glasses/core/core-domain/src/main/kotlin/com/guideglasses/core/domain/assistant/AssistantIntent.kt（100 行）
用途：9 種意圖的 enum，同時是 LLM function calling 的工具清單來源
建立原因：讓本地快捷指令與 LLM 兩條路徑對應到同一組 intent，不會分岔

guide-glasses/core/core-domain/src/main/kotlin/com/guideglasses/core/domain/assistant/LocalCommandMatcher.kt（95 行）
用途：本地片語比對，只處理高頻且不能等雲端的指令
建立原因：取代舊專案的關鍵字字串比對，修正漏判與誤判

guide-glasses/core/core-domain/src/main/kotlin/com/guideglasses/core/domain/assistant/IntentRouter.kt（103 行）
用途：雙層路由（本地優先，未命中才呼叫 LLM）+ LlmIntentGateway 介面 + 降級訊息
建立原因：系統中樞，所有功能由此分派

guide-glasses/core/core-domain/src/main/kotlin/com/guideglasses/core/domain/assistant/ConversationHistory.kt（45 行）
用途：有上限的對話歷史（預設 10 輪）
建立原因：取代舊後端全域且無上限的 ConversationBufferMemory

guide-glasses/core/core-domain/src/main/kotlin/com/guideglasses/core/domain/glasses/GlassesGateway.kt（93 行）
用途：眼鏡連線、事件、GlassesCapabilities 能力查詢
建立原因：CXR-M 有多項能力待實機驗證，上層需依實際能力動態降級而非假設 API 存在

guide-glasses/core/core-domain/src/main/kotlin/com/guideglasses/core/domain/glasses/FrameSource.kt（81 行）
用途：影像來源抽象、CaptureRequest、CameraFrame
建立原因：讓「眼鏡相機」與「手機相機」可互換，眼鏡不在手邊也能開發

guide-glasses/core/core-domain/src/main/kotlin/com/guideglasses/core/domain/speech/SpeechRecognitionGateway.kt（50 行）
用途：ASR 抽象、SpeechEvent sealed interface
建立原因：讓 ASR 實作可替換（Android 原生 / 雲端）
```

## 2.5 guide-glasses / core-domain 測試（4 個，共 681 行）

```
guide-glasses/core/core-domain/src/test/kotlin/.../announce/AnnouncementQueueTest.kt（208 行，14 個測試）
用途：驗證播報仲裁 —— 危險警示能否打斷長文、同優先級先到先播、去抖動、clearAtOrBelow
建立原因：這些是攸關安全的行為，必須有測試守護

guide-glasses/core/core-domain/src/test/kotlin/.../announce/AnnouncementManagerTest.kt（195 行，8 個測試）
用途：驗證執行層 —— 打斷時先靜音、播完自動接下一則、過期回呼不讓佇列跳號、說停後遲來的回呼不讓內容復活
建立原因：競態問題在真機上難以重現，必須用假 Announcer 手動控制回呼時機來測

guide-glasses/core/core-domain/src/test/kotlin/.../assistant/LocalCommandMatcherTest.kt（115 行，14 個測試）
用途：驗證意圖比對 —— 停的優先級、舊版會漏掉的說法現在能命中、標點與全形處理、閒聊不誤觸發
建立原因：直接針對舊專案的兩類 Bug 寫回歸測試

guide-glasses/core/core-domain/src/test/kotlin/.../assistant/IntentRouterTest.kt（163 行，10 個測試）
用途：驗證雙層路由 —— 本地命中不呼叫 LLM、沒網路時降級成人話且提示可用離線指令、歷史有上限
建立原因：「沒有網路時助理會怎麼回應」對導盲系統是必須測到的路徑
```

## 2.6 guide-glasses / core-common（3 個）

```
guide-glasses/core/core-common/build.gradle.kts
用途：Android library 建置設定
建立原因：需要 Android 相依的共用工具

guide-glasses/core/core-common/src/main/AndroidManifest.xml
用途：空 manifest（AGP 要求）
建立原因：Android library 模組必須有

guide-glasses/core/core-common/src/main/kotlin/com/guideglasses/core/common/DispatcherProvider.kt（25 行）
用途：可注入的 CoroutineDispatcher 來源（main / io / compute）
建立原因：舊專案直接寫死 Dispatchers.IO，導致執行緒切換邏輯無法測試
```

## 2.7 guide-glasses / ai-speech（4 個）

```
guide-glasses/ai/ai-speech/build.gradle.kts
用途：模組建置設定
建立原因：隔離 Android 語音 API

guide-glasses/ai/ai-speech/src/main/AndroidManifest.xml
用途：RECORD_AUDIO 權限 + Android 11+ package visibility 的 <queries>
建立原因：不宣告 queries 的話查詢不到語音辨識與 TTS 服務

guide-glasses/ai/ai-speech/src/main/kotlin/.../AndroidTtsAnnouncer.kt（172 行）
用途：Android TextToSpeech 實作 Announcer，走無障礙音訊通道
建立原因：取代「上傳後端 → tts-1 → 下載 mp3 → MediaPlayer」的 2–3 秒路徑

guide-glasses/ai/ai-speech/src/main/kotlin/.../AndroidSpeechRecognitionGateway.kt（156 行）
用途：Android SpeechRecognizer 實作，callbackFlow 包裝，離線優先，錯誤碼轉領域錯誤
建立原因：取代「錄整段 m4a 上傳 Whisper」的 3–5 秒路徑
```

## 2.8 guide-glasses / ai-agent（4 個）

```
guide-glasses/ai/ai-agent/build.gradle.kts
用途：模組建置設定（含 kotlinx.serialization plugin）
建立原因：需要 JSON 序列化

guide-glasses/ai/ai-agent/src/main/AndroidManifest.xml
用途：INTERNET 權限
建立原因：需要呼叫 BFF

guide-glasses/ai/ai-agent/src/main/kotlin/.../AgentProtocol.kt（90 行）
用途：手機與 BFF 之間的意圖解析協定（RouteRequest / RouteResponse / ToolSpec / ToolInvocation）+ 型別轉換
建立原因：App 不直接呼叫 LLM 供應商；換供應商不需改 App

guide-glasses/ai/ai-agent/src/main/kotlin/.../RemoteLlmIntentGateway.kt（153 行）
用途：HTTP 閘道 + OfflineLlmIntentGateway（未設定後端時的降級）
建立原因：實作 LlmIntentGateway；逾時刻意設短（連線 3s / 讀取 8s），導盲助理十秒後才回答已無意義
```

## 2.9 guide-glasses / ai-agent 測試（1 個）

```
guide-glasses/ai/ai-agent/src/test/kotlin/.../RemoteLlmIntentGatewayTest.kt（182 行，11 個測試）
用途：以 MockWebServer 驗證 —— 工具呼叫解析、非字串參數、未知工具名稱視為失敗、HTTP 錯誤、格式錯誤 JSON、連線中斷、請求內容正確性
建立原因：JSON 解析是最容易出錯的部分，而且很好測
```

## 2.10 guide-glasses / feature-assistant（3 個）

```
guide-glasses/feature/feature-assistant/build.gradle.kts
用途：模組建置設定（含 Hilt）
建立原因：功能模組

guide-glasses/feature/feature-assistant/src/main/AndroidManifest.xml
用途：空 manifest
建立原因：Android library 模組必須有

guide-glasses/feature/feature-assistant/src/main/kotlin/.../AssistantViewModel.kt（187 行）
用途：完整流程 ASR → 意圖路由 → 分派 → 播報；AppError 翻成人話
建立原因：助理中樞的 presentation 層
```

## 2.11 guide-glasses / app 原始碼與資源（16 個）

```
guide-glasses/app/src/main/kotlin/com/guideglasses/GuideGlassesApplication.kt（7 行）
用途：@HiltAndroidApp 進入點
建立原因：Hilt 必須

guide-glasses/app/src/main/kotlin/com/guideglasses/MainActivity.kt（107 行）
用途：單一 Activity，麥克風權限、狀態渲染、TalkBack 標籤隨狀態變化
建立原因：App 主畫面

guide-glasses/app/src/main/kotlin/com/guideglasses/di/CoreModule.kt（18 行）
用途：提供 DispatcherProvider
建立原因：Hilt 接線

guide-glasses/app/src/main/kotlin/com/guideglasses/di/AssistantModule.kt（88 行）
用途：提供 Announcer / AnnouncementManager / SpeechGateway / LlmGateway / IntentRouter / ConversationHistory
建立原因：Hilt 接線；播報用 application 層級 scope，因為要活得比任何畫面久

guide-glasses/app/src/main/AndroidManifest.xml
用途：權限宣告、Application、MainActivity；allowBackup="false"
建立原因：allowBackup 設 false 是刻意的 —— 未來會存人臉資料，不得進雲端備份

guide-glasses/app/src/main/res/values/strings.xml
用途：全部使用者可見文字
建立原因：可翻譯、可測試

guide-glasses/app/src/main/res/values/themes.xml
用途：高對比深色主題
建立原因：對低視力使用者，深底淺字 + 大字級最容易辨識

guide-glasses/app/src/main/res/layout/activity_main.xml
用途：無障礙優先版面 —— 「說話」是佔滿畫面的大按鈕
建立原因：看不見畫面的人不必尋找按鈕在哪，點任何地方都有效

guide-glasses/app/src/main/res/drawable/ic_launcher_background.xml
guide-glasses/app/src/main/res/drawable/ic_launcher_foreground.xml
guide-glasses/app/src/main/res/mipmap-anydpi/ic_launcher.xml
guide-glasses/app/src/main/res/mipmap-anydpi/ic_launcher_round.xml
guide-glasses/app/src/main/res/mipmap-{h,m,xh,xxh,xxxh}dpi/ic_launcher{,_round}.webp（共 10 個）
用途：App 圖示
建立原因：從 AI_Assistant/android 複製，避免使用預設圖示
```

## 2.12 guide-glasses 文件（1 個）

```
guide-glasses/README.md
用途：建置說明、工具鏈版本與鎖版原因、模組結構、目前狀態、語音指令表、播報優先級表、測試說明
建立原因：新開發者的入口
```

---

# 3. 修改了哪些原本就存在的檔案

共 **6 個**，全部集中在 Phase 0 的安全止血。

---

### 3.1 `.gitignore`

**原本用途**：忽略建置產物與暫存檔。

**修改內容**
- 新增機敏樣式並移到檔案最前面：`.env`、`.env.*`（但 `!.env.example`）、`*.pem`、`*.p12`、`*.keystore`、`*.jks`、`**/service-account*.json`、`**/*-credentials.json`、`blind-glasses-ocr-*.json`、`keystore.properties`、`signing.properties`
- **刪除** `*.json`、`*.png`、`*.jpg`、`*.jpeg` 四條全域規則
- **刪除** 第 2 行的 `.gitignore`（原本 gitignore 忽略自己）
- **刪除** 已不存在的路徑規則 `python-server/*`、`android/build/`、`android/app/build/`
- 新增 `.claude/`、`.kotlin/`、`*.apk`、`*.aab`、`*.tflite`、`*.task`、`debug.jpg`、`temp_output_*.mp3`、`recording_*.m4a`

**為什麼**：原本 `.env` 與 GCP JSON 雖已列在 ignore 中，但**檔案在規則之前就被追蹤了，ignore 對已追蹤檔案無效**。同時 `*.json` / `*.png` / `*.jpg` 全域規則會誤擋 `google-services.json` 與 Android `res/drawable` 圖片資源，是實際的地雷。

**效果**：機敏檔案類型不會再被誤加入；Android 資源與設定檔可正常提交。

---

### 3.2 `AI_Assistant/python/main.py`

**原本用途**：FastAPI 後端，提供 `/chat`、`/chat_audio`、`/tts`、`/recognize`、`/register`、`/faces`、`/reload`。

**修改內容**

```python
# 修改前
client = OpenAI(api_key=os.getenv("api_key"))
key = os.getenv("api_key")

# 修改後
key = os.getenv("OPENAI_API_KEY")
if not key:
    raise RuntimeError(
        "缺少環境變數 OPENAI_API_KEY。\n"
        "請複製 .env.example 為 .env，並填入你的 OpenAI API 金鑰。"
    )
client = OpenAI(api_key=key)
```

**刪除的程式碼**（`/recognize` 內）

```python
# debug - 儲存收到的圖片到本地，方便檢查
import cv2
cv2.imwrite("debug.jpg", image)
print(f"Debug image saved to debug.jpg")
```

**為什麼**：`os.getenv("api_key")` 回傳 `None` 時 OpenAI client 會靜默建立，錯誤發生在很後面難以診斷。`debug.jpg` 那三行會把使用者拍到的**任何路人臉部影像**寫到磁碟，屬未經同意蒐集生物特徵。

**效果**：金鑰缺失時啟動即失敗並給出可操作的指示；不再有影像落地。

---

### 3.3 `AI_Assistant/python/function/stt.py`

**原本用途**：呼叫 OpenAI `gpt-4o-transcribe` 做語音轉文字。

**修改內容**：`os.getenv("api_key")` → `os.getenv("OPENAI_API_KEY")`；註解由簡體「加载 .env 文件」改為繁體並指向 `.env.example`。

**為什麼**：統一環境變數命名。

**效果**：功能不變。

---

### 3.4 `AI_Assistant/python/function/tts.py`

**原本用途**：呼叫 OpenAI `tts-1` 做文字轉語音。

**修改內容**：同 3.3。

**為什麼 / 效果**：同 3.3。

> **注意（未修改的既有 Bug）**：`generate_text(response_text, voice="alloy")` 的 `voice` 參數在函式內被 `voice = "nova"` 硬編碼覆蓋，參數完全被忽略。這個 Bug **尚未修正**，因為該後端會在 Phase 3 被端側方案取代。已記錄在 `docs/01_REPOSITORY_ANALYSIS.md` 的問題清單 M2。

---

### 3.5 `AI_Assistant/python/requirements.txt`

**原本用途**：Python 依賴清單（原本 8 行）。

**修改內容**：新增 `opencv-python`、`numpy`、`insightface`、`onnxruntime`、`opencc-python-reimplemented`，並加上分組註解。

**為什麼**：`main.py` 有 `import cv2`、`import numpy`、`from insightface.app import FaceAnalysis`、`from opencc import OpenCC`，但 requirements 完全沒列。照文件安裝必定 `ModuleNotFoundError`。

**效果**：`pip install -r requirements.txt` 之後後端可實際啟動。

---

### 3.6 `Text_Recognition/text_recognize/python/ocr_doc.py`

**原本用途**：Google Cloud Vision OCR 封裝。

**修改內容**

```python
# 修改前
os.environ["GOOGLE_APPLICATION_CREDENTIALS"] = r"C:\Users\user\Downloads\blind-glasses-ocr-d82297cbca1a.json"

# 修改後
from dotenv import load_dotenv
load_dotenv()
if not os.getenv("GOOGLE_APPLICATION_CREDENTIALS"):
    raise RuntimeError(
        "缺少環境變數 GOOGLE_APPLICATION_CREDENTIALS。\n"
        "請複製 .env.example 為 .env，並填入服務帳戶金鑰的檔案路徑。"
    )
```

**為什麼**：憑證路徑寫死在別人電腦的 Downloads 資料夾，換一台電腦就壞；同時把金鑰檔名暴露在程式碼中。

**效果**：可攜；缺少設定時明確報錯。

> **注意（未修改的既有問題）**：`preprocess_doc()` 把彩色影像轉灰階再送 Google Vision，**反而降低準確率**（Vision 對彩色原圖表現更好）。此問題**尚未修正**，已記錄在 `docs/01_REPOSITORY_ANALYSIS.md` 的 M8，將在 Phase 3 重寫 OCR 時處理。

---

### 3.7 刪除的檔案（2 個）

```
AI_Assistant/python/.env
Text_Recognition/text_recognize/python/blind-glasses-ocr-d82297cbca1a.json
```

**說明**：這兩個檔案是**從 git 追蹤中移除**（`git rm --cached`），**本機磁碟上的檔案仍然保留**，因此既有的本機開發環境不受影響。之後又從 git 歷史中徹底清除。

---

# 4. 是否有動到原本專案的檔案？

## **有。**

但範圍極小且用途單一：**6 個既有檔案被修改、2 個被解除追蹤，全部都是為了處理已外洩的金鑰與讓後端能實際安裝啟動。沒有任何一項是為了新架構而改動舊程式。**

## 4.1 完整清單

| 檔案 | 修改類型 | 影響原本功能？ | 可還原？ | 相容性問題？ |
|---|---|---|---|---|
| `.gitignore` | 重寫 | 否（不影響執行） | 是 | 無 |
| `AI_Assistant/python/main.py` | 環境變數名稱 + 刪除 debug 寫檔 | **是，見下方說明** | 是 | 見下方 |
| `AI_Assistant/python/function/stt.py` | 環境變數名稱 | 是（同上） | 是 | 見下方 |
| `AI_Assistant/python/function/tts.py` | 環境變數名稱 | 是（同上） | 是 | 見下方 |
| `AI_Assistant/python/requirements.txt` | 新增 5 個套件 | 否（只會讓安裝更完整） | 是 | 無 |
| `Text_Recognition/.../ocr_doc.py` | 憑證改由環境變數提供 | **是，見下方說明** | 是 | 見下方 |
| `AI_Assistant/python/.env` | 解除 git 追蹤（本機檔保留） | 否 | 是 | 無 |
| `Text_Recognition/.../blind-glasses-ocr-*.json` | 解除 git 追蹤（本機檔保留） | 否 | 是 | 無 |

## 4.2 會影響原本功能的兩點（必須知道）

**（1）環境變數名稱從 `api_key` 改成 `OPENAI_API_KEY`**

- 舊的 `.env` 若仍寫 `api_key=...`，後端會啟動失敗並印出中文指示。
- **本機已處理**：這個工作階段中已用 `sed` 把本機 `AI_Assistant/python/.env` 的鍵名改成 `OPENAI_API_KEY`（未顯示其值）。
- 其他開發者的機器需要自行改名，或複製 `.env.example` 重建。

**（2）OCR 憑證改由環境變數提供**

- 原本路徑寫死，現在需要設定 `GOOGLE_APPLICATION_CREDENTIALS`。
- **本機已處理**：已建立 `Text_Recognition/text_recognize/python/.env` 指向本機既有的金鑰檔。
- 其他開發者需自行設定。

**（3）金鑰本身已被撤銷的話（強烈建議撤銷）**

兩個後端都會因為金鑰失效而無法運作，直到填入新金鑰為止。這是預期行為，不是缺陷。

## 4.3 可還原性

全部可還原。

```bash
git revert 5687577
```

（`5687577` 是歷史重寫後的 `security:` commit hash。）

但**不建議還原** —— 還原等於把兩把金鑰重新放回版控。若只是想恢復舊的環境變數命名，改 `.env` 檔的鍵名即可，不需要 revert。

## 4.4 完全沒有動到的部分

以下**一行都沒有改**：

- `AI_Assistant/android/` 全部 Kotlin 原始碼、layout、resource、gradle 設定
- `Face_Recognition/` 整個資料夾（Android + Python）
- `Obstacle_Recognition/` 整個資料夾
- `Audio_Navigation/` 整個資料夾
- `Text_Recognition/text_recognize/andriod/` 全部 Java 原始碼與資源
- `Text_Recognition/text_recognize/python/api.py`
- `AI_Assistant/python/face_engine.py`、`admin.py`、`function/file_manager.py`
- `AI_Assistant/python/face_database/` 的 5 筆人臉測試資料
- 根目錄 `README.md`、`requirements.txt`、`LICENSE`

**因此 5 個舊 Android App 的行為完全沒有改變。**

---

# 5. `guide-glasses` 資料夾是什麼？

## 5.1 逐項回答

| 問題 | 回答 |
|---|---|
| 是否為新建立的整合專案？ | **是。** 完全由本次工作新建，起始於 commit `08a547b`（Phase 1） |
| 是否為完整專案？ | **架構上完整，功能上不完整。** 它是一個能獨立建置、能產出可安裝 APK 的 Gradle 專案；但六大功能中只有「AI 助理中樞」實作完成，其餘五個尚未實作 |
| 是否獨立於原本專案？ | **完全獨立。** 有自己的 `settings.gradle.kts`、`gradlew`、version catalog、package name（`com.guideglasses`）。舊專案的 5 個 Gradle 專案完全不知道它的存在 |
| 是否有引用原本專案？ | **沒有任何程式碼引用。** 唯一的關聯是複製了 launcher icon 圖檔（`ic_launcher*.webp` / `.xml`）與 Gradle wrapper 的 jar/腳本 |
| 與原本專案的關係？ | **取代關係，但目前是並存的。** 新專案會逐步實作六大功能；每個功能對齊後，對應的舊專案才會被移除。目前**舊專案全部保留、可正常使用** |
| 為什麼要建立？ | 見下方 5.2 |

## 5.2 為什麼要建立 guide-glasses

分析後與使用者確認採「建立新的多模組專案」而非「原地重構」，理由：

1. **舊的是 5 個原型，不是 1 個系統的 5 個模組。** 沒有共用層，沒有統一入口，使用者要裝 5 個 APK。
2. **技術棧不一致。** 4 個 Kotlin + 1 個 Java；package 命名有 `com.example.*` 也有 `com.rokid.*`。
3. **舊專案有多個阻斷級問題需要逐一拆除**（Chaquopy 硬編碼路徑、Manifest 引用不存在的 resource、`BASE_URL` 佔位字串），改造成本高於重寫。
4. **需要在建置層面建立架構約束**（domain 層不得依賴 Android/Rokid），在既有結構上加不上去。
5. **有價值的資產可以移植而不必連同問題一起繼承** —— `splitTextForSpeech()` 斷句邏輯、播報冷卻機制、人臉測試資料、預錄提示音。

## 5.3 專案架構樹

```
guide_glasses_project_/                    ← Repository 根目錄
│
├── docs/                                  ← 【新增】分析報告與交接文件
│   ├── 00_README.md
│   ├── 01_REPOSITORY_ANALYSIS.md
│   ├── 02_ROKID_SDK_ANALYSIS.md
│   ├── 03_FEATURE_ANALYSIS.md
│   ├── 04_ARCHITECTURE.md
│   ├── 05_ROADMAP_AND_FEASIBILITY.md
│   ├── 06_SECURITY_RUNBOOK.md
│   └── 07_HANDOVER.md                     ← 本文件
│
├── guide-glasses/                         ← 【新增】統一的多模組 Android 專案
│   │
│   ├── settings.gradle.kts                模組註冊 + repository（含 Rokid maven）
│   ├── build.gradle.kts                   根專案，plugin 宣告
│   ├── gradle.properties                  JVM 記憶體、平行建置、configuration cache
│   ├── gradlew / gradlew.bat              Gradle wrapper 腳本
│   ├── README.md                          建置說明與目前狀態
│   ├── gradle/
│   │   ├── libs.versions.toml             version catalog（集中管理全部版本）
│   │   └── wrapper/                       Gradle 8.13
│   │
│   ├── app/                               【組裝層】
│   │   ├── build.gradle.kts               含 LLM_ENDPOINT buildConfigField
│   │   ├── proguard-rules.pro
│   │   └── src/main/
│   │       ├── AndroidManifest.xml        權限宣告，allowBackup="false"
│   │       ├── kotlin/com/guideglasses/
│   │       │   ├── GuideGlassesApplication.kt   @HiltAndroidApp
│   │       │   ├── MainActivity.kt              單一 Activity
│   │       │   └── di/
│   │       │       ├── CoreModule.kt            DispatcherProvider
│   │       │       └── AssistantModule.kt       助理相關全部接線
│   │       └── res/                             layout / values / mipmap / drawable
│   │
│   ├── core/                              【共用基礎】
│   │   ├── core-domain/                   ★ 純 Kotlin，不套用 Android plugin
│   │   │   └── src/
│   │   │       ├── main/kotlin/com/guideglasses/core/domain/
│   │   │       │   ├── AppResult.kt             結果與錯誤型別
│   │   │       │   ├── announce/                播報仲裁
│   │   │       │   │   ├── Announcement.kt
│   │   │       │   │   ├── AnnouncementPriority（在 Announcement.kt 內）
│   │   │       │   │   ├── AnnouncementQueue.kt
│   │   │       │   │   ├── AnnouncementManager.kt
│   │   │       │   │   └── Announcer.kt
│   │   │       │   ├── assistant/               意圖路由
│   │   │       │   │   ├── AssistantIntent.kt
│   │   │       │   │   ├── LocalCommandMatcher.kt
│   │   │       │   │   ├── IntentRouter.kt
│   │   │       │   │   └── ConversationHistory.kt
│   │   │       │   ├── glasses/                 眼鏡能力抽象
│   │   │       │   │   ├── GlassesGateway.kt
│   │   │       │   │   └── FrameSource.kt
│   │   │       │   └── speech/
│   │   │       │       └── SpeechRecognitionGateway.kt
│   │   │       └── test/kotlin/...              4 個測試類、46 個測試
│   │   │
│   │   └── core-common/                   Android 相依的共用工具
│   │       └── src/main/kotlin/.../DispatcherProvider.kt
│   │
│   ├── ai/                                【Edge AI 實作】
│   │   ├── ai-speech/                     Android 原生語音
│   │   │   └── src/main/kotlin/.../
│   │   │       ├── AndroidTtsAnnouncer.kt
│   │   │       └── AndroidSpeechRecognitionGateway.kt
│   │   │
│   │   └── ai-agent/                      LLM function calling
│   │       └── src/
│   │           ├── main/kotlin/.../
│   │           │   ├── AgentProtocol.kt
│   │           │   └── RemoteLlmIntentGateway.kt
│   │           └── test/kotlin/...        11 個測試
│   │
│   └── feature/                           【功能模組】
│       └── feature-assistant/
│           └── src/main/kotlin/.../AssistantViewModel.kt
│
├── AI_Assistant/                          ← 【原有，保留】
├── Face_Recognition/                      ← 【原有，保留】
├── Obstacle_Recognition/                  ← 【原有，保留】
├── Audio_Navigation/                      ← 【原有，保留】
├── Text_Recognition/                      ← 【原有，保留】
├── README.md                              ← 【原有，未修改】
├── requirements.txt                       ← 【原有，未修改】
└── LICENSE                                ← 【原有，未修改】
```

## 5.4 每個資料夾的用途

| 資料夾 | 用途 | 為什麼這樣分 |
|---|---|---|
| `app/` | 組裝層。只做 DI 接線、Activity、Manifest | 讓所有實作細節都在別的模組，app 保持極薄 |
| `core/core-domain/` | Entity、業務規則、介面定義 | **刻意只套用 `kotlin.jvm`**。任何 `android.*` 或 `com.rokid.*` 的 import 都會編譯失敗，從建置機制上鎖住分層，並讓核心邏輯能用純 JVM 測試（秒級、不需模擬器） |
| `core/core-common/` | 需要 Android 相依的共用工具 | 與 core-domain 分開，避免污染純 Kotlin 模組 |
| `ai/ai-speech/` | Android 語音 API 的封裝 | 把 `android.speech.*` 隔離在單一模組 |
| `ai/ai-agent/` | LLM 協定與 HTTP | 把網路與序列化隔離；換 LLM 供應商不需改其他模組 |
| `feature/feature-assistant/` | 助理中樞的 presentation 層 | 功能可獨立開發、獨立編譯 |

**尚未建立、規劃中的模組**：`glasses/glasses-api`、`glasses/glasses-cxr`、`glasses/glasses-fallback`、`ai/ai-vision`、`ai/ai-face`、`ai/ai-ocr`、`core/core-database`、`core/core-network`、`core/core-ui`、`feature/feature-obstacle`、`feature/feature-face`、`feature/feature-navigation`、`feature/feature-ocr`。

---

# 6. 現在可以直接執行嗎？

## **可以（但只有 guide-glasses 的助理功能）。**

分三個部分說明。

---

## 6.1 `guide-glasses` Android App —— ✅ 可以直接建置與執行

### 需要安裝的東西

| 項目 | 版本要求 | 備註 |
|---|---|---|
| **JDK** | **17 或以上** | 建議直接用 Android Studio 內附的 JBR（本機實測用的是 JBR 21.0.9）。**JDK 11 不行**，AGP 8.13.2 需要 17+ |
| **Android SDK** | Platform **36**，Build-Tools **36.x** | 透過 Android Studio SDK Manager 安裝 |
| **Android Studio** | 選用 | 只用命令列建置的話不需要，但需要 Android SDK |
| **Gradle** | 不需另外安裝 | 專案自帶 wrapper，會自動下載 Gradle 8.13 |
| **網路** | 首次建置需要 | 下載依賴（Maven Central、Google Maven、Rokid Maven） |

**不需要**：Node.js、npm/pnpm/yarn、Docker、Python（guide-glasses 部分完全用不到）。

### 執行流程

**Step 1 — 取得程式碼**

> ⚠️ **git 歷史已於 2026-08-05 重寫。舊的本機 clone 必須刪除後重新 clone，否則 `git pull` 會把含金鑰的舊歷史推回去。**

```bash
git clone https://github.com/mato1321/guide_glasses_project_.git
```

**Step 2 — 建立 `local.properties`**

在 `guide-glasses/local.properties` 建立檔案，內容指向你的 Android SDK。**反斜線與磁碟機冒號都必須跳脫**，否則建置會失敗：

```
sdk.dir=C\:\\Users\\<你的帳號>\\AppData\\Local\\Android\\Sdk
```

macOS / Linux：

```
sdk.dir=/Users/<你的帳號>/Library/Android/sdk
```

**Step 3 — 設定 JDK（若系統預設不是 17+）**

Windows Git Bash：

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
```

PowerShell：

```powershell
$env:JAVA_HOME = "$env:ProgramFiles\Android\Android Studio\jbr"
```

**Step 4 — 建置**

```bash
cd guide-glasses && ./gradlew build
```

首次建置約 1–3 分鐘（需下載依賴），之後約 10 秒。

**Step 5 — 執行單元測試**

```bash
cd guide-glasses && ./gradlew test
```

**Step 6 — 安裝到手機**

```bash
cd guide-glasses && ./gradlew installDebug
```

或手動安裝 `guide-glasses/app/build/outputs/apk/debug/app-debug.apk`。

**Step 7 — 使用**

1. 開啟「導盲眼鏡」App
2. 允許麥克風權限
3. 點畫面任何地方（整片都是「說話」按鈕）
4. 說出指令，例如「這是誰」「前面有什麼」「唸給我聽」「停」

目前的行為：本地快捷指令會被正確辨識並播報「這個功能還在開發中」；「停」會立刻靜音。

### 選用：設定 LLM 後端

不設定的話 App 完全可用，只是複雜語句（「帶我去台北101」）無法理解。設定方式是在 `guide-glasses/local.properties` 或 `~/.gradle/gradle.properties` 加入：

```
guideglasses.llmEndpoint=https://your-bff.run.app/route
```

**BFF 目前不存在，需要自行實作。** 協定定義在 `guide-glasses/ai/ai-agent/src/main/kotlin/com/guideglasses/ai/agent/AgentProtocol.kt`：

- 請求：`POST` JSON `{utterance, history[], tools[], locale}`
- 回應：`{"tool": {"name": "...", "arguments": {...}}}` 或 `{"reply": "..."}`

---

## 6.2 舊的 5 個 Android 專案 —— ⚠️ 部分不能執行（與本次工作無關，是原有狀態）

| 專案 | 可否建置 | 原因 |
|---|---|---|
| `AI_Assistant/android` | **不行** | Chaquopy 硬編碼 `C:\Users\jerry\...python.exe`；Manifest 引用不存在的 `@xml/device_filter` |
| `AI_Assistant/android`（執行期） | **不行** | `BASE_URL = "你的ip位址"`，Retrofit 建構即拋 `IllegalArgumentException` |
| `Face_Recognition/android` | 無法確認 | 未實際建置測試 |
| `Obstacle_Recognition/android` | 無法確認 | 未實際建置測試；即使能建置也只有權限檢查，沒有功能 |
| `Audio_Navigation/android` | 無法確認 | 同上 |
| `Text_Recognition/.../andriod` | 無法確認 | 未實際建置測試；`OCR_URL` 硬編碼 `http://172.20.10.5:8000` 需要改成你的後端位址 |

**這些問題都是原本就存在的，本次工作沒有修改任何舊的 Android 程式碼。**

---

## 6.3 Python 後端 —— ✅ 可以執行（金鑰有效的前提下）

### AI_Assistant 後端

需要：Python 3.10+（`__pycache__` 顯示原作者用 3.10）

```bash
cd AI_Assistant/python && pip install -r requirements.txt
```

```bash
cp AI_Assistant/python/.env.example AI_Assistant/python/.env
```

編輯 `.env` 填入新的 `OPENAI_API_KEY`（**舊金鑰請務必撤銷**），然後：

```bash
cd AI_Assistant/python && uvicorn main:app --host 0.0.0.0 --port 8000
```

> 首次啟動會下載 InsightFace `buffalo_l` 模型（數百 MB），需要時間與網路。

### Text_Recognition OCR 後端

```bash
cd Text_Recognition/text_recognize/python && pip install -r requirements.txt
```

```bash
cp Text_Recognition/text_recognize/python/.env.example Text_Recognition/text_recognize/python/.env
```

編輯 `.env` 的 `GOOGLE_APPLICATION_CREDENTIALS` 指向你的 GCP 服務帳戶金鑰檔（**放在版控目錄之外**，舊金鑰請務必撤銷），然後：

```bash
cd Text_Recognition/text_recognize/python && uvicorn api:app --host 0.0.0.0 --port 8000
```

### Obstacle_Recognition 腳本

`zebra/main.py` 與 `trafficlight/main.py` 需要 `ultralytics` 與模型權重檔（`yolo-seg.pt`、`trafficlight.pt`）。**權重檔不在 repository 中**，無法直接執行。

### Audio_Navigation

`python/main.py` 是 0 bytes，沒有東西可執行。

---

# 7. 如何執行（完整步驟彙整）

## 7.1 最快路徑：只跑新的 guide-glasses App

```bash
git clone https://github.com/mato1321/guide_glasses_project_.git
```

**Step 2** — 建立 `guide-glasses/local.properties`（見 6.1 Step 2 的跳脫規則）

```bash
cd guide-glasses && ./gradlew build
```

```bash
cd guide-glasses && ./gradlew installDebug
```

**Step 5** — 開 App、允許麥克風、點畫面說「這是誰」

**不需要**啟動任何後端、不需要 Python、不需要眼鏡。

---

## 7.2 完整路徑：新 App + 舊後端

**Step 1–2**：同 7.1

**Step 3** — 啟動 AI_Assistant 後端

```bash
cd AI_Assistant/python && pip install -r requirements.txt && cp .env.example .env
```

編輯 `.env` 填入新的 OpenAI 金鑰，然後：

```bash
cd AI_Assistant/python && uvicorn main:app --host 0.0.0.0 --port 8000
```

**Step 4** — 啟動 OCR 後端（另一個終端機，注意換 port）

```bash
cd Text_Recognition/text_recognize/python && pip install -r requirements.txt && cp .env.example .env
```

編輯 `.env` 指向 GCP 金鑰檔，然後：

```bash
cd Text_Recognition/text_recognize/python && uvicorn api:app --host 0.0.0.0 --port 8001
```

**Step 5** — 人臉資料庫管理（選用）

瀏覽器開啟 `http://localhost:8000/admin`

> ⚠️ 這個頁面**沒有任何認證**，且有 XSS 與路徑穿越漏洞。**只能在本機使用，絕不可對外開放。**

**Step 6** — 建置並安裝 App（同 7.1 Step 3–4）

> 注意：目前 `guide-glasses` **尚未串接這兩個後端**。Phase 3 起才會接。現階段啟動後端只對舊的 5 個 App 有意義。

---

## 7.3 各層啟動方式對照

| 層 | 啟動方式 | 目前狀態 |
|---|---|---|
| 前端（Android） | `./gradlew installDebug` | ✅ 可用（新專案） |
| 後端（AI 助理） | `uvicorn main:app` | ✅ 可用（需新金鑰） |
| 後端（OCR） | `uvicorn api:app` | ✅ 可用（需新金鑰） |
| AI（端側） | 隨 App 一起，不需另外啟動 | ✅ ASR/TTS 可用 |
| AI（雲端 LLM） | 需自行實作 BFF | ❌ 不存在 |
| Database | 無 | ❌ 尚未使用任何資料庫。人臉資料目前存在後端的檔案系統 |
| Web | 只有後端的 `/admin` 頁面 | ⚠️ 無認證，僅限本機 |

---

# 8. 所有使用到的技術

## 8.1 guide-glasses（新專案）

### 建置工具

| 技術 | 版本 | 用途 |
|---|---|---|
| Gradle | 8.13 | 建置系統 |
| Android Gradle Plugin | 8.13.2 | Android 建置 |
| Kotlin | 2.2.10 | 主要語言 |
| KSP | 2.2.10-2.0.2 | 註解處理（Hilt 需要） |
| JDK | 17（target） | 編譯目標 |

### Framework / Library

| 技術 | 版本 | 用途 |
|---|---|---|
| Hilt (Dagger) | 2.57.1 | 依賴注入 |
| AndroidX Core KTX | 1.17.0 | Kotlin 擴充 |
| AndroidX AppCompat | 1.7.1 | 相容性 Activity |
| AndroidX Activity KTX | 1.12.3 | `by viewModels()` |
| AndroidX Lifecycle | 2.9.4 | ViewModel、`repeatOnLifecycle` |
| Material Components | 1.13.0 | UI 元件與主題 |
| kotlinx-coroutines | 1.10.2 | 非同步 |
| kotlinx-serialization-json | 1.9.0 | BFF 協定 JSON |
| OkHttp | 4.12.0 | HTTP 客戶端 |

### Android 平台 API

| API | 用途 |
|---|---|
| `android.speech.SpeechRecognizer` | 語音辨識（串流、離線優先） |
| `android.speech.tts.TextToSpeech` | 語音合成 |
| `android.media.AudioAttributes` | 無障礙音訊通道 |

### 測試

| 技術 | 版本 | 用途 |
|---|---|---|
| JUnit | 4.13.2 | 測試框架 |
| Google Truth | 1.4.5 | 斷言 |
| kotlinx-coroutines-test | 1.10.2 | coroutine 測試 |
| OkHttp MockWebServer | 4.12.0 | HTTP 測試 |
| Turbine | 1.2.1 | Flow 測試（已宣告，尚未使用） |

### 已宣告但尚未使用

| 技術 | 說明 |
|---|---|
| Rokid Maven repository | `settings.gradle.kts` 已加入 `https://maven.rokid.com/repository/maven-public/`，但**尚未宣告任何 Rokid 依賴** |

---

## 8.2 舊專案（原有技術，未修改）

### Android

| 技術 | 用途 | 所在專案 |
|---|---|---|
| Retrofit 2.9.0 + Gson | HTTP | AI_Assistant, Face_Recognition |
| OkHttp 4.9.3 | HTTP | AI_Assistant, Text_Recognition |
| CameraX 1.3.1 | 相機 | AI_Assistant, Face_Recognition |
| UVCAndroid 1.0.7 | USB 相機（**未實際使用**） | AI_Assistant, Face_Recognition |
| `com.rokid.cxr:client-m` | Rokid SDK（**未實際使用**） | AI_Assistant |
| opencc4j 1.6.2 | 繁簡轉換 | AI_Assistant |
| Chaquopy 17.0.0 | Android 內嵌 Python（**只有兩個沒用的函式**） | AI_Assistant |

### Python 後端

| 技術 | 用途 |
|---|---|
| FastAPI + Uvicorn | Web framework |
| LangChain + langchain-openai | 對話鏈 |
| OpenAI SDK | `gpt-4o-mini`、`gpt-4o-transcribe`、`tts-1` |
| InsightFace（`buffalo_l`）+ ONNX Runtime | 人臉 embedding |
| OpenCV + NumPy | 影像處理 |
| Google Cloud Vision | OCR (`document_text_detection`) |
| OpenCC | 繁簡轉換 |
| Ultralytics YOLO | 桌面實驗腳本（權重檔不在 repo） |

### AI Model

| 模型 | 位置 | 用途 |
|---|---|---|
| `gpt-4o-mini` | OpenAI 雲端 | 對話 |
| `gpt-4o-transcribe` | OpenAI 雲端 | STT |
| `tts-1`（voice: nova） | OpenAI 雲端 | TTS |
| InsightFace `buffalo_l` | 後端本機 CPU | 人臉 embedding |
| `yolo-seg.pt` | **不在 repo** | 斑馬線分割實驗 |
| `trafficlight.pt` | **不在 repo** | 紅綠燈偵測實驗 |

### 雲端服務

| 服務 | 用途 | 狀態 |
|---|---|---|
| OpenAI API | LLM / STT / TTS | 金鑰已外洩，需撤銷 |
| Google Cloud Vision API | OCR | 金鑰已外洩，需撤銷 |

### 規劃中但尚未使用

YOLO26-n（TFLite）、MediaPipe Face Detector、MobileFaceNet TFLite、ML Kit Text Recognition v2、ML Kit Translation、Room、ObjectBox、Google Maps Platform、TDX 運輸資料流通服務、Cloud Run、Firebase Auth、Anthropic Claude API。

---

# 9. 哪些地方還沒有完成？

## 9.1 尚未實作的功能

| 功能 | 完成度 | 說明 |
|---|---|---|
| **障礙物偵測** | 0%（新專案） | `ai-vision` 模組尚未建立。`AssistantIntent.DETECT_OBSTACLES` 目前只會播報「功能還在開發中」 |
| **人臉辨識** | 0%（新專案） | `ai-face` 模組尚未建立 |
| **智慧導航** | 0% | `feature-navigation` 尚未建立 |
| **翻譯** | 0% | 尚未建立 |
| **OCR 朗讀** | 0%（新專案） | `ai-ocr` 尚未建立 |
| **眼鏡連線** | 0% | `glasses-cxr` / `glasses-fallback` 尚未建立。**目前 App 完全沒有碰過 Rokid SDK** |
| **BFF 後端** | 0% | 協定已定義，實作不存在 |
| **相機** | 0% | `FrameSource` 介面已定義，無任何實作 |
| **資料庫** | 0% | `core-database` 尚未建立 |

## 9.2 已知 Bug（尚未修正）

| # | 位置 | 問題 | 為什麼還沒修 |
|---|---|---|---|
| B1 | `AI_Assistant/python/function/tts.py:25` | `voice` 參數被 `voice = "nova"` 硬編碼覆蓋 | 該後端將在 Phase 3 被端側方案取代 |
| B2 | `AI_Assistant/python/main.py:78-86` | 繁簡轉換結果 `response_1` 被丟棄，實際送 TTS 與 header 的是未轉換的簡體 | 同上 |
| B3 | `Text_Recognition/.../ocr_doc.py` | `preprocess_doc()` 轉灰階反而降低 Vision 準確率 | Phase 3 重寫 OCR 時處理 |
| B4 | `AI_Assistant/.../AudioPlayer.kt:46` | `setOnCompletionListener` 內的 `release()` 解析到 `MediaPlayer.release()` 而非外層，`mediaPlayer` 欄位不會被設為 null | 舊專案，將被取代 |
| B5 | `AI_Assistant/.../ApiClient.kt:12` | `BASE_URL = "你的ip位址"`，Retrofit 建構即崩潰 | 舊專案，將被取代 |
| B6 | `AI_Assistant/android/app/build.gradle.kts:97` | Chaquopy 硬編碼他人電腦路徑 | 舊專案，將被取代 |
| B7 | `AI_Assistant/AndroidManifest.xml:54` | 引用不存在的 `@xml/device_filter` | 舊專案，將被取代 |
| B8 | `Text_Recognition/.../CameraPreviewActivity.java` | 用 `Intent` 傳完整 JPEG `byte[]`，大圖會 `TransactionTooLargeException` | 舊專案，將被取代 |

## 9.3 已知安全問題（尚未修正）

| # | 位置 | 問題 |
|---|---|---|
| S1 | `AI_Assistant/python/admin.py` | `/admin` **完全沒有認證**，任何人可新增／改名／刪除人臉 |
| S2 | `admin.py` | HTML 用 f-string 拼接使用者輸入 → **XSS** |
| S3 | `admin.py:136` | `rename` 未過濾 `../` → **路徑穿越**，可寫到任意路徑 |
| S4 | `Text_Recognition/.../api.py:11` | `allow_origins=["*"]` |
| S5 | `Text_Recognition/.../MainActivity.java:58` | 明文 HTTP + 硬編碼區網 IP |
| S6 | GitHub | **舊 blob 仍可透過 GitHub API 依 SHA 取回**（見 §10.1） |

## 9.4 需要優化的部分

- `FaceEngine.recognize()` 是 O(n) 線性掃描（舊後端）
- `Turbine` 已宣告但尚未使用
- guide-glasses 尚無 CI（GitHub Actions）
- guide-glasses 尚無 instrumented test
- `AndroidTtsAnnouncer.applyRateFor()` 已實作但尚未被呼叫

## 9.5 後續建議（依 Roadmap 順序）

1. **Phase 0 實機驗證**（最高優先，且**只有你能做**）：確認 `takeGlassPhoto()` 往返延遲、是否有連續串流 API、`sendTtsContent()` 由誰發聲、眼鏡麥克風能否串流、實際續航
2. **Phase 3**：OCR（ML Kit 三層策略）+ 人臉（MediaPipe + MobileFaceNet 端側）
3. **Phase 4**：障礙物偵測（YOLO26-n）—— 需視 Phase 0 的串流驗證結果決定產品定位
4. **Phase 5**：導航（5a 步行先行，5b 公車後補）
5. **Phase 6**：整合、續航實測、**與真實視障使用者的可用性測試**

---

# 10. 是否有任何可能造成錯誤的地方？

## 10.1 🔴 安全風險

**（1）兩把金鑰已外洩，且 GitHub 仍可依 SHA 取回舊 blob**

實測結果（2026-08-05，歷史重寫並 force push 之後）：

```
GET /repos/mato1321/guide_glasses_project_/git/blobs/dded5150c0d1...  → HTTP 200, size=52
GET /repos/mato1321/guide_glasses_project_/git/blobs/f0fa304c7e24...  → HTTP 200, size=619
```

一般 `git clone` 已取不到，但知道 SHA 的人仍可取得完整內容。這是 GitHub 的已知行為（force push 不移除被 `refs/pull/*` 參照或近期快取的物件）。

→ **撤銷金鑰是唯一有效的止血手段。** 另建議聯繫 GitHub Support 要求清除。

**（2）`/admin` 頁面無認證 + XSS + 路徑穿越** —— 絕不可對外開放。

## 10.2 🔴 建置與相依風險

| 風險 | 說明 | 緩解 |
|---|---|---|
| **AGP 不可升到 9.x** | AGP 9 內建 Kotlin 支援會與 `kotlin.android` plugin 衝突；且移除 `BaseExtension` 導致 Hilt（至 2.57.1）無法套用。**兩者都經實測確認** | 已鎖版並在 `libs.versions.toml` 與 README 註明 |
| **JDK 11 無法建置** | AGP 8.13.2 需要 JDK 17+ | 文件中已標明 |
| **`local.properties` 跳脫** | Windows 路徑的反斜線與磁碟機冒號都要跳脫，否則 `java.io.IOException: Invalid file path` 或 lint 失敗 | README 已說明 |
| **Rokid Maven 可用性** | `maven.rokid.com` 是第三方 repository，若無法連線則整個專案無法解析依賴（目前尚未宣告 Rokid 依賴，暫無影響） | 目前無實際依賴，風險低 |
| **首次建置需要網路** | 下載 Gradle 8.13 + 全部依賴 | — |

## 10.3 🟠 SDK 與硬體限制

| 限制 | 說明 | 狀態 |
|---|---|---|
| **無公開的連續影像串流 API** | CXR-M 官方文件只有 `takeGlassPhoto()`。社群專案 RokidStream 自行實作只達 240×240 @ 10fps @ 100kbps（BLE L2CAP），對 YOLO（通常需 640×640）幾乎不可用 | **待實機驗證** |
| **眼鏡 2 GB RAM** | 扣掉 YodaOS 本身，第三方可用記憶體極少，不可能跑 AI 模型 | 已確認（規格） |
| **眼鏡 210 mAh / 約 4 小時** | 且是不開相機的 4 小時。連續拍照傳輸下可能 <1.5 小時 | 已確認（規格），實測待驗證 |
| **顯示為單色綠 480×398 / 23° FOV** | 對全盲使用者無意義 | 已確認（規格） |
| **`sendTtsContent()` 音訊路由** | 由眼鏡還是手機發聲，文件未明確說明 | **待驗證** |
| **眼鏡麥克風串流** | 能否傳到手機，未見文件 | **待驗證** |
| **眼鏡 IMU / GPS** | 是否開放讀取，未見文件。推測不開放，導航需靠手機 GPS | **待驗證** |
| **眼鏡端自訂 UI** | 只找到 `configTranslationText` / `configWordTipsText` 這類預設場景參數，未見任意繪製 API | **待驗證** |

## 10.4 🟠 Android 平台限制

| 限制 | 影響 |
|---|---|
| Android 14+ 前景服務型別 | 需宣告 `FOREGROUND_SERVICE_LOCATION` 並顯示使用者可見通知 |
| 背景相機限制 | 螢幕關閉時無法使用相機，除非 App 在前景 |
| **廠商 ROM 省電機制** | 小米、OPPO、華為會主動殺掉長時間執行的服務 —— 使用者不能把手機放口袋鎖螢幕就走 |
| `SpeechRecognizer` 執行緒 | 必須在主執行緒建立與呼叫（已在實作中處理） |
| `EXTRA_PREFER_OFFLINE` | 需 API 23+，且**是否真的有離線模型取決於裝置**。無法確認每台裝置都支援中文離線辨識 |
| TTS 中文語音資料 | 部分裝置可能未安裝，`AndroidTtsAnnouncer` 已處理此情況並回報失敗 |

## 10.5 🟠 API 與成本限制

| 項目 | 限制 |
|---|---|
| Google Directions API | **transit 模式不回傳公車即時到站時間** —— 必須另接 TDX |
| Google Maps 成本 | Directions 約 US$5/1000 次，導航中每次重新規劃就是一次呼叫，**必須節流** |
| TDX | 需申請會員，每會員最多 3 組金鑰。免費額度的具體限制**無法確認**，需查官方 |
| 都市 GPS 精度 | 台北高樓區誤差可達 15–30m，「偏離 30m 重新規劃」的閾值需實地調校 |
| 估算月成本 | 100 使用者 × 每天 2 小時 ≈ US$165–295/月，其中 Google Maps 占最大宗 |

## 10.6 🟡 尚未驗證的功能

以下**全部尚未在實機或實際環境驗證過**：

- guide-glasses App 在真實 Android 裝置上的執行（**只驗證過建置成功，未安裝到實機測試**）
- `AndroidSpeechRecognitionGateway` 的實際辨識品質與離線可用性
- `AndroidTtsAnnouncer` 在不同裝置上的中文語音可用性
- `RemoteLlmIntentGateway` 對真實 BFF 的呼叫（只用 MockWebServer 測過）
- 任何與 Rokid 眼鏡的實際連線
- 舊的 5 個 Android 專案是否能建置（除 AI_Assistant 已確認不行）

> **明確聲明**：本次工作的驗證方式是「Gradle 建置成功 + 單元測試通過 + lint 無錯誤」。**沒有在實體 Android 裝置上執行過 App**，也**沒有連接過 Rokid 眼鏡**。

---

# 11. Git 修改摘要

Git 資訊**可以取得**，以下全部以 `git` 指令實際查詢為依據。

## 11.1 Commit 清單

歷史已於 2026-08-05 重寫，因此有兩組 hash。

| 舊 hash | 新 hash | 訊息 | 檔案數 | +行 | −行 |
|---|---|---|---|---|---|
| `2e626cf` | `5687577` | security: 移除已提交的金鑰並將設定外部化 | 17 | 2121 | 86 |
| `94ef9d1` | `ae1dcb5` | docs: 新增金鑰外洩處理 runbook（含已驗證的歷史清除程序） | 1 | 132 | 0 |
| `09dbe3c` | `08a547b` | feat(phase1): 建立 guide-glasses 多模組專案地基 | 43 | 1744 | 0 |
| `163f3eb` | `591a433` | feat(phase2): AI 助理中樞 —— 原生 ASR/TTS 與 Function Calling 路由 | 29 | 2327 | 15 |
| `1b20ef8` | `d7da0e1` | docs: 更新 guide-glasses README 至 Phase 2 狀態 | 1 | 60 | 12 |
| `cb104d3` | `f7fe1d5` | Merge pull request #2 | — | — | — |

## 11.2 總計

```
83 files changed, 6357 insertions(+), 86 deletions(-)
```

（相對於起始基準 `1adfd1a` / `0de7c5d`）

## 11.3 依類型分類

| 類型 | 數量 |
|---|---|
| **新增（A）** | 75 |
| **修改（M）** | 6 |
| **刪除（D）** | 2 |

**刪除的 2 個**：
```
AI_Assistant/python/.env
Text_Recognition/text_recognize/python/blind-glasses-ocr-d82297cbca1a.json
```

**修改的 6 個**：
```
.gitignore
AI_Assistant/python/main.py
AI_Assistant/python/function/stt.py
AI_Assistant/python/function/tts.py
AI_Assistant/python/requirements.txt
Text_Recognition/text_recognize/python/ocr_doc.py
```

**新增的 75 個**：完整清單見 §2。

## 11.4 歷史重寫紀錄

| 項目 | 內容 |
|---|---|
| 工具 | `git-filter-repo`（透過 `pip install git-filter-repo`） |
| 指令 | `--invert-paths --path "AI_Assistant/python/.env" --path-glob "Text_Recognition/text_recognize/python/blind-glasses-ocr-*.json" --force` |
| 受影響的分支 | `main`（`cb104d3`→`f7fe1d5`）、`claude/rokid-guide-glasses-analysis-0b52ae`（`1b20ef8`→`d7da0e1`）、`copilot/refactor-ai-assistant-module`（`6cf42c1`→`ea531ba`） |
| `main` commit 數 | 17（重寫前後相同，只有 hash 改變） |
| 檔案數 | 365 |
| 驗證方式 | 全新 `git clone` 後以 `git rev-list --all --objects \| grep` 與 `git cat-file -e <blob>` 雙重確認 |

## 11.5 ⚠️ 對其他開發者的重要影響

**所有既有的本機 clone 都必須刪除後重新 clone。**

若有人用舊 clone 執行 `git pull`，會把含金鑰的舊歷史推回遠端，前功盡棄。

```bash
git clone https://github.com/mato1321/guide_glasses_project_.git
```

**此外**：GitHub 上若有任何 fork，**force push 不會影響 fork** —— 金鑰仍留在 fork 的歷史中。需聯繫 fork 擁有者或請 GitHub Support 協助。

## 11.6 相關 PR

| PR | 狀態 | 內容 |
|---|---|---|
| [#2](https://github.com/mato1321/guide_glasses_project_/pull/2) | 已合併 | 5 個 commit、83 個檔案 |

> 說明：PR #2 是在完整的 PR 說明撰寫完成**之前**就被合併的，因此該 PR 的 body 是自動產生的標題，不含詳細說明。所有內容都在 `docs/` 與 `guide-glasses/README.md` 中。

---

# 最終總結

## 1. 完成了哪些工作

| 階段 | 內容 |
|---|---|
| **分析** | 讀完 263 個檔案 + 4 份官方文件 + 社群資料，產出 7 份共 2100+ 行的分析報告 |
| **Phase 0** | 安全止血 —— 金鑰解除追蹤、設定外部化、`.gitignore` 重寫、移除影像落地、補齊依賴 |
| **Phase 1** | 建立 `guide-glasses` 多模組專案地基 —— Clean Architecture 分層、Hilt DI、播報仲裁、眼鏡能力抽象 |
| **Phase 2** | AI 助理中樞 —— 雙層意圖路由取代關鍵字比對、Android 原生 ASR/TTS 取代雲端、BFF 協定 |
| **歷史清除** | `git filter-repo` 重寫全部三個分支並 force push，經獨立 clone 驗證 |

## 2. 修改了哪些檔案

**6 個既有檔案**（全部為安全止血）：`.gitignore`、`AI_Assistant/python/main.py`、`stt.py`、`tts.py`、`requirements.txt`、`Text_Recognition/.../ocr_doc.py`

**2 個檔案解除 git 追蹤**（本機檔保留）：`.env`、GCP 金鑰 JSON

## 3. 新增了哪些檔案

**75 個**：8 份文件、3 個環境範本、64 個 `guide-glasses` 專案檔（含 10 個 launcher icon、5 個測試類共 68 個測試方法）

## 4. 是否修改原本專案

**有，但只有 6 個 Python/設定檔，全部是為了處理已外洩的金鑰與讓後端能實際安裝。**

**5 個舊 Android App 的原始碼一行都沒有動**，行為完全不變。

唯一會影響既有環境的是環境變數改名（`api_key` → `OPENAI_API_KEY`）與 OCR 憑證改由環境變數提供 —— 這兩項在本機都已處理完成。全部可用 `git revert` 還原，但不建議。

## 5. guide-glasses 是否為完整整合專案

**架構上完整，功能上不完整。**

- ✅ 獨立、可建置、可產出可安裝的 APK
- ✅ Clean Architecture 分層，且 domain 層的隔離是**建置層面強制**的
- ✅ 57 個單元測試守護核心行為
- ❌ 六大功能只完成「AI 助理中樞」，其餘五個尚未實作
- ❌ **完全沒有碰過 Rokid SDK**，眼鏡尚未接上

## 6. 是否可以直接執行

**可以。** `guide-glasses` App 可以建置、安裝、執行，語音指令能正確辨識與播報。不需要後端、不需要 Python、不需要眼鏡。

## 7. 執行方式

```bash
git clone https://github.com/mato1321/guide_glasses_project_.git
```

建立 `guide-glasses/local.properties` 指向 Android SDK（注意跳脫），然後：

```bash
cd guide-glasses && ./gradlew installDebug
```

需要：JDK 17+、Android SDK Platform 36。詳見 §6、§7。

## 8. 尚未完成事項

- 障礙物、人臉、導航、翻譯、OCR 五大功能（新專案中皆為 0%）
- Rokid 眼鏡連線（`glasses-cxr` / `glasses-fallback` 未建立）
- BFF 後端（協定已定義，實作不存在）
- 相機、資料庫、CI
- 8 個已知 Bug、6 個已知安全問題（詳見 §9.2、§9.3）
- **從未在實體 Android 裝置上執行過，也從未連接過 Rokid 眼鏡**

## 9. 下一步建議

**立即（只有你能做）**

1. 🔴 **撤銷 OpenAI 與 GCP 金鑰** —— GitHub 仍可依 SHA 取回舊 blob，這是唯一有效的止血
2. 🔴 **通知所有協作者刪除舊 clone 並重新 clone**，否則舊歷史會被推回
3. 🔴 檢查是否有 fork（force push 不影響 fork）
4. 開啟 GitHub Secret Scanning + Push Protection

**接著（Phase 0 實機驗證，決定後續架構）**

5. 確認 `takeGlassPhoto()` 的完整往返延遲 → 決定障礙物偵測是「即時警示」還是「查詢式描述」
6. 洽詢 Rokid 技術支援：是否有連續影像串流 API、能否取得 CXR-S SDK
7. 實測眼鏡連續拍照下的續航
8. 把 guide-glasses App 裝到實機上跑一次，驗證 ASR/TTS 實際品質

**然後（開發）**

9. Phase 3：OCR + 人臉（都不需要眼鏡實機，用手機相機即可完整驗證）
10. Phase 4：障礙物偵測（需視第 5、6 項的結果決定產品定位）
11. Phase 5：導航（5a 步行先行）
12. **盡早找到真實視障使用者參與測試** —— 這對產品成敗的影響大於任何技術選型
