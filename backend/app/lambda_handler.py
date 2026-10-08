"""
AWS Lambda 進入點：Function URL 的事件 → Mangum → 同一個 FastAPI app。

部署見 `deploy/aws/README.md`（handler 設成 `app.lambda_handler.handler`）。
本機與 Cloudflare 版用不到這個檔案，照舊用 uvicorn 跑 `app.main:app`。

Function URL 不支援 WebSocket，AWS 版沒有 `/ws`（目前也沒有 App 在用）。
"""

from mangum import Mangum

from .main import app

# app 沒有 startup／shutdown 事件，關掉 lifespan 省冷啟動時間。
handler = Mangum(app, lifespan="off")
