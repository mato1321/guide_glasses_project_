"""
即時步行導航的逐步轉彎指示。

對應 Kotlin 端 `ai-navigation` 的 `HttpWalkingRouteGateway` →
`NavigateWalkingUseCase`：查一次路線、拿到一串「在哪個座標該講哪句話」，
之後由手機/眼鏡端自己比對目前位置、決定播報時機——這裡只負責查路線，
不管播報節奏。
"""

import traceback

from fastapi import APIRouter
from fastapi.responses import JSONResponse

from .. import navigation_logic
from ..bus_logic import NotConfiguredError, parse_latlng

router = APIRouter()


@router.get("/walking-route")
def walking_route(origin: str = "", dest: str = "", dest_name: str = ""):
    """
    `dest`（座標 "lat,lng"）跟 `dest_name`（地名）擇一，跟 `/bus-plans` 同慣例。
    """
    try:
        origin_lat, origin_lng = parse_latlng(origin)

        dest_lat = dest_lng = None
        if not dest_name:
            dest_lat, dest_lng = parse_latlng(dest)

        data = navigation_logic.call_walking_routes_api(
            origin_lat, origin_lng, dest_lat, dest_lng, dest_name,
        )
        return navigation_logic.walking_steps_from_response(data)

    except NotConfiguredError as e:
        return JSONResponse(status_code=503, content={"success": False, "message": str(e), "steps": []})

    except ValueError as e:
        return JSONResponse(status_code=400, content={"success": False, "message": str(e), "steps": []})

    except Exception as e:
        print("❌ /walking-route Exception:", e)
        print(traceback.format_exc())
        return JSONResponse(status_code=500, content={"success": False, "message": str(e), "steps": []})
