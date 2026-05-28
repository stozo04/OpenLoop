# Slice 03 — Tabbed Editor + Direction tab

> **Branch:** `feature/boomerang-slice-03-direction-tab`
> **Depends on:** slice 02 (Trim screen + default-render Save).
> **Unblocks:** slices 04 (Speed) and 05 (Reps).

---

## Problem

After slice 02, the user can capture, trim, and save a default boomerang —
but the boomerang is *always* `fwd→rev` at 2× speed for 1 rep. There is no
expression. This slice introduces:

1. **The tabbed editor screen** — opens after the user taps `NEXT` on the Trim
   screen. The trim values flow through.
2. **The Direction tab** (the only tab present in this slice) — four chips:
   Forward, Reverse, Forward → Reverse, Reverse → Forward. Default is
   `FORWARD_THEN_REVERSE`.
3. **The save checkmark** in the top-right of the editor, hidden until now.
4. **Preview fidelity for direction** — the preview at the top of the editor
   loops with the chosen direction applied (not just the trimmed raw).

The bottom tab bar shows **only the Direction icon** in this slice (Lean).
Speed and Reps icons arrive in slices 04 and 05 respectively.

## Scope

### In scope
- New `BoomerangEditor(source, trim)` state, distinct from `Trim`.
- `Trim` screen's `NEXT` button now transitions to `BoomerangEditor` (was: to
  `Processing`).
- New `BoomerangEditorScreen` composable: preview top ~75%, content panel ~20%,
  tab bar ~5%.
- Direction tab content: 4 chip-style buttons with icons matching reference
  Boomerang (fwd `▶▶`, rev `◀◀`, fwd→rev `▶◀`, rev→fwd `◀▶`); selected chip
  uses `NeonCoral → NeonPurple` gradient fill; unselected chips use
  `GlassWhite` 20% on `GlassWhiteBorder` 30%.
- Tab bar with **one** icon (Direction = `>>` fast-forward glyph), centered.
  The bar height is reserved (~56 dp) so that adding more tabs in slices 04/05
  doesn't reflow layout; the single icon is centered in that bar.
- Save checkmark top-right (filled circle, `NeonPurple`, white check glyph) —
  renders with the chosen direction, hard-coded `speed=2.0f, reps=1`.
- Live preview reflects the chosen direction (see "Reverse preview decision"
  below).

### Out of scope
- Speed control (slice 04).
- Reps control (slice 05).
- Share sheet on save (slice 06).
- Gallery editing (slice 07).
- More than one icon in the tab bar.

## UX deltas

### State transitions

```
Trim                tap NEXT  →  BoomerangEditor(source, trim)   (was: → Processing)
BoomerangEditor     tap ←     →  Trim                            (preserves trim)
BoomerangEditor     tap ✓     →  Processing
Processing          success   →  ReadyToCapture (snackbar)        (same as slice 02)
```

Note: `Trim` is no longer terminal — `NEXT` opens the editor instead of
short-circuiting to `Processing`. The default-render `saveBoomerangDefault()`
path from slice 02 is **deleted**; all saves now go through
`BoomerangEditor.saveBoomerang()`.

### Editor screen layout

```
┌────────────────────────────────────────────────┐
│  ←                                  [ ✓ ]      │   top bar (back, save)
├────────────────────────────────────────────────┤
│                                                │
│              ┌──────────────────┐              │
│              │                  │              │
│              │     preview      │              │
│              │  (loops with     │              │   ~75% height
│              │   chosen dir.)   │              │
│              │                  │              │
│              └──────────────────┘              │
│                       3.2s                     │   live output-duration
├────────────────────────────────────────────────┤
│                                                │
│        Select video direction                  │   tab content panel
│                                                │   ~20% height
│       [▶▶]   [◀◀]   [▶◀]   [◀▶]                │
│       Fwd    Rev   F→R    R→F   ← labels       │
│                                                │
├────────────────────────────────────────────────┤
│                  [ >> ]                        │   tab bar (~5%)
│                  Dir.                          │   one icon only
└────────────────────────────────────────────────┘
```

- Selected direction chip is `NeonCoral → NeonPurple` gradient, white icon;
  others are glassmorphic outlines, `NeonPurple` icon.
- Chip hit-target ≥ 44 dp (Material accessibility).
- Label below each chip uses caption typography per PRD §3.
- The "3.2s" indicator below the preview now shows the **output duration**:
  `cycle_ms × reps / speed`, where `cycle_ms = trimDuration` for `FWD`/`REV`
  and `2 × trimDuration` for `F→R`/`R→F`. With `reps=1, speed=2`, a 5 s trim
  in `F→R` produces 5 s output.

### Reverse preview — decision LOCKED

**Source of truth:** [`RESEARCH-reverse-video.md`](./RESEARCH-reverse-video.md)
(verified May 2026 against developer.android.com, the androidx/media GitHub,
and the FFmpegKit retirement announcement).

**The problem:** ExoPlayer cannot natively play a video in reverse. The Forward
and Forward→Reverse modes are easy (forward playback with looping). The Reverse
and Reverse→Forward modes need reversed frames.

**Decision:** Reuse the cached output of the **`VideoReverser`** class
introduced in slice 02. The cache key is `<source-abs-path>_<trimStart>_<trimEnd>`,
so the moment the user enters the editor with the default mode
`FORWARD_THEN_REVERSE`, the reversed half is already being (or has already
been) generated for the *render* path; the preview just consumes the same
file. First switch to a reverse-containing direction in a session: shimmer for
0.5 – 3 s while the reverser finishes (latency scales with trim length per
RESEARCH §5). Subsequent direction switches: instant.

This is a deliberate change from "build a separate preview-only reverser" —
sharing the cache between preview and render is what makes reference
Boomerang feel instant after the first generation, and saves us ~250 lines
of duplicated MediaCodec plumbing.

**Why we landed here:**
- Media3 1.10.x has no built-in reverse effect (verified — see RESEARCH §1).
- FFmpegKit is retired and unsuitable (verified — see RESEARCH §2).
- The hand-rolled two-pass MediaCodec approach is the standard, working,
  documented Android path (RESEARCH §3).
- Sharing one cache between preview and render is the only way to avoid
  doing the work twice — and the work is the expensive part.

**Rejected alternatives** (details in RESEARCH §4): in-memory frame buffers
(OOM on >3 s clips), Media3 custom `Effect` (shaders are spatial, not
temporal), OpenGL shader reversal (same reason), FFmpegKit / custom FFmpeg
build (license / size / patent exposure / maintenance burden), cloud SDKs
(violates on-device principle), commercial SDKs (license conflict with
Apache 2.0).

### Direction-aware live preview implementation

For Forward only:
- ExoPlayer with `MediaItem` clipped to `[trimStart, trimEnd]`,
  `repeatMode = REPEAT_MODE_ALL`. Free, native.

For everything else (Reverse / Forward→Reverse / Reverse→Forward — three of
the four modes need the reversed file):
- On editor entry, the ViewModel **eagerly** calls
  `viewModel.ensureReversedSegment()` regardless of which direction is
  selected, because the default is `FORWARD_THEN_REVERSE` and 95% of users
  will end up needing the reversed file anyway. The hit is the same whether
  it happens now or on first interaction.
- `ensureReversedSegment()` calls `videoReverser.reverse(source, trimStart, trimEnd)`
  which is idempotent — slice 02's `VideoProcessor` already calls the same
  function for the default render, so if the user trimmed and saved a default
  boomerang before opening the editor, the cache is already hot. (This is
  rare — slice 02's flow is `Trim → NEXT → default render → camera`, so the
  editor never sees that path. The "hot cache" case arises when the user
  re-edits an existing raw from the gallery in slice 07.)
- While the reverser is running, overlay a small `CircularProgressIndicator`
  on the preview with "Preparing reverse…" caption (`NeonPurple` 80% scrim).
- On completion: store the reversed file in `EditorTabState.reversedFile`.
- Build the preview via `ConcatenatingMediaSource2` (or Media3's preview
  `Composition` API — open implementation detail, both are viable):
  - `FORWARD`              → `[trimmed]`, looped.
  - `REVERSE`              → `[reversed]`, looped.
  - `FORWARD_THEN_REVERSE` → `[trimmed, reversed]`, looped.
  - `REVERSE_THEN_FORWARD` → `[reversed, trimmed]`, looped.
- Apply the 1-frame seam offset (`setStartPositionMs(frameDurationMs)`) on
  the second item of two-clip compositions to drop the duplicate seam frame
  (parent doc §6.4).

## Technical deltas

### `OpenRangUiState.kt`

```kotlin
data class BoomerangEditor(val source: EditorSource, val trim: TrimWindow) : OpenRangUiState

data class TrimWindow(val startMs: Long, val endMs: Long)
```

`Trim`'s `NEXT` handler in the ViewModel constructs `TrimWindow(trimStart, trimEnd)`
and posts `BoomerangEditor(source, trim)`.

### `OpenRangViewModel.kt`

- Add `editorTabState: StateFlow<EditorTabState>`:
  ```kotlin
  data class EditorTabState(
      val mode: BoomerangMode = BoomerangMode.FORWARD_THEN_REVERSE,
      val reversedFile: File? = null,        // populated when reverse preview ready
      val isReversedFileLoading: Boolean = false,
  )
  ```
- Add mutators: `updateMode(mode: BoomerangMode)`, `ensureReversedSegment()`,
  `saveBoomerang()`, `discardEditor()`.
- `saveBoomerang()` calls `VideoProcessor.renderBoomerang(...)` with chosen
  mode, `speed = 2.0f` (hard-coded for this slice), `reps = 1` (hard-coded for
  this slice).
- Delete `saveBoomerangDefault()` from slice 02 — superseded.

### `media/VideoProcessor.kt`

Update the implementation to honor `mode` parameter (was hard-coded to
`FORWARD_THEN_REVERSE` in slice 02). The four cases:

| mode                       | Composition items                |
|----------------------------|----------------------------------|
| `FORWARD`                  | `[trimmed]`                      |
| `REVERSE`                  | `[reversed]`                     |
| `FORWARD_THEN_REVERSE`     | `[trimmed, reversed]` (drop 1 frame seam) |
| `REVERSE_THEN_FORWARD`     | `[reversed, trimmed]` (drop 1 frame seam) |

For modes that need a reversed half, call
`videoReverser.reverse(source, trimStart, trimEnd)` — which is the same call
the editor's live preview makes. If the preview already populated the cache
(common case), this resolves instantly. `VideoProcessor` does NOT keep its own
copy of the reversed file; the cache is the single source of truth.

For `FORWARD` mode, `videoReverser.reverse(...)` is not called at all — saves
the user 0.5 – 3 s of unnecessary work and a few hundred MB of intermediate
disk I/O.

Continue to strip audio and apply `SpeedChangingVideoEffect(2.0f)` (hard-coded
speed still for this slice). Seam handling: drop the first frame of the second
clip via `setStartPositionMs(frameDurationMs)` — at 30 fps that's ~33 ms.

### `ui/BoomerangEditorScreen.kt` (new)

- Compose layout per the ASCII mock.
- Hosts the preview ExoPlayer with direction-aware bind logic. On `mode` change:
  if a reverse file is needed and not yet ready, call
  `viewModel.ensureReversedSegment()` and show the loading shimmer; else rebind
  the player with the appropriate composition.
- Direction chips: 4 buttons in a row, single-select. Selected chip emits
  `viewModel.updateMode(mode)`. Selected chip styling per UX section above.
- Save checkmark: `viewModel.saveBoomerang()`. Disabled while
  `editorTabState.isReversedFileLoading` or `uiState == Processing`.
- Back: confirms "Discard changes?" if the user changed `mode` from default,
  else returns silently to `Trim`.

### `MainActivity.kt`

Route `BoomerangEditor` to `BoomerangEditorScreen(...)`.

## Testing plan

### Unit tests

- `OpenRangViewModelTest`:
  - `Trim.NEXT` → `BoomerangEditor(source, trim)` with the trim window
    preserved.
  - `updateMode(REVERSE)` sets `editorTabState.mode` and triggers
    `ensureReversedSegment()` (verifiable via a fake `VideoProcessor` that
    records calls).
  - `updateMode(FORWARD)` does NOT trigger reverse generation.
  - `saveBoomerang()` calls `VideoProcessor.renderBoomerang(... mode=<chosen>,
    speed=2.0f, reps=1 ...)`.
  - Back from editor preserves the trim window when returning to `Trim`.
- `VideoProcessorTest` (JVM with a small fixture mp4 in `src/test/resources/`):
  - For each of the 4 modes, render produces a file with the expected duration
    within ±1 frame.
  - Seam frame is dropped (assert frame count is `2N - 1`, not `2N`, for the
    F→R / R→F cases).

### Instrumented tests

- `BoomerangEditorScreenTest`: 4 chips render; tapping a chip selects it and
  deselects others; save checkmark is disabled while reversed-file loading;
  back returns to Trim.
- End-to-end: capture 3 s → trim to 2 s → editor opens → pick `REVERSE` →
  loading shimmer appears on preview → resolves → preview plays reversed →
  save → file exists with reversed playback.

### Manual QA

- All four directions produce visually correct previews (forward looks
  forward, reverse looks reverse, etc.).
- First switch to Reverse on a 3 s clip: shimmer < 2 s on Pixel 10 Pro Fold.
- Second switch to a different reverse-containing direction: instant (cached
  reversed file reused).
- Save → file in `filesDir/boomerangs/` plays the chosen direction.
- Back from editor preserves trim window on the Trim screen (drag positions
  still where the user left them).
- Screenshot of the Direction tab with each of the 4 chips selected (one
  screenshot per chip, or one collage) attached to the PR.

## Acceptance criteria

- [ ] `assembleDebug` + `assembleRelease`: BUILD SUCCESSFUL, exit 0, zero `e:`.
- [ ] `testDebugUnitTest`: 0 failures; all unit tests present.
- [ ] `connectedDebugAndroidTest`: 0 failures.
- [ ] `zipalign -c -P 16 -v 4 …` on release APK shows `(OK)` (Lesson 011).
- [ ] Editor and `VideoProcessor` share the **same** `VideoReverser` instance
      via DI in the ViewModel `Factory` — verify by snapshotting cache file
      `lastModified` across an editor-preview-then-save flow on the same trim
      and confirming no re-generation.
- [ ] App launched on emulator AND Pixel 10 Pro Fold; all 4 directions
      manually verified; reverse-shimmer timing recorded for a 3 s, a 10 s,
      and a 30 s trim (calibration data — slot into the perf estimates
      table in [`RESEARCH-reverse-video.md`](./RESEARCH-reverse-video.md) §5
      via a follow-up commit if they meaningfully diverge from the back-of-
      envelope numbers there).
- [ ] No `Color(0x…)` literal violates the 8-hex-digit rule (Lesson 001).
- [ ] All Flow collection in editor uses `collectAsStateWithLifecycle()`
      (Lesson 002).
- [ ] All repository / DataStore writes wrapped in `try / catch (IOException)`
      (Lesson 003).
- [ ] No `Context` parameter on any `OpenRangViewModel` method (Lesson 004).
- [ ] PR description states reverse-shimmer max observed latency on Pixel 10
      Pro Fold, and notes the behavior on lowest-end test device (if any).
