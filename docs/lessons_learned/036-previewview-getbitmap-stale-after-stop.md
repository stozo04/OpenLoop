# Lesson 036 — `PreviewView.getBitmap()` serves a frozen frame after started-then-stopped: gate timed grabs on the lifecycle

> Origin: PR #138 review (photo-booth strip) — caught by review before it shipped, not by a field
> bug, which is exactly why it earns a lesson: nothing in the API surface hints at it.

## What would have gone wrong

The booth sequence is a wall-clock loop: `delay(1_000L)` ticks in a `LaunchedEffect`, and after
each `1` it grabs the viewfinder with `previewView.bitmap`. The null-grab abort guard assumed
"`getBitmap()` returns null until the preview streams" — true, but only **before the preview has
EVER streamed**.

Background the app mid-countdown (home, lock, an incoming call — no activity recreation) and three
facts compound:

1. **`delay()` is not frame-bound.** Coroutine timers keep ticking while the activity is stopped —
   only `animateTo`/`withFrameNanos` suspend with the paused frame clock. The countdown, the grabs,
   and the completion hand-off all keep executing in the background.
2. **CameraX stops the stream on ON_STOP, but the COMPATIBLE-mode `PreviewView` is a
   `TextureView`, and a `TextureView` retains its last rendered frame.** `getBitmap()` happily
   returns that frozen frame, non-null, for as long as the view lives.
3. So the null-grab abort never fires, and every backgrounded "shot" bakes the **same stale frame**
   into the strip — which then saves, publishes to MediaStore, and launches the share sheet by
   itself when the user returns from the call.

## Pattern

**Any timed/background-capable read of `PreviewView.getBitmap()` must check the lifecycle first.**
A null-check is not a liveness check:

```kotlin
// In the sequence loop, immediately before the grab (CameraScreen.kt):
if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
    boothActive = false      // treat ON_STOP like Cancel: silent discard back to idle
    return@LaunchedEffect
}
val grab = previewView.bitmap // null only if the preview has never streamed
```

- The one-shot photo path (`capturePhoto` on shutter tap) does **not** need the check — a tap can
  only happen while the screen is foregrounded and receiving input.
- Deciding *what* the abort does is a product call, made explicitly: here ON_STOP behaves like
  Cancel (discard, no snackbar — PRD-photo-booth §5.1/§5.4), not like the null-grab error path.

## Detection checklist

- `rg -n "previewView.bitmap|getBitmap\(" app/src/main` — for every hit, ask: *can this line run
  while the activity is stopped?* Timer/coroutine-driven grabs need the lifecycle gate; direct
  input-handler grabs don't.
- A `LaunchedEffect` that mixes `delay()` with capture/save/share side effects is the smell:
  `delay` runs through ON_STOP, so every side effect after it can fire from the background.
- Manual repro (emulator): start a booth countdown, press home at shot 2, wait out the sequence,
  reopen the app. Correct: idle viewfinder, no new strip in the gallery. Bug: a strip of duplicate
  frozen frames + the share sheet.

## Reference

- [`TextureView`](https://developer.android.com/reference/android/view/TextureView) — retains its
  content; `getBitmap()` returns the latest rendered frame.
- [`PreviewView.getBitmap()`](https://developer.android.com/reference/androidx/camera/view/PreviewView#getBitmap()) —
  null only before the first frame; says nothing about staleness.
- [Compose side effects](https://developer.android.com/develop/ui/compose/side-effects) —
  `LaunchedEffect` coroutines are not lifecycle-paused; only frame-clock suspensions are.
- `ui/CameraScreen.kt` (booth sequence effect). Related: [[022-release-camera-when-preview-leaves-composition]]
  (the other "the preview outlives the stream" trap).
