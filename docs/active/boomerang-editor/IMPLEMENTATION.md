# Boomerang Editor — Implementation Plan

Feature folder for the **capture-to-edit flow** and the **Boomerang Editor screen**: the
piece of OpenRang that takes a raw clip (just captured, or picked from the gallery) and turns
it into a configurable looped boomerang.

> Status: **Spec / sign-off pending.** No code written yet. Owner: Steven.
> Branch (when started): see the per-slice branches under [`../boomerang-rollout/`](../boomerang-rollout/README.md).

---

## Status updates (most-recent first)

**2026-05-28 — Reverse-video implementation locked.** Verified via web research
that Media3 1.10.x has no built-in reverse effect and FFmpegKit is retired
(archived June 2025, no Maven artifacts). The path forward is a hand-rolled
two-pass MediaCodec reverser (`media/VideoReverser.kt`) shared between the
live preview and the render pipeline via a per-trim cache. Full rationale,
algorithm, alternatives, and performance estimates in
[`../boomerang-rollout/RESEARCH-reverse-video.md`](../boomerang-rollout/RESEARCH-reverse-video.md).
This resolves Q-4 below.

**2026-05-28 — Rollout broken into 7 slices.** Per Steven's request, this
parent doc remains the design/PRD ("what are we building?"); the new
[`../boomerang-rollout/`](../boomerang-rollout/README.md) folder owns delivery
sequencing ("in what order do we ship?") as 7 thin, vertical, PR-sized slices.

**2026-05-28 — Decisions finalized since v1 of this doc.** Inputs from Steven's
review:

- **D-1 default mode:** `FORWARD_THEN_REVERSE` is the default — confirmed (was
  flagged as override-able; locked).
- **D-1 recording cap:** changed from 10 s to **30 s** to give users headroom
  ("people don't always start the camera at the perfect time" — trim is the
  escape valve). Storage cost ~80 MB at 1080p is acceptable.
- **Editor entry pattern:** **no buttons.** After capture finalizes, the app
  auto-routes into the editor flow (no "Make Boomerang" button anywhere). Same
  pattern for gallery items: tap a raw clip → autoloads into the editor flow.
- **Trim is its own screen** before the tabbed editor, not a section *inside*
  the editor. Inspired by reference Boomerang's flow: Capture → **Trim** (with
  `NEXT`) → **Tabbed Editor** (with checkmark save). The §4 editor layout in
  this doc is now obsolete on that point — see the per-slice docs for the
  current layout.
- **D-7 post-save flow:** lift the Android share sheet from reference Boomerang
  on save (genuinely useful), drop the review-nag / settings-detour clunk
  (not useful). Confirmed.
- **Reverse preview:** open question. Initial recommendation in slice 03 is
  to pre-render a reversed segment, cache it, and play the cached file in the
  live preview (mirrors how reference Boomerang feels instant). Steven is
  doing independent research before this slice's implementation begins.

The sections below still capture the deep-dive architecture (storage model,
state machine, Media3 pipeline, design tokens) — they remain accurate.
Mismatches between this doc's UI section (§4) and the per-slice docs should
defer to the per-slice docs.

---

## 1. Problem statement

Today OpenRang's capture flow ends in `LoopingPreview` — a fixed-speed preview of the raw
burst, saved verbatim to the gallery as `clip_<ts>.mp4`. There is no editor, no reverse, no
concat, no speed/repetition control, no trim. The Boomerang feature itself does not yet exist.

We are also reverse-engineering the proprietary **Boomerang** app, which has the right
*editor* but the wrong *capture entry point* (forces the user to pre-record in another app)
and the wrong *business model* (ads/IAP per save). OpenRang's wedge is:

1. **Camera-first.** The app boots straight into the viewfinder so the user can react to what
   they're witnessing without leaving for the system camera and coming back.
2. **Free and on-device.** Every operation is local; there are no ads, no IAP, no network
   round-trip, no account.
3. **Open source.** The whole pipeline is auditable; users can fork the speed range, the modes,
   the duration cap, anything.

This document specifies the editor and the handoff from camera to editor.

---

## 2. Scope

### In scope

- **Capture-to-editor handoff**: after the user stops recording in `CameraScreen`, route into
  the new `BoomerangEditorScreen` with the raw clip loaded.
- **Edit-from-gallery entry point**: tapping a raw clip in `GalleryScreen` opens the same
  editor; tapping a finished boomerang opens it in *re-edit* mode (if the source raw still
  exists) or plays it back.
- **Editor UI**: video preview that updates live as settings change; trim handles; mode chips
  (Forward / Reverse / Reverse→Forward / Forward→Reverse); Speed and Repetitions tab controls;
  Save.
- **Media3 pipeline**: trim, reverse, concat, speed change, repetitions → single MP4 output.
- **Storage model**: keep the raw clip in the gallery *and* the finished boomerang, with a
  visible distinction between the two (see [§7](#7-data-layer-changes)).
- **Post-save UX**: trigger the Android share sheet for the finished MP4, then return to the
  camera viewfinder.

### Out of scope (defer)

- Audio handling beyond strip-or-keep (no pitch correction, no audio FX).
- Multi-clip composition (more than one source clip per boomerang).
- Cloud sync, sharing accounts, watermarks, captions, stickers, filters.
- Editing existing *boomerangs* by reverse-engineering their pipeline. Re-edit always works
  from the original raw, never from a rendered boomerang.
- Auto-detection of "best loop point" (interesting heuristic; not v1).

---

## 3. UX flow

### 3.1 Capture-first path (primary)

```
ReadyToCapture (camera viewfinder)
   │ tap shutter, record
   ▼
Recording
   │ tap stop, OR auto-stop at 30 s (Decision D-1)
   ▼
[raw written to cacheDir/scratch/raw_<uuid>.mp4 — survives process kill]
   │
   ▼
BoomerangEditor(source = ScratchClip(uuid))
   │ user trims, picks mode, sets speed + reps, taps Save
   ▼
Processing (Media3 Transformer renders → filesDir/boomerangs/boom_<ts>.mp4)
   │ on success: scratch file is promoted to filesDir/videos/clip_<ts>.mp4 (raw kept)
   ▼
ShareSheet (Android ACTION_SEND chooser for the rendered boomerang MP4)
   │ user dismisses (shares or cancels)
   ▼
ReadyToCapture   ← back to viewfinder; gallery now shows the new boomerang and raw
```

If the user backs out of the editor before saving, the scratch file is **discarded** — no
half-finished raws clutter the gallery.

### 3.2 Edit-from-gallery path (secondary)

```
Gallery
   │ tap a RAW clip
   ▼
BoomerangEditor(source = GalleryClip(rawId))
   │ same editor, raw clip is not promoted again (already in gallery)
   ▼
Processing → ShareSheet → Gallery
```

For finished boomerangs in the gallery: tap → preview (existing `LoopingPreview` behavior).
Long-press a boomerang whose source raw still exists → **Re-edit** (opens the editor over the
*raw*, not the rendered output). Long-press a raw → **Make a boomerang**.

### 3.3 Post-save share sheet (interpretation flag)

The original Boomerang app pops the Android share sheet on save, then drops the user into a
review nag + settings page before they can get back to the gallery. **We lift the share sheet
(genuinely useful) and drop the nag chain.** On share-sheet dismissal we land back on the
camera viewfinder, with a toast: *"Saved — view in gallery"* (tap to jump to Gallery).

> **Decision D-7** in [§12](#12-decisions) calls this out explicitly so Steven can override.

---

## 4. Editor screen layout

```
┌────────────────────────────────────────────────┐
│  ←  back/discard                       Save →  │   top bar (glassmorphic, DeepCharcoal 80%)
├────────────────────────────────────────────────┤
│                                                │
│                                                │
│          ┌──────────────────────┐              │
│          │                      │              │
│          │   live preview       │              │   ExoPlayer, repeatMode = ALL,
│          │   (loops, reflects   │              │   reflects current settings end-to-end
│          │    current settings) │              │
│          │                      │              │
│          └──────────────────────┘              │
│                                                │
│   ◀──────[ trim window ]──────▶                │   trim handles (only if duration > 1.0 s)
│   00:00         00:03.2        00:05           │   playhead + start/end markers
│                                                │
│   [Fwd] [Rev] [Rev→Fwd] [Fwd→Rev]              │   mode chips (single-select)
│                                                │
│   ┌── Speed ──┬── Repetitions ──┐              │   tabbed controls
│   │           │                  │              │
│   │  slider   │  [1] [2] [3] [4] │              │   speed: 0.25× – 3.0× (default 2.0×)
│   │  0.25× 3× │   segmented      │              │   reps:  1–4
│   │           │                  │              │
│   └───────────┴──────────────────┘              │
└────────────────────────────────────────────────┘
```

**Behavioral notes:**

- Every control mutates `EditorState` in the ViewModel; the preview re-binds the player to
  the new settings (without re-rendering the file — see [§6](#6-media3-pipeline)).
- "Save" is disabled while the preview is mid-bind or while a previous render is in flight.
- Back/discard from a *scratch* source shows a confirm dialog ("Discard this clip?"). Back
  from a *gallery* source needs no confirm (nothing is lost — raw is already in the gallery).
- The trim window enforces a **min 0.4 s** and **max = source duration** post-trim, and a
  warning bar appears if projected output duration (see §6.3) would exceed **30 s**.

**Design tokens** (from PRD §3 Design System Tokens):
- Background: `darkColorScheme` (DeepCharcoal-derived).
- Mode chip selected fill: `NeonCoral → NeonPurple` horizontal gradient.
- Tab indicator + slider track: `NeonPurple` 100%.
- Glass surfaces (top bar, control panel): `GlassWhite` 20% over `GlassWhiteBorder` 30%.
- All colors must be 8-hex-digit `Color(0xAARRGGBB)` literals (Lesson 001).

---

## 5. State machine update

Add to `OpenRangUiState`:

```kotlin
/**
 * The boomerang editor is open over a raw clip (either a fresh scratch capture or an
 * existing gallery raw). All editor controls mutate an inner [EditorState] held by the
 * ViewModel; this object only carries the source identity so navigation is resumable.
 */
data class BoomerangEditor(val source: EditorSource) : OpenRangUiState

sealed interface EditorSource {
    /** A just-captured clip living in cacheDir/scratch/raw_<uuid>.mp4, not yet in the gallery. */
    data class ScratchClip(val uuid: String) : EditorSource
    /** An existing raw clip in the gallery, addressed by its RecordedVideo.id. */
    data class GalleryClip(val rawId: Long) : EditorSource
}
```

Updated transitions (only the affected edges):

```
Recording        ──▶ BoomerangEditor(ScratchClip)        (was: LoopingPreview)
Gallery          ──▶ BoomerangEditor(GalleryClip)        (new: tap raw, or long-press boomerang→Re-edit)
BoomerangEditor  ──▶ Processing                          (user taps Save)
Processing       ──▶ ShareSheet (intent) ──▶ ReadyToCapture
BoomerangEditor  ──▶ ReadyToCapture / Gallery            (back/discard, depending on source)
```

`LoopingPreview` is **kept** for tap-a-finished-boomerang playback in the gallery. The editor
does not replace it.

The `EditorState` lives inside the ViewModel and is **not** part of `OpenRangUiState` — keeping
it out of the public state surface lets us mutate freely on every slider tick without producing
re-renders of the navigation host. The editor screen collects `editorState: StateFlow<EditorState>`
directly via `collectAsStateWithLifecycle()` (Lesson 002).

```kotlin
data class EditorState(
    val sourceDurationMs: Long,
    val trimStartMs: Long,
    val trimEndMs: Long,
    val mode: BoomerangMode,                       // FORWARD | REVERSE | REVERSE_THEN_FORWARD | FORWARD_THEN_REVERSE
    val speed: Float,                              // 0.25f .. 3.0f
    val repetitions: Int,                          // 1 .. 4
    val isRendering: Boolean = false,
)
```

---

## 6. Media3 pipeline

A new `media/VideoProcessor.kt` owns all rendering. The ViewModel calls a single suspend
function and awaits the rendered file.

### 6.1 Render contract

```kotlin
interface VideoProcessor {
    /**
     * Render a boomerang from [source] applying [trim], [mode], [speed], and [repetitions],
     * writing the output to [outputFile] and reporting progress via [onProgress] (0f..1f).
     * Throws on Media3 failure; cancellable via coroutine cancellation.
     */
    suspend fun renderBoomerang(
        source: File,
        trimStartMs: Long,
        trimEndMs: Long,
        mode: BoomerangMode,
        speed: Float,
        repetitions: Int,
        outputFile: File,
        onProgress: (Float) -> Unit = {},
    ): File
}
```

### 6.2 Pipeline stages (single Transformer invocation)

The render builds **one** `Composition` of `EditedMediaItem`s rather than chaining multiple
Transformer passes — chaining doubles I/O and re-encodes per pass.

1. **Trim**: `MediaItem.ClippingConfiguration` with `setStartPositionMs(trimStartMs)` and
   `setEndPositionMs(trimEndMs)` on the source.
2. **Reverse half** (only if `mode ∈ {REVERSE, REVERSE_THEN_FORWARD, FORWARD_THEN_REVERSE}`):
   call `videoReverser.reverse(source, trimStart, trimEnd)` to obtain (or
   cache-hit) a reversed `File`, then feed it as the second `EditedMediaItem`
   in the Composition. The `VideoReverser` is the hand-rolled two-pass
   MediaCodec implementation specified in
   [`../boomerang-rollout/RESEARCH-reverse-video.md`](../boomerang-rollout/RESEARCH-reverse-video.md)
   — Media3 itself has no reverse effect, and FFmpegKit is no longer
   maintained.
3. **Assemble cycle** depending on mode:
   - `FORWARD`               → `[trimmed]`
   - `REVERSE`               → `[reversed]`
   - `FORWARD_THEN_REVERSE`  → `[trimmed, reversed]`
   - `REVERSE_THEN_FORWARD`  → `[reversed, trimmed]`
4. **Repeat cycle**: append the assembled cycle `repetitions` times to the `Composition`.
5. **Speed**: apply `SpeedChangeEffect(speed)` on the composition — the constant-speed video effect
   in Media3 1.10.1 (**not** `SpeedChangingVideoEffect`, which isn't in this version). **Audio is
   stripped on render** (Decision D-3), so no audio speed processor is applied.
6. **Output**: H.264 / AAC-stripped, MP4 container, matching source dimensions and orientation
   (no normalization in v1).

### 6.3 Output duration math (and the 30 s warning)

```
cycle_ms      = trimDuration                       (FORWARD, REVERSE)
              = 2 × trimDuration                   (FORWARD_THEN_REVERSE, REVERSE_THEN_FORWARD)
output_ms     = (cycle_ms × repetitions) / speed
```

A 5 s trim at `FORWARD_THEN_REVERSE`, `reps=4`, `speed=0.5×` produces 80 s — way past what a
boomerang should be. The editor shows a non-blocking warning chip above Save when
`output_ms > 30_000`, and a hard error when `output_ms > 60_000`. (These thresholds are
Decision D-5; tune after first dogfood.)

### 6.4 Seam handling

When concatenating `trimmed + reversed` (or vice versa), the last frame of the first clip is
visually identical to the first frame of the second clip, producing a perceptible "freeze" at
the seam. Drop the first frame of the second clip via a 1-frame clip offset
(`setStartPositionMs(frameDurationMs)`). At 30 fps that's ~33 ms.

---

## 7. Data layer changes

### 7.1 `RecordedVideo` gains a kind

```kotlin
data class RecordedVideo(
    val id: Long,
    val videoPath: String,
    val thumbnailPath: String,
    val kind: VideoKind,            // NEW
    val sourceRawId: Long? = null,  // NEW — set on BOOMERANG, points back to its raw
)

enum class VideoKind { RAW, BOOMERANG }
```

Determined at load time from the file's directory (raws in `filesDir/videos/`, boomerangs in
`filesDir/boomerangs/`). `sourceRawId` is encoded in the boomerang filename:
`boom_<ts>_from_<rawTs>.mp4`.

### 7.2 `VideoStorageRepository` additions

```kotlin
interface VideoStorageRepository {
    // existing: rawCaptureFile, saveFinalizedVideo, loadRecordedVideos, deleteVideo

    /** Create a fresh scratch capture file under cacheDir/scratch/raw_<uuid>.mp4. */
    fun createScratchCapture(): ScratchCapture

    /** Promote a scratch capture into the gallery as a RAW; returns the persisted file. */
    fun promoteScratchToRaw(scratch: ScratchCapture): File?

    /** Delete a scratch capture (user backed out of the editor). */
    fun discardScratch(scratch: ScratchCapture)

    /** Allocate the output File for a rendered boomerang derived from [sourceRawId]. */
    fun allocateBoomerangFile(sourceRawId: Long): File

    /** Register a rendered boomerang (extract thumbnail, etc.) so loadRecordedVideos() sees it. */
    fun registerBoomerang(file: File, sourceRawId: Long): RecordedVideo?
}

data class ScratchCapture(val uuid: String, val file: File)
```

Directory layout after this change:

```
cacheDir/
  scratch/raw_<uuid>.mp4              ← in-flight scratch captures
filesDir/
  videos/clip_<ts>.mp4                ← RAW kind (unchanged path for back-compat)
  thumbnails/clip_<ts>.jpg
  boomerangs/boom_<ts>_from_<rawTs>.mp4   ← BOOMERANG kind (new)
  thumbnails/boom_<ts>_from_<rawTs>.jpg
```

> **Back-compat:** existing `clip_<ts>.mp4` files (today's only output) are treated as `RAW`
> with no associated boomerang. The gallery will show them with the raw badge. This matches
> reality — they were never processed.

### 7.3 Gallery visual distinction

`GalleryScreen` adds:

- A small badge overlay on raw thumbnails (e.g., a "•R" chip in the bottom-right corner using
  `NeonPurple` 80%). Boomerangs get no badge (they're the default expected output).
- A filter row at the top: `All` · `Boomerangs` · `Raw`. Default `All`.
- Long-press menu:
  - Raw → *Make a boomerang* (opens editor), *Delete*.
  - Boomerang → *Re-edit from source* (only if source raw still exists), *Delete*.

Deleting a raw that has linked boomerangs prompts: *"This will also break re-edit for N
boomerang(s). Delete anyway?"* The boomerangs themselves are unaffected — only the re-edit
path breaks.

---

## 8. ViewModel surface

New entry points and one removed transition.

```kotlin
// New
fun startEditorFromScratch(scratch: ScratchCapture)
fun startEditorFromGallery(rawId: Long)
fun startEditorFromBoomerang(boomerangId: Long)   // finds source raw; falls back to playback

fun updateTrim(startMs: Long, endMs: Long)
fun updateMode(mode: BoomerangMode)
fun updateSpeed(speed: Float)
fun updateRepetitions(reps: Int)

fun saveBoomerang()                               // launches Processing → ShareSheet
fun discardEditor()                               // confirms if source is ScratchClip

// Removed: the auto-transition from Recording → LoopingPreview goes away.
//          Recording → BoomerangEditor(ScratchClip) replaces it.
```

All writes are guarded with `try { ... } catch (e: IOException) { ... }` per Lesson 003.
No `Context` is passed into these functions — the `Factory` in `MainActivity` wires the
repository as before (Lesson 004).

---

## 9. Camera capture changes

`CameraManager.startRecording(file, onEvent)` is unchanged. The differences are at the call
site:

1. `startBurstCapture` allocates a `ScratchCapture` via `videoStorage.createScratchCapture()`
   instead of overwriting the singleton `rawCaptureFile`.
2. The 1.5 s self-stop timer is replaced with a **30 s cap** (Decision D-1); user-driven stop
   is the expected path.
3. On `VideoRecordEvent.Finalize` success, transition to
   `BoomerangEditor(ScratchClip(scratch.uuid))` instead of `LoopingPreview`.
4. On `Finalize` error, discard the scratch and return to `ReadyToCapture` (unchanged).

`cacheDir/raw_capture.mp4` (the singleton scratch file) is **retired** — every capture now
gets its own UUID so a process kill mid-edit doesn't clobber the previous in-flight capture.

---

## 10. Post-save flow

After `Processing` succeeds:

1. Launch `Intent.ACTION_SEND` with `EXTRA_STREAM = FileProvider URI for the rendered MP4`,
   `type = "video/mp4"`. Wrap with `Intent.createChooser(...)` so the user picks the target
   app. **No write to public storage is required** for this — the FileProvider points at the
   app-private file.
2. Whether the user shares or dismisses, return to `ReadyToCapture`.
3. Show a `Snackbar` (preferred over `Toast` for accessibility and action affordance) with
   text *"Saved — view in gallery"* and a `View` action that navigates to `Gallery`.
4. Auto-dismiss the snackbar at 4 s.

We **do not** copy the boomerang into public DCIM/Pictures in v1 — that's a separate decision
(D-6) about media-store integration and scoped storage. The share sheet is sufficient for v1
since FileProvider URIs are first-class share targets.

---

## 11. Testing plan

### 11.1 Unit tests (JVM)

- `OpenRangViewModelTest`:
  - Recording → BoomerangEditor(ScratchClip) transition fires on finalize success.
  - Recording → ReadyToCapture transition on finalize error; scratch is discarded.
  - Editor mutators (`updateTrim`, `updateMode`, `updateSpeed`, `updateRepetitions`) push
    onto `editorState` without changing `uiState`.
  - `saveBoomerang()` flips `editorState.isRendering = true`, awaits the processor, and
    transitions to `ReadyToCapture` on success.
  - `discardEditor()` from a `ScratchClip` source calls `discardScratch()`; from a
    `GalleryClip` it does not.
  - Uses the patterns in Lesson 008: real `TemporaryFolder`, single shared `TestDispatcher`,
    fake `VideoProcessor` instead of mocking the Media3 surface.
- `VideoStorageRepositoryImplTest`:
  - `createScratchCapture` / `promoteScratchToRaw` / `discardScratch` round-trip.
  - `allocateBoomerangFile` + `registerBoomerang` produce a `RecordedVideo` with
    `kind = BOOMERANG` and correct `sourceRawId`.
  - `loadRecordedVideos` returns mixed kinds, newest first.

### 11.2 Instrumented tests (androidTest)

- End-to-end: capture → editor opens with the scratch loaded → set mode + speed + reps →
  Save → assert the boomerang file exists, has expected duration (within ±1 frame), and
  appears in `loadRecordedVideos()`.
- `BoomerangEditorScreenTest` (Compose): mode chips are single-select; speed slider clamps
  to `[0.25, 3.0]`; reps segmented control clamps to `[1, 4]`; Save is disabled while
  rendering.
- Re-edit-from-gallery path: tap a `BOOMERANG` with a present source → opens editor over the
  raw; tap a `BOOMERANG` whose raw is missing → falls back to playback.

### 11.3 Manual QA checklist (post-build, per `DEFINITION_OF_DONE.md`)

- Capture → editor opens with the captured clip; preview loops.
- All four modes produce visually correct previews.
- Speed slider: 0.25× plays slow-motion, 3.0× plays fast.
- Reps: 4 visibly repeats; 1 plays once.
- Save → share sheet appears; share to a file-saving target (Drive / Telegram) succeeds.
- Back from camera → app returns to viewfinder; gallery contains both the raw and the
  boomerang, raw badged.
- Process kill during edit (`adb shell am force-stop com.openrang.app`): on relaunch the
  raw scratch is detected and offered for resume. *(Stretch — Decision D-8.)*
- Screenshot of the editor screen attached to the PR.

---

## 12. Decisions

Each decision below is the **default** for this spec. Override any of them on review and
I'll update the doc.

| ID  | Decision                                                                                                 | Rationale                                                                                  |
|-----|----------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------|
| D-1 | Recording cap: **30 s**. Auto-stop at 30 s; user can stop earlier. (was 10 s in v1 of this doc)             | More headroom so users don't have to start the camera at the "perfect" moment. Trim is the escape valve. |
| D-2 | Speed range: **0.25× – 3.0×**, default **2.0×**.                                                         | 2× matches original Boomerang feel. 0.25× enables hyperlapse-style slow loops.             |
| D-3 | **Audio stripped on render.** Reversed audio sounds awful; speed-shifted audio is artifact-laden.        | Boomerang strips audio. Add a "Keep audio" toggle later if requested.                      |
| D-4 | Modes: **FORWARD / REVERSE / FORWARD_THEN_REVERSE / REVERSE_THEN_FORWARD**.                              | Matches your description verbatim.                                                         |
| D-5 | Output duration warning at **>30 s**, hard error at **>60 s**.                                            | Above 30 s a "boomerang" stops being one. Hard error protects against accidental 5-min renders. |
| D-6 | Boomerang lives in **app-private storage** + FileProvider share. No DCIM/MediaStore write in v1.          | Avoids scoped-storage complexity. Share sheet covers "get it out of the app."              |
| D-7 | Post-save flow: **share sheet → snackbar → camera viewfinder**. No review nag, no settings detour.       | Drops the original Boomerang's clunk; keeps the useful piece (share sheet).                |
| D-8 | **Resume after process kill** is stretch, not v1. Scratch files older than 24 h are auto-pruned at boot. | Keeps v1 scope tight. Resume is a nice-to-have once the core flow works.                   |

---

## 13. Open questions

| # | Question                                                                                                          |
|---|-------------------------------------------------------------------------------------------------------------------|
| ~~Q-1~~ | ~~Default mode on editor open~~ — **resolved 2026-05-28: `FORWARD_THEN_REVERSE`** (classic boomerang).         |
| Q-2 | Should the editor allow **0 reps** (preview only, no save)? Probably no; flag for sanity.                       |
| Q-3 | Front-camera vs back-camera capture: anything mode-specific (e.g., mirror flip)? Likely no; track for v1.5.     |
| ~~Q-4~~ | ~~Media3 `ReverseVideoEffect` availability~~ — **resolved 2026-05-28: there is no such API in Media3 1.10.x**, and FFmpegKit is retired. Plan locked: hand-rolled two-pass MediaCodec via the new `media/VideoReverser.kt`. Full rationale + algorithm + alternatives in [`../boomerang-rollout/RESEARCH-reverse-video.md`](../boomerang-rollout/RESEARCH-reverse-video.md). |
| Q-5 | "Make a boomerang" from a raw imported via the system file picker (vs. captured by us): in-scope for v1, or v1.5? |

---

## 14. Acceptance criteria

A change is shippable for this feature when **all** of the following hold (per
`DEFINITION_OF_DONE.md`):

- [ ] `assembleDebug` + `assembleRelease` are genuinely green (BUILD SUCCESSFUL, exit 0, zero `e:`).
- [ ] `testDebugUnitTest`: 0 failures; new tests in §11.1 present.
- [ ] `connectedDebugAndroidTest`: 0 failures; new tests in §11.2 present.
- [ ] `zipalign -c -P 16 -v 4 …` on the release APK shows `(OK)` (not `(OK - compressed)`)
      for every `.so` (Lesson 011).
- [ ] App actually launched on an emulator AND on Steven's Pixel 10 Pro Fold; manual QA
      checklist in §11.3 walked end-to-end; screenshot(s) attached to the PR.
- [ ] Honest coverage statement: list anything not exercised on real hardware (e.g., very long
      clips, low-storage edge cases).
- [ ] No `Color(0x…)` literal in the new code violates the 8-hex-digit rule (Lesson 001).
- [ ] Every `Flow` collected in the editor screen uses `collectAsStateWithLifecycle()`
      (Lesson 002).
- [ ] Every `DataStore` / repository write is wrapped in `try / catch (IOException)`
      (Lesson 003).
- [ ] No `Context` parameter on any `OpenRangViewModel` function (Lesson 004).

---

## 15. Implementation order (suggested)

1. **Data-layer plumbing**: `RecordedVideo.kind`, `VideoStorageRepository` additions, tests.
2. **State + ViewModel**: `BoomerangEditor` state, `EditorState`, editor mutators, tests
   with a fake `VideoProcessor` that returns a stub file.
3. **`VideoProcessor` implementation**: trim → reverse → concat → speed in one Composition,
   instrumented tests against a known input file.
4. **Capture handoff**: rewire `startBurstCapture` to scratch + transition to editor.
5. **`BoomerangEditorScreen`**: preview, trim, mode chips, tabs, Save. Compose tests.
6. **Gallery updates**: kind badge, filter row, long-press menu, re-edit entry point.
7. **Post-save share intent + snackbar**, FileProvider config in `AndroidManifest.xml`.
8. **Manual QA pass** on emulator + Pixel 10 Pro Fold; screenshot; PR.

Each numbered step is a meaningful PR-sized chunk. Steps 1–4 are invisible to the user but
unblock 5; do not bundle them into one mega-PR.
