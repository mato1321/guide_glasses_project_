"""Translation module wrapper.

Wraps translation functionality from ``Translate/python/main.py``.
"""
import logging
import os
from typing import Any, Dict, List

from modules import BaseModule

logger = logging.getLogger(__name__)

# Attempt to reuse the OpenAI client already configured in the environment.
_openai_available = False
try:
    from openai import OpenAI as _OpenAI
    _openai_available = True
except ImportError:  # pragma: no cover
    pass


class TranslationModule(BaseModule):
    """Module that translates text between languages."""

    def __init__(self, model: str = "gpt-4o-mini") -> None:
        self._model = model
        self._client = None
        if _openai_available:
            try:
                self._client = _OpenAI(api_key=os.getenv("api_key"))
            except Exception as exc:  # pragma: no cover
                logger.warning("Failed to initialise OpenAI client for translation: %s", exc)

    # ------------------------------------------------------------------
    # BaseModule interface
    # ------------------------------------------------------------------

    @property
    def name(self) -> str:
        return "translation"

    @property
    def keywords(self) -> List[str]:
        return [
            "翻譯", "translate", "translation", "轉換語言", "語言轉換",
            "英文翻譯", "中文翻譯", "日文翻譯", "韓文翻譯",
        ]

    async def execute(self, command: str, **kwargs: Any) -> Dict[str, Any]:
        """Translate text found in *command* or in ``kwargs["text"]``.

        Keyword args:
            text (str): Text to translate (falls back to *command*).
            target_language (str): Target language (default: ``"英文"``).

        Returns a dict with:
        - ``action``: ``"translate"``
        - ``data``:   translation result or error
        """
        text = kwargs.get("text") or command
        target_language = kwargs.get("target_language", "英文")

        # Basic sanitization: strip leading/trailing whitespace and limit length
        text = str(text).strip()[:4000]
        target_language = str(target_language).strip()[:50]

        if self._client is None:
            return {
                "action": "translate",
                "data": {
                    "error": "Translation service not available (OpenAI client not initialised)."
                },
            }

        try:
            translated = await self._translate(text, target_language)
            return {
                "action": "translate",
                "data": {
                    "original": text,
                    "translated": translated,
                    "target_language": target_language,
                },
            }
        except Exception as exc:
            logger.exception("Translation failed: %s", exc)
            return {"action": "translate", "data": {"error": str(exc)}}

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    async def _translate(self, text: str, target_language: str) -> str:
        import asyncio
        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(
            None, self._translate_sync, text, target_language
        )

    def _translate_sync(self, text: str, target_language: str) -> str:
        prompt = f"請將以下文字翻譯成{target_language}，只回覆翻譯結果：\n{text}"
        response = self._client.chat.completions.create(
            model=self._model,
            messages=[{"role": "user", "content": prompt}],
            temperature=0,
        )
        return response.choices[0].message.content.strip()

    def health_check(self) -> bool:
        return self._client is not None
