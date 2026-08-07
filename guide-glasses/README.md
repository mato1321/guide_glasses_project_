# guide-glasses

Rokid AI 導盲眼鏡系統 —— 統一的多模組 Android 專案。

這是**最終的完整整合系統**。目標裝置是 Rokid Glasses —— 它執行 YodaOS-Sprite
（Android 12 / API 32），APK 可直接安裝執行。

`AI_Assistant/` / `Face_Recognition/` / `Obstacle_Recognition/` /
`Audio_Navigation/` / `Text_Recognition/` 是五位組員各自的工作區。
**guide-glasses 不修改它們** —— 需要引用時複製過來再重新整合，
讓原始資料夾保持可供組員繼續開發。

**完整技術文件見 [`DOCUMENTATION.md`](DOCUMENTATION.md)** —— 架構、功能、
哪些功能跑在哪裡、系統流程圖、執行步驟、測試方法、完成度。

分析報告與各功能實作規劃見 [`../docs/`](../docs/README.md)。

---

## 建置

需求：JDK 17+（建議直接用 Android Studio 內附的 JBR）、Android SDK 36。

```bash
cd guide-glasses && ./gradlew build
```

`local.properties` 需指向本機 Android SDK，且**必須跳脫反斜線與冒號**：

```
sdk.dir=C\:\\Users\\<你的帳號>\\AppData\\Local\\Android\\Sdk
```

---

## 工具鏈版本（請勿隨意升級）

| 元件 | 版本 | 備註 |
|---|---|---|
| Gradle | 9.5.0 | |
| AGP | 9.3.1 | 需要兩個相容開關，見下 |
| Kotlin | 2.2.10 | |
| KSP | 2.3.6 | |
| Hilt | 2.57.1 | |
| compileSdk / targetSdk | 36 | |
| minSdk | 28 | |

### AGP 9 需要的兩個相容開關

寫在 `gradle.properties`，**不要拿掉**：

```properties
android.builtInKotlin=false   # 讓出 kotlin extension 給 kotlin.android plugin
android.newDsl=false          # 保留舊 DSL，Hilt plugin 才找得到 BaseExtension
```

少了它們會分別出現 `Cannot add extension with name 'kotlin'` 與
`Android BaseExtension not found`。這是**過渡措施** —— Hilt 正式支援
AGP 9 的新 DSL 之後才能移除。

---

## 模組結構

```
app/                          組裝層：Application、MainActivity、Hilt Modules
core/
  core-domain/                【純 Kotlin】Entity、播報仲裁、意圖路由
  core-common/                Dispatcher、共用工具
  core-database/              Room + Keystore 加密（人臉特徵）
glasses/
  glasses-camerax/            CameraX 影像來源
  glasses-sensors/            IMU 感測與相機模式切換
ai/
  ai-speech/                  Android SpeechRecognizer / TextToSpeech
  ai-agent/                   BFF function calling 協定與 HTTP 閘道
  ai-ocr/                     ML Kit 中文 OCR（bundled，離線）
  ai-face/                    ML Kit 偵測 + ONNX 端側 / 遠端辨識
  ai-translate/               ML Kit 翻譯（語言包執行期下載）
  ai-vision/                  YOLOv8 障礙物偵測（ONNX，八類）
feature/
  feature-assistant/          助理中樞 ViewModel
```

尚未建立：`feature-navigation`（等 Rokid 實測是否有 GPS）。
`glasses-cxr` 目前判定為非必要 —— 眼鏡就是 Android 裝置，標準 API 全部可用。

### `core-domain` 刻意不是 Android 模組

它只套用 `kotlin.jvm`，不套用任何 Android plugin。這是**建置層面的架構約束**：
任何 `android.*` 或 `com.rokid.*` 的 import 都會直接編譯失敗，
從機制上保證 domain 層不被基礎設施污染，也讓核心邏輯能用純 JVM 測試驗證
（不需模擬器）。

---

## 目前狀態

**完整現況見 [`../docs/STATUS.md`](../docs/STATUS.md)** —— 這裡只列摘要。

已完成：地基（多模組 / Hilt / version catalog）、播報優先級仲裁、
AI 助理中樞（雙層意圖路由）、STT / TTS、相機（CameraX）、
OCR 朗讀（含分段控制）、人臉辨識（端側）、IMU 動作感測、翻譯、
**障礙物偵測**（YOLOv8 八類）。

尚未實作：**導航**（眼鏡推定無 GPS，待 A10 實測）。這個 intent 目前會播報
「這個功能還在開發中」—— 刻意不靜默，因為對看不見畫面的使用者，
沒有聲音等於系統當掉。

> ⚠️ **小米 Android 手機已驗證**（並因此找出三個真實 bug），
> 但 **Rokid Glasses 從未執行過**。306 個單元測試通過、lint 無錯誤。

### 設定 LLM 後端（選用）

留空時 App 仍完全可用，只是複雜語句無法理解。在 `local.properties` 或
`~/.gradle/gradle.properties` 加入：

```
guideglasses.llmEndpoint=https://your-bff.run.app/route
```

BFF 需實作的協定見 `ai/ai-agent/src/main/kotlin/.../AgentProtocol.kt`。
App 端刻意不直接呼叫 LLM 供應商 —— 內嵌金鑰必然會被反編譯取出。

### 語音指令（本地、不需網路）

| 說法 | 動作 |
|---|---|
| 停 / 安靜 / 別說了 | 立刻停止播報 |
| 測試相機 / 測試感測器 | 自我檢測（實機驗證用，用聽的就知道通不通） |
| 前面有什麼 / 可以走嗎 / 有障礙物嗎 | 障礙物偵測（YOLOv8 八類） |
| 這是誰 / 這個人是誰 / 誰在我前面 | 人臉辨識（含方位與距離） |
| 唸給我聽 / 上面寫什麼 | OCR 文件朗讀 |
| 這是哪裡 / 招牌寫什麼 | OCR 招牌模式（只唸最大的字） |
| 下一段 / 上一段 / 繼續唸 | 朗讀控制 |
| **翻成英文 / 翻譯** | 翻譯上一次 OCR 的內容 |
| 再說一次 / 剛剛說什麼 | 重複上一則 |

參數是**開放集合**的指令（「帶我去台北101」的目的地、「他叫小明」的人名）走 LLM。
翻譯是刻意的例外 —— 目標語言是封閉集合，本地就能解析，所以**翻譯不需要 BFF**。

### 播報優先級

| 優先級 | 用途 | 行為 |
|---|---|---|
| `CRITICAL` | 立即危險（2m 內的車、落差） | 打斷一切 |
| `USER_RESPONSE` | 使用者主動查詢的回應 | 打斷導航與一般內容 |
| `NAVIGATION` | 轉彎、到站提醒 | 打斷一般內容 |
| `AMBIENT` | OCR 長文朗讀、閒聊 | 可被任何上位打斷，支援續播 |

同優先級先到先播，不互相打斷。相同 `dedupeKey` 在時間窗內（預設 10 秒）
只播一次 —— 避免同一個人的臉被連續辨識十次就播十次。

---

## 測試

```bash
./gradlew test
```

目前 **306 個單元測試**，全部是純 JVM，秒級完成，不需要模擬器。

守護的是行為而不只是資料結構：
- 危險警示能否打斷正在進行的長文朗讀
- 使用者說「停」是否真的清空所有佇列
- 已被打斷的 TTS 回呼遲到時，佇列會不會跳號漏播
- 「這個人是誰」能否命中（舊版關鍵字比對會漏掉）
- 沒有網路時，助理是否說人話而不是唸出 exception
- 障礙物的**模型類別索引**是否對到正確的 domain 類別
  （八類裡有六類的 ordinal 與模型索引不同，錯了不會報錯）
