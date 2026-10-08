"""AWS Lambda 進入點（app.lambda_handler）：Function URL 的事件 → Mangum → FastAPI。

事件格式照 Lambda Function URL（payload 2.0）組，不會連到真的 AWS。
"""

import base64
import json
from types import SimpleNamespace

import pytest

from app import bus_logic, config
from app.lambda_handler import handler
from app.routers import location

KEY = "test-key-0123456789"
HOST = "abcdefg.lambda-url.us-west-2.on.aws"
CONTEXT = SimpleNamespace(function_name="guideglasses-backend", aws_request_id="test")


def url_event(method, path, *, query="", headers=None, body=None, base64_body=False):
    """Function URL 送進 Lambda 的事件。header 名稱一律小寫，跟 AWS 實際送的一樣。"""
    return {
        "version": "2.0",
        "routeKey": "$default",
        "rawPath": path,
        "rawQueryString": query,
        "headers": {"host": HOST, "x-forwarded-proto": "https", "x-forwarded-port": "443", **(headers or {})},
        "requestContext": {
            "accountId": "anonymous",
            "apiId": "abcdefg",
            "domainName": HOST,
            "domainPrefix": "abcdefg",
            "http": {"method": method, "path": path, "protocol": "HTTP/1.1", "sourceIp": "203.0.113.1",
                     "userAgent": "okhttp/4.12.0"},
            "requestId": "test",
            "routeKey": "$default",
            "stage": "$default",
            "time": "08/Oct/2026:12:00:00 +0000",
            "timeEpoch": 1_791_460_800_000,
        },
        "body": body,
        "isBase64Encoded": base64_body,
    }


def call(event):
    response = handler(event, CONTEXT)
    body = response["body"]
    if response.get("isBase64Encoded"):
        body = base64.b64decode(body).decode("utf-8")
    return response["statusCode"], (json.loads(body) if body else None)


@pytest.fixture(autouse=True)
def fresh_backend(monkeypatch):
    monkeypatch.setattr(config, "API_KEY", KEY)
    monkeypatch.setattr(location, "_store", location.MemoryLocationStore())


def test_health_不需要金鑰():
    status, body = call(url_event("GET", "/health"))
    assert status == 200
    assert body["status"] == "ok"


def test_沒帶金鑰回_401():
    status, body = call(url_event("GET", "/current-location"))
    assert status == 401
    assert body["success"] is False


def test_手機寫入位置後眼鏡讀得到():
    headers = {"x-api-key": KEY, "content-type": "application/json"}
    payload = json.dumps({"lat": 25.03, "lng": 121.56, "accuracy_m": 12.5, "fix_age_ms": 300})
    status, body = call(url_event("POST", "/update-location", headers=headers, body=payload))
    assert status == 200 and body["success"] is True

    status, body = call(url_event("GET", "/current-location", headers={"x-api-key": KEY}))
    assert status == 200
    assert (body["lat"], body["lng"]) == (25.03, 121.56)


def test_查詢字串會傳進端點():
    # 起點格式錯誤 → 400，代表 rawQueryString 有被解析並交給 /bus-plans。
    status, body = call(url_event("GET", "/bus-plans", query="origin=not-a-coordinate&dest=25.0,121.5",
                                  headers={"x-api-key": KEY}))
    assert status == 400
    assert body["plans"] == []


def test_上傳照片_base64_的二進位內容能正確解開(monkeypatch, tmp_path):
    # Function URL 把 multipart 的照片包成 base64。能讀到圖、走到檢查金鑰檔那一步，
    # 就代表 Mangum 正確解開了二進位內容（金鑰檔故意不存在 → 503）。
    cv2 = pytest.importorskip("cv2")
    np = pytest.importorskip("numpy")
    missing = tmp_path / "missing.json"
    monkeypatch.setattr(config, "GOOGLE_APPLICATION_CREDENTIALS", str(missing))
    monkeypatch.setattr(bus_logic, "detect_led_box", lambda image: None)

    ok, jpeg = cv2.imencode(".jpg", np.full((32, 32, 3), 128, np.uint8))
    assert ok
    boundary = "----guideglasses"
    multipart = (
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"bus_no\"\r\n\r\n310\r\n"
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"image\"; filename=\"bus.jpg\"\r\n"
        f"Content-Type: image/jpeg\r\n\r\n"
    ).encode() + jpeg.tobytes() + f"\r\n--{boundary}--\r\n".encode()

    status, body = call(url_event(
        "POST", "/bus-ocr",
        headers={"x-api-key": KEY, "content-type": f"multipart/form-data; boundary={boundary}"},
        body=base64.b64encode(multipart).decode(), base64_body=True,
    ))
    assert status == 503
    assert str(missing) in body["message"]
    assert body["bus_no"] == "310"
