import os
from dotenv import load_dotenv
from openai import OpenAI

# 載入 .env（見 .env.example）
load_dotenv()
client = OpenAI(api_key=os.getenv("OPENAI_API_KEY"))

async def transcribe_audio(temp_path:str , language:str = "zh") -> str:
    """
    使用 Whisper API 將語音轉換為文字
    
    Args:
        temp_path: 臨時音檔路徑
        language: 語言代碼 (預設: 中文)
    
    Returns:
        轉錄的文字
    """
    
    with open(temp_path , "rb") as audiofile:
        transcript = client.audio.transcriptions.create(
            model = "gpt-4o-transcribe",
            file = audiofile,
            language=language
        )
    return transcript.text