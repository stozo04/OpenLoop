# PRD — Photo Booth Strip

**Branch:** `feature/photo-booth` (off `main`)
**Owner:** Steven Gates · **Status:** Signed off · **Date:** 2026-08-20
**Tracking:** [Issue #135](https://github.com/stozo04/OpenLoop/issues/135)
**Review:** Kayley AR sign-off [comment](https://github.com/stozo04/OpenLoop/issues/135#issuecomment-5357467668) (crop origin + color default)

---

## 1. Problem statement

Photo mode captures one still and stops there. There is no way to create the classic photo-booth
artifact: a vertical strip of sequential shots where the subject changes pose (or, in OpenLoop's
case, changes *lens*) between frames. The strip and the countdown ritual are the feature — the
black-and-white grade is a classic-booth costume, not the hook, and already exists elsewhere
(Looks → NOIR, video-only).

## 2. What we're building

A **Booth** button on the camera screen. Tapping it runs a self-driving capture sequence:

```text
5-4-3-2-1 → flash + snap  (×3, auto-advancing)
   └─ each countdown is the window to change pose or swap lenses
→ composite the 3 shots into one vertical strip
   (square frames, white borders, "OpenLoop + date" footer, color by default)
→ save through the EXISTING photo path
   (in-app gallery + MediaStore Pictures/OpenLoop + share sheet)
```

Proof-of-concept scope: **get the flow working; the button's placement and styling are
explicitly provisional** (owner, 2026-08-20 — "add a new button on the page; we'll figure it out
later"). The **strip itself is not provisional.** Borders, footer, and crop origin are the product
surface (same bar as Elvis art) and ship in this pass.

## 3. Confirmed decisions (owner, 2026-08-20; crop + color locked same day)

| # | Question | Decision |
|---|----------|----------|
| D1 | Frame count | **3** (proof of concept; classic 4-frame strip can come later) |
| D2 | Where the feature lives | ~~A provisional Booth button~~ **Decided (owner, 2026-08-20, design canvas "Photo Booth Controls" Option E):** booth lives in the **lens drawer**. The bottom-left button opens a two-tab drawer — a `Photo Booth \| Lenses` slider (Lenses default) above the tab's content; the Photo Booth tab swaps the carousel for a `Color \| Black & White` radio pair (Color default, D4) and **arms** the booth: the **shutter** starts the strip while armed. Armed state survives closing the drawer (the lens button goes lime, as with an active lens); the booth tab's ✕ is also the sequence's **Cancel** — it aborts a running countdown, disarms, and closes (the lens tab's ✕ contract; predictive back is the buttonless abort; there is no separate mid-sequence Cancel/B&W row — the drawer is the control surface); picking Camera/Video in the top-right selector disarms. Mid-sequence, flipping the slider to Lenses (D5's swap window) disarms *after* the running sequence — it keys on its own active flag — so a second strip needs re-arming. Booth still does **not** become a `CaptureMode` entry — this is UI presentation only; §5.1 stands |
| D3 | Frame crop | **Square, top-biased** (not geometric center). Strip ≈ 1100×3500 px. Full-height 9:19.5 stacks would be ≈ 1080×7000 — a sliver in share previews. A center square on a tall selfie clips the crown and kills lenses that sit above the eyes (Elvis pompadour at anatomy +1.25, broccoli, etc.). A mall booth is already square; our preview is tall. Crop the long axis with origin toward the **top** of the frame (or the face box if landmarks are available). Change the origin, not the square. |
| D4 | Color | **Color by default, toggleable to B&W** before the composite. The hook is Broccoli in frame 1 and Elvis in frame 3; grayscale mutes photoreal lenses. B&W is the classic-booth toggle, not the default. |
| D5 | Countdown | **5-4-3-2-1 per frame**, auto-advancing (~18 s per strip) — roomy enough to open the lens tray and swap lenses between frames. Do not shrink until a swap is missed. |
| D6 | Footer | **"OpenLoop" wordmark + capture date** printed in the bottom border, like a real booth strip. This is in-scope look, not a later UI pass. Use a clean bold sans that reads as a printed strip at share-preview size — not a throwaway default typeface. |

### The capture path is settled prior art

Photo capture ([PRD-photo-capture.md](./PRD-photo-capture.md) D1, shipped) grabs the **composited
viewfinder** via `PreviewView.getBitmap()` — no `ImageCapture` use case, no rebind. Booth is three
of those grabs on a timer:

- **Zero camera-binding changes.** No new use case, no mode-driven rebind, so
  [Lesson 031](./lessons_learned/031-camera-effect-attach-once-switch-by-uniform.md) never comes
  into play and `CameraManager` is untouched (again).
- **Lenses bake into each frame independently.** Because each grab is of the composited preview,
  swapping the lens during a countdown puts a *different* lens in the next frame — Broccoli in
  frame 1, Elvis in frame 3. This is the product hook and it costs zero extra code.
- **The countdown overlay can never contaminate a shot.** `getBitmap()` returns the camera preview
  content only; Compose overlays (countdown digits, flash, chips, lens tray) are composited above
  the `PreviewView` and are not part of the grab. Grab **after** the `1`, not during the digit, so
  the ritual stays on the overlay and the JPEG stays clean.

## 4. Scope

### In scope

- Booth button (provisional placement) + Color/B&W chip on the camera screen (color is the default)
- 5-second countdown overlay with per-shot flash flicker, auto-advancing across 3 shots
- Lens tray remains usable during the countdown (the swap window is the point)
- Square **top-biased** crop at grab time, strip composite (white borders + footer + optional B&W)
- Save via the existing `savePhoto` path: JPEG → `filesDir/videos/photo_<ts>.jpg` → gallery
  refresh → MediaStore publish → share sheet
- Cancel (button or predictive back) aborts the sequence and discards captured frames — up to
  the last grab, after which the strip is committed (§5.4)

### Out of scope (deliberately)

- ~~Final UI/placement polish for the **button and chip** (owner: later pass).~~ Shipped 2026-08-20 — see the amended D2 (drawer + armed shutter). Not the strip.
- 4-frame strips, frame-count settings, retakes of individual frames
- An animated "strip of loops" (3 boomerangs tiled via Media3 `VideoCompositorSettings` — real,
  docs-confirmed, but a v2 with full media-pipeline exposure; tracked separately if wanted)
- Booth-specific Looks/filters beyond the Color/B&W toggle
- Persisting the Color/B&W choice across launches

## 5. Design

### 5.1 State — booth bypasses `CaptureMode` entirely

Booth is not a third `CaptureMode`: it doesn't change what the shutter does — it *replaces* the
shutter with an auto-running sequence. So the Video↔Photo toggle stays binary, the exhaustive
router `when` is untouched ([Lesson 014](./lessons_learned/014-state-router-when-exhaustive-no-else.md)),
and no `OpenLoopUiState` entry is added.

The sequence is **UI-driven, mirroring the shipped photo path** (where `CameraScreen` already
reads `previewView.bitmap` on the main thread and hands it to the ViewModel):

- A `LaunchedEffect` in `CameraScreen` runs the countdown loop (`delay()` ticks), fires the flash,
  grabs **after** `1`, and top-bias-crops each grab to a square immediately (retaining 3× ~1080²
  ARGB ≈ 14 MB instead of 3× full-screen ≈ 30 MB).
- Countdown digits / progress ("2 of 3") are local Compose state — ephemeral UI.
- On completion the UI calls `viewModel.captureBoothStrip(frames, monochrome)`; the ViewModel
  composites off the main thread and saves. Bitmaps are plain data objects, so this stays clean of
  [Lesson 004](./lessons_learned/004-viewmodel-no-context-parameters.md) (no `Context` in the VM).
- Re-entrancy guarded by a `boothSaveInProgress` flag, mirroring `photoSaveInProgress`.

During the sequence: shutter, mode toggle, and camera-flip are disabled; the **lens tray stays
interactive** (D5's swap window). Predictive back aborts the sequence (until the last grab —
§5.4) — the booth-active flag
joins the existing `BackHandler` gate
([Lesson 015](./lessons_learned/015-predictive-back-state-routed-backhandler.md)).

**Accessibility:** a timed, visual-only countdown excludes non-sighted users. The countdown
overlay announces its ticks and shot progress via a Compose live region
(`liveRegion = LiveRegionMode.Polite` on the digits, "Shot 2 of 3" on advance), and the Booth
button, Cancel, and Color/B&W chip all carry content descriptions. This is in the
never-simplify-away bucket, not polish.

**Accepted POC limitation:** an activity recreation mid-sequence (rotation, process death) resets
booth to idle and discards captured frames — ~18 s of loss, not precious work. Backgrounding
mid-sequence (home, lock, an incoming call) aborts the same way, deliberately: after
started-then-stopped the COMPATIBLE-mode `TextureView` keeps serving its frozen last frame, so
`getBitmap()` grabs can no longer be trusted
([Lesson 036](./lessons_learned/036-previewview-getbitmap-stale-after-stop.md)) — the sequence
checks the lifecycle before each grab and silently discards, exactly like Cancel.

### 5.2 Composite — pure layout math + a thin Canvas pass

Following the repo's extract-the-math pattern (`TrimHandleMath`, `ZoomUi`):

- **`BoothStripLayout`** (pure, JVM-tested): given source dimensions, frame count, border and
  footer proportions → the **top-biased** square crop `Rect` per source (not geometric center),
  per-frame destination `Rect`s, and the strip's overall dimensions. Handles both orientations of
  source (crop the long axis). Tests must fail a centered crop on a tall 9:19.5 source — that
  origin clips the crown.
- **Composer** (thin, not clever): white-filled `Bitmap` → `Canvas.drawBitmap` ×3 using the layout
  rects → footer text via `drawText` ("OPENLOOP · AUG 20 2026" style). B&W (when toggled) = a
  `Paint` with `ColorMatrixColorFilter(ColorMatrix().setSaturation(0))` on the frame draws only —
  the borders and footer stay crisp white/black either way.

Rough numbers at a 1080-wide grab: 1080² frames, ~32 px borders, ~160 px footer → strip
≈ 1144×3528. Exact values live in `BoothStripLayout` and its tests, not here.

### 5.3 Save path — reused wholesale

`videoStorage.savePhoto(stripBitmap)` already does everything downstream: JPEG 90 % into
`filesDir/videos/` (inside the `file_paths.xml` share boundary), `VideoKind.PHOTO`, gallery
refresh, best-effort MediaStore publish to `Pictures/OpenLoop`, and the
`BoomerangEvent.Share` → share sheet → "Saved" snackbar flow. `shareMimeType` already returns
`image/jpeg` for `.jpg`. The gallery's full-screen `Image` overlay renders a tall strip correctly
(`ContentScale` fit). **Zero storage/share/gallery code changes expected.**

### 5.4 Failure modes

| Failure | Handling |
|---|---|
| `previewView.bitmap` returns null on any snap | Abort the whole sequence → existing capture-failed snackbar; no partial strip is ever saved |
| Cancel / back before the last grab | Discard frames, return to idle camera; no save, no snackbar |
| App backgrounded (ON_STOP) before the last grab | Silent abort, same as Cancel — a stopped preview's `getBitmap()` serves the frozen last frame, so grabs can't be trusted ([Lesson 036](./lessons_learned/036-previewview-getbitmap-stale-after-stop.md)) |
| Strip completes while a previous save is still in flight | Rejected with the capture-failed snackbar; frames recycled eagerly |
| Cancel / back / gallery / ON_STOP during the **final** flash | Nothing — the strip is already committed: hand-over happens at the third grab, *before* the cosmetic flash, so no input in that 250 ms can discard ~18 s of posing (this is also D4's "B&W applies until the last grab" boundary) |
| Strip composite runs out of memory | Caught (`OutOfMemoryError` on the ~16 MB allocation) → capture-failed snackbar, frames recycled; never a process kill |
| MediaStore publish fails | Logged and swallowed (photo-path precedent: the in-app save is the one that matters) |

## 6. Success criteria

1. Booth button starts a 5-4-3-2-1 ×3 auto-advancing sequence with a visible flash per shot.
2. Countdown digits, flash, and tray never appear in the captured frames (grab after `1`).
3. Output is one JPEG strip: 3 square frames, white borders, OpenLoop + date footer; **color by
   default**, B&W when toggled.
4. Each square keeps the face and lens hair (crown / pompadour) in frame — a geometric center-crop
   of a tall selfie fails this.
5. The strip appears in the in-app gallery **and** the device Photos app, and the share sheet
   opens on save — all via the unmodified photo path.
6. Swapping lenses during a countdown bakes different lenses into different frames, in color.
7. Cancel/back aborts cleanly (before the last grab — after it the strip is committed, §5.4); a
   null grab aborts with the friendly snackbar; no partial strips.
8. Video recording and single-photo capture are behaviourally unchanged.

## 7. Test plan

| Layer | Coverage |
|---|----------|
| JVM unit | `BoothStripLayout`: strip dimensions, frame rects, **top-biased square crop for tall 9:19.5 and wide sources** (assert the crop origin is not vertical-center on a tall source), footer band; `captureBoothStrip` saves + emits `Share`, guards re-entrancy, rejects incomplete frame sets ([Lesson 008](./lessons_learned/008-jvm-test-file-and-dispatcher-pitfalls.md) — real temp dirs, shared dispatcher) |
| Instrumented | Camera screen renders the Booth button with a11y label; countdown overlay appears on tap |
| Manual (emulator) | Full sequence → strip in gallery → Photos app → share sheet. On a device with a face: Elvis / broccoli hair still visible in each square. Emulator-verifiable like photo mode (no face needed unless demonstrating the lens swap), so the DoD screenshot is achievable |

## 8. Risks

| Risk | Mitigation |
|---|---|
| Geometric center-crop clips lens hair on a tall selfie | D3: top-biased (or face-box) origin; layout tests reject a centered crop on 9:19.5 |
| B&W default mutes photoreal lenses | D4: color default; B&W is the toggle |
| Transient memory spike (3 frames + strip ≈ 28 MB) | Crop-at-grab halves retained frame memory; composite off-main; bitmaps released after save |
| ~18 s sequence feels long | D5 was an explicit owner choice for lens-swap room; frame time is one constant if it needs tuning |
| Provisional button clutters the camera screen | Accepted — owner has an explicit **button** UI cleanup pass planned. Strip look ships now. |
| Config change mid-sequence loses frames | Accepted POC limitation (§5.1) |

## 9. Open questions

None blocking. Button placement, provisional in the first pass, was settled 2026-08-20 — see the
amended D2 (lens-drawer tabs, shutter-as-trigger while armed). Crop origin and color default are
locked.
