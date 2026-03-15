import os
from dotenv import load_dotenv
from openai import OpenAI
from langchain_openai import ChatOpenAI
from langchain.memory import ConversationBufferMemory
from langchain.chains import ConversationChain
from fastapi import FastAPI, UploadFile, File
from pydantic import BaseModel
from fastapi.responses import FileResponse
from fastapi.middleware.cors import CORSMiddleware
from starlette.background import BackgroundTask
import time
import base64

# 加载 .env 文件
load_dotenv()

app = FastAPI()

client = OpenAI(api_key=os.getenv("api_key"))
key = os.getenv("api_key")

llm = ChatOpenAI(
    model = "gpt-4o-mini",
    api_key=key,
    temperature=0
)

memory = ConversationBufferMemory()

conversation = ConversationChain(
    llm = llm,
    memory = memory
)

class chatrequest(BaseModel):
    message : str


@app.post("/chat")
async def chat(request: chatrequest):
    response = conversation.predict(input=request.message)

    return {
        "reply": response
    }

@app.post("/chat_audio")
async def chat_audio(file: UploadFile = File(...)):
    """
    語音對話完整流程：
    1. 接收語音檔案
    2. Whisper STT (語音轉文字)
    3. LangChain + OpenAI 對話
    4. OpenAI TTS (文字轉語音)
    5. 回傳音檔 + 文字
    """
    try:
        print(f"收到音檔: {file.filename}, Content-Type: {file.content_type}")
        
        # 1) 存成臨時檔案
        temp_path = f"temp_{file.filename}"
        with open(temp_path, "wb") as f:
            content = await file.read()
            f.write(content)
        
        print(f"音檔大小: {len(content)} bytes")
        
        # 2) 用 OpenAI Whisper API 轉文字（支援多種格式）
        with open(temp_path, "rb") as audio_file:
            transcript = client.audio.transcriptions.create(
                model="gpt-4o-transcribe",
                file=audio_file,
                language="zh"  # 指定中文
            )
        
        # 3) 刪除臨時檔案
        os.remove(temp_path)
        
        user_text = transcript.text
        print(f"辨識文字: {user_text}")
        
        # 4) 送到 LangChain 對話
        response = conversation.predict(input=user_text)
        print(f"AI 回覆: {response}")
        
        output_audio_path = f"temp_output_{int(time.time() * 1000)}.mp3"

        with client.audio.speech.with_streaming_response.create(
            model = "tts-1",
            voice = "alloy",  # 可選聲音
            input = response,
        ) as tts_response:

            with open(output_audio_path, "wb") as f:
                for chunk in tts_response.iter_bytes():
                    f.write(chunk)

            print(f"TTS 音檔已生成: {output_audio_path}")
        
        user_text = base64.b64encode(user_text.encode("utf-8")).decode("ascii")
        response = base64.b64encode(response.encode("utf-8")).decode("ascii")

         # 5) 回傳音檔
        return FileResponse(
            path=output_audio_path,
            media_type="audio/mpeg",
            headers={
                "X-User-Text": user_text,  # 自訂 header 回傳文字
                "X-Reply-Text": response
            },
            background=BackgroundTask(cleanup_file, output_audio_path)  # 回傳後刪除檔案
        )
    
    except Exception as e:
        import traceback
        traceback.print_exc()
        return {
            "error": str(e),
            "user_text": "",
            "reply": ""
        }


async def text_to_speech(request: chatrequest):
    """
    純 TTS 服務（給文字，回傳語音）
    """
    try:
        output_audio_path = f"temp_tts_{int(os.time.time() * 1000)}.mp3"
        
        with client.audio.speech.with_streaming_response.create(
            model = "tts-1",
            voice = "alloy",  # 可選聲音
            input = request.message,
        ) as tts_response:

            with open(output_audio_path, "wb") as f:
                for chunk in tts_response.iter_bytes():
                    f.write(chunk)

            print(f"TTS 音檔已生成: {output_audio_path}")

         # 5) 回傳音檔
        return FileResponse(
            path=output_audio_path,
            media_type="audio/mpeg",
            background=BackgroundTask(cleanup_file, output_audio_path)  # 回傳後刪除檔案
        )
    except Exception as e:
        return {"error": str(e)}


def cleanup_file(file_path: str):
    """清理臨時檔案"""
    try:
        if os.path.exists(file_path):
            os.remove(file_path)
            print(f"已刪除臨時檔案: {file_path}")
    except Exception as e:
        print(f"刪除檔案失敗: {e}")


@app.get("/")
async def root():
    """測試 API 是否正常"""
    return {"status": "ok", "message": "FastAPI is running"}


