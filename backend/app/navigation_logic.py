"""
步行轉彎指示（即時導航用）。

跟 `bus_logic.py` 的路線規劃故意分開：那邊是「查一次、答一次」的公車方案，
這邊是給**持續播報**用的逐步轉彎指示（「在忠孝東路左轉」）。

刻意不自己算路徑/街道——直接拿 Google Routes API 的
`navigationInstruction.instructions`，那是 Google 自己組好的人話，
比我們自己拿座標算方位角、再翻成「左轉/右轉」更準（真的街道會轉彎、
會有單行道，純方位角算不出這些）。

我們自己只做一件事：**什麼時候該講出這句話**——見
`ai-navigation/NavigateWalkingUseCase.kt`，這個節奏／打斷邏輯不假手 Google
Maps App，全部走 `AnnouncementManager`，這樣障礙物警告才插得了話。
"""

from __future__ import annotations

from typing import Optional

import requests

from . import config
from .bus_logic import NotConfiguredError, destination_waypoint

ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"


def call_walking_routes_api(
    origin_lat: float,
    origin_lng: float,
    dest_lat: Optional[float] = None,
    dest_lng: Optional[float] = None,
    dest_name: str = "",
) -> dict:
    if not config.GOOGLE_MAPS_API_KEY:
        raise NotConfiguredError("GOOGLE_MAPS_API_KEY 尚未設定")

    headers = {
        "Content-Type": "application/json; charset=utf-8",
        "X-Goog-Api-Key": config.GOOGLE_MAPS_API_KEY,
        "X-Goog-FieldMask": ",".join([
            "routes.duration",
            "routes.distanceMeters",
            "routes.legs.steps.navigationInstruction",
            "routes.legs.steps.startLocation",
            "routes.legs.steps.endLocation",
            "routes.legs.steps.distanceMeters",
            "routes.legs.steps.staticDuration",
        ]),
    }

    payload = {
        "origin": {"location": {"latLng": {"latitude": origin_lat, "longitude": origin_lng}}},
        "destination": destination_waypoint(dest_lat, dest_lng, dest_name),
        "travelMode": "WALK",
        "languageCode": "zh-TW",
        "units": "METRIC",
    }

    response = requests.post(ROUTES_URL, json=payload, headers=headers, timeout=20)
    response.raise_for_status()
    return response.json()


def walking_steps_from_response(data: dict) -> dict:
    """
    把 Google 的巢狀結構整理成手機端要的扁平格式。

    只取第一條路線（`computeAlternativeRoutes` 沒開，Google 本來就只會給一條）。
    """
    routes = data.get("routes", [])
    if not routes:
        return {"success": False, "message": "找不到步行路線", "steps": [], "total_distance_m": 0}

    route = routes[0]
    steps_out = []

    for leg in route.get("legs", []):
        for step in leg.get("steps", []):
            instruction = (step.get("navigationInstruction") or {}).get("instructions", "")
            start = (step.get("startLocation") or {}).get("latLng") or {}

            if not instruction or "latitude" not in start:
                continue  # 缺這兩樣資料的步驟沒辦法播報也沒辦法判斷時機，跳過比塞假資料安全

            steps_out.append({
                "instruction": instruction,
                "lat": start["latitude"],
                "lng": start["longitude"],
                "distance_m": step.get("distanceMeters", 0),
            })

    return {
        "success": True,
        "message": "",
        "steps": steps_out,
        "total_distance_m": route.get("distanceMeters", 0),
    }
