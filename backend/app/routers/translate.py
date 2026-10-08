"""
英→繁中翻譯（deep_translator 免金鑰）。

搬自舊原型 `api/translate_api.py`。目前沒有任何 Kotlin 端呼叫這組端點
——眼鏡端翻譯走的是 `ai-translate`（ML Kit，離線）。保留是因為它是
既有能力的一部分，之後若要做「大範圍自由語句翻譯」可以接這裡而不是
重新造一輪。
"""

from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter()

_latest_text = ""


class TranslateRequest(BaseModel):
    text: str


@router.post("/translate")
def translate_text(req: TranslateRequest):
    global _latest_text

    from deep_translator import GoogleTranslator

    print("收到英文：", req.text)
    chinese = GoogleTranslator(source="en", target="zh-TW").translate(req.text)
    print("翻譯中文：", chinese)

    _latest_text = chinese
    return {"original": req.text, "translation": chinese}


@router.get("/latest-text")
def get_latest_text():
    print("眼鏡取得文字：", _latest_text)
    return {"text": _latest_text}
