# 技術筆記

硬體限制、SDK 能力邊界、技術選型的理由，以及幾個具體問題的分析。

---

## 1. Rokid SDK：為什麼幾乎用不到

App 跑在眼鏡上，標準 Android API 就夠了。CXR SDK 提供的是「與 Rokid 官方 App
生態整合」，不是硬體存取。

| SDK | 定位 | 對本專案 |
|---|---|---|
| **CXR-M** | 手機端 companion | ❌ App 不跑在手機上。官方資料明載**不提供 ASR / AI / TTS 引擎** |
| **CXR-S** | 眼鏡與手機的橋接 | ❌ 同上 |
| **CXR-L** | 眼鏡端獨立 App | 🟡 `takePhoto` 只有單張（文件明載 "No continuous camera stream API exists"），CameraX 比它強。**唯一值得評估的是 `startAudioStream()`** —— 可能拿得到 4 麥克風陣列的降噪音訊 |
| Glass 3 企業版 SDK | 另一條產品線 | ❌ 不可用，不再討論 |

`AI_Assistant/` 宣告了 `com.rokid.cxr:client-m` 依賴，但程式碼中零呼叫 ——
不是遺漏，是根本不需要。

### CXR-L API（社群反編譯文件，非官方，使用前需實測）

`CXRLink` 透過 AIDL 綁定 `com.rokid.sprite.aiapp`：
`connect(token)` / `takePhoto(w, h, quality)` / `startAudioStream(codec)` /
`openCustomView(json)` / `setIcons(json)`。需 `AuthorizationHelper` 驗證，
且 `com.rokid.sprite.aiapp` 版本 ≥ 100000。

---

## 2. 硬體限制

| 項目 | 規格 | 影響 |
|---|---|---|
| SoC | Snapdragon AR1 | 有 NPU |
| **RAM** | **2 GB** | 端側模型總量 < 400MB；不同時載入多個大模型 |
| 相機 | 12 MP | 充足 |
| 顯示 | 雙目單色綠 Micro-LED 480×398、23° FOV | **對全盲使用者無意義**，HUD 列為低優先 |
| **電池** | **210 mAh，約 4 小時** | 開相機後可能 <1.5 小時 |
| **GPS** | **沒有** | 擋住導航，見 [`ROADMAP.md`](ROADMAP.md) §3 |
| IMU | 有（6 軸或 9 軸**待實測**） | 相機省電、方位修正、步態輔助 |
| 連線 | Wi-Fi 6、BT 5.3 | |
| 重量 | 49 g | 長時間配戴的最大優勢 |

### 電池與外接供電

210mAh 是物理限制，但可以外接。

| 方案 | 評估 |
|---|---|
| **口袋行動電源 + 短線** | ⭐ 日常主力。重量在口袋不在頭上，49g 的優勢不該被頸掛破壞 |
| 腰包 / 斜背包 | ⭐ 長時間外出 |
| 頸掛式行充 | 🟡 線最短但頸部負重 |
| 充電盒輪替 | ❌ 會中斷使用 —— 導盲系統不能中斷 |

線材建議：**磁吸接頭**（勾到會脫落而不是扯到頭）、扁線、60–80cm、衣夾固定。

**待驗證**：邊充邊用是否可行（若不行，整個外接方案不成立）、充電時發熱、
連續 2fps 偵測下的實際續航、充電接口位置。

---

## 3. Face_Recognition 延遲分析

團隊回報 `Face_Recognition/` 延遲很高，懷疑是 FastAPI。

**結論：不是 FastAPI 這個 framework 的錯**（它的路由開銷是 1–5ms 等級）。
但後端確實有一個 FastAPI **用法**上的真 bug。

| # | 來源 | 位置 | 估時 | 修改難度 |
|---|---|---|---|---|
| L1 | 輪詢間隔 5 秒 | `MainActivity.kt:46` | **平均 2500ms** | 改一個常數 |
| L2 | `HttpLoggingInterceptor.Level.BODY` | `ApiClient.kt:18` | 50–300ms | 改一行 |
| L3 | 每次請求都 `cv2.imwrite("debug.jpg")` | `main.py:42-45` | 10–50ms | 刪三行 |
| L4 | `async def` 內呼叫阻塞的 `engine.recognize()` | `main.py:34` | 併發時嚴重 | `async def` → `def` |
| L5 | `rgbaToJpeg` 慢速路徑逐像素迴圈 | `MainActivity.kt:168-189` | 100–400ms | 改用 `toBitmap()` |
| L6 | InsightFace `buffalo_l` CPU 推論 | `face_engine.py:13-17` | 100–200ms | 換 `buffalo_s` 或加 GPU |

**L2 值得特別說**：`Level.BODY` 會呼叫 `requestBody.writeTo()` 把整個 multipart
JPEG 寫進 buffer 記錄，**然後才真正上傳一次** —— 影像被序列化兩次。
`AI_Assistant` 的 `ApiClient.kt` 也有同樣問題。

**L4 是「懷疑 FastAPI」中唯一成立的部分** —— 問題不在 FastAPI，在於
`async def` 裡放了 CPU 密集的阻塞呼叫，會卡住 event loop。

> 依 [OkHttp #4076](https://github.com/square/okhttp/issues/4076)、
> [#6312](https://github.com/square/okhttp/issues/6312)、
> [Adventures in Tracking Upload Progress](https://getstream.io/blog/android-upload-progress/)

### 建議：先量測再優化

```kotlin
val tCapture = SystemClock.elapsedRealtime()
val tEncoded = SystemClock.elapsedRealtime()
val tResponse = SystemClock.elapsedRealtime()
Log.d("LAT", "encode=${tEncoded-tCapture} network+infer=${tResponse-tEncoded}")
```

```python
t0 = time.perf_counter()
results = engine.recognize(image)
print(f"infer={1000*(time.perf_counter()-t0):.0f}ms")
```

### guide-glasses 的做法

`RemoteFaceIdentification` 已實作「端側偵測 + 遠端辨識」，**沿用同一個
`/recognize` 端點，不需要改後端**：

| | Face_Recognition 現行 | guide-glasses |
|---|---|---|
| 觸發 | 固定每 5 秒 | 使用者說「這是誰」時 |
| 上傳 | 整張畫面 ~40KB | **裁切後的臉 ~3-8KB** |
| 沒有人臉時 | 照樣上傳 | **完全不上傳** |
| logging | `Level.BODY` | 無 |

> 以上問題**未在 `Face_Recognition/` 中修正** —— 那是組員的工作區。
> 請該模組負責人自行處理。

---

## 4. 技術選型

| 領域 | 選擇 | 理由 |
|---|---|---|
| **相機** | CameraX `ImageAnalysis` | App 跑在眼鏡上，標準 API 直接給 30fps。實際用 2–5fps（步行 1.4m/s 之下，5fps 等於每 28cm 判斷一次） |
| **OCR** | ML Kit v2 中文 **bundled** | 約 100ms、免費、離線。選 bundled 而非 play-services 版：**眼鏡是否預裝 Play Services 無法確認**。代價是 APK 大約 +20MB |
| **人臉偵測** | ML Kit Face Detection bundled | 同上理由 |
| **人臉特徵** | MobileFaceNet TFLite（端側）／ InsightFace（遠端） | 兩條並存，端側優先。端側需模型檔 |
| **人臉資料庫** | Room + Keystore AES/GCM 加密 | 生物特徵不該以明文躺在 SQLite。**絕不上雲** |
| **ASR** | Android `SpeechRecognizer` | 串流式、免費、離線優先。取代「錄整段上傳 Whisper」的 3–5 秒 |
| **TTS** | Android `TextToSpeech` | 約 50ms。取代 OpenAI tts-1 的 2–3 秒 —— 以步行速度算，2 秒延遲的「前方有車」等於車已經到了 |
| **意圖路由** | 本地片語 + LLM Function Calling | 「停」不能等雲端 |
| **物件偵測** | YOLO（團隊訓練中，8 類） | 見下 |

### 障礙物模型（Obstacle_Recognition 訓練中）

8 類：`bicycle`、`car`、`crosswalk`、`guidebrick`、`motorcycle`、`obstacle`、
`people`、`sidewalk`。

**設計得不錯** —— 同時涵蓋危險物與**導引物**（斑馬線、導盲磚、人行道）。
導盲不只是避開東西，更重要的是「告訴我該往哪走」，那正是導盲杖給不了的。

**一個建議**：`crosswalk` / `guidebrick` / `sidewalk` 是長條狀地面區域，
bounding box 表達力不足，建議改用 segmentation。

**整合需要的交付物**：INT8 `.tflite`、類別索引對照表、輸入尺寸、
前處理規格（正規化方式、RGB/BGR）、後處理規格（輸出張量格式、NMS 是否內建）、
驗證集 mAP。

### 距離估計

眼鏡是單目相機、無深度感測器。用**針孔相機模型 + 已知物體尺寸反推**：
純幾何、零額外算力、誤差 20–30%，對導盲場景足夠。
不用 Depth Anything —— 它給的是相對深度不是絕對距離，而且多一個模型多 80ms。

**需要校正**：`FaceDistanceEstimator` 預設水平視角 66 度是手機廣角鏡的概略值，
**Rokid Glasses 的實際視角官方未載明**。校正方式見
[`../guide-glasses/DOCUMENTATION.md`](../guide-glasses/DOCUMENTATION.md) §6.4。

---

## 5. Android 14+ 前景服務保活

眼鏡是**專用裝置**不是日常手機，這改變了最佳解。

| 方案 | 適用 | 說明 |
|---|---|---|
| **正確宣告 FGS Type** | ✅ 必做 | `camera｜microphone｜location` + 對應執行時權限 |
| **電池最佳化白名單** | ✅ 必做 | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。官方認可的背景啟動例外 |
| **前景通知** | ✅ 必做 | FGS 本來就強制要有 |
| **Device Owner + Lock Task** | ⭐ **最值得投資** | 眼鏡是專用裝置，設成單一用途沒有損失，卻能一次解決保活、誤觸、開機自啟。`adb shell dpm set-device-owner` |
| 開機自啟 | 🟡 建議 | 注意 Android 14 起 `camera` 型別不可從 `BOOT_COMPLETED` 啟動 |
| WorkManager | 🟡 部分 | 最短間隔 15 分鐘，只適合人臉庫同步這類非即時工作 |
| Accessibility Service | 🟠 謹慎 | 本專案確實是無障礙用途，理由正當，但複雜度高。除非上述都不夠否則不用 |
| 系統簽章 | ❌ | 需與 Rokid 商業合作 |

> 依 [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)、
> [Restrictions on starting a FGS from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

**待驗證**：YodaOS 是否允許設 Device Owner、是否有額外的省電殺進程機制、
電池最佳化白名單 UI 是否存在。

---

## 6. Edge / Cloud 配置原則

| 原則 | 說明 |
|---|---|
| **安全相關一律 Edge** | 障礙物警示延遲預算 <300ms，雲端往返做不到；斷網不能等於失明 |
| **生物特徵絕不上雲** | 人臉是特種個資。上雲帶來法遵責任、離線失效、延遲增加 —— 三個缺點零個優點 |
| **語音一律本機** | 50ms vs 2–3 秒 |
| **只有非做不可的才上雲** | LLM、地圖路網、公車即時資料 |
| **金鑰只在 BFF** | App 內嵌金鑰必然會被反編譯取出 |

### 雲端供應商

| | 評估 |
|---|---|
| **Cloud Run**（asia-east1） | ⭐ BFF 首選。可縮到 0、無冷啟動問題、容器化好測 |
| Google Maps Platform | ⭐ 必用，無替代品 |
| Firebase Auth | ⭐ 身分驗證 |
| Cloud Vision | 🟡 OCR 第二層 fallback |
| Supabase | 🟡 最近區域在新加坡，延遲較高 |
| Azure / AWS | 🟡 已用 Google 生態則無必要 |

**成本估算**（100 使用者 × 每天 2 小時）：約 US$165–295/月，
其中 Google Maps 占最大宗，必須做快取與節流。

---

## 7. 待實機驗證清單

**這些都是未知，不要當成已知。** App 內建「測試相機」「測試感測器」兩個
語音指令可直接確認前兩項。

| # | 項目 | 影響 |
|---|---|---|
| V1 | 眼鏡是否有 `SpeechRecognizer` | **沒有的話語音助理整個不能用** |
| V2 | 是否有中文 TTS 語音資料 | 沒有就完全沒聲音 |
| V3 | 實際有哪些感測器（6 軸還 9 軸） | 決定導航與省電設計 |
| V4 | CameraX 可用解析度與幀率 | 決定各功能的擷取參數 |
| V5 | 相機水平視角 | 距離估計的準確度 |
| V6 | `LocationManager` 有哪些 provider | 決定導航架構 |
| V7 | 是否預裝 Google Play Services | 決定能否改用 unbundled 版瘦身 APK |
| V8 | 邊充邊用是否可行 | 決定外接供電方案是否成立 |
| V9 | 連續 2fps 下的實際續航 | 決定省電策略 |
| V10 | 能否設 Device Owner | 決定保活方案 |
| V11 | YodaOS 是否有額外省電殺進程 | 同上 |
| V12 | 開發者模式與 adb 連線方式 | 部署流程 |
