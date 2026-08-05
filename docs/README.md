# Rokid AI 導盲眼鏡 — 文件導覽

最後更新：2026-08-05

---

## 先看哪一份

| 你要做什麼 | 看這份 |
|---|---|
| **知道做到哪裡了 / 交接給新對話** | [`STATUS.md`](STATUS.md) ← 現況快照，隨時更新 |
| **知道還有什麼沒做 / 勾待辦** | [`TASKS.md`](TASKS.md) ← 可勾選的清單 |
| **執行 / 測試 App** | [`../guide-glasses/DOCUMENTATION.md`](../guide-glasses/DOCUMENTATION.md) |
| **實作某個功能 / 了解它怎麼運作** | [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) ← 含 Mermaid 圖與整合步驟 |
| 了解技術選型與硬體限制 | [`TECHNICAL_NOTES.md`](TECHNICAL_NOTES.md) |
| 知道接下來要做什麼、卡在哪 | [`ROADMAP.md`](ROADMAP.md) |

`archive/` 是被刪除分支的備份，不是文件。

---

## 專案結構

```
guide_glasses_project_/
├── AI_Assistant/           組員工作區：AI 助理 + 整合版人臉辨識
├── Face_Recognition/       組員工作區：人臉辨識（已在眼鏡實機運作）
├── Obstacle_Recognition/   組員工作區：YOLO 8 類訓練中
├── Audio_Navigation/       組員工作區：語音導航
├── Text_Recognition/       組員工作區：OCR
├── guide-glasses/          ★ 最終整合系統
└── docs/                   本資料夾
```

**開發規則**

- 五個功能資料夾是五位成員各自的工作區，**guide-glasses 不修改它們**
- 需要引用時**複製**過來重新整合，原資料夾保持可供組員繼續開發
- Git 只保留 `main` 一個分支，直接 `git push origin HEAD:main`

---

## 五個必須知道的事實

**1. Rokid Glasses 就是一台 Android 12 裝置。**
執行 YodaOS-Sprite（API 32），APK 直接安裝執行。標準 CameraX、TextToSpeech、
SensorManager 全部可用。`Face_Recognition/` 已在眼鏡上實機驗證過這一點。

**2. 幾乎用不到 CXR SDK。**
App 跑在眼鏡上，標準 Android API 就夠了。詳見
[`TECHNICAL_NOTES.md`](TECHNICAL_NOTES.md) §1。

**3. 硬體是主要約束。**
2GB RAM、210mAh（約 4 小時）、12MP 相機、**沒有 GPS**、有 IMU。
端側模型總量要控制在 400MB 內，相機建議 2–5fps 而非 30fps。

**4. 沒有 GPS 擋住導航。**
IMU 可以做「跟著走」，做不到「知道在哪」。需要架構決策，見
[`ROADMAP.md`](ROADMAP.md) §3。

**5. 很多「規格」還沒實測。**
IMU 軸數、Play Services 有無、相機視角、`SpeechRecognizer` 可用性都是未知。
App 內建「測試相機」「測試感測器」兩個語音自我檢測指令就是為了這個。

---

## guide-glasses 目前狀態

> 完整快照見 [`STATUS.md`](STATUS.md)，待辦清單見 [`TASKS.md`](TASKS.md)。

**完成度約 55%，196 個純 JVM 單元測試全過。**

| 功能 | 狀態 |
|---|---|
| AI 語音助理（雙層意圖路由） | ✅ |
| 語音辨識 / 合成（Android 原生） | ✅ |
| 播報優先級仲裁 | ✅ |
| 相機（CameraX） | ✅ |
| OCR 朗讀（ML Kit 中文離線 + 分段 + 控制） | ✅ |
| **人臉辨識** | ✅ **端側或遠端擇一，兩條都可用** |
| IMU 動作感測 + 相機模式自動切換 | ✅ |
| 障礙物偵測 | ⏸ 等 Obstacle_Recognition 的 `.tflite` |
| 導航 | 🔴 等架構決策（無 GPS） |
| 翻譯 | ❌ 未開始，無阻塞 |

---

## 文件變更說明

2026-08-05 之前有 `00`–`08` 共 9 份分析文件（4500+ 行），其中部分結論在
取得團隊實際狀況後被修正。已整併為現在的三份，**修正後的內容直接寫進正文**，
不再保留「修正提示」與被推翻的舊結論。

被修正的主要有三點，記錄於此以免重蹈覆轍：

| 當初的錯誤結論 | 實際狀況 |
|---|---|
| 「專案沒有真的用到 Rokid 眼鏡」 | Face_Recognition 早已在眼鏡上運作 |
| 「沒有連續影像串流 API 是最大風險」 | App 跑在眼鏡上，CameraX 直接給 30fps |
| 「五個獨立專案是結構性問題，應合併」 | 那是刻意的團隊分工 |

三者的共同成因是**從依賴宣告推測架構，而沒有先問**。
