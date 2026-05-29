# Lesson 015 — Predictive back is default-on at target 36: gate a state-routed `BackHandler` on any screen that can lose work

> Origin: PR #19 (slice 01 review) — finding WARNING-2 in `CameraScreen`.

## What went wrong

`CameraScreen` had **no `BackHandler`**. At `targetSdk 36`, predictive back is on by default and
the platform no longer dispatches `Activity.onBackPressed()` / `KEYCODE_BACK` to ad-hoc overrides —
back is expected to flow through `OnBackPressedDispatcher`. So a back gesture **mid-recording**
finished the Activity → `onDestroy()` → `cameraManager.shutdown()`, **silently discarding an
up-to-30 s in-flight clip** with no save and no prompt.

## Pattern

- **Any screen with state worth not losing gets a `BackHandler` that routes through the state
  machine** — never let back fall through to "finish the Activity" while work is in flight.
- **Gate it on the condition, don't always-intercept.** A disabled `BackHandler` passes the event
  through, so back still behaves normally when there's nothing to protect:
  ```kotlin
  // CameraScreen: while recording, back == tapping stop (stop & finalize → preview). Owner
  // decision D1 — finalize, don't discard (no silent data loss). Disabled when idle so back
  // exits the home screen normally.
  BackHandler(enabled = isRecording) {
      viewModel.stopBurstCapture(cameraManager)
  }
  ```
  `PreviewScreen.kt` already uses the unconditional form (`BackHandler { onBackToCaptureClick() }`)
  — match the surrounding pattern; gate only when "do nothing extra" is a valid back outcome.
- **Decide finalize-vs-discard explicitly with the owner** before coding (this is a UX/data
  choice, not a default). D1 here was *finalized*.

### Testing the gating (instrumented — and the gotchas)

- Use `createAndroidComposeRule<ComponentActivity>()` — it supplies a real
  `OnBackPressedDispatcher`. The plain `createComposeRule()` does **not**, so back can't be
  dispatched. (`ComponentActivity` for tests is provided by the `androidx.compose.ui.test.manifest`
  `debugImplementation` dependency.)
- Drive a tiny stateless host wrapping the same `BackHandler(enabled = …) { … }` so you test the
  gating without binding the camera (the lambda's real target is covered by ViewModel tests).
- **Dispatch and assert synchronously on the UI thread**, because `onBackPressed()` runs callbacks
  synchronously and its *fallback* (no enabled callback) finishes the Activity — assert before
  teardown:
  ```kotlin
  composeTestRule.runOnUiThread {
      composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
      assertEquals(expected, backCount)   // 0 when disabled (passes through), 1 when enabled
  }
  ```

> **Slice 02 note:** the new `Trim` and `Processing` screens both need a deliberate back decision.
> `Trim` likely routes back to a discard-confirm dialog; `Processing` (an in-flight render) should
> decide cancel-vs-ignore. Don't ship either without a `BackHandler` — the default at target 36 is
> "finish the Activity and lose the work."

## Detection checklist

- For each screen that holds in-progress/unsaved state, grep its file for `BackHandler` — its
  absence is the smell. (`grep -rn "BackHandler" app/src/main/java/.../ui/`)
- A `BackHandler` that protects work in only *some* states must be `BackHandler(enabled = <cond>)`,
  not unconditional — otherwise it hijacks back on screens where plain back is correct.
- Manual QA (real hardware — emulator can't reproduce the camera-source loss, Lesson 012): record →
  back mid-record → confirm clip finalizes and no `ERROR_SOURCE_INACTIVE` in logcat.

## Reference

- [Add support for the predictive back gesture](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture) — default-on behavior, route back through `OnBackPressedDispatcher`.
- [`BackHandler` (Compose)](https://developer.android.com/reference/kotlin/androidx/activity/compose/package-summary#BackHandler(kotlin.Boolean,kotlin.Function0)) — the `enabled` gate.
- ANDROID_STANDARDS §11 (predictive back at target 36). Related: [[012-camera-bound-screen-single-call-site]].
