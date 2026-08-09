# Lesson 031 — A `CameraEffect` is attached once per bind and switched by uniform, never re-attached

**Origin:** Camera lenses (`docs/PRD-camera-lenses.md`), 2026-08-08
**Applies to:** `camera/CameraManager.kt`, `camera/lens/LensSurfaceProcessor.kt`

## What would have gone wrong

The obvious way to build a lens picker is "attach the effect when the user picks a lens, remove it
when they clear it." That is wrong in a way that only shows up in the field.

CameraX effects are attached through `UseCaseGroup.Builder().addEffect(...)`, which is consumed by
`bindToLifecycle`. **There is no API to add or remove an effect on a live binding.** Changing the
effect set therefore means `unbindAll()` + rebind — and a rebind during capture tears the camera
out from under the `Recorder`, which finalizes with `ERROR_SOURCE_INACTIVE`. That is precisely the
failure class this repo already paid for twice:

- Lesson 012 — two `CameraScreen` call sites remounting across the capture transition.
- Issue #36 / Lesson 022 — CameraX left queueing into an abandoned `PreviewView` surface.

A user tapping a lens two seconds into a recording would have silently lost the clip.

The same trap applies to a second effect: **CameraX allows at most one effect per target**, so
"an `OverlayEffect` for stickers and a `SurfaceProcessor` for warps" cannot coexist either.

## Pattern

**One effect, attached on every bind, whose behavior is data the renderer reads per frame.**

```kotlin
// CameraManager — the effect is not conditional on a lens being selected.
private val lensProcessor = LensSurfaceProcessor(context)

UseCaseGroup.Builder()
    .addUseCase(preview)
    .addUseCase(videoCapture)
    .addEffect(LensCameraEffect(lensProcessor.glExecutor, lensProcessor))
    .build()

// Selecting a lens is a field write. No rebind, safe mid-recording.
fun setLens(lens: Lens?) = lensProcessor.setLens(lens)
```

With no lens the shader is an identity pass-through, so "off" costs a copy, not a rebind. Anything
the renderer must switch on — active lens, tracked face, mirroring — is a `@Volatile` field read on
the GL thread, never a constructor argument.

Cost, stated honestly: an always-attached effect routes frames through a GPU copy even when idle.
That is the price of never rebinding, and it is the right trade in a camera app that records.

## Detection checklist

- `grep -n "addEffect" app/src/main/java` → every hit must be on a path that runs for *every* bind,
  never inside an `if (someFeatureEnabled)`.
- Any `startCamera(...)` / `bindToLifecycle` call newly reachable from a UI toggle is a red flag.
  Ask: "can the user hit this while `activeRecording != null`?"
- A second `CameraEffect` subclass in the tree is a design error, not an extension point.
- Regression guards: `OpenLoopViewModelTest."changing lenses mid-recording never touches the
  capture state"`, and `CameraBackHandlerTest` for the tray's back priority.

## Reference

- [`CameraEffect`](https://developer.android.com/reference/androidx/camera/core/CameraEffect)
- [`UseCaseGroup.Builder.addEffect`](https://developer.android.com/reference/androidx/camera/core/UseCaseGroup.Builder)
- [CameraX architecture](https://developer.android.com/media/camera/camerax/architecture)
- Lessons [012](./012-camera-bound-screen-single-call-site.md), [022](./022-release-camera-when-preview-leaves-composition.md)
