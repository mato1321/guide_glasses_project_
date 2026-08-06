# guide-glasses 現況快照

> **這份文件每次有進度就更新。** 想知道「做到哪裡了」看這份就好。
> 需要交接給新對話時，直接把 §6 貼過去。

| | |
|---|---|
| 最後更新 | 2026-08-06 |
| `main` HEAD | `580e9ca` |
| 整體完成度 | **約 70%** |
| 單元測試 | **279 個，全過**（31 個測試類，純 JVM） |
| 模組數 | 12 |
| Kotlin 行數 | 7,545（主程式）+ 3,448（測試） |
| 建置狀態 | ✅ `./gradlew build` 通過，lint 無錯誤 |
| APK | debug 約 95 MB（含 ONNX Runtime 18 MB ＋ 人臉模型 13 MB） |
| clone 後可直接建置 | ✅ 模型已進版控，只需自補 `local.properties` |
| 實機驗證 | ❌ **從未在任何實體裝置上執行過** |

---

## 1. 專案架構

```
guide-glasses/
├── app/                        組裝層  447 行
│   ├── MainActivity.kt             單一 Activity，整片畫面是按鈕
│   ├── GuideGlassesApplication.kt  @HiltAndroidApp
│   └── di/
│       ├── CoreModule.kt           DispatcherProvider
│       └── AssistantModule.kt      全部功能的接線
│
├── core/
│   ├── core-domain/            3811 行 + 3266 行測試  ★ 純 Kotlin
│   │   ├── AppResult.kt            型別化的結果與錯誤
│   │   ├── announce/               播報優先級仲裁
│   │   ├── assistant/              意圖路由、對話歷史
│   │   ├── glasses/                影像來源、幀率節流、相機自我檢測
│   │   ├── ocr/                    辨識介面、斷句、朗讀進度
│   │   ├── face/                   比對、方位、距離、辨識策略、照片同步
│   │   ├── motion/                 步態、轉向指示、相機模式
│   │   ├── speech/                 ASR 介面
│   │   ├── translate/              目標語言解析、翻譯、語言包預下載
│   │   ├── obstacle/               ★框架：八類、距離、危險分級、去抖動
│   │   ├── navigation/             ★框架：定位抽象、球面幾何
│   │   ├── readiness/              出門前檢查
│   │   └── text/                   ASR 文字正規化（共用）
│   │
│   ├── core-common/               25 行   DispatcherProvider
│   └── core-database/            317 行   Room + Keystore 加密
│
├── glasses/                    眼鏡硬體
│   ├── glasses-camerax/          364 行   CameraX 影像來源
│   └── glasses-sensors/          214 行   IMU 感測
│
├── ai/                         AI 能力
│   ├── ai-speech/                385 行   SpeechRecognizer / TextToSpeech
│   ├── ai-agent/                 243 行 + 182 行測試   LLM BFF 協定
│   ├── ai-ocr/                   124 行   ML Kit 中文（bundled）
│   ├── ai-face/                  860 行   ML Kit + ONNX/TFLite + 遠端 + 同步
│   └── ai-translate/             166 行   ML Kit 翻譯（語言包執行期下載）
│
├── feature/
│   └── feature-assistant/        555 行   AssistantViewModel
│
└── tools/                      開發工具（不進 APK）
    ├── face_enroll_server.py     瀏覽器上傳照片＋標人名，零依賴
    └── README.md
```

**依賴方向**：`app` → `feature` → `core-domain` ← `glasses/* + ai/* + core-database`

`core-domain` 只套用 `kotlin.jvm`，任何 `android.*` 的 import 都會編譯失敗 ——
這是建置層面強制的架構約束，也是為什麼 3266 行測試可以純 JVM 秒級跑完。

**部署形態**：單一 APK，直接裝在 Rokid Glasses 上執行。手機目前**不在必要路徑上**。
完整的分層決策與未來的手機 companion 設計見
[`ARCHITECTURE.md`](ARCHITECTURE.md)。

---

## 2. 功能進度

| 功能 | 完成度 | 狀態 |
|---|---:|---|
| 專案地基（多模組、Hilt、version catalog） | 95% | ✅ 缺 CI |
| 播報優先級仲裁 | 100% | ✅ |
| AI 助理中樞（雙層意圖路由） | 85% | ✅ 缺 BFF、眼鏡 AI 鍵 |
| 語音辨識 / 合成 | 90% | ✅ 待實機驗證 |
| 相機（CameraX） | 80% | ✅ 待實機驗證 |
| OCR 朗讀 | 75% | ✅ 缺雲端 fallback |
| 人臉辨識 | 95% | ✅ 端側可用，瀏覽器註冊＋語音同步 |
| IMU 動作感測 | 75% | ✅ 待實機確認感測器 |
| 翻譯 | 80% | ✅ 語言包首次需網路下載 |
| 障礙物偵測 | 35% | ⏸ **domain 框架已完成**，等模型交付前後處理 |
| 導航 | 15% | 🟡 **定位抽象＋球面幾何已完成**，等 A10 決定實作 |

---

## 3. 目前可用的語音指令

| 說法 | 動作 | 需要什麼 |
|---|---|---|
| 停 / 安靜 / 別說了 | 立刻靜音 | — |
| 再說一次 / 剛剛說什麼 | 重播上一則 | — |
| **測試相機** | 回報解析度與耗時 | 相機權限 |
| **測試感測器** | 回報實際可用的感測能力 | — |
| 唸給我聽 / 上面寫什麼 | OCR 文件朗讀 | 相機權限 |
| 這是哪裡 / 招牌寫什麼 | OCR 招牌模式（只唸最大的字） | 相機權限 |
| 下一段 / 上一段 / 繼續唸 | 朗讀控制 | 進行中的朗讀 |
| 這是誰 | 人臉辨識，含方位與距離 | 模型檔**或**遠端後端 |
| **同步人臉** | 從註冊工具抓照片、重算特徵 | `photoEndpoint` ＋ 模型檔 |
| **出門前檢查** | 回報離線可用狀態與該補什麼 | — |
| **準備翻譯** | 預先下載語言包 | 網路 |
| 翻成英文 / 翻譯 | 翻譯上一次 OCR 的內容 | 首次該語言需網路下載語言包 |
| 前面有什麼 | 障礙物偵測 | ⏸ 回「開發中」 |
| 帶我去⋯ | 導航 | ⏸ 回「開發中」，且需 BFF |

**最實用的組合**：「唸給我聽」→ 聽到中文 → 「翻成英文」，同一份內容不必再拍一次。

**哪些指令不需要網路**：除了「翻成⋯」的首次語言包下載、以及需要抽開放集合參數的
指令（「帶我去台北101」）之外，**全部離線可用**。翻譯的目標語言是封閉集合，
在本地解析，所以翻譯本身不需要 BFF。

---

## 4. 阻塞項目

| # | 卡在什麼 | 誰能解 | 影響 |
|---|---|---|---|
| 1 | 障礙物模型未交付 | Obstacle_Recognition 負責人 | 🔴 `ai-vision` 無法開工 |
| 2 | ~~端側人臉模型檔~~ | ✅ **已解決** | 用 InsightFace 的 `w600k_mbf.onnx`，直接執行不需轉檔 |

~~導航架構未定~~ → **已於 2026-08-06 決策**：手機 companion 只當
「GPS 感測器 + 網路閘道」，播報仲裁一律留在眼鏡上。理由與被否決的方案見
[`ARCHITECTURE.md`](ARCHITECTURE.md) §5。導航現在可以開工。

詳見 [`ROADMAP.md`](ROADMAP.md) §2。

---

## 4.5 clone 下來怎麼跑起來

**模型與所有建置必需檔案都已進版控，clone 完只差一個 SDK 路徑。**

```bash
git clone https://github.com/mato1321/guide_glasses_project_.git
```

建立 `guide-glasses/local.properties`（這是**唯一**需要自己補的檔案，
因為 SDK 路徑每台機器不同。反斜線與冒號都要跳脫）：

```
sdk.dir=C\:\\Users\\<你的帳號>\\AppData\\Local\\Android\\Sdk
```

> 或者設好 `ANDROID_HOME` 環境變數也可以，AGP 會自動找到。

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
cd guide-glasses && ./gradlew build
```

需要 JDK 17+（JDK 11 不行）與 Android SDK Platform 36。

**哪些功能 clone 完就能測**：

| 功能 | 額外需要什麼 |
|---|---|
| 語音助理、STT / TTS、播報仲裁 | — |
| 相機、感測器自我檢測 | — |
| OCR 朗讀（文件／招牌） | — |
| 人臉辨識（端側） | 先跑註冊工具建幾個人，見 [`tools/README.md`](../guide-glasses/tools/README.md) |
| 翻譯 | 首次該語言需連網下載語言包 |
| 障礙物、導航 | ⏸ 尚未實作，會播報「開發中」 |

`local.properties` 可選的兩行（不設也能跑）：

```
guideglasses.photoEndpoint=http://<你的IP>:8100     # 人臉同步來源
guideglasses.faceEndpoint=http://<你的IP>:8000/recognize   # 遠端人臉備援
```

---

## 5. 出門實測怎麼準備

> **眼鏡沒有 SIM 卡。** 出了 Wi-Fi 範圍就完全沒有網路。
> 四個核心功能本身都是端側的，但有**兩件事必須出門前用網路做完**。

### 出門前（在有 Wi-Fi 的地方）

| 步驟 | 說什麼 | 為什麼 |
|---|---|---|
| 1 | 上傳照片到註冊工具 | 見 [`tools/README.md`](../guide-glasses/tools/README.md) |
| 2 | 「**同步人臉**」 | 沒同步＝出門後誰都認不出來 |
| 3 | 「**準備翻譯**」 | 語言包約 30MB，出門後下載不了 |
| 4 | 「**出門前檢查**」 | 一句話確認上面兩項都好了 |

第 4 步會聽到其中之一：

> ✅「可以出門了。認得 5 個人，英文翻譯已就緒」
> ❌「還沒完全準備好。人臉資料庫是空的，請先說同步人臉。目前離線可用的有：OCR 朗讀」

**實測最常見的失敗不是程式壞掉，而是忘記同步就出門。** 這個檢查就是為此存在。

### 出門後（無網路）

| 功能 | 離線可用 |
|---|---|
| 助理本地指令（停、再說一次、測試相機／感測器） | ✅ |
| OCR 朗讀（文件／招牌／分段控制） | ✅ |
| 人臉辨識（同步過之後） | ✅ |
| 翻譯（語言包下載過之後） | ✅ |
| 「帶我去⋯」「把他記起來」 | ❌ 需 BFF，會播報離線提示 |

**所以這四個功能出門完全不需要網路，也不需要 BFF。**

---

## 5.5 最重要的待辦：第一次上機

**這五分鐘會消掉目前最多的未知數，而且只有你能做。**

裝到眼鏡上，依序說：

1. 「停」→ 確認有沒有聲音（**沒聲音就先解這個**）
2. 「測試感測器」→ **記下整句回答**，有沒有「電子羅盤」決定導航設計
3. 「測試相機」→ **記下毫秒數**，那是所有視覺功能的延遲基線

完整清單見 [`TASKS.md`](TASKS.md) §A 與 [`TECHNICAL_NOTES.md`](TECHNICAL_NOTES.md) §7。

> ⚠️ **這個 App 從未在任何實體裝置上執行過。** 驗證只有建置成功、
> 279 個單元測試通過、lint 無錯誤。請把第一次上機當成探勘而不是驗收。

---

## 6. 交接給新對話用

> 需要開新對話時，把下面整段貼過去。

```text
接手 Rokid AI 導盲眼鏡專案，繼續開發 guide-glasses。

先讀這五份：
1. docs/STATUS.md               ← 現況快照，做到哪裡了
2. docs/TASKS.md                ← 待辦清單，哪些勾了哪些沒
3. docs/ARCHITECTURE.md         ← 三層分工決策（哪個功能放哪一層、為什麼）
4. docs/IMPLEMENTATION_PLAN.md  ← 每個功能怎麼做、怎麼整合（含 Mermaid 圖）
5. guide-glasses/DOCUMENTATION.md ← 怎麼跑、怎麼測

工作規則：
- 只在 guide-glasses/ 開發。AI_Assistant/、Face_Recognition/、
  Obstacle_Recognition/、Audio_Navigation/、Text_Recognition/ 是五位組員
  各自的工作區，不得修改。需要引用時複製過來重構。
- Git 只保留 main，直接 git push origin HEAD:main，不開 PR。
- 每完成一項就重新編譯、跑測試、更新 docs/STATUS.md 與 docs/TASKS.md。
- 一律用繁體中文回覆。

建置（JDK 11 不行，需要 17+）：
  export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
  cd guide-glasses && ./gradlew build

不要升級 AGP 到 9.x（實測會與 Kotlin plugin 衝突且 Hilt 無法套用）。

接下來要做：<寫你要的>
```

**「接下來要做」可以填什麼**

| 你的狀況 | 填什麼 |
|---|---|
| 跑過自我檢測了 | **把聽到的原話貼上去** —— 最有價值的輸入 |
| 拿到障礙物模型 | 「實作 ai-vision，模型在 <路徑>，規格如下…」 |
| 都還沒有 | 「實作障礙物偵測的純 domain 部分」—— 不需模型，可純 JVM 測試 |
| 想推進導航 | 「實作 FollowHeadingUseCase」—— 純 IMU，不需 GPS |

---

## 7. 變更歷史

| 日期 | 進度 | 內容 |
|---|---:|---|
| 2026-08-06 | 70% | 出門前檢查、語言包預下載；障礙物與導航的 domain 框架（不需模型即可測試） |
| 2026-08-06 | 65% | 人臉模型改為隨 repo 附上，clone 完即可測試；`face_photos/` 加入忽略 |
| 2026-08-06 | 65% | 端側人臉打通：ONNX Runtime、瀏覽器註冊工具、語音「同步人臉」 |
| 2026-08-06 | 60% | 三層架構決策（`ARCHITECTURE.md`）：導航走手機 companion A 案，導航解除阻塞 |
| 2026-08-05 | 60% | `ai-translate`：ML Kit 離線翻譯、OCR→翻譯串接、TTS 逐句切換語言 |
| 2026-08-05 | 55% | 修正 `local.properties` 設定靜默失效（`providers.gradleProperty` 不讀該檔） |
| 2026-08-05 | 55% | 遠端人臉辨識、docs 整併（9→4 份）、實作規劃文件 |
| 2026-08-05 | 53% | `glasses-sensors`：IMU 感測、相機模式自動切換 |
| 2026-08-05 | 48% | `ai-face`：端側人臉辨識、Keystore 加密儲存 |
| 2026-08-05 | 38% | `ai-ocr`：ML Kit 中文、分段朗讀、朗讀控制 |
| 2026-08-05 | 27% | `glasses-camerax`：CameraX 影像來源、相機自我檢測 |
| 2026-08-05 | 18% | Phase 2：AI 助理中樞、Android 原生 STT/TTS |
| 2026-08-05 | 10% | Phase 1：多模組地基、播報仲裁 |

> 更新這份文件時，順手在這裡加一行。
