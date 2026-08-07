> 🔴 **2026-08-08 更新：多數「未知」已由實機測試解答。**
> 本文件部分內容已過時，最新事實見 [`DEVICE_FINDINGS.md`](DEVICE_FINDINGS.md)：
> 眼鏡**沒有 Play Services、沒有 STT、TTS 綁不上、沒有 GPS、沒有電子羅盤、
> 相機擷取 930ms**。導航的方案 C（網路定位）已排除 —— 連 `network provider`
> 都沒有註冊。

# 路線圖與待決策事項

最後更新：2026-08-05

---

## 1. 現在該做什麼（依優先序）

### 🔴 最優先：把 App 裝到眼鏡上跑兩個自我檢測

**這五分鐘會消掉目前最多的未知數**，而且只有你能做。

```
說「測試相機」   → 記下解析度與毫秒數
說「測試感測器」 → 記下整句回答
```

第二項尤其重要 —— 有沒有「電子羅盤」會直接決定導航怎麼設計。
完整測試流程見 [`../guide-glasses/DOCUMENTATION.md`](../guide-glasses/DOCUMENTATION.md) §6。

### 接下來

| 順序 | 工作 | 阻塞條件 | 預估 |
|---|---|---|---|
| 1 | **翻譯**（ML Kit 離線） | 無 | 3 天 |
| 2 | **障礙物偵測** `ai-vision` | 需 Obstacle_Recognition 交付 `.tflite` 與規格 | 3–4 週 |
| 3 | **導航** `feature-navigation` | 🔴 需先做架構決策，見 §3 | 2–3 週（步行） |
| 4 | BFF 後端 | 需雲端帳號 | 1 週 |
| 5 | 公車整合（MVP） | 需 TDX 金鑰 | 2 週 |

已完成：專案地基、播報仲裁、AI 助理中樞、Android 原生 STT/TTS、
`glasses-camerax`、`ai-ocr`、`ai-face`、`glasses-sensors`。

**每個功能的詳細實作規劃、流程圖與整合步驟見
[`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md)。**

**逐項可勾選的待辦見 [`TASKS.md`](TASKS.md)，目前進度見 [`STATUS.md`](STATUS.md)。**

---

## 2. 三個卡住的項目

### 2.1 人臉辨識的 `.tflite`（已有替代路徑，不再是阻塞）

端側需要模型檔，規格見
[`../guide-glasses/ai/ai-face/src/main/assets/README.md`](../guide-glasses/ai/ai-face/src/main/assets/README.md)。

**但現在不放也能用** —— `RemoteFaceIdentification` 會自動接手，沿用團隊既有的
InsightFace 後端。設定方式：

```
guideglasses.faceEndpoint=http://<你的後端IP>:8000/recognize
```

放進 `guide-glasses/local.properties` 或 `~/.gradle/gradle.properties`。

### 2.2 障礙物模型

需要 Obstacle_Recognition 交付：INT8 `.tflite`、類別索引對照表、輸入尺寸、
前處理規格（正規化方式、RGB/BGR）、後處理規格（輸出張量格式、NMS 是否內建）、
驗證集 mAP。

相機模式控制（`CameraModeController`）已就緒，模型一到就能接。

### 2.3 導航的架構決策

見 §3。

---

## 3. 🔴 導航：眼鏡沒有 GPS

App 跑在眼鏡上，眼鏡沒有 GPS。這不是寫程式能解決的。

**IMU 能做「跟著走」，做不到「知道在哪」**：

| IMU 能給 | IMU 給不了 |
|---|---|
| 相對轉向、走了幾步、有沒有在動 | 我在哪裡、目的地在哪個方向、有沒有偏離路線 |

沒有絕對位置就沒有路線規劃、偏離偵測、到站提醒。

### 三個方向

| 方案 | 做法 | 代價 |
|---|---|---|
| **A. 手機 companion 提供定位** | 手機透過網路或 CXR 把 GPS 座標送給眼鏡 | 要維護第二個 App 與一條通訊管道 |
| **B. 導航跑在手機、感測跑在眼鏡** | 手機負責路線與播報 | 播報仲裁要跨裝置，**會重新引入蓋台問題** —— 目前花了不少力氣才讓播報在單一裝置上正確 |
| ~~**C. 網路定位**~~ | 🔴 **已排除** —— 實測沒有 `network provider`，也沒有 Play Services | — |

**建議先實測 C 是否可行**（列出眼鏡 `LocationManager` 的 provider），
再決定 A 或 B。**在決策之前導航不應開工。**

### 導航的其他已知難題

**公車「哪一輛車進站」目前無可靠解法** —— 視障者看不到車頭號碼。
團隊目前的 MVP 是「詢問司機」或「挑單一路線的站牌」。這是過渡方案，
**不應描述成完成的功能**。

系統仍能提供高價值：TDX 到站倒數、進站提醒、上下車確認、站數倒數。
一個值得做的：**路線規劃時優先選擇停靠路線少的站牌** —— 把人工妥協變成產品特性。

**Google Directions API 的 transit 模式不回傳即時到站時間**，必須另接
TDX（交通部運輸資料流通服務，需申請會員，每會員最多 3 組金鑰）。

**都市 GPS 精度**：台北高樓區誤差可達 15–30m，「偏離 30m 重新規劃」的閾值
需實地調校，否則會不停誤報。

---

## 4. 可行性評估

| 功能 | 可行性 | 主要風險 |
|---|---|---|
| AI 助理 | 🟢 高 | 已完成 |
| OCR 朗讀 | 🟢 高 | 已完成 |
| 語音辨識 / 合成 | 🟢 高 | 已完成，但**眼鏡是否有 `SpeechRecognizer` 未實測** |
| 人臉辨識 | 🟢 高 | 已完成（端側或遠端）。風險在法遵而非技術 |
| 障礙物偵測 | 🟠 中 | 依賴外部模型交付；本地類別需自行標註訓練 |
| 導航（步行） | 🟠 中低 | **無 GPS**，需架構決策 |
| 導航（公車） | 🟠 中低 | 「哪一輛車」無可靠解法 |
| 翻譯 | ✅ | **已完成並在眼鏡實測通過** |

### 必須正視的限制

**續航。** 210mAh 約 4 小時（不開相機）。導盲場景是「出門一整天」。
外接行動電源是必須而非選配，且**邊充邊用是否可行尚未驗證**。

**Android 廠商 ROM。** YodaOS 是客製 ROM。已知：螢幕逾時只有 5 秒，App idle 時 Android 會擋掉相機（`Access Denial: idle UID`）—— 需要 Foreground Service。
Device Owner 是最值得投資的對策，見
[`TECHNICAL_NOTES.md`](TECHNICAL_NOTES.md) §5。

**成本。** 100 使用者估算約 US$165–295/月，Google Maps 占最大宗。
若不收費，這是需要長期補助的營運成本，建議及早規劃。

**人臉辨識的法律風險。** 在公共場所辨識未同意的人臉在臺灣涉及《個人資料
保護法》。現行設計已限制在「使用者主動註冊過的人」，註冊前會播報同意提示，
資料以 Keystore 加密且絕不上雲。若要商業化建議諮詢法律意見。

### 一個產品定位上的觀察

**導盲杖已經很有效地處理近距離地面障礙。** 這套系統的差異化不應該是
「取代導盲杖」，而是「提供杖子給不了的資訊」—— 遠距離接近的車輛、
頭部高度的障礙、環境的語意描述、人的身分、文字內容。

這個定位差異會影響障礙物偵測的設計方向。

---

## 5. 歷史紀錄

### 金鑰事件（已結案）

`.env` 的 OpenAI key 與 GCP service account 私鑰曾進入版控。

| 項目 | 狀態 |
|---|---|
| 金鑰撤銷並重新產生 | ✅ 團隊已完成 |
| 工作目錄清理 | ✅ commit `5687577` |
| git 歷史清除 + force push | ✅ 2026-08-05 |

實測發現：force push 之後 **GitHub API 仍可依 SHA 取回舊 blob**（一般
`git clone` 取不到）。這是 GitHub 的已知行為 —— 因此撤銷金鑰才是唯一有效的
止血手段，歷史清除只是善後。

**歷史已重寫，舊的本機 clone 必須刪除後重新 clone**，否則 `git pull` 會把
舊歷史推回去。

### Phase 0 曾修改組員的工作區

在「不得修改其他資料夾」的規則訂立**之前**，安全止血曾動過：

- `AI_Assistant/python/`（`main.py`、`stt.py`、`tts.py`、`requirements.txt`）
- `Text_Recognition/.../ocr_doc.py`
- 根目錄 `.gitignore`

有實際影響的是**環境變數改名 `api_key` → `OPENAI_API_KEY`** ——
組員的 `.env` 若沒改鍵名，後端會啟動失敗並拋出中文錯誤訊息。

**此後不再修改 `guide-glasses/` 以外的任何檔案。**

### 分支整併

2026-08-05 專案改為只保留 `main`。被刪除的
`copilot/refactor-ai-assistant-module`（804 行未合併的 AI_Assistant 重構）
已完整封存於 [`archive/copilot-refactor-ai-assistant/`](archive/copilot-refactor-ai-assistant/)，
含 git bundle 與 patch 檔，還原方式見該資料夾的 README。
