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
| Gradle | 8.13 | |
| AGP | 8.13.2 | **不要升到 9.x** |
| Kotlin | 2.2.10 | |
| KSP | 2.2.10-2.0.2 | |
| Hilt | 2.57.1 | |
| compileSdk / targetSdk | 36 | |
| minSdk | 28 | CXR-M SDK 要求 ≥ 28 |

### 為什麼卡在 AGP 8.x

實測過 AGP 9.1.0，會遇到兩個阻斷問題：

1. AGP 9 內建 Kotlin 支援，與 `org.jetbrains.kotlin.android` 衝突
   （`Cannot add extension with name 'kotlin'`）
2. AGP 9 移除了 `BaseExtension`，Hilt Gradle plugin（至 2.57.1）無法套用
   （`Android BaseExtension not found`）

待 Hilt 正式支援 AGP 9 之後再評估升級。

---

## 模組結構

```
app/                          組裝層：Application、MainActivity、Hilt Modules
core/
  core-domain/                【純 Kotlin】Entity、播報仲裁、意圖路由
  core-common/                Dispatcher、共用工具
ai/
  ai-speech/                  Android SpeechRecognizer / TextToSpeech
  ai-agent/                   BFF function calling 協定與 HTTP 閘道
feature/
  feature-assistant/          助理中樞 ViewModel
```

後續 Phase 會加入：`glasses-cxr` / `glasses-fallback` / `ai-vision` /
`ai-face` / `ai-ocr` / `feature-obstacle` / `feature-face` /
`feature-navigation` / `feature-ocr`。

### `core-domain` 刻意不是 Android 模組

它只套用 `kotlin.jvm`，不套用任何 Android plugin。這是**建置層面的架構約束**：
任何 `android.*` 或 `com.rokid.*` 的 import 都會直接編譯失敗，
從機制上保證 domain 層不被基礎設施污染，也讓核心邏輯能用純 JVM 測試驗證
（不需模擬器）。

---

## 目前狀態（Phase 2 完成）

已完成：

**Phase 1 地基**
- 多模組骨架、version catalog、Hilt DI 接線
- `AppResult` / `AppError` —— 型別化的錯誤
- `AnnouncementQueue` / `AnnouncementManager` —— 播報優先級仲裁
- `GlassesGateway` / `FrameSource` / `Announcer` —— 眼鏡能力的抽象介面

**Phase 2 助理中樞**
- `LocalCommandMatcher` —— 本地快捷指令，<100ms、離線可用
- `IntentRouter` —— 雙層路由（本地優先，未命中才呼叫 LLM）
- `ConversationHistory` —— 有界、可注入，取代全域無上限的對話記憶
- `AndroidSpeechRecognitionGateway` —— 串流式 ASR，離線優先
- `AndroidTtsAnnouncer` —— 本機 TTS，走無障礙音訊通道
- `RemoteLlmIntentGateway` —— BFF function calling
- `AssistantViewModel` + 無障礙主畫面

尚未實作：障礙物、人臉、導航、OCR、翻譯。這些 intent 目前會播報
「這個功能還在開發中」—— 刻意不靜默，因為對看不見畫面的使用者，
沒有聲音等於系統當掉。

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
| 前面有什麼 / 可以走嗎 / 有障礙物嗎 | 障礙物偵測 |
| 這是誰 / 這個人是誰 / 誰在我前面 | 人臉辨識 |
| 唸給我聽 / 上面寫什麼 | OCR 朗讀 |
| 再說一次 / 剛剛說什麼 | 重複上一則 |

需要參數的指令（「帶我去台北101」「翻成英文」）走 LLM。

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

目前 57 個單元測試，全部是純 JVM，秒級完成，不需要模擬器。

守護的是行為而不只是資料結構：
- 危險警示能否打斷正在進行的長文朗讀
- 使用者說「停」是否真的清空所有佇列
- 已被打斷的 TTS 回呼遲到時，佇列會不會跳號漏播
- 「這個人是誰」能否命中（舊版關鍵字比對會漏掉）
- 沒有網路時，助理是否說人話而不是唸出 exception
