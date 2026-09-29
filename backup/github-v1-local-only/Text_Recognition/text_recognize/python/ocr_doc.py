import os
import cv2
import numpy as np
import re
from dotenv import load_dotenv
from google.cloud import vision

# 憑證路徑從 .env 讀取（見 .env.example）。
# 絕不可把金鑰路徑或金鑰內容寫死在程式碼裡。
load_dotenv()

if not os.getenv("GOOGLE_APPLICATION_CREDENTIALS"):
    raise RuntimeError(
        "缺少環境變數 GOOGLE_APPLICATION_CREDENTIALS。\n"
        "請複製 .env.example 為 .env，並填入服務帳戶金鑰的檔案路徑。"
    )

# 把 bytes 轉成 OpenCV 圖片
def imdecode_bytes(image_bytes: bytes):
    arr = np.frombuffer(image_bytes, dtype=np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    return img

# 圖片前處理（讓 OCR 更準）
def preprocess_doc(bgr):

    img = cv2.resize(
        bgr,
        None,
        fx=1.5,
        fy=1.5,
        interpolation=cv2.INTER_CUBIC
    )

    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)

    # CLAHE 增強對比
    clahe = cv2.createCLAHE(
        clipLimit=1.5,
        tileGridSize=(8, 8)
    )

    gray = clahe.apply(gray)

    return gray

# Google Vision OCR
def vision_ocr(gray):

    client = vision.ImageAnnotatorClient()

    ok, buf = cv2.imencode(".png", gray)

    if not ok:
        return ""

    image = vision.Image(content=buf.tobytes())

    response = client.document_text_detection(
        image=image,
        image_context={
            "language_hints": ["zh-TW", "en"]
        }
    )

    if response.error.message:
        raise RuntimeError(response.error.message)

    if not response.text_annotations:
        return ""

    return response.text_annotations[0].description

# 整理 OCR 文字
def clean_text(text):

    # 去掉換行
    text = text.replace("\n", " ")

    # 多個空格變一個
    text = re.sub(r"\s+", " ", text)

    return text.strip()

# OCR 主函式（API / AI助理 呼叫）
def ocr_doc_from_bgr(image_bgr):

    gray = preprocess_doc(image_bgr)

    text = vision_ocr(gray)

    text = clean_text(text)

    print("OCR結果：")
    print(text)

    return text