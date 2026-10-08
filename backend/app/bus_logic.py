"""
公車路線規劃／到站時間／車頭 OCR 的共用邏輯。

整併自舊原型 `api/GPS_app.py`（路線規劃、到站時間）與 `api/BUS_app.py`
（車頭 LED OCR），邏輯本身沒有改變，只把金鑰與路徑從寫死的常數改成從
`app.config` 讀（見該檔案開頭的說明）。

對應的 Kotlin 呼叫端：
- `HttpBusPlanningGateway`（ai-navigation）→ /bus-plans、/eta
- `HttpBusOcrGateway`（ai-navigation）→ /bus-ocr
回傳的 JSON 欄位名稱刻意與原型完全一致，不能改，否則 Kotlin 那邊的
`@Serializable` data class 會解析失敗。

`cv2`／`numpy`／`ultralytics`／`google-cloud-vision` 都是只有 `/bus-ocr`
才需要的重依賴，刻意延遲到實際呼叫 OCR 相關函式時才 import——沒裝這些
套件也不影響 `/bus-plans`、`/eta`、定位轉傳、翻譯這幾個功能，跟
`AppResult`／`isAvailable` 那套「缺什麼只降級，不整個炸掉」的精神一致。
"""

from __future__ import annotations

import math
import random
import re
import time
from pathlib import Path
from typing import TYPE_CHECKING, Any, Dict, List, Optional
from urllib.parse import quote

import requests

if TYPE_CHECKING:
    import numpy as np

from . import config

ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"
TDX_TOKEN_URL = "https://tdx.transportdata.tw/auth/realms/TDXConnect/protocol/openid-connect/token"
TDX_BASE = "https://tdx.transportdata.tw/api/basic/v2"

CITIES = ["Taipei", "NewTaipei"]
DEFAULT_RADIUS_M = 1000
TIMEOUT = (5, 12)
SLEEP_EACH_CALL = 0.12
MAX_RETRIES = 6

YOLO_CONF = 0.25
YOLO_IMGSZ_LIST = [640, 960, 1280]
BUS_SCALE = 3.0
USE_CLAHE = True
MAX_OCR_CALLS = 3


class NotConfiguredError(Exception):
    """對應的金鑰／路徑還沒設定，呼叫端應該回傳明確訊息而不是讓例外炸開。"""


# ===== 文字正規化 =====

def normalize_route_name(route_name: str) -> str:
    if not route_name:
        return route_name or ""

    s = route_name.strip()

    for kw in ["平日", "假日", "例假日", "假日時段", "平日時段"]:
        s = s.replace(kw, "")

    s = re.split(r"\s*經", s, maxsplit=1)[0].strip()
    s = re.split(r"\s*繞", s, maxsplit=1)[0].strip()
    s = re.sub(r"(直)(行|達)(車|班次)?[^0-9A-Za-z一-鿿]*.*$", r"\1", s).strip()
    s = re.sub(r"(直)\s*(行|達)(車|班次)?.*$", r"\1", s).strip()

    # Google 叫「88區間車」，TDX 的路線名是「88區」（2026-09-30 以 TDX Route API
    # 查證：「88區間車」「88區間」查無、「88區」查得到）。不轉的話 /eta 找不到站牌，
    # 眼鏡只能唸出「最近一班 未知 到站」。
    s = re.sub(r"區間車?$", "區", s)

    return s


def normalize_text(s: str) -> str:
    if not s:
        return ""

    s = s.lower()
    for ch in (" ", "\n", "-", "_", "　"):
        s = s.replace(ch, "")

    return s


def parse_latlng(s: str):
    if not s:
        raise ValueError("Missing coordinates")

    parts = [p.strip() for p in s.split(",") if p.strip()]
    if len(parts) != 2:
        raise ValueError("Invalid coordinate format")

    return float(parts[0]), float(parts[1])


# ===== 路線規劃（Google Routes API） =====

def destination_waypoint(dest_lat=None, dest_lng=None, dest_name: str = "") -> dict:
    """
    目的地可以是座標（快捷指令「查公車路線」，設定檔固定值）或地名
    （開放式語句「帶我去台北車站」，LLM 抽出來的文字）——Google Routes API
    的 waypoint 本來就同時支援 `location.latLng` 跟 `address`，不需要另外
    接一支地理編碼服務把地名轉成座標，少一次呼叫、少一個失敗點。

    `navigation_logic.py` 的步行路線也共用這個函式，目的地的表示方式
    一致，不用維護兩份邏輯。
    """
    if dest_name:
        return {"address": dest_name}
    return {"location": {"latLng": {"latitude": dest_lat, "longitude": dest_lng}}}


def build_routes_request(origin_lat, origin_lng, dest_lat=None, dest_lng=None, dest_name: str = "") -> dict:
    return {
        "origin": {"location": {"latLng": {"latitude": origin_lat, "longitude": origin_lng}}},
        "destination": destination_waypoint(dest_lat, dest_lng, dest_name),
        "travelMode": "TRANSIT",
        "computeAlternativeRoutes": True,
        "languageCode": "zh-TW",
        "units": "METRIC",
        "transitPreferences": {"routingPreference": "LESS_WALKING"},
    }


def call_routes_api(origin_lat, origin_lng, dest_lat=None, dest_lng=None, dest_name: str = "") -> dict:
    if not config.GOOGLE_MAPS_API_KEY:
        raise NotConfiguredError("GOOGLE_MAPS_API_KEY 尚未設定")

    headers = {
        "Content-Type": "application/json; charset=utf-8",
        "X-Goog-Api-Key": config.GOOGLE_MAPS_API_KEY,
        "X-Goog-FieldMask": ",".join([
            "routes.duration",
            "routes.distanceMeters",
            "routes.legs.steps.travelMode",
            "routes.legs.steps.transitDetails",
            "routes.legs.steps.staticDuration",
            "routes.routeLabels",
        ]),
    }

    response = requests.post(
        ROUTES_URL,
        json=build_routes_request(origin_lat, origin_lng, dest_lat, dest_lng, dest_name),
        headers=headers,
        timeout=20,
    )
    response.raise_for_status()
    return response.json()


def duration_to_seconds(duration_str: str) -> int:
    if not duration_str:
        return 0
    return int(duration_str.replace("s", "").strip())


def extract_direct_bus_plan(route_obj: dict) -> Optional[dict]:
    legs = route_obj.get("legs", [])
    if not legs:
        return None

    steps = legs[0].get("steps", [])
    total_sec = duration_to_seconds(route_obj.get("duration", "0s"))

    bus_steps = []
    for st in steps:
        if st.get("travelMode") != "TRANSIT":
            continue
        td = st.get("transitDetails") or {}
        transit_line = td.get("transitLine") or {}
        vehicle = transit_line.get("vehicle") or {}
        if vehicle.get("type") == "BUS":
            bus_steps.append(st)

    if len(bus_steps) < 1:
        return None

    bus_step = bus_steps[0]
    td = bus_step.get("transitDetails") or {}
    transit_line = td.get("transitLine") or {}

    bus_no_raw = (transit_line.get("nameShort") or transit_line.get("name") or "").strip()
    bus_no = normalize_route_name(bus_no_raw)

    dep_stop = td.get("stopDetails", {}).get("departureStop", {})
    arr_stop = td.get("stopDetails", {}).get("arrivalStop", {})
    dep_loc = dep_stop.get("location", {}).get("latLng", {})
    arr_loc = arr_stop.get("location", {}).get("latLng", {})

    idx = steps.index(bus_step)
    walk_to_bus_sec = 0
    walk_to_dest_sec = 0
    for i, st in enumerate(steps):
        if st.get("travelMode") != "WALK":
            continue
        sec = duration_to_seconds(st.get("staticDuration", "0s"))
        if i < idx:
            walk_to_bus_sec += sec
        elif i > idx:
            walk_to_dest_sec += sec

    return {
        "total_sec": total_sec,
        "walk_to_bus_sec": walk_to_bus_sec,
        "walk_to_dest_sec": walk_to_dest_sec,
        "bus_sec": duration_to_seconds(bus_step.get("staticDuration", "0s")),
        "bus_no_raw": bus_no_raw,
        "bus_no": bus_no,
        "headsign": td.get("headsign"),
        "num_stops": td.get("stopCount"),
        "dep_stop": dep_stop.get("name", ""),
        "dep_lat": dep_loc.get("latitude"),
        "dep_lng": dep_loc.get("longitude"),
        "arr_stop": arr_stop.get("name", ""),
        "arr_lat": arr_loc.get("latitude"),
        "arr_lng": arr_loc.get("longitude"),
    }


def top_3_direct_bus_plans(data: dict) -> List[dict]:
    routes = data.get("routes", [])
    results = []
    seen = set()

    for route in routes:
        plan = extract_direct_bus_plan(route)
        if not plan:
            continue
        key = (plan.get("bus_no") or "", plan.get("dep_stop") or "", plan.get("arr_stop") or "")
        if key in seen:
            continue
        seen.add(key)
        results.append(plan)

    results.sort(key=lambda x: (x["total_sec"], x["walk_to_bus_sec"] + x["walk_to_dest_sec"]))
    return results[:3]


# ===== 到站時間（TDX） =====

def eta_text_to_seconds(eta_text: str) -> Optional[int]:
    if not eta_text:
        return None
    s = eta_text.strip()
    if s == "未知":
        return None
    if "進站" in s or "到站" in s:
        return 0
    m = re.search(r"(\d+)\s*分", s)
    sec = re.search(r"(\d+)\s*秒", s)
    minutes = int(m.group(1)) if m else 0
    seconds = int(sec.group(1)) if sec else 0
    return minutes * 60 + seconds


def pretty_eta_seconds(sec: Optional[int]) -> str:
    if sec is None:
        return "未知"
    try:
        sec = int(sec)
    except Exception:
        return "未知"
    if sec <= 0:
        return "進站/到站"
    m, s = divmod(sec, 60)
    return f"{m}分{s}秒" if m > 0 else f"{s}秒"


def pretty_sec(sec: int) -> str:
    sec = max(0, int(sec))
    m, s = divmod(sec, 60)
    return f"{m}分{s}秒" if m > 0 else f"{s}秒"


def decide_next_buses_message(eta_list: List[str], walk_sec: int) -> Dict[str, Any]:
    parsed = []
    for t in eta_list or []:
        s = eta_text_to_seconds(t)
        if s is not None:
            parsed.append((s, t))
    parsed.sort(key=lambda x: x[0])

    if not parsed:
        return {
            "eta1": None,
            "eta2": None,
            "walk_text": pretty_sec(walk_sec),
            "msg_lines": ["目前沒有可用的到站時間資料。"],
            "recommend_eta": None,
        }

    eta1_sec, eta1_text = parsed[0]
    eta2_text = parsed[1][1] if len(parsed) >= 2 else None
    walk_text = pretty_sec(walk_sec)

    lines = []
    recommend = None
    if eta1_sec >= walk_sec:
        lines.append(f"最近一班 {eta1_text} 到站，您步行需 {walk_text}，可趕上。")
        if eta2_text:
            lines.append(f"下一班 {eta2_text} 到站。")
        lines.append("建議搭乘最近一班。")
        recommend = eta1_text
    else:
        lines.append(f"最近一班 {eta1_text} 到站，您步行需 {walk_text}，可能趕不上。")
        if eta2_text:
            lines.append(f"下一班 {eta2_text} 到站，建議搭乘下一班。")
            recommend = eta2_text

    return {
        "eta1": eta1_text,
        "eta2": eta2_text,
        "walk_text": walk_text,
        "msg_lines": lines,
        "recommend_eta": recommend,
    }


def haversine_m(lat1, lng1, lat2, lng2) -> float:
    R = 6371000.0
    p1 = math.radians(lat1)
    p2 = math.radians(lat2)
    dlat = math.radians(lat2 - lat1)
    dlng = math.radians(lng2 - lng1)
    a = math.sin(dlat / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlng / 2) ** 2
    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
    return R * c


class TDXClient:
    def __init__(self, client_id: str, client_secret: str):
        if not client_id or not client_secret:
            raise NotConfiguredError("TDX_CLIENT_ID / TDX_CLIENT_SECRET 尚未設定")
        self.client_id = client_id
        self.client_secret = client_secret
        self._token: Optional[str] = None
        self._expire_at = 0.0

    def _get_token(self) -> str:
        now = time.time()
        if self._token and now < self._expire_at - 60:
            return self._token

        data = {
            "grant_type": "client_credentials",
            "client_id": self.client_id,
            "client_secret": self.client_secret,
        }

        last_err = None
        for attempt in range(MAX_RETRIES):
            try:
                r = requests.post(TDX_TOKEN_URL, data=data, timeout=TIMEOUT)
                if r.status_code != 200:
                    raise RuntimeError(f"Token HTTP {r.status_code}: {r.text[:500]}")
                j = r.json()
                self._token = j["access_token"]
                self._expire_at = now + int(j.get("expires_in", 86400))
                return self._token
            except Exception as e:
                last_err = e
                time.sleep((2 ** attempt) * 0.5 + random.uniform(0, 0.3))

        raise RuntimeError(f"取得 token 失敗：{repr(last_err)}")

    def get(self, url: str, params: Optional[Dict] = None) -> List[Dict]:
        headers = {"Authorization": f"Bearer {self._get_token()}"}

        last_err = None
        for attempt in range(MAX_RETRIES):
            try:
                r = requests.get(url, headers=headers, params=params, timeout=TIMEOUT)
                if r.status_code >= 400:
                    raise RuntimeError(f"TDX HTTP {r.status_code}: {r.text[:500]}")
                data = r.json()
                time.sleep(SLEEP_EACH_CALL)
                if isinstance(data, list):
                    return data
                if isinstance(data, dict) and "data" in data:
                    return data["data"]
                return []
            except Exception as e:
                last_err = e
                time.sleep((2 ** attempt) * 0.5 + random.uniform(0, 0.4))

        raise RuntimeError(f"TDX GET 失敗：{repr(last_err)}")


def get_route_stops_nearest_to_coord(
    tdx: TDXClient,
    lat: float,
    lng: float,
    route_name_zh: str,
    cities: Optional[List[str]] = None,
    max_distance_m: int = DEFAULT_RADIUS_M,
) -> Optional[Dict]:
    if cities is None:
        cities = CITIES

    route_enc = quote(route_name_zh.strip(), safe="")
    best = None

    for city in cities:
        url = f"{TDX_BASE}/Bus/StopOfRoute/City/{city}/{route_enc}"
        try:
            rows = tdx.get(url, params={"$format": "JSON"})
        except Exception as e:
            print(f"StopOfRoute 查詢失敗 city={city}, route={route_name_zh}, err={e}")
            continue

        for route in rows:
            direction = route.get("Direction")
            stops = route.get("Stops") or []
            for stop in stops:
                pos = stop.get("StopPosition") or {}
                slat = pos.get("PositionLat")
                slng = pos.get("PositionLon")
                if slat is None or slng is None:
                    continue

                d = haversine_m(lat, lng, float(slat), float(slng))
                row = {
                    "city": city,
                    "StationID": str(stop.get("StationID", "")).strip(),
                    "StopID": str(stop.get("StopID", "")).strip(),
                    "StopNameZh": (stop.get("StopName") or {}).get("Zh_tw", ""),
                    "Direction": int(direction) if direction is not None else -1,
                    "PositionLat": float(slat),
                    "PositionLon": float(slng),
                    "dist_m": d,
                    "RouteNameZh": route_name_zh.strip(),
                }
                if best is None or row["dist_m"] < best["dist_m"]:
                    best = row

    if best is None:
        return None
    if best["dist_m"] > max_distance_m:
        print("最近的該路線站牌超過範圍：", best)
        return None
    return best


def get_all_operating_etas_to_stop_dual_city(
    tdx: TDXClient,
    route_name_zh: str,
    stop_id: str,
    direction: Optional[int] = None,
    require_eta: bool = True,
) -> Optional[List[str]]:
    encoded_route = quote(route_name_zh.strip(), safe="")

    for city in CITIES:
        url = f"{TDX_BASE}/Bus/EstimatedTimeOfArrival/City/{city}/{encoded_route}"
        try:
            data = tdx.get(url, params={"$format": "JSON"})
        except Exception:
            continue

        rows = [x for x in data if str(x.get("StopID")) == str(stop_id)]
        if direction is not None:
            rows = [x for x in rows if x.get("Direction") == direction]
        if not rows:
            continue

        vehicles = []
        for r in rows:
            est = r.get("EstimateTime")
            if require_eta and est is None:
                continue
            vehicles.append({"EstimateTimeSec": est, "PlateNumb": r.get("PlateNumb") or ""})

        vehicles.sort(
            key=lambda x: int(x["EstimateTimeSec"]) if x["EstimateTimeSec"] is not None else 10 ** 12,
        )
        return [pretty_eta_seconds(v.get("EstimateTimeSec")) for v in vehicles]

    return None


def get_station_and_eta_by_coord_and_route(
    lat: float,
    lng: float,
    route_name_zh: str,
    radius_m: int = DEFAULT_RADIUS_M,
) -> Optional[Dict]:
    tdx = TDXClient(config.TDX_CLIENT_ID, config.TDX_CLIENT_SECRET)

    best_stop = get_route_stops_nearest_to_coord(
        tdx=tdx, lat=lat, lng=lng, route_name_zh=route_name_zh,
        cities=CITIES, max_distance_m=radius_m,
    )
    if not best_stop:
        return None

    stop_id = best_stop["StopID"]
    direction = best_stop["Direction"]

    etas = get_all_operating_etas_to_stop_dual_city(
        tdx, route_name_zh, stop_id, direction=direction, require_eta=True,
    ) or []

    return {
        "StationID": best_stop["StationID"],
        "RouteNameZh": route_name_zh.strip(),
        "StopID": stop_id,
        "Direction": direction,
        "StopNameZh": best_stop["StopNameZh"],
        "StopDistanceM": round(best_stop["dist_m"], 1),
        "ETA_list": etas,
    }


# ===== 公車車頭／LED OCR =====

_yolo_model = None


def _load_yolo():
    """
    YOLO 模型只載入一次。以前每次 /bus-ocr 都重新 `YOLO(path)`，每次多花約半秒。

    沒裝 ultralytics 或載入失敗回 None（呼叫端改用整張圖），下次呼叫會再試。
    """
    global _yolo_model
    if _yolo_model is not None:
        return _yolo_model
    try:
        from ultralytics import YOLO
    except Exception:
        print("⚠️ 未安裝 ultralytics")
        return None
    try:
        _yolo_model = YOLO(config.BUS_OCR_MODEL_PATH)
    except Exception as e:
        print("❌ YOLO 模型載入失敗：", e)
        return None
    return _yolo_model


def detect_led_box(image_bgr: "np.ndarray") -> Optional[tuple]:
    """車頭 LED 看板的位置 `(x1, y1, x2, y2, 信心)`；找不到回 None。"""
    model = _load_yolo()
    if model is None:
        return None

    best = None
    best_score = -1

    for imgsz in YOLO_IMGSZ_LIST:
        results = model(image_bgr, conf=YOLO_CONF, imgsz=imgsz, verbose=False)
        if not results or results[0].boxes is None:
            continue

        for box in results[0].boxes:
            conf = float(box.conf[0])
            x1, y1, x2, y2 = map(int, box.xyxy[0])
            area = (x2 - x1) * (y2 - y1)
            score = conf + 0.000001 * area
            if score > best_score:
                best_score = score
                best = (x1, y1, x2, y2, conf)

    if best is None:
        return None
    x1, y1, x2, y2, _ = best
    return best if image_bgr[y1:y2, x1:x2].size else None


def save_debug_images(image_bgr, box, bus_no: str, ocr_text: str, matched: bool, directory) -> Path:
    """
    存下這次辨識的原圖、畫上 YOLO 框的圖、結果 JSON（設定 BUS_OCR_DEBUG_DIR 時才存）。

    給專題影片剪輯用：畫面上的框必須是系統真正找到的位置，不是後製自己畫的。
    框的標籤只寫英文數字 —— OpenCV 內建字型畫不出中文，OCR 原文放在 JSON。
    用 imencode 再寫檔而不是 cv2.imwrite：後者在 Windows 上遇到非 ASCII 路徑會靜靜地失敗。
    """
    import json

    import cv2

    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    safe_bus = re.sub(r"[^0-9A-Za-z一-鿿]", "", bus_no) or "bus"
    # 加毫秒：連續兩次辨識在同一秒內結束時，後一次會蓋掉前一次（實測發生過）。
    now = time.time()
    stamp = f"{time.strftime('%Y%m%d-%H%M%S', time.localtime(now))}-{int(now * 1000) % 1000:03d}"
    base = f"{stamp}_{safe_bus}_{'match' if matched else 'nomatch'}"
    stem, n = base, 1
    while (directory / f"{stem}.json").exists():
        n += 1
        stem = f"{base}-{n}"

    height, width = image_bgr.shape[:2]
    thickness = max(2, width // 300)
    scale = max(0.6, width / 1000)
    color = (0, 200, 0) if matched else (0, 0, 255)

    annotated = image_bgr.copy()
    if box is not None:
        x1, y1, x2, y2, conf = box
        cv2.rectangle(annotated, (x1, y1), (x2, y2), color, thickness)
        cv2.putText(annotated, f"LED panel {conf:.2f}", (x1, max(0, y1 - 10)),
                    cv2.FONT_HERSHEY_SIMPLEX, scale, color, thickness, cv2.LINE_AA)
    else:
        cv2.putText(annotated, "no LED panel found - whole image OCR", (10, int(40 * scale)),
                    cv2.FONT_HERSHEY_SIMPLEX, scale, color, thickness, cv2.LINE_AA)
    cv2.putText(annotated, f"target {bus_no if bus_no.isascii() else ''}: {'MATCH' if matched else 'NO MATCH'}",
                (10, height - 20), cv2.FONT_HERSHEY_SIMPLEX, scale, color, thickness, cv2.LINE_AA)

    for suffix, image in (("raw", image_bgr), ("box", annotated)):
        ok, encoded = cv2.imencode(".jpg", image, [cv2.IMWRITE_JPEG_QUALITY, 92])
        if ok:
            (directory / f"{stem}_{suffix}.jpg").write_bytes(encoded.tobytes())

    (directory / f"{stem}.json").write_text(
        json.dumps(
            {
                "bus_no": bus_no,
                "matched": matched,
                "ocr_text": ocr_text,
                "led_box": None if box is None else {"x1": box[0], "y1": box[1], "x2": box[2], "y2": box[3], "conf": round(box[4], 3)},
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    return directory / f"{stem}_box.jpg"


def apply_clahe(gray):
    import cv2

    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    return clahe.apply(gray)


def preprocess_bus_gray(bgr):
    import cv2

    img = cv2.resize(bgr, None, fx=BUS_SCALE, fy=BUS_SCALE)
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    return apply_clahe(gray) if USE_CLAHE else gray


class OcrBudget:
    def __init__(self, max_calls: int):
        self.max_calls = max_calls
        self.used = 0

    def can_call(self) -> bool:
        return self.used < self.max_calls

    def use(self) -> None:
        self.used += 1


def vision_text(gray, language_hints, budget: OcrBudget) -> str:
    if not budget.can_call():
        return ""
    if not config.GOOGLE_APPLICATION_CREDENTIALS:
        raise NotConfiguredError("GOOGLE_APPLICATION_CREDENTIALS 尚未設定")
    # 設了但檔案不在：以前在建立 client 時才丟出 google-auth 的例外，/bus-ocr 回 500，
    # log 裡一大串 traceback。改成跟「沒設定」一樣回 503 並說清楚是哪個檔案。
    if not Path(config.GOOGLE_APPLICATION_CREDENTIALS).is_file():
        raise NotConfiguredError(f"找不到 Google Vision 服務帳戶金鑰：{config.GOOGLE_APPLICATION_CREDENTIALS}")

    import cv2
    from google.cloud import vision

    client = vision.ImageAnnotatorClient()
    ok, buf = cv2.imencode(".png", gray)
    if not ok:
        return ""

    budget.use()
    image = vision.Image(content=buf.tobytes())
    ctx = vision.ImageContext(language_hints=language_hints)
    resp = client.text_detection(image=image, image_context=ctx)

    if resp.text_annotations:
        return resp.text_annotations[0].description
    return ""


def match_bus_no(ocr_text: str, bus_no: str) -> bool:
    target_clean = normalize_text(normalize_route_name(bus_no))
    if not target_clean:
        return False
    return target_clean in normalize_text(ocr_text)


def run_bus_by_image(image_bgr, bus_no: str, stop_id: str = "", direction: int = -1) -> dict:
    bus_no = normalize_route_name(bus_no)

    print("==== BUS OCR DEBUG ====")
    print("bus_no =", bus_no, "stop_id =", stop_id, "direction =", direction)

    budget = OcrBudget(MAX_OCR_CALLS)

    box = detect_led_box(image_bgr)
    if box is None:
        print("⚠️ YOLO 未偵測到 LED 區域，改用整張圖片辨識")
        roi = image_bgr
    else:
        x1, y1, x2, y2, conf = box
        print(f"✅ YOLO 找到 LED 看板（信心 {conf:.2f}）")
        roi = image_bgr[y1:y2, x1:x2]

    gray = preprocess_bus_gray(roi)
    text = vision_text(gray, ["zh-TW", "en"], budget)
    matched = match_bus_no(text, bus_no)

    if config.BUS_OCR_DEBUG_DIR:
        try:
            saved = save_debug_images(image_bgr, box, bus_no, text, matched, config.BUS_OCR_DEBUG_DIR)
            print("🖼️ 已存辨識畫面：", saved)
        except Exception as e:
            # 存圖只是輔助，失敗不能讓使用者的確認公車跟著失敗。
            print("⚠️ 存辨識畫面失敗：", e)

    return {
        "success": True,
        "message": (
            f"辨識成功，確認為 {bus_no}，StopID={stop_id}，Direction={direction}"
            if matched
            else f"未確認為 {bus_no}，StopID={stop_id}，Direction={direction}"
        ),
        "bus_no": bus_no,
        "stop_id": stop_id,
        "direction": direction,
        "ocr_text": text,
        "matched": matched,
    }
