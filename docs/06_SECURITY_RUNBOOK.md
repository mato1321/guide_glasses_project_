# 第六部：金鑰外洩處理 Runbook

狀態：**工作目錄已清理並提交（commit `2e626cf`）。歷史清除程序已驗證，待執行。**

---

## 1. 🔴 最優先：撤銷金鑰（只有你能做，且必須現在做）

**刪除檔案、清除 git 歷史，都不能解除「金鑰已經外洩」這個事實。**
這個 repo 是 GitHub 上的公開專案（`github.com/mato1321/guide_glasses_project_`），
任何人在過去任何時間 clone 過，都已經拿到這兩把金鑰的完整內容。

### 1.1 OpenAI API Key

1. 前往 https://platform.openai.com/api-keys
2. 找到目前使用中的金鑰 → **Revoke**
3. 建立新金鑰
4. 填入本機 `AI_Assistant/python/.env` 的 `OPENAI_API_KEY=`（該檔已解除追蹤）
5. 順便到 https://platform.openai.com/usage 檢查**是否有不明用量**

### 1.2 GCP 服務帳戶金鑰

1. 前往 GCP Console → IAM 與管理 → 服務帳戶
2. 找到 `blind-glasses-ocr` 專案的服務帳戶 → 金鑰分頁
3. **刪除** ID 開頭為 `d82297cbca1a` 的金鑰
4. 建立新的 JSON 金鑰，存到**版控目錄之外**（例如 `~/.config/gcloud/`）
5. 更新 `Text_Recognition/text_recognize/python/.env` 的 `GOOGLE_APPLICATION_CREDENTIALS=`
6. 到「記錄檔探索工具」檢查是否有異常呼叫

### 1.3 資料庫憑證

`.env` 中的 `DB_HOST` / `DB_USER` 也已外洩。若該資料庫對外開放，請一併更換密碼並檢查存取記錄。

---

## 2. 已完成的程式碼變更（commit `2e626cf`）

| 變更 | 說明 |
|---|---|
| 解除追蹤 `AI_Assistant/python/.env` | 本機檔案保留，開發不受影響 |
| 解除追蹤 `blind-glasses-ocr-*.json` | 同上 |
| 重寫 `.gitignore` | 機敏樣式移到最前；**移除原本的 `*.json` / `*.png` / `*.jpg` 全域規則**（會誤擋 `google-services.json` 與 Android `res/drawable` 資源） |
| 新增 `.env.example` × 2 | 說明需要哪些環境變數 |
| `main.py` / `stt.py` / `tts.py` | `api_key` → `OPENAI_API_KEY`，缺少時明確 `raise RuntimeError` 而非靜默失敗 |
| `ocr_doc.py` | 移除硬編碼路徑 `C:\Users\user\Downloads\blind-glasses-ocr-*.json`，改由環境變數提供 |
| `main.py` | 移除 `/recognize` 中的 `cv2.imwrite("debug.jpg")` —— 該行會把未經同意的路人臉部影像落地 |
| `requirements.txt` | 補上原本缺漏的 `opencv-python` / `numpy` / `insightface` / `onnxruntime` / `opencc`；新增 `Text_Recognition` 的 requirements |

---

## 3. Git 歷史清除（已驗證，**建議在本分支合併回 main 之後再執行**）

### 為什麼要等到合併後

歷史重寫會改變所有 commit hash。若現在就重寫 `main`，這個工作分支
（`claude/rokid-guide-glasses-analysis-0b52ae`）的祖先 commit 會全部失效，
變成無法合併。**正確順序是：先合併 → 再重寫歷史 → 再 force push。**

### 驗證結果

已在隔離的複本中完整跑過一次，結果：

```
清理前，歷史中的機敏 blob：
  dded5150c0d1437c9e5bd4955d3cb055c8d2a8be  AI_Assistant/python/.env
  f0fa304c7e2461a4a6ee73796d14889fd8c5f8f3  Text_Recognition/.../blind-glasses-ocr-*.json

清理後：
  ✅ 兩個 blob 皆已不存在（git cat-file -e 確認）
  ✅ 12 個 commit 全數保留，僅 hash 改變
```

### 執行步驟

**前置：** 先確認所有分支都已合併或備份 —— 這個操作**不可逆**。

```bash
python -m pip install git-filter-repo
```

```bash
git clone --mirror https://github.com/mato1321/guide_glasses_project_.git gg-mirror
```

```bash
cd gg-mirror && python -m git_filter_repo --invert-paths --path "AI_Assistant/python/.env" --path-glob "Text_Recognition/text_recognize/python/blind-glasses-ocr-*.json" --force
```

驗證（下面這行應該**沒有任何輸出**）：

```bash
git rev-list --all --objects | grep -iE "\.env$|blind-glasses.*\.json"
```

確認無誤後才推送（**不可逆**）：

```bash
git push --force --all origin && git push --force --tags origin
```

### 執行後必須做的事

1. **通知所有協作者刪除本機 clone 並重新 clone。** 若有人用舊 clone 執行 `git pull`，
   會把舊歷史（含金鑰）推回去。
2. 到 GitHub → Settings → 檢查是否有 fork。**fork 不會被你的 force push 影響，
   金鑰仍留在 fork 的歷史中。** 需要聯繫 fork 擁有者，或請 GitHub Support 協助。
3. 檢查 `remotes/origin/copilot/refactor-ai-assistant-module` 這個分支是否也需要處理。
4. GitHub 的快取與 API 可能仍能存取舊 commit 一段時間 → **再次強調：撤銷金鑰才是真正的解法。**

---

## 4. 後續預防

| 措施 | 說明 |
|---|---|
| 啟用 GitHub Secret Scanning | Repo → Settings → Code security → 開啟 Secret scanning + Push protection。**這會在 push 含金鑰的 commit 時直接擋下來** |
| pre-commit hook | 導入 `gitleaks` 或 `detect-secrets`，本機提交前先掃 |
| 正式環境金鑰不落地 | Phase 2 的 Cloud Run BFF 用 Secret Manager，App 端一把金鑰都不放 |
| 定期輪替 | 每季更換一次 API 金鑰 |

---

## 5. 仍待處理的安全問題（非金鑰類，排在後續 Phase）

| 問題 | 位置 | 計畫 |
|---|---|---|
| `/admin` 無任何認證 | `AI_Assistant/python/admin.py` | Phase 3 隨人臉功能搬到端側時一併移除／加認證 |
| `/admin` HTML f-string 拼接使用者輸入 → XSS | 同上 | 同上 |
| `/admin/rename` 未過濾 `../` → 路徑穿越 | `admin.py:136` | 同上 |
| `allow_origins=["*"]` | `api.py:11` | Phase 2 改由 BFF 統一控管 |
| 明文 HTTP + 硬編碼區網 IP | `Text_Recognition/MainActivity.java:58` | 新專案中直接以 HTTPS + BuildConfig 取代 |
| `android:allowBackup="true"` | AndroidManifest | 新專案設為 `false`（人臉資料不得進雲端備份） |
