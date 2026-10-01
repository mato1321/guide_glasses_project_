"""
真正呼叫 OpenAI 做 function calling，對應手機端既有的協定。

協定定義在 Kotlin 端：`ai-agent/src/main/kotlin/.../AgentProtocol.kt`
（`RouteRequest`/`RouteResponse`/`ToolSpec`/`ToolInvocation`）。這裡的
pydantic model **欄位名稱必須跟 Kotlin 那邊的 `@Serializable` data class
一字不差**，不然手機端會解析失敗。

設計原則（跟 Kotlin 端 `RemoteLlmIntentGateway.kt` 的註解呼應）：
導盲場景下「快速回覆」比「回答得更聰明」重要，所以：
- 預設用小模型（`gpt-4o-mini`）
- 系統提示詞要求 LLM 回覆盡量簡短、口語化——這是要被唸出來的，不是文字聊天
- 任何失敗都要回傳「說得出口的一句話」，不要讓手機端拿到解析不了的東西
"""

from __future__ import annotations

import json

from pydantic import BaseModel

from . import config


class ToolSpec(BaseModel):
    name: str
    description: str
    parameters: list[str] = []


class HistoryTurn(BaseModel):
    role: str
    content: str


class RouteRequest(BaseModel):
    utterance: str
    history: list[HistoryTurn] = []
    tools: list[ToolSpec] = []
    locale: str = "zh-TW"


class ToolInvocation(BaseModel):
    name: str
    arguments: dict = {}


class RouteResponse(BaseModel):
    tool: ToolInvocation | None = None
    reply: str | None = None


SYSTEM_PROMPT = """\
你是導盲眼鏡「guide-glasses」內建的語音助理，服務對象是視障使用者。

# 使用者是誰、你在什麼情境下說話
使用者看不見畫面，只能用耳朵接收你的回覆，而且他人在戶外走動、可能正在過馬路
或等公車——不是坐在電腦前跟你聊天。這決定了下面所有規則。

# 回覆風格
1. 簡短口語化，像面對面講一句話，不要條列、不要「首先/其次/總結」這種書面語。
2. 能一句話講完就不要兩句。使用者聽完馬上要繼續走路或做決定，你拖越久他等越久。
3. 不確定的事不要編——寧可說「我不確定」，也不要講錯的路名、地址、時間。
4. 你不知道使用者現在人在哪裡、眼前有什麼——除非工具回傳結果告訴你，否則不要假裝看得到畫面。

# 什麼時候呼叫工具、什麼時候直接回答
- 使用者的話明確對應某個工具，就呼叫該工具，**不要**用文字重複工具會做的事
  （例如不要說「好的，我幫你查」又同時呼叫工具——直接呼叫就好，結果出來後系統會自己組成回覆）。
- 工具的參數盡量從使用者的話裡抽取；抽不到就別放那個欄位，不要瞎猜或填預設值。
- 不是任何工具能處理的一般問題或閒聊，才直接用繁體中文簡短回答，不呼叫工具。

# 容易搞混的兩個工具：navigate_to 與 plan_bus_route
這兩個都跟公車/路線有關，差別是**使用者有沒有明確講出要去哪裡**：
- 使用者講出具體地點（「帶我去台北車站」「去市政府怎麼走」「我要去士林夜市」）
  → 呼叫 `navigate_to`，`destination` 填使用者講的那個地點，不要換成別的地方、不要翻譯或簡化地名。
  **但要修正語音辨識的錯字**：使用者的話來自眼鏡的離線語音辨識，輸出常是簡體字，
  還會有同音錯字（實測「中正紀念堂」被辨識成「中证纪念堂」，照原文查 Google 就查不到）。
  `destination` 一律寫成台灣慣用的**繁體正式名稱**，例如「中证纪念堂」→「中正紀念堂」、
  「台北车站」→「台北車站」。聽不出是哪裡的話照原文轉成繁體就好，不要猜一個不相干的地點。
- 使用者只是籠統地問公車、沒有講目的地（「查公車路線」「幫我看一下公車」）
  → 呼叫 `plan_bus_route`，不用帶任何參數（目的地系統已經另外設定好了）。
- 拿不準的話，**只要使用者的話裡有任何地名，一律選 navigate_to**——
  忽略使用者講的地點比亂猜一個地點更糟。

# 範例（不是真的工具呼叫紀錄，只是幫助你判斷的參考）
- 「帶我去台北車站」→ 呼叫 navigate_to，destination="台北車站"
- 「去士林夜市要怎麼去」→ 呼叫 navigate_to，destination="士林夜市"
- 「查公車路線」「附近有公車嗎」→ 呼叫 plan_bus_route，不帶參數
- 「翻成日文，你好嗎」→ 呼叫 translate，text="你好嗎"、target_language="ja"
- 「現在幾點」「今天天氣如何」→ 這些不是任何工具能做的事，直接口語簡短回答或誠實說不知道，不呼叫工具
- 「謝謝」「你在嗎」→ 一般對話，簡短回應，不呼叫工具
"""


def _build_tools_schema(tools: list[ToolSpec]) -> list[dict]:
    return [
        {
            "type": "function",
            "function": {
                "name": tool.name,
                "description": tool.description,
                "parameters": {
                    "type": "object",
                    "properties": {name: {"type": "string"} for name in tool.parameters},
                    "required": [],
                },
            },
        }
        for tool in tools
    ]


def _build_messages(req: RouteRequest) -> list[dict]:
    messages = [{"role": "system", "content": SYSTEM_PROMPT}]
    for turn in req.history:
        role = turn.role if turn.role in ("user", "assistant") else "user"
        messages.append({"role": role, "content": turn.content})
    messages.append({"role": "user", "content": req.utterance})
    return messages


class NotConfiguredError(Exception):
    """OPENAI_API_KEY 還沒設定。"""


def route_utterance(req: RouteRequest) -> RouteResponse:
    if not config.OPENAI_API_KEY:
        raise NotConfiguredError("OPENAI_API_KEY 尚未設定")

    from openai import OpenAI

    client = OpenAI(api_key=config.OPENAI_API_KEY)

    response = client.chat.completions.create(
        model=config.OPENAI_MODEL,
        messages=_build_messages(req),
        tools=_build_tools_schema(req.tools) if req.tools else None,
        tool_choice="auto" if req.tools else None,
        timeout=6.0,  # 手機端讀取逾時只給 8 秒，這裡要留緩衝給網路來回
    )

    message = response.choices[0].message

    if message.tool_calls:
        call = message.tool_calls[0]
        try:
            arguments = json.loads(call.function.arguments or "{}")
        except json.JSONDecodeError:
            arguments = {}
        return RouteResponse(tool=ToolInvocation(name=call.function.name, arguments=arguments))

    return RouteResponse(reply=message.content or "我現在不知道該怎麼回答")
