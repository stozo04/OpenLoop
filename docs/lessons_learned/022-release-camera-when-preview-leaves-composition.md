# Lesson 022 — Release CameraX when the PreviewView leaves composition, not only when the Activity stops

> Origin: Issue [#36](https://github.com/stozo04/OpenLoop/issues/36) (camera preview teardown log noise).

## What went wrong

Logcat showed benign-but-alarming **E** lines when leaving the camera screen (`BufferQueue has been abandoned`, `Surface failed to disconnect`, `queueBuffer: error -19`) even though capture and preview worked. CameraX was bound to the **Activity** lifecycle via `bindToLifecycle`, but `CameraScreen`'s `PreviewView` was destroyed as soon as navigation moved to Trim / Gallery / Editor while the activity stayed **RESUMED**. The HAL kept queueing into a surface consumer that Compose had already torn down.

## Pattern

Pair `startCamera()` (on enter) with `releaseCamera()` → `ProcessCameraProvider.unbindAll()` in a `DisposableEffect` `onDispose` on [CameraScreen]. Re-bind on re-entry via the existing `LaunchedEffect`.

**Do not** call `releaseCamera()` while `activeRecording != null` — that would reproduce Lesson 012 (`ERROR_SOURCE_INACTIVE`). The `ReadyToCapture` ↔ `Recording` transition must still use a single [CameraScreenHost] call site so `DisposableEffect` is not disposed mid-record.

## Detection checklist

- Leaving camera for Trim/Gallery/Editor: logcat should show fewer/no `BufferQueue has been abandoned` lines.
- Returning to camera: preview must recover without an extra Activity restart.
- Mid-record: `adb logcat` must not show `ERROR_SOURCE_INACTIVE` within tens of ms of `Video burst recording started` (Lesson 012 regression).

## Reference

- [CameraX architecture — lifecycle and `unbindAll`](https://developer.android.com/media/camera/camerax/architecture)
- [Compose side effects — `DisposableEffect`](https://developer.android.com/develop/ui/compose/side-effects#disposableeffect)
- Lesson 012 — single call site across capture states
