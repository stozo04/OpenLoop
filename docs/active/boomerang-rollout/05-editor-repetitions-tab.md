# Slice 05 — Repetitions tab

> **Branch:** `feature/boomerang-slice-05-reps-tab`
> **Depends on:** slice 04 (Speed tab).
> **Unblocks:** slice 06 (Share sheet).

---

## Problem

After slice 04 the editor has Direction and Speed tabs, but the boomerang
always plays one cycle of the chosen mode. For social shares, the boomerang
often feels better when the cycle plays 2–4 times in one file — the receiver
sees the loop a few times without needing to manually replay.

This slice introduces:

1. **Reps tab** as the third (and final) tab in the bottom tab bar (icon =
   stopwatch `⏱`).
2. **Four circle buttons** for 1 / 2 / 3 / 4 repetitions. Default = 1.
3. **Live preview** that loops the cycle N times before repeating (where N is
   the chosen reps).
4. **Render pipeline** that appends the cycle N times in the output composition.
5. **Soft warning** when projected output duration exceeds 30 s (per parent D-5).

## Scope

### In scope
- Tab bar grows from 2 icons to 3 icons (Direction, Speed, Reps).
- New Reps tab content: row of 4 circle buttons labeled 1, 2, 3, 4. Selected
  button is filled (`NeonCoral → NeonPurple` gradient); unselected are
  glassmorphic outlines with the number in `NeonPurple`.
- `EditorTabState.repetitions: Int` (1..4) added.
- Render pipeline: `Composition` appends the cycle `reps` times.
- Live preview: instead of `repeatMode = REPEAT_MODE_ALL` on a single cycle,
  build a composition of `reps` cycles and loop that. (See "Preview
  implementation" below — there's a small subtlety.)
- Output duration indicator updates with reps applied.
- Soft warning chip ("Long boomerang — N s") appears above the tab bar when
  output duration > 30 s. Save is **still allowed** in this range.
- Hard error when output > 60 s: save button disabled with explanatory tooltip.

### Out of scope
- Reps > 4. Reference Boomerang caps at 4; we match for v1.
- Per-cycle settings (e.g., reps 1 fast, reps 2 slow). Out of v1 scope.
- "Infinite loop" reps option (would need a different output format —
  animated GIF, MP4 isn't well-suited to "infinite" repeat at the file level).

## UX deltas

### Editor screen layout (additions)

```
┌────────────────────────────────────────────────┐
│  ←                                  [ ✓ ]      │
├────────────────────────────────────────────────┤
│              ┌──────────────────┐              │
│              │     preview      │              │   ~75%
│              │  (loops N cycles │              │
│              │   before repeat) │              │
│              └──────────────────┘              │
│                       6.4s                     │   live; ⚠ chip if > 30s
│           [ ⚠ Long boomerang ]                 │   appears when > 30 s
├────────────────────────────────────────────────┤
│                                                │
│         Select the number of repetitions       │
│                                                │
│        ( 1 )   ( 2 )   ( 3 )   ( 4 )           │   circle buttons
│         ●       ○       ○       ○              │
│                                                │
├────────────────────────────────────────────────┤
│        [ >> ]    [ ⚡ ]    [ ⏱ ]               │   tab bar
│        Dir.     Spd      Reps                  │
└────────────────────────────────────────────────┘
```

### Reps buttons

- Layout: row of 4 circular buttons, evenly spaced, centered in panel.
- Diameter: 56 dp. Hit-target ≥ 56 dp (Material `Touch Target` recommendation).
- Selected: filled gradient `NeonCoral → NeonPurple`, white digit, 1.05× scale
  on press.
- Unselected: glass surface (`GlassWhite` 20% fill, `GlassWhiteBorder` 30% 1.5 dp
  border), `NeonPurple` digit.
- Tap → immediate state change + 80 ms scale-pop animation.

### Long-boomerang warning

- Warning chip uses the `NeonCoral` tone (the "attention" color in the palette).
- Copy: "Long boomerang — Ns" where N is rounded to whole seconds.
- Position: directly below the live duration indicator, above the tab content
  panel. Slides in / out with a 200 ms vertical translate when crossing the
  30 s threshold.
- Save remains enabled in the 30–60 s range.

### Hard error (> 60 s)

- Save checkmark grays out and ripple is disabled.
- Long-press save → tooltip / toast: "Boomerang would be over 60 s. Reduce
  reps, increase speed, or shorten trim."
- The warning chip changes copy to "Too long — Ns" in `NeonCoral` 100% (more
  saturated) and turns into a tappable info chip that, when tapped, briefly
  flashes the three controls that can fix it (trim handles via slide-up
  highlight on the Trim screen → no, we're not on that screen — just visually
  pulse the Reps and Speed tab icons in the bar).

This is a defensive case — at default speed 2× and 4 reps, you need a trim of
≥ 7.5 s on F→R to cross 60 s (`2 × 7.5 × 4 / 2 = 30 s`). 0.25× speed makes it
much easier to hit. Document that this case is most common with slow speeds.

## Technical deltas

### `OpenRangViewModel.kt`

```kotlin
data class EditorTabState(
    val mode: BoomerangMode = BoomerangMode.FORWARD_THEN_REVERSE,
    val speed: Float = 2.0f,
    val repetitions: Int = 1,                 // NEW
    val activeTab: EditorTab = EditorTab.DIRECTION,
    val reversedFile: File? = null,
    val isReversedFileLoading: Boolean = false,
)

enum class EditorTab { DIRECTION, SPEED, REPETITIONS }  // expanded

fun updateRepetitions(reps: Int) {
    val clamped = reps.coerceIn(1, 4)
    _editorTabState.value = _editorTabState.value.copy(repetitions = clamped)
}

// derived output duration, exposed as a computed property
val EditorTabState.outputDurationMs: Long
    get() = ((cycleDurationMs * repetitions) / speed).toLong()

private val EditorTabState.cycleDurationMs: Long
    get() = when (mode) {
        FORWARD, REVERSE                              -> trim.durationMs
        FORWARD_THEN_REVERSE, REVERSE_THEN_FORWARD    -> 2 * trim.durationMs
    }
```

`saveBoomerang()` now passes `repetitions = state.repetitions` to
`VideoProcessor.renderBoomerang(...)`. Block save if `outputDurationMs > 60_000`.

### `media/VideoProcessor.kt`

Append the cycle to the `Composition` `repetitions` times. Reuse the same
`EditedMediaItem`s — Media3 supports multiple instances of the same item in a
composition without re-reading the source each time.

Seam between cycles: the *end* of one cycle and the *start* of the next can
also produce a visual freeze (one cycle ends on its last frame, the next
starts on the same first frame the previous one started on). Apply the same
1-frame drop trick at cycle boundaries that we use at the F→R seam in slice 03.

### `ui/BoomerangEditorScreen.kt`

- Render 3-icon tab bar; `EditorTab.REPETITIONS` is now reachable.
- `RepetitionsTabContent` composable: row of 4 circular buttons. Each button
  reads `editorTabState.repetitions` to decide its selected state.
- Live preview's `Composition` (built via Media3's preview API or by chaining
  MediaItems through `ConcatenatingMediaSource2`) now appends the cycle N
  times. Rebuild the player when `repetitions` changes.
  - Performance note: rebuilding the composition is cheap (no file re-encoding)
    but does interrupt playback for ~100 ms. Acceptable.
- Output duration label below preview reads `outputDurationMs / 1000.0` with
  one decimal place.
- Warning chip slides in/out at the 30 s threshold.
- Save button enabled/disabled based on the 60 s threshold.

## Testing plan

### Unit tests

- `OpenRangViewModelTest`:
  - `updateRepetitions(3)` → `editorTabState.repetitions == 3`.
  - `updateRepetitions(0)` clamps to 1; `updateRepetitions(7)` clamps to 4.
  - `outputDurationMs` math correctness for all 4 modes × edge speeds × reps.
  - `saveBoomerang()` is a no-op (and emits a "too long" event) when
    `outputDurationMs > 60_000`.
- `VideoProcessorTest`:
  - For `mode=F→R, trim=2 s, speed=1×, reps=3` → output duration ≈ 12 s
    (`2 × 2 × 3 / 1 = 12 s`).
  - For `mode=FWD, trim=1 s, speed=2×, reps=4` → output duration ≈ 2 s
    (`1 × 4 / 2 = 2 s`).

### Instrumented tests

- `BoomerangEditorScreenTest`:
  - Tab bar shows 3 icons; tapping Reps icon shows the 4 circle buttons.
  - Tapping `3` selects it and deselects `1`. Selection persists when
    switching tabs and returning.
  - Warning chip appears when (programmatically) `outputDurationMs` crosses
    30 s; save button disables when it crosses 60 s.
- End-to-end: capture 2 s → trim to 1.5 s → editor → F→R, 1× speed, 3 reps →
  save → output duration ≈ 9 s.

### Manual QA

- Each rep value (1, 2, 3, 4): preview loops the cycle that many times before
  repeating; output file plays the same.
- At default settings (F→R, 2×, trim 5 s, reps 4): output is 10 s — under
  warning threshold; no chip.
- At F→R, 0.5×, trim 4 s, reps 4: output is 64 s — save disables; tooltip
  appears; tab icons pulse on long-press of save.
- Switching tabs (Direction ↔ Speed ↔ Reps) preserves all selections.
- Screenshot of the Reps tab with `2` selected attached to the PR.

## Acceptance criteria

- [ ] `assembleDebug` + `assembleRelease`: BUILD SUCCESSFUL, exit 0, zero `e:`.
- [ ] `testDebugUnitTest`: 0 failures.
- [ ] `connectedDebugAndroidTest`: 0 failures.
- [ ] `zipalign -c -P 16 -v 4 …` on release APK shows `(OK)` (Lesson 011).
- [ ] App launched on emulator AND Pixel 10 Pro Fold; manual QA walked;
      screenshot attached.
- [ ] Long-boomerang warning chip verified at the 30 s and 60 s thresholds.
- [ ] No `Color(0x…)` literal violates the 8-hex-digit rule (Lesson 001).
- [ ] All Flow collection uses `collectAsStateWithLifecycle()` (Lesson 002).
- [ ] No `Context` parameter on any `OpenRangViewModel` method (Lesson 004).
- [ ] PR description includes the largest output file produced during QA
      (file size + duration) so we can sanity-check we're not creating
      multi-GB files.
