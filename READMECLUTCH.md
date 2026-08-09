# CLUTCH — Auto Highlight Reel

On-device, real-time gameplay highlight detection. No cloud, no editing —
the phone watches a session and clips the exciting moments itself.

## Run it

**Full step-by-step instructions, device setup, tuning and troubleshooting are in
[RUNBOOK.md](RUNBOOK.md). Read that one.** Short version:

0. Put your OpenRouter key in `local.properties` (gitignored):
   `OPENROUTER_API_KEY=sk-or-v1-...`. Optional — without it you get the detector's
   ranking and no clip titles, and everything else works.
1. Open this repository's root folder in Android Studio (Hedgehog or newer, JDK 17).
2. If Studio flags a missing Gradle wrapper, accept its offer to generate one —
   `gradle-wrapper.jar` / `gradlew` are the only files not in this repo.
3. Sync, then Run on a **physical device** (API 29+). Screen capture and playback
   audio capture do not work meaningfully on an emulator.
4. Grant the mic + notification prompts, then the system "start recording" prompt.

## How it works

- `CaptureService.kt` — owns the session. MediaProjection → VirtualDisplay →
  MediaRecorder (video + mic audio to a file), plus a parallel playback-audio tap
  via `AudioPlaybackCaptureConfiguration` → `AudioRecord` feeding the detector.
  Driven by `ACTION_START` / `ACTION_STOP` intents.
- `HighlightDetector.kt` — the actual "IP": a rolling-baseline loudness-spike
  detector that flags highlight moments in real time (deliberately a fast
  heuristic, not a trained model — explainable and tunable live). Optimises for
  recall: catch anything that might be a moment.
- `HighlightNarrator.kt` — the second half of the intelligence, and the half that
  does *not* run live. After you stop, it pulls two frames around each candidate,
  sends them to a vision model via OpenRouter, and gets back which candidates are
  real and what to call them. Optimises for precision. **Fails closed** — no key,
  no signal, bad JSON, all fall back to the detector's own ranking.
- `ClipExporter.kt` — trims a clip around each detected timestamp using Media3
  Transformer. Sequential, top-3 by confidence, overlapping windows merged.
- `SessionState.kt` — shared in-memory state; the Service writes, the UI reads.
- `MainActivity.kt` — permissions, start/stop, live audio meter, clip reel with
  thumbnails, in-app playback, and share.

## Tuning the detector (do this once, with numbers)

Every buffer logs under tag `CLUTCH_LEVELS`:

```
adb logcat -s CLUTCH_LEVELS
```

Run one test session on your actual demo game, watch what `delta` the exciting
moments reach, and set `SPIKE_THRESHOLD_DB` in `HighlightDetector.kt` a little
under that. Also worth tuning — all of these are **milliseconds**, not buffer
counts, so they mean the same thing on the demo phone as on the one you tuned on:

- `CONFIRM_WINDOW_MS` — how long the level must stay up before it counts.
  Higher = fewer false positives, slower to fire.
- `COOLDOWN_MS` — how far apart two highlights must be
- `BASELINE_WINDOW_MS` — how much recent audio defines "normal" for this game
- `LEAD_IN_MS` / `LEAD_OUT_MS` in `ClipExporter` — audio spikes tend to fire
  slightly *before* the visual payoff, so lead-in is the longer of the two

Do not tune this blind on stage.

## Knobs you may need on the demo device

- `CaptureService.RECORD_MIC_AUDIO` — set `false` if the mic source or the
  `|microphone` foreground-service type misbehaves. Clips go silent, but capture
  still works.
- `CaptureService.TARGET_SHORT_SIDE` — capture resolution (720 by default).
- `ClipExporter.MAX_CLIPS` — 3. Raising it makes export noticeably slower.

## Known limitations

- `AudioPlaybackCaptureConfiguration` will not capture DRM-protected audio, or
  audio from apps that opted out of playback capture. **Verify your demo game
  early** — this is the single biggest device-specific risk.
- Clips land in the app's external files dir (`Android/data/com.iqoo.clutch/files/clips`),
  not the system Gallery. They're listed and playable in-app; use Share to get
  one out to another app.
- Export re-encodes, so a long session with many highlights takes real time.
  Keep demo sessions to 60–90 seconds.

## Demo script

Play an intense 60–90 seconds of a chosen game live. Let CLUTCH run in the
background — stop it from the notification shade without leaving the game.
Back in the app, the clip reel populates with correctly-timed highlights,
each one *named* — playable on the spot. No editing, no manual tagging.

The line worth landing while the reel builds: **detection ran on-device the whole
time you were playing, because it had to. The naming happened once, at the end,
because that's the part worth spending a model on.**

**Record a successful run as a backup video before you go on stage.**

Close with: **"Every gamer wants this. No phone ships it. This only works
because it has to run on-device, in real time — which is exactly what the
chip in your hand was built for."**
