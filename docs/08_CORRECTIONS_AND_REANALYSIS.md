# 第八部：前次分析的修正與重新分析

> 日期：2026-08-05
> 依據：使用者提供的實際專案狀況 + 重新掃描全部程式碼 + 重新查證官方與社群資料
> **本文件的結論優先於 `01`～`07` 中相衝突的部分。**

---

## 0. 修正摘要

| # | 前次分析的說法 | 實際狀況 | 嚴重度 |
|---|---|---|---|
| C1 | 「5 個獨立專案是結構性問題」 | **刻意如此** —— 五位成員各自開發自己的功能，之後才整合 | 🔴 結論錯誤 |
| C2 | 「專案沒有真的用到 Rokid 眼鏡，實際跑在手機上」 | **錯。Face_Recognition 已經在眼鏡上實機運作**：編譯 APK → 安裝到 Rokid Glasses → 即時人臉辨識 → 語音播報 | 🔴 事實錯誤 |
| C3 | 「Face_Recognition 與 AI_Assistant 90% 重複，應刪除」 | **是整合關係不是重複開發** —— AI_Assistant 已整合 Face_Recognition | 🔴 結論錯誤 |
| C4 | 「沒有連續影像串流 API 是全案最大技術風險」 | **風險大幅降低。** App 跑在眼鏡上，直接用 Android CameraX 即可，已被 Face_Recognition 實證 | 🔴 風險評估錯誤 |
| C5 | 「210mAh/4小時是物理限制，無解」 | 可搭配行動電源，有多種配戴方案 | 🟠 過度悲觀 |
| C6 | 「Android 14+ 前景服務有限制」（只說限制不給解法） | 有完整的解法組合 | 🟠 分析不完整 |
| C7 | 「公車辨識沒有可靠解法」 | 團隊已有 MVP 策略（詢問司機 / 挑單一路線站牌） | 🟠 未反映團隊決策 |
| C8 | 反覆提醒金鑰外洩 | **金鑰已全部重新產生、舊的已撤銷** | 🟢 已解決，不再提 |
| C9 | 討論 B 線（Glass 3 企業版 SDK） | **B 線不可用，不再討論** | 🟢 範圍調整 |

**另外補充：CXR-L SDK 的完整能力（前次未分析）見 §3。**

---

## 1. 【C2 / C4 修正】Rokid Glasses 就是 Android 裝置 —— 這改變了整個架構前提

### 1.1 前次錯在哪

前次分析從 `build.gradle.kts` 裡的 `com.rokid.cxr:client-m`（**手機端** SDK）依賴，推論這是「手機當大腦、眼鏡當終端」的 CXR-M 架構，於是把「CXR-M 沒有連續影像串流 API」當成全案最大風險。

**這個推論是錯的。** 實際上：

- `com.rokid.cxr:client-m` 在程式碼中確實**零呼叫**（這點沒錯）
- 但那不代表沒用到眼鏡 —— 而是**根本不需要它**
- Rokid Glasses 執行 YodaOS-Sprite（基於 Android 12 / API 32），**APK 可直接安裝並執行**
- `Face_Recognition` 用的是標準 `androidx.camera`（CameraX）+ `CameraSelector.DEFAULT_BACK_CAMERA`，**在眼鏡上直接取得相機**

### 1.2 正確的架構前提

```
【錯誤（前次）】                    【正確（本次）】

手機 App ──CXR-M──> 眼鏡            眼鏡上的 Android App
  ↑                                    ↓ CameraX（標準 Android API）
大腦在手機                            眼鏡自己的相機
眼鏡只是終端                          ↓
                                    需要重運算時才送遠端
```

**眼鏡是一台會執行你的 APK 的 Android 12 裝置。** 標準 Android API 全部可用：

| 能力 | 取得方式 | 是否已實證 |
|---|---|---|
| 連續相機影像 | `androidx.camera`（CameraX）`ImageAnalysis` | ✅ Face_Recognition 已在眼鏡上運作 |
| 麥克風 | `AudioRecord` / `SpeechRecognizer` | 無法確認（未實測） |
| TTS | `android.speech.tts.TextToSpeech` | ✅ Face_Recognition 已在眼鏡上播報 |
| 網路 | 標準 `OkHttp` / `Retrofit` | ✅ 已實證（呼叫 FastAPI） |
| 感測器 | `SensorManager` | 無法確認（未實測） |
| GPS | `FusedLocationProvider` | 無法確認（眼鏡規格中未見 GPS 模組，**待驗證**） |

### 1.3 那 CXR SDK 還有什麼用？

CXR SDK 提供的是**標準 Android API 給不了的東西** —— 與 Rokid 官方 App 生態的整合：

| SDK | 定位 | 對本專案的價值 |
|---|---|---|
| **CXR-M** | 手機端 companion App | 🟡 低。本專案 App 跑在眼鏡上，不需要手機當中介 |
| **CXR-S** | 眼鏡端與手機通訊的橋接 | 🟡 低。同上 |
| **CXR-L** | **眼鏡端獨立 App**，取代 Rokid 預設功能 | 🟠 **中，見 §3** |

**結論：本專案應以「標準 Android App 跑在眼鏡上」為主軸，CXR-L 為選用增強。**

---

## 2. 【重點】Face_Recognition 延遲問題的完整分析

### 2.1 直接回答：不是 FastAPI 的錯（但有一個 FastAPI 用法上的真 Bug）

FastAPI 這個 web framework 本身的路由與序列化開銷是 **1–5 毫秒**等級，不可能是使用者感受到的延遲來源。

但你的直覺方向對了一半 —— **後端確實有一個嚴重的 FastAPI 使用錯誤**，見 L4。

### 2.2 延遲來源逐項拆解（依實際程式碼）

以下全部以 `Face_Recognition/` 的實際程式碼為依據。

---

#### 🔴 L1：輪詢間隔 5 秒 —— **最大的感知延遲來源**

`Face_Recognition/android/.../MainActivity.kt:46`

```kotlin
private val detectIntervalMs = 5000L
```

即使後端處理是零延遲，使用者從「有人走到面前」到「聽到名字」平均要等 **2.5 秒**，最壞 **5 秒**。

（`AI_Assistant` 的 `FaceRecognitionFragment.kt:60` 是 `3000L`，稍好但同樣的問題。）

**這一項很可能占了使用者感知延遲的一半以上，而且改一行就能改善。**

---

#### 🔴 L2：`HttpLoggingInterceptor.Level.BODY` —— **請求體被寫兩次**

`Face_Recognition/android/.../ApiClient.kt:17-19`

```kotlin
private val logging = HttpLoggingInterceptor().apply {
    level = HttpLoggingInterceptor.Level.BODY
}
```

`Level.BODY` 會呼叫 `requestBody.writeTo()` 把整個 multipart JPEG 寫進 buffer 做記錄，**然後才真正上傳一次**。也就是說：

- 影像被序列化兩次
- 二進位內容被轉成字串塞進 logcat
- 額外的記憶體配置與 GC 壓力

> 依據：[OkHttp issue #4076](https://github.com/square/okhttp/issues/4076)、[OkHttp issue #6312](https://github.com/square/okhttp/issues/6312)、[Adventures in Tracking Upload Progress With OkHttp](https://getstream.io/blog/android-upload-progress/) —— 該文明確描述進度會「從 0% 到 100% 跑兩次」。

**這在正式版本中必須關掉。** 這是一個典型的「開發時加上去，忘了拿掉」的效能殺手。
（`AI_Assistant/network/ApiClient.kt:14-18` 有同樣的問題。）

---

#### 🟠 L3：`rgbaToJpeg()` 的慢速路徑 —— 逐像素 Kotlin 迴圈

`Face_Recognition/android/.../MainActivity.kt:168-189`

```kotlin
if (rowStride == width * pixelStride) {
    buffer.rewind()
    bitmap.copyPixelsFromBuffer(buffer)     // 快
} else {
    for (y in 0 until height) {              // 慢：480 次
        for (x in 0 until width) {           // 慢：640 次 → 共 307,200 次
            ...
        }
        bitmap.setPixels(pixels, 0, width, 0, y, width, 1)   // 480 次呼叫
    }
}
```

當 `rowStride != width * pixelStride`（相機有 padding 時很常見）就會走慢速路徑。30 萬次 Kotlin 迴圈 + 480 次 `setPixels`，在中低階 SoC 上可能是 **100–400 毫秒**。

**改善**：CameraX `camera-core` 1.3+ 已內建 `ImageProxy.toBitmap()`，有硬體加速。

---

#### 🔴 L4：後端在 `async def` 裡呼叫同步阻塞函式 —— **真正的 FastAPI 使用錯誤**

`Face_Recognition/Python/main.py:33-51`

```python
@app.post("/recognize")
async def recognize(file: UploadFile = File(...)):
    ...
    results = engine.recognize(image)      # ← 同步、CPU 密集、會阻塞 event loop
```

`engine.recognize()` 是純 CPU 的 ONNX 推論，卻直接在 `async def` 中呼叫。這會**阻塞 uvicorn 的 event loop**，導致：

- 同時只能處理一個請求
- 眼鏡若同時有其他請求（例如 AI_Assistant 整合版的 chat + face），會排隊
- 健康檢查等輕量請求也被卡住

**修法**：把 endpoint 改成 `def`（同步），FastAPI 會自動丟到 threadpool；或用 `run_in_executor`。

> **這是你「懷疑 FastAPI」直覺中唯一成立的部分 —— 但問題不在 FastAPI，在於 `async def` 裡放了阻塞呼叫。**

---

#### 🔴 L5：`cv2.imwrite("debug.jpg")` —— 每個請求都同步寫磁碟

`Face_Recognition/Python/main.py:42-45`

```python
# debug - 儲存收到的圖片到本地，方便檢查
import cv2
cv2.imwrite("debug.jpg", image)
print(f"Debug image saved to debug.jpg")
```

三個問題疊在一起：

1. **同步磁碟 I/O** 在請求路徑中（且在阻塞 event loop 的 `async def` 內，見 L4）
2. `import cv2` 寫在函式內（有 module cache，成本低，但是壞習慣）
3. `print()` 在高頻請求下也有成本

> 註：`AI_Assistant/python/main.py` 的同一段已在 Phase 0 移除，**但 `Face_Recognition/Python/main.py` 仍然存在**。依照「不修改其他組員資料夾」的原則，我沒有動它 —— 請 Face_Recognition 的負責人自行移除。

---

#### 🟠 L6：InsightFace `buffalo_l` 在 CPU 上跑 `det_size=(640,640)`

`Face_Recognition/Python/face_engine.py:13-17`

```python
self.app = FaceAnalysis(name="buffalo_l", providers=["CPUExecutionProvider"])
self.app.prepare(ctx_id=0, det_size=(640, 640))
```

`buffalo_l` 是 InsightFace 最大的模型包。CPU 推論大約 **100–200 毫秒/張**（依 CPU 而定）。

> 依據：[InsightFace 效能討論](https://github.com/deepinsight/insightface/issues/2530)

**改善選項**（依效益排序）：

| 方案 | 預期改善 | 代價 |
|---|---|---|
| 換 `buffalo_s`（小模型） | 快 2–3 倍 | 準確率略降，對「認識的 10–50 人」影響很小 |
| `det_size` 降到 `(320, 320)` | 快約 4 倍 | 遠距離小臉偵測率下降 |
| 加 GPU（`CUDAExecutionProvider`） | 快 5–10 倍 | 需要有 GPU 的伺服器 |
| **改跑在眼鏡端**（MediaPipe + MobileFaceNet TFLite） | **消除整段網路延遲** | 需要重寫，且受 2GB RAM 限制 |

---

#### 🟡 L7：`ctx_id=0` 語意混淆

`prepare(ctx_id=0)` 在 InsightFace 中代表「使用 GPU 0」。但 provider 明確指定了 `CPUExecutionProvider`，所以實際仍走 CPU。這不會造成錯誤，但**容易誤導後續維護者以為在用 GPU**。CPU 應寫 `ctx_id=-1`。

---

#### 🟡 L8：`recognize()` 是 O(n) 線性掃描

`face_engine.py:133` 對資料庫中每個人做一次 cosine similarity。目前 5 人無感，但人數上百就會顯現。改用 NumPy 矩陣運算一次算完即可。

---

#### 🟡 L9：Wi-Fi 傳輸與後端位置

`ApiClient.kt:15` 是 `http://172.20.10.3:8000` —— 這是 **iPhone 個人熱點的典型網段**。若後端跑在筆電、筆電與眼鏡都連同一個手機熱點，那麼：

- 頻寬受手機熱點限制
- 延遲受熱點品質影響
- 一張 640×480 quality 75 的 JPEG 約 30–60 KB，理論上 <50ms，但熱點壅塞時可能到數百毫秒

**無法確認**實際網路延遲，需要實測（見 §2.4）。

---

#### 🟡 L10：TTS 播報冷卻 10 秒

`MainActivity.kt:57` 的 `announceCooldown = 10000L`。這**不是延遲問題**，但會造成「明明看到人了卻沒播報」的體感，容易被誤認為延遲。同一人 10 秒內只播一次是合理設計，但值得知道。

---

### 2.3 延遲來源估算總表

| # | 來源 | 估計耗時 | 修改難度 | 建議優先序 |
|---|---|---|---|---|
| L1 | 輪詢間隔 5 秒 | **平均 2500ms** | ⭐ 極簡（改一個常數） | **1** |
| L2 | HttpLogging BODY 雙寫 | 50–300ms | ⭐ 極簡（改一行） | **2** |
| L5 | debug.jpg 磁碟寫入 | 10–50ms | ⭐ 極簡（刪三行） | **3** |
| L4 | async def 阻塞 event loop | 併發時嚴重 | ⭐⭐ 簡單（`async def` → `def`） | **4** |
| L3 | rgbaToJpeg 慢速路徑 | 100–400ms | ⭐⭐ 中（改用 `toBitmap()`） | **5** |
| L6 | InsightFace CPU 推論 | 100–200ms | ⭐⭐⭐ 中（換模型或加 GPU） | **6** |
| L9 | 網路傳輸 | 無法確認 | ⭐⭐⭐ 需先量測 | 先量測 |
| L8 | O(n) 線性掃描 | <1ms（目前 5 人） | ⭐⭐ | 低 |

**粗估：只做 L1 + L2 + L5 三項（都是極簡改動），感知延遲可能從數秒降到 1 秒以內。**

### 2.4 建議的量測方法（先量測再優化）

在動手改之前，先確認瓶頸真的在哪。建議在四個點打時間戳：

```kotlin
// Android 端
val tCapture = SystemClock.elapsedRealtime()      // 擷取到 frame
val tEncoded = SystemClock.elapsedRealtime()      // JPEG 編碼完成
val tResponse = SystemClock.elapsedRealtime()     // 收到回應
Log.d("LAT", "encode=${tEncoded-tCapture} network+infer=${tResponse-tEncoded}")
```

```python
# 後端
import time
t0 = time.perf_counter()
results = engine.recognize(image)
print(f"infer={1000*(time.perf_counter()-t0):.0f}ms")
```

有了這三個數字，就能明確分辨是編碼、網路、還是推論的問題，不需要再推測。

### 2.5 架構層級的改善方案（中長期）

| 方案 | 說明 | 延遲改善 | 難度 |
|---|---|---|---|
| **A. 端側人臉辨識** | 眼鏡上跑 MediaPipe FaceDetector + MobileFaceNet TFLite | **消除全部網路延遲**，端到端可到 50–100ms | 高（受 2GB RAM 限制，需實測） |
| **B. 兩段式：端側偵測 + 遠端辨識** | 眼鏡端用 MediaPipe 偵測是否有臉（~5ms），**有臉才上傳**，且只上傳裁切後的臉部區域（幾 KB） | 大幅減少無效上傳與傳輸量 | 中 ⭐ **建議先做這個** |
| **C. 持久連線** | 改用 WebSocket 取代每次 HTTP，省去連線建立 | 每次省 20–100ms | 中 |
| **D. 事件驅動取代輪詢** | 端側偵測到「畫面中出現新的人臉」才觸發，取代固定間隔 | 消除 L1 的等待 | 中 |

**建議路線**：先做 §2.3 的 L1/L2/L5（一小時內可完成），量測後再決定要不要走 B/D。

---

## 3. CXR-L SDK 能力分析（本次新增）

> 依據：[rokid-docs 社群文件 cxr-l/api-reference.md](https://github.com/buildwithfenna/rokid-docs)
> ⚠️ 這是**社群維護的反編譯文件**，非官方。所有 API 使用前需實機驗證。

### 3.1 CXR-L 是什麼

給**跑在眼鏡上的獨立 App** 使用的 SDK，用來取代 Rokid 預設功能。透過 AIDL 綁定 `com.rokid.sprite.aiapp` 服務。

進入點：`CXRLink`（繼承 `ExternalAppClient`），只需要一個 Android `Context`。

### 3.2 API 清單

| 分類 | API | 說明 |
|---|---|---|
| **連線** | `connect(token: String)` | 以 auth token 綁定 `com.rokid.sprite.aiapp` |
| | `disconnect()` | 解除綁定 |
| | `ICXRLConnectCbk.onCXRLConnected(Boolean)` | 連線狀態回呼 |
| **相機** | `takePhoto(width, height, quality)` | 單張拍照，預設 1920×1080 / quality 80 |
| | `IImageStreamCbk.onImageReceived(ByteArray)` | 非同步回傳影像 |
| **音訊** | `startAudioStream(codecType)` | **開始音訊串流**（codec 值未文件化） |
| | `stopAudioStream()` | 停止 |
| | `IAudioStreamCbk` | 回傳取樣率與聲道數 |
| **顯示** | `openCustomView(data)` | 以 JSON 開啟自訂視圖 |
| | `updateCustomView(data)` | 更新 |
| | `closeCustomView()` | 關閉 |
| | `isCustomViewOpened()` / `getCurrentCustomViewData()` | 查詢狀態 |
| | `setIcons(iconsJson)` | 設定圖示（base64 `IconInfo`） |
| **授權** | `AuthorizationHelper` | 驗證 `com.rokid.sprite.aiapp` 已安裝且 versionCode ≥ 100000 |

### 3.3 對本專案的評估

| CXR-L 能力 | 本專案是否需要 | 理由 |
|---|---|---|
| `takePhoto()` | ❌ **不需要** | CameraX 已可用且能連續取像，比單張拍照強 |
| `startAudioStream()` | 🟠 **值得評估** | 若能取得**經過 4 麥克風陣列降噪**的音訊，會優於 App 自己開 `AudioRecord`。**待實機比較** |
| `openCustomView()` | 🟡 低優先 | 眼鏡顯示對全盲使用者無意義；但對低視力使用者與陪同者有用 |
| `setIcons()` | 🟡 低優先 | 同上 |
| 連線與授權 | ⚠️ 是使用上述任一能力的前提 | 需 `com.rokid.sprite.aiapp` 已安裝 |

**明確結論：CXR-L 文件也載明「No continuous camera stream API exists — only takePhoto for snapshots」。**
**但這不影響本專案 —— 因為我們用 CameraX 直接取相機，繞過整個 CXR 體系。**

**建議**：CXR-L 目前只有 `startAudioStream()` 一項值得評估（降噪音訊品質）。其餘不需要引入，避免多一層依賴與授權耦合。

---

## 4. 【C4 修正】取得連續影像的方案比較

你要求不要停在「沒有」，以下逐一分析你列出的方案。

| 方案 | 可行性 | 需要 Root | 幀率上限 | 評估 |
|---|---|---|---|---|
| **CameraX / Camera2** ⭐ | ✅ **完全可行，已實證** | ❌ 不需要 | 30 fps | **這就是答案。** Face_Recognition 已在眼鏡上用 CameraX 運作。標準 Android API，無任何限制 |
| CXR-L `takePhoto()` | ✅ 可行 | ❌ | 單張，無法連續 | 不如 CameraX |
| CXR-M `takeGlassPhoto()` | ✅ 可行 | ❌ | 單張 | 屬手機端架構，本專案不需要 |
| **MediaProjection** | 🟡 技術可行 | ❌ | 依螢幕更新率 | ❌ **不適用**。它擷取的是**螢幕內容**不是相機。眼鏡顯示是單色綠 480×398 HUD，擷取它毫無意義 |
| **Accessibility Service 截圖** | 🟡 `takeScreenshot()` API 30+ | ❌ | 很低（有頻率限制） | ❌ 同上，擷取的是螢幕不是相機 |
| **Camera Intent**（`ACTION_IMAGE_CAPTURE`） | ✅ 可行 | ❌ | 極低（每次要跳相機 App） | ❌ 完全不適合連續偵測 |
| **定時拍照** | ✅ 可行 | ❌ | 依實作 | 🟡 CameraX 已能連續取像，定時拍照是退化方案 |
| **OCR 自動拍照** | ✅ 可行 | ❌ | 事件驅動 | ✅ 這不是取像方案，是**觸發策略** —— 適合 OCR 功能（偵測到畫面穩定且有文字時才拍） |
| **Native Hook / Xposed** | ⚠️ 技術上可行 | ✅ **需要 Root** | — | ❌ **不建議**。需 Root、破壞保固、無法上架、安全風險高，而且**沒有必要** —— CameraX 已經給你要的東西 |

### 結論

**「眼鏡沒有公開影像串流 API」這個問題在當前架構下不存在。**

因為 App 跑在眼鏡上，`androidx.camera` 的 `ImageAnalysis` 就是完整的連續影像串流，最高 30fps，不需要 Root、不需要 CXR SDK、不需要任何 hack。

前次分析之所以把它列為最大風險，是因為誤判成「手機 App + CXR-M」架構。**這個風險項目應予撤銷。**

真正需要注意的反而是**相反方向的問題**：

| 新的注意事項 | 說明 |
|---|---|
| 幀率不是越高越好 | 2GB RAM + 210mAh，30fps 連續推論會很快耗盡電量與記憶體 |
| 建議 2–5 fps | 對「行進中障礙物警示」足夠（步行 1.4 m/s，5fps 等於每 28cm 一次判斷） |
| 需要背壓控制 | Face_Recognition 已用 `AtomicBoolean` 做，思路正確，保留 |
| `STRATEGY_KEEP_ONLY_LATEST` | 已使用，正確 |

---

## 5. 【C5 修正】電池與行動電源的配戴方案

### 5.1 前次的說法需要修正

前次寫「210mAh / 4 小時是物理限制，不是可以靠優化解決的問題」—— 這句話本身沒錯，但**結論下得太早**，忽略了外接供電這個明顯的解法。

### 5.2 Rokid Glasses 的供電方式

| 項目 | 規格 | 來源 |
|---|---|---|
| 內建電池 | 210 mAh | 官方規格 |
| 續航 | 約 4 小時（一般使用） | 官方規格 |
| 官方充電盒 | 3000 mAh，可充約 10 次 | 官方規格 |
| **邊充邊用是否可行** | **無法確認** | 需實機驗證，見 §5.5 |

### 5.3 配戴方案比較

| 方案 | 說明 | 優點 | 缺點 | 適合情境 |
|---|---|---|---|---|
| **A. 純內建電池** | 不外接 | 最輕、無線材 | 開相機下可能 <1.5 小時 | 短時間外出（買東西、看醫生） |
| **B. 口袋行動電源 + 短線** | 行充放口袋，線沿衣服內側走到鏡腳 | 續航無上限、重量在口袋不在頭上 | 有一條線；線材可能勾到；視障者較難自行整理線材 | ⭐ **日常主力方案** |
| **C. 頸掛式行動電源** | 掛頸行充，線很短 | 線最短、不易勾到 | 頸部負重 | 🟡 長時間定點使用 |
| **D. 腰包 / 斜背包** | 行充在包內 | 重量在腰不在頸；可同時放手機 | 線較長 | ⭐ 長時間外出 |
| **E. 充電盒輪替** | 不外接，電量低時換到充電盒充 | 無線材 | **中斷使用** —— 對導盲系統是致命缺點 | ❌ 不建議作為主要方案 |

### 5.4 建議

**主推 B（口袋行動電源 + 短線），以 D（腰包）為長時間備案。**

理由：
1. 重量不在頭上 —— 49g 的眼鏡是它最大的優勢，不該用頸掛破壞它
2. 線材沿衣服內側走，實務上是可接受的（助聽器、有線耳機使用者都是這樣）
3. 導盲系統**不能中斷**，所以 E 方案不可取

**線材管理的具體建議**（這對視障使用者特別重要）：
- 用**磁吸式** USB-C 接頭 —— 勾到時會自動脫落而不是扯壞或扯到頭
- 線材用**衣夾**固定在衣領，減少晃動
- 選**扁線**而非圓線，較不易纏繞
- 線長 **60–80cm** 足夠（口袋到頭部），過長反而是負擔

### 5.5 仍需實機驗證的項目

| # | 待驗證 | 為什麼重要 |
|---|---|---|
| P1 | **邊充邊用是否可行**（充電時能否正常執行 App） | 若不行，整個外接供電方案不成立 |
| P2 | 充電時的發熱程度 | 貼著臉的裝置，發熱是安全與舒適問題 |
| P3 | 連續 CameraX 2fps + 推論下的實際續航 | 決定行充容量需求 |
| P4 | 眼鏡的充電接口型式與位置 | 決定線材選型與走線方式 |

**在 P1 未驗證之前，不應把外接供電當成既定方案。**

---

## 6. 【C6 修正】Android 14+ 前景服務保活的完整解法

前次只寫「有限制」，這裡給完整的解法組合。

### 6.1 Android 14/15 的實際規則

| 規則 | 說明 |
|---|---|
| **FGS Type 必填** | Android 14 起，所有前景服務必須在 manifest 宣告 `foregroundServiceType` |
| **camera type 的 while-in-use 限制** | `camera` 型別的 FGS **不能從背景啟動**（有例外，見下） |
| **location type** | 需要 `ACCESS_FINE_LOCATION` + `FOREGROUND_SERVICE_LOCATION` |
| **背景啟動 FGS 的例外** | 使用者關閉該 App 的電池最佳化，是官方認可的例外之一 |

> 依據：[Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)、[Restrictions on starting a FGS from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

### 6.2 解法逐項分析

| 方案 | 是否適用本專案 | 需要什麼 | 評估 |
|---|---|---|---|
| **1. 正確宣告 FGS Type** ⭐ | ✅ **必做** | manifest 宣告 `camera\|microphone\|location` + 對應執行時權限 | 這是基礎，不做就不能用 |
| **2. 電池最佳化白名單** ⭐ | ✅ **必做** | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent 引導使用者手動加入 | 官方認可的例外途徑。導盲 App 屬於合理使用情境 |
| **3. 持續顯示通知** ⭐ | ✅ **必做** | FGS 本來就強制要有通知 | 讓系統與使用者知道 App 在執行 |
| **4. 開機自啟** | ✅ 建議 | `RECEIVE_BOOT_COMPLETED` + `BootReceiver` | 眼鏡重開機後自動恢復。**注意 Android 14 起從 BOOT_COMPLETED 啟動 FGS 有型別限制，`camera` 型別不被允許** |
| **5. Device Owner / 專用裝置模式** ⭐⭐ | ✅ **非常適合本專案** | 透過 `adb shell dpm set-device-owner` 設定（需 factory reset 後、無其他帳號） | **這是最強的解法。** 眼鏡是**專用裝置**（不是使用者的日常手機），可以設成 Device Owner + Lock Task Mode，系統就不會殺 App。犧牲是眼鏡變成單一用途裝置 —— 但對導盲眼鏡而言這正是我們要的 |
| **6. WorkManager** | 🟡 部分適用 | — | 適合「定期同步人臉資料庫」這類可延遲的工作，**不適合**即時導盲（WorkManager 最短間隔 15 分鐘） |
| **7. Accessibility Service** | 🟠 可行但要謹慎 | 使用者手動開啟 | 無障礙服務不易被系統殺掉，且本專案**確實是無障礙用途**，理由正當。但濫用會被 Google Play 下架 —— 若不上架則無此顧慮 |
| **8. 廠商設定** | ⚠️ 無法確認 | — | Rokid YodaOS 是客製 ROM，**是否有小米/OPPO 那種額外的省電殺進程機制，無法確認**，需實機測試 |
| **9. CXR SDK 特殊權限** | ⚠️ 無法確認 | — | 官方與社群文件中**未見** CXR SDK 提供任何保活或系統級權限的說明 |
| **10. 系統簽章 / 預裝** | ❌ 不可行 | 需要 Rokid 官方合作 | 除非與 Rokid 有商業合作 |

### 6.3 建議的組合

**針對「眼鏡是專用裝置」這個特性，建議：**

```
必做（基礎）：
  1. 正確的 FGS Type 宣告（camera + microphone + location）
  2. 引導使用者加入電池最佳化白名單
  3. 前景通知

強烈建議（因為眼鏡是專用裝置）：
  5. Device Owner + Lock Task Mode
     → 眼鏡開機直接進入導盲 App，系統不殺、使用者不會誤觸切換

輔助：
  4. 開機自啟（注意 camera 型別的限制，改為啟動一個 Activity 或
     先啟 dataSync 型別 FGS，待使用者互動後再升級）
  6. WorkManager 只用於人臉資料庫同步這類非即時工作

不建議：
  7. Accessibility Service —— 除非上述都不夠，否則不要用（複雜度高）
  10. 系統簽章 —— 需要官方合作
```

**Device Owner 是本專案最值得投資的一項。** 因為 Rokid Glasses 不是使用者的日常手機，把它設成專用裝置沒有任何損失，卻能一次解決保活、誤觸、開機自啟三個問題。

### 6.4 待驗證

| # | 待驗證 | 方法 |
|---|---|---|
| F1 | YodaOS 是否允許設定 Device Owner | `adb shell dpm set-device-owner com.guideglasses/.DeviceAdminReceiver` |
| F2 | YodaOS 是否有額外的省電殺進程機制 | 讓 FGS 連續執行 8 小時觀察 |
| F3 | YodaOS 的電池最佳化白名單 UI 是否存在 | 實機查看設定 |

---

## 7. 【C7 修正】公車辨識 —— MVP 策略

### 7.1 團隊目前的做法（依你提供的資訊）

1. 公車來的時候，**直接詢問司機**
2. 或者，**挑只有一條公車路線的站牌**

### 7.2 這是 MVP，不是完成的功能

**文件中不應把它描述為已完成或已解決。** 正確的定位是：

> 目前以「人工確認」作為過渡方案，讓其餘導航流程（步行、到站、下車提醒）可以先跑通並接受實測。自動辨識車號留待後續階段。

### 7.3 兩種 MVP 策略的評估

| 策略 | 可行性 | 限制 | 對使用者的實際體驗 |
|---|---|---|---|
| **詢問司機** | ✅ 立即可用 | 依賴社會互動；使用者需要開口；吵雜環境司機可能沒聽到 | 可接受。許多視障者本來就這樣搭車。系統的價值在於「告訴他車快到了、該準備了」 |
| **挑單一路線站牌** | ✅ 立即可用 | **大幅限制可用路線**；台北市多數站牌不只一條路線 | 適合固定通勤路線（家↔學校），不適合任意目的地 |

### 7.4 系統應該做什麼來支援這個 MVP

即使不自動辨識車號，系統仍能提供高價值：

| 功能 | 說明 | 技術難度 |
|---|---|---|
| 到站倒數 | TDX 即時資料：「307 還有 3 分鐘」 | 低 ✅ |
| **進站提醒 + 提示音** | 「307 即將進站，請準備並向司機確認」 | 低 ✅ |
| 引導站立位置 | 「請站在站牌旁等候」 | 低 |
| **上車確認** | 系統問「您上車了嗎」，使用者按實體鍵或說「上車了」回應 | 低 ✅ |
| 車上站數倒數 | GPS 比對站點序列，「還有 2 站」 | 中 |
| **下車提醒 + 提示音** | 「下一站下車，請按鈴」 | 中 |
| 單一路線站牌篩選 | 路線規劃時**優先選擇只有一條路線的站牌** | 中 ⭐ 值得做 |

**最後一項特別值得做** —— 把「挑單一路線站牌」從人工策略變成系統自動偏好，在 Directions API 回傳多個方案時，優先選停靠路線少的站牌。這讓 MVP 策略變成產品特性而不是妥協。

### 7.5 未來的自動化選項（不承諾）

| 方案 | 評估 |
|---|---|
| 相機 OCR 辨識車頭路線號碼 | 技術可行，但公車進站時在移動、角度多變、可能被人擋住。**成功率無法保證，不應作為唯一依據** |
| 藍牙信標 | 需公車業者配合，不在團隊可控範圍 |
| 車輛 GPS 比對（TDX 有車輛位置） | 🟠 **值得評估** —— TDX 提供公車即時位置，理論上可判斷「哪一台車正在接近本站」。但無法解決「同時有兩台車進站」 |

---

## 8. 【C1 / C3 修正】五個 Gradle 專案與 Face_Recognition 的定位

### 8.1 五個獨立專案是刻意的

**修正前次的說法。** 前次把「5 個獨立專案、無程式碼共用」列為結構性問題並建議合併，這是**錯誤的判斷**，因為不了解團隊分工。

正確的理解：

| 專案 | 負責範圍 | 狀態 |
|---|---|---|
| `AI_Assistant/` | AI 助理 + 已整合的人臉辨識 | 開發中 |
| `Face_Recognition/` | 人臉辨識 | **已可在眼鏡上實機運作** |
| `Obstacle_Recognition/` | 障礙物辨識（YOLO 訓練中） | 模型訓練中 |
| `Audio_Navigation/` | 語音導航 | 開發中 |
| `Text_Recognition/` | OCR | 開發中 |

**五個資料夾是五位成員的工作區，彼此獨立是為了避免互相干擾。這是合理的協作方式。**

### 8.2 Face_Recognition 與 AI_Assistant 的關係

**修正前次的說法。** 前次寫「90% 重複，應刪除 Face_Recognition」。

正確的理解：**AI_Assistant 中的人臉辨識就是 Face_Recognition 的整合版本。**

```
Face_Recognition/                      AI_Assistant/
  MainActivity.kt         ──整合──>     FaceRecognitionFragment.kt
  （獨立 Activity）                     （成為 Fragment，可與 ChatFragment 切換）

  Python/main.py          ──整合──>     python/main.py
  （只有 /recognize）                   （/recognize + /chat + /chat_audio + /tts）
```

程式碼相似是**整合的結果**，不是重複開發。兩者都應保留：

- `Face_Recognition/` —— 獨立驗證用，開發與除錯人臉功能時較單純
- `AI_Assistant/` —— 整合驗證用

### 8.3 對 guide-glasses 的意義

`guide-glasses` 的角色因此更清楚了：

```
五個成員的工作區（各自獨立開發、不互相修改）
        │
        │  複製（不是修改）
        ▼
   guide-glasses/  ← 最終的完整整合系統
```

**guide-glasses 是第六個、也是最終的整合專案。**

---

## 9. 【重要】關於「不得修改其他資料夾」的既有違反

必須誠實說明：**在你訂下這條規則之前，我已經修改過其他組員的資料夾。**

### 9.1 已經修改的檔案

| 檔案 | 修改內容 | commit |
|---|---|---|
| `AI_Assistant/python/main.py` | `api_key` → `OPENAI_API_KEY`；移除 `cv2.imwrite("debug.jpg")` | `5687577` |
| `AI_Assistant/python/function/stt.py` | `api_key` → `OPENAI_API_KEY` | `5687577` |
| `AI_Assistant/python/function/tts.py` | `api_key` → `OPENAI_API_KEY` | `5687577` |
| `AI_Assistant/python/requirements.txt` | 補上 5 個缺漏套件 | `5687577` |
| `AI_Assistant/python/.env` | 解除 git 追蹤 | `5687577` |
| `Text_Recognition/.../ocr_doc.py` | 憑證路徑改由環境變數提供 | `5687577` |
| `Text_Recognition/.../blind-glasses-ocr-*.json` | 解除 git 追蹤 | `5687577` |
| `.gitignore` | 重寫 | `5687577` |

這些變更已經合併進 `main`（PR #2）。

### 9.2 這對其他組員的實際影響

| 影響 | 嚴重度 | 說明 |
|---|---|---|
| **環境變數改名** | 🟠 **會讓組員的後端啟動失敗** | 若組員的 `.env` 仍寫 `api_key=...`，`main.py` 會 `raise RuntimeError`。他們必須把鍵名改成 `OPENAI_API_KEY` |
| OCR 憑證改由環境變數提供 | 🟠 同上 | 需設定 `GOOGLE_APPLICATION_CREDENTIALS` |
| `requirements.txt` 新增套件 | 🟢 只會讓安裝更完整 | 無負面影響 |
| 移除 `debug.jpg` 寫檔 | 🟢 只影響除錯便利性 | 若組員需要，可自行加回 |
| `.gitignore` 重寫 | 🟢 | 反而修正了會誤擋 Android 資源的規則 |

### 9.3 我的建議

**不建議還原**，理由：
- 還原等於把兩把（已撤銷的）金鑰放回版控
- `requirements.txt` 與 `.gitignore` 的修正是純粹的改善

**建議的處理方式**：通知 AI_Assistant 與 Text_Recognition 的負責人，請他們把本機 `.env` 的鍵名改成 `OPENAI_API_KEY`，一分鐘的事。

**從現在起，我不會再修改 `guide-glasses/` 以外的任何檔案。**

### 9.4 本次刻意「沒有修改」的問題

以下問題我在重新掃描時發現，但**依照新規則不予修改**，僅在此記錄供該模組負責人參考：

| 檔案 | 問題 | 建議 |
|---|---|---|
| `Face_Recognition/Python/main.py:42-45` | 每次請求都 `cv2.imwrite("debug.jpg")` | 移除（見 §2.2 L5） |
| `Face_Recognition/Python/main.py:34` | `async def` 內呼叫阻塞的 `engine.recognize()` | 改成 `def`（見 L4） |
| `Face_Recognition/android/.../ApiClient.kt:18` | `HttpLoggingInterceptor.Level.BODY` | 正式版改 `NONE`（見 L2） |
| `Face_Recognition/android/.../MainActivity.kt:46` | `detectIntervalMs = 5000L` | 降到 500–1000ms（見 L1） |
| `Face_Recognition/Python/face_engine.py:17` | `ctx_id=0` 但 provider 是 CPU | 改 `ctx_id=-1` 避免誤導（見 L7） |
| `AI_Assistant/network/ApiClient.kt:16` | 同樣的 `Level.BODY` | 同 L2 |

---

## 10. 【C5 補充】障礙物辨識模型的現況（依你提供的資訊）

### 10.1 訓練中的模型

| 項目 | 內容 |
|---|---|
| 模型 | YOLO（**版本無法確認**，`Obstacle_Recognition/` 中的腳本用 `ultralytics`） |
| 狀態 | **訓練中** |
| 類別數 | 8 |

**類別清單**

| # | 類別 | 用途 |
|---|---|---|
| 1 | `bicycle` | 腳踏車 —— 移動障礙物，需警示 |
| 2 | `car` | 汽車 —— 高危險移動障礙物 |
| 3 | `crosswalk` | 斑馬線 —— **導引資訊**，告知可安全穿越的位置 |
| 4 | `guidebrick` | 導盲磚 —— **導引資訊**，臺灣本地類別，COCO 沒有 |
| 5 | `motorcycle` | 機車 —— 臺灣最常見的高危險移動障礙物 |
| 6 | `obstacle` | 一般障礙物 —— 路障、電線桿等的統稱 |
| 7 | `people` | 行人 —— 需避讓 |
| 8 | `sidewalk` | 人行道 —— **導引資訊**，可行走區域 |

### 10.2 這組類別設計的評估

**設計得不錯。** 三個觀察：

1. **同時涵蓋「危險物」與「導引物」是對的。** 導盲不只是「避開東西」，更重要的是「告訴我該往哪走」。`crosswalk` / `guidebrick` / `sidewalk` 三個導引類別正是導盲杖給不了的資訊。
2. **包含 `motorcycle` 很正確。** 臺灣的人行道與騎樓常有機車停放與行駛，這是本地化的關鍵類別。
3. **`obstacle` 作為統稱類別是務實的取捨。** 不細分路障/電線桿/垃圾桶，可以減少標註工作量。代價是播報時只能說「前方有障礙物」而非「前方有電線桿」。

**一個建議**：`crosswalk` / `guidebrick` / `sidewalk` 是**長條狀地面區域**，用 bounding box 表達力不足。若模型還在訓練中，建議這三類改用 **segmentation（`yolo-seg`）**。`Obstacle_Recognition/zebra/main.py` 已經在用 `yolo-seg.pt`，方向是對的。

### 10.3 目前完成度

| 項目 | 狀態 |
|---|---|
| 資料集標註 | **無法確認**（repo 中無資料集） |
| 模型訓練 | 進行中（依你提供的資訊） |
| 權重檔 | **不在 repo 中**（`.gitignore` 已排除 `*.pt`） |
| Android 端整合 | **0%** —— `Obstacle_Recognition/android/MainActivity.kt` 只有 54 行權限檢查 |
| 距離估計 | **0%** |
| 方位判定 | **0%** |
| 危險分級 | **0%** |
| TTS 播報策略 | **0%** |
| guide-glasses 整合 | **0%** —— `ai-vision` 模組尚未建立 |

### 10.4 未來如何整合到 guide-glasses

```
訓練完成的 .pt 權重
      ↓ 匯出
yolo export format=tflite int8=True
      ↓
guide-glasses/ai/ai-vision/src/main/assets/obstacle.tflite
      ↓
ObstacleDetector（TFLite Interpreter + NNAPI/GPU delegate）
      ↓
DetectionResult(類別, bbox, 信心度)
      ↓
DistanceEstimator（已知尺寸反推 + 地平面假設）
      ↓
BearingResolver（bbox 中心 x → 左前/正前/右前）
      ↓
DangerClassifier（依類別 + 距離分級）
      ↓
AnnouncementManager（已完成，Phase 1/2 已實作）
      ↓
「右前方三公尺有機車」
```

**需要 Obstacle_Recognition 負責人提供的東西**：

1. 匯出的 `.tflite` 檔（INT8 量化）
2. 類別索引對照表（`data.yaml` 的 `names`）
3. 輸入尺寸（通常 640×640）
4. 前處理規格（正規化方式、色彩順序 RGB/BGR）
5. 後處理規格（輸出張量格式、NMS 是否已內建）
6. 驗證集上的 mAP 數據

**在拿到這些之前，`ai-vision` 模組無法完成整合。**

---

## 11. 對既有文件的影響

| 文件 | 受影響的章節 | 處理方式 |
|---|---|---|
| `01_REPOSITORY_ANALYSIS.md` | §7「三個結構性問題」全部三點、§2.3 Face_Recognition「應刪除」、§4.4 死碼清單中的 Face_Recognition | **以本文件 §1、§8 為準** |
| `02_ROKID_SDK_ANALYSIS.md` | §1 兩條產品線的比較（B 線不再討論）、§3.3 待驗證清單中的「連續影像串流」、§5 角色分工圖 | **以本文件 §1、§3、§4 為準** |
| `03_FEATURE_ANALYSIS.md` | 功能一的「最大風險：怎麼拿到連續影像」、功能二「現況 50%」的描述、功能四的公車段 | **以本文件 §4、§7、§10 為準** |
| `04_ARCHITECTURE.md` | §1.1 三層配置表（眼鏡欄位需大幅上調）、§2 整體架構圖 | **以本文件 §1 為準** |
| `05_ROADMAP_AND_FEASIBILITY.md` | §2.2 限制 1（相機串流）、限制 2（續航）、限制 3（Android 限制）、限制 4（公車） | **以本文件 §4、§5、§6、§7 為準** |
| `06_SECURITY_RUNBOOK.md` | 全部 | 金鑰已撤銷重發，本文件僅具歷史紀錄價值 |
| `07_HANDOVER.md` | §4「是否有動到原本專案」 | 以本文件 §9 補充 |

> 為保留分析的可追溯性，舊文件不刪除、不改寫，僅在此標明何處已被取代。
