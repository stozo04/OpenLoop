# PRD — Photo Capture Mode

**Branch:** `feature/photo-capture` (off `feature/camera-lenses` @ `6dd2419`)
**Owner:** Steven Gates · **Status:** Awaiting sign-off · **Date:** 2026-08-09

---

## 1. Problem statement

OpenLoop opens to a viewfinder that can only record video. Every capture is funnelled through the
full boomerang pipeline (Trim → Editor → Loopify render), which is right for loops and wrong for the
moment a user just wants a still. There is no way to take a photo at all.

## 2. What we're building

A **mode toggle** in the top-right of the camera screen that flips the viewfinder between
**Video mode** (today's behaviour, unchanged) and **Photo mode**.

In Photo mode:

1. The top-right button swaps to a video icon so the user can flip back.
2. The shutter becomes a capture button (solid white fill, no progress ring).
3. Tapping it **skips the entire boomerang pipeline** — no trim, no editor, no filters, no render.
   The photo is saved to the in-app library *and* the device's public Photos library, then the
   Android share sheet opens immediately (the existing slice-06 flow).

## 3. Confirmed decisions (owner, 2026-08-09)

| # | Question | Decision |
|---|----------|----------|
| D1 | Capture path | **Preview snapshot** (`PreviewView.getBitmap()`), not an `ImageCapture` use case |
| D2 | "Send notification" | The **existing share sheet** (`BoomerangEvent.Share`), not a system push notification |
| D3 | Camera flip in photo mode | **Required** — front/back toggle must work exactly as it does in video mode |

### Why D1 — and what it costs

Verified on developer.android.com this session (not from training data):

- **`Preview + VideoCapture + ImageAnalysis + ImageCapture` is not a guaranteed bind.** CameraX
  "might still fail the binding when the required combination is not supported on the requested
  camera" ([video-capture](https://developer.android.com/media/camera/camerax/video-capture)). The
  app already binds the first three — `ImageAnalysis` is what feeds lens face-tracking — so a real
  `ImageCapture` use case would push us onto a 4-way combination Google does not guarantee.
- Avoiding that means swapping `ImageAnalysis` out for `ImageCapture` on every mode toggle. That is
  a `unbindAll()` + rebind driven by a UI toggle — the exact red flag
  [Lesson 031](./lessons_learned/031-camera-effect-attach-once-switch-by-uniform.md) names — and it
  would make **lenses inert in photo mode** (no face tracking without `ImageAnalysis`).
- **`PreviewView.getBitmap()`** returns an `ARGB_8888` bitmap whose "dimensions are the same as this
  view's", or `null` before the preview is streaming
  ([PreviewView source](https://github.com/androidx/androidx/blob/androidx-main/camera/camera-view/src/main/java/androidx/camera/view/PreviewView.java)).

**Accepted trade-off:** photos are a WYSIWYG grab of the composited viewfinder at view resolution
(≈1080×2340 on a modern phone), cropped exactly as `FILL_CENTER` displays it — not a full-sensor
image. In exchange: **zero change to the camera binding**, no rebind, no bind-failure risk, and
lenses / pinch-zoom / camera-flip all work in photo mode for free because we are capturing the
already-composited preview (the lens effect targets `PREVIEW`).

If full-sensor stills are wanted later, that is a separate PRD — it means dropping face-tracking in
photo mode or waiting on a guaranteed 4-way combination.

## 4. Scope

### In scope
- `CaptureMode` toggle (Video ↔ Photo) on the camera screen
- Photo capture → JPEG → app library + MediaStore → share sheet
- Photos in the gallery grid, with a still-image preview overlay and a working Send
- Lenses, pinch-zoom and camera-flip functional in photo mode

### Out of scope (deliberately — not requested)
- Flash, timer, grid lines, aspect-ratio picker, HDR
- A photo review / edit screen before saving
- Photo-specific filters or the Looks tab
- Burst / live photos, or persisting the chosen mode across app launches

## 5. Design

### 5.1 State — a sibling flow, not a new UI state

```kotlin
enum class CaptureMode { VIDEO, PHOTO }
```

Held as `OpenLoopViewModel.captureMode: StateFlow<CaptureMode>`, exactly like `lensTrayOpen` and
`activeLens`. It is **not** an `OpenLoopUiState` entry, so the exhaustive router `when` in
`OpenLoopNavHost` is untouched ([Lesson 014](./lessons_learned/014-state-router-when-exhaustive-no-else.md)).
Not persisted — the app always opens in Video mode.

### 5.2 Camera screen

| Element | Video mode | Photo mode |
|---|---|---|
| Top-right button | `Icons.Outlined.PhotoCamera`, "Switch to photo mode" | `Icons.Outlined.Videocam`, "Switch to video mode" |
| Shutter interior | neon `shutterGradient()` | solid white |
| Shutter a11y label | "Start recording" / "Stop recording" | "Take photo" |
| Progress ring | on while recording | never |
| Flip camera (D3) | present | **present — unchanged** |
| Lens button + tray | present | present (lenses bake into the photo) |

The toggle is **hidden while `Recording`** — you cannot change mode mid-capture. `BackHandler`
gating (`isRecording || lensTrayOpen`) needs no change: photo mode holds no unsaved work.

Icons come from `material-icons-extended`, already on the classpath. No new vector assets.

### 5.3 Capture path

```
ShutterButton.onClick (PHOTO)
  └─ viewModel.capturePhoto(previewView.bitmap)      // main-thread view read
       ├─ null bitmap  → CaptureFailed snackbar, no state change
       └─ videoStorage.savePhoto(bitmap)             // IO: JPEG 90% → filesDir/videos/photo_<ts>.jpg
            ├─ publishPhotoToLibrary(file)           // IO: MediaStore.Images (best-effort)
            ├─ loadRecordedVideos()                  // gallery refresh
            └─ _events.send(BoomerangEvent.Share(file))
                 └─ MainActivity.deliverShareSheet() → swipe down → onShareSheetClosed() → "Saved"
```

`CameraManager` is **not** modified. The `PreviewView` already lives in `CameraScreen`; the bitmap is
read there and handed to the ViewModel. A `Bitmap` is a plain data object, so this does not violate
"no Context in the ViewModel" ([Lesson 004](./lessons_learned/004-viewmodel-no-context-parameters.md)).

Re-entrancy is guarded by a `photoSaveInProgress` flag, mirroring `saveInProgress` on the loop path.

### 5.4 Storage — photos live in `filesDir/videos/`

Named `photo_<timestamp>.jpg`, alongside `clip_*.mp4` and `boom_*.mp4`. This looks odd but is
deliberate and saves two edits that are easy to get wrong:

- `file_paths.xml` exposes exactly `<files-path name="videos" path="videos/"/>` — a new directory
  would need a new entry or the share sheet gets a `SecurityException`.
- `MainActivity.launchShareSheet` **silently refuses** any file whose path does not contain
  `/videos/`. A photo outside that directory would fail to share with only a log line.

`VideoKind` gains a `PHOTO` entry. It is only ever compared with `==` (never in an exhaustive
`when`), so nothing else breaks — verified by grep across `main`, `test` and `androidTest`.

**No separate thumbnail file.** `RecordedVideo.thumbnailPath` points at the photo itself;
`ThumbnailDecoder.decodeSampled` already subsamples any JPEG to a ≤256 px long edge before decode,
so the grid gets a bounded bitmap with zero extra I/O.

### 5.5 Public library (MediaStore)

New `publishImageToPhotos(context, file)` beside the existing `publishVideoToPhotos`, using
`MediaStore.Images.Media`, `DIRECTORY_PICTURES/OpenLoop`, `image/jpeg`, and the same `IS_PENDING`
dance on Q+. Bridged into the ViewModel as an injected `suspend (File) -> Unit` lambda — the same
Context-free seam `isLowMemoryNow: () -> Boolean` already uses.

**Deliberate deviation from the video path:** a MediaStore failure is logged and swallowed rather
than failing the capture. The in-app save is the one that matters; losing the public copy should not
cost the user their photo. (The render worker treats it as fatal because a loop that never reaches
Photos is a broken promise of the "Loop saved to Photos" snackbar.)

`WRITE_EXTERNAL_STORAGE` on API ≤ 28 is already covered by `requiredCapturePermissions(sdkInt)`.

### 5.6 Share MIME

`buildBoomerangShareIntent` hardcodes `video/mp4`. Extract a pure `shareMimeType(file)` →
`image/jpeg` for `.jpg`, else `video/mp4`, and JVM-test it. `ClipData` stays
([Lesson 028](./lessons_learned/028-share-intent-clipdata-for-chooser.md)).

### 5.7 Gallery

`LoopingVideoOverlay` is ExoPlayer-only. Branch on the file extension: a photo renders a full-screen
`Image` (no player, no `LifecycleStartEffect`), keeping the same Close and SEND controls. Grid tiles,
long-press multi-select and delete all work unchanged.

## 6. Success criteria

1. Toggle flips Video ↔ Photo; icon, shutter and a11y labels all swap.
2. Photo capture never enters Trim / Editor / Processing.
3. The photo appears in the in-app gallery **and** in the device Photos app under `Pictures/OpenLoop`.
4. The share sheet opens on capture; swiping it down yields the "Saved" snackbar.
5. Camera flip works in photo mode (D3); front-camera photos save correctly.
6. An active lens is baked into the photo.
7. The toggle is unavailable while recording, and video capture is behaviourally unchanged.

## 7. Test plan

| Layer | Coverage |
|---|---|
| JVM unit | `capturePhoto` saves + emits `Share`; null bitmap → no save, no crash; double-tap guarded; mode toggle ignored while `Recording`; `shareMimeType` per extension; repository `savePhoto` round-trip via `TemporaryFolder` ([Lesson 008](./lessons_learned/008-jvm-test-file-and-dispatcher-pitfalls.md)) |
| Instrumented | Camera screen renders the toggle; shutter a11y label swaps with mode |
| Manual (emulator) | Full capture → share → gallery → Photos loop. Photo mode **is** emulator-verifiable, unlike lenses ([memory: emulator virtual scene has no face](#)) — so the DoD screenshot is achievable |

## 8. Risks

| Risk | Mitigation |
|---|---|
| `getBitmap()` returns null before the preview streams | Null-guarded → friendly snackbar, never a crash |
| Photo resolution is view-sized, not sensor-sized | Accepted under D1; documented above |
| `CameraManager.kt` is also touched by the in-review lenses PR | This PRD does **not** modify `CameraManager` at all — no conflict |
| The worktree is based on `6dd2419`, before the uncommitted lens fixes in the main checkout | Rebase onto `feature/camera-lenses` after that PR merges |

## 9. Open questions

None blocking. Building on the decisions in §3.
