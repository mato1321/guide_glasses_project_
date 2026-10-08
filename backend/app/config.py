"""
所有需要密鑰／本機路徑的設定，統一從環境變數讀。

**這個檔案裡不准出現任何真正的金鑰或密碼字串** —— 專案已經發生過一次
「.env 金鑰進版控」事件（見 docs/STATUS.md、docs/ROADMAP.md），這次整併
`api/` 底下的原型時特別要避免重蹈覆轍。實際的值放在 `.env`（已被
`.gitignore` 排除），或部署環境自己的環境變數。

複製 `.env.example` 成 `.env` 並填入真正的值，或直接 export 環境變數。
"""

import os
import tempfile
from pathlib import Path

from dotenv import load_dotenv

load_dotenv()

BACKEND_ROOT = Path(__file__).resolve().parent.parent


def _backend_path(value: str) -> str:
    """設定裡的路徑：相對路徑一律相對於 backend/，不管後端是從哪個資料夾啟動的。"""
    if not value:
        return ""
    path = Path(value)
    return str(path if path.is_absolute() else (BACKEND_ROOT / path).resolve())


def _google_credentials_from_json(content: str, directory: Path) -> str:
    """
    服務帳戶 JSON 的**內容** → 寫成檔案，回傳路徑；沒有內容回空字串。

    AWS Lambda 上沒有 credentials/ 資料夾，部署腳本（deploy/aws/deploy.py）把 JSON 內容
    放進環境變數 GOOGLE_APPLICATION_CREDENTIALS_JSON，冷啟動時在這裡寫到 /tmp。
    """
    if not content:
        return ""
    path = directory / "google-application-credentials.json"
    path.write_text(content, encoding="utf-8")
    path.chmod(0o600)
    return str(path)

# Google Routes API（公車路線規劃，對應 /bus-plans）。
# 留空時 /bus-plans 會回傳明確的「未設定」錯誤，而不是用壞掉的金鑰打 API。
GOOGLE_MAPS_API_KEY = os.getenv("GOOGLE_MAPS_API_KEY", "")

# TDX 運輸資料流通服務（到站時間，對應 /eta）。
TDX_CLIENT_ID = os.getenv("TDX_CLIENT_ID", "")
TDX_CLIENT_SECRET = os.getenv("TDX_CLIENT_SECRET", "")

# Google Cloud Vision 的服務帳戶金鑰路徑（公車車頭 OCR，對應 /bus-ocr）。
# 這個環境變數本身就是 google-cloud 函式庫的標準慣例
# （GOOGLE_APPLICATION_CREDENTIALS），指到 credentials/ 底下的 json 檔即可，
# 該資料夾已在 .gitignore 排除。Lambda 上改用 GOOGLE_APPLICATION_CREDENTIALS_JSON，
# 見 _google_credentials_from_json。
#
# 相對路徑（.env.example 寫的 ./credentials/⋯）一律相對於 backend/，不是目前的工作目錄：
# 以前照工作目錄找，從別的資料夾啟動後端（開發工具、Windows 服務）就找不到檔案，
# 實測「確認公車」每次都 500。寫回 os.environ 要用指定而不是 setdefault ——
# load_dotenv 已經把相對路徑放進去了，setdefault 蓋不掉，google-cloud 讀到的還是舊值。
GOOGLE_APPLICATION_CREDENTIALS = _backend_path(os.getenv("GOOGLE_APPLICATION_CREDENTIALS", "")) or _google_credentials_from_json(
    os.getenv("GOOGLE_APPLICATION_CREDENTIALS_JSON", ""), Path(tempfile.gettempdir()),
)
if GOOGLE_APPLICATION_CREDENTIALS:
    os.environ["GOOGLE_APPLICATION_CREDENTIALS"] = GOOGLE_APPLICATION_CREDENTIALS

# 公車 LED／車頭號碼偵測用的 YOLO 權重。預設放在 models/ 底下（已在 .gitignore
# 排除，太大不該進版控），換機器要自己放一份或改這個環境變數指到別的位置。
BUS_OCR_MODEL_PATH = os.getenv(
    "BUS_OCR_MODEL_PATH",
    str(BACKEND_ROOT / "models" / "best.pt"),
)

# 設定時，/bus-ocr 每次都把原圖、畫上 YOLO 框的圖、結果 JSON 存到這個資料夾
# （相對路徑相對於 backend/）。給專題影片剪輯與除錯用；留空＝不存。
# 圖裡可能有路人，用完記得清掉。
BUS_OCR_DEBUG_DIR = _backend_path(os.getenv("BUS_OCR_DEBUG_DIR", ""))

# LLM 意圖解析（對應 /route，見 `ai-agent/RemoteLlmIntentGateway.kt` 的協定）。
# 留空時 /route 會回傳一句「尚未設定」的口語回覆，而不是報錯，
# 這樣手機端會把它當成一句正常的助理回話播出來，而不是靜默失敗。
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")
# 選小模型優先：導盲場景下「快速回覆」比「回答得更聰明」重要，
# 見 `RemoteLlmIntentGateway.kt` 的逾時設計（讀取逾時只給 8 秒）。
OPENAI_MODEL = os.getenv("OPENAI_MODEL", "gpt-4o-mini")

# 眼鏡與手機呼叫這個後端時帶的共用金鑰（HTTP header `X-Api-Key`），見 main.py 的驗證。
# 後端一旦經由 Cloudflare 通道公開，沒有它任何人拿到網址就能用光 OpenAI／Google 額度。
# **沒設定時後端拒絕所有請求**（/health 除外），不是全部放行 —— 忘了設比設錯更常發生。
API_KEY = os.getenv("GUIDEGLASSES_API_KEY", "")

# AWS Lambda 版：手機回報的最新座標存在這個 DynamoDB 表，見 routers/location.py。
# 留空＝存在記憶體（本機、Cloudflare 版）。
LOCATION_TABLE = os.getenv("LOCATION_TABLE", "")
