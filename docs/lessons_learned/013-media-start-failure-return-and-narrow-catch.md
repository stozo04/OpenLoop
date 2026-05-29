# Lesson 013 — Media start calls: handle the failure RETURN, catch only the documented synchronous throwables, and remember async errors arrive via a callback

> Origin: PR #19 (slice 01 review) — findings REC-2 + REC-3 in `OpenRangViewModel.startBurstCapture`.

## What went wrong

`startBurstCapture` did two careless things around `CameraManager.startRecording(...)`:

1. **It ignored the failure return.** `startRecording` returns `Recording?` and returns
   **`null` when the `VideoCapture` use case isn't bound yet**. The code launched the elapsed-time
   timer coroutine anyway. Consequence: no `VideoRecordEvent.Finalize` ever fires, the 30 s
   auto-cap's `stopRecording()` is a no-op, and the UI sits **stuck in `Recording` with a full
   progress ring for 30 s** with no way out.

2. **It wrapped the whole start in `catch (e: Exception)`.** A blanket catch turns every
   programming error (NPE, `IllegalArgumentException` from a bad argument we passed) into a silent
   "reset to ReadyToCapture", hiding bugs (ANDROID_STANDARDS §3).

The trap on #2: it's tempting to "play it safe" and list speculative exceptions
(`IOException`, `IllegalArgumentException`). **Web-verifying the actual call chain showed those
are not thrown synchronously here at all** — so catching them is dead code that signals a
misunderstanding of the API.

## The verified facts (CameraX `androidx.camera.video`, source Javadoc, checked this session)

The `startRecording` chain is `Recorder.prepareRecording(ctx, FileOutputOptions)` →
`PendingRecording.withAudioEnabled()` → `PendingRecording.start(executor, listener)`:

| Call | Declared **synchronous** throw | Condition |
|------|--------------------------------|-----------|
| `prepareRecording(Context, FileOutputOptions)` | **none** | — |
| `withAudioEnabled()` | `IllegalStateException` | Recorder doesn't support audio |
| `withAudioEnabled()` | `SecurityException` | `RECORD_AUDIO` denied at call time |
| `start(executor, listener)` | `IllegalStateException` | Recorder already has an unfinished active recording |

**Errors that occur *while* recording (source inactive, disk full, encoder failure, GC of the
`Recording`) are NOT thrown.** They arrive asynchronously as a `VideoRecordEvent.Finalize` event
with `event.hasError()` / `event.getError()` (e.g. `ERROR_SOURCE_INACTIVE` — see Lesson 012). So
there is **no** synchronous `IOException` to catch on this path.

## Pattern

For any "start a media operation" call (CameraX `Recording`, and by extension the slice-02
Media3 `Transformer` and `MediaCodec` work):

1. **Capture and check the return.** If it signals "couldn't start" (`null` here), recover
   *before* spinning up dependent coroutines/timers, and `return`:
   ```kotlin
   fun startBurstCapture(cameraManager: CameraManager) {
       val recording = cameraManager.startRecording(outputFile) { event -> /* … */ }
       if (recording == null) {            // VideoCapture not bound → no Finalize will ever fire
           clearRecordingTimers()
           _uiState.value = OpenRangUiState.ReadyToCapture
           return                          // bail BEFORE launching the timer
       }
       // … only now launch the elapsed-time / auto-cap coroutine
   }
   ```

2. **Catch only the documented synchronous throwables; let the rest propagate.** Kotlin has no
   multi-catch, so use one block per type (delegating to a shared recovery fn):
   ```kotlin
   try {
       // … prepareRecording → withAudioEnabled → start …
   } catch (e: IllegalStateException) {   // prepareRecording/start: Recorder busy / no audio support
       recoverFromFailedStart(e)
   } catch (e: SecurityException) {        // withAudioEnabled: RECORD_AUDIO revoked since our check
       recoverFromFailedStart(e)
   }
   // NOT catch(Exception): async failures come via VideoRecordEvent.Finalize, and a stray
   // throwable should stay visible as a real bug.
   ```

3. **Async error path is separate.** Handle `Finalize.hasError()` in the event callback — that is
   the *only* place runtime media failures surface. Slice 02: `Transformer.Listener.onError(...)`
   and `MediaCodec` error callbacks are the equivalent — do not expect a `try/catch` around the
   *start* call to catch a *runtime* export/transcode failure.

## Detection checklist

- Grep for a media start whose result is discarded: a `startRecording(`/`prepareRecording(` /
  `transformer.start(` line that is **not** assigned to a `val` and null/began-checked.
- Grep for `catch (e: Exception)` / `catch (e: Throwable)` in any ViewModel/media class — each is
  a candidate to narrow to the API's documented throwables.
- For every exception type you *do* catch, you must be able to point to the `@throws` in the
  API source/reference. If you can't, you're guessing — remove it.
- Covering tests (`OpenRangViewModelTest`): null-return → ReadyToCapture, `elapsed == 0`,
  `stopRecording` never called, `advanceUntilIdle()` settles (no orphan timer); and one test per
  caught exception type proving recovery to idle.

## Reference

- [`PendingRecording.start`](https://developer.android.com/reference/androidx/camera/video/PendingRecording#start(java.util.concurrent.Executor,androidx.core.util.Consumer%3Candroidx.camera.video.VideoRecordEvent%3E)) — `@throws IllegalStateException` (unfinished active recording).
- [`PendingRecording.withAudioEnabled`](https://developer.android.com/reference/androidx/camera/video/PendingRecording#withAudioEnabled()) — `@throws IllegalStateException` / `SecurityException`.
- [`VideoRecordEvent.Finalize`](https://developer.android.com/reference/androidx/camera/video/VideoRecordEvent.Finalize) — `getError()`; runtime errors are delivered here, not thrown.
- [CameraX video capture](https://developer.android.com/training/camerax/video-capture) — `start()` returns the `Recording`; null when the use case isn't bound.
- ANDROID_STANDARDS §3 (catch specific exception types). See also [[012-camera-bound-screen-single-call-site]] for the async `ERROR_SOURCE_INACTIVE` path.
