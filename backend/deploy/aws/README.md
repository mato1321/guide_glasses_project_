# AWS Lambda 版部署（AWS Academy Learner Lab）

把同一個 FastAPI 後端部署成 **AWS Lambda ＋ Function URL**，不需要家裡的電腦 24 小時開機。
App 端用 `aws` 版本（眼鏡「導盲眼鏡 AWS」、手機「導盲定位 AWS」），可以和 Cloudflare 版同時裝著。

```
眼鏡／手機 ──HTTPS（X-Api-Key）──► Lambda Function URL ──► Lambda（FastAPI，Mangum）
                                                           ├─► DynamoDB：手機最新位置
                                                           └─► OpenAI、Google Routes、TDX、Google Cloud Vision
```

## 會建立的資源（區域預設 us-west-2）

| 資源 | 名稱 | 用途 |
|---|---|---|
| Lambda | `guideglasses-backend` | Python 3.12、1024 MB、逾時 30 秒，執行角色用 Learner Lab 內建的 `LabRole` |
| Function URL | （自動產生） | 公開的 HTTPS 網址；驗證由後端的 `X-Api-Key` 負責 |
| DynamoDB | `guideglasses-location` | 只存「最新一筆」手機位置，隨需計費，一天沒更新自動刪除 |
| S3 bucket | `guideglasses-deploy-<帳號>-<區域>` | 只放部署用的 zip，不公開 |
| CloudWatch Logs | `/aws/lambda/guideglasses-backend` | 紀錄只保留 7 天 |

## 與 Cloudflare 版的差異

| 項目 | Cloudflare 版 | AWS 版 |
|---|---|---|
| 後端在哪 | 家中電腦 | Lambda，電腦不用開 |
| 手機位置 | 存在記憶體 | 存在 DynamoDB（Lambda 可能同時有好幾個執行個體） |
| 確認公車 | YOLO 框出 LED 看板再辨識 | 不用 YOLO（torch 太大，Lambda 放不下），整張照片交給 Google Vision |
| `/ws` | 有（目前沒有 App 用） | 沒有（Function URL 不支援 WebSocket） |
| 金鑰 | `backend/.env` | Lambda 環境變數（來自 `backend/.env.aws`） |
| 人臉照片同步 | 本機的註冊工具 | 同左，照片不上雲端 |

## 部署步驟

以下指令都在 `backend` 資料夾執行。

### 1. 準備 AWS 憑證（每次開 Lab 都要做）

1. 登入 AWS Academy，進入 Learner Lab，按 **Start Lab**，等左上角變綠燈。
2. 按 **AWS Details** → AWS CLI 旁的 **Show**，把整段內容貼到 `%USERPROFILE%\.aws\credentials`。
3. Learner Lab 的憑證幾個小時就會過期，**只有部署時需要**。Lambda 執行時用的是 `LabRole`，不受影響。

### 2. 設定金鑰

```bash
copy .env.aws.example .env.aws
```

打開 `.env.aws` 填入各項金鑰。`GUIDEGLASSES_API_KEY` 建議另外產生一把，不要和 Cloudflare 版共用：

```bash
.venv\Scripts\python -c "import secrets; print(secrets.token_urlsafe(32))"
```

### 3. 打包

```bash
.venv\Scripts\python deploy\aws\build_lambda.py
```

這一步會直接下載 Linux 版的套件，所以在 Windows 上也能打包，不需要 Docker。產出 `build\lambda.zip`，約 80 MB，解壓後約 235 MB；Lambda 的上限是 250 MB。

### 4. 部署

```bash
.venv\Scripts\python deploy\aws\deploy.py deploy
```

完成後會：
- 自動驗證三件事：`/health` 正常、沒帶金鑰時被拒絕（401）、帶金鑰可以讀到位置；
- 印出要寫進 `apps/local.properties` 的設定，例如：

```properties
guideglasses.aws.busApiEndpoint=https://xxxx.lambda-url.us-west-2.on.aws
guideglasses.aws.llmEndpoint=https://xxxx.lambda-url.us-west-2.on.aws/route
guideglasses.aws.apiKey=<.env.aws 的 GUIDEGLASSES_API_KEY>
```

重複執行 `deploy` 是安全的：已存在的資源只會更新程式與設定，Function URL 不會變。

### 5. 建置並安裝 AWS 版 App

```bash
cd ..\apps
gradlew.bat :app:assembleAwsDebug :companion:companion-app:assembleAwsDebug
adb -s <眼鏡序號> install -r -g app\build\outputs\apk\aws\debug\app-aws-debug.apk
adb -s <手機序號> install -r companion\companion-app\build\outputs\apk\aws\debug\companion-app-aws-debug.apk
adb -s <眼鏡序號> shell cmd appops set com.guideglasses.aws RUN_ANY_IN_BACKGROUND allow
```

使用時打開眼鏡的「**導盲眼鏡 AWS**」和手機的「**導盲定位 AWS**」。AWS 版的手機直連用 port 8766，和 CF 版的 8765 不衝突。

## 查看狀態、刪除

```bash
.venv\Scripts\python deploy\aws\deploy.py status
.venv\Scripts\python deploy\aws\deploy.py destroy --yes
```

`destroy` 會刪除 Lambda、Function URL、DynamoDB 表（含最新位置）、部署用的 bucket 與紀錄。課程結束前記得執行。

## 費用（估計，以 AWS 官網為準）

- Lambda 與 DynamoDB 都是**用多少付多少，閒置不收費**。
- 手機開著時大約每秒回報一次位置。就算一整個月 24 小時都開著，Lambda 加 DynamoDB 約每月 US$3–4；只在測試和拍攝時開，每月不到 US$1。
- S3（一個 80 MB 的 zip）與 7 天的紀錄，費用可以忽略。
- 不會建立 EC2、NAT Gateway、Elastic IP 這類「開著就計費」的資源。
- OpenAI、Google、TDX 的費用另外計算，不在 AWS 額度內。

## 常見問題

| 狀況 | 原因與處理 |
|---|---|
| `找不到 AWS 憑證` 或 `憑證無效或已過期` | 重開 Lab，重貼 `%USERPROFILE%\.aws\credentials` |
| 呼叫網址回 403 | Function URL 的兩條公開權限少了一條。重新執行 `deploy` 會自動補上 |
| 第一次呼叫很慢 | 冷啟動，第一次要載入套件，約十幾秒；之後就快了 |
| `確認公車` 回「未設定」 | `.env.aws` 的 `GOOGLE_APPLICATION_CREDENTIALS` 沒填或路徑錯，改好後重新 `deploy` |
| 想看錯誤紀錄 | AWS Console → CloudWatch → 紀錄群組 `/aws/lambda/guideglasses-backend` |

> ⚠️ 尚未驗證：Learner Lab 結束 session（Stop Lab）之後，Function URL 還能不能呼叫。
> 部署完成後要實測一次；如果不行，出門前就要先 Start Lab。
