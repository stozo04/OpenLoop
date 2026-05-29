# Slice 03 — Tabbed Editor + Direction tab

> **Branch:** `feature/boomerang-slice-03-direction-tab` (PR #25)
> **Depends on:** slice 02 (Trim screen + default-render Save).
> **Unblocks:** slices 04 (Speed) and 05 (Reps).
>
> **Status: built.** This doc has been reconciled to the **shipped** implementation (PR #25); where the
> original spec and the code diverged, the code wins and is described here. It moves to
> `docs/completed/boomerang-rollout/` when PR #25 merges.

---

## Problem

After slice 02 the user can capture, trim, and save a default boomerang — but it is *always*
`FORWARD_THEN_REVERSE` at 2× for 1 rep. There is no expression. This slice introduces the **tabbed
editor** and its first tab, **Direction**: after `NEXT` on the Trim screen the editor opens with the
trimmed boomerang already looping, and four chips let the user pick the loop direction before saving.

The bottom tab bar shows **only the Direction icon** in this slice; its full height is reserved so
Speed (slice 04) and Reps (slice 05) drop in without reflowing the layout.

## Scope

### In scope (as shipped)
- New `BoomerangEditor(source)` routed state — a **slim discriminator** like `Trim` (the trim window
  stays in the ViewModel's `editorState`; the editor's tab selections live in a new sibling
  `editorTabState`). *(The original spec sketched `BoomerangEditor(source, trim)` + a `TrimWindow` type;
  the shipped code keeps the established slim-discriminator-plus-sibling-flow pattern instead.)*
- The Trim screen's `NEXT` now opens `BoomerangEditor` (was: rendered the default boomerang and returned
  to camera). The slice-02 default-render-on-`NEXT` path is removed; **all saves now go through the
  editor's save checkmark** (`saveBoomerang()`).
- New `BoomerangEditorScreen`: preview (~75%), Direction tab content panel, one-icon tab bar (height
  reserved ~56 dp).
- Direction tab: 4 single-select chips — Forward `▶▶`, Reverse `◀◀`, Forward→Reverse `▶◀`,
  Reverse→Forward `◀▶`. Default `FORWARD_THEN_REVERSE`. Selected chip = `NeonCoral → NeonPurple`
  gradient, white glyph; unselected = `GlassWhite` fill + `GlassWhiteBorder`, `NeonPurple` glyph. Hit
  target ≥ 44 dp.
- Save checkmark (top-right, filled `NeonPurple` circle, white check): renders with the chosen direction
  at the still-hard-wired `speed = 2.0×`, `reps = 1`. Disabled while the reversed clip is generating.
- Live preview reflects the chosen direction (see "Reverse preview" below).

### Out of scope
- Speed control (slice 04), Reps control (slice 05), share sheet (slice 06), gallery edit (slice 07).
- More than one icon in the tab bar.

## UX deltas

### State transitions (as shipped)

```
Trim             tap NEXT  →  BoomerangEditor(source)   (was: render default → ReadyToCapture)
BoomerangEditor  tap ←     →  Trim                       (preserves the trim selection)
BoomerangEditor  tap ✓     →  Processing
Processing       success   →  ReadyToCapture (snackbar)  (same as slice 02)
Processing       failure   →  BoomerangEditor            (direction selection preserved, "Failed" snackbar)
```

### Editor screen layout

```
┌────────────────────────────────────────────────┐
│  ←                                  [ ✓ ]      │   top bar (back, save)
├────────────────────────────────────────────────┤
│              ┌──────────────────┐              │
│              │     preview      │              │   ~75% — loops with chosen direction
│              │  (loops w/ dir.) │              │
│              └──────────────────┘              │
│                       3.2s                     │   live output-duration label
├────────────────────────────────────────────────┤
│        Select video direction                  │   Direction tab content
│       [▶▶]   [◀◀]   [▶◀]   [◀▶]                │
│       Fwd    Rev   F→R    R→F                   │
├────────────────────────────────────────────────┤
│                  [ ≫ ]                         │   tab bar — one icon, ~56 dp reserved
└────────────────────────────────────────────────┘
```

- Output-duration label = `boomerangOutputDurationMs(mode, trim, speed=2×, reps=1)` (pure helper):
  `trimDuration` for `FWD`/`REV`, `2 × trimDuration` for the two-part modes, divided by speed. A 5 s
  trim in `F→R` shows `5.0s`.
- Back: a "Discard changes?" confirm dialog only when the user changed the direction off the default;
  otherwise back returns silently to Trim (Lesson 015 — gate the `BackHandler`, don't always-intercept).

### Reverse preview — as shipped

ExoPlayer can't reverse natively, so reverse-containing previews need the pre-rendered reversed file.
The editor reuses the slice-02 `VideoReverser` **through the `VideoProcessor`** (see "shared cache"
below), then builds the looping preview as an **ExoPlayer playlist** — `setMediaItems(...)` +
`REPEAT_MODE_ALL`, rebuilt only when the direction / reversed-file / trim changes:

- `FORWARD`              → `[trimmed]`
- `REVERSE`              → `[reversed]`
- `FORWARD_THEN_REVERSE` → `[trimmed, reversed]` (seam frame dropped off the 2nd clip)
- `REVERSE_THEN_FORWARD` → `[reversed, trimmed]` (seam frame dropped off the 2nd clip)

*(The original spec left this open between `ConcatenatingMediaSource2` and a preview `Composition`/
`CompositionPlayer`. The shipped code uses the plain ExoPlayer playlist — simplest, and the seam +
direction match the render for `reps = 1`. A constant ~33 ms preview seam offset is used; the export
computes the source's exact frame duration.)*

On editor entry (and on any switch to a reverse-containing direction) the ViewModel eagerly calls
`ensureReversedSegment()`. While the reversed clip is generating, a **"Loopifying…"** glass overlay
covers the preview and Save is disabled. The generation is serialized — once the reversed file is ready
or in flight, repeated chip taps are no-ops (the trim is fixed for the session).

## Technical deltas (as shipped)

### `OpenRangUiState.kt`

```kotlin
data class BoomerangEditor(val source: EditorSource) : OpenRangUiState

data class EditorTabState(
    val mode: BoomerangMode = BoomerangMode.FORWARD_THEN_REVERSE,
    val reversedFile: File? = null,            // the cached reversed clip the preview plays
    val isReversedFileLoading: Boolean = false,
)
```

There is **no** `TrimWindow` type; the trim window is read from the existing `TrimState` in
`editorState`.

### `OpenRangViewModel.kt`

- `editorTabState: StateFlow<EditorTabState>` (sibling to `editorState`).
- `onNextFromTrim()` — resets `editorTabState`, posts `BoomerangEditor(source)`, calls
  `ensureReversedSegment()`. (No longer renders.)
- `updateMode(mode)` — sets the direction; triggers `ensureReversedSegment()` for reverse-containing modes.
- `ensureReversedSegment()` — serialized; calls `videoProcessor.ensureReversed(...)`, stores the result
  in `editorTabState.reversedFile`, drives `isReversedFileLoading`.
- `saveBoomerang()` — promotes scratch→raw, renders the chosen `mode` (speed `DEFAULT_SPEED` = 2.0f,
  reps `DEFAULT_REPS` = 1) **from the scratch path** (so the render hits the same reverse cache the
  preview filled), registers the boomerang, discards the scratch, emits `Saved`. On failure → back to
  `BoomerangEditor`, emit `Failed`, selection preserved.
- `backToTrim()` — returns to `Trim`, preserving the trim selection.

### `media/VideoProcessor.kt`

- **`ensureReversed(source, trimStartMs, trimEndMs)`** added to the interface — `Media3VideoProcessor`
  delegates to its `VideoReverser`. The editor preview and the render both call through the **same
  processor instance**, so the reversed clip for a given (source path, trim) is generated **once** and
  reused (satisfies the "shared cache / no re-generation" goal by construction — they literally can't
  diverge).
- **Seam fix (Lesson 018):** the 1-frame seam drop now follows **sequence position**, extracted into a
  pure `boomerangSequence(mode, repetitions)` in `media/BoomerangSequence.kt`. Slice 02 already handled
  all four modes; the real change here is fixing the latent seam bug that the identity-based drop ("drop
  the reversed clip") had for `REVERSE` (lost a real frame) and `REVERSE_THEN_FORWARD` (dropped the
  wrong clip).
- Speed effect is `SpeedChangeEffect(speed)` (Media3 1.10.1 — **not** `SpeedChangingVideoEffect`, which
  isn't in this version). Audio stripped.

### `ui/BoomerangEditorScreen.kt` (new)

- `BoomerangEditorScreen(viewModel)` (thin) + `BoomerangEditorContent(...)` (hoisted, testable).
- Direction-aware ExoPlayer playlist preview; "Loopifying…" overlay while loading.
- 4 direction chips; one-icon reserved tab bar; gated discard `BackHandler`; save checkmark disabled
  while loading.

### `MainActivity.kt`

Routes `BoomerangEditor` → `BoomerangEditorScreen` in the exhaustive `OpenRangNavHost` `when`
(no `else` — Lesson 014).

## Testing plan (as shipped)

### Unit tests (JVM)
- `BoomerangSequenceTest` — the pure seam-by-position rule (all 4 modes × reps, incl. the two slice-02
  regression cases) **and** `boomerangOutputDurationMs`.
- `OpenRangViewModelTest` — `onNextFromTrim` opens the editor + eagerly generates the reversed clip;
  `updateMode(FORWARD)` generates nothing; the reversed clip is generated once and reused across
  reverse-containing modes; `backToTrim` preserves the trim; `saveBoomerang` renders the chosen mode /
  saves / emits `Saved`; render failure returns to the editor with the direction preserved.

> Note: there is **no** JVM `VideoProcessorTest` with a `src/test/resources` fixture (the original spec
> asked for one). `Media3VideoProcessor` drives a `Transformer` (Looper + MediaCodec + GL) that does not
> run on a pure JVM — the JVM-testable piece is the extracted `boomerangSequence`/duration math; the
> encode is covered by the instrumented smoke path. (Lesson 008/017.)

### Instrumented tests
- `BoomerangEditorScreenTest` — 4 chips render; tapping selects (single-select); selected chip reports
  `selected` semantics; Save disabled while loading; "Loopifying…" shimmer shown; gated back dialog;
  duration label per mode. (No mockk — Lesson 017.)

### Manual QA (Pixel 10 Pro Fold — owner)
- All four directions produce visually correct, **seam-clean** previews (especially `REVERSE` — no lost
  frame — and `REVERSE_THEN_FORWARD` — no freeze at the turn).
- **#1658 check:** `F→R` and `R→F` both halves play at 2× (the per-item-effect risk).
- Reverse correctness/orientation (slice-02 device debt): reversed half reads reversed and upright.
- First switch to a reverse-containing direction shimmers, then is instant on later switches.
- Save → file in `filesDir/boomerangs/` plays the chosen direction; back preserves the trim.

## Acceptance criteria

- [x] `assembleDebug` + `assembleRelease`: BUILD SUCCESSFUL, exit 0, zero `e:`.
- [x] `testDebugUnitTest`: 0 failures.
- [x] `lintDebug`: no new issues; `compileDebugAndroidTestKotlin` compiles.
- [ ] `connectedDebugAndroidTest`: 0 failures (owner, on device).
- [ ] `zipalign -c -P 16 -v 4 …` on release APK shows `(OK)` (Lesson 011).
- [ ] App launched on the Fold; all 4 directions + the #1658 check manually verified; screenshot attached.
- [x] Editor preview and the render share the **same** reverser via `VideoProcessor.ensureReversed`
      (one processor instance → one trim-keyed cache; no re-generation by construction).
- [x] No `Color(0x…)` literal violates the 8-hex rule (Lesson 001); editor Flow collection uses
      `collectAsStateWithLifecycle()` (Lesson 002); no `Context` on any ViewModel method (Lesson 004).
