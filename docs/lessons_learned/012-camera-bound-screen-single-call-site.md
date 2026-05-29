# Lesson 012 — A camera-bound screen must stay on ONE composable call site across state transitions

> Origin: Slice 01 (variable-length capture, `feature/boomerang-slice-01-variable-length`).
> **Read this before slice 02** — slice 02 rewires the exact post-capture routing that caused this bug.

## What went wrong

On the **Pixel 10 Pro Fold** (real hardware), every recording self-terminated ~1 s after
the shutter tap. Logcat told the story:

```
t+0ms    OpenRangViewModel: Video burst recording started.
t+25ms   Detaching [Preview, VideoCapture] from UseCaseManager   ← unbindAll()
t+25ms   Recorder: RECORDING --> STOPPING                         ← source went away
t+25ms   Attaching [Preview, VideoCapture] (new instances)        ← startCamera() ran again
t+1000ms Sending VideoRecordEvent Finalize [error: ERROR_SOURCE_INACTIVE]  (code 4)
         OpenRangViewModel: Video burst recording failed: 4
```

Root cause was in **`MainActivity.kt` routing**, not the recording logic. `ReadyToCapture`
and `Recording` were rendered from **two separate `when` branches**, each with its own
`CameraScreen(...)` call:

```text
is OpenRangUiState.ReadyToCapture -> { CameraScreen(viewModel, cameraManager) }  // call site A
is OpenRangUiState.Recording      -> { CameraScreen(viewModel, cameraManager) }  // call site B
```

In Compose, two calls at two positions in a `when` are **two different call sites** — distinct
node identities. The instant `startBurstCapture` flipped state to `Recording`, Compose
**disposed** the call-site-A `CameraScreen` and **built a fresh** call-site-B one. The new
instance re-ran its `LaunchedEffect(lifecycleOwner) { cameraManager.startCamera(...) }`, which
calls `cameraProvider.unbindAll()` and rebinds — pulling the camera (the video frame source)
out from under the recording that had started 25 ms earlier. CameraX finalized with
`ERROR_SOURCE_INACTIVE`.

Two things made it nasty to find:

1. **It was pre-existing and latent.** The two-branch routing predated slice 01; the old 1.5 s
   self-stop and the fact that recording was only ever exercised on an **emulator** hid it.
2. **The emulator cannot reproduce it.** The emulator's virtual camera does **not** fail when
   unbound mid-record, so unit/instrumented tests and emulator runs were all green. It only
   surfaced once a real recording ran past the first instant on **real hardware**.

## Pattern

Route every state that shows the same camera-bound screen through **one** call site so Compose
preserves a single instance (and its bound camera + `startCamera` `LaunchedEffect`) across the
transition:

```text
// MainActivity.kt — ONE branch, ONE CameraScreen instance for both capture states.
is OpenRangUiState.ReadyToCapture,
is OpenRangUiState.Recording -> {
    CameraScreenHost(uiState) {
        CameraScreen(viewModel = viewModel, cameraManager = cameraManager)
    }
}
```

`CameraScreenHost` (in `ui/CameraScreen.kt`) is the single hosting composable. **Do not split it
back into per-state branches.** More generally: any composable that binds the camera in a
`LaunchedEffect` must not be disposed/recreated while a recording is active — a remount means
`unbindAll()` → `ERROR_SOURCE_INACTIVE`.

## Detection checklist

- There must be exactly **one** `CameraScreen(` call site reachable during capture:
  ```
  grep -n "CameraScreen(" app/src/main/java/com/openrang/app/MainActivity.kt
  ```
  Capture states (`ReadyToCapture`, `Recording`, and any future capture-time state) must funnel
  through the single `CameraScreenHost` branch — never their own branches.
- Keep the regression test green:
  `CameraScreenTest.cameraScreenHost_keepsContentMounted_acrossCaptureTransition` (instrumented)
  asserts the camera-init `LaunchedEffect` runs **once** across `ReadyToCapture → Recording`.
  This behavior is a Compose recomposition property — it is **not** reachable from a pure JVM
  unit test, so it must stay instrumented.
- On real hardware, `adb logcat` during a capture: `ERROR_SOURCE_INACTIVE` or
  `Recorder ... RECORDING --> STOPPING` within tens of ms of `Video burst recording started`
  is the signature of the camera being unbound mid-record.

## Reference

- [`VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE`](https://developer.android.com/reference/androidx/camera/video/VideoRecordEvent.Finalize#ERROR_SOURCE_INACTIVE) — "the video frame producer stops sending frames" (e.g. the camera was unbound).
- [`ProcessCameraProvider.unbindAll` / `bindToLifecycle`](https://developer.android.com/reference/androidx/camera/lifecycle/ProcessCameraProvider) — `startCamera()` calls these.
- [Compose lifecycle & side effects](https://developer.android.com/develop/ui/compose/side-effects) — `LaunchedEffect` runs on (re)entry to composition; distinct `when` branches are distinct call sites.

---

## Hand-off notes to my future self-starting slice 02

Slice 02 (`02-auto-route-trim-and-default-save.md`) **rewires the exact routing that caused the
bug above**: `Finalize(success)` will go to `Trim(ScratchClip)` instead of `LoopingPreview`, and
adds `Trim` + `Processing` states. Tread carefully:

1. **Don't reintroduce the remount.** The `ReadyToCapture`/`Recording` single-call-site rule
   (Lesson above) still holds. Unbinding the camera *after* finalize (when you actually leave the
   camera screen for `Trim`) is fine — the recording is already done. The danger is anything that
   rebinds/remounts the camera *during* an active recording.

2. **Verify on the physical Fold, not just the emulator.** Camera-record failures
   (`ERROR_SOURCE_INACTIVE`) and the MediaCodec/Media3 reverse pipeline slice 02 introduces are
   only trustworthy on real hardware. The emulator masked the entire bug above. Steven plugs the
   Fold in as `58271FDCG000XC`.

3. **The "Loopify" button is a placeholder.** It lives in `PreviewScreen.kt`, currently a no-op
   `onClick` styled with the `NeonCoral → NeonPurple` gradient; a `BackHandler` is the escape
   hatch. Slice 02 repoints it to the Trim screen. **Open design decision** (Steven's call): keep
   the explicit "Loopify" tap to advance vs. the slice-02 doc's silent auto-route — Steven leans
   toward keeping the explicit button. Confirm before building. `PreviewScreen` is **not** deleted
   in slice 02 — it becomes the gallery-playback target (slice 07).

4. **The recording timer drifts on slow emulators.** `recordingElapsedMs` is `delay(33)`-
   accumulated and capped at `MAX_RECORDING_MS = 30_000`. On a CPU-starved/software-GPU emulator
   it lagged wall-clock badly (~48 s wall ≈ <30 s ticked, so the cap didn't fire); on the Fold it
   was accurate. If you ever need a precise wall-clock cap, inject a monotonic time source
   (`SystemClock.elapsedRealtime` as a `() -> Long`; tests pass `testScheduler.currentTime`) to
   stay both accurate *and* deterministic in virtual-time tests. `stopBurstCapture` idempotency is
   guarded by `recordingJob == null` (handles the user-tap-vs-auto-cap race) — preserve it.

5. **Environment gotchas that cost real time this session:**
   - `connectedDebugAndroidTest` **OOMs a default-RAM AVD**: `lowmemorykiller` kills the
     instrumentation process, and you get `Starting 0 tests … Process crashed` — which is **not** a
     test or code failure. Boot the AVD with `-memory 4096`.
   - **Don't run `gradlew` while Android Studio is building/syncing the same project** — they
     deadlock on the Gradle build lock (a `connectedDebugAndroidTest` hung ~7 min from this). To
     push a build without Gradle, use `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
   - **Stale-build trap:** after CLI edits, a Studio rebuild or a killed Gradle daemon can install
     a stale APK. Confirm the *running* build is current via a visible marker before you debug
     "it didn't work" (slice 01: the `30s` shutter badge vs. the old `1.5s`).
   - **Foldable screencap:** `adb screencap` warns "multiple displays" and may grab the wrong one
     (cover vs. inner). Use `-d <displayId>` from `dumpsys SurfaceFlinger --display-id`.
