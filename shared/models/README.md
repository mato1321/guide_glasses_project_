# 模型母本

這裡的實體檔案**不進版控**（見根目錄 `.gitignore`），只有本說明與 `SHA256SUMS.txt` 進版控。
用途是當 Android 模組或後端的模型遺失時，有一份可驗證的來源可以補回。

驗證：

```bash
cd shared/models && sha256sum -c SHA256SUMS.txt
```

| 路徑 | 用途 | 來源 | 放回哪裡 |
|---|---|---|---|
| `asr/kws/*.onnx`、`asr/zh/model.int8.onnx` | 離線關鍵詞偵測與語音辨識（sherpa-onnx） | 眼鏡 APK（tag `v1-local-only`）。**git 從未收錄**，這是唯一可重建的來源 | `edge/`、`apps/` 的 `ai/ai-asr-offline/src/main/assets/` |
| `tts/` | 離線語音合成（中文 matcha、英文 amy） | tag `v1-local-only` 的 `ai-tts-offline` assets（已在 git 內） | `ai/ai-tts-offline/src/main/assets/tts/` |
| `vision/obstacle_yolov8.onnx` | 障礙物偵測 | tag `v1-local-only`（已在 git 內） | `ai/ai-vision/src/main/assets/` |
| `face/w600k_mbf.onnx` | 人臉特徵（InsightFace buffalo_sc，**僅限非商業研究**） | tag `v1-local-only`（已在 git 內） | `ai/ai-face/src/main/assets/` |
| `bus-led/best.pt` | 公車 LED 車號偵測（後端 `/bus-ocr`） | `Desktop/backend/backend/models/best.pt` | `backend/models/best.pt` |

ASR/KWS 模型建議之後改用 Git LFS 或寫下載腳本納入版控（企畫書 P0：目前是單點存放）。
