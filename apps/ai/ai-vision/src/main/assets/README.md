# 障礙物偵測模型

`obstacle_yolov8.onnx` — 團隊自訓練的 YOLOv8n-seg，**已隨 repo 附上**，不需要另外下載。

## 規格

| 項目 | 值 |
|---|---|
| 來源 | `runs/segment/train-4/weights/best.pt`（base：`yolov8n-seg.pt`） |
| 資料集 | Roboflow `lin-yung-yi/instancee-segment-_test2` v1 |
| 輸入 | `[1, 3, 640, 640]` float32，NCHW，RGB，`pixel / 255`，letterbox 補 114 灰 |
| 輸出 | `output0 [1, 44, 8400]`、`output1 [1, 32, 160, 160]` |
| 大小 | 12.7 MB |

`44 = 4`（cx, cy, w, h）`+ 8`（類別分數）`+ 32`（mask 係數）。

**`YoloObstacleDetector` 只讀前 12 個通道。** 這是分割模型，但 domain 的
`Detection` 只需要框與類別；解 32 個係數與 160×160 的 proto 對導盲沒有增益，
只會增加延遲，而延遲在走路時是實際的安全成本。

## 類別索引

順序**就是** `data.yaml` 的 `names`，不可以更動：

| 索引 | 名稱 | domain 類別 | 種類 |
|---:|---|---|---|
| 0 | `bicycle` | `BICYCLE` | 危險 |
| 1 | `car` | `CAR` | 危險 |
| 2 | `crosswalk` | `CROSSWALK` | 導引 |
| 3 | `guidebrick` | `GUIDE_BRICK` | 導引 |
| 4 | `motorcycle` | `MOTORCYCLE` | 危險 |
| 5 | `obstacle` | `OBSTACLE` | 危險 |
| 6 | `people` | `PERSON` | 危險 |
| 7 | `sidewalk` | `SIDEWALK` | 導引 |

⚠️ **`ObstacleClass` 的 enum 順序與這裡完全不同**（八類裡只有兩類位置相同）。
對照一律走 `YoloObstacleDetector.toObstacleClass()` 的名稱比對，
**絕不可以用 ordinal** —— 弄錯不會有任何錯誤訊息，只會把腳踏車唸成行人。
`ObstacleClassMappingTest` 鎖住了這件事。

## 重新匯出

換模型或重新訓練之後：

```bash
pip install ultralytics onnx onnxsim
```

```bash
python -c "from ultralytics import YOLO; YOLO('best.pt').export(format='onnx', imgsz=640, opset=12, simplify=True, dynamic=False)"
```

把產出的 `best.onnx` 覆蓋成本資料夾的 `obstacle_yolov8.onnx`。

**若類別有變動，必須同時更新三個地方**：

1. `YoloObstacleDetector.CLASS_NAMES`
2. `YoloObstacleDetector.toObstacleClass()`
3. `ObstacleClassMappingTest` 的期望值

## 為什麼是 ONNX 不是 tflite

與 `ai-face` 同一個理由：YOLOv8 是 PyTorch 匯出的，張量排列是 NCHW；
轉 tflite 要做 NHWC 重排，弄錯不會報錯，只會安靜地什麼都偵測不到。
ONNX Runtime 直接吃 NCHW，少一次轉換就少一個出錯的機會。

## 驗證方式

前後處理（letterbox、通道解析、NMS、座標還原）是照著 ultralytics 的做法複刻的，
用資料集的 12 張測試圖與 `model.predict()` 對答案，**類別全部相同、框差在 6 像素內**。
改動這段邏輯之後應該重跑一次同樣的比對。
