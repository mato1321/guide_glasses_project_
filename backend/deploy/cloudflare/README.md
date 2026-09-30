# Cloudflare 具名通道（Cloudflare 版的對外連線）

眼鏡與手機出門走 4G，連不到家裡的 `192.168.x.x`。這裡用 Cloudflare 具名通道把
家中 GPU 電腦上的 FastAPI 公開成固定網址 `https://api.<你的網域>`：

- 電腦上的 `cloudflared` **主動往外**連 Cloudflare，家用路由器不用開任何埠，也不怕浮動 IP
- 網址固定 —— 後端位址是編譯進 APK 的，換網址就要重新建置安裝
- 自動 HTTPS

**不要用 Quick Tunnel（`trycloudflare.com`）當正式用途**：每次執行網址都會變，
Cloudflare 官方也明說只供測試。之前 portable 版就是用它，網址失效後 App 整個連不到。

## 前置

1. Cloudflare 帳號（免費方案即可）
2. 一個網域，DNS 交給 Cloudflare 管理。沒有的話可在 Cloudflare Registrar 買，約 US$10／年
3. `cloudflared` —— 這台電腦已安裝在 `C:\Program Files (x86)\cloudflared\`（2026.9.1）
4. 後端的 `.env` 已設定 `GUIDEGLASSES_API_KEY`（見 `backend/.env.example`）。
   **通道一開，網址就是公開的**，沒有金鑰任何人都能用光 OpenAI／Google 的額度

## 建立通道（只做一次）

以下在 PowerShell 執行，`cloudflared` 路徑太長的話先加進 PATH。

```powershell
# 1. 登入：會開瀏覽器，選你的網域授權。完成後產生 %USERPROFILE%\.cloudflared\cert.pem
cloudflared tunnel login

# 2. 建立通道：產生 %USERPROFILE%\.cloudflared\<TUNNEL_UUID>.json（通道的憑證，不要進版控）
cloudflared tunnel create guideglasses

# 3. 把 api.<你的網域> 指到這條通道（會在 Cloudflare DNS 建一筆 CNAME）
cloudflared tunnel route dns guideglasses api.<你的網域>
```

4. 把 `config.yml.example` 複製成 `%USERPROFILE%\.cloudflared\config.yml`，填入 UUID、帳號、網域。

## 手動測試

開兩個 PowerShell：

```powershell
# 視窗 1：後端（只聽 127.0.0.1:8000）
powershell -ExecutionPolicy Bypass -File backend\deploy\cloudflare\start-backend.ps1

# 視窗 2：通道
cloudflared tunnel run guideglasses
```

在**手機關掉 Wi-Fi、只用 4G** 的情況下開瀏覽器確認：

| 網址 | 預期 |
|---|---|
| `https://api.<你的網域>/health` | `{"status":"ok",...}` |
| `https://api.<你的網域>/current-location` | 401（沒帶金鑰，代表驗證有生效） |

## 接到 App

`apps/local.properties`：

```
guideglasses.cloudflare.busApiEndpoint=https://api.<你的網域>
guideglasses.cloudflare.llmEndpoint=https://api.<你的網域>/route
guideglasses.cloudflare.apiKey=<與 backend/.env 的 GUIDEGLASSES_API_KEY 相同>
```

重新建置並安裝眼鏡端（`:app:assembleCloudflareDebug`）與手機端
（`:companion:companion-app:assembleCloudflareDebug`）。走 HTTPS 之後就不再需要 `adb reverse`。

## 開機自動啟動（企畫書第 2 週）

GPU 電腦重開後兩個都要自己起來，否則雲端功能全部失效（端側功能不受影響）。

**cloudflared**（系統管理員 PowerShell）：

```powershell
cloudflared service install
```

Windows 服務以 LocalSystem 身分執行，讀的是
`C:\Windows\System32\config\systemprofile\.cloudflared\`，
要把 `config.yml` 與 `<TUNNEL_UUID>.json` 複製過去，並把 `config.yml` 裡的
`credentials-file` 改成新路徑。

**FastAPI**：工作排程器 → 建立工作

- 觸發程序：「電腦啟動時」
- 動作：`powershell.exe`，引數 `-ExecutionPolicy Bypass -File <完整路徑>\backend\deploy\cloudflare\start-backend.ps1`
- 勾選「不論使用者登入與否均執行」

## 安全注意

- `X-Api-Key` 編進 APK，反編譯就拿得到。它擋的是「路人掃到網址」，不是刻意拆 APK 的人。
  可以再在 Cloudflare 儀表板加一條 Rate limiting 規則（免費方案有一條）限制每個 IP 的請求數
- `%USERPROFILE%\.cloudflared\` 底下的 `cert.pem` 與 `<TUNNEL_UUID>.json` 等同通道的鑰匙，
  不要放進 repo、不要傳給別人
- 金鑰外流時：改 `backend/.env` 的 `GUIDEGLASSES_API_KEY` → 重啟後端 → 改
  `apps/local.properties` → 重新建置安裝眼鏡與手機
