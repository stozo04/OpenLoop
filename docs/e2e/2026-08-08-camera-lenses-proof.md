# E2E proof — Camera Lenses

**Date:** 2026-08-08
**Feature:** `docs/PRD-camera-lenses.md` — Broccoli / Shades / Big Mouth live camera lenses
**Device:** Pixel_8 AVD, Android 16 (API 37 system image), cold boot

## Verification gate (`docs/DEFINITION_OF_DONE.md`)

| Gate | Result |
|---|---|
| `:app:testDebugUnitTest` | **343 tests, 0 failures** |
| `:app:connectedDebugAndroidTest` | **98 tests, 0 failures** (1 skipped, pre-existing) |
| `:app:lintDebug` | **0 errors**, 24 warnings (none new; 11 filtered by baseline) |
| `:app:assembleRelease` | `BUILD SUCCESSFUL` |
| App launched on emulator | ✅ screenshots below |
| Record → Trim with a lens active | ✅ `Capture finalized (6247ms)`, no `ERROR_*` |

## Screenshots

| File | What it shows |
|---|---|
| `2026-08-08-camera-lenses-shades-tracked.png` | **Shades tracking a real face** — centred on the eye line, sized to the head, rotated with it |
| `2026-08-08-camera-lenses-broccoli-character.png` | **Broccoli as a character**: the head is entirely broccoli, with the subject's own eyes and mouth composited onto it. No human nose, cheeks, forehead or jaw visible |
| `2026-08-08-camera-lenses-trim-after-record.png` | A clip recorded **with the lens effect attached**, landing in Trim with a correct filmstrip — proof the effect in the capture path does not disturb the downstream pipeline |

## Getting a face in front of the emulator camera

Solved, and repeatable. Google's own docs (the
[ARCore emulator guide](https://developers.google.com/ar/develop/java/emulator)) put the virtual
scene's two image surfaces in the dining room, **through the doorway behind the starting camera
pose** — which is why nothing showed for so long.

```
adb emu virtualscene-image wall <face.png>     # runtime, no restart; also `table`
adb emu automation record <macro>              # then Shift+W/A/S/D/Q/E, Shift+mouse, in the window
adb emu automation stop-record
adb emu automation play <macro>                # replay before every lens run
```

Navigating is host-window keyboard input, not adb — so it is a one-time manual step, after which
`automation play` makes it automatic. The recorded macro for this feature is a walk from the start
pose through the doorway to the poster.

## Measured camera geometry (the reason three bugs were found)

One line of per-bind logging on each side:

```
OpenLoopFaceTracker: Analysis buffer=1280x720 rotation=90 upright=720x1280
OpenLoopLens:        Lens output targets=3 size=1280x960 inputDet=-1.0 outputDet=-1.0 mirrored=false
```

* `targets=3` = `PREVIEW or VIDEO_CAPTURE` — one shared output for both, as designed.
* Analysis upright is **portrait**, lens output is **landscape** → a quarter turn, handled by
  `LensAnchor.uprightToBuffer`.
* 16:9 vs 4:3 → different sensor coverage, handled by `LensAnchor.reframe`.
* `inputDet == outputDet` → no extra flip on this output, so mirroring correctly resolves to false
  rather than being assumed from lens facing.

## What could NOT be verified here

* **Steadiness.** The emulator plays a recorded video on a flat poster, so it cannot show how the
  lens behaves under real motion blur, fast turns, or changing light. No smoothing was added — see
  PRD §11.1 item 0.
* **The front camera and its mirroring.** Everything here is the back camera against a poster.
  Mirroring should be a non-issue by construction (the face frame is built from the mouth→eyes
  axis, never from the detector's left/right labels) and is covered by
  `LensAnchorTest.sticker_onAMirroredFace_isTheMirroredPlacement`, but it has not been seen live.
* **Real device performance.** Frame rate here is an emulator's.

The rest of `docs/PRD-camera-lenses.md` §11.1 still deserves one pass on hardware.

## ⚠️ Open before the PR

The broccoli art is a photograph of unknown licence — see PRD §11.2. A public-domain fallback is
prepared and drops straight in.
