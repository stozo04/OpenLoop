# E2E proof — Camera Lenses

**Feature:** `docs/PRD-camera-lenses.md` — Broccoli / Shades / Big Mouth live camera lenses

| Run | Date | Device |
|---|---|---|
| Original build | 2026-08-08 | Pixel_8 AVD, Android 16 (API 37 system image), cold boot |
| **Re-verification after the PR #118 review cuts** | **2026-08-09** | **Pixel 10 Pro Fold (`58271FDCG000XC`), Android 16 — real hardware** + Pixel_8 AVD |

> The numbers and the geometry block below are from the **2026-08-09** run. The 2026-08-08 figures
> were superseded: that run's geometry log predated the `RATIO_4_3_FALLBACK_AUTO_STRATEGY` pin on
> `ImageAnalysis` and recorded a `mirrored=` field the shipped code no longer emits — see
> [Correction](#correction-the-2026-08-08-geometry-block-was-stale).

## Verification gate (`docs/DEFINITION_OF_DONE.md`)

| Gate | Result |
|---|---|
| `:app:testDebugUnitTest` | **354 tests, 0 failures** |
| `:app:connectedDebugAndroidTest` | **98 tests, 0 failures** (1 skipped, pre-existing) — run on the **physical Fold** |
| `:app:lintDebug` | **0 errors**, 24 warnings (none new; 11 filtered by baseline) |
| `:app:assembleRelease` + `:app:bundleRelease` | `BUILD SUCCESSFUL`, exit 0 |
| App launched on real hardware | ✅ Fold screenshot below |
| All three lenses tracking a face | ✅ emulator screenshots below |
| Record with a lens → Trim | ✅ `Capture finalized (4127ms)`, no `ERROR_*` |

## Screenshots

| File | What it shows |
|---|---|
| `2026-08-09-camera-lenses-fold-hardware.webp` | **Real hardware** (Pixel 10 Pro Fold): camera bound, lens carousel open, Broccoli selected. No face in front of the device, so no lens art — this is the hardware-run proof, not a tracking proof |
| `2026-08-09-camera-lenses-broccoli-character.webp` | **Broccoli as a character**: the head is entirely broccoli, with the subject's own eyes and mouth composited onto it. No human nose, cheeks, forehead or jaw visible |
| `2026-08-09-camera-lenses-shades-tracked.webp` | **Shades tracking a face** — centred on the eye line, sized to the head, rotated with it |
| `2026-08-09-camera-lenses-big-mouth-warp.webp` | **Big Mouth**: the radial bulge on the mouth, visibly widening the lower face against the un-warped Shades frame |
| `2026-08-09-camera-lenses-trim-after-record.webp` | A clip recorded **with the lens effect attached**, landing in Trim with a correct filmstrip and the warp baked into the frames — proof the effect in the capture path reaches `VideoCapture`, not just preview, and does not disturb the downstream pipeline |

Stored as WebP: they are photographic screenshots, and the five together are ~240 KB where the three
PNGs they replace were 4.4 MB.

## Getting a face in front of the emulator camera

Solved, and repeatable **with no manual step at all** — the emulator ships the macro this needs.
Google's own docs (the [ARCore emulator guide](https://developers.google.com/ar/develop/java/emulator))
put the virtual scene's two image surfaces in the dining room, **through the doorway behind the
starting camera pose** — which is why nothing showed for so long.

```bash
# 1. Any face image becomes the wall poster. Runtime, no restart, returns OK.
adb -s emulator-5556 emu virtualscene-image wall <face.png>

# 2. Walk the scene camera to it. Use the emulator's OWN stock macro — a bare name is
#    "KO: Could not open file"; it wants the full path.
adb -s emulator-5556 emu automation play \
  "$LOCALAPPDATA/Android/Sdk/emulator/resources/macros/Walk_to_image_room"
```

`Walk_to_image_room` is shipped in `<sdk>/emulator/resources/macros/`, so **recording a macro by hand
is no longer necessary** (the 2026-08-08 run did that and it is not needed).

The face used here is the repo's own instrumented-test fixture,
`app/src/androidTest/assets/face_fixture.jpg` (public domain), upscaled to 1024×1024 — no personal
image, and reproducible from a clean checkout.

## Measured camera geometry

One line of per-bind logging on each side. **Both rigs, both streams, 4:3:**

```
# Pixel 10 Pro Fold — real hardware, back camera
OpenLoopFaceTracker: Analysis buffer=640x480 rotation=90 upright=480x640
OpenLoopLens:        Lens output targets=3 size=1600x1200 inputDet=-1.0 outputDet=-1.0

# Pixel_8 AVD
OpenLoopFaceTracker: Analysis buffer=640x480 rotation=90 upright=480x640
OpenLoopLens:        Lens output targets=3 size=1280x960 inputDet=-1.0 outputDet=-1.0
```

* `targets=3` = `PREVIEW or VIDEO_CAPTURE` — one shared output for both, as designed.
* Analysis upright is **portrait**, lens output is **landscape** → a quarter turn, undone once at the
  tracker boundary by `LensAnchor.uprightToBuffer`.
* **Analysis is 640×480 = 4:3 and the lens output is 4:3 on both devices — the
  `RATIO_4_3_FALLBACK_AUTO_STRATEGY` pin held.** Two streams of the same shape cover the same sensor
  rectangle, so `LensAnchor.reframe` hits its `targetAspect == sourceAspect` early return and does
  nothing. It is retained as the residual-case guard, **not** dead code: the CameraX strategy is a
  *fallback* one, so a device that cannot serve 4:3 analysis will hand back another shape and
  `reframe` is what absorbs it. See PRD §7 and lesson 032.
* `inputDet == outputDet` on the back camera → no extra flip on this output. The front camera on the
  Fold measured `inputDet=1.0 outputDet=-1.0`, i.e. the output transform does flip handedness — which
  costs nothing, because the face frame is built from the mouth→eyes axis and follows a mirrored
  image without a mirror flag.

### Correction: the 2026-08-08 geometry block was stale

The original run recorded:

```
OpenLoopFaceTracker: Analysis buffer=1280x720 rotation=90 upright=720x1280   ← 16:9
OpenLoopLens:        Lens output ... size=1280x960 ... mirrored=false
```

and concluded "16:9 vs 4:3 → different sensor coverage, handled by `LensAnchor.reframe`". Both halves
of that are obsolete. The `mirrored=` field does not exist in the shipped `logOutputGeometryOnce`,
which dates the excerpt to a build before mirroring was removed — and before the 4:3 pin landed. The
PR #118 review caught the contradiction between this block and the PR description; the description
was right and this document was the stale artifact. Re-measured above on two devices.

**Keep the once-per-bind geometry logs.** They are what turned this from an argument into a
measurement, and they are the instrument for the next "the lens is sideways on a Samsung" report.

## What could NOT be verified here

* **Steadiness.** The emulator plays a still poster and the hardware run had no face, so neither
  shows how the lens behaves under real motion blur, fast turns, or changing light. No smoothing was
  added — see PRD §11.1 item 0.
* **A live face on hardware.** The Fold run proves the camera binds, the effect attaches, the
  carousel works and the art decodes on a real device; it does not show a lens tracking a real face,
  because there was nobody in front of the camera. Face tracking is proven on the emulator only.
* **The front camera against a real face.** Its geometry was measured on the Fold (above), and
  mirroring is covered by `LensAnchorTest.sticker_onAMirroredFace_isTheMirroredPlacement`, but it has
  not been seen live.
* **Real device performance.** Frame rate on the emulator is an emulator's.

The rest of `docs/PRD-camera-lenses.md` §11.1 still deserves one pass with a real face on hardware.
