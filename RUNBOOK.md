# CLUTCH — Runbook

Everything you need to go from a fresh clone to a working demo on a phone.
Read this end to end once before you start; several steps are ordering-sensitive.

**Read this first:** this code has been reviewed line by line but **never
compiled** — it was written on a machine with no Android SDK. Expect to spend the
first ~10 minutes on a sync and a first build, and possibly on one or two small
compile fixes. Do that before anything else, not at 20 minutes to showtime.

---

## 0. What you need

| Thing | Notes |
|---|---|
| Android Studio | Hedgehog (2023.1) or newer |
| JDK 17 | Use Studio's embedded JDK. AGP 8.4 rejects JDK 24/25. |
| SDK Platform 34 | Tools → SDK Manager → SDK Platforms → Android 14 (API 34) |
| Build-Tools 34 | Tools → SDK Manager → SDK Tools |
| A physical phone, API 29+ | **Not an emulator.** Screen capture and playback-audio capture are meaningless there. |
| A USB cable that does data | Surprisingly common failure. Test it early. |
| An OpenRouter API key | Optional. Enables clip titles and the smarter culling. Without it everything else still works — see §5b. |

---

## 1. Open the project

Open this repository's **root** folder in Android Studio — the one containing
`settings.gradle.kts`.

### The Gradle wrapper

`gradle-wrapper.jar` and `gradlew` are **not in this repo** (they're binary/
generated files that couldn't be authored here). `gradle/wrapper/gradle-wrapper.properties`
*is* present, pinning Gradle 8.6.

Studio normally regenerates the missing pieces on first sync. If it doesn't, use
either fallback:

- **Studio Terminal:** `gradle wrapper --gradle-version 8.6` (needs Gradle on PATH), or
- **Copy them** from any other Android project on the machine: copy `gradlew`,
  `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` across. The jar is a
  small launcher and is not version-specific in a way that matters here.

Once they exist, **commit them** so nobody repeats this step.

### Set the JDK

`File → Settings → Build, Execution, Deployment → Build Tools → Gradle →
Gradle JDK` → pick the **embedded JDK 17** (jbr-17). If this is wrong you get
confusing "Unsupported class file major version" errors.

### Sync

`File → Sync Project with Gradle Files`. First sync downloads Gradle 8.6 plus
Compose/Media3 dependencies — a few hundred MB. **Do this on good wifi, early.**

---

## 2. Prepare the phone

1. **Developer options:** Settings → About phone → tap *Build number* 7×.
2. **USB debugging:** on.
3. **vivo / iQOO / Xiaomi / Oppo specifically** — also enable
   **"USB debugging (Security settings)"** or the equivalent. Without it, installs
   and runtime permission grants get silently blocked. This bites people every time.
4. **Battery / background restrictions:** once CLUTCH is installed, set it to
   *Unrestricted* / *Allow background activity*. Aggressive OEM battery managers
   will kill a foreground service mid-session, and you lose the recording.
5. Plug in, accept the "Allow USB debugging?" fingerprint prompt on the phone.
6. Confirm Studio shows the device in the target dropdown.

---

## 3. Build and install

Run the `app` configuration (green ▶). This does a debug build and installs.

Nothing needs signing — a debug APK is fine for the demo.

If you'd rather use the terminal once the wrapper exists:

```
./gradlew installDebug          # macOS/Linux
gradlew.bat installDebug        # Windows
```

---

## 4. First launch — the permission sequence

The order here matters, and the app enforces it:

1. Tap **Start session**.
2. **Microphone permission** → Allow. *This is the one that matters most.*
   Without `RECORD_AUDIO` the detector never fires and there is no product.
3. **Notification permission** (Android 13+) → Allow. Needed for the foreground
   service notification, which is also where the Stop button lives.
4. **"CLUTCH will start capturing everything on your screen"** → Start now.

If you deny the mic prompt, the app tells you so in the status line rather than
failing silently. Grant it in Settings → Apps → CLUTCH → Permissions and retry.

---

## 5. Tune the detector — do this before the demo, not during

This is the single highest-value 15 minutes you will spend.

```
adb logcat -s CLUTCH_LEVELS CLUTCH
```

Run a **60-second session on the actual game you'll demo**. Watch the log:

```
level=72.3 baseline=64.1 delta=8.2
```

Note what `delta` reaches during the moments you'd *want* clipped (a kill, a
goal, an explosion). Then in `HighlightDetector.kt`:

| Knob | What it does | Symptom it fixes |
|---|---|---|
| `SPIKE_THRESHOLD_DB` (9.0) | dB above rolling baseline that counts as a spike | Nothing fires → lower it. Everything fires → raise it. |
| `CONFIRM_WINDOW_MS` (250) | how long the level must stay up before it counts | Random pops trigger clips → raise it |
| `COOLDOWN_MS` (8000) | minimum gap between highlights | One firefight makes 6 clips → raise it |
| `BASELINE_WINDOW_MS` (3000) | how much recent audio defines "normal" | Slow-building moments get missed → lower it |
| `WARMUP_MS` (1500) | ignore the opening moments | First second always fires → raise it |

Every one of these is in **milliseconds**, deliberately. AudioRecord hands you a
different buffer size on every device, so the detector converts these into buffer
counts at runtime and logs what it picked:

```
adb logcat -s CLUTCH | grep "Detector calibrated"
```

And in `ClipExporter.kt`:

| Knob | What it does |
|---|---|
| `LEAD_IN_MS` (6000) | seconds captured *before* the spike |
| `LEAD_OUT_MS` (4000) | seconds captured *after* |
| `MAX_CLIPS` (3) | how many clips get exported. Raising it slows export a lot. |

Audio spikes tend to fire slightly **before** the visual payoff registers, which
is why lead-in is longer than lead-out. If clips feel like they start too late,
raise `LEAD_IN_MS`.

Set these once from real numbers. Do not tune by feel on stage.

---

## 5b. Set up the vision review (optional, 2 minutes)

After you stop a session, CLUTCH sends two frames from each detected candidate to a
vision model and asks which are real highlights and what to call them. This is the
only network call the app makes, and it happens **after** capture, never during.

Put the key in `local.properties` at the repo root — it is gitignored, and the build
reads it into `BuildConfig` so it never appears in source:

```properties
OPENROUTER_API_KEY=sk-or-v1-...
OPENROUTER_MODEL=minimax/minimax-m3
```

### Picking the model — vision is the hard requirement

The review sends **images**, so a text-only model cannot do this job no matter how
cheap or highly ranked it is. Verified against OpenRouter's live model list:

| Model | Images? | in / out per Mtok | Verdict |
|---|---|---|---|
| `minimax/minimax-m3` | ✅ | $0.30 / $1.20 | **Use this.** Rank-1 on the event's list *and* multimodal |
| `qwen/qwen3.7-flash` | ✅ | $0.03 / $0.13 | Cheapest usable fallback if M3 misbehaves |
| `xiaomi/mimo-v2.5` | ✅ | $0.14 / $0.28 | Fine alternative |
| `deepseek/deepseek-v4-pro` | ❌ text only | $0.44 / $0.87 | **Cannot be used here** despite rank 2 |
| `deepseek/deepseek-v4-flash` | ❌ text only | $0.14 / $0.28 | **Cannot be used here** despite rank 3 |
| `anthropic/claude-opus-5` | ✅ | $5.00 / $25.00 | Best quality, ~17× the cost. Event flags it credit-intensive |

Re-check any model yourself before switching:

```
curl -s https://openrouter.ai/api/v1/models | grep -A3 '"id":"<slug>"'
```

**Cost per session is negligible on M3** — roughly 6 small images plus a short prompt,
about **$0.002 a run**. Hundreds of test sessions won't dent a shared credit pool.
That matters: tune against the real game as much as you like.

Re-sync Gradle after editing (Studio prompts you). Watch it work:

```
adb logcat -s CLUTCH_AI:D CLUTCH:D
```

You want a line like `Review kept 3 of 5 candidate(s)`. What the other outcomes mean:

| Log line | Meaning |
|---|---|
| `No OPENROUTER_API_KEY set` | Key missing or Gradle not re-synced. Detector ranking is used. |
| `OpenRouter returned 401` | Bad key |
| `OpenRouter returned 402` | Out of credits |
| `OpenRouter returned 404` | Model slug wrong — check OpenRouter's model list and set `OPENROUTER_MODEL` |
| `Model kept nothing` | It judged every candidate a false positive. Detector ranking is used. |
| `Highlight review failed` | Network, timeout, or malformed JSON. Detector ranking is used. |

> **Every one of these falls back to the detector's own top-3.** You always get a reel;
> without the review you just lose the titles and the culling. **This is the point** —
> do not let a venue's wifi be a single point of failure for your demo.

**Security, plainly:** an API key compiled into an APK can be extracted from the binary.
`local.properties` keeps it out of git, which is what the submission requires, but not
out of the app. Use a key with a spend limit and rotate it after the event.

---

## 6. Verify the audio tap works on your game — the one real unknown

`AudioPlaybackCaptureConfiguration` **cannot** capture:

- DRM-protected audio
- audio from apps that set `ALLOW_CAPTURE_BY_NONE`

Some games opt out. If your chosen game does, the meter in the app stays flat and
nothing ever fires — and no amount of tuning will help.

**Test this in the first 20 minutes.** Start a session, look at the live meter on
the CLUTCH screen. If it moves with the game audio, you're fine. If it's dead:

- try a different game, **or**
- set `CaptureService.RECORD_MIC_AUDIO = true` (already the default) and accept
  that you're demoing on room audio via the mic — but note the *detector* still
  reads the playback tap, so a dead tap means changing games is the real fix.

Pick your demo game based on this test, not on which game looks coolest.

---

## 7. The demo run

1. Open CLUTCH → **Start session** → grant prompts.
2. Home button → open the game. CLUTCH keeps recording in the background.
3. Play **60–90 seconds**. Longer sessions mean longer exports and dead air on stage.
4. **Stop from the notification shade** — pull down, tap *Stop session*. You never
   have to tab back to the app mid-game.
5. Reopen CLUTCH. Status shows "Exporting clip 1 of 3..." then the reel populates
   with thumbnails.
6. Tap a clip to play it inline. Tap **Share** to send one out.

**What to point at while presenting:** during the session, the live audio meter and
the highlight counter ticking up. That's the proof it's actually listening —
visible before a single clip exists, and it fills the time while you play.

---

## 8. Backup plan (do this, it's 5 minutes)

1. Do one full successful run in the venue, well before you present.
2. **Leave the clips on the device.** The app reloads them from disk on launch, so
   they're still in the reel even after a restart.
3. Separately, **screen-record a successful run** on another phone. If capture
   misbehaves on stage, you show the video and keep talking.

Nobody has ever regretted this. Plenty have regretted skipping it.

---

## 9. Mirroring to a projector

`scrcpy` mirrors the phone over USB and runs fine alongside MediaProjection —
they don't conflict.

```
scrcpy --stay-awake
```

If the venue has HDMI-out for phones, that works too. Check which one you have
before you're standing at the podium.

---

## 10. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Unsupported class file major version` | Gradle running on JDK 21+ | Set Gradle JDK to embedded jbr-17 (§1) |
| `Could not find or load main class ...GradleWrapperMain` | wrapper jar missing | §1, wrapper fallbacks |
| Resource linking failed on a theme or icon | someone re-added `android:icon` or a Material3 View theme | neither exists in this project — revert it |
| Compose compiler version mismatch | Kotlin and compiler ext drifted | they must pair: Kotlin 1.9.24 ↔ ext 1.5.14 |
| App installs, Start does nothing | mic permission denied | Settings → Apps → CLUTCH → Permissions |
| `AudioRecord failed to initialize` in logcat | `RECORD_AUDIO` not granted | same as above |
| Crash the instant recording starts | usually the projection callback or FGS type | check logcat tag `CLUTCH`; confirm the manifest still has `mediaProjection\|microphone` |
| Meter flat, zero highlights | game opted out of playback capture | §6 — change games |
| Session records but no clips appear | check logcat for export errors; also check the session mp4 exists in `Android/data/com.iqoo.clutch/files/` | if the mp4 is 0 bytes, the recorder never started — usually an encoder size issue, lower `TARGET_SHORT_SIDE` |
| Export takes forever | too many highlights, or resolution too high | lower `MAX_CLIPS`, lower `TARGET_SHORT_SIDE` |
| Service dies mid-session | OEM battery manager | set the app to Unrestricted (§2.4) |
| Clips are silent | `RECORD_MIC_AUDIO` off, or mic perm denied | it's on by default; check the permission |
| Clips not in Gallery | by design — they're in app-private external storage | they play in-app; use Share to get them out |
| Clips have no titles | review didn't run | `adb logcat -s CLUTCH_AI:D` and match the line against the table in §5b |
| "Reviewing your session..." hangs | slow venue network | it self-cancels after 45s and exports anyway; no action needed |
| Fewer clips than highlights detected | working as intended | the review culled false positives; the reason is in the `CLUTCH_AI` log |

---

## 11. Where the files land

```
/sdcard/Android/data/com.iqoo.clutch/files/
├── session_<timestamp>.mp4     full session recording (last 3 kept)
└── clips/
    ├── clutch_<timestamp>_1.mp4    exported highlights, tagged with their session
    ├── clutch_<timestamp>_2.mp4
    └── clutch_<timestamp>_3.mp4
```

Clip filenames carry the session timestamp, so a second run never overwrites the
first — and the app only shows the newest session's clips, while keeping the two
before it on disk as a demo fallback. "Clear clips" wipes all of them.

Pull them off with:

```
adb pull /sdcard/Android/data/com.iqoo.clutch/files/clips ./clips
```

Session recordings are **never deleted automatically** — they'll fill storage
after a lot of testing. "Clear clips" in the app only removes the exported clips,
not the source sessions. Delete those manually if space gets tight.

---

## 12. If you're splitting the work

- **Person A** — get it building and installed (§1–3), then own detector tuning (§5).
- **Person B** — own the game choice and the audio-tap verification (§6). This is
  a go/no-go decision and it blocks everything else, so start it immediately.
- **Person C** — own the demo script, the backup recording (§8), and mirroring (§9).

Sync at the 30-minute mark on one question: **has anyone seen a highlight fire on
the real game yet?** If no, everything else stops until that works.
