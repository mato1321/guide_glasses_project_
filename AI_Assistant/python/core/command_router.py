"""Centralized command router / dispatcher.

Implements a Facade pattern: the AI Assistant delegates every user request to
this router, which detects the user's intent and forwards the request to the
appropriate registered module.  If no module matches, execution falls back to
the general-purpose LangChain conversation chain.
"""
import logging
import re
from typing import Any, Callable, Dict, List, Optional

from core.module_manager import ModuleManager
from modules import BaseModule

logger = logging.getLogger(__name__)


class CommandRouter:
    """Routes user commands to the correct feature module.

    Usage::

        router = CommandRouter(module_manager, fallback_fn)
        result = await router.route("幫我辨識人臉")
    """

    def __init__(
        self,
        module_manager: ModuleManager,
        fallback: Callable[[str], Any],
    ) -> None:
        """
        Args:
            module_manager: Populated :class:`ModuleManager` instance.
            fallback: Async-compatible callable used when no module matches.
                      Should accept a ``str`` and return a reply string.
        """
        self._manager = module_manager
        self._fallback = fallback

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    async def route(self, command: str, **kwargs: Any) -> Dict[str, Any]:
        """Detect intent and dispatch to the matching module(s).

        Supports simple command chaining via 「和」/「並且」/「AND」 separators.
        When multiple intents are detected the results are merged.

        Args:
            command: Raw user text input.
            **kwargs: Extra data forwarded to each module (e.g. uploaded files).

        Returns:
            A dict with:
            - ``module``:  name of the matched module (or ``"chat"`` for fallback)
            - ``result``:  module response
            - ``user_text``: the original command
        """
        sub_commands = self._split_chained(command)

        if len(sub_commands) == 1:
            return await self._dispatch_single(command, **kwargs)

        # --- command chaining ---
        results = []
        for sub in sub_commands:
            results.append(await self._dispatch_single(sub.strip(), **kwargs))
        return {
            "module": "chained",
            "result": results,
            "user_text": command,
        }

    # ------------------------------------------------------------------
    # Intent detection helpers
    # ------------------------------------------------------------------

    def detect_intent(self, command: str) -> Optional[BaseModule]:
        """Return the best-matching module for *command*, or None."""
        command_lower = command.lower()
        best_module: Optional[BaseModule] = None
        best_count = 0

        for module in self._manager.all_modules():
            count = sum(
                1 for kw in module.keywords if kw.lower() in command_lower
            )
            if count > best_count:
                best_count = count
                best_module = module

        return best_module if best_count > 0 else None

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    async def _dispatch_single(self, command: str, **kwargs: Any) -> Dict[str, Any]:
        module = self.detect_intent(command)

        if module is None:
            logger.debug("No module matched '%s'; falling back to chat.", command)
            reply = await self._call_fallback(command)
            return {"module": "chat", "result": reply, "user_text": command}

        logger.info("Routing '%s' → module '%s'", command, module.name)
        try:
            result = await module.execute(command, **kwargs)
            return {"module": module.name, "result": result, "user_text": command}
        except Exception as exc:  # pragma: no cover
            logger.exception("Module '%s' raised an error: %s", module.name, exc)
            return {
                "module": module.name,
                "result": {"error": str(exc)},
                "user_text": command,
            }

    async def _call_fallback(self, command: str) -> str:
        """Call the fallback (may be sync or async)."""
        import asyncio
        import inspect
        if inspect.iscoroutinefunction(self._fallback):
            return await self._fallback(command)
        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(None, self._fallback, command)

    @staticmethod
    def _split_chained(command: str) -> List[str]:
        """Split compound commands joined by 和/並且/AND."""
        pattern = r"\s+(?:和|並且|and|AND)\s+"
        parts = re.split(pattern, command)
        return parts if len(parts) > 1 else [command]
