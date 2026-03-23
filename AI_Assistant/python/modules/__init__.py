from abc import ABC, abstractmethod
from typing import Any, Dict, List


class BaseModule(ABC):
    """Abstract base class for all feature modules."""

    @property
    @abstractmethod
    def name(self) -> str:
        """Unique module identifier (e.g. 'face_recognition')."""

    @property
    @abstractmethod
    def keywords(self) -> List[str]:
        """Keyword list used for intent matching (Chinese and/or English)."""

    @abstractmethod
    async def execute(self, command: str, **kwargs: Any) -> Dict[str, Any]:
        """Execute the module's primary action.

        Args:
            command: The user command / text input.
            **kwargs: Additional data (e.g. uploaded files, ``image`` ndarray).

        Returns:
            A dict with at least ``action`` and ``data`` keys, e.g.::

                {"action": "recognize", "data": {...}}

            On error the ``data`` value should contain an ``"error"`` key.
        """

    def health_check(self) -> bool:
        """Return True if the module is operational. Override as needed."""
        return True
