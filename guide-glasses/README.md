# guide-glasses

Rokid AI 導盲眼鏡系統 —— 統一的多模組 Android 專案。

取代舊有的 5 個獨立 Gradle 專案（`AI_Assistant/` / `Face_Recognition/` /
`Obstacle_Recognition/` / `Audio_Navigation/` / `Text_Recognition/`）。
舊專案在新版功能對齊之前**保留不動**，可作為對照。

完整分析與規劃見 [`../docs/`](../docs/00_README.md)。

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
app/                    組裝層：Application、MainActivity、Hilt Modules
core/
  core-domain/          【純 Kotlin】Entity、UseCase 介面、播報仲裁邏輯
  core-common/          Dispatcher、共用工具
```

後續 Phase 會加入：`glasses-cxr` / `glasses-fallback` / `ai-*` / `feature-*`。

### `core-domain` 刻意不是 Android 模組

它只套用 `kotlin.jvm`，不套用任何 Android plugin。這是**建置層面的架構約束**：
任何 `android.*` 或 `com.rokid.*` 的 import 都會直接編譯失敗，
從機制上保證 domain 層不被基礎設施污染，也讓核心邏輯能用純 JVM 測試驗證
（不需模擬器）。

---

## 目前狀態（Phase 1）

已完成：

- 多模組骨架、version catalog、Hilt DI 接線
- `AppResult` / `AppError` —— 型別化的錯誤，取代舊專案讓 exception
  直接穿透到 UI 再唸出英文訊息的做法
- `AnnouncementQueue` —— 播報優先級仲裁（14 個單元測試涵蓋）
- `GlassesGateway` / `FrameSource` / `Announcer` —— 眼鏡能力的抽象介面

尚未實作：所有功能模組。`MainActivity` 目前只是一個空畫面。

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
./gradlew :core:core-domain:test
```

`core-domain` 是純 JVM 模組，測試秒級完成，不需要模擬器。
攸關安全的行為（危險警示能否打斷長文朗讀）都以單元測試守護。
