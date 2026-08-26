# E2E proof — Hand-driven lens flick

**Feature:** `docs/PRD-lens-hand-flick.md` — wave a hand past the Football / Broccoli / Pizza Face
and it spins (MediaPipe Hand Landmarker replaces the touchscreen fling).

| Run                          | Date       | Device                                                                         |
| ---------------------------- | ---------- | ------------------------------------------------------------------------------ |
| Touch pipeline, pre-swap     | 2026-08-26 | Pixel_8 AVD, API 37 — `adb shell input swipe` over the ball                    |
| Hand tracker + coordinates   | 2026-08-26 | Pixel_8 AVD, API 37 — poster face + the owner's hand on the virtual-scene wall |
| **The gesture on hardware**  | 2026-08-26 | **Pixel 10 Pro Fold (`58271FDCG000XC`) — owner: "It totally freaking works"**  |

## Verification gate (`docs/DEFINITION_OF_DONE.md`)

Numbers from `scripts/pre-pr-sweep.ps1 -SkipInspectCode` run on the code commit (`a364170`); the
green receipt (`build/sweep-receipt.json`) is written by the same sweep re-run on the final commit
of the branch, docs included.

| Gate                                                 | Result                                                                                                                                                                                                                                                                                       |
| ---------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Clean `assembleDebug` + `assembleRelease`            | `BUILD SUCCESSFUL`, exit 0, 0 `e:`, 0 `w:` — clean, both variants                                                                                                                                                                                                                            |
| 16 KB `zipalign -c -P 16` on the release APK         | `Verification successful` — 24 `.so` entries `(OK)`, including `libmediapipe_tasks_jni.so` on all four ABIs                                                                                                                                                                                  |
| Android Lint (Engine 1)                              | 0 errors, 0 warnings (7 advisory version-freshness findings — dependency-age notices, never gating per `docs/STATIC_ANALYSIS.md`)                                                                                                                                                            |
| `:app:testDebugUnitTest`                             | **624 tests, 0 failures, 0 errors** (51 suites; the sweep of the first commit caught the Lesson 029 WorkManager cancel race — fixed in the scheduler, +1 mapping test)                                                                                                                       |
| `:app:connectedDebugAndroidTest` (Pixel_8 AVD, 4 GB) | **120 tests, 0 failures**, 1 skipped (the pre-existing Samsung-only assumption). On the stock 2 GB AVD the run aborted twice with `INSTRUMENTATION_ABORTED: System has crashed` during the camera-bound booth tests — the emulator's `system_server`, not the app (Lesson 012 hand-off note) |
| Markdown / tables / links / cspell / JSON            | all zero (markdownlint, table alignment, link check, cspell, JSON, IDE dictionary in sync)                                                                                                                                                                                                   |
| Inspect Code (Engine 2)                              | SKIPPED — headless run; the IDE export is vacuous on this machine                                                                                                                                                                                                                            |
| App launched, lens on a face, hand detected          | ✅ screenshots below                                                                                                                                                                                                                                                                          |
| **Release (R8) APK** on the emulator                 | ✅ `Hand tracking on` → `Hand detected`, same landmarks as debug — after the Flogger keep rules; the first R8 build crashed in `Graph.<clinit>` (PRD §1.2)                                                                                                                                    |
| The spin itself                                      | ✅ owner on the Fold; emulator touch run before the swap (5/5 `Flick HIT`)                                                                                                                                                                                                                    |

## Screenshots

| File                                                | What it shows                                                                                                                                                                                                                                          |
| --------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `2026-08-26-hand-flick-poster-hand-football.webp`   | Pixel_8 AVD: the Mona Lisa fixture wearing Football, with a hand (cropped from the owner's Fold recording) on the wall poster above the head — the hand tracker's test subject                                                                         |
| `2026-08-26-hand-flick-landmark-overlay.webp`       | The 21 landmarks from `OpenLoopHand: Hand detected …` drawn onto the preview under the "landmarks are in the un-rotated buffer" contract: every point lands on the hand (red = wrist, blue = MediaPipe's index tip). The coordinate contract, measured |
| `2026-08-26-hand-flick-spin-inverted.webp`          | The spin pipeline the hand feeds (emulator, touch run before the swap): ~0.25 s after the impulse the ball is inverted and the composited eyes/mouth are hidden (D2)                                                                                   |
| `2026-08-26-hand-flick-spin-landed.webp`            | +1.75 s: landed on a whole revolution, features snapped back                                                                                                                                                                                           |

WebP at 540 px wide, ~57 KB each.

## Putting a hand in front of the emulator camera

The virtual scene's wall poster takes any image at runtime (`adb emu virtualscene-image wall`), so a
hand can be composited next to the face fixture and swapped in without re-walking the macro:

```bash
ffmpeg -ss 2.5 -i owner-clip.mp4 -frames:v 1 -vf "crop=330:500:0:110" hand.png
ffmpeg -i face1024.png -i hand.png -filter_complex "[1]scale=-1:380[h];[0][h]overlay=700:0" poster.png
adb -s emulator-5584 emu virtualscene-image wall poster.png
```

With Football active the log shows `Hand tracking on` and, 90–140 ms later,
`Hand detected in 640x480 wrist=(…) indexTip=(…) landmarks=…`. The overlay above is those 21 pairs
mapped with `uprightX = 1 − y`, `uprightY = x` (display rotation 90) and the `FILL_CENTER` scale
(960×1280 → 1800×2400, 360 px cropped per side).

## Measured geometry

```text
# Pixel_8 AVD, back camera
OpenLoopFaceTracker: Analysis buffer=640x480 rotation=90 upright=480x640
OpenLoopLens:        Lens output targets=3 size=1280x960 inputDet=-1.0 outputDet=-1.0
OpenLoopHand:        Hand detected in 640x480 …          ← same 640x480 buffer as the analysis stream
```

Hands and faces are measured on the same stream, so a hand landmark and a face landmark mean the
same thing in the renderer with no mapping between them — the reason the touch path's whole
view→buffer transform could be deleted.

## What could NOT be verified here

* **The gesture on the emulator.** A poster hand is static; velocity needs motion. The verb is
  covered by `HandFlickTest` (18 JVM cases) and by the owner on the Fold — not by an emulator run.
* **Broccoli and Pizza Face on hardware.** The owner's Fold test was Football, before the two
  characters were opted in. They share `SPIN_ON_A_HEAD` and the identical pipeline (pinned by
  `LensPhysicsTest`), so they spin — but their *feel* (a 4.4-unit wreath and a wedge spinning
  about their own centres) has not been seen on a phone yet.
* **Inference cost on mid-range phones** (PRD R1). The Fold and the AVD are not that population;
  measure on the Samsung RTL lane when it is next exercised.
* **Two faces, one hand** (D6). The virtual scene has one poster surface.
