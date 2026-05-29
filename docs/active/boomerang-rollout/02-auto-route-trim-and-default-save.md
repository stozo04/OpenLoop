# Slice 02 — Auto-route to Trim screen + default-render Save

> **Branch:** `feature/boomerang-slice-02-trim-and-default-save`
> **Depends on:** slice 01 (variable-length capture).
> **Unblocks:** slice 03 (tabbed editor).

---

## Problem

A captured clip today drops into `LoopingPreview` — a dead-end screen that just
plays the raw clip. There is no way for the user to make a boomerang. This slice
introduces:

1. **Auto-route**: after capture finalizes, the user is dropped onto a dedicated
   **Trim screen** with their clip loaded — no button to press, no decision to
   make. This is what reference Boomerang does and matches Steven's "remove
   hurdles between witnessing and creating" framing.
2. **First boomerang output**: the Trim screen's `NEXT` button renders a
   **default** boomerang (`fwd→rev`, 2× speed, 1 rep, no extra trim if the user
   left the handles alone) and saves it. The user gets a real boomerang from a
   real capture, without any tab UI existing yet.

The tabbed editor itself is **out of scope** for this slice — it arrives in
slice 03. Slice 02 proves the full pipeline: capture → trim → render → save,
end-to-end, with the simplest possible config.

## Scope

### In scope
- New `BoomerangUiState.Trim(source)` state.
- New `TrimScreen` composable: preview at top (~75%), trim bar + `NEXT` button
  at bottom (~25%).
- Auto-transition from `Recording → Finalize → Trim(ScratchClip)` (replaces
  today's `Finalize → LoopingPreview`).
- New `media/VideoProcessor.kt` with a single `renderBoomerang(...)` entry point
  hard-wired to `mode=FORWARD_THEN_REVERSE, speed=2.0f, reps=1`. Trim values
  come from the Trim screen.
- New `media/VideoReverser.kt` — the two-pass MediaCodec reverser that
  `VideoProcessor` depends on for any reverse-containing mode (which includes
  the default `FORWARD_THEN_REVERSE`, so this slice must ship it). See
  [`RESEARCH-reverse-video.md`](./RESEARCH-reverse-video.md) for the verified
  rationale and algorithm specification.
- New `VideoStorageRepository.createScratchCapture()` /
  `promoteScratchToRaw()` / `allocateBoomerangFile()` /
  `registerBoomerang()` — per-UUID scratch files, separate `boomerangs/` dir,
  raws kept (per Steven's "raw alongside boomerang" decision).
- `RecordedVideo.kind` field (`RAW` | `BOOMERANG`); inferred from the file's
  directory. Gallery still displays both without distinction for this slice
  (badges + filter come in slice 07).
- A `Processing` state with a spinner during the render (Media3 is fast for
  short clips but not instantaneous on older hardware).

### Out of scope
- Any tab UI. The editor screen does not exist yet.
- Picking direction / speed / reps. All three are hard-coded.
- Share sheet on save. Save just snackbars "Saved" and returns to camera. Share
  arrives in slice 06.
- Gallery filter / badge / tap-to-edit. Arrives in slice 07.
- Mid-render cancel ("oops" button). Renders are fast enough that v1 doesn't
  need this; flag as a stretch for the v1.5 backlog.

## UX deltas

### Auto-route

```
Recording                tap shutter again → finalize  (slice 01)
Finalize (success)       _no LoopingPreview!_  →  Trim(ScratchClip(uuid))
Finalize (error)         → ReadyToCapture  (unchanged)
```

`LoopingPreview` is **not deleted** — it remains as the playback target for
finished boomerangs tapped from the gallery (slice 07 wires this up). It just
stops being the post-capture landing pad.

### Trim screen layout

```
┌────────────────────────────────────────────────┐
│  ←                                  [ ✓ ]      │   top bar
│  back                            (save: nyi)   │   ✓ is HIDDEN in slice 02
├────────────────────────────────────────────────┤
│                                                │
│                                                │
│              ┌──────────────────┐              │
│              │                  │              │
│              │  preview (loops  │              │
│              │  trimmed range)  │              │   ~75% screen height
│              │                  │              │
│              └──────────────────┘              │
│                                                │
│                       3.2s                     │   live duration indicator
│                                                │
├────────────────────────────────────────────────┤
│   ◀══════[ trimmed range ]══════▶              │   trim bar
│         (drag handles)                         │   ~25% screen height
│                                                │
│             [    NEXT (full-width)    ]        │   primary action
└────────────────────────────────────────────────┘
```

**Behavioral notes:**

- The back arrow (top-left) discards the scratch clip with a confirm dialog
  ("Discard this clip?") and routes back to `ReadyToCapture`.
- The save checkmark (top-right) is **hidden** in this slice — `NEXT` is the
  only primary action. (Slice 03 brings the checkmark online when the editor
  exists.)
- The trim bar has two draggable handles (left = start, right = end). Min
  trimmed duration: **400 ms**. Max trimmed duration: equal to source duration.
- Dragging either handle scrubs the preview to that handle's frame momentarily,
  then resumes looping the trimmed range when the user lifts.
- The "3.2s" indicator below the preview shows the **trimmed range duration**,
  updating live as handles drag.
- `NEXT` is enabled whenever `trimEnd - trimStart >= 400 ms`. Disabled
  otherwise (gray pill, no ripple).

### After NEXT

A full-screen `Processing` state appears with a centered spinner and
"Creating boomerang…" caption. On success: snackbar **"Saved — view in gallery"**
with a `View` action, route to `ReadyToCapture`. On failure: snackbar
**"Couldn't save boomerang. Try again."**, route back to `Trim` with the user's
trim selection preserved.

## Technical deltas

### `OpenRangUiState.kt`

```kotlin
data class Trim(val source: EditorSource) : OpenRangUiState
object Processing : OpenRangUiState   // already declared in current state file; keep

sealed interface EditorSource {
    data class ScratchClip(val uuid: String) : EditorSource
    // GalleryClip arrives in slice 07
}
```

### `OpenRangViewModel.kt`

- On `VideoRecordEvent.Finalize` success: instead of transitioning to
  `LoopingPreview`, build a `ScratchClip(uuid)` from the just-finalized
  scratch file and post `Trim(ScratchClip(uuid))`.
- Add a sibling `editorState: StateFlow<TrimState>` that the Trim screen reads:
  ```kotlin
  data class TrimState(
      val sourceFile: File,
      val sourceDurationMs: Long,
      val trimStartMs: Long = 0L,
      val trimEndMs: Long = sourceDurationMs,
  )
  ```
- Add mutators: `updateTrim(startMs, endMs)`, `discardTrim()`,
  `saveBoomerangDefault()` — the last one calls `VideoProcessor.renderBoomerang`
  with hard-coded `mode=FORWARD_THEN_REVERSE, speed=2.0f, reps=1`.
- Wrap every repository write in `try { ... } catch (e: IOException) { ... }`
  per Lesson 003.

### `VideoStorageRepository` (interface + impl)

Add the four methods listed in the parent IMPLEMENTATION.md §7.2:

```kotlin
fun createScratchCapture(): ScratchCapture
fun promoteScratchToRaw(scratch: ScratchCapture): File?
fun discardScratch(scratch: ScratchCapture)
fun allocateBoomerangFile(sourceRawId: Long): File
fun registerBoomerang(file: File, sourceRawId: Long): RecordedVideo?

data class ScratchCapture(val uuid: String, val file: File)
```

Directory additions:

```
cacheDir/scratch/raw_<uuid>.mp4
filesDir/boomerangs/boom_<ts>_from_<rawTs>.mp4
filesDir/thumbnails/boom_<ts>_from_<rawTs>.jpg
```

Also extend `RecordedVideo` with `kind: VideoKind` and `sourceRawId: Long?`
(see parent doc §7.1). `loadRecordedVideos()` infers `kind` from the file's
parent directory.

### `media/VideoReverser.kt` (new)

The two-pass MediaCodec reverser, fully specified in
[`RESEARCH-reverse-video.md`](./RESEARCH-reverse-video.md) §3 and §5. Surface:

```kotlin
class VideoReverser(
    private val scratchDir: File,        // cacheDir/scratch/reversed/
) {
    /**
     * Produce a reversed version of [source] over the trim window
     * [[trimStartMs, trimEndMs]]. Suspending, cancellable, idempotent —
     * a cache key of "<source-abs-path>_<trimStart>_<trimEnd>" is checked
     * first; if a reversed file already exists in [scratchDir], it is
     * returned without re-processing. Strips audio. Throws on MediaCodec
     * failure.
     */
    suspend fun reverse(
        source: File,
        trimStartMs: Long,
        trimEndMs: Long,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) { /* ... */ }
}
```

Internals (mirrors the sisik.eu reference at
[github.com/sixo/reverse-video](https://github.com/sixo/reverse-video),
MIT licensed):

1. **Pass 1:** transcode the trimmed source to an intermediate MP4 with
   `KEY_I_FRAME_INTERVAL = 0` (every frame is an I-frame). Lives in
   `cacheDir/scratch/reversed/_intermediate_<uuid>.mp4`; deleted at end of
   pass 2 regardless of success.
2. **Pass 2:** walk the intermediate's sync samples backwards via a `Stack<Long>`
   of presentation times, feed each sample to a `MediaCodec` decoder whose
   output Surface is the encoder's input Surface (single-Surface path; no EGL
   needed since we're not adding visual effects), remap presentation time
   to `endTimeUs - extractor.sampleTime`, mux to the final reversed file.
3. **Output path:** `cacheDir/scratch/reversed/<sha1(source-path + trim)>.mp4`.
4. **Lifecycle:** decoder, encoder, muxer all released in a `finally` block;
   `CancellationException` propagates cleanly so coroutine cancellation
   tears down the pipeline.

This slice must ship `VideoReverser` because the default mode
`FORWARD_THEN_REVERSE` needs reverse to work — there is no "ship reverse
later" path.

### `media/VideoProcessor.kt` (new)

A single-class file implementing the interface in the parent doc §6.1. For
this slice the implementation:

1. Builds a `MediaItem` from the source file with
   `ClippingConfiguration(start=trimStartMs, end=trimEndMs)` — this is the
   "forward" half.
2. Calls `videoReverser.reverse(source, trimStartMs, trimEndMs)` to obtain
   (or retrieve from cache) the reversed half as a `File`. Reports its
   `onProgress` fraction back to the caller, scaled into the first 80% of
   the overall progress budget — the Composition encode is the remaining 20%.
3. Constructs a Media3 `Composition` containing:
   - `EditedMediaItem` for the trimmed forward half.
   - `EditedMediaItem` for the reversed half (built from the file returned
     in step 2), with the seam-handling 1-frame `setStartPositionMs` offset
     described in parent doc §6.4.
4. Applies `SpeedChangingVideoEffect(2.0f)` to the composition and strips
   audio (no `SpeedChangingAudioProcessor`).
5. Outputs to `videoStorage.allocateBoomerangFile(sourceRawId)`.
6. Reports `onProgress(0f..1f)` via `Transformer.Listener.onCompleted` /
   `onError` / progress polling.

Suspend-cancellable: the function returns a `withContext(Dispatchers.IO) { ... }`
that catches `CancellationException` and aborts both the in-flight
`VideoReverser` (if running) and the Transformer.

> **Decision locked.** The earlier "Open question — verify
> `ReverseVideoEffect`" callout is resolved in
> [`RESEARCH-reverse-video.md`](./RESEARCH-reverse-video.md): Media3 1.10.x
> does **not** ship a reverse effect, and FFmpegKit is retired and unsuitable
> as a dependency. The two-pass MediaCodec approach via the new
> `VideoReverser` class is the chosen path. No further web-verification
> required before implementation begins.

### `ui/TrimScreen.kt` (new)

- Compose layout following the ASCII mock above.
- Hosts ExoPlayer in an `AndroidView`. Player binds the source with
  `ClippingConfiguration` and `repeatMode = Player.REPEAT_MODE_ALL`. On trim
  changes, rebuild the `MediaItem` with the new clipping and re-prepare the
  player.
- Trim bar: a `Canvas`-based custom composable with two draggable handles.
  Hit-targets ≥ 44 dp per Material accessibility guidance.
- `NEXT` button: enabled state bound to `(trimEnd - trimStart) >= 400`.
- Collects `editorState` via `collectAsStateWithLifecycle()` (Lesson 002).

### `MainActivity.kt`

Add the `Trim` and `Processing` branches to the conditional routing. `Trim` →
`TrimScreen(...)`. `Processing` → existing or new `ProcessingScreen` with a
centered spinner + caption (small composable).

## Testing plan

### Unit tests

- `OpenRangViewModelTest`:
  - On `Finalize` success, `uiState` transitions to `Trim(ScratchClip(uuid))`
    (NOT `LoopingPreview` anymore).
  - `editorState.trimStart / trimEnd` initialize to `0 / sourceDuration`.
  - `updateTrim(...)` clamps to `[0, sourceDuration]` and enforces
    `trimEnd - trimStart >= 400`.
  - `saveBoomerangDefault()` flips `uiState` to `Processing`, awaits the
    `VideoProcessor` (fake returning a stub file), promotes the scratch to a
    raw, registers the boomerang, transitions to `ReadyToCapture`, and emits a
    "Saved" event (collected via a `SharedFlow<Event>`).
  - `saveBoomerangDefault()` on `VideoProcessor` failure transitions back to
    `Trim` with the prior trim selection intact and emits a "Failed" event.
  - `discardTrim()` calls `videoStorage.discardScratch(...)` and transitions to
    `ReadyToCapture`.
- `VideoStorageRepositoryImplTest`:
  - `createScratchCapture` → returns a `ScratchCapture` whose `file` is in
    `cacheDir/scratch/` and does not yet exist on disk.
  - `promoteScratchToRaw` copies the scratch file into `filesDir/videos/` with
    the expected `clip_<ts>.mp4` name and extracts a thumbnail.
  - `discardScratch` deletes the scratch file (if present); idempotent.
  - `allocateBoomerangFile` + `registerBoomerang` produce a `RecordedVideo`
    with `kind = BOOMERANG` and correct `sourceRawId`.
  - Use `TemporaryFolder` per Lesson 008 — do **not** mock `File`.
- `VideoReverserTest` (instrumented — needs real `MediaCodec`, can't run on
  pure JVM):
  - Reversing a short fixture MP4 produces a file with duration matching the
    trim window within ±1 frame.
  - First frame of the reversed output ≈ last frame of the source trim window
    (sample via `MediaMetadataRetriever.getFrameAtTime`, compare via histogram
    distance — exact pixel equality is unreliable across codec round-trips).
  - Calling `reverse(...)` twice with the same source + trim returns the
    cached file path without invoking MediaCodec again (verify by snapshotting
    file `lastModified`).
  - Intermediate keyframe-only file is deleted from `cacheDir/scratch/reversed/`
    after pass 2 (success or failure).
  - Coroutine cancellation mid-reverse releases codec resources and removes
    partial output (assert no `_intermediate_*.mp4` and no partial output
    file linger).

### Instrumented tests

- `TrimScreenTest`: handles drag updates `editorState`; `NEXT` is disabled when
  trim duration < 400 ms; preview re-binds with new clipping when handles change.
- End-to-end (slowest test): record a 3 s capture on an emulator, drag handles
  to a 1.5 s range, tap `NEXT`, assert that:
  - A file appears under `filesDir/boomerangs/`.
  - The file's duration is `≈ 1.5 s` (one fwd + one rev cycle at 1× speed) /
    `2.0` (speed) = `≈ 1.5 s`. (i.e. `cycle_ms × reps / speed`.)
  - `loadRecordedVideos()` returns the new boomerang with `kind = BOOMERANG`.
  - A raw also exists with `kind = RAW`.
  - The `cacheDir/scratch/reversed/` cache contains exactly one file (the
    reversed half), and no `_intermediate_*.mp4` files remain.

### Manual QA

- Record 5 s clip → lands on Trim screen, preview loops the full 5 s.
- Drag start handle to ~1 s, end handle to ~4 s → preview loops 3 s range,
  duration indicator reads "3.0s".
- Tap `NEXT` → spinner with "Creating boomerang…", then snackbar "Saved — view
  in gallery". **Note the elapsed time** and record it in the PR description —
  per [`RESEARCH-reverse-video.md`](./RESEARCH-reverse-video.md) §5 perf
  estimates, a 3 s trimmed source on Pixel 10 Pro Fold should produce a
  reversed half in ~1 s plus the Composition encode (~0.5 s) for a total of
  ~1.5 s end-to-end. Anything > 4 s warrants investigation.
- Inspect `cacheDir/scratch/reversed/` via `adb shell run-as com.openrang.app ls`:
  exactly one reversed `.mp4` should exist; no `_intermediate_*.mp4` should
  remain.
- Save a second boomerang from a *different* trim window of the same raw → a
  second reversed file appears in the cache (different cache key because trim
  is part of the key).
- Save a second boomerang from the *same* trim window of the same raw → no
  new reversed file is created (cache hit); render is noticeably faster
  (just the Composition encode).
- Tap `View` on snackbar → gallery shows the new boomerang (no badge yet).
- Discard from Trim screen → confirm dialog → "Yes" returns to camera, scratch
  cleaned up.
- Process kill on the Trim screen (`adb shell am force-stop com.openrang.app`)
  → relaunch returns to `ReadyToCapture` (scratch is orphaned; pruning is
  slice-07 territory). Document this is expected.
- Process kill *during* an in-flight render → on relaunch, no zombie
  intermediate / partial output files in the cache directory; codecs released
  (verify via `adb shell dumpsys media.codec | grep com.openrang.app` shows
  no held instances).
- Screenshot of the Trim screen attached to the PR.

## Acceptance criteria

- [ ] `assembleDebug` + `assembleRelease`: BUILD SUCCESSFUL, exit 0, zero `e:`.
- [ ] `testDebugUnitTest`: 0 failures; all unit tests above present.
- [ ] `connectedDebugAndroidTest`: 0 failures.
- [ ] `zipalign -c -P 16 -v 4 …` on release APK shows `(OK)` (Lesson 011).
- [ ] App launched on emulator AND Pixel 10 Pro Fold; end-to-end walked;
      screenshot attached.
- [ ] No `Color(0x…)` literal violates the 8-hex-digit rule (Lesson 001).
- [ ] All Flow collection in TrimScreen uses `collectAsStateWithLifecycle()`
      (Lesson 002).
- [ ] All repository writes wrapped in `try / catch (IOException)` (Lesson 003).
- [ ] No `Context` parameter on any `OpenRangViewModel` method (Lesson 004).
- [ ] FileProvider not yet configured (that's slice 06); confirm no
      `Intent.ACTION_SEND` shipped prematurely.
- [ ] `VideoReverser` cache behavior verified: cache hit on identical
      source + trim is instant (no MediaCodec invocation); cache miss on
      different trim produces a new reversed file.
- [ ] `VideoReverser` cleanup verified: no `_intermediate_*.mp4` files in
      `cacheDir/scratch/reversed/` after success OR failure OR cancellation.
- [ ] Reverse-generation latency for a 3 s trimmed source on Pixel 10 Pro Fold
      recorded in the PR description (target: ≤ 1.5 s end-to-end including
      Composition encode; investigate if higher).
- [ ] PR description states what was NOT verified (especially: process-kill
      scratch resume is out of scope for this slice).
