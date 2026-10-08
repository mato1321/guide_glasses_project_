"""
公車路線規劃、到站時間、車頭 OCR。

對應的 Kotlin 呼叫端見 `guide-glasses/ai/ai-navigation`：
- `HttpBusPlanningGateway` → GET /bus-plans、GET /eta
- `HttpBusOcrGateway` → POST /bus-ocr

回傳的 JSON 欄位名稱與舊原型（api/GPS_app.py、api/BUS_app.py）完全一致，
不能改，否則 Kotlin 那邊的 `@Serializable` data class 會解析失敗。
"""

import traceback

import requests
from fastapi import APIRouter, File, Form, UploadFile
from fastapi.responses import JSONResponse

from .. import bus_logic
from ..bus_logic import NotConfiguredError

router = APIRouter()


@router.get("/bus-plans")
def bus_plans(origin: str = "", dest: str = "", dest_name: str = ""):
    """
    `dest`（座標 "lat,lng"）跟 `dest_name`（地名，例如「台北車站」）擇一。
    後者是給開放式語句用的：LLM 從「帶我去台北車站」抽出來的是文字，
    不是座標，直接把地名交給 Google Routes API 的 `address` 欄位解析，
    不用另外接地理編碼服務。
    """
    try:
        origin_lat, origin_lng = bus_logic.parse_latlng(origin)

        dest_lat = dest_lng = None
        if not dest_name:
            dest_lat, dest_lng = bus_logic.parse_latlng(dest)

        data = bus_logic.call_routes_api(origin_lat, origin_lng, dest_lat, dest_lng, dest_name)
        plans = bus_logic.top_3_direct_bus_plans(data)

        formatted = []
        for p in plans:
            formatted.append({
                "bus_no": bus_logic.normalize_route_name(p["bus_no"]),
                "dep_stop": p["dep_stop"],
                "arr_stop": p["arr_stop"],
                "num_stops": p["num_stops"],
                "headsign": p["headsign"],
                "walk_to_bus_sec": p["walk_to_bus_sec"],
                "walk_to_bus_min": round(p["walk_to_bus_sec"] / 60),
                "walk_to_dest_min": round(p["walk_to_dest_sec"] / 60),
                "total_min": round(p["total_sec"] / 60),
                "dep_lat": p["dep_lat"],
                "dep_lng": p["dep_lng"],
                "arr_lat": p["arr_lat"],
                "arr_lng": p["arr_lng"],
            })

        return {"success": True, "message": "", "count": len(formatted), "plans": formatted}

    except NotConfiguredError as e:
        return JSONResponse(status_code=503, content={"success": False, "message": str(e), "plans": []})

    except (ValueError, requests.HTTPError) as e:
        detail = ""
        if isinstance(e, requests.HTTPError):
            try:
                detail = e.response.text if e.response else ""
            except Exception:
                pass
        print("❌ /bus-plans Error:", e, detail)
        return JSONResponse(
            status_code=400 if isinstance(e, ValueError) else 500,
            content={"success": False, "message": str(e), "detail": detail, "plans": []},
        )

    except Exception as e:
        print("❌ /bus-plans Exception")
        print(traceback.format_exc())
        return JSONResponse(status_code=500, content={"success": False, "message": str(e), "plans": []})


@router.get("/eta")
def eta_api(lat: float = 0.0, lng: float = 0.0, bus: str = "", walk_sec: int = 0):
    bus = bus.strip()
    if not bus:
        return JSONResponse(
            status_code=400,
            content={
                "eta1": "未知", "eta2": "未知", "message": "缺少公車號碼",
                "station_id": "", "stop_id": "", "direction": -1,
            },
        )

    bus = bus_logic.normalize_route_name(bus)

    try:
        res = bus_logic.get_station_and_eta_by_coord_and_route(
            lat=lat, lng=lng, route_name_zh=bus, radius_m=bus_logic.DEFAULT_RADIUS_M,
        )

        if res is None:
            return {
                "eta1": "未知", "eta2": "未知",
                "message": f"找不到資料：此路線在 {bus_logic.DEFAULT_RADIUS_M} 公尺內找不到對應站牌，bus={bus}",
                "station_id": "", "stop_id": "", "direction": -1,
            }

        decision = bus_logic.decide_next_buses_message(res["ETA_list"], walk_sec)

        return {
            "eta1": decision["eta1"] or "未知",
            "eta2": decision["eta2"] or "未知",
            "message": "；".join(decision["msg_lines"]) if decision["msg_lines"] else "success",
            "station_id": res["StationID"],
            "stop_id": res["StopID"],
            "direction": res["Direction"],
            "bus": res["RouteNameZh"],
            "walk_time": decision["walk_text"],
            "recommend_eta": decision["recommend_eta"] or "未知",
            "tdx_stop_name": res.get("StopNameZh", ""),
            "tdx_stop_distance_m": res.get("StopDistanceM", ""),
        }

    except NotConfiguredError as e:
        return JSONResponse(
            status_code=503,
            content={
                "eta1": "未知", "eta2": "未知", "message": str(e),
                "station_id": "", "stop_id": "", "direction": -1,
            },
        )

    except Exception as e:
        print(traceback.format_exc())
        return JSONResponse(
            status_code=500,
            content={
                "eta1": "未知", "eta2": "未知", "message": f"伺服器例外：{e}",
                "station_id": "", "stop_id": "", "direction": -1,
            },
        )


@router.post("/bus-ocr")
async def bus_ocr(
    bus_no: str = Form(...),
    stop_id: str = Form(""),
    direction: int = Form(-1),
    image: UploadFile = File(...),
):
    bus_no = bus_logic.normalize_route_name(bus_no)

    import cv2
    import numpy as np

    contents = await image.read()
    image_bgr = cv2.imdecode(np.frombuffer(contents, np.uint8), cv2.IMREAD_COLOR)

    if image_bgr is None:
        return {
            "success": False, "message": "圖片解析失敗", "bus_no": bus_no,
            "stop_id": stop_id, "direction": direction, "ocr_text": "", "matched": False,
        }

    try:
        return bus_logic.run_bus_by_image(
            image_bgr=image_bgr, bus_no=bus_no, stop_id=stop_id, direction=direction,
        )
    except NotConfiguredError as e:
        return JSONResponse(
            status_code=503,
            content={
                "success": False, "message": str(e), "bus_no": bus_no,
                "stop_id": stop_id, "direction": direction, "ocr_text": "", "matched": False,
            },
        )
