"""guide-glasses 後端 —— 目前是走路骨架，還沒有任何 AI。

## 為什麼先寫一個什麼都不做的版本

先確定「連得上、斷得乾淨、health check 有回應」，之後每加一個功能
（人臉辨識、場景描述、STT、OCR 備援、LLM 意圖解析…）都是在同一條
WebSocket 上加一種訊息類型，而不是各自開一條新連線 —— 這是
docs/LATENCY_PLAN.md 裡「一條長連線，不要每次重連」的落地版本。

## 本地測試（先在自己電腦上跑，確認沒問題再推上 GitHub）

    cd backend
    python -m venv .venv
    .venv/Scripts/activate        # Windows；macOS/Linux 用 source .venv/bin/activate
    pip install -r requirements.txt
    cp .env.example .env          # 填入真正的金鑰，.env 不會進版控
    uvicorn app.main:app --reload --host 0.0.0.0 --port 8000

確認活著：
    curl http://127.0.0.1:8000/health

手機 App 要連這台電腦時，把 local.properties 的位址換成你電腦的區網 IP：
    guideglasses.cloudflare.llmEndpoint=http://<你電腦的區網IP>:8000/route
    guideglasses.cloudflare.busApiEndpoint=http://<你電腦的區網IP>:8000
（用 `ipconfig` 查區網 IP；手機和電腦要在同一個 Wi-Fi 下才連得到。）

## 公車查詢／定位轉傳／翻譯

整併自舊原型 `api/GPS_app.py`（Flask）、`api/BUS_app.py`、
`api/translate_api.py`（各自獨立的 FastAPI），原本三個服務各佔一個 port，
現在是同一個 app 底下的三個 router（`app/routers/`），只需要跑一個進程。
金鑰與模型路徑一律從環境變數讀，見 `app/config.py` 與 `.env.example`。
"""
from __future__ import annotations

import sys
import time

# Windows 上 stdout 被導向（Windows 服務、管線、IDE）時，Python 用系統編碼 cp950，
# print 表情符號（📍 ❌ ⚠️）會丟 UnicodeEncodeError。實測 /update-location
# 因此回 500 —— 座標其實已經存下，手機卻每 5 秒顯示「後端回傳錯誤」；
# 例外處理裡的 print("❌ ...") 也會自己炸掉、蓋掉原本的錯誤。統一改成 UTF-8。
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

import hmac

from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool

from . import config, llm
from .routers import bus, location, navigation, translate

app = FastAPI(title="guide-glasses backend")

# ===== 共用金鑰驗證（企畫書 P0：公開端點加上驗證，缺少即回 401）=====
#
# 後端一旦經由 Cloudflare 通道公開，網址就是公開的。沒有這一層，
# 任何人掃到網址就能無限呼叫 /route（OpenAI）、/bus-plans（Google Routes）。

API_KEY_HEADER = "x-api-key"

# 不需要金鑰的路徑：App 用 /health 判斷「網路通不通」，要能在設定金鑰之前就測。
PUBLIC_PATHS = frozenset({"/health"})


def _key_problem(provided: str | None) -> tuple[int, str] | None:
    """金鑰有問題時回傳 (HTTP 狀態碼, 說明)；沒問題回 None。"""
    if not config.API_KEY:
        # 沒設定就全部拒絕而不是全部放行：忘了設比設錯更常發生，
        # 而全部放行的後端一公開就是讓人刷額度。
        return 503, "後端尚未設定 GUIDEGLASSES_API_KEY，拒絕所有請求（見 .env.example）"
    # 固定時間比較，避免從回應時間逐字元猜出金鑰。
    if not provided or not hmac.compare_digest(provided.encode(), config.API_KEY.encode()):
        return 401, "缺少或錯誤的 X-Api-Key"
    return None


@app.middleware("http")
async def require_api_key(request: Request, call_next):
    if request.url.path in PUBLIC_PATHS:
        return await call_next(request)
    problem = _key_problem(request.headers.get(API_KEY_HEADER))
    if problem:
        status, message = problem
        return JSONResponse(status_code=status, content={"success": False, "message": message})
    return await call_next(request)


if not config.API_KEY:
    print("⚠️ 沒有設定 GUIDEGLASSES_API_KEY：除了 /health 之外所有請求都會回 503")

app.include_router(location.router)
app.include_router(bus.router)
app.include_router(navigation.router)
app.include_router(translate.router)


@app.get("/health")
def health() -> dict[str, object]:
    """App 啟動時 ping 這個，確認「後端活著、網路通不通」跟「後端邏輯對不對」是兩件事。"""
    return {"status": "ok", "time": time.time()}


@app.post("/route", response_model=llm.RouteResponse)
async def route(payload: llm.RouteRequest) -> llm.RouteResponse:
    """
    對應 guide-glasses 手機 App 既有的 `RemoteLlmIntentGateway`（見
    `ai/ai-agent/src/main/kotlin/.../RemoteLlmIntentGateway.kt`）——
    Kotlin 端完全不用改，這裡只是協定的伺服器端實作。

    真正呼叫 OpenAI 做 function calling，把工具清單（Kotlin 傳來的
    `AssistantIntent.callableTools`）交給模型判斷要不要呼叫、呼叫哪個、
    參數是什麼；不對應任何工具時走一般對話回覆。
    """
    try:
        return await run_in_threadpool(llm.route_utterance, payload)
    except llm.NotConfiguredError:
        # 回 200 + reply，而不是丟錯誤——手機端會把這句話當成正常的助理
        # 回覆唸出來，比播「服務暫時忙碌」更準確地告訴使用者現在缺什麼。
        return llm.RouteResponse(reply="LLM 還沒設定好，請稍後再試，或直接說本地指令")
    except Exception as e:
        print("❌ /route Exception:", e)
        return llm.RouteResponse(reply="我現在沒辦法理解這句話，請再說一次")


@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket) -> None:
    """
    目前只會把收到的文字原樣送回去（echo），用來驗證連線本身通不通。

    之後每個功能上線時，會在這裡依訊息內容分派到對應的處理函式，
    例如 `{"type": "recognize_face", "image": "<base64>"}`。
    """
    # HTTP middleware 管不到 WebSocket，這裡另外驗證。1008 = policy violation。
    if _key_problem(websocket.headers.get(API_KEY_HEADER)):
        await websocket.close(code=1008)
        return

    await websocket.accept()
    try:
        while True:
            message = await websocket.receive_text()
            await websocket.send_text(f"echo: {message}")
    except WebSocketDisconnect:
        pass
