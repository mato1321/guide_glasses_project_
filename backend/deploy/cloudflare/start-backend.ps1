# 用 backend\.venv 啟動 FastAPI（Cloudflare 版的 GPU 電腦端）。
#
# 只聽 127.0.0.1：外部的請求一律經過 Cloudflare 具名通道進來（cloudflared 在同一台
# 電腦上連 localhost:8000），不需要對區網或網際網路開任何埠。
# 給「工作排程器 → 開機時執行」用，見 README.md 的「開機自動啟動」。
#
# 用法：powershell -ExecutionPolicy Bypass -File start-backend.ps1 [-Port 8000]

param([int]$Port = 8000)

$backend = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$python = Join-Path $backend ".venv\Scripts\python.exe"

if (-not (Test-Path $python)) {
    Write-Error "找不到 $python。先在 backend 資料夾建立 venv：py -3.12 -m venv .venv，再 pip install -r requirements.txt"
    exit 1
}
if (-not (Select-String -Path (Join-Path $backend ".env") -Pattern "^GUIDEGLASSES_API_KEY=.+" -Quiet)) {
    Write-Warning ".env 沒有設定 GUIDEGLASSES_API_KEY —— 後端會拒絕 /health 以外的所有請求"
}

& $python -m uvicorn app.main:app --app-dir $backend --host 127.0.0.1 --port $Port
