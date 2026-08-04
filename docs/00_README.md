# Rokid AI 導盲眼鏡系統 — 完整分析報告

分析日期：2026-08-05 ｜ 基準 commit：`1adfd1a`

---

## 報告目錄

| 文件 | 內容 |
|---|---|
| [01_REPOSITORY_ANALYSIS.md](01_REPOSITORY_ANALYSIS.md) | Repository 逐檔案分析、架構圖、問題清單（分級）、死碼清單、可用 SDK 取代的部分 |
| [02_ROKID_SDK_ANALYSIS.md](02_ROKID_SDK_ANALYSIS.md) | 兩條產品線的區分、CXR-M API 清單（含出處與可信度）、硬體限制、SDK 優先原則套用 |
| [03_FEATURE_ANALYSIS.md](03_FEATURE_ANALYSIS.md) | 六大功能逐項分析：現況、模型選型、Edge/Cloud 判定、風險 |
| [04_ARCHITECTURE.md](04_ARCHITECTURE.md) | Edge/Cloud 配置、雲端供應商比較、Clean Architecture 模組設計、7 張系統流程圖 |
| [05_ROADMAP_AND_FEASIBILITY.md](05_ROADMAP_AND_FEASIBILITY.md) | Phase 0–6 Roadmap、誠實的可行性分析、待確認事項 |

---

## 🔴 立即行動事項（在做任何其他事之前）

1. **撤銷 OpenAI API Key** — `AI_Assistant/python/.env` 已提交進 git 且存在於歷史中
2. **撤銷 GCP Service Account Key** — `Text_Recognition/text_recognize/python/blind-glasses-ocr-d82297cbca1a.json` 同上

刪除檔案沒有用，金鑰仍在 git 歷史裡。必須到供應商後台撤銷並重新產生。

---

## 五個最關鍵的結論

1. **專案目前沒有真的使用 Rokid 眼鏡。** `com.rokid.cxr:client-m` 只宣告在 gradle，程式碼中零呼叫。目前所有功能跑在手機 CameraX 上。

2. **你給的四份文件屬於兩條不同的產品線。** `x-docs` 的 Glass3 企業 SDK（有內建 ASR/TTS/人臉辨識）與你 repo 實際依賴的 CXR-M SDK（**明確不提供** ASR/AI/TTS 引擎）不是同一套東西。**這需要你先確認手上的裝置型號。**

3. **眼鏡只有 2GB RAM、210mAh、4 小時續航。** 所有 AI 運算必須放在手機端。眼鏡是感測器與喇叭，手機是大腦。

4. **最大的技術風險是「眼鏡沒有公開的連續影像串流 API」。** 官方文件只有 `takeGlassPhoto()` 單張拍照。這決定「障礙物偵測」是「即時警示」還是「查詢式描述」——兩種完全不同的產品。

5. **建議重建而非原地重構。** 5 個獨立專案改造成 1 個多模組系統的成本，高於帶著有價值的資產重寫。

---

## 六大功能現況一覽

| 功能 | 完成度 | 可行性 | 主要風險 |
|---|---|---|---|
| 一、障礙物偵測 | 5% | 🟠 中 | 相機串流 API 未知；本地類別需自行標註 |
| 二、人臉辨識 | 50% | 🟡 中高 | 隱私法遵（非技術風險） |
| 三、AI 語音助理 | 30% | 🟢 高 | 需從關鍵字比對改為 Function Calling |
| 四、智慧導航 | **0%** | 🟠 中低 | 「哪一輛公車進站」無可靠解法 |
| 五、語音辨識與翻譯 | 40% | 🟢 高 | 應改用 Android 原生取代雲端 |
| 六、OCR 朗讀 | 60% | 🟢 高 | 改良即可，斷句邏輯值得保留 |

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
