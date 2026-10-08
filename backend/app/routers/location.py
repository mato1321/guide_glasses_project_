"""
手機 GPS 座標中繼。

對應 Kotlin 端 `PhoneCompanionLocationProvider`（GET /current-location 輪詢）
與手機 `companion-app`（POST /update-location，前景服務每秒回報一次），
見 `docs/ARCHITECTURE.md` §5.2/§5.3。眼鏡連著手機熱點時會先直接跟手機拿，
這裡是直連失敗時的備援。

手機另外會送 `accuracy_m` 與 `fix_age_ms`；`/current-location` 回傳這筆座標
到現在的 `age_ms`，眼鏡據此丟掉過舊的座標（門檻見眼鏡端
`PhoneCompanionLocationProvider.DEFAULT_MAX_AGE_MILLIS`）。
`fix_time_ms`（手機的牆上時間）只是參考，不拿來算年齡，理由見 `MemoryLocationStore`。

## 座標存哪裡

- 沒設 `LOCATION_TABLE`（本機、Cloudflare 版）：存在這個進程的記憶體，見 `MemoryLocationStore`
- 有設（AWS Lambda 版）：存在 DynamoDB，見 `DynamoLocationStore`。Lambda 可能同時開好幾個
  執行個體，存在記憶體的話手機寫進 A、眼鏡從 B 讀就讀不到

`/navigation-target` 這組端點是舊原型 `GPS_phone`/`GPS_glasses` 用來讓
眼鏡把目的地推給手機、手機自己開 Google Maps 的機制 —— 新架構刻意不採用
這個做法（手機不該自己對使用者講話，見 companion-app 的 KDoc），目前沒有
任何 Kotlin 端會呼叫這兩個端點。保留是為了不遺失既有能力，之後若有
需要手機顯示導航狀態的畫面，可以在這個基礎上接。它一律存在記憶體，
在 Lambda 上每個執行個體各記各的。
"""

import time
from decimal import Decimal

from fastapi import APIRouter

from .. import config

router = APIRouter()


class MemoryLocationStore:
    """
    存在這個進程的記憶體，只適用單一進程（uvicorn）。

    座標「多舊」刻意不用任何一台裝置的牆上時鐘：眼鏡的時鐘實測快了 4 小時 15 分，
    拿手機的 fix_time_ms 跟眼鏡的時間比，每一筆都會被判成過期。
    改成手機回報「送出當下這筆定位已經幾毫秒」（用手機自己的開機時間算），
    這裡再加上後端持有它多久（用 monotonic 時鐘算），眼鏡只要看 age_ms。
    """

    def __init__(self, monotonic=time.monotonic, wall=time.time):
        self._monotonic = monotonic
        self._wall = wall
        self._latest = None

    def save(self, lat, lng, accuracy_m, fix_age_ms):
        self._latest = {
            "lat": lat,
            "lng": lng,
            "updated_at": self._wall(),
            "accuracy_m": accuracy_m,
            "fix_age_ms": fix_age_ms,
            "received_monotonic": self._monotonic(),
        }
        return _public(self._latest)

    def load(self):
        if self._latest is None:
            return None
        held_ms = (self._monotonic() - self._latest["received_monotonic"]) * 1000
        return {**_public(self._latest), "age_ms": round(self._latest["fix_age_ms"] + held_ms)}


class DynamoLocationStore:
    """
    存在 DynamoDB 的單一一筆（id = "latest"），AWS Lambda 版用。

    持有時間改用**伺服器的牆上時鐘**算：monotonic 時鐘只在同一個執行個體內有意義，
    這裡寫入跟讀取常常是不同的執行個體。AWS 的主機都有校時，彼此誤差在毫秒級；
    萬一讀的那台比寫的那台慢一點，持有時間當成 0，不會算出負數。
    眼鏡的時鐘依然完全不參與。

    `expires_at` 是 DynamoDB 的 TTL 欄位：手機停止回報一天後自動刪掉這筆位置，
    不讓使用者的位置一直留在雲端。TTL 刪除會延遲，所以新舊一律看 age_ms，不靠它判斷。
    """

    ITEM_ID = "latest"
    TTL_SECONDS = 24 * 60 * 60

    def __init__(self, table, wall=time.time):
        self._table = table
        self._wall = wall

    def save(self, lat, lng, accuracy_m, fix_age_ms):
        now = self._wall()
        item = {
            "id": self.ITEM_ID,
            # DynamoDB 不收 float，一律轉成 Decimal。
            "lat": Decimal(str(lat)),
            "lng": Decimal(str(lng)),
            "updated_at": Decimal(str(now)),
            "fix_age_ms": Decimal(str(fix_age_ms)),
            "received_at_ms": int(now * 1000),
            "expires_at": int(now) + self.TTL_SECONDS,
        }
        if accuracy_m is not None:
            item["accuracy_m"] = Decimal(str(accuracy_m))
        self._table.put_item(Item=item)
        return {"lat": lat, "lng": lng, "updated_at": now, "accuracy_m": accuracy_m}

    def load(self):
        # 強一致讀取：剛寫進去的下一次讀就要讀得到（費用是一般讀取的兩倍，但一次仍不到百萬分之一美元）。
        item = self._table.get_item(Key={"id": self.ITEM_ID}, ConsistentRead=True).get("Item")
        if item is None:
            return None
        held_ms = max(0, int(self._wall() * 1000) - int(item["received_at_ms"]))
        accuracy = item.get("accuracy_m")
        return {
            "lat": float(item["lat"]),
            "lng": float(item["lng"]),
            "updated_at": float(item["updated_at"]),
            "accuracy_m": float(accuracy) if accuracy is not None else None,
            "age_ms": round(float(item["fix_age_ms"]) + held_ms),
        }


def _public(latest):
    """回給手機的欄位（不含算年齡用的內部欄位）。"""
    return {key: latest[key] for key in ("lat", "lng", "updated_at", "accuracy_m")}


def _make_store():
    if config.LOCATION_TABLE:
        import boto3  # Lambda 執行環境內建；本機沒設 LOCATION_TABLE 就不需要裝

        return DynamoLocationStore(boto3.resource("dynamodb").Table(config.LOCATION_TABLE))
    return MemoryLocationStore()


_store = _make_store()

_navigation_target = {
    "lat": None, "lng": None, "name": "", "mode": "walking", "bus_no": "", "updated_at": None,
}


@router.post("/update-location")
def update_location(payload: dict):
    try:
        lat = float(payload.get("lat", 0))
        lng = float(payload.get("lng", 0))
    except (TypeError, ValueError) as e:
        return {"success": False, "message": f"座標格式錯誤：{e}"}

    location = _store.save(
        lat,
        lng,
        _optional_float(payload.get("accuracy_m")),
        # 舊版手機不送 fix_age_ms，當成剛取得（年齡只算後端持有的時間）。
        max(0.0, _optional_float(payload.get("fix_age_ms")) or 0.0),
    )

    print("📍 收到手機位置：", location)
    return {"success": True, "message": "位置已更新", "location": location}


@router.get("/current-location")
def current_location():
    latest = _store.load()
    if latest is None:
        # 還沒有人回報過。age_ms 為 None，眼鏡端 lat/lng 為 0 時也會當成沒有座標。
        return {"success": True, "lat": 0.0, "lng": 0.0, "updated_at": None, "accuracy_m": None, "age_ms": None}
    # age_ms：這筆定位到現在多舊（毫秒）。眼鏡用它丟掉過舊的座標，見 MemoryLocationStore。
    return {"success": True, **latest}


def _optional_float(value):
    try:
        return float(value) if value is not None else None
    except (TypeError, ValueError):
        return None


@router.post("/navigation-target")
async def update_navigation_target(payload: dict):
    if "lat" not in payload or "lng" not in payload:
        return {"success": False, "message": "缺少 lat 或 lng"}

    try:
        lat = float(payload["lat"])
        lng = float(payload["lng"])
    except (TypeError, ValueError) as e:
        return {"success": False, "message": f"座標格式錯誤：{e}"}

    if not (-90 <= lat <= 90 and -180 <= lng <= 180):
        return {"success": False, "message": "座標超出有效範圍"}

    _navigation_target.update({
        "lat": lat,
        "lng": lng,
        "name": str(payload.get("name", "")).strip(),
        "mode": str(payload.get("mode", "walking")).strip() or "walking",
        "bus_no": str(payload.get("bus_no", "")).strip(),
        "updated_at": time.time(),
    })

    print("🧭 收到眼鏡導航目的地：", _navigation_target)
    return {"success": True, "message": "導航目的地已更新", "target": _navigation_target}


@router.get("/navigation-target")
def get_navigation_target():
    has_target = (
        _navigation_target["lat"] is not None
        and _navigation_target["lng"] is not None
        and _navigation_target["updated_at"] is not None
    )
    return {"success": True, "has_target": has_target, "target": _navigation_target}
