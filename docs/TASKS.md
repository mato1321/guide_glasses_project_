# 待辦清單

> **完成一項就把 `[ ]` 改成 `[x]`。** 順手更新 [`STATUS.md`](STATUS.md) 的完成度。
> 怎麼做見 [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md)。

最後更新：2026-08-05 ｜ 已完成 **68 / 177**

---

## 🔴 A. 只有你能做（實機驗證）

**優先做這一組。** 這些答案會決定後面很多設計。

- [ ] A1 把 debug APK 裝到 Rokid Glasses
- [ ] A2 說「停」→ **確認有沒有聲音**（沒聲音先解這個）
- [ ] A3 說「測試感測器」→ 記下整句回答
- [ ] A4 說「測試相機」→ 記下解析度與毫秒數
- [ ] A5 對藥袋說「唸給我聽」→ 記錄 OCR 品質與斷句是否自然
- [ ] A6 說「下一段」→ 確認朗讀控制
- [ ] A7 對門牌說「這是哪裡」→ 確認只唸最大的字
- [ ] A8 設定 `faceEndpoint` 後說「這是誰」→ 記錄延遲
- [ ] A9 確認眼鏡有無 Google Play Services
- [ ] A10 列出 `LocationManager` 的 provider（決定導航架構）
- [ ] A11 測試邊充邊用是否可行
- [ ] A12 連續 2fps 偵測下的實際續航
- [ ] A13 測試能否設 Device Owner
- [ ] A14 相機水平視角校正（2 公尺實測比對）
- [ ] A15 說「唸給我聽」再說「翻成英文」→ 記錄語言包下載耗時
- [ ] A16 **確認眼鏡有沒有英文 TTS 語音資料** —— 沒有的話翻譯結果會用中文腔唸出來
- [ ] A17 記錄翻譯本身的延遲（語言包就緒後）

---

## 📦 B. 需要別人交付

- [ ] B1 Obstacle_Recognition：INT8 `.tflite`
- [ ] B2 Obstacle_Recognition：類別索引對照表（8 類的順序）
- [ ] B3 Obstacle_Recognition：輸入尺寸
- [ ] B4 Obstacle_Recognition：前處理規格（正規化方式、RGB/BGR）
- [ ] B5 Obstacle_Recognition：後處理規格（輸出張量、NMS 是否內建）
- [ ] B6 Obstacle_Recognition：驗證集 mAP
- [ ] B7 端側人臉模型 `.tflite`（🟡 有遠端替代，非阻塞）
- [x] ~~B8 導航架構決策（A / B / C 案）~~ → **已決策**：A 案（手機 companion
      只當 GPS 感測器 + 網路閘道，播報仲裁留在眼鏡）。定位來源抽象成
      `LocationProvider`，A10 的實測結果只決定挑哪個實作。見 `ARCHITECTURE.md` §5
- [ ] B9 TDX 金鑰（公車功能用）
- [ ] B10 Google Maps API 金鑰（導航用）
- [ ] B11 雲端帳號（BFF 用）

---

## ✅ C. 已完成的功能

### C1. 專案地基

- [x] 多模組 Gradle 專案
- [x] version catalog 集中管理版本
- [x] Hilt DI 接線
- [x] `core-domain` 純 Kotlin 約束（建置層面強制）
- [x] 無障礙 UI 基礎（大字、高對比、整片按鈕）
- [x] `AppResult` / `AppError` 型別化錯誤
- [ ] CI（GitHub Actions：build + lint + test）

### C2. 播報仲裁

- [x] 四級優先級（CRITICAL / USER_RESPONSE / NAVIGATION / AMBIENT）
- [x] 打斷與續播
- [x] `dedupeKey` 去抖動
- [x] `speakingToken` 防止遲到的回呼讓佇列跳號
- [x] `clearAtOrBelow` 保留高優先內容
- [x] 22 個單元測試

### C3. AI 助理中樞

- [x] `LocalCommandMatcher` 本地片語比對（<100ms、離線）
- [x] `IntentRouter` 雙層路由
- [x] `ConversationHistory` 有界對話歷史
- [x] `RemoteLlmIntentGateway` BFF 協定
- [x] `OfflineLlmIntentGateway` 離線降級
- [x] 無網路時播報人話並提示可用的離線指令
- [x] 31 個單元測試
- [ ] BFF 後端本身（見 §D5）
- [ ] 眼鏡 AI 實體鍵整合
- [ ] 喚醒詞

### C4. 語音辨識 / 合成

- [x] `AndroidSpeechRecognitionGateway`（串流、離線優先）
- [x] `AndroidTtsAnnouncer`（約 50ms、無障礙音訊通道）
- [x] 錯誤碼轉成人話
- [x] `onDone` 契約保證（否則佇列會卡死）
- [ ] 實機驗證（A2、A3）
- [ ] 依優先級調整語速（`applyRateFor` 已實作但未呼叫）

### C5. 相機

- [x] `CameraXFrameSource` 連續串流與單張擷取
- [x] 自管 LifecycleOwner（相機生命週期 = Flow 生命週期）
- [x] `FrameRateLimiter` 節流（在轉檔前丟棄）
- [x] `ResolutionPlanner` 依長邊縮放
- [x] JPEG / RGBA 雙格式輸出
- [x] 旋轉統一處理
- [x] `CameraSelfTestUseCase` 語音自我檢測
- [x] 22 個單元測試
- [ ] 實機驗證（A4）
- [ ] 多消費者共用同一條串流

### C6. OCR 朗讀

- [x] `MlKitTextRecognizer` 中文 bundled、離線
- [x] `SpeechSegmenter` 斷句（移植自 Text_Recognition，修了兩個 bug）
- [x] `ReadingSession` 朗讀進度與控制
- [x] 文件模式 / 招牌模式
- [x] 三層策略的前兩層
- [x] 39 個單元測試
- [ ] 雲端 fallback（需 BFF）
- [ ] Vision LLM 第三層
- [ ] 朗讀速度調整

### C7. 人臉辨識

- [x] `MlKitFaceDetector` bundled
- [x] `TfLiteFaceEmbedder` 端側（需模型檔）
- [x] `RemoteFaceIdentification` 遠端（沿用既有後端）
- [x] `CompositeFaceIdentification` 雙策略自動切換
- [x] `FaceMatcher` 三段式信心
- [x] `BearingResolver` 方位判定
- [x] `FaceDistanceEstimator` 距離估計
- [x] Room + Keystore AES/GCM 加密儲存
- [x] 註冊流程與同意提示
- [x] 33 個單元測試
- [ ] 多張照片註冊（提升穩定度）
- [ ] 相機視角校正（A14）
- [ ] 端側模型檔（B7）

### C8. IMU 動作感測

- [x] `SensorCapabilities` 能力探測
- [x] `WalkingStateDebouncer` 步態去抖動
- [x] `HeadingGuidance` 轉向指示（含跨零度最短轉向）
- [x] `StepDistanceEstimator` 步數距離
- [x] `CameraModeController` 相機模式決策
- [x] `AndroidMotionSensorGateway`
- [x] 語音自我檢測
- [x] 39 個單元測試
- [ ] 實機確認實際感測器（A3）
- [ ] **接上相機模式**（走路才開相機 —— 目前 Controller 寫好了但沒接）
- [ ] 陀螺儀漂移校正

---

## 🔨 D. 待實作的功能

### ~~D1. 翻譯~~ ✅ 已完成（2026-08-05）

- [x] domain：`Translator` 介面
- [x] domain：`TranslateUseCase` + Outcome
- [x] domain：單元測試（`TranslateUseCaseTest` 10 個、`TargetLanguageTest` 6 個）
- [x] 建立 `ai/ai-translate` 模組
- [x] `MlKitTranslator`（`com.google.mlkit:translate`）
- [x] 語言包下載進度回饋（`onPreparing` → 「正在準備英文翻譯，第一次使用需要下載」）
- [x] 無 BFF 時的預設目標語言（英文）
- [x] **目標語言本地解析** —— `TargetLanguage.fromSpoken`，封閉集合所以不需 BFF
- [x] ViewModel 分派 + DI 接線
- [x] **TTS 逐句切換語言** —— `Announcement.languageTag`，否則中文語音唸英文聽不懂
- [x] `./gradlew build` 通過，228 個測試全過

剩下的（非阻塞）：

- [ ] 接語言偵測（`com.google.mlkit:language-id`）取代目前的來源語言啟發式
- [ ] 長文分段翻譯（目前超過 1000 字截斷）
- [ ] 實機驗證：語言包下載時間、翻譯延遲、外語 TTS 是否存在（見 A15–A17）

### D2. 障礙物偵測（等 B1–B6）

**不等模型也能先做的（純 domain）：**

- [ ] domain：`ObstacleClass` 八類 enum（含 spoken 與 isHazard）
- [ ] domain：`Detection` 資料模型（相對座標）
- [ ] domain：`ObstacleDetector` 介面
- [ ] domain：`ObstacleDistanceEstimator`（已知寬度反推）
- [ ] domain：`DangerClassifier`（類別 × 距離 → 優先級）
- [ ] domain：`ObstacleAnnouncementComposer`（組播報字串）
- [ ] domain：播報去抖動策略（避免疲勞轟炸）
- [ ] domain：單元測試

**需要模型之後：**

- [ ] 建立 `ai/ai-vision` 模組（`noCompress += "tflite"`）
- [ ] `YoloDetector` TFLite + NNAPI/GPU delegate
- [ ] 前處理（依 B4 規格）
- [ ] 後處理與 NMS（依 B5 規格）
- [ ] 接上 `CameraModeController`
- [ ] 導引類與危險類的不同播報節奏
- [ ] ViewModel 分派（`DETECT_OBSTACLES` 已有 intent）
- [ ] 實機測試：偵測率、誤報率、端到端延遲

### D3. 導航（架構已定，可開工）

**不需 GPS 就能做的：**

- [ ] 純 IMU 的「跟著走」（維持方向 + 計步）
- [ ] `FollowHeadingUseCase` + 測試

**定位來源抽象（先做這個，A10 只影響挑哪個實作）：**

- [ ] domain：`LocationProvider` 介面（`isAvailable` / `accuracyMeters` / `locations()`）
- [ ] domain：導航狀態機 + 測試（不知道座標從哪來）
- [ ] `GlassesGpsLocationProvider` —— 若 A10 發現眼鏡有 GPS，**手機層就不需要了**
- [ ] `PhoneCompanionLocationProvider` —— 眼鏡確認無 GPS 時才做

**手機 companion（只在確認無 GPS 後才做，範圍嚴格限制）：**

- [ ] 連線層（WLAN socket 或 BLE GATT）
- [ ] 只傳座標，**不負責任何播報**（避免跨裝置蓋台）
- [ ] 斷線時眼鏡明確播報「手機連線中斷，導航暫停」
- [ ] `feature-navigation` 模組
- [ ] Google Directions API 整合
- [ ] 導航狀態機
- [ ] 轉彎播報（30m 預告 / 5m 執行）
- [ ] 偏離重規劃（閾值需實地調校）
- [ ] Foreground Service（`FOREGROUND_SERVICE_LOCATION`）
- [ ] 電池最佳化白名單引導

### D4. 公車整合（MVP，等 B9）

- [ ] TDX 會員申請與金鑰
- [ ] TDX 到站資料介接
- [ ] 到站倒數播報
- [ ] 進站提醒 + 提示音
- [ ] 上車人工確認（按鍵或語音）
- [ ] 車上站數倒數
- [ ] 下車提醒 + 提示音
- [ ] **路線規劃時優先選單一路線站牌**（把妥協變成特性）
- [ ] 明確標示這是 MVP，不是完成功能

### D5. BFF 後端（等 B11）

- [ ] Cloud Run 專案建立（asia-east1）
- [ ] `POST /route` 意圖解析（協定見 `AgentProtocol.kt`）
- [ ] Claude API 整合 + Function Calling
- [ ] Secret Manager 保管金鑰
- [ ] 節流與配額控管
- [ ] 快取（Maps 查詢）
- [ ] OCR 雲端 fallback 端點

---

## 🔗 E. 交叉整合

功能之間互相加值，目前只完成一條。

- [x] IMU → 相機模式（走路才開相機）※ Controller 完成，尚未接線
- [ ] **IMU → 方位修正**（轉頭之後「右前方」的意義會變）⭐ 建議早做
- [x] **OCR → 翻譯**（「唸給我聽」之後說「翻成英文」，不必再拍一次）
- [ ] OCR → 導航（辨識公車車頭號碼，成功率有限）
- [ ] 人臉 → 障礙物（「你認識的人在附近」）
- [ ] 障礙物 → 導航（偵測到斑馬線）

---

## 🧹 F. 技術債與優化

- [ ] F1 CI（GitHub Actions）
- [ ] F2 instrumented test（目前只有純 JVM 測試）
- [ ] F3 `Turbine` 已宣告但未使用
- [ ] F4 APK 51MB —— 若確認有 Play Services 可改 unbundled 版瘦身
- [ ] F5 `AndroidTtsAnnouncer.applyRateFor()` 已實作但未呼叫
- [ ] F6 多消費者共用同一條相機串流
- [ ] F7 人臉多張照片註冊
- [ ] F8 `core-ui` 共用無障礙元件模組
- [ ] F9 `core-network` 共用 HTTP 設定

---

## 📋 G. 交給組員的建議（不修改他們的資料夾）

以下是重新掃描時發現的問題，**已記錄未修改**。
詳細分析見 [`TECHNICAL_NOTES.md`](TECHNICAL_NOTES.md) §3。

- [ ] G1 通知 AI_Assistant / Text_Recognition 負責人：`.env` 鍵名改成 `OPENAI_API_KEY`
- [ ] G2 Face_Recognition：`detectIntervalMs = 5000L` 降到 500–1000ms
- [ ] G3 Face_Recognition：`HttpLoggingInterceptor.Level.BODY` 正式版改 `NONE`
- [ ] G4 Face_Recognition：移除 `cv2.imwrite("debug.jpg")`
- [ ] G5 Face_Recognition：`async def recognize` 改成 `def`（避免阻塞 event loop）
- [ ] G6 Face_Recognition：`ctx_id=0` 改 `-1`（provider 是 CPU，避免誤導）
- [ ] G7 AI_Assistant：同樣的 `Level.BODY` 問題
- [ ] G8 Obstacle_Recognition：`crosswalk`/`guidebrick`/`sidewalk` 建議改用 segmentation

---

## 統計

| 分類 | 已完成 | 總數 |
|---|---:|---:|
| A. 實機驗證 | 0 | 17 |
| B. 外部交付 | 0 | 11 |
| C. 已完成的功能 | 55 | 72 |
| D. 待實作 | 11 | 54 |
| E. 交叉整合 | 2 | 6 |
| F. 技術債 | 0 | 9 |
| G. 給組員的建議 | 0 | 8 |
| **合計** | **68** | **177** |

> 更新這份文件時，順手改一下這個表與檔頭的數字。
