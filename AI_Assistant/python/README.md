# FastAPI 語音/文字聊天 API（OpenAI + LangChain）

這是一個使用 **FastAPI** 建立的簡易聊天服務，支援：
- `POST /chat`：文字對話（透過 LangChain `ConversationChain` + 記憶）
- `POST /chat_audio`：語音對話（上傳音檔 → STT 轉文字 → 對話 → TTS 產生語音 → 回傳 mp3）
- `GET /`：健康檢查

> 注意：目前程式碼中的 `/chat_audio` 會把對話記憶存放在全域 `ConversationBufferMemory()`，也就是**所有使用者共用同一段對話記憶**；如果要做到每位使用者/每個 session 各自獨立，需要再做 session/user 綁定。


## 專案結構（建議）
假設你的主程式檔案叫 `main.py`：

```
.
├── main.py
├── .env
├── requirements.txt
└── README.md
```


## 環境需求
- Python 3.10+（建議 3.11）
- 需要有 OpenAI API Key


## 安裝與啟動

### 1) 建立虛擬環境（建議）
```bash
python -m venv .venv
# macOS / Linux
source .venv/bin/activate
# Windows PowerShell
# .\.venv\Scripts\Activate.ps1
```

### 2) 安裝套件
```bash
pip install -r requirements.txt
```

### 3) 建立 `.env`
在專案根目錄建立 `.env`，內容如下：

```env
api_key=YOUR_OPENAI_API_KEY
```

> 程式碼用的是 `os.getenv("api_key")`，所以 key 名稱必須是 `api_key`。


### 4) 啟動 FastAPI
```bash
uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

啟動後可打開 Swagger 文件：
- `http://127.0.0.1:8000/docs`


## API 使用方式

### 1) GET /
健康檢查

**Request**
```bash
curl http://127.0.0.1:8000/
```

**Response**
```json
{"status":"ok","message":"FastAPI is running"}
```


### 2) POST /chat（文字聊天）
**Request**
```bash
curl -X POST http://127.0.0.1:8000/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"你好，請自我介紹"}'
```

**Response**
```json
{
  "reply": "..."
}
```


### 3) POST /chat_audio（語音聊天）
流程：
1. 上傳音檔
2. OpenAI STT (`gpt-4o-transcribe`) 轉文字
3. LangChain 進行對話（有記憶）
4. OpenAI TTS (`tts-1`) 把 AI 回覆轉成 mp3
5. 回傳 mp3，並在 response headers 夾帶文字資訊（base64 編碼）

**Request（curl 範例）**
```bash
curl -X POST "http://127.0.0.1:8000/chat_audio" \
  -H "accept: audio/mpeg" \
  -F "file=@./your_audio_file.mp3"
```

**Response**
- Body：`audio/mpeg`（mp3 音檔）
- Headers：
  - `X-User-Text`：使用者語音辨識文字（base64）
  - `X-Reply-Text`：AI 回覆文字（base64）

你可以用任意方式把 header base64 decode 回來，例如（macOS/Linux）：
```bash
echo "BASE64_STRING" | base64 --decode
```


## 使用到的 OpenAI 模型
- STT：`gpt-4o-transcribe`
- Chat：`gpt-4o-mini`（透過 `langchain_openai.ChatOpenAI`）
- TTS：`tts-1`（voice: `alloy`）


## 注意事項 / 已知問題
1. **Conversation 記憶是全域共享**
   - `memory = ConversationBufferMemory()` 是全域變數，代表所有人打 API 都共享同一段對話記憶。
   - 若要支援多人同時使用，建議把 memory 改成「依照 user/session 分流」。

2. **暫存檔案**
   - 上傳音檔會暫存為 `temp_{filename}`，完成 STT 後會刪除。
   - TTS 產生的 mp3 回傳後會透過 `BackgroundTask(cleanup_file, ...)` 刪除。

3. **`text_to_speech` 目前不是 API endpoint**
   - `text_to_speech()` 沒有加上 `@app.post(...)`，所以外部呼叫不到。
   - 如果你想提供純文字轉語音 API，需要補上路由 decorator。

4. **程式碼可能存在小 bug**
   - `text_to_speech` 裡面使用了 `os.time.time()`（這會出錯），正確通常是 `time.time()`。
   - 如果你需要，我也可以幫你把這段修正並補上 `/tts` endpoint。


## 授權
自行依需求加入 License（例如 MIT）。