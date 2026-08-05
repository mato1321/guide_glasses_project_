# 封存：`copilot/refactor-ai-assistant-module` 分支

這裡保存的是一個**未合併就被刪除的遠端分支**的完整內容。

| | |
|---|---|
| 原分支 | `origin/copilot/refactor-ai-assistant-module` |
| 分支 HEAD | `ea531ba`（歷史重寫後；重寫前為 `6cf42c1`） |
| 分出點 | `main` 的 `0de7c5d`（重寫前為 `1adfd1a`） |
| 作者 | `copilot-swe-agent[bot]` |
| 日期 | 2026-03-23 |
| 封存日期 | 2026-08-05 |
| 封存原因 | 專案改為只保留 `main` 一個分支；此分支未合併，先封存再刪除 |

---

## 內容

3 個 commit，804 行新增、82 行刪除，全部在 `AI_Assistant/python/`。

把 AI_Assistant 的後端重構成「中央指令分派器 + 模組架構」：

| 檔案 | 行數 | 說明 |
|---|---|---|
| `core/command_router.py` | +134 | 指令路由 |
| `core/module_manager.py` | +69 | 模組管理 |
| `modules/face_recognition_module.py` | +128 | 人臉辨識模組 |
| `modules/translation_module.py` | +110 | 翻譯模組 |
| `modules/audio_module.py` | +78 | 音訊模組 |
| `modules/obstacle_module.py` | +71 | 障礙物模組 |
| `modules/__init__.py` | +36 | |
| `core/__init__.py` | +4 | |
| `main.py` | +247 / −82 | 改為使用上述架構 |
| `.gitignore` | +9 | |

---

## 備份格式

| 檔案 | 用途 |
|---|---|
| `branch.bundle` | **完整的 git bundle**，含分支的全部歷史。這是主要備份 |
| `000*.patch` | 同樣的 3 個 commit，純文字格式，方便直接閱讀 |

## 如何還原（建議用 bundle）

從 bundle clone 出一個獨立的 repo 來看：

```bash
git clone -b _archive_copilot docs/archive/copilot-refactor-ai-assistant/branch.bundle /tmp/copilot-restore
```

或把分支拉回目前的 repo：

```bash
git fetch docs/archive/copilot-refactor-ai-assistant/branch.bundle _archive_copilot:copilot/refactor-ai-assistant-module
```

**已驗證**：從 bundle 還原出的 HEAD 精確等於 `ea531ba`，6 個重構檔案行數完全一致。

## 如何還原（用 patch）

```bash
git checkout -b restore-copilot-refactor 0de7c5d
```

```bash
git am --allow-empty --3way docs/archive/copilot-refactor-ai-assistant/*.patch
```

> ⚠️ `--allow-empty` 是必要的 —— 第一個 commit「Initial plan」是空 commit，
> 沒有這個旗標 `git am` 會停住。`--3way` 用來處理與上游變動的衝突。

只想看內容不想套用的話，`.patch` 檔本身就是純文字，直接開來讀即可。

---

## 注意

- 這些 patch 的分出點是 `0de7c5d`，而 `main` 之後有 Phase 0 的安全修正
  （`api_key` → `OPENAI_API_KEY`、移除 `debug.jpg` 寫檔等），**套用時 `main.py` 會衝突**。
- `AI_Assistant/` 是組員的工作區。要不要採用這份重構，應由該模組負責人決定。
