"""Face Recognition module wrapper.

Wraps the FaceEngine from ``Face_Recognition/Python/face_engine.py``.
When the underlying library (InsightFace) is not installed the module
degrades gracefully and reports an informative error.
"""
import logging
import sys
import os
from typing import Any, Dict, List

from modules import BaseModule

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Try to import the real FaceEngine.  Add its directory to sys.path so that
# the import works regardless of working-directory.
# ---------------------------------------------------------------------------
_FACE_ENGINE_DIR = os.environ.get(
    "FACE_ENGINE_DIR",
    os.path.join(
        os.path.dirname(__file__),
        "../../../../Face_Recognition/Python",
    ),
)

_face_engine_available = False
FaceEngine = None  # type: ignore

try:
    if _FACE_ENGINE_DIR not in sys.path:
        sys.path.insert(0, os.path.abspath(_FACE_ENGINE_DIR))
    from face_engine import FaceEngine  # type: ignore  # noqa: F811
    _face_engine_available = True
except Exception as _exc:  # pragma: no cover
    logger.warning("FaceEngine not available: %s", _exc)


class FaceRecognitionModule(BaseModule):
    """Module that exposes face recognition through the common BaseModule API."""

    def __init__(
        self,
        db_path: str = "face_database",
        similarity_threshold: float = 0.4,
    ) -> None:
        self._db_path = db_path
        self._threshold = similarity_threshold
        self._engine = None

        if _face_engine_available:
            try:
                self._engine = FaceEngine(
                    db_path=self._db_path,
                    similarity_threshold=self._threshold,
                )
                self._engine.load_database()
            except Exception as exc:  # pragma: no cover
                logger.error("Failed to initialise FaceEngine: %s", exc)

    # ------------------------------------------------------------------
    # BaseModule interface
    # ------------------------------------------------------------------

    @property
    def name(self) -> str:
        return "face_recognition"

    @property
    def keywords(self) -> List[str]:
        return [
            "人臉", "臉部", "辨識人臉", "face", "recognize", "recognition",
            "register face", "人臉辨識", "辨識", "誰", "who",
        ]

    async def execute(self, command: str, **kwargs: Any) -> Dict[str, Any]:
        """Recognize or register faces depending on the command keyword.

        Accepts an optional ``image`` keyword argument containing a
        ``numpy.ndarray`` (BGR) image.

        Returns a dict with:
        - ``action``: one of ``"recognize"``, ``"register"``, ``"list"``
        - ``data``:   action-specific payload
        """
        if self._engine is None:
            return {
                "error": (
                    "Face recognition engine is not available. "
                    "Please ensure InsightFace is installed."
                )
            }

        cmd_lower = command.lower()

        # --- list registered faces ---
        if any(kw in cmd_lower for kw in ("list", "列表", "已註冊", "names")):
            names = self._engine.get_registered_names()
            return {"action": "list", "data": {"faces": names, "total": len(names)}}

        # --- register ---
        if any(kw in cmd_lower for kw in ("register", "註冊", "新增", "add")):
            image = kwargs.get("image")
            name = kwargs.get("name", "")
            if image is None or not name:
                return {
                    "action": "register",
                    "data": {"error": "須提供 name 和 image 才能完成人臉註冊"},
                }
            result = self._engine.register_face(name, image)
            return {"action": "register", "data": result}

        # --- recognize (default) ---
        image = kwargs.get("image")
        if image is None:
            return {
                "action": "recognize",
                "data": {"error": "須提供 image 才能進行人臉辨識"},
            }
        faces = self._engine.recognize(image)
        return {
            "action": "recognize",
            "data": {"face_count": len(faces), "faces": faces},
        }

    def health_check(self) -> bool:
        return self._engine is not None
