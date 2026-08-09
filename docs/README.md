# Rokid AI 導盲眼鏡 — 文件導覽

最後更新：2026-08-08

---

## 先看哪一份

| 你要做什麼 | 看這份 |
|---|---|
| **第一次接手這個專案** | [`DEVELOPER_GUIDE.md`](DEVELOPER_GUIDE.md) ← 從零建置到接手開發，新手看這份 |
| 🔴 **眼鏡上跑不起來 / 沒聲音** | [`DEVICE_FINDINGS.md`](DEVICE_FINDINGS.md) ← 實機診斷，含可重跑的指令 |
| **知道做到哪裡了 / 交接給新對話** | [`STATUS.md`](STATUS.md) ← 現況快照，隨時更新 |
| **知道還有什麼沒做 / 勾待辦** | [`PROVISIONING.md`](PROVISIONING.md) | 每台眼鏡的一次性佈建。不做的話前景服務會被靜默擋掉 |
| [`PRESENTATION.md`](PRESENTATION.md) | 簡報素材：手機 → 眼鏡的移植實錄，含七次靜默失敗 |
| [`TASKS.md`](TASKS.md) ← 可勾選的清單 |
| **哪個功能放眼鏡／手機／雲端、為什麼** | [`ARCHITECTURE.md`](ARCHITECTURE.md) ← 分層決策，含 7 張 Mermaid 圖 |
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

**1. Rokid Glasses 是 Android 12 裝置，但語音堆疊不完整。**
執行 YodaOS-Sprite（API 32），APK 直接安裝執行。相機、感測器、OCR、翻譯、
障礙物偵測**都已在眼鏡實測通過**，但 **`TextToSpeech` 綁定失敗、
`SpeechRecognizer` 完全不存在** —— 目前無法用語音操作。
詳見 [`DEVICE_FINDINGS.md`](DEVICE_FINDINGS.md)。

**2. 幾乎用不到 CXR SDK。**
App 跑在眼鏡上，標準 Android API 就夠了。詳見
[`TECHNICAL_NOTES.md`](TECHNICAL_NOTES.md) §1。

**3. 硬體是主要約束，而且比原本以為的更嚴格。**
2GB RAM、210mAh（約 4 小時）、**沒有 GPS**、**沒有電子羅盤**、
**沒有 Google Play Services**、**相機擷取要 930ms**（全部 2026-08-08 實測）。

> ⚠️ 這台眼鏡有個反覆出現的陷阱：**`pm list features` 宣告有，實際上沒有**。
> GPS、前鏡頭、TTS 都中過。**任何硬體能力都要實測，不能看 API 宣告。**

**4. 導航難度上修：無 GPS **且**無電子羅盤。**
手機可以提供座標，但**朝向拿不到** —— 算不出「往左轉還是往右轉」。
架構決策（手機 companion 只當定位來源，播報仲裁留在眼鏡）仍然成立，
但導航本身比原先評估的難。見 [`ARCHITECTURE.md`](ARCHITECTURE.md) §5。

**5. 🔴 語音是目前的總阻塞，但功能驗證已有替代路徑。**
眼鏡上沒有語音輸入也沒有輸出。但 debug build 有廣播入口可以直接觸發任何功能，
TTS 失敗時也會把「本來要唸的話」印進 log —— **聽不到但看得到**。
五個核心功能已用這個方式在眼鏡上驗證通過。

---

## guide-glasses 目前狀態

> 完整快照見 [`STATUS.md`](STATUS.md)，待辦清單見 [`TASKS.md`](TASKS.md)。

**完成度約 78%，306 個純 JVM 單元測試全過。**

| 功能 | 狀態 |
|---|---|
| AI 語音助理（雙層意圖路由） | ✅ |
| 語音辨識 / 合成 | 🔴 **眼鏡上不可用**（無 RecognitionService、TTS 綁不上） |
| 播報優先級仲裁 | ✅ |
| 相機（CameraX） | ✅ |
| OCR 朗讀（ML Kit 中文離線 + 分段 + 控制） | ✅ |
| **人臉辨識** | ✅ 端側（ONNX），瀏覽器註冊 + 語音同步 |
| IMU 動作感測 | ✅ |
| **翻譯**（ML Kit + OCR 串接 + 口述內容） | ✅ |
| **障礙物偵測**（YOLOv8 八類） | ✅ |
| 導航 | 🟡 定位抽象 + 幾何完成 |

> 🟡 **Rokid Glasses 實測**：相機／OCR／障礙物／翻譯／感測器**全部通過**。
> 🔴 語音（TTS + STT）不可用。見 [`DEVICE_FINDINGS.md`](DEVICE_FINDINGS.md)。

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
| 「眼鏡是標準 Android 裝置，原生 TTS/STT 都能用」 | **2026-08-08 實機推翻**：STT 不存在、TTS 綁定失敗 |
| 「不能升到 AGP 9.x」 | 2026-08-07 用兩個相容開關解掉了 |
| 「眼鏡有 GPS 硬體特徵就有 GPS」 | 宣告有、實際無 provider。前鏡頭、TTS 同樣 |

前三者的共同成因是**從依賴宣告推測架構，而沒有先問**。
第四項的成因不同 —— 那是**從「它是 Android 裝置」推論「標準 API 都可用」**，
在精簡版系統上不成立。兩種都是同一類錯誤：**用推論代替實測**。
