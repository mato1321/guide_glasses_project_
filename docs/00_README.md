# Rokid AI 導盲眼鏡系統 — 完整分析報告

分析日期：2026-08-05

---

## 🔵 請從這裡開始

| 你想知道 | 看這份 |
|---|---|
| **guide-glasses 怎麼跑、怎麼測、完成到哪** | [`guide-glasses/DOCUMENTATION.md`](../guide-glasses/DOCUMENTATION.md) |
| **前次分析哪些結論是錯的、正確的是什麼** | [`08_CORRECTIONS_AND_REANALYSIS.md`](08_CORRECTIONS_AND_REANALYSIS.md) |
| Face_Recognition 為什麼延遲高 | [`08` §2](08_CORRECTIONS_AND_REANALYSIS.md) |

> ⚠️ **`01`～`07` 有部分結論已於 2026-08-05 被修正**（見 `08`）。
> 這些文件保留不改寫，僅供分析過程的可追溯性。

---

## 報告目錄

| 文件 | 內容 |
|---|---|
| [01_REPOSITORY_ANALYSIS.md](01_REPOSITORY_ANALYSIS.md) | Repository 逐檔案分析、架構圖、問題清單（分級）、死碼清單、可用 SDK 取代的部分 |
| [02_ROKID_SDK_ANALYSIS.md](02_ROKID_SDK_ANALYSIS.md) | 兩條產品線的區分、CXR-M API 清單（含出處與可信度）、硬體限制、SDK 優先原則套用 |
| [03_FEATURE_ANALYSIS.md](03_FEATURE_ANALYSIS.md) | 六大功能逐項分析：現況、模型選型、Edge/Cloud 判定、風險 |
| [04_ARCHITECTURE.md](04_ARCHITECTURE.md) | Edge/Cloud 配置、雲端供應商比較、Clean Architecture 模組設計、7 張系統流程圖 |
| [05_ROADMAP_AND_FEASIBILITY.md](05_ROADMAP_AND_FEASIBILITY.md) | Phase 0–6 Roadmap、誠實的可行性分析、待確認事項 |
| [06_SECURITY_RUNBOOK.md](06_SECURITY_RUNBOOK.md) | 金鑰外洩處理程序與執行結果 |
| [07_HANDOVER.md](07_HANDOVER.md) | 專案交接文件 — 完整工作紀錄、檔案異動清單、風險清單 |
| [08_CORRECTIONS_AND_REANALYSIS.md](08_CORRECTIONS_AND_REANALYSIS.md) | **⭐ 前次分析的修正與重新分析** — 延遲根因、相機方案、CXR-L、電池、Android 保活、公車 MVP、YOLO 模型 |
| [../guide-glasses/DOCUMENTATION.md](../guide-glasses/DOCUMENTATION.md) | **⭐ guide-glasses 技術文件** — 架構、功能、執行、測試、完成度 |

---

## 金鑰事件（已結案）

`.env` 的 OpenAI key 與 GCP service account 私鑰曾進入版控。處理結果：

| 項目 | 狀態 |
|---|---|
| 金鑰撤銷並重新產生 | ✅ 團隊已完成 |
| 工作目錄清理 | ✅ commit `5687577` |
| git 歷史清除 + force push | ✅ 2026-08-05 完成 |

> ⚠️ **歷史已重寫。所有既有的本機 clone 必須刪除後重新 clone**，
> 否則 `git pull` 會把舊歷史推回去。

處理過程見 [06_SECURITY_RUNBOOK.md](06_SECURITY_RUNBOOK.md)。

---

## 五個最關鍵的結論（2026-08-05 修正後）

1. **Rokid Glasses 就是一台 Android 12 裝置，APK 直接安裝執行。**
   `Face_Recognition/` 已經在眼鏡上實機運作 —— 用標準 CameraX 取像、TTS 播報。
   因此連續影像串流不是問題，標準 Android API 就給你 30fps。

2. **五個 Gradle 專案是刻意的分工**，五位成員各自開發。`guide-glasses` 是第六個、
   也是最終的整合專案。**只在 guide-glasses 開發，不修改其他人的資料夾。**

3. **Face_Recognition 延遲高不是 FastAPI 的錯。** 主因依序是：5 秒輪詢間隔、
   `HttpLoggingInterceptor.Level.BODY` 導致請求體寫兩次、每次請求都寫 `debug.jpg`、
   `async def` 內呼叫阻塞函式。前三項都是改一兩行的事。詳見 [`08` §2](08_CORRECTIONS_AND_REANALYSIS.md)。

4. **眼鏡只有 2GB RAM、210mAh。** 端側模型總量要控制在 400MB 內，相機建議
   2–5 fps 而非 30fps。續航靠外接行動電源解決（**邊充邊用是否可行待驗證**）。

5. **guide-glasses 目前約 18% 完成。** 已有「大腦」（語音、意圖路由、播報仲裁），
   還沒有「眼睛」（相機、人臉、OCR、障礙物、導航全部未實作）。

---

## 六大功能在 guide-glasses 中的完成度

| 功能 | guide-glasses | 組員工作區的狀態 |
|---|---:|---|
| AI 語音助理 | **85%** | AI_Assistant 開發中 |
| 語音 STT / TTS | **90%** | 已被 guide-glasses 取代（Android 原生） |
| 人臉辨識 | **0%** | Face_Recognition **已在眼鏡實機運作**（延遲待優化） |
| OCR 朗讀 | **0%** | Text_Recognition 開發中 |
| 障礙物偵測 | **0%** | Obstacle_Recognition **YOLO 8 類訓練中** |
| 智慧導航 | **0%** | Audio_Navigation 開發中 |
| 翻譯 | **0%** | 無人負責 |

完整的模組完成度表見 [`guide-glasses/DOCUMENTATION.md` §8](../guide-glasses/DOCUMENTATION.md)。

---

## 建議的技術選型摘要

| 領域 | 建議 | 取代什麼 |
|---|---|---|
| 物件偵測 | **YOLO26-n INT8 / TFLite + NNAPI** | 尚未實作 |
| 距離估計 | 已知尺寸反推 + 地平面假設（**先不用 Depth Anything**） | 尚未實作 |
| 人臉偵測 | MediaPipe Face Detector | 後端 InsightFace |
| 人臉特徵 | MobileFaceNet TFLite（端側） | 後端 InsightFace |
| 人臉資料庫 | **Room（≤100 人記憶體比對）**，>100 人再加 ObjectBox HNSW | 伺服器檔案系統 |
| ASR | **Android SpeechRecognizer** | OpenAI gpt-4o-transcribe |
| TTS | **Android TextToSpeech** | OpenAI tts-1（2–3 秒太慢） |
| 翻譯 | ML Kit Translation（離線） | 尚未實作 |
| OCR | **ML Kit v2 中文（離線）→ Cloud Vision → Gemini/Claude Vision** 三層 | 直接打 Cloud Vision |
| 意圖路由 | **Claude Haiku 4.5 Function Calling** + 本地快捷指令 | 關鍵字字串比對 |
| 導航 | Google Directions + Places + **TDX 即時公車** | 尚未實作 |
| 後端 | **Cloud Run BFF**（asia-east1） | 開發者筆電上的 FastAPI |
| 架構 | Clean Architecture + MVVM + Hilt + 多模組 | 無架構 |
