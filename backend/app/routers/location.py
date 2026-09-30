"""
手機 GPS 座標中繼。

對應 Kotlin 端 `PhoneCompanionLocationProvider`（GET /current-location 輪詢）
與手機 `companion-app`（POST /update-location，前景服務每秒回報一次），
見 `docs/ARCHITECTURE.md` §5.2/§5.3。

手機另外會送 `accuracy_m` 與 `fix_age_ms`；`/current-location` 回傳這筆座標
到現在的 `age_ms`，眼鏡據此丟掉過舊的座標（門檻見眼鏡端
`PhoneCompanionLocationProvider.DEFAULT_MAX_AGE_MILLIS`）。
`fix_time_ms`（手機的牆上時間）只是參考，不拿來算年齡，理由見 `_freshness`。

`/navigation-target` 這組端點是舊原型 `GPS_phone`/`GPS_glasses` 用來讓
眼鏡把目的地推給手機、手機自己開 Google Maps 的機制 —— 新架構刻意不採用
這個做法（手機不該自己對使用者講話，見 companion-app 的 KDoc），目前沒有
任何 Kotlin 端會呼叫這兩個端點。保留是為了不遺失既有能力，之後若有
需要手機顯示導航狀態的畫面，可以在這個基礎上接。
"""

import time

from fastapi import APIRouter

router = APIRouter()

_latest_location = {"lat": 0.0, "lng": 0.0, "updated_at": None, "accuracy_m": None}

# 座標「多舊」的計算材料，不回給呼叫端。
#
# 刻意不用任何一台裝置的牆上時鐘：眼鏡的時鐘實測快了 4 小時 15 分，
# 拿手機的 fix_time_ms 跟眼鏡的時間比，每一筆都會被判成過期。
# 改成手機回報「送出當下這筆定位已經幾毫秒」（用手機自己的開機時間算），
# 這裡再加上後端持有它多久（用 monotonic 時鐘算），眼鏡只要看 age_ms。
_freshness = {"received_monotonic": None, "fix_age_ms": 0.0}

_navigation_target = {
    "lat": None, "lng": None, "name": "", "mode": "walking", "bus_no": "", "updated_at": None,
}


@router.post("/update-location")
async def update_location(payload: dict):
    try:
        lat = float(payload.get("lat", 0))
        lng = float(payload.get("lng", 0))
    except (TypeError, ValueError) as e:
        return {"success": False, "message": f"座標格式錯誤：{e}"}

    _latest_location["lat"] = lat
    _latest_location["lng"] = lng
    _latest_location["updated_at"] = time.time()
    _latest_location["accuracy_m"] = _optional_float(payload.get("accuracy_m"))

    # 舊版手機不送 fix_age_ms，當成剛取得（年齡只算後端持有的時間）。
    _freshness["fix_age_ms"] = max(0.0, _optional_float(payload.get("fix_age_ms")) or 0.0)
    _freshness["received_monotonic"] = time.monotonic()

    print("📍 收到手機位置：", _latest_location)
    return {"success": True, "message": "位置已更新", "location": _latest_location}


@router.get("/current-location")
def current_location():
    return {
        "success": True,
        "lat": _latest_location["lat"],
        "lng": _latest_location["lng"],
        "updated_at": _latest_location["updated_at"],
        "accuracy_m": _latest_location["accuracy_m"],
        # 這筆定位到現在多舊（毫秒）。眼鏡用它丟掉過舊的座標，見上方 _freshness。
        "age_ms": _age_ms(),
    }


def _age_ms():
    received = _freshness["received_monotonic"]
    if received is None:
        return None
    return round(_freshness["fix_age_ms"] + (time.monotonic() - received) * 1000)


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
