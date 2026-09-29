"""
所有需要密鑰／本機路徑的設定，統一從環境變數讀。

**這個檔案裡不准出現任何真正的金鑰或密碼字串** —— 專案已經發生過一次
「.env 金鑰進版控」事件（見 docs/STATUS.md、docs/ROADMAP.md），這次整併
`api/` 底下的原型時特別要避免重蹈覆轍。實際的值放在 `.env`（已被
`.gitignore` 排除），或部署環境自己的環境變數。

複製 `.env.example` 成 `.env` 並填入真正的值，或直接 export 環境變數。
"""

import os
from pathlib import Path

from dotenv import load_dotenv

load_dotenv()

BACKEND_ROOT = Path(__file__).resolve().parent.parent

# Google Routes API（公車路線規劃，對應 /bus-plans）。
# 留空時 /bus-plans 會回傳明確的「未設定」錯誤，而不是用壞掉的金鑰打 API。
GOOGLE_MAPS_API_KEY = os.getenv("GOOGLE_MAPS_API_KEY", "")

# TDX 運輸資料流通服務（到站時間，對應 /eta）。
TDX_CLIENT_ID = os.getenv("TDX_CLIENT_ID", "")
TDX_CLIENT_SECRET = os.getenv("TDX_CLIENT_SECRET", "")

# Google Cloud Vision 的服務帳戶金鑰路徑（公車車頭 OCR，對應 /bus-ocr）。
# 這個環境變數本身就是 google-cloud 函式庫的標準慣例
# （GOOGLE_APPLICATION_CREDENTIALS），指到 credentials/ 底下的 json 檔即可，
# 該資料夾已在 .gitignore 排除。
GOOGLE_APPLICATION_CREDENTIALS = os.getenv("GOOGLE_APPLICATION_CREDENTIALS", "")
if GOOGLE_APPLICATION_CREDENTIALS:
    os.environ.setdefault("GOOGLE_APPLICATION_CREDENTIALS", GOOGLE_APPLICATION_CREDENTIALS)

# 公車 LED／車頭號碼偵測用的 YOLO 權重。預設放在 models/ 底下（已在 .gitignore
# 排除，太大不該進版控），換機器要自己放一份或改這個環境變數指到別的位置。
BUS_OCR_MODEL_PATH = os.getenv(
    "BUS_OCR_MODEL_PATH",
    str(BACKEND_ROOT / "models" / "best.pt"),
)

# LLM 意圖解析（對應 /route，見 `ai-agent/RemoteLlmIntentGateway.kt` 的協定）。
# 留空時 /route 會回傳一句「尚未設定」的口語回覆，而不是報錯，
# 這樣手機端會把它當成一句正常的助理回話播出來，而不是靜默失敗。
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")
# 選小模型優先：導盲場景下「快速回覆」比「回答得更聰明」重要，
# 見 `RemoteLlmIntentGateway.kt` 的逾時設計（讀取逾時只給 8 秒）。
OPENAI_MODEL = os.getenv("OPENAI_MODEL", "gpt-4o-mini")
