# shared —— 三版共用的資料

| 資料夾 | 內容 | 版控 |
|---|---|---|
| `models/` | 模型母本與 SHA256 清單，見 `models/README.md` | 只有清單與說明 |
| `faces/` | 人臉註冊照片，一人一個資料夾 | **不進版控**（生物特徵，屬個資法特種個資） |
| `api/` | 三端之間的 API 規格（步驟 4 實作時建立） | 是 |

## 人臉照片怎麼用

眼鏡上的人臉特徵用 Android Keystore 加密，**無法在 App 之間或裝置之間拷貝**。
每個版本（每個 applicationId）都要各自「同步人臉」一次：

```bash
python edge/tools/face_enroll_server.py --dir shared/faces   # 預設 port 8100
adb reverse tcp:8100 tcp:8100                                   # 走 USB 時
```

App 的 `local.properties` 設 `guideglasses.photoEndpoint=http://127.0.0.1:8100`，
重新建置安裝後，對眼鏡說「同步人臉」。AWS 版改由 S3 + Lambda 提供同一組
`GET /manifest`、`GET /photos/{ref}` 契約。
