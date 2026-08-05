# 第六部：金鑰外洩處理 Runbook

> ⚠️ **本文件部分結論已被修正。**
> 2026-08-05 依據團隊提供的實際狀況重新分析後，以下結論已不成立：
> 五個獨立專案是刻意的分工、Face_Recognition 已在眼鏡上實機運作、
> App 直接跑在眼鏡上（CameraX 可用）、金鑰已全部重發。
> **請以 [`08_CORRECTIONS_AND_REANALYSIS.md`](08_CORRECTIONS_AND_REANALYSIS.md) 為準。**
> 本文件保留不改寫，僅供分析過程的可追溯性。

狀態：**工作目錄已清理（commit `5687577`）。git 歷史已於 2026-08-05 清除並 force push 完成。**
**⚠️ 金鑰本身尚待撤銷 —— 見 §1，這是唯一有效的止血手段。**

---

## 0. 執行結果摘要（2026-08-05）

| 項目 | 狀態 |
|---|---|
| 工作目錄清理 | ✅ 完成 |
| git 歷史重寫 | ✅ 完成，三個分支全部重寫並 force push |
| 一般 `git clone` 能否取得金鑰 | ✅ 否 |
| **GitHub API 依 SHA 能否取回舊 blob** | ⚠️ **仍可以**（見 §3.4） |
| **撤銷 OpenAI 金鑰** | ❌ **待你執行** |
| **撤銷 GCP 服務帳戶金鑰** | ❌ **待你執行** |
| 通知協作者重新 clone | ❌ 待你執行 |

### 重寫前後的 commit hash 對照

| 分支 | 重寫前 | 重寫後 |
|---|---|---|
| `main` | `cb104d3` | `f7fe1d5` |
| `claude/rokid-guide-glasses-analysis-0b52ae` | `1b20ef8` | `d7da0e1` |
| `copilot/refactor-ai-assistant-module` | `6cf42c1` | `ea531ba` |

`main` 的 17 個 commit 全數保留，365 個檔案完整，僅 hash 改變。

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

## 3. Git 歷史清除 —— ✅ 已於 2026-08-05 執行完成

### 執行時機

刻意等到 PR #2 合併之後才執行。歷史重寫會改變所有 commit hash，若在合併前重寫
`main`，工作分支的祖先 commit 會全部失效而無法合併。
**正確順序是：先合併 → 再重寫歷史 → 再 force push。**

### 實際執行結果

```
清理前，歷史中的機敏 blob：
  dded5150c0d1437c9e5bd4955d3cb055c8d2a8be  AI_Assistant/python/.env
  f0fa304c7e2461a4a6ee73796d14889fd8c5f8f3  Text_Recognition/.../blind-glasses-ocr-*.json

清理後（以全新 clone 獨立驗證）：
  ✅ git rev-list --all --objects | grep  →  無輸出
  ✅ git cat-file -e <blob>              →  兩個 blob 皆不存在
  ✅ main 17 個 commit 全數保留，365 個檔案完整
  ✅ 三個分支全部重寫並 force push 成功
```

### 已執行的步驟（保留供日後參考）

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

### 3.4 ⚠️ 實測發現：GitHub 仍可依 SHA 取回舊 blob

force push 完成之後，實際測試 GitHub API：

```
GET /repos/mato1321/guide_glasses_project_/git/blobs/dded5150c0d1...
  → HTTP 200, size=52     （.env）

GET /repos/mato1321/guide_glasses_project_/git/blobs/f0fa304c7e24...
  → HTTP 200, size=619    （GCP 服務帳戶私鑰）
```

同時驗證：**全新 `git clone` 已經取不到這兩個 blob** ✅

這是 GitHub 的已知行為 —— force push 不會移除被 `refs/pull/*` 參照或近期快取的
物件。一般使用者 clone 不到，但**知道 SHA 的人仍可取得完整內容**，而 SHA 可能
從 fork、快取的 PR 頁面、或 GitHub Events API 封存中取得。

→ **這使得撤銷金鑰不是選項，而是唯一有效的止血手段。**

### 執行後仍必須做的事

1. 🔴 **撤銷兩把金鑰**（見 §1）—— 因為 §3.4 的緣故，這是唯一真正有效的動作。
2. 🔴 **通知所有協作者刪除本機 clone 並重新 clone。** 若有人用舊 clone 執行
   `git pull`，會把舊歷史（含金鑰）推回去，前功盡棄。

   ```bash
   git clone https://github.com/mato1321/guide_glasses_project_.git
   ```

3. 到 GitHub → Insights → Forks 檢查是否有 fork。**fork 不會被 force push 影響，
   金鑰仍留在 fork 的歷史中。** 需聯繫 fork 擁有者，或請 GitHub Support 協助。
4. 考慮向 GitHub Support 申請清除快取的 commit 與 PR ref
   （<https://support.github.com/contact> → 說明是 sensitive data removal）。

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
