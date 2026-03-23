"""Audio Navigation module wrapper.

Wraps audio-based spatial navigation from ``Audio_Navigation/python/main.py``.
"""
import logging
from typing import Any, Dict, List

from modules import BaseModule

logger = logging.getLogger(__name__)


class AudioNavigationModule(BaseModule):
    """Module that provides audio-based navigation guidance."""

    @property
    def name(self) -> str:
        return "audio_navigation"

    @property
    def keywords(self) -> List[str]:
        return [
            "導航", "音頻導航", "navigation", "audio navigation",
            "引導", "方向", "路線", "navigate", "guide", "指路",
        ]

    async def execute(self, command: str, **kwargs: Any) -> Dict[str, Any]:
        """Generate audio navigation guidance.

        Accepts an optional ``destination`` keyword argument.

        Returns a dict with:
        - ``action``: ``"navigate"``
        - ``data``:   navigation instructions or an error message
        """
        destination = kwargs.get("destination", "")
        if not destination:
            # Try to extract destination from the command text
            destination = self._extract_destination(command)

        try:
            guidance = self._generate_guidance(destination)
            return {"action": "navigate", "data": guidance}
        except Exception as exc:
            logger.exception("Audio navigation failed: %s", exc)
            return {"action": "navigate", "data": {"error": str(exc)}}

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _extract_destination(self, command: str) -> str:
        """Attempt a simple extraction of a destination from the command."""
        for kw in ("到", "去", "前往", "navigate to", "go to"):
            if kw in command:
                idx = command.index(kw) + len(kw)
                return command[idx:].strip()
        return ""

    def _generate_guidance(self, destination: str) -> Dict[str, Any]:
        """Generate navigation instructions.

        TODO: integrate the real audio navigation logic from
              Audio_Navigation/python/main.py when available.
        """
        if not destination:
            return {
                "message": "請提供目的地以開始導航。",
                "instructions": [],
            }
        return {
            "destination": destination,
            "message": f"正在為您導航到：{destination}",
            "instructions": [],  # Populated by real navigation model
        }

    def health_check(self) -> bool:
        return True
