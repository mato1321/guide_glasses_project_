# 眼鏡佈建

> 每一台 Rokid Glasses 裝好 APK 之後，**必須跑一次下面的指令**，
> 否則螢幕一關 App 就被系統回收，等於整套系統不存在。
>
> 最後更新：2026-08-08

---

## 必要：解除背景限制

```bash
adb shell cmd appops set com.guideglasses RUN_ANY_IN_BACKGROUND allow
```

### 為什麼

YodaOS **預設把每一個第三方 App 都設成背景限制**，連 Google Maps 也是：

```bash
adb shell cmd appops get com.guideglasses RUN_ANY_IN_BACKGROUND          # ignore
adb shell cmd appops get com.example.ocr RUN_ANY_IN_BACKGROUND           # ignore
adb shell cmd appops get com.google.android.apps.maps RUN_ANY_IN_BACKGROUND  # ignore
```

沒解除的話 `startForeground()` 會被系統**靜默拒絕** ——
不丟例外、不回傳值：

```
W ActivityManager: Service.startForeground() not allowed due to bg restriction
```

App 完全不知道自己還在 cached 狀態，退到背景 2.4 秒就被殺。
**電池最佳化白名單（`deviceidle whitelist`）解不了這個**，那是另一層限制。

### 怎麼確認有沒有生效

```bash
adb logcat -d | grep GuideService
```

| log | 意思 |
|---|---|
| `前景服務已生效，type=192` | ✅ 正常 |
| `🔴 前景服務被系統擋下（背景限制=true）⋯` | ❌ 上面那行指令沒跑或沒生效 |

或直接看行程狀態：

```bash
adb shell dumpsys activity processes | grep -A1 "com.guideglasses"
# oom: cur=200 (fg-service)  ← 正常
# oom: cur=905 (cached)      ← 被擋住了
```

---

## 建議：把 App 標記為使用中

```bash
adb shell am set-inactive com.guideglasses false
```

不是必要的（前景服務已經涵蓋），但開發時可以少一個變因。

---

## 開發用：保持螢幕不滅

眼鏡的螢幕逾時只有 **5 秒**。測試時螢幕一睡，`input tap` 就點不到按鈕，
而且 launcher 會把焦點搶走。

```bash
adb shell svc power stayon true
# 測完記得設回來
adb shell svc power stayon false
```

⚠️ 這只影響開發。**不要**為了讓 App 活著而加長正式版的螢幕逾時 ——
眼鏡只有 210mAh，螢幕亮著會非常耗電，而前景服務已經解決了存活問題。

---

## 🔴 未解決：量產怎麼辦

上面第一條指令要靠 adb，全盲使用者自己進設定關背景限制並不現實。
量產需要 **Device Owner** 或 MDM 佈建。

### 目前查到的狀況（2026-08-08，唯讀檢查）

| 前置條件 | 這台眼鏡 | 是否符合 |
|---|---|---|
| 裝置上沒有帳號 | 0 個 | ✅ |
| 尚未設定 device/profile owner | 無 | ✅ |
| **尚未完成初始設定** | `device_provisioned=1`、`user_setup_complete=1` | ❌ **不符合** |
| `dpm` 指令存在 | 是 | ✅ |

`dpm set-device-owner` 通常要求裝置**尚未完成初始設定**，也就是恢復原廠之後
的第一次開機。這台已經 provisioned，直接下指令大機率會被拒絕。

### ⚠️ 為什麼還沒實際嘗試

**設定成功之後通常要恢復原廠才能解除。** 這是不可逆的裝置變更，
而且會影響組員共用的這台機器，所以只做了唯讀檢查，沒有實際執行。

要試的話，指令是（**執行前請確認你接受恢復原廠的風險**）：

```bash
# 1. 需要一個實作 DeviceAdminReceiver 的 App（本專案目前沒有）
# 2. 恢復原廠後、跳過帳號設定，然後：
adb shell dpm set-device-owner com.guideglasses/.DeviceAdminReceiver
```

### 拿到 Device Owner 之後能做什麼

`DevicePolicyManager.setUserControlDisabledPackages()`（API 30+）可以讓
指定套件**不能被使用者或系統背景限制**，那正是這裡需要的能力。

### 更值得先試的方向

**直接問 Rokid**：他們為什麼預設限制所有 App、有沒有官方的白名單機制。
這比自己走 Device Owner 乾淨得多，而且如果有官方途徑，量產問題就一次解決。
