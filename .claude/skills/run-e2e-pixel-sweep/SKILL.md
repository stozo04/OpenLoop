---
name: run-e2e-pixel-sweep
description: >-
  Run the repeatable OpenLoop 4-emulator E2E sweep (Pixel 6, Pixel 8, Pixel 10 Pro Fold, Pixel 8
  API 34) using the canonical imported test video: build once, then per device cold-boot →
  scripted import → trim → speed → all four loop directions (real reverse previews) → B&W look →
  save → share sheet → gallery playback, followed by a programmatic output-quality gate (per-half
  fps, mirror SSIM, freeze/green/black scans) and a logcat scan. Use whenever the user says
  "pixel sweep", "run the 4-device e2e", "/run-e2e-pixel-sweep", "api 34 fgs regression",
  "samsung rtl sweep", "/samsung-rtl-sweep", "regression-test the boomerang flow on the emulators",
  or wants proof a media-pipeline or foreground-service change didn't break the import→save path
  on Google devices and Android 14. Scripts compute every gesture from uiautomator bounds, so they
  run unmodified on any screen size.
---

# run-e2e-pixel-sweep — the repeatable 4-emulator OpenLoop sweep

Proven end-to-end on 2026-06-04 (fold-loop iterations 1–2 + scripted validation runs on
Pixel 6 and the Fold) and 2026-06-22 (API-34 FGS fix verification on `Pixel_8_API34`).
One sweep ≈ 10 minutes/device, ~40 minutes total, fully scripted — your job is to run the
phases, read the PASS/FAIL lines, and apply judgment where the scripts explicitly hand it back
(borderline SSIM, any FAIL, churn growth).

`<skill>` = this directory. Sibling skill `run-e2e` provides `scripts/scan-logcat.ps1` and
`scripts/uiauto.ps1` (manual driving / debugging) — reuse them, don't duplicate.

## The canonical test asset

`<repo>\google-pro-fold-video.mp4` — 2,602,743 bytes,
sha256 `7A53253A45F7ABD7FD43B2D6F5F26D7C1D72C77CABFE9A850FCEFADC31E934AB`,
1280×720 H.264 8-bit SDR (bt709), 30 fps with **33,222 µs real frame spacing** (deliberately
just under the nominal 33,333 µs — this exact jitter is what exposed the pass-1 subsample bug,
fold-loop BUG-1, so this file is the regression fixture for it). 5.447 s, AAC audio + a data
track. It is **gitignored — never commit it**. If it's missing or the hash differs: STOP and
tell the user; do not substitute another video.

## Preconditions (verify, never assume)

- AVDs `Pixel_6`, `Pixel_8`, `Pixel_10_Pro_Fold`, and **`Pixel_8_API34`** exist
  (`& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -list-avds`).
  If `Pixel_8_API34` is missing, create it:
  `pwsh <skill>\scripts\create-api34-avd.ps1` (requires the
  `system-images;android-34;google_apis_playstore;x86_64` SDK image).
- ffmpeg/ffprobe on PATH (`winget install Gyan.FFmpeg` if not).
- `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`.
- No emulator already running (`adb devices` empty) — sweep-prep refuses otherwise.
- Build ONCE per sweep: `gradlew :app:assembleDebug` — require `BUILD SUCCESSFUL` **and**
  `$LASTEXITCODE -eq 0` **and** zero `e:` lines. If you change code mid-sweep, rebuild and
  re-run every device that already passed — a pass on a stale APK doesn't count.

## The sweep (run for each AVD, sequentially, in this order)

Order: `Pixel_6` → `Pixel_8` → `Pixel_10_Pro_Fold` → **`Pixel_8_API34`**. One emulator at a
time — parallel emulators fight over host CPU/codecs and fake contention bugs.

**Why `Pixel_8_API34` last:** it runs **Android 14 (API 34)** — the OS level where
`FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` (8192) is *not recognized* and v1.0.23 crashed every
Loopify save with `InvalidForegroundServiceTypeException: Starting FGS with type unknown`
(Crashlytics 9663c743, Galaxy A55). The fix gates to `dataSync` on API 29–34. After the three
API-35+ emulators pass, this device is the **mandatory FGS regression gate**: a green save +
`Worker result SUCCESS` for `BoomerangRenderWorker` and **zero** `InvalidForegroundServiceTypeException`
lines in logcat. See `docs/e2e/2026-06-22_082330-api34-fgs-fix.md` for the first verified run.

```powershell
$avd = "Pixel_6"   # then Pixel_8, then Pixel_10_Pro_Fold, then Pixel_8_API34
$dir = "$env:TEMP\openloop_sweep\$avd"

# 1. Cold-boot + install (fresh app state) + push video + start logcat capture + launch.
pwsh <skill>\scripts\sweep-prep.ps1 -Avd $avd -ArtifactDir $dir
#    → prints SERIAL=emulator-NNNN; capture it.

# 2. Drive the full flow. Add -Fold ONLY for Pixel_10_Pro_Fold (posture toggle mid-preview).
pwsh <skill>\scripts\drive-flow.ps1 -Serial $serial -ArtifactDir $dir   # [-Fold]
#    → 9 steps (10 with -Fold), each [PASS]/[FAIL]; exit 2 on any FAIL.

# 3. Pull the saved boomerang and prove its quality programmatically.
pwsh <skill>\scripts\quality-gate.ps1 -Serial $serial -ArtifactDir $dir

# 4. Scan the whole capture for crash/timeout/churn signatures.
pwsh <repo>\.claude\skills\run-e2e\scripts\scan-logcat.ps1 -LogFile $dir\logcat.txt

# 5. Kill before the next device; wait until it's gone from `adb devices`.
adb -s $serial emu kill
```

A device passes when: all drive-flow steps PASS + quality gate PASS + scan shows **0 in every
CRASH and TIMEOUT row**. Anything else is a finding — root-cause it (see "Reading failures").

## OEM regression lanes (API 34 / Samsung / LG)

Not all OEM bugs reproduce on stock emulators. Use the right lane:

| Lane | What it catches | How to run | Emulator? |
|------|-----------------|------------|-----------|
| **API 34 FGS** | `InvalidForegroundServiceTypeException` on Android 14 saves (9663c743) | 4th device in sweep: `Pixel_8_API34` | ✅ Stock AVD |
| **Samsung codec + preview cap** | Exynos/QC wedging, 480p preview cap, `c2.google` encoder order | **Samsung RTL sweep** (real Galaxy) | ❌ Use RTL |
| **Samsung app logic (identity)** | `isSamsungDevice()` branches, preview cap, encoder ordering policy | JVM: `DeviceMediaHintsOemRobolectricTest` + `SamsungReversePreviewRegressionTest` | ✅ Robolectric |
| **LG `start failed` recovery** | Hardware codec rejects `start()` → software fallback (47233ad7) | Instrumented: `VideoReverserTest.reverse_recoversFromCodecStartFailure_viaSoftwareFallback` | ✅ Any device/emulator |

**Do NOT** add fake `Pixel_8_Samsung` AVDs — Google Play images ignore `-prop ro.product.manufacturer`
and block `setprop`; the emulator still reports `Google` / goldfish codecs.

### Samsung RTL sweep (real Galaxy hardware)

Setup: [`docs/guides/samsung-rtl-steps.md`](../../docs/guides/samsung-rtl-steps.md) — reserve device,
start RDB, verify `adb devices` shows `localhost:<port>`.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
& "<repo>\gradlew.bat" :app:assembleDebug
pwsh <skill>\scripts\samsung-rtl-sweep.ps1 -ArtifactDir $env:TEMP\openloop_rtl\s23
# Optional: -Serial localhost:52172  (port changes every RTL session)
```

Pass = drive-flow PASS + quality-gate PASS + scan **0 CRASH/TIMEOUT** + grep shows
`ensureReversed.ok` and `Worker result SUCCESS … BoomerangRenderWorker`.

### LG instrumented lane (headless on emulator)

LG has no public RTL. The recovery path is fault-injected on any attached device:

```powershell
& "<repo>\gradlew.bat" :app:assembleDebug :app:installDebug
adb shell am instrument -w -r `
  -e class io.github.stozo04.openloop.media.VideoReverserTest#reverse_recoversFromCodecStartFailure_viaSoftwareFallback `
  io.github.stozo04.openloop.test/androidx.test.runner.AndroidJUnitRunner
```

Pass = `tests=1 failures=0`. Literal LM-X540 hardware still needs a physical device if available.

## What the scripts verify (and what they hand back to you)

| Phase | Hard checks | Judgment handed to the agent |
|---|---|---|
| drive-flow | import lands on Trim; trim label changes; speed lands exactly 1x; `ensureReversed.ok` (never timeout/fail, 130 s budget); zero "forward only"/"unavailable"/"failed" overlays across all 4 directions; ping-pong duration ≈ 2× single; render worker SUCCESS; share sheet shows `boom_*.mp4`; playback overlay opens with no `Playback error`; (-Fold) editor + `Save boomerang` survive fold→unfold | any FAIL: read `flow-summary.txt` + the logcat before retrying |
| quality-gate | 1 H.264 track; duration within 0.4 s of the editor's prediction; **per-half fps ≥ 27** (the BUG-1 regression tripwire — a subsampled reverse half shows ~15 fps in the first half only); mirror SSIM ≥ 0.85; no 0.5 s freezes; no near-black frames; no green-shifted frames | SSIM 0.85–0.90 prints BORDERLINE → eyeball `frames\f25.png` vs `f75.png` (Read them — they're images), send to the user via SendUserFile if unsure. Healthy runs score 0.93–0.96. **Do not trust SSIM alone: the iter-1 corrupt half still scored 0.88.** |
| scan-logcat | counts per signature | CHURN rows are advisory — single-run baseline ≈ 42–54 `Created component [c2.*]` (Fold slightly higher from the posture rebind). Meaningful growth across runs = leak signal. |

Honesty rules from `run-e2e` apply unchanged: a skipped step is a finding; "confirmed via
logcat" ≠ "saw it"; a TIMEOUT is not a CRASH; **a green emulator sweep says nothing about
physical-device codec behavior** (emulators run host software codecs — never claim a
device-specific Crashlytics issue is fixed from emulator evidence alone).

## Reading failures — signature → meaning

| Signature | Meaning |
|---|---|
| `process died` + `ShortcutService: clearing data for package` within ~15 s of capture start, app relaunches fine | **Harness artifact, not an app bug**: `pm clear`'s data wipe is partly async and its deferred SIGKILL caught the launch. sweep-prep orders + settles to avoid this and drive-flow self-heals; if it still fires, re-run the device before filing anything. |
| `Timed out after 120` / `ensureReversed.timeout` | Reverse wedge. On a COLD-booted emulator this is real — capture `dumpsys media.codec` during the stall and check `OpenLoopReverse` for which pass stalled. On a warm-booted one, cold boot first before blaming code. |
| first-half fps ≈ 15, second ≈ 30 | Pass-1 subsampling regression (BUG-1 family) — check `reverse pass1:` log line for `skipped=` ≠ 0 on this 30 fps source. |
| Moving-region macroblock smear in reversed half, static background clean | Compressed samples dropped before the decoder (broken P-frame references). Pull the cached reversed clip from `cache/scratch/reversed/` to isolate reverser vs Transformer. |
| `MPEG4Writer … encoded 0 frames` + tiny output | Zero-frame completion (S23 family, PR #62 validator territory) — different from a timeout. |
| `InvalidForegroundServiceTypeException` / `Starting FGS with type unknown` on API 34 | **FGS type regression** — the worker is passing `mediaProcessing` (8192) on Android 14. Check `BoomerangRenderNotifications.foregroundServiceTypeForSdk` gates on `VANILLA_ICE_CREAM` (35), not `UPSIDE_DOWN_CAKE` (34). Only fires on `Pixel_8_API34`; the three API-35+ emulators won't catch it. |
| `ChooserPreview: Could not read content://…fileprovider…` ×3 | Cosmetic — the OS share-sheet preview can't query FileProvider metadata. Ignore. |

## Field notes (each of these cost real time once — don't relearn them)

1. **Pull app-private files binary-safe:**
   `cmd /c "adb -s $serial exec-out run-as io.github.stozo04.openloop cat files/videos/<name> > out.mp4"`.
   `run-as … > /sdcard/…` silently writes **0 bytes**; PowerShell pipes mangle binary streams.
2. **Compute gestures from uiautomator bounds, never hardcode coordinates.** The Fold's inner
   display is 2076×2152; everything that hardcoded 1080×2400 broke, everything bounds-derived
   ran unmodified. (drive-flow already does this.)
3. **Label matching: prefer exact when a substring is ambiguous.** Substring "Allow" matches
   the question text "Allow OpenLoop to send you notifications?" before the Allow button.
4. **POST_NOTIFICATIONS dialog appears after the first save** on a fresh (pm-cleared) install
   — the render worker's foreground notification triggers it. drive-flow dismisses it.
5. **Folding the Fold AVD locks the screen.** After `emu unfold`:
   `input keyevent KEYCODE_WAKEUP` → `wm dismiss-keyguard` → `KEYCODE_WAKEUP` again. Plain
   swipes on the keyguard do nothing. The emulator clock can also jump after unlock — don't
   trust logcat wall-time ordering across the toggle.
6. **The trim handles obey a slow drag.** ~1300 ms, 30 % of screen width, landing on the
   handle's bounds-center: first try on every device, every run (5/5). The historic
   "trim handle fights you" bug never reproduced with this technique.
7. **The speed slider is continuous, not snapped.** Tap ≈ 3.9 % of the bar width right of the
   "1x" tick label's center, then VERIFY the value chip next to "Current speed" and retry with
   widening offsets (drive-flow does this; it lands within 1–3 taps).
8. **The gallery grid is newest-first** (`sortedByDescending { it.id }`) — the first
   "Video thumbnail" node is always the clip you just saved.
9. **Don't watch for the "Loop saved / View" snackbar** — it expires before a dump-tap cycle
   completes. Tap the first grid tile instead.
10. **The reverse cache is trim-keyed and survives reinstalls.** After ANY reverser change,
    `pm clear` before re-verifying, or the cache serves the pre-fix artifact and your "fix"
    looks like it did nothing (or worse, looks broken when it isn't).
11. **B&W look ⇒ U=V=128 across every frame of the export.** That's the filter *working* and
    doubles as programmatic proof the Looks step applied; a chroma-flatness check that flags
    it as corruption is wrong. Green corruption is U **and** V well below neutral on many frames.
12. **Wait for terminal events by polling the logcat FILE** (`viewModel.ensureReversed.(ok|timeout|fail)`,
    `Worker result (SUCCESS|FAILURE) … BoomerangRenderWorker`) — not by sleeping fixed amounts,
    and not with tail-armed monitors that miss fast events. Reverse ≈ 3–5 s and render ≈ 6 s
    on these emulators, but budget 130 s / 150 s before declaring a stall.
13. **Renders are deterministic** for identical trim windows — same SSIM to 6 decimal places
    across runs. Useful: a changed SSIM means the pipeline changed, not noise.
14. **Emulator quirks:** all three AVDs report model `sdk_gphone16k_x86_64` (verify identity
    with `adb emu avd name`, not the model prop); adb sometimes holds a stale
    `localhost:NNNNN` transport — always pass `-s $serial`.
