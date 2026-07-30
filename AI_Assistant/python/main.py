import os
from dotenv import load_dotenv
from openai import OpenAI
from langchain_openai import ChatOpenAI
from langchain.memory import ConversationBufferMemory
from langchain.chains import ConversationChain
from fastapi import FastAPI, UploadFile, File, Query
from pydantic import BaseModel
from fastapi.responses import FileResponse, JSONResponse
from fastapi.middleware.cors import CORSMiddleware
from starlette.background import BackgroundTask
from function import stt, tts , file_manager
import time
import base64
import cv2
import numpy as np
from face_engine import FaceEngine
from opencc import OpenCC

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
        temp_path = await file_manager.save_upload_file(file)
        print(f"收到音檔:{file.filename}")

        # 2) 用 OpenAI Whisper API 轉文字（支援多種格式）
        user_text = await stt.transcribe_audio(temp_path)
        file_manager.cleanup_file(temp_path)
        print(f"辨識文字: {user_text}")
        
        # 3) 送到 LangChain 對話
        response = conversation.predict(input=user_text)
        cc = OpenCC('s2twp')
        response_1 = cc.convert(response)
        print(f"AI 回覆: {response_1}")

        # 4) 文字轉語音
        output_audio_path = await tts.generate_text(response)
        
         # 5) 回傳音檔
        user_text = base64.b64encode(user_text.encode("utf-8")).decode("ascii")
        response = base64.b64encode(response.encode("utf-8")).decode("ascii")

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

@app.post("/tts")
async def text_to_speech(request: chatrequest):
    """
    純 TTS 服務（給文字，回傳語音）
    """
    try:
        output_audio_path = await tts.generate_text(request.message)

        return FileResponse(
            path=output_audio_path,
            media_type="audio/mpeg",
            background=BackgroundTask(cleanup_file, output_audio_path)  # 回傳後刪除檔案
        )
    
    except Exception as e:
        import traceback
        traceback.print_exc()
        return {
            "error": str(e),
        }

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



engine = FaceEngine(db_path="face_database", similarity_threshold=0.4)
engine.load_database()


# tools.py - 將上傳的圖片轉換為 OpenCV 格式
async def read_image_from_upload(file: UploadFile) -> np.ndarray:
    contents = await file.read()
    nparr = np.frombuffer(contents, np.uint8)
    image = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
    return image

@app.post("/recognize")
async def recognize(file: UploadFile = File(...)):
    image = await read_image_from_upload(file)
    if image is None:
        return JSONResponse(
            status_code=400,
            content={"error": "無法解析圖片"}
        )

    # debug - 儲存收到的圖片到本地，方便檢查
    import cv2
    cv2.imwrite("debug.jpg", image)
    print(f"Debug image saved to debug.jpg")
    results = engine.recognize(image)
    return {
        "success": True,
        "face_count": len(results),
        "faces": results
    }

@app.post("/register")
async def register(
    name: str = Query(..., description="要註冊的人名"),
    file: UploadFile = File(...)
):
    image = await read_image_from_upload(file)
    if image is None:
        return JSONResponse(
            status_code=400,
            content={"error": "無法解析圖片"}
        )
    result = engine.register_face(name, image)
    if result["success"]:
        return result
    else:
        return JSONResponse(status_code=400, content=result)

@app.delete("/faces/{name}")
async def delete_face(name: str):
    result = engine.delete_face(name)
    if result["success"]:
        return result
    else:
        return JSONResponse(status_code=404, content=result)

@app.get("/faces")
async def list_faces():
    return {
        "faces": engine.get_registered_names(),
        "total": len(engine.get_registered_names())
    }


@app.post("/reload")
async def reload_database():
    engine.face_database.clear()
    engine.load_database()
    return {
        "message": "資料庫已重新載入",
        "faces": engine.get_registered_names()
    }

from admin import router as admin_router
app.include_router(admin_router)




