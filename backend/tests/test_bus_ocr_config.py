"""「確認公車」（/bus-ocr）的設定問題。

實測（2026-09-30）：.env 寫的是 ./credentials/⋯ 相對路徑，後端從別的資料夾啟動時
照工作目錄找不到檔案，每次都 500，log 裡一大串 google-auth 的 traceback。
"""

from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app import bus_logic, config
from app.main import app

KEY = "test-key-0123456789"


def test_相對路徑以_backend_為準而不是工作目錄():
    assert config._backend_path("./credentials/sa.json") == str((config.BACKEND_ROOT / "credentials" / "sa.json").resolve())
    assert config._backend_path("") == ""


def test_絕對路徑照用(tmp_path):
    absolute = tmp_path / "sa.json"
    assert config._backend_path(str(absolute)) == str(absolute)


def test_金鑰檔不存在時回_503_並說出是哪個檔案(monkeypatch, tmp_path):
    cv2 = pytest.importorskip("cv2")
    np = pytest.importorskip("numpy")

    missing = tmp_path / "不存在.json"
    monkeypatch.setattr(config, "API_KEY", KEY)
    monkeypatch.setattr(config, "GOOGLE_APPLICATION_CREDENTIALS", str(missing))
    monkeypatch.setattr(bus_logic, "detect_led_box", lambda image: None)  # 不跑 YOLO

    ok, jpeg = cv2.imencode(".jpg", np.zeros((32, 32, 3), np.uint8))
    assert ok
    response = TestClient(app).post(
        "/bus-ocr",
        data={"bus_no": "307"},
        files={"image": ("bus.jpg", jpeg.tobytes(), "image/jpeg")},
        headers={"X-Api-Key": KEY},
    )

    assert response.status_code == 503
    assert str(missing) in response.json()["message"]
    assert response.json()["matched"] is False


def test_Lambda_上從環境變數的內容寫出金鑰檔(tmp_path):
    path = config._google_credentials_from_json('{"type": "service_account"}', tmp_path)
    assert Path(path).read_text(encoding="utf-8") == '{"type": "service_account"}'
    assert config._google_credentials_from_json("", tmp_path) == ""


@pytest.mark.parametrize("box", [(4, 6, 40, 20, 0.87), None])
def test_存下原圖_畫框圖與結果給影片剪輯(tmp_path, box):
    cv2 = pytest.importorskip("cv2")
    np = pytest.importorskip("numpy")
    import json

    image = np.zeros((48, 64, 3), np.uint8)
    saved = bus_logic.save_debug_images(image, box, "310", "310 台北車站", True, tmp_path / "debug")

    stem = saved.name[: -len("_box.jpg")]
    assert saved.is_file()
    assert (saved.parent / f"{stem}_raw.jpg").is_file()
    result = json.loads((saved.parent / f"{stem}.json").read_text(encoding="utf-8"))
    assert result["ocr_text"] == "310 台北車站"
    assert result["matched"] is True
    assert (result["led_box"] is None) == (box is None)
    # 畫了框的圖跟原圖不一樣
    assert not np.array_equal(cv2.imdecode(np.fromfile(str(saved), np.uint8), cv2.IMREAD_COLOR), image)


def test_同一秒內連續辨識不會互相覆蓋(tmp_path):
    np = pytest.importorskip("numpy")
    pytest.importorskip("cv2")

    image = np.zeros((16, 16, 3), np.uint8)
    first = bus_logic.save_debug_images(image, None, "310", "", False, tmp_path)
    second = bus_logic.save_debug_images(image, None, "310", "", False, tmp_path)
    assert first != second
    assert len(list(tmp_path.glob("*.json"))) == 2
