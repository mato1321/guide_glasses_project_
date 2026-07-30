import os
from dotenv import load_dotenv
from openai import OpenAI
import time

# 加载 .env 文件
load_dotenv()
client = OpenAI(api_key=os.getenv("api_key"))

async def generate_text(response_text:str , voice:str = "alloy") -> str:
    """
    使用 TTS API 將文字轉換為語音
    
    Args:
        response_text: 要轉換的文字
        voice: 聲音選擇 (預設: alloy)
    
    Returns:
        生成的音檔路徑
    """
    output_audio_path = f"temp_output_{int(time.time() * 1000)}.mp3"

    with client.audio.speech.with_streaming_response.create(
        model = "tts-1",
        voice = "nova",
        input = response_text,
    )as tts_response:
        with open(output_audio_path , "wb") as f:
            for i in tts_response.iter_bytes():
                f.write(i)
    
    return output_audio_path
