"""Obstacle Detection module wrapper.

Wraps the obstacle recognition functionality from
``Obstacle_Recognition/python/main.py``.  When the underlying model is not
available the module degrades gracefully.
"""
import logging
from typing import Any, Dict, List

from modules import BaseModule

logger = logging.getLogger(__name__)


class ObstacleModule(BaseModule):
    """Module that performs obstacle / object detection."""

    @property
    def name(self) -> str:
        return "obstacle_detection"

    @property
    def keywords(self) -> List[str]:
        return [
            "障礙物", "障礙", "obstacle", "detect", "偵測", "物體",
            "偵測障礙", "避障", "路障", "前方", "object detection",
        ]

    async def execute(self, command: str, **kwargs: Any) -> Dict[str, Any]:
        """Perform obstacle detection on the provided image frame.

        Accepts an optional ``image`` keyword argument containing a
        ``numpy.ndarray`` (BGR) image.

        Returns a dict with:
        - ``action``: ``"detect"``
        - ``data``:   detection results or an error message
        """
        image = kwargs.get("image")
        if image is None:
            return {
                "action": "detect",
                "data": {"error": "須提供 image 才能進行障礙物偵測"},
            }

        try:
            results = self._run_detection(image)
            return {"action": "detect", "data": results}
        except Exception as exc:
            logger.exception("Obstacle detection failed: %s", exc)
            return {"action": "detect", "data": {"error": str(exc)}}

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _run_detection(self, image: Any) -> Dict[str, Any]:
        """Run the obstacle detection model.

        Override or extend this method when the underlying model is
        integrated.  Returns a placeholder result until then.
        """
        # TODO: integrate the real YOLO / detection model from
        #       Obstacle_Recognition/python/main.py when available.
        return {
            "message": "Obstacle detection module ready; model not yet integrated.",
            "obstacles": [],
        }

    def health_check(self) -> bool:
        return True
