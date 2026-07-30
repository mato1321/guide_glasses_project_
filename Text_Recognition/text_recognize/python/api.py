from fastapi import FastAPI, UploadFile, File
from fastapi.middleware.cors import CORSMiddleware
from ocr_doc import imdecode_bytes, ocr_doc_from_bgr

app = FastAPI()

# 允許外部呼叫 API（例如 Android）
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# 測試 API 是否正常
@app.get("/")
def home():
    return {"message": "OCR API running"}

# OCR 文字辨識
@app.post("/ocr/doc")
async def ocr_doc(file: UploadFile = File(...)):
    image_bytes = await file.read()

    img = imdecode_bytes(image_bytes)
    if img is None:
        return {"ok": False, "error": "圖片讀取失敗"}

    text = ocr_doc_from_bgr(img)

    return {
        "ok": True,
        "text": text
    }