# glasses-project —— Rokid AI 導盲眼鏡（三版整合）

雲端負責「想得更好」，眼鏡負責「活得下去」。三個版本共用同一個底線：
**拔掉手機與雲端後，障礙物、人臉、OCR、已下載語言的翻譯、本地語音指令必須照常運作。**

| 版本 | 眼鏡 App | 手機 App | 後端 |
|---|---|---|---|
| 端側版（edge） | `edge/` | — | — |
| Cloudflare 版 | `apps/`（flavor `cloudflare`，尚未建立） | `apps/companion/`（flavor `cloudflare`，尚未建立） | `backend/`，跑在家中 GPU 電腦，經 Cloudflare 具名通道 |
| AWS 版 | `apps/`（flavor `aws`，尚未建立） | `apps/companion/`（flavor `aws`，尚未建立） | `backend/`，改寫為 Lambda 部署到 AWS Learner Lab（US$50 額度） |

> 「edge」刻意不叫「地端」：企畫書裡的「地端版（On-Premises）」指的是家中 GPU 電腦那一版，
> 也就是這裡的 Cloudflare 版。

## 資料夾

```
edge/      端側版 Android 專案（凍結）。= GitHub tag v1-local-only（53cf852）= 眼鏡上實際安裝的 APK
apps/      連網版 Android 專案：眼鏡 App + 手機 companion，一份程式碼，之後用 productFlavors 分 cloudflare / aws
backend/   Python FastAPI（公車、步行路線、LLM 意圖、翻譯、定位轉傳）
shared/    模型母本（含 SHA256 清單）、人臉註冊照片、API 規格
docs/      架構、實機發現、任務清單；proposals/ 是兩份導航整合企畫書
backup/    不屬於三版的原始檔案，原封不動保留
```

## 來源（2026-09-29 重整）

| 內容 | 來源 |
|---|---|
| `edge/`、`docs/`、`backup/github-v1-local-only/` | GitHub tag `v1-local-only`（commit `53cf852`，比 `main` 多 2 個修正） |
| `apps/` | `Desktop/guide-glasses_portable/guide-glasses_portable`（組員的公車／導航整合版，衍生自 `main`，**尚未包含** tag 的 2 個修正） |
| `backend/` | `Desktop/backend/backend` |
| `edge/`、`apps/` 的 ASR/KWS 模型 | 從眼鏡 APK 取出（git 與 portable 皆沒有），SHA256 見 `shared/models/SHA256SUMS.txt` |
| `shared/faces/` | 舊 repo 的 `guide-glasses/tools/face_photos/`（已被 `.gitignore` 排除，未進版控） |
| `backup/apk/` | 眼鏡上 `com.guideglasses` 的 APK（253,272,539 bytes） |

原始資料夾都沒有被修改或刪除。

## 不進版控的東西

- `**/.env`、`backend/credentials/`：金鑰。範本見 `backend/.env.example`
- `shared/faces/`：真人臉部照片（生物特徵）
- `shared/models/*`、各模組的 ASR/KWS `.onnx`：只有 `SHA256SUMS.txt` 進版控
- `local.properties`、`backup/local-configs/`、`backup/apk/`

## 建置

需求：JDK 17 以上（直接用 Android Studio 內附的 JBR）、Android SDK 36。
`edge/` 與 `apps/` 各自是獨立的 Gradle 專案，用 Android Studio 分別開啟**那一層**資料夾。

```bash
cd edge && ./gradlew assembleDebug
cd apps && ./gradlew assembleDebug
```

`local.properties` 需自行建立（至少 `sdk.dir=...`），可參考 `backup/local-configs/`。

## 注意

- 這個 clone 的 push 已停用（`git remote -v` 的 push URL 是 `DISABLED-push-not-allowed`），
  要推上 GitHub 前需明確決定並手動恢復。
- 眼鏡上現有的 `com.guideglasses` 是用另一台電腦的 debug key 簽的，這台電腦編出來的同名 APK
  裝不上去。端側版之後若需重編，applicationId 改用 `com.guideglasses.edge` 另裝。
