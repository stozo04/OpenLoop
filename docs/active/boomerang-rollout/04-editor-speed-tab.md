# Slice 04 — Speed tab

> **Branch:** `feature/boomerang-slice-04-speed-tab`
> **Depends on:** slice 03 (tabbed editor with Direction tab).
> **Unblocks:** slice 05 (Reps tab).

---

## Problem

After slice 03 the editor has a Direction tab and a save checkmark — but the
boomerang's playback speed is still hard-coded at 2.0×. That's a fine default
but not a feature. Users want to dial in slow-motion boomerangs (a kid running,
played at 0.25×) and fast pings (a high-five, played at 3×).

This slice introduces:

1. **Speed tab** as the second tab in the bottom tab bar (icon = lightning bolt
   `⚡`).
2. **Horizontal speed slider** in the tab's content panel — drag with finger,
   range `0.25× – 3.0×`, default `2.0×`.
3. **Live preview** that re-binds to the new playback speed instantly.
4. **Render pipeline** that honors the chosen speed instead of the hard-coded
   2.0×.

## Scope

### In scope
- Tab bar grows from 1 icon (Direction) to 2 icons (Direction, Speed).
- New Speed tab content: horizontal slider, range 0.25× – 3.0×, default 2.0×,
  with a value label that floats above the thumb showing current speed
  (e.g., "1.75×").
- `EditorTabState.speed: Float` added.
- ExoPlayer `setPlaybackSpeed(speed)` called on every slider change so the
  preview reflects the new speed live (this is native and free — no
  re-rendering).
- `saveBoomerang()` passes `speed = state.speed` to
  `VideoProcessor.renderBoomerang(...)` — replaces the hard-coded `DEFAULT_SPEED`.
  (The processor itself already applies the `speed` parameter; see the note below.)
- Output duration indicator above the tab bar updates live as speed changes
  (`cycle_ms × reps / speed`).

### Out of scope
- Pitch correction / audio handling beyond strip. Audio remains stripped on
  render per parent doc D-3.
- Per-slice speed within a single boomerang (e.g., first rep at 1×, second rep
  at 2×). Out of scope for the entire v1.
- Snap-to-preset speed values (e.g., 0.5× / 1× / 2×). Slider is continuous;
  add snap if user testing finds the slider too fiddly.
- Storing the user's last-used speed across captures. Each capture starts at
  the default 2.0×.

## UX deltas

### Editor screen layout (additions)

```
┌────────────────────────────────────────────────┐
│  ←                                  [ ✓ ]      │   top bar
├────────────────────────────────────────────────┤
│                                                │
│              ┌──────────────────┐              │
│              │     preview      │              │   ~75%
│              │   (now reflects  │              │
│              │   chosen speed)  │              │
│              └──────────────────┘              │
│                       1.6s                     │   live (output ms)
├────────────────────────────────────────────────┤
│                                                │
│        Slow down or speed up the video         │   tab title
│                                                │
│   0.25×  ●────────╪──────────────  3.0×        │   slider w/ value label
│                  "1.75×" (floating)            │
│                                                │
├────────────────────────────────────────────────┤
│           [ >> ]     [ ⚡ ]                    │   tab bar (~5%)
│           Dir.       Spd                       │
└────────────────────────────────────────────────┘
```

### Tab bar behavior

- Tab bar is centered horizontally; icons are 28 dp, spaced 32 dp apart.
- Active tab has a darker pill background (`DeepCharcoal` 60%) with `NeonPurple`
  icon. Inactive tabs use `GlassWhite` 20% pill background with white icon.
- Tap an inactive icon → switches the content panel above. Animations: 200 ms
  fade-cross between tab content panels.

### Slider behavior

- Range: `0.25..3.0`, default `2.0`.
- Visual: horizontal track 4 dp tall, `NeonPurple` filled portion to the left
  of the thumb, `GlassWhite` 30% to the right.
- Thumb: 24 dp filled circle, `NeonCoral`.
- Value label: floats 12 dp above the thumb while dragging; reads `"1.75×"`
  (2 decimal places for values 0.25–1.0; 2 decimal places for 1.0–3.0; trim
  trailing zeros). Hidden when not dragging.
- Tick marks (optional polish, not required): subtle marks at 0.5×, 1×, 1.5×,
  2×, 2.5× as discoverable reference points.
- Haptic tick at exactly 1.0× (Material `HapticFeedbackConstants.CLOCK_TICK`)
  so users find "normal speed" by feel.

### Live preview behavior

On every slider change (debounced to ~50 ms to avoid spamming `setPlaybackSpeed`):

```text
player.setPlaybackSpeed(state.speed)
```

That's it for Forward / Forward→Reverse modes. For Reverse-containing modes,
the cached reversed file in `EditorTabState.reversedFile` already plays
forward (just with reversed frames), so `setPlaybackSpeed` works there too.
**The pre-rendered reversed file is reused across speed changes** — speed is
a player-side effect, not a render-side one.

### Output duration indicator

Below the preview, the "Xs" indicator now uses the speed:

```
output_ms = (cycle_ms × reps) / speed
```

For `mode = F→R, reps = 1, speed = 1.75×, trim = 2.0 s`:
- `cycle_ms = 2 × 2000 = 4000`
- `output_ms = 4000 × 1 / 1.75 = 2285 ms` → display `"2.3s"`.

## Technical deltas

### `OpenRangViewModel.kt`

```kotlin
data class EditorTabState(
    val mode: BoomerangMode = BoomerangMode.FORWARD_THEN_REVERSE,
    val speed: Float = 2.0f,                  // NEW
    val activeTab: EditorTab = EditorTab.DIRECTION,   // NEW
    val reversedFile: File? = null,
    val isReversedFileLoading: Boolean = false,
)

enum class EditorTab { DIRECTION, SPEED }     // REPS arrives in slice 05

fun updateSpeed(speed: Float) {
    val clamped = speed.coerceIn(0.25f, 3.0f)
    _editorTabState.value = _editorTabState.value.copy(speed = clamped)
}

fun switchTab(tab: EditorTab) {
    _editorTabState.value = _editorTabState.value.copy(activeTab = tab)
}
```

`saveBoomerang()` now passes `speed = state.speed` to
`VideoProcessor.renderBoomerang(...)`.

### `media/VideoProcessor.kt`

**No change needed.** `renderBoomerang(...)` already takes a `speed: Float` parameter
(since slice 02) and `Media3VideoProcessor` already applies it per clip via
`SpeedChangeEffect(speed)` — the constant-speed effect in Media3 1.10.1 (**not**
`SpeedChangingVideoEffect`, which isn't in this version). There is no `2.0f`
literal in the processor to replace. The only render-side change is in
`OpenRangViewModel.saveBoomerang()` (pass `state.speed` instead of `DEFAULT_SPEED`).
Audio remains stripped.

### `ui/BoomerangEditorScreen.kt`

- Render the tab bar with 2 entries instead of 1; active-tab pill switching
  driven by `editorTabState.activeTab`.
- Add `SpeedTabContent` composable: hosts a Compose `Slider` with custom
  thumb / track. Bind to `editorTabState.speed`; on `onValueChange`, call
  `viewModel.updateSpeed(it)`.
- Debounce slider emissions to ~50 ms before calling
  `player.setPlaybackSpeed(...)`. (Use `snapshotFlow { state.speed }.debounce(50.milliseconds)`
  inside a `LaunchedEffect`.)
- Animate tab content cross-fade: `AnimatedContent` with 200 ms `fadeIn` +
  `fadeOut`.

### `MainActivity.kt`

No new routes — same `BoomerangEditor` destination, expanded content.

## Testing plan

### Unit tests

- `OpenRangViewModelTest`:
  - `updateSpeed(1.5f)` updates `editorTabState.speed = 1.5f`.
  - `updateSpeed(5.0f)` clamps to `3.0f`; `updateSpeed(0.1f)` clamps to `0.25f`.
  - `switchTab(EditorTab.SPEED)` updates `editorTabState.activeTab`.
  - `saveBoomerang()` passes the current `speed` to `VideoProcessor`.
- `VideoProcessorTest`:
  - For each of `[0.25, 0.5, 1.0, 1.5, 2.0, 3.0]`, render produces output with
    duration ≈ `cycle_ms / speed` (±1 frame).

### Instrumented tests

- `BoomerangEditorScreenTest`:
  - Tab bar shows 2 icons; tapping Speed icon shows the slider.
  - Slider thumb position reflects current speed; dragging updates the value
    label and `editorTabState.speed`.
  - Preview's `playbackSpeed` matches `editorTabState.speed` after debounce
    settles.
  - Output duration indicator updates as speed changes.
- End-to-end: capture 3 s → trim to 2 s → editor → switch to Speed tab → drag
  to 0.5× → preview plays slow → save → output file duration ≈ 8 s (2 s cycle
  × 2 for F→R × 1 rep / 0.5 speed = 8 s).

### Manual QA

- Drag slider through full range; preview speed changes smoothly with no jank.
- At 0.25×: preview is clearly slow-motion. At 3.0×: preview is clearly fast.
- Haptic tick at 1.0× felt on Pixel 10 Pro Fold.
- Save at three different speeds (0.5×, 1×, 2.5×); verify each output file
  plays at the expected speed in the gallery's `LoopingPreview`.
- Switch tabs (Direction ↔ Speed) several times; selections persist on each
  tab; tab content cross-fades cleanly.
- Screenshot of the Speed tab with the slider mid-drag (value label visible)
  attached to the PR.

## Acceptance criteria

- [ ] `assembleDebug` + `assembleRelease`: BUILD SUCCESSFUL, exit 0, zero `e:`.
- [ ] `testDebugUnitTest`: 0 failures; new tests above present.
- [ ] `connectedDebugAndroidTest`: 0 failures.
- [ ] `zipalign -c -P 16 -v 4 …` on release APK shows `(OK)` (Lesson 011).
- [ ] App launched on emulator AND Pixel 10 Pro Fold; manual QA walked;
      screenshot attached.
- [ ] Speed slider clamps verified on both ends (no `0.1×`, no `5.0×` reaches
      the player or the renderer).
- [ ] No `Color(0x…)` literal violates the 8-hex-digit rule (Lesson 001).
- [ ] All Flow collection in editor uses `collectAsStateWithLifecycle()`
      (Lesson 002).
- [ ] No `Context` parameter on any `OpenRangViewModel` method (Lesson 004).
- [ ] PR description states the longest output duration observed in QA (so we
      know we're still under the 60 s hard cap from parent doc D-5).
