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
   `⚡`). The bar now shows **three** icons — Direction, Speed, and a **disabled
   Reps stub** (`⏱`) that lands in slice 05 — so the tab bar reaches its final
   width this slice and slice 05 only enables an existing icon (no reflow).
2. **Horizontal speed slider** in the tab's content panel — drag with finger,
   range `0.25× – 3.0×`, default `2.0×`. **Minimalist visual** matching the
   reference screenshot (comet / motion-trail thumb on a filled track) — *no*
   end labels and *no* floating value label. Current speed is conveyed by the
   live duration chip and by haptic detents (see Slider behavior).
3. **Live preview** that re-binds to the new playback speed instantly.
4. **Render pipeline** that honors the chosen speed instead of the hard-coded
   2.0×.

> **Design deviations from the original draft (decided 2026-05-29).** The
> screenshot wins over the earlier written spec on two points, and haptics grew:
> (a) **slider is label-free** (comet thumb, no `0.25×/3.0×` end labels, no
> floating `"1.75×"` bubble) — feedback comes from the duration chip + haptics,
> not text; (b) **tab bar ships 3 icons** (Direction, Speed, disabled Reps stub)
> rather than 2; (c) **haptic detents fire at both 1.0× and 2.0×** (find "normal"
> *and* the default by feel) — this is the label-free slider's substitute for a
> value readout, so the user can return to known speeds. A `stateDescription`
> still announces the numeric speed to TalkBack (accessibility is not optional —
> it's just invisible to sighted users and doesn't affect the screenshot match).

## Scope

### In scope
- Tab bar grows from 1 icon (Direction) to 3 slots (Direction, Speed, **disabled
  Reps stub**). Only Direction and Speed are interactive this slice; the Reps
  `⏱` icon renders dimmed (~40% alpha), is non-clickable, and carries no active
  pill — it exists so slice 05 enables it without a layout shift.
- New Speed tab content: horizontal slider, range 0.25× – 3.0×, default 2.0×.
  **No floating value label and no end labels** — minimalist comet thumb per the
  reference screenshot. A `stateDescription` exposes the numeric speed to
  TalkBack only.
- `EditorTabState.speed: Float` and `EditorTabState.activeTab: EditorTab` added.
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
  add snap if user testing finds the slider too fiddly. (Haptic detents at 1.0×
  and 2.0× are the lighter-weight substitute shipped this slice — they let the
  user *feel* the two key speeds without forcing them.)
- A visible speed readout (floating label, end labels, or a "1.75×" chip). The
  slider is deliberately label-free to match the screenshot; revisit only if QA
  finds users can't tell what speed they've set.
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
│   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━☄ ───────     │   comet thumb, no labels
│                                                │
├────────────────────────────────────────────────┤
│        [ ≫ ]    [ ⚡ ]    ( ⏱ )                │   tab bar (~5%)
│        Dir.     Spd      Reps(off)             │   3 slots; Reps disabled
└────────────────────────────────────────────────┘
```

### Tab bar behavior

- Tab bar is centered horizontally; icons are 28 dp, spaced 32 dp apart, in
  three slots: Direction (`≫`), Speed (`⚡`), Reps (`⏱`, **disabled this slice**).
- Active tab has a darker pill background (`DeepCharcoal` 60%) with `NeonPurple`
  icon. Inactive interactive tabs use `GlassWhite` 20% pill background with white
  icon.
- **Reps stub:** rendered at ~40% alpha, no pill, `enabled = false` (no
  `clickable`), `selected = false`. It is *not* part of the pill rotation and
  taps are ignored — it only reserves its slot so slice 05 enables it without
  reflowing the bar.
- Tap an interactive inactive icon → switches the content panel above.
  Animations: 200 ms fade-cross between tab content panels.

### Slider behavior

- Range: `0.25..3.0`, default `2.0`.
- **Minimalist visual, matching the reference screenshot:** a comet /
  motion-trail thumb riding a 4 dp track — `NeonPurple` filled portion to the
  left of the thumb, `GlassWhite` 30% to the right. **No** `0.25×/3.0×` end
  labels and **no** floating value bubble.
- **No visible speed readout.** Current speed is conveyed by (a) the live
  duration chip above the tab bar and (b) haptic detents. A `stateDescription`
  on the slider semantics announces the numeric speed (e.g. `"1.75 times speed"`)
  to TalkBack — required for an unlabeled continuous slider (ANDROID_STANDARDS
  §7), invisible to sighted users.
- **Haptic detents at both 1.0× and 2.0×.** Fire a short tick when the dragged
  value *crosses* either threshold (debounced so a single drag-through fires once
  per crossing, not per frame). 1.0× = "normal speed"; 2.0× = the default /
  classic-boomerang sweet spot — together they let the user return to the two
  known speeds by feel, standing in for the omitted value label. Exact API
  (`View.performHapticFeedback` vs Compose `LocalHapticFeedback`) verified
  against `developer.android.com` at implementation time — do not assume
  `CLOCK_TICK` is reachable from Compose's `HapticFeedbackType`.
- Tick marks (optional polish, not required): not shipped — the comet thumb keeps
  the track clean.

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

### `OpenLoopViewModel.kt`

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
`OpenLoopViewModel.saveBoomerang()` (pass `state.speed` instead of `DEFAULT_SPEED`).
Audio remains stripped.

### `ui/BoomerangEditorScreen.kt`

- Replace the single centered `Text("≫")` tab bar with a 3-slot row: Direction
  (`≫`) and Speed (`⚡`) interactive with active-tab pill switching driven by
  `editorTabState.activeTab`; Reps (`⏱`) a dimmed, non-clickable stub.
- `BoomerangEditorContent` stays stateless (mirrors `TrimScreenContent`): thread
  new params `speed: Float`, `activeTab: EditorTab`, `onSpeedChange: (Float) -> Unit`,
  `onSwitchTab: (EditorTab) -> Unit` from `BoomerangEditorScreen`.
- Add `SpeedTabContent` composable: hosts a custom-drawn slider (comet thumb,
  `NeonPurple`-filled track, `GlassWhite` 30% remainder) — **no value label, no
  end labels**. Bind to `speed`; on drag, call `onSpeedChange(it)`. Add a
  `stateDescription` to the slider semantics announcing the numeric speed.
- Debounce slider emissions to ~50 ms before calling
  `player.setPlaybackSpeed(...)`. (Use `snapshotFlow { speed }.debounce(50.milliseconds)`
  inside a `LaunchedEffect`.)
- Haptic detents: track the previous speed and, on each change, fire a tick when
  the value crosses 1.0× or 2.0×. Verify the haptics API against
  `developer.android.com` before wiring (likely `LocalView.current.performHapticFeedback`).
- The duration chip now reads `boomerangOutputDurationMs(..., speed = speed, ...)`
  (it already takes `speed`) so it updates live as the slider moves — this is the
  user's only *visual* speed feedback.
- Animate tab content cross-fade: `AnimatedContent` with 200 ms `fadeIn` +
  `fadeOut`.

### `MainActivity.kt`

No new routes — same `BoomerangEditor` destination, expanded content.

## Testing plan

### Unit tests

- `OpenLoopViewModelTest`:
  - `updateSpeed(1.5f)` updates `editorTabState.speed = 1.5f`.
  - `updateSpeed(5.0f)` clamps to `3.0f`; `updateSpeed(0.1f)` clamps to `0.25f`.
  - `switchTab(EditorTab.SPEED)` updates `editorTabState.activeTab`.
  - `saveBoomerang()` passes the current `speed` to `VideoProcessor`.
- `VideoProcessorTest`:
  - For each of `[0.25, 0.5, 1.0, 1.5, 2.0, 3.0]`, render produces output with
    duration ≈ `cycle_ms / speed` (±1 frame).

### Instrumented tests

- `BoomerangEditorScreenTest`:
  - Tab bar shows 3 slots; Speed is tappable and shows the slider; the Reps stub
    is present, dimmed, and **not** clickable (tap is a no-op, panel unchanged).
  - Slider thumb position reflects current speed; dragging updates
    `editorTabState.speed` and the slider's `stateDescription` (no visible label
    to assert).
  - Preview's `playbackSpeed` matches `editorTabState.speed` after debounce
    settles.
  - Output duration indicator updates as speed changes.
- End-to-end: capture 3 s → trim to 2 s → editor → switch to Speed tab → drag
  to 0.5× → preview plays slow → save → output file duration ≈ 8 s (2 s cycle
  × 2 for F→R × 1 rep / 0.5 speed = 8 s).

### Manual QA

- Drag slider through full range; preview speed changes smoothly with no jank.
- At 0.25×: preview is clearly slow-motion. At 3.0×: preview is clearly fast.
- Haptic detents felt at **both** 1.0× and 2.0× on Pixel 10 Pro Fold; each fires
  once per crossing (not repeatedly while dragging through).
- Save at three different speeds (0.5×, 1×, 2.5×); verify each output file
  plays at the expected speed in the gallery's `LoopingPreview`.
- Switch tabs (Direction ↔ Speed) several times; selections persist on each
  tab; tab content cross-fades cleanly. Tapping the disabled Reps stub does
  nothing.
- TalkBack on the slider announces the numeric speed (`stateDescription`).
- Screenshot of the Speed tab with the slider mid-drag attached to the PR (no
  value label — confirm the comet thumb + live duration chip read clearly).

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
- [ ] No `Context` parameter on any `OpenLoopViewModel` method (Lesson 004).
- [ ] PR description states the longest output duration observed in QA (so we
      know we're still under the 60 s hard cap from parent doc D-5).
