# Slice 01 — Variable-length capture (≤30 s)

> **Branch:** `feature/boomerang-slice-01-variable-length`
> **Depends on:** nothing (first slice).
> **Unblocks:** every other slice in this rollout.

---

## Problem

Today `startBurstCapture` records exactly 1.5 s of video before auto-stopping. That
length is too short to make a meaningful boomerang loop and gives the user no time
to react to what they're filming. The whole point of OpenLoop's camera-first wedge
("react to what you're witnessing without leaving for the system camera") falls
flat when the recording is shorter than a sneeze.

Variable-length capture is the foundation every other slice in this rollout
assumes. Trim only matters when the source is long enough to need trimming;
direction / speed / reps only matter on top of a clip the user actually likes.

## Scope

### In scope
- Replace the 1.5 s self-stop timer with a **user-controlled stop** and a **30 s
  hard cap** auto-stop.
- Shutter button becomes **tap-to-start / tap-to-stop**.
- Visible **progress ring** around the shutter showing elapsed time toward the
  30 s cap.
- 30 s cap behavior: recording auto-finalizes at 30.0 s exactly (treat as the
  same finalize path as a user tap).
- Unit and instrumented tests covering both stop paths (user tap, auto-cap).

### Out of scope (deferred)
- Anything boomerang-related (no editor, no render, no trim).
- Showing the captured clip in a new screen — the existing `LoopingPreview`
  transition stays as-is for this slice. The next slice replaces it.
- Visual countdown / haptic at the 5 s and 1 s "about to auto-stop" marks. Nice
  to have, but adds polish work that isn't required to ship the capability.
- Pause/resume mid-capture. Not in the rollout at all.

## UX deltas

### Shutter behavior

Today: tap shutter → records for 1.5 s → auto-stop.

After this slice:

```
ReadyToCapture       tap shutter → Recording (progress ring starts)
Recording            tap shutter again → finalize
Recording            elapsed ≥ 30.0 s   → finalize  (auto-cap)
```

The shutter button's visual state communicates the mode:

- **Idle:** filled circle, `NeonCoral → NeonPurple` gradient (current).
- **Recording:** thin progress ring overlaid on the button, sweeping clockwise
  from 12 o'clock; ring color `NeonCoral` 100%; button interior dimmed to
  indicate "tap to stop"; small square glyph (vs. dot) in the center.

### Capture-screen overlays

A small `00:00 / 00:30` countdown chip appears top-center while recording (glass
surface, monospaced digits, `DeepCharcoal` 80% over `GlassWhite` 20%). Hidden in
idle state.

### After-finalize routing

Unchanged for this slice — finalize still transitions to `LoopingPreview` so the
user can verify the longer clip played back. Slice 02 replaces this transition
with the auto-route to the Trim screen.

## Technical deltas

### `OpenLoopViewModel.kt`

- Remove the 1.5 s `delay()` self-stop in `startBurstCapture`. The
  `viewModelScope.launch { delay(1500); ... }` block goes away.
- Add `stopBurstCapture(cameraManager: CameraManager)`:
  ```text
  // pseudocode — actual signature mirrors startBurstCapture's idempotency guards
  if (_uiState.value != Recording) return
  cameraManager.stopRecording()
  // VideoRecordEvent.Finalize callback already wired in startBurstCapture
  // continues to drive the state to LoopingPreview as today.
  ```
- Add a coroutine that posts a `recordingElapsedMs: StateFlow<Long>` for the UI
  progress ring (re-emit every ~33 ms while in `Recording`; cancel on finalize).
- Add the 30 s auto-cap: another coroutine launched alongside the elapsed-time
  flow that calls `stopBurstCapture()` when elapsed crosses 30 000 ms.

### `CameraManager.kt`

`stopRecording()` already exists per the existing code. Confirm no signature
changes needed. Add a Kotlin-doc note that the function is now called from both
the user-tap path and the auto-cap path.

### `CameraScreen.kt`

- Shutter button: read `uiState` and `recordingElapsedMs`; switch glyph and
  draw the progress ring when `uiState == Recording`. Use `Canvas` for the
  ring (Compose `drawArc` with `startAngle = -90f`, `sweepAngle = elapsed/30000 * 360f`).
- Collect both flows via `collectAsStateWithLifecycle()` (Lesson 002).
- Wire shutter `onClick`: if idle, `viewModel.startBurstCapture(cameraManager)`;
  if recording, `viewModel.stopBurstCapture(cameraManager)`.
- Add the `00:00 / 00:30` countdown chip; format with
  `"%02d:%02d".format(elapsed / 60_000, (elapsed / 1000) % 60)`.

### `OpenLoopUiState.kt`

No additions. `Recording` and `ReadyToCapture` cover this slice's flow.

### Storage

No changes to `VideoStorageRepository`. The same `rawCaptureFile` (cacheDir
singleton) is used; the resulting clip is still copied to
`filesDir/videos/clip_<ts>.mp4` via the existing `saveFinalizedVideo`. (Per-UUID
scratch files arrive in slice 02.)

## Testing plan

### Unit tests (`app/src/test/`)

- `OpenLoopViewModelTest`:
  - `startBurstCapture` puts the state into `Recording` and starts emitting on
    `recordingElapsedMs`.
  - `stopBurstCapture` while in `Recording` calls `cameraManager.stopRecording()`
    exactly once and is a no-op outside `Recording`.
  - Virtual-time test (`runTest(mainDispatcherRule.testDispatcher)` per Lesson
    008): advancing past 30 000 ms triggers the auto-cap which calls
    `cameraManager.stopRecording()` exactly once even if no user tap occurs.
  - The double-tap race: a user tap arriving at the same scheduler tick as the
    auto-cap results in only one `stopRecording()` call.

### Instrumented tests (`app/src/androidTest/`)

- `CameraScreenTest` (Compose UI): shutter button toggles between idle and
  recording glyph on click; progress ring is visible only while recording;
  countdown chip text matches elapsed time within ±100 ms.
- End-to-end on emulator: tap shutter, wait 5 s, tap again → finalize, file
  exists, duration is 5 s ± 100 ms.
- End-to-end auto-cap: tap shutter, wait 30 s+, no second tap → finalize fires
  on the 30 s mark, file duration is 30 s ± 100 ms.

### Manual QA (per `DEFINITION_OF_DONE.md`)

- Tap shutter, immediately tap again → short clip (< 1 s) saves cleanly.
- Tap shutter, wait 30 s untouched → auto-stops; the resulting clip plays back
  in `LoopingPreview` without freezing.
- Front camera + back camera both honor the new behavior.
- App backgrounded mid-recording → recording finalizes (CameraX default
  behavior; just confirm no crash and the partial clip is saved).
- Screenshot of the recording state (shutter mid-progress, countdown chip
  visible) attached to the PR.

## Acceptance criteria

- [ ] `assembleDebug` + `assembleRelease`: BUILD SUCCESSFUL, exit 0, zero `e:`.
- [ ] `testDebugUnitTest`: 0 failures; new tests above are present and passing.
- [ ] `connectedDebugAndroidTest`: 0 failures.
- [ ] `zipalign -c -P 16 -v 4 …` on release APK shows `(OK)` per native lib
      (Lesson 011) — no regressions from baseline.
- [ ] App launched on an emulator AND on Pixel 10 Pro Fold; manual QA checklist
      walked end-to-end; screenshot attached.
- [ ] No `Color(0x…)` literal violates the 8-hex-digit rule (Lesson 001).
- [ ] Every `Flow` collected in `CameraScreen` uses
      `collectAsStateWithLifecycle()` (Lesson 002).
- [ ] No `Context` parameter introduced on `OpenLoopViewModel` methods (Lesson 004).
- [ ] PR description states what was NOT verified (e.g., "did not test recording
      across screen rotation mid-record").
