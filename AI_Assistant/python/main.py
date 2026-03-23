import logging
import os
import time
import base64
import traceback

from dotenv import load_dotenv
from openai import OpenAI
from langchain_openai import ChatOpenAI
from langchain.memory import ConversationBufferMemory
from langchain.chains import ConversationChain
from fastapi import FastAPI, UploadFile, File
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel
from starlette.background import BackgroundTask

from core.command_router import CommandRouter
from core.module_manager import ModuleManager
from modules.audio_module import AudioNavigationModule
from modules.face_recognition_module import FaceRecognitionModule
from modules.obstacle_module import ObstacleModule
from modules.translation_module import TranslationModule

# ---------------------------------------------------------------------------
# Bootstrap
# ---------------------------------------------------------------------------

load_dotenv()
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(
    title="Rokid AI Assistant API",
    description="Centralised AI assistant that dispatches to feature modules",
    version="2.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ---------------------------------------------------------------------------
# OpenAI / LangChain setup
# ---------------------------------------------------------------------------

key = os.getenv("api_key")
client = OpenAI(api_key=key)

llm = ChatOpenAI(
    model="gpt-4o-mini",
    api_key=key,
    temperature=0,
)

memory = ConversationBufferMemory()

conversation = ConversationChain(
    llm=llm,
    memory=memory,
)

# ---------------------------------------------------------------------------
# Module manager & command router
# ---------------------------------------------------------------------------

module_manager = ModuleManager()
module_manager.register(FaceRecognitionModule())
module_manager.register(ObstacleModule())
module_manager.register(AudioNavigationModule())
module_manager.register(TranslationModule())


def _chat_fallback(text: str) -> str:
    """LangChain conversation chain used as the router fallback."""
    return conversation.predict(input=text)


router = CommandRouter(module_manager=module_manager, fallback=_chat_fallback)

# ---------------------------------------------------------------------------
# Pydantic models
# ---------------------------------------------------------------------------


class chatrequest(BaseModel):
    message: str


# ---------------------------------------------------------------------------
# Utility helpers
# ---------------------------------------------------------------------------


def cleanup_file(file_path: str) -> None:
    """刪除臨時檔案"""
    try:
        if os.path.exists(file_path):
            os.remove(file_path)
            logger.info("已刪除臨時檔案: %s", file_path)
    except Exception as exc:
        logger.error("刪除檔案失敗: %s", exc)


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------


@app.get("/")
async def root():
    """健康檢查"""
    return {"status": "ok", "message": "FastAPI is running"}


@app.post("/chat")
async def chat(request: chatrequest):
    """文字對話 — 透過命令路由器分派，無匹配模組時回退到一般對話。"""
    result = await router.route(request.message)
    if result["module"] == "chat":
        return {"reply": result["result"]}
    return result


@app.post("/chat_audio")
async def chat_audio(file: UploadFile = File(...)):
    """
    語音對話完整流程：
    1. 接收語音檔案
    2. Whisper STT (語音轉文字)
    3. CommandRouter 分派 (可能觸發特定模組或回退至 LangChain 對話)
    4. OpenAI TTS (文字轉語音)
    5. 回傳音檔 + 文字
    """
    try:
        logger.info("收到音檔: %s, Content-Type: %s", file.filename, file.content_type)

        # 1) 存成臨時檔案
        temp_path = f"temp_{file.filename}"
        content = await file.read()
        with open(temp_path, "wb") as f:
            f.write(content)

        logger.info("音檔大小: %d bytes", len(content))

        # 2) Whisper STT
        with open(temp_path, "rb") as audio_file:
            transcript = client.audio.transcriptions.create(
                model="gpt-4o-transcribe",
                file=audio_file,
                language="zh",
            )

        # 3) 刪除輸入臨時檔案
        os.remove(temp_path)

        user_text = transcript.text
        logger.info("辨識文字: %s", user_text)

        # 4) 命令路由
        route_result = await router.route(user_text)
        if route_result["module"] == "chat":
            reply_text = route_result["result"]
        else:
            # Summarise the structured result as a spoken sentence
            reply_text = str(route_result.get("result", ""))

        logger.info("AI 回覆: %s", reply_text)

        # 5) TTS
        output_audio_path = f"temp_output_{int(time.time() * 1000)}.mp3"
        with client.audio.speech.with_streaming_response.create(
            model="tts-1",
            voice="alloy",
            input=reply_text,
        ) as tts_response:
            with open(output_audio_path, "wb") as f:
                for chunk in tts_response.iter_bytes():
                    f.write(chunk)

        logger.info("TTS 音檔已生成: %s", output_audio_path)

        # 6) 回傳
        encoded_user_text = base64.b64encode(user_text.encode("utf-8")).decode("ascii")
        encoded_reply = base64.b64encode(reply_text.encode("utf-8")).decode("ascii")

        return FileResponse(
            path=output_audio_path,
            media_type="audio/mpeg",
            headers={
                "X-User-Text": encoded_user_text,
                "X-Reply-Text": encoded_reply,
            },
            background=BackgroundTask(cleanup_file, output_audio_path),
        )

    except Exception as exc:
        traceback.print_exc()
        return JSONResponse(
            status_code=500,
            content={"error": str(exc), "user_text": "", "reply": ""},
        )


@app.post("/tts")
async def text_to_speech(request: chatrequest):
    """純 TTS 服務（給文字，回傳語音）"""
    try:
        output_audio_path = f"temp_tts_{int(time.time() * 1000)}.mp3"

        with client.audio.speech.with_streaming_response.create(
            model="tts-1",
            voice="alloy",
            input=request.message,
        ) as tts_response:
            with open(output_audio_path, "wb") as f:
                for chunk in tts_response.iter_bytes():
                    f.write(chunk)

        logger.info("TTS 音檔已生成: %s", output_audio_path)

        return FileResponse(
            path=output_audio_path,
            media_type="audio/mpeg",
            background=BackgroundTask(cleanup_file, output_audio_path),
        )
    except Exception as exc:
        return JSONResponse(status_code=500, content={"error": str(exc)})


@app.get("/modules")
async def list_modules():
    """列出所有已註冊模組及其健康狀態。"""
    health = module_manager.health_report()
    return {
        "modules": [
            {"name": name, "healthy": status}
            for name, status in health.items()
        ]
    }


@app.post("/command")
async def command(request: chatrequest):
    """直接透過命令路由器分派請求（回傳結構化結果）。"""
    result = await router.route(request.message)
    return result

