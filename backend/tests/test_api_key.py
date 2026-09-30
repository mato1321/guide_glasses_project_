"""共用金鑰驗證（main.py 的 require_api_key）。

跑法：
    cd backend
    .venv\\Scripts\\python -m pip install -r requirements-dev.txt
    .venv\\Scripts\\python -m pytest
"""

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from app import config
from app.main import app

KEY = "test-key-0123456789"


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setattr(config, "API_KEY", KEY)
    return TestClient(app)


def test_health_不需要金鑰(client):
    assert client.get("/health").status_code == 200


def test_沒帶金鑰回_401(client):
    response = client.get("/current-location")
    assert response.status_code == 401
    assert response.json()["success"] is False


def test_金鑰錯誤回_401(client):
    assert client.get("/current-location", headers={"X-Api-Key": "wrong"}).status_code == 401


def test_金鑰正確照常回應(client):
    response = client.post(
        "/update-location",
        json={"lat": 25.03, "lng": 121.56},
        headers={"X-Api-Key": KEY},
    )
    assert response.status_code == 200
    assert client.get("/current-location", headers={"X-Api-Key": KEY}).json()["lat"] == 25.03


def test_header_名稱不分大小寫(client):
    assert client.get("/current-location", headers={"x-api-key": KEY}).status_code == 200


def test_後端沒設定金鑰時全部拒絕而不是全部放行(monkeypatch):
    monkeypatch.setattr(config, "API_KEY", "")
    client = TestClient(app)

    assert client.get("/current-location").status_code == 503
    assert client.get("/current-location", headers={"X-Api-Key": "anything"}).status_code == 503
    assert client.get("/health").status_code == 200


def test_websocket_沒帶金鑰被拒(client):
    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect("/ws") as ws:
            ws.receive_text()


def test_websocket_帶金鑰可以連(client):
    with client.websocket_connect("/ws", headers={"X-Api-Key": KEY}) as ws:
        ws.send_text("hi")
        assert ws.receive_text() == "echo: hi"
