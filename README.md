# Guide Glasses — AI Navigation Glasses for the Visually Impaired

**English** | [繁體中文](README.zh-TW.md)

Guide Glasses turns a pair of **Rokid smart glasses** into a voice-controlled assistant for blind and low-vision users.
Wearing the glasses, the user never has to look at a screen or press a button. They just speak, and the glasses
tell them what is ahead, who is in front of them, what a sign says, and how to catch the right bus.

> The cloud makes the glasses *smarter*; the glasses keep the user *safe*.
> Obstacle detection, face recognition, text reading and speech all run **on the glasses, offline**.
> Only queries that need live data, such as bus routes and arrival times, go online.

---

## Highlights

- **Fully voice-controlled, no buttons.** 18 spoken commands, no wake word. Say *"我要說話"* (or tap the touchpad once) to ask anything in free speech.
- **Safety features work offline.** Obstacle detection (YOLOv8), face recognition, text reading (OCR), translation, speech recognition and speech synthesis all run on the glasses.
- **A complete bus trip, end to end.** Plan the fastest bus → walk to the stop with turn-by-turn voice guidance → point the glasses at the arriving bus to confirm its route number.
- **The phone is the GPS.** The glasses have no GPS. The companion phone app shares its location **directly over the Wi-Fi hotspot**, so navigation keeps working even if the backend goes down. The backend acts as a fallback relay.
- **A natural-language assistant.** An LLM understands requests such as *"I want to take a bus to Chiang Kai-shek Memorial Hall"* and corrects speech-recognition typos in Taiwanese place names.
- **Built for real devices.** Announcements are always at maximum volume, the backend URL can be changed at runtime (no rebuild when a Cloudflare tunnel restarts), and three app versions can coexist on one pair of glasses.
- **Privacy and security by default.** Face embeddings stay on the glasses, encrypted with AES-GCM keys held in the Android Keystore. The backend rejects every request without an API key (fail-closed). Keys, photos and location data are never committed.

## What It Can Do

Just speak. No wake word is needed.

| Feature | Say (Mandarin) | Meaning | Needs internet |
|---|---|---|---|
| Stop everything (including navigation) | 停止播報 / 不要說了 | "Stop" | No |
| Detect obstacles once / continuously | 前面有什麼 / 持續偵測前方 | "What's ahead?" / "Keep watching ahead" | No |
| Recognize a person | 這是誰 / 這個人是誰 / 前面有沒有人 | "Who is this?" | No |
| Read text / signs | 上面寫什麼 / 唸給我聽 / 這是哪裡 / 下一段 | "What does it say?" | No |
| Translate the text just read | 翻成英文 | "Translate to English" | No (language pack downloaded once) |
| Plan a bus trip (preset destination) | 查公車路線 | "Find a bus route" | Yes |
| Confirm the arriving bus | 確認公車 | "Confirm the bus" | Yes |
| Ask anything / name a destination | 我要說話 → *beep* → your question | "I want to talk" | Yes |
| Readiness check | 出門前檢查 | "Check before going out" | No |
| Sync registered faces | 同步人臉 | "Sync faces" | Local network |
| Camera self-test | 測試相機 | "Test camera" | No |

**Example: taking a bus**

1. Say *"我要說話"*, wait for the beep, then say *"I want to take a bus to …"* (in Mandarin). Or simply say *"查公車路線"*.
2. The glasses announce which bus to take, where to board, and when the next one arrives.
3. Turn-by-turn **walking navigation to the bus stop starts automatically**.
4. When a bus arrives, face its front and say *"確認公車"*. The glasses read the route number on the LED panel and tell you whether it matches.
5. Say *"停止播報"* to end navigation.

## System Architecture

```
 ┌──────────────────────────┐   Wi-Fi hotspot (direct GPS)   ┌──────────────────────┐
 │  Rokid Glasses (Android) │ ◄────────────────────────────► │  Android phone       │
 │  • voice commands, ASR   │                                │  • GPS, 1 fix/second │
 │  • obstacles / faces /   │                                │  • hotspot server    │
 │    OCR / translation     │                                │  • backend URL setup │
 │  • offline TTS, nav      │                                └──────────┬───────────┘
 └────────────┬─────────────┘                                           │
              │ HTTPS (Cloudflare Tunnel, X-Api-Key)                    │ HTTPS
              ▼                                                         ▼
 ┌────────────────────────────────────────────────────────────────────────────────┐
 │  Backend — FastAPI (home PC)                                                   │
 │  /route (LLM intent) · /bus-plans · /eta · /walking-route · /bus-ocr · location │
 └──────────┬──────────────────┬───────────────────┬──────────────────┬───────────┘
            ▼                  ▼                   ▼                  ▼
         OpenAI         Google Routes API      TDX (bus ETA)   Google Cloud Vision
```

| Part | Responsibilities | Tech |
|---|---|---|
| Glasses app | Voice commands, speech recognition, on-device vision, offline speech output, navigation state machine, announcement arbitration | Kotlin, CameraX, Hilt, sherpa-onnx, ONNX Runtime, ML Kit |
| Phone app | GPS fixes every second, served directly to the glasses over the hotspot and relayed to the backend as a fallback | Kotlin, Fused Location, foreground service |
| Backend | LLM intent parsing, bus plans and arrival times, walking routes, bus front-panel recognition, location relay | Python 3.12, FastAPI, OpenAI, Google Routes, TDX, Ultralytics YOLO, Google Cloud Vision |

## AI Models

| Task | Model | Runs on | License |
|---|---|---|---|
| Obstacle detection (8 classes: pedestrian, car, motorcycle, bicycle, obstacle, crosswalk, tactile paving, sidewalk) | `obstacle_yolov8.onnx`, **trained by our team** from YOLOv8n-seg | Glasses | AGPL-3.0 (Ultralytics) |
| Bus LED panel detection | `best.pt`, **trained by our team** with Ultralytics YOLO | Backend | AGPL-3.0 (Ultralytics) |
| Face embedding (512-d) | InsightFace `w600k_mbf` (MobileFaceNet, ArcFace) | Glasses | Non-commercial research only |
| English speech synthesis | Piper `en_US-amy-medium` (VITS) | Glasses | See model card |
| Keyword spotting (voice commands) | sherpa-onnx zipformer KWS | Glasses | See sherpa-onnx |
| Speech recognition (Mandarin, streaming) | `sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01` | Glasses | See sherpa-onnx |
| Mandarin speech synthesis | `matcha-icefall-zh-baker` + HiFi-GAN vocoder | Glasses | See sherpa-onnx |
| Face detection, OCR, translation | Google ML Kit | Glasses | Google ML Kit terms |
| Intent parsing and chat | OpenAI `gpt-4o-mini` | Cloud | OpenAI terms |
| Bus number OCR | Google Cloud Vision | Cloud | Google Cloud terms |

Model sources and SHA-256 checksums are listed in [`shared/models/README.md`](shared/models/README.md).

## Repository Structure

```
apps/              Android project (Cloudflare & AWS flavors share one codebase)
├── app/           Glasses app — "導盲眼鏡 CF / AWS"
├── companion/     Phone app — "導盲定位 CF / AWS"
├── core/          Domain logic: voice commands, bus planning & confirmation,
│                  walking navigation, announcement priority, encrypted face storage
├── ai/            Offline ASR & keyword spotting, offline TTS, obstacle detection,
│                  face recognition, OCR, translation, location & bus gateways
├── glasses/       Camera (CameraX) and motion sensors
├── feature/       Assistant view model (command dispatch) and beep tone
└── tools/         Face enrollment server (upload photos in a browser)
backend/           FastAPI backend, tests, Cloudflare Tunnel deployment guide
edge/              Offline-only edition (frozen, kept for reference)
docs/              Architecture, device findings, project plan, quick-start card (PDF)
shared/models/     Model inventory and SHA-256 checksums
backup/            Pre-restructure originals, kept untouched
```

## Getting Started

### Requirements

- Rokid Glasses (Android 12) and an Android phone
- Windows PC with **Android Studio** (bundled JDK 17+), Android SDK 36
- **Python 3.12** and **cloudflared**
- API keys: OpenAI, Google Maps Platform (Routes API), TDX, and a Google Cloud Vision service account

### 1. Get the code

```bash
git clone https://github.com/mato1321/guide_glasses_project_.git
cd guide_glasses_project_
git checkout restructure/three-versions
```

The speech recognition and keyword-spotting model files are too large for Git. See [`shared/models/README.md`](shared/models/README.md)
for where to get them and how to verify their checksums. Place them in `apps/ai/ai-asr-offline/src/main/assets/`.

### 2. Start the backend

```bash
cd backend
py -3.12 -m venv .venv
.venv\Scripts\pip install -r requirements.txt
copy .env.example .env
```

Fill in `.env`, including `GUIDEGLASSES_API_KEY`, a shared secret of your choice. Without it, the backend rejects every request. Then start the backend and open a tunnel:

```bash
powershell -ExecutionPolicy Bypass -File deploy\cloudflare\start-backend.ps1 -Port 8001
```

```bash
cloudflared tunnel --url http://localhost:8001
```

Check `https://<your-tunnel>.trycloudflare.com/health`. It should return `{"status":"ok"}`.

### 3. Configure and build the apps

Create `apps/local.properties`. It is git-ignored, so keys stay on your machine.

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
guideglasses.cloudflare.busApiEndpoint=https://<your-tunnel>.trycloudflare.com
guideglasses.cloudflare.llmEndpoint=https://<your-tunnel>.trycloudflare.com/route
guideglasses.cloudflare.apiKey=<same value as GUIDEGLASSES_API_KEY>
```

Optional: copy `apps/keystore.properties.example` to `keystore.properties` so that every team member signs with the same key.

Open the `apps` folder in Android Studio, or build from the command line:

```bash
cd apps
gradlew.bat :app:assembleCloudflareDebug :companion:companion-app:assembleCloudflareDebug
```

### 4. Install

```bash
adb -s <glasses-serial> install -r -g app/build/outputs/apk/cloudflare/debug/app-cloudflare-debug.apk
adb -s <phone-serial> install -r companion/companion-app/build/outputs/apk/cloudflare/debug/companion-app-cloudflare-debug.apk
```

One-time setup on the glasses, so the app is not killed in the background and the clock stays correct:

```bash
adb -s <glasses-serial> shell cmd appops set com.guideglasses.cloudflare RUN_ANY_IN_BACKGROUND allow
adb -s <glasses-serial> shell settings put global auto_time 1
```

### 5. Use it

1. Turn on the phone's hotspot and connect the glasses to it.
2. On the phone, open **導盲定位 CF**. It starts sharing location automatically.
3. On the glasses, open **導盲眼鏡 CF** and wait about 20 seconds for the speech models to load.
4. Say *"出門前檢查"*. When you hear "可以出門了" ("ready to go"), you're set.

**Tunnel URL changed?** No rebuild is needed. Paste the new URL into the phone app's backend URL field. Glasses connected to the phone's hotspot
pick it up automatically. Otherwise, set it with
`adb shell am broadcast -a com.guideglasses.cloudflare.DEBUG --es cmd SET_BACKEND --es url <new-url>`.

## Testing

```bash
cd apps && gradlew.bat test            # 423 unit tests
cd backend && .venv\Scripts\python -m pytest   # 14 tests
```

Debug builds also accept `adb` broadcasts that trigger any feature without speaking, for example
`--es cmd DETECT_OBSTACLES` or `--es cmd ASK --es text 你好`. See `MainActivity.kt` for the full list.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| "目前沒有網路" ("no network") or Wi-Fi shows *limited connectivity* | The glasses' clock is wrong, so every HTTPS certificate fails. Enable automatic time (see step 4). |
| "拿不到定位" ("no location") | Make sure the phone app is running and online. Indoor fixes can take up to 15 seconds. |
| Two voices talking at once | The old offline edition (導盲眼鏡) is also running. Close it. |
| A command isn't recognized | Speak clearly and close to the glasses, or tap the touchpad once and speak after the beep. |

## Documentation

- [Quick-start card (PDF, Chinese)](docs/AI導盲眼鏡操作卡.pdf)
- [Project plan (PDF, Chinese)](docs/AI導盲眼鏡專題計畫書.pdf)
- [Architecture](docs/ARCHITECTURE.md) · [Device findings](docs/DEVICE_FINDINGS.md) · [Glasses provisioning](docs/PROVISIONING.md)
- [Backend](backend/README.md) · [Cloudflare Tunnel deployment](backend/deploy/cloudflare/README.md)
- [Restructure notes, 2026-09-29 (Chinese)](docs/RESTRUCTURE_NOTES.md)

## Editions

| Edition | Glasses app | Backend | Status |
|---|---|---|---|
| Edge (offline only) | `edge/` | none | Frozen |
| **Cloudflare** | `apps/`, flavor `cloudflare` | FastAPI on a home PC + Cloudflare Tunnel | **Main edition, field-tested** |
| AWS | `apps/`, flavor `aws` | AWS Lambda (in progress) | In progress |

## License

Source code is released under the [MIT License](LICENSE). Third-party models and libraries keep their own licenses.
Notably, the InsightFace weights are for non-commercial research only, and models trained with Ultralytics YOLO fall under AGPL-3.0.
