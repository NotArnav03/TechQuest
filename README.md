# 🚀 TechQuest — Team Submission

> **Fork this repo → build your app on your fork → open a Pull Request back to `Reskilll/TechQuest`.**
> Your Pull Request is your official submission.

---

## 👥 Team

| | |
|---|---|
| **Team name** | _TODO — fill in (this is also your PR title)_ |
| **Members** | _TODO — fill in_ |
| **City / Venue** | _TODO — Pune / Hyderabad / Bengaluru / Chennai_ |

---

## 🎯 App

| | |
|---|---|
| **App name** | **CLUTCH** |
| **Theme** | Creative tool |
| **One-liner** | Your phone watches you game and cuts your highlight reel by itself, in real time, entirely on-device. |

### What we built

CLUTCH records a gaming session and, while it's still recording, listens to the
game's own audio to work out *when something exciting just happened* — a kill, a
goal, an explosion, a crowd sting. When the session ends it has already cut short
clips around each of those moments and put them in a reel you can play and share.

No manual tagging, no scrubbing a 40-minute recording afterwards, no upload to a
cloud editor. It's for anyone who plays on their phone and would post highlights
if editing them weren't a chore.

### How the AI is used

CLUTCH uses **two** kinds of intelligence, split by what each is actually good at.

**1. On-device detector — runs live, during play.** `HighlightDetector.kt` scores
every incoming audio buffer, maintains a rolling loudness baseline of what "normal"
sounds like for the game being played, and fires when the signal spikes well above
that baseline and *stays* there — with a confirm window to reject pops and a cooldown
so one firefight doesn't produce six near-identical clips. It optimises for **recall**:
catch anything that might be a moment.

**2. Vision LLM — runs once, after you stop.** `HighlightNarrator.kt` takes the
detector's candidates, extracts two frames around each one, and asks a multimodal
model which are *genuinely* highlights and what to call them. It optimises for
**precision and presentation**: throw out the loud menu screen and the reload, keep
the actual plays, and give each clip a name a human recognises.

- **Model:** `minimax/minimax-m3` via OpenRouter — multimodal, ~$0.002 per session
  (configurable via `OPENROUTER_MODEL`; see setup below)
- **AI pattern:** Vision + Classify + Generate (rank candidates from frames, then title them)
- **What it receives:** 1–2 downscaled JPEG frames per candidate plus the detector's
  telemetry (timestamp, peak dB above baseline)
- **What it returns:** structured JSON — a keep/drop verdict, a 2–5 word title and a
  reason per candidate, plus a one-line session summary

**Why the split, and why detection can't be the LLM's job:** the product only works if
detection happens *during* the session, on the device, while the user is still playing.
Round-tripping to a cloud model per candidate would cost seconds each, burn bandwidth,
and stop working the moment the network does. So the latency-critical half stays on the
chip, and the judgment-heavy half — which only needs to run once, over six small images,
during export time that's already dead — goes to a model that can actually *see* what
happened. Audio loudness cannot tell a kill from a crowd sting; frames can.

**It degrades cleanly.** No API key, no signal, a venue hotspot that times out, a model
that returns prose instead of JSON — every one of those paths falls back to the detector's
own confidence ranking. You lose the titles and the culling; you still get your reel.

---

## ▶️ How to run it

```bash
# 1. Clone (your fork)
git clone https://github.com/<your-username>/TechQuest.git
cd TechQuest
```

2. Add your OpenRouter key to `local.properties` in the repository root
   (**gitignored — never commit it**):

   ```properties
   OPENROUTER_API_KEY=sk-or-v1-...
   # Optional — defaults to minimax/minimax-m3
   OPENROUTER_MODEL=minimax/minimax-m3
   ```

   The key is optional. Without it the app builds and runs fine; you just get the
   detector's own ranking and no clip titles.

3. Open the repository root in **Android Studio** (Hedgehog or newer, JDK 17).
4. If Studio offers to generate the missing Gradle wrapper, accept — `gradlew` and
   `gradle-wrapper.jar` are the only files not committed here.
5. Sync, then **Run on a physical device (API 29+)**. Screen capture and playback-audio
   capture do not work meaningfully on an emulator.
6. Grant the microphone + notification prompts, then the system "start recording" prompt.

**OpenRouter setup**
- Base URL: `https://openrouter.ai/api/v1`
- Model: `minimax/minimax-m3` (must be a **multimodal** model — the review sends frames)
- API key: stored in `local.properties`, read into `BuildConfig` at build time —
  **never committed** (`local.properties` is in `.gitignore`).

**Full device setup, detector tuning and troubleshooting: [RUNBOOK.md](RUNBOOK.md).
Architecture notes: [READMECLUTCH.md](READMECLUTCH.md).**

**Build the APK**
- Android Studio → `Build → Build Bundle(s)/APK(s) → Build APK(s)`
- Output: `app/build/outputs/apk/debug/app-debug.apk`

---

## 📱 Demo

- **APK:** _TODO — attach `app-debug.apk` or link it_
- **Screen recording:** _TODO — link a short video of a session on the iQOO_
- **Screenshots:** _optional_

---

## 🧰 Tech stack

- Android / Kotlin, Jetpack Compose (Material 3)
- `MediaProjection` + `VirtualDisplay` + `MediaRecorder` — screen capture
- `AudioPlaybackCaptureConfiguration` + `AudioRecord` — live tap on the game's audio
- Custom real-time RMS / rolling-baseline detector — the live highlight logic
- OpenRouter (`minimax/minimax-m3`) — vision review, culling and titling
- `MediaMetadataRetriever` — frame extraction for the vision request
- `androidx.media3` Transformer — on-device clip trimming
- Kotlin coroutines + `StateFlow` for service ↔ UI state

No third-party HTTP or JSON library: the API call is `HttpURLConnection` + `org.json`,
both in the Android platform, so there's nothing extra to resolve at build time.

---

## ✅ Submission checklist

- [ ] Team name, members and venue filled in above
- [x] README explains what the app does and how the AI works
- [x] API key is **NOT** in the repo (`local.properties`, gitignored)
- [ ] Final code pushed to **your fork**
- [ ] APK and/or screen recording added or linked
- [ ] **Pull Request opened** from your fork → `Reskilll/TechQuest` before the deadline
- [ ] PR title = your **team name**

---

<sub>Built at **TechQuest · AI Tech Workshop** — iQOO Connect × Reskilll.</sub>
