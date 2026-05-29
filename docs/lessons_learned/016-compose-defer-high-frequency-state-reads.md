# Lesson 016 — Compose: defer high-frequency state reads behind `() -> T` lambdas read in the narrowest (or draw) scope

> Origin: PR #19 (slice 01 review) — finding REC-1 in `CameraScreen`.

## What went wrong

`CameraScreen` collected the elapsed-time flow at the **screen root**:

```kotlin
val recordingElapsedMs by viewModel.recordingElapsedMs.collectAsStateWithLifecycle()
val progress = (recordingElapsedMs.toFloat() / MAX).coerceIn(0f, 1f)   // read at root
val elapsedLabel = "%02d:%02d".format(min, sec)                        // read at root
```

`recordingElapsedMs` re-emits ~every 33 ms (~30×/s). Because `.value` was read in the composable's
**root** scope, every tick recomposed the **entire** `CameraScreen` body — including the
`AndroidView` camera viewfinder and both gradient bars — to update a ring sweep and a text chip.

## Pattern

**Read a high-frequency `State` in the lowest scope that needs it — pass it down as a lambda, and
keep the root from ever reading `.value`.**

- Keep the collected flow as a **raw `State`** (no `by` delegate at root), so *constructing* it
  doesn't subscribe the root:
  ```kotlin
  val recordingElapsedState = viewModel.recordingElapsedMs.collectAsStateWithLifecycle() // State<Long>
  // NOTE: do NOT read recordingElapsedState.value anywhere in CameraScreen's body.
  ```
- Change consumers to take **lambdas**, and read `.value` *inside* them:
  ```kotlin
  ShutterButton(
      isRecording = isRecording,
      progressFraction = {                                   // read happens in the Canvas DRAW phase
          (recordingElapsedState.value.toFloat() / MAX).coerceIn(0f, 1f)
      },
      onClick = { /* … */ },
  )
  RecordingCountdownChip(
      visible = isRecording,
      text = { val ms = recordingElapsedState.value; "%02d:%02d / %s".format(ms/60000, (ms/1000)%60, capLabel) },
  )
  ```
- **Draw-phase read is best:** invoking `progressFraction()` inside `Canvas(...) { drawArc(...) }`
  means a tick triggers only a **redraw**, not a recomposition, of the ring. The chip reads its
  lambda in composition (a `Text` needs a `String`), so it recomposes — but only the chip, which is
  the whole point: only the consumers react, never the viewfinder tree.

The general rule (Compose perf "defer reads"): the closer to the actual read site you subscribe,
the smaller the recomposition/redraw scope. Lambdas move the subscription downward.

> **Slice 02 note:** the `Trim` screen's scrubber/preview position will update at a similar high
> frequency. Apply the same shape — pass the position as `() -> Long`/`() -> Float` to the trim-bar
> handles and preview overlay; don't read it at the `TrimScreen` root next to the ExoPlayer
> `AndroidView`.

## Detection checklist

- In a screen that hosts an `AndroidView` (camera viewfinder, ExoPlayer), grep for a root-level
  `by ....collectAsStateWithLifecycle()` of a fast-ticking flow — that's a full-tree recompose per
  tick. High-frequency state should be a raw `State` read inside lambdas, not a root `by`.
- A consumer of a fast value taking a plain `Float`/`String` (not `() -> Float`/`() -> String`)
  forces its read into the caller's scope — prefer the lambda form for tick-rate values.
- This perf win is hard to assert reliably (a parent-recomposition counter is fragile); verify by
  reasoning + Layout Inspector recomposition counts rather than shipping a flaky automated test.
- Don't regress Lesson 002: still collect via `collectAsStateWithLifecycle()` — just keep it as a
  `State` and defer the `.value` read.

## Reference

- [Compose performance — defer reads as long as possible](https://developer.android.com/develop/ui/compose/performance/bestpractices#defer-reads).
- [Compose phases (composition / layout / draw)](https://developer.android.com/develop/ui/compose/phases) — why a draw-phase read avoids recomposition.
- Related: [[002-lifecycle-aware-flow-collection]].
