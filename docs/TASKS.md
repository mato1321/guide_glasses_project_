# 待辦清單

> **完成一項就把 `[ ]` 改成 `[x]`。** 順手更新 [`STATUS.md`](STATUS.md) 的完成度。
> 怎麼做見 [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md)。

最後更新：2026-08-08 ｜ 已完成 **123 / 217**

---

## 🔴 A. 只有你能做（實機驗證）

**優先做這一組。** 這些答案會決定後面很多設計。

> 🟡 已在**小米 Android 手機**上跑過，找出三個真實 bug。
> 🔴 **2026-08-08 首次在 Rokid Glasses 執行 —— 語音完全不可用**，
> 所有需要「說話 → 聽回應」的項目目前都做不了。見 [`DEVICE_FINDINGS.md`](DEVICE_FINDINGS.md)。

- [x] ~~A1 把 debug APK 裝到 Rokid Glasses~~ ✅ 2026-08-08
- [x] ~~A2 說「停」→ 確認有沒有聲音~~ → 🔴 **沒有聲音**。TTS 綁定失敗，見 `DEVICE_FINDINGS.md` §4
- [x] ~~A3 感測器自我檢測~~ → 「可偵測走路、可追蹤轉向、**沒有電子羅盤**」
- [x] ~~A4 相機自我檢測~~ → **480×640、22KB、930ms**（比估計慢 6 倍）
- [x] ~~A5 OCR 管線~~ → 眼鏡上完整跑通（鏡頭前無字時正確回報）
- [ ] A5b 對真實藥袋測 OCR 品質與斷句
- [ ] A6 說「下一段」→ 確認朗讀控制
- [ ] A7 對門牌說「這是哪裡」→ 確認只唸最大的字
- [ ] A8 設定 `faceEndpoint` 後說「這是誰」→ 記錄延遲
- [x] ~~A9 確認眼鏡有無 Google Play Services~~ → 🔴 **沒有**（連 Play Store 都沒有）
- [x] ~~A10 列出 `LocationManager` 的 provider~~ → 🔴 **只有 passive/fused，沒有 gps**。
      宣告有 `location.gps` 特徵但無 provider、無 GNSS HAL → **確定走手機 companion**
- [ ] A11 測試邊充邊用是否可行
- [ ] A12 連續 2fps 偵測下的實際續航
- [ ] A13 測試能否設 Device Owner
- [ ] A14 相機水平視角校正（2 公尺實測比對）
- [ ] A15 說「唸給我聽」再說「翻成英文」→ 記錄語言包下載耗時
- [ ] A16 **確認眼鏡有沒有英文 TTS 語音資料** —— 沒有的話翻譯結果會用中文腔唸出來
- [ ] A17 記錄翻譯本身的延遲（語言包就緒後）
- [ ] A18 上傳幾個人的照片後說「同步人臉」→ **記下播報的相似度百分比**
      （低於 35% 代表模型或前處理不對，這是唯一能發現該問題的方式）
- [ ] A19 出門前說「出門前檢查」→ 確認回報內容正確
- [x] ~~A20 準備翻譯~~ → **語言包下載成功**（無 Play Services 也能用）
- [ ] A21 **拔掉網路後**完整測一輪四個功能（這才是實測的重點）
- [x] ~~A22 障礙物管線~~ → YOLO ONNX 載入並推論成功，2GB RAM 未 OOM
- [ ] A22b 對真實障礙物測偵測率、誤報率與端到端延遲

---

## 🔴 A0. 語音阻塞（最優先，擋住所有其他驗證）

- [x] ~~A0-1 確認 `tts_server_android` 缺什麼設定~~ → 🔴 **三處全空，無解**（`DEVICE_FINDINGS.md` §8）
- [ ] A0-2 問組員：他們的 App 在眼鏡上語音怎麼處理的
- [x] ~~A0-3 問組員 `com.example.gps`~~ → 組員說沒試過；**我直接用 adb 查出答案**（見 A10）
- [ ] A0-4 決定語音方案（`DEVICE_FINDINGS.md` §8 的 A–E）
- [x] ~~A0-5 Glass3 SDK 實測~~ → 🔴 探針實跑 `isReady()=false`，**確定不可用**
- [ ] A0-6 **向 Rokid 索取 Sprite 語音服務介面文件**（`com.rokid.os.sprite.tts.TTS_SERVICE`）⭐ 最有希望
- [ ] A0-7 試 sideload 一個不依賴 Play Services 的離線 TTS 引擎 APK
- [ ] A23 🔴 **解決 idle UID 擋相機** —— 需要 Foreground Service，
      否則使用者戴著眼鏡、螢幕關閉時障礙物偵測完全不能用
- [ ] A24 量測相機是否每次都要 930ms，還是只有首次（CameraX 初始化）

---

## 📦 B. 需要別人交付

- [x] ~~B1 Obstacle_Recognition：模型~~ → **已交付**，改用 ONNX（`obstacle_yolov8.onnx`）
- [x] ~~B2 類別索引對照表~~ → **已取得**。⚠️ 與 `ObstacleClass` 的 ordinal **有 6 處不一致**，
      一律按名稱對照，由 `ObstacleClassMappingTest` 鎖住
- [x] ~~B3 輸入尺寸~~ → 640×640 letterbox
- [x] ~~B4 前處理規格~~ → RGB、/255、NCHW
- [x] ~~B5 後處理規格~~ → 輸出 `[1, 44, 8400]`，只讀前 12 通道，NMS 自行實作
- [ ] B6 驗證集 mAP（仍未取得，但已用 12 張測試圖與 ultralytics 比對通過）
- [x] ~~B7 端側人臉模型~~ → **已解決**：用 InsightFace 自動下載的
      `buffalo_sc/w600k_mbf.onnx`，改走 ONNX Runtime 直接執行，不需轉檔
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
- [x] **小米手機驗證**：修正缺離線語音包時完全無法使用
- [x] 錯誤碼分類到 `SpeechCapability`，播報依類型分岔
- [ ] **Rokid 眼鏡**驗證（A2、A3）
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
- [x] 多張照片註冊（同步時自動取平均）
- [x] `OnnxFaceEmbedder` —— 直接跑 InsightFace 的 .onnx，免轉檔
- [x] `tools/face_enroll_server.py` —— 瀏覽器上傳照片＋標人名（零依賴）
- [x] `SyncPeopleUseCase` ＋ 語音「同步人臉」（10 個測試）
- [x] 同步時偵測模型是否正確（同一人照片相似度過低就警告）
- [ ] 相機視角校正（A14）
- [ ] 實機驗證同步流程（A18）

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
- [x] `./gradlew build` 通過（當時 228 個測試，現為 306）

剩下的（非阻塞）：

- [ ] 接語言偵測（`com.google.mlkit:language-id`）取代目前的來源語言啟發式
- [x] 支援口述內容直接翻譯（`SpokenTranslation`，仍不需 BFF）
- [x] 修正 `isReady()` 漏檢來源語言（ML Kit 以英文為樞紐，中翻英 100% 失敗）
- [ ] 長文分段翻譯（目前超過 1000 字截斷）
- [ ] 實機驗證：語言包下載時間、翻譯延遲、外語 TTS 是否存在（見 A15–A17）

### ~~D2. 障礙物偵測~~ ✅ 已接上（2026-08-07），待 Rokid 實測

**✅ 純 domain 部分已完成（2026-08-06，不需模型）：**

- [x] domain：`ObstacleClass` 八類 enum（含 spoken、realWidthMeters、Kind）
- [x] domain：`Detection` 資料模型（相對座標）
- [x] domain：`ObstacleDetector` 介面
- [x] domain：`ObstacleDistanceEstimator`（已知寬度反推）
- [x] domain：`DangerClassifier`（類別 × 距離 → 優先級）
- [x] domain：`ObstacleAnnouncementComposer`（組播報字串、最多唸三個）
- [x] domain：`ObstacleDebouncer`（同一物體 5 秒內只播一次）
- [x] domain：單元測試（22 個）

> ⚠️ 八類的**順序與名稱必須與模型交付時的類別索引校對**（B2）。
> 索引對錯不會報錯，只會把車唸成盲磚。

**需要模型之後：**

- [x] 建立 `ai/ai-vision` 模組（`noCompress += "onnx"`）
- [x] `YoloObstacleDetector` —— ONNX Runtime（不轉 tflite，理由同 ai-face）
- [x] 前處理：letterbox + RGB + /255 + NCHW
- [x] 後處理與 NMS（以 Python 複刻後與 ultralytics 比對，框差 <6px）
- [ ] 接上 `CameraModeController`（走路才開相機，眼鏡續航很吃這個）
- [ ] 導引類與危險類的不同播報節奏
- [x] ViewModel 分派 + DI 接線
- [ ] **Rokid 眼鏡**實機測試：偵測率、誤報率、端到端延遲

### D3. 導航（架構已定，可開工）

**不需 GPS 就能做的：**

- [ ] 純 IMU 的「跟著走」（維持方向 + 計步）
- [ ] `FollowHeadingUseCase` + 測試

**✅ 定位抽象與幾何已完成（2026-08-06）：**

- [x] domain：`LocationProvider` 介面（`isAvailable` / `accuracyMeters` / `locations()`）
- [x] domain：`Coordinate` 資料模型（含精度）
- [x] domain：`Geo` 球面幾何 —— Haversine 距離、方位角、**跨零度最短轉向**、口語轉向
- [x] domain：單元測試（19 個）
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
- [ ] F4 APK 95MB —— 若確認有 Play Services 可改 unbundled ML Kit 瘦身；
      ONNX Runtime 18MB 與人臉模型 13MB 是端側辨識的固定成本
- [ ] F5 `AndroidTtsAnnouncer.applyRateFor()` 已實作但未呼叫
- [ ] F6 多消費者共用同一條相機串流
- [x] ~~F7 人臉多張照片註冊~~（同步時自動取平均）
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
| A. 實機驗證 | 13 | 37 |
| B. 外部交付 | 7 | 11 |
| C. 已完成的功能 | 62 | 76 |
| D. 待實作 | 37 | 71 |
| E. 交叉整合 | 2 | 6 |
| F. 技術債 | 1 | 9 |
| G. 給組員的建議 | 0 | 8 |
| **合計** | **123** | **217** |

> 更新這份文件時，順手改一下這個表與檔頭的數字。
