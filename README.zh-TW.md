# Guide Glasses — AI 導盲眼鏡

[English](README.md) | **繁體中文**

Guide Glasses 把 **Rokid 智慧眼鏡**變成視障者的語音助理。
使用者戴上眼鏡後，不需要看螢幕，也不需要按任何按鈕，只要用說的，
眼鏡就會告訴他前方有什麼、眼前的人是誰、招牌寫什麼，並一路帶他搭上正確的公車。

> 雲端負責「想得更好」，眼鏡負責「讓人安全」。
> 障礙物偵測、人臉辨識、文字朗讀與語音都**在眼鏡上離線執行**；
> 只有需要即時資料的查詢（公車路線、到站時間）才會上網。

---

## 特點

- **全語音操作，畫面上沒有按鈕**：18 句語音指令直接說就能用，不需要喚醒詞；想自由發問就說「我要說話」，或點一下觸控板。
- **安全功能不需要網路**：障礙物偵測（YOLOv8）、人臉辨識、文字辨識、翻譯、語音辨識與語音合成，全部在眼鏡上執行。
- **完整的公車出行流程**：規劃最快的公車 → 語音一步步帶路到上車站 → 公車進站時對準車頭，核對路線號碼。
- **手機就是 GPS**：眼鏡本身沒有 GPS，手機 App 透過**熱點直接**把位置傳給眼鏡，後端斷線時導航也不會中斷；後端只當備援。
- **自然語言助理**：LLM 聽得懂「我要搭公車去中正紀念堂」這類說法，還會修正語音辨識聽錯的台灣地名。
- **為真實裝置打造**：播報音量永遠開到最大；Cloudflare 通道重開、網址變了，也不必重新建置 App；同一副眼鏡可以同時裝三個版本。
- **重視隱私與安全**：人臉特徵只存在眼鏡上，並以 Android Keystore 保管的 AES-GCM 金鑰加密；後端沒有金鑰一律拒絕（fail-closed）；金鑰、照片與定位資料從不進版控。

## 功能一覽

直接說就好，不需要喚醒詞。

| 功能 | 怎麼說 | 需要網路 |
|---|---|---|
| 停止播報（導航也一起停） | 停止播報／不要說了 | 否 |
| 偵測障礙物（一次／持續） | 前面有什麼／持續偵測前方 | 否 |
| 認人 | 這是誰／這個人是誰／前面有沒有人 | 否 |
| 讀字、讀招牌 | 上面寫什麼／唸給我聽／這是哪裡／下一段 | 否 |
| 翻譯剛讀到的字 | 翻成英文 | 否（語言包需先下載一次） |
| 查公車（預設目的地） | 查公車路線 | 是 |
| 核對進站的公車 | 確認公車 | 是 |
| 自由發問、說出目的地 | 我要說話 → 嗶一聲 → 說內容 | 是 |
| 檢查狀態 | 出門前檢查 | 否 |
| 同步已登錄的人臉 | 同步人臉 | 區域網路 |
| 相機自我檢測 | 測試相機 | 否 |

**範例：搭公車**

1. 說「我要說話」，聽到嗶一聲後說「我要搭公車去○○」；或直接說「查公車路線」。
2. 眼鏡播報要搭幾路、在哪一站上車、下一班多久後到。
3. **自動開始步行導航**，用語音一步步帶到上車站。
4. 公車進站時面向車頭，說「確認公車」，眼鏡會讀出 LED 看板上的路線號碼，告訴你相不相符。
5. 說「停止播報」結束導航。

## 系統架構

```
 ┌──────────────────────────┐   Wi-Fi 熱點（直接傳 GPS）     ┌──────────────────────┐
 │  Rokid 智慧眼鏡（Android）│ ◄────────────────────────────► │  Android 手機         │
 │  • 語音指令、語音辨識     │                                │  • GPS（每秒一筆）    │
 │  • 障礙物／人臉／         │                                │  • 熱點直連伺服器     │
 │    文字辨識／翻譯         │                                │  • 後端網址設定       │
 │  • 離線語音播報、導航     │                                └──────────┬───────────┘
 └────────────┬─────────────┘                                           │
              │ HTTPS（Cloudflare 通道，X-Api-Key）                     │ HTTPS
              ▼                                                         ▼
 ┌────────────────────────────────────────────────────────────────────────────────┐
 │  後端 FastAPI（家中電腦）                                                       │
 │  /route（LLM 意圖）· /bus-plans · /eta · /walking-route · /bus-ocr · 定位轉傳   │
 └──────────┬──────────────────┬───────────────────┬──────────────────┬───────────┘
            ▼                  ▼                   ▼                  ▼
         OpenAI         Google Routes API      TDX（到站時間）   Google Cloud Vision
```

| 部分 | 負責什麼 | 技術 |
|---|---|---|
| 眼鏡 App | 語音指令、語音辨識、影像辨識、離線語音播報、導航狀態機、播報優先順序仲裁 | Kotlin、CameraX、Hilt、sherpa-onnx、ONNX Runtime、ML Kit |
| 手機 App | 每秒取得 GPS，經熱點直接傳給眼鏡，並轉送後端當備援 | Kotlin、Fused Location、前景服務 |
| 後端 | LLM 意圖解析、公車方案與到站時間、步行路線、公車車頭辨識、定位轉傳 | Python 3.12、FastAPI、OpenAI、Google Routes、TDX、Ultralytics YOLO、Google Cloud Vision |

## AI 模型

| 用途 | 模型 | 執行位置 | 授權 |
|---|---|---|---|
| 障礙物偵測（8 類：行人、汽車、機車、腳踏車、障礙物、斑馬線、導盲磚、人行道） | `obstacle_yolov8.onnx`，**團隊自行訓練**（以 YOLOv8n-seg 為基礎） | 眼鏡 | AGPL-3.0（Ultralytics） |
| 公車 LED 看板偵測 | `best.pt`，**團隊自行訓練**（Ultralytics YOLO） | 後端 | AGPL-3.0（Ultralytics） |
| 人臉特徵（512 維） | InsightFace `w600k_mbf`（MobileFaceNet、ArcFace） | 眼鏡 | 僅限非商業研究 |
| 英文語音合成 | Piper `en_US-amy-medium`（VITS） | 眼鏡 | 見模型說明 |
| 語音指令偵測 | sherpa-onnx zipformer 關鍵詞偵測模型 | 眼鏡 | 見 sherpa-onnx |
| 語音辨識（中文、串流） | `sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01` | 眼鏡 | 見 sherpa-onnx |
| 中文語音合成 | `matcha-icefall-zh-baker` ＋ HiFi-GAN 聲碼器 | 眼鏡 | 見 sherpa-onnx |
| 人臉偵測、文字辨識、翻譯 | Google ML Kit | 眼鏡 | Google ML Kit 條款 |
| 意圖解析、聊天 | OpenAI `gpt-4o-mini` | 雲端 | OpenAI 條款 |
| 公車號碼文字辨識 | Google Cloud Vision | 雲端 | Google Cloud 條款 |

模型來源與 SHA-256 校驗碼見 [`shared/models/README.md`](shared/models/README.md)。

## 專案結構

```
guide_glasses_project_/
├── README.md                        英文說明（GitHub 首頁）
├── README.zh-TW.md                  繁體中文說明（本檔案）
├── LICENSE                          MIT
│
├── apps/                            ★ Android 專案（Cloudflare 版與 AWS 版共用同一份程式碼）
│   ├── app/                         眼鏡 App「導盲眼鏡 CF／AWS」
│   │   ├── MainActivity.kt                  畫面（沒有按鈕）、觸控板點一下、開發用廣播
│   │   ├── GuideGlassesApplication.kt       啟動時把音量開到最大、版本交接
│   │   ├── GuideGlassesForegroundService.kt 前景服務：相機、麥克風在背景持續運作
│   │   ├── SensorHandoff.kt                 三個版本輪流使用相機與麥克風
│   │   ├── AnnouncementVolume.kt            播報音量 15/15
│   │   └── di/                              功能組裝、X-Api-Key、執行期更換後端網址
│   ├── companion/companion-app/     手機 App「導盲定位 CF／AWS」
│   │   ├── LocationReportService.kt         前景服務：GPS 送到後端
│   │   ├── LocalLocationServer.kt           熱點直連伺服器，眼鏡直接來問位置（port 8765／8766）
│   │   └── BackendUrlStore.kt               手機上設定的後端網址，會同步給眼鏡
│   ├── core/
│   │   ├── core-domain/             純 Kotlin 功能邏輯：語音指令、公車規劃與核對、步行導航、
│   │   │                            播報優先順序、人臉比對
│   │   ├── core-common/             共用工具
│   │   └── core-database/           人臉特徵（以 Android Keystore 金鑰加密）
│   ├── ai/
│   │   ├── ai-asr-offline/          離線語音辨識與語音指令偵測
│   │   ├── ai-tts-offline/          離線語音合成（中文、英文）
│   │   ├── ai-speech/               系統語音服務（備援）
│   │   ├── ai-agent/                LLM 意圖解析（後端 /route）
│   │   ├── ai-vision/               障礙物偵測（團隊自訓 obstacle_yolov8.onnx）
│   │   ├── ai-face/                 人臉偵測與特徵（InsightFace w600k_mbf）、照片同步
│   │   ├── ai-ocr/                  文字辨識（ML Kit）
│   │   ├── ai-translate/            翻譯（ML Kit）
│   │   └── ai-navigation/           手機定位（直連／經由後端）、公車與步行路線、車頭辨識
│   ├── glasses/                     相機（CameraX）與動作感測
│   ├── feature/feature-assistant/   助理的指令分派與提示音
│   ├── tools/face_enroll_server.py  人臉註冊工具（在瀏覽器上傳照片）
│   ├── keystore.properties.example  共用簽章設定範本
│   └── local.properties             （不進版控）後端網址與金鑰
│
├── backend/                         ★ FastAPI 後端
│   ├── app/
│   │   ├── main.py                  金鑰驗證、/route（LLM）、/health
│   │   ├── config.py                設定（路徑一律以 backend/ 為準）
│   │   ├── llm.py                   OpenAI 意圖解析、修正地名
│   │   ├── bus_logic.py             公車方案、到站時間、LED 看板 YOLO ＋ Cloud Vision
│   │   ├── navigation_logic.py      步行路線
│   │   ├── lambda_handler.py        AWS Lambda 進入點（Mangum）
│   │   └── routers/                 bus · location（記憶體或 DynamoDB）· navigation · translate
│   ├── tests/                       pytest（36 個；AWS 以 moto 在本機模擬）
│   ├── deploy/
│   │   ├── cloudflare/              Cloudflare 通道說明與啟動腳本
│   │   └── aws/                     Lambda 打包、部署腳本與說明
│   ├── requirements.txt             本機／Cloudflare 版
│   ├── requirements-lambda.txt      AWS Lambda 版
│   ├── requirements-dev.txt         測試與部署工具
│   ├── .env.example                 設定範本（Cloudflare 版）
│   ├── .env.aws.example             設定範本（AWS 版）
│   └── .env、credentials/、models/、debug/   （不進版控）金鑰、服務帳戶、LED 看板模型、辨識畫面
│
├── docs/                            架構、裝置實測紀錄、眼鏡佈建、專題計畫書、操作卡（PDF）、重整紀錄
├── edge/                            端側版（只能離線，已凍結，保留作為對照）
├── shared/models/                   模型清單與 SHA-256 校驗碼
└── backup/                          整理前的原始檔案，原封不動保留
```

## 開始使用

### 需要準備

- Rokid 智慧眼鏡（Android 12）與一支 Android 手機
- Windows 電腦，裝好 **Android Studio**（內附 JDK 17 以上）與 Android SDK 36
- **Python 3.12** 與 **cloudflared**
- API 金鑰：OpenAI、Google Maps Platform（Routes API）、TDX，以及 Google Cloud Vision 服務帳戶

### 1. 取得原始碼

```bash
git clone https://github.com/mato1321/guide_glasses_project_.git
cd guide_glasses_project_
```

語音辨識與語音指令的模型檔太大，沒有放進 Git。取得方式與校驗方法見 [`shared/models/README.md`](shared/models/README.md)，
下載後放到 `apps/ai/ai-asr-offline/src/main/assets/`。

### 2. 啟動後端

```bash
cd backend
py -3.12 -m venv .venv
.venv\Scripts\pip install -r requirements.txt
copy .env.example .env
```

打開 `.env` 填入各項金鑰，包括 `GUIDEGLASSES_API_KEY`（自己訂一組共用密鑰；沒填的話後端會拒絕所有請求）。接著啟動後端並開通道：

```bash
powershell -ExecutionPolicy Bypass -File deploy\cloudflare\start-backend.ps1 -Port 8001
```

```bash
cloudflared tunnel --url http://localhost:8001
```

用瀏覽器開 `https://<你的通道>.trycloudflare.com/health`，看到 `{"status":"ok"}` 就代表成功。

### 3. 設定並建置 App

建立 `apps/local.properties`（這個檔案不進版控，金鑰只會留在你的電腦）：

```properties
sdk.dir=C\:\\Users\\<你的帳號>\\AppData\\Local\\Android\\Sdk
guideglasses.cloudflare.busApiEndpoint=https://<你的通道>.trycloudflare.com
guideglasses.cloudflare.llmEndpoint=https://<你的通道>.trycloudflare.com/route
guideglasses.cloudflare.apiKey=<和 GUIDEGLASSES_API_KEY 相同>
```

（選用）把 `apps/keystore.properties.example` 複製成 `keystore.properties`，讓組員都用同一把簽章。

用 Android Studio 開啟 `apps` 資料夾，或用指令建置：

```bash
cd apps
gradlew.bat :app:assembleCloudflareDebug :companion:companion-app:assembleCloudflareDebug
```

### 4. 安裝

```bash
adb -s <眼鏡序號> install -r -g app/build/outputs/apk/cloudflare/debug/app-cloudflare-debug.apk
adb -s <手機序號> install -r companion/companion-app/build/outputs/apk/cloudflare/debug/companion-app-cloudflare-debug.apk
```

眼鏡第一次使用前要設定一次，讓 App 在背景不會被系統關掉，並且時間保持正確：

```bash
adb -s <眼鏡序號> shell cmd appops set com.guideglasses.cloudflare RUN_ANY_IN_BACKGROUND allow
adb -s <眼鏡序號> shell settings put global auto_time 1
```

### 5. 開始使用

1. 手機開熱點，眼鏡連上去。
2. 手機打開「**導盲定位 CF**」，會自動開始傳送位置。
3. 眼鏡打開「**導盲眼鏡 CF**」，等約 20 秒讓語音模型載入。
4. 說「出門前檢查」，聽到「可以出門了」就準備好了。

**通道網址變了？** 不必重新建置。在手機 App 的「後端網址」貼上新網址即可，眼鏡連著手機熱點時會自動同步；
否則用 `adb shell am broadcast -a com.guideglasses.cloudflare.DEBUG --es cmd SET_BACKEND --es url <新網址>` 設定。

## 測試

```bash
cd apps && gradlew.bat test            # 423 個單元測試
cd backend && .venv\Scripts\python -m pytest   # 36 個測試（AWS 在本機模擬）
```

debug 版也可以用 `adb` 廣播直接觸發任何功能，不必對眼鏡說話，例如
`--es cmd DETECT_OBSTACLES`、`--es cmd ASK --es text 你好`，完整清單見 `MainActivity.kt`。

## 常見問題

| 狀況 | 原因與處理 |
|---|---|
| 眼鏡說「目前沒有網路」，或 Wi-Fi 顯示「連線能力受限」 | 眼鏡時間錯了，所有 HTTPS 憑證都會被判定無效。開啟自動校時（見步驟 4）。 |
| 查公車說「拿不到定位」 | 確認手機 App 有開、手機有網路；室內定位較慢，最多要等 15 秒。 |
| 兩個聲音同時講話 | 舊的端側版「導盲眼鏡」也在執行，把它關掉。 |
| 說了沒反應 | 講清楚、靠近一點；或點一下觸控板，聽到嗶聲再講。 |

## 文件

- [操作卡（PDF）](docs/AI導盲眼鏡操作卡.pdf)
- [專題計畫書（PDF）](docs/AI導盲眼鏡專題計畫書.pdf)
- [系統架構](docs/ARCHITECTURE.md) · [裝置實測紀錄](docs/DEVICE_FINDINGS.md) · [眼鏡佈建](docs/PROVISIONING.md)
- [後端說明](backend/README.md) · [Cloudflare 通道部署](backend/deploy/cloudflare/README.md) · [AWS Lambda 部署](backend/deploy/aws/README.md)
- [2026-09-29 三版重整紀錄](docs/RESTRUCTURE_NOTES.md)

## 版本

| 版本 | 眼鏡 App | 後端 | 狀態 |
|---|---|---|---|
| 端側版（只能離線） | `edge/` | 無 | 凍結 |
| **Cloudflare 版** | `apps/`，flavor `cloudflare` | 家中電腦上的 FastAPI ＋ Cloudflare 通道 | **主力版本，已實測** |
| AWS 版 | `apps/`，flavor `aws` | AWS Lambda ＋ Function URL ＋ DynamoDB | 部署腳本完成，尚未部署 |

## 授權

原始碼採用 [MIT License](LICENSE)。第三方模型與函式庫依其各自的授權，
特別是 InsightFace 的模型權重僅限非商業研究使用，以 Ultralytics YOLO 訓練的模型適用 AGPL-3.0。
