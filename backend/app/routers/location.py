"""
手機 GPS 座標中繼。

對應 Kotlin 端 `PhoneCompanionLocationProvider`（GET /current-location 輪詢）
與手機 `companion-app`（POST /update-location 每 5 秒回報一次），
見 `docs/ARCHITECTURE.md` §5.2/§5.3。

`/navigation-target` 這組端點是舊原型 `GPS_phone`/`GPS_glasses` 用來讓
眼鏡把目的地推給手機、手機自己開 Google Maps 的機制 —— 新架構刻意不採用
這個做法（手機不該自己對使用者講話，見 companion-app 的 KDoc），目前沒有
任何 Kotlin 端會呼叫這兩個端點。保留是為了不遺失既有能力，之後若有
需要手機顯示導航狀態的畫面，可以在這個基礎上接。
"""

import time

from fastapi import APIRouter

router = APIRouter()

_latest_location = {"lat": 0.0, "lng": 0.0, "updated_at": None}

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

    print("📍 收到手機位置：", _latest_location)
    return {"success": True, "message": "位置已更新", "location": _latest_location}


@router.get("/current-location")
def current_location():
    return {
        "success": True,
        "lat": _latest_location["lat"],
        "lng": _latest_location["lng"],
        "updated_at": _latest_location["updated_at"],
    }


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
