# guide-glasses 後端

給眼鏡／手機呼叫的重運算服務（人臉辨識、場景描述、自由語句 STT、
OCR 長文備援、LLM 意圖解析）。原則見 `docs/LATENCY_PLAN.md`：
**安全相關（障礙物）永遠留在眼鏡本地，不會經過這裡。**

> 這個資料夾是**獨立於 `guide-glasses/`（Android App）的 Python 專案**，
> 刻意放在 repo 根目錄跟 `guide-glasses/` 平行，而不是巢狀在它裡面 ——
> 兩種語言用兩個編輯器開：Android Studio 開 `guide-glasses/`，
> VS Code 開這個資料夾（`backend/`）。這樣 Android Studio 不會把 Python
> 檔案掃進 Gradle 專案，VS Code 也不會看到一堆 `.gradle`／建置產物。

## 目前狀態

`/health`、`/route`、`/ws` 是走路骨架，`/route` 還沒接真正的 LLM。

公車查詢、手機定位轉傳、英→繁中翻譯已經整併自舊原型
（`api/GPS_app.py`、`api/BUS_app.py`、`api/translate_api.py`），
邏輯搬過來但金鑰／模型路徑全部改讀環境變數，見「設定金鑰與模型路徑」。

之後每個新功能（人臉辨識、場景描述…）都會在同一條 WebSocket 上
加一種訊息類型，不是各自開一條新連線 —— 避免每次呼叫都要重新握手
（那在行動網路上是 200~500ms 的隱藏成本）。

## 工作流程：先本地測試，確認沒問題才推上 GitHub

1. 在自己電腦上跑起來、手動測過
2. 用手機（同一個 Wi-Fi）連過一次，確認手機真的連得到電腦
3. 沒問題才 commit / push

**不要跳過第 1、2 步直接推。**

> ⚠️ **Python 版本建議 3.11 或 3.12**，不要用 3.13+。`opencv-python-headless`
> 與 `ultralytics`（`/bus-ocr` 用的）在很新的 Python 版本上常常還沒有預編譯
> whl，pip 會改成本機編譯，實測在 Python 3.14 上安裝**卡超過 40 分鐘沒有
> 任何輸出**。其餘功能（`/health`、`/route`、`/bus-plans`、`/eta`、定位轉傳、
> 翻譯）不需要這兩個套件，先只裝輕量依賴一樣能跑，見下方指令。

## 本地怎麼跑

```bash
cd backend
python -m venv .venv

# Windows
.venv\Scripts\activate
# macOS / Linux
source .venv/bin/activate

pip install -r requirements.txt
cp .env.example .env   # 填入真正的金鑰，.env 不會進版控（見下方「設定金鑰與模型路徑」）
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

只想先跑 `/health`、`/route`、`/bus-plans`、`/eta`、定位轉傳、翻譯（不含
`/bus-ocr`）？跳過 `opencv-python-headless`／`numpy`／`ultralytics`／
`google-cloud-vision` 這幾個重依賴，只裝其他的：

```bash
pip install fastapi==0.115.6 "uvicorn[standard]==0.32.1" websockets==13.1 \
  python-dotenv==1.0.1 python-multipart==0.0.20 requests==2.32.3 deep-translator==1.11.4
```

（實測過：這樣裝幾秒鐘就完成，伺服器正常啟動，除了 `/bus-ocr` 會因為
少裝 `cv2` 回 500 之外其他端點都正常。）

`--host 0.0.0.0` 是關鍵 —— 預設的 `127.0.0.1` 只有電腦自己連得到，
手機在同一個 Wi-Fi 下連不進來。

## 怎麼確認活著

```bash
curl http://127.0.0.1:8000/health
# {"status":"ok","time":1234567890.123}
```

手機要連的話，先用 `ipconfig`（Windows）或 `ifconfig`（macOS/Linux）
查電腦在區網的 IP（通常長得像 `192.168.x.x`），然後在手機瀏覽器打開：

```
http://<電腦的區網IP>:8000/health
```

手機和電腦要在**同一個 Wi-Fi** 下才連得到 —— 手機開行動數據、或電腦接的是
另一個 Wi-Fi，都連不到。

## 接到 guide-glasses App

在 `guide-glasses/local.properties` 加兩行（IP 換成你電腦的區網 IP）：

```
guideglasses.llmEndpoint=http://192.168.1.5:8000/route
guideglasses.busApiEndpoint=http://192.168.1.5:8000
```

`llmEndpoint` 對到既有的 `RemoteLlmIntentGateway`；`busApiEndpoint` 對到
`ai-navigation` 的 `HttpBusPlanningGateway`／`HttpBusOcrGateway`／
`PhoneCompanionLocationProvider`。**手機 companion-app** 的
`local.properties`（在它自己的 module 目錄或共用同一份）也要設定同一個
`guideglasses.busApiEndpoint`，兩邊本來就該指到同一台電腦。

不裝 App 也能用 curl 確認格式對不對：

```bash
curl -X POST http://127.0.0.1:8000/route \
  -H "Content-Type: application/json" \
  -d "{\"utterance\":\"你好\",\"history\":[],\"tools\":[]}"

curl "http://127.0.0.1:8000/bus-plans?origin=25.03,121.51&dest=25.05,121.55"
curl "http://127.0.0.1:8000/eta?lat=25.03&lng=121.51&bus=307&walk_sec=180"
curl "http://127.0.0.1:8000/current-location"
```

## 設定金鑰與模型路徑

三個功能各自需要不同的金鑰／檔案，全部從環境變數讀（見 `app/config.py`），
留空時對應端點會回傳明確的「未設定」訊息，而不是用壞掉的金鑰打 API：

| 功能 | 環境變數 | 從哪裡拿 |
|---|---|---|
| `/bus-plans` 路線規劃 | `GOOGLE_MAPS_API_KEY` | Google Cloud Console 的 Routes API 金鑰 |
| `/eta` 到站時間 | `TDX_CLIENT_ID`、`TDX_CLIENT_SECRET` | TDX 運輸資料流通服務會員中心 |
| `/bus-ocr` 車頭 OCR | `GOOGLE_APPLICATION_CREDENTIALS` | GCP 服務帳戶金鑰（json），放 `credentials/`（已排除版控） |
| `/bus-ocr` 車頭 OCR | `BUS_OCR_MODEL_PATH`（選填） | YOLO 權重，預設 `models/best.pt`（已排除版控） |

複製 `.env.example` 成 `.env` 並填值。

> ⚠️ 如果你是從舊原型 `api/GPS_app.py`、`api/BUS_app.py` 搬過來的金鑰：
> 那兩把（Google Maps API Key、TDX Client Secret）先前是明碼寫在原始碼裡，
> 等同已經外洩。填進 `.env` 之前，請先去 Google Cloud Console 與 TDX
> 會員中心**撤銷重發**，不要沿用舊值。

## 資料夾

```
backend/
├── app/
│   ├── main.py           FastAPI app：掛載所有 router + health/route/ws
│   ├── config.py         金鑰／路徑統一從環境變數讀
│   ├── bus_logic.py       公車路線規劃／到站時間／車頭 OCR 的純邏輯
│   └── routers/
│       ├── location.py   手機 GPS 座標中繼
│       ├── bus.py        /bus-plans、/eta、/bus-ocr
│       └── translate.py  /translate、/latest-text
├── credentials/           GCP 服務帳戶金鑰（已排除版控，需自己放）
├── models/                YOLO 權重 best.pt（已排除版控，需自己放）
├── .env.example            複製成 .env 並填值
├── requirements.txt
└── .gitignore             擋掉 .venv、模型檔、.env、credentials/ —— 這些不該進 git
```

## 下一步

依 `docs/LATENCY_PLAN.md` 的順序，一次加一個：

1. 場景描述（送一張圖 → 回一句話）
2. 人臉辨識（可參考 `Face_Recognition/Python/` 既有的 InsightFace 用法，
   但這是獨立一份，不會改到那邊的檔案）
3. 自由語句 STT（Whisper）
4. OCR 長文備援
5. LLM 意圖解析（「帶我去⋯」）
6. 真正的路線規劃品質調校（目前 `/bus-plans`、`/eta` 已可用，但未接 TDX
   即時公車動態以外的資料來源，也還沒有自動化測試）

## 絕對不要進版控的東西

`.gitignore` 已經擋了，但推上 GitHub 前**手動再檢查一次**：
- `.env`、任何 API 金鑰
- `credentials/` 底下的服務帳戶金鑰
- 模型權重檔（`.onnx` / `.pt` / `.safetensors`…）—— 太大，而且換機器要重下
- 人臉照片或任何使用者資料
