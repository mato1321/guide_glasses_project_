# 人臉特徵模型放這裡

本專案**不含模型權重**。`.onnx` 與 `.tflite` 都被 `.gitignore` 排除 ——
各家模型有各自的授權條款，不適合直接進版控。

支援兩種格式，**優先用 ONNX**（不需轉檔，出錯機會較少）。

---

## 方式一：ONNX（推薦）

檔名：

```
w600k_mbf.onnx
```

**如果你跑過 `Face_Recognition/Python` 的後端，這個檔案已經在你電腦裡了** ——
`insightface` 套件會自動下載：

```
Windows      C:\Users\<你的帳號>\.insightface\models\buffalo_sc\w600k_mbf.onnx
macOS/Linux  ~/.insightface/models/buffalo_sc/w600k_mbf.onnx
```

複製過來即可（約 13MB）：

```bash
cp ~/.insightface/models/buffalo_sc/w600k_mbf.onnx guide-glasses/ai/ai-face/src/main/assets/
```

沒有的話，跑一次 `insightface` 的辨識就會自動下載。也可以改用
`buffalo_l/w600k_r50.onnx`（較準但有 166MB，對 2GB RAM 的眼鏡偏大）。

### 規格

| 項目 | 值 |
|---|---|
| 輸入 | `[1, 3, 112, 112]` float32，**NCHW** |
| 色彩 | RGB |
| 正規化 | `(pixel - 127.5) / 127.5` |
| 輸出 | `[1, 512]` float32 |

與 InsightFace 的 `ArcFaceONNX`（`input_mean=127.5`、`input_std=127.5`、
`swapRB=True`）一致。`OnnxFaceEmbedder` 已依此實作，換同系列的 ArcFace
模型不需改程式（輸出維度會自動讀取）。

---

## 方式二：TFLite

檔名：

```
mobilefacenet.tflite
```

| 項目 | 要求 |
|---|---|
| 輸入尺寸 | 112 × 112，**NHWC**（見 `TfLiteFaceEmbedder.INPUT_SIZE`） |
| 輸入格式 | RGB float32，正規化到 `[-1, 1]` |
| 輸出 | 一維特徵向量（維度自動讀取，128 / 192 / 512 都可以） |

從 ONNX 轉檔：

```bash
pip install onnx2tf onnx onnxruntime tensorflow onnx-graphsurgeon sng4onnx
```

```bash
onnx2tf -i w600k_mbf.onnx -o converted -osd
```

產出 `converted/model_float32.tflite`，改名放進來。

> ⚠️ **轉檔一定要驗證。** ONNX 是 **NCHW**（先全部 R、再全部 G、再全部 B），
> TFLite 是 **NHWC**（每個像素的 R G B 連在一起）。轉換工具要做這層重排，
> 沒處理好時模型照樣輸出向量，**不會有任何錯誤訊息**，只是那些向量沒有意義。
> 這正是預設推薦 ONNX 的原因 —— 不轉檔就沒有這個風險。

若模型的前處理不是 `[-1, 1]` 正規化（例如 `[0, 1]` 或 ImageNet 標準化），
必須改 `TfLiteFaceEmbedder.toInputBuffer()`。

---

## 兩個都沒放會怎樣

`isAvailable` 皆為 false，辨識自動退回**遠端後端**（若已設定 `faceEndpoint`）。
兩條路都沒有時助理才播報「人臉辨識不可用」—— 刻意不靜默失敗。
OCR、相機、語音、翻譯不受影響。

---

## 怎麼知道模型是對的

放好模型、用 [`tools/`](../../../../tools/README.md) 的註冊工具上傳幾個人的照片，
然後對眼鏡說「**同步人臉**」。正常會聽到：

> 「同步完成，3 人，11 張照片」

聽到這一句代表**模型有問題**：

> 「⋯但同一個人的照片相似度只有 8 %，人臉模型可能不正確」

同步時每個人有多張照片可以互相比對。正常模型下同一人的相似度通常在 60% 以上；
掉到 35% 以下幾乎只可能是前處理不符（色彩順序、正規化、張量排列），
而不是照片拍得不好。**這把最難除錯的靜默失敗變成一句聽得見的警告。**

---

## 換模型之後

已註冊的特徵是用舊模型產生的，維度或特徵空間都可能不同。
`FaceMatcher` 會跳過維度不符的資料，但**維度剛好相同卻是不同模型**的情況
無法自動偵測。

換模型後**重新說一次「同步人臉」**即可 —— 眼鏡會用新模型重算全部特徵。
這正是同步照片而不是同步特徵的好處。
