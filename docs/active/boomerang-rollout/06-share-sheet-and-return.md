# Slice 06 — Share sheet + return-to-camera

> **Branch:** `feature/boomerang-slice-06-share-sheet`
> **Depends on:** slice 05 (Looks tab — editor is feature-complete).
> **Unblocks:** slice 07 (Gallery tap-to-edit).

---

## Problem

Through slice 05 the editor is feature-complete (direction, speed, looks,
trim) and saves rendered boomerangs to internal storage. But saving today
just shows a snackbar — the user has no fast path to **share** the
boomerang to messaging, social, or cloud apps without first navigating to
the gallery, finding the file, and using a separate share gesture.

Reference Boomerang's post-save flow pops the **Android share sheet**
immediately on save. This is the genuinely useful piece of their post-save
chain (the review nag and settings detour are not). Lifting just the share
sheet gives users single-tap distribution.

## Scope

### In scope
- `FileProvider` configuration in `AndroidManifest.xml` + `res/xml/file_paths.xml`.
- On successful render in `saveBoomerang()`, launch `Intent.ACTION_SEND`
  via `Intent.createChooser(...)` for the rendered MP4.
- After the chooser dismisses (shared, canceled, or backed out), route to
  `ReadyToCapture` (the camera) and show a snackbar **"Saved — view in gallery"**
  with a `View` action that navigates to `Gallery`.
- Snackbar auto-dismisses at 4 s.

### Out of scope
- Saving the boomerang to public storage (DCIM / `MediaStore.Video`). Not
  needed for v1 — the FileProvider URI is a first-class share target, and
  most receivers (Drive, Telegram, Photos) save their own copy. Public
  MediaStore write is a future decision (parent doc D-6).
- "Save to gallery" as a separate option distinct from share. Save and
  in-gallery storage already happen — share is just the additional surfacing.
- Watermarks, share captions, deep links back to OpenLoop.
- Per-target customization (e.g., compress for SMS). Out of scope for v1.

## UX deltas

### Save flow

```
Editor              tap ✓                   → Processing
Processing          render success          → Intent.createChooser(ACTION_SEND)
Share sheet         user shares or cancels  → ReadyToCapture + snackbar
Snackbar            auto-dismiss in 4 s     → snackbar removed
Snackbar            tap "View"              → Gallery
```

If the render **fails**, no share sheet appears; instead the editor stays
visible with a snackbar **"Couldn't save boomerang. Try again."** (same as the
slice 02 failure path).

### Snackbar copy and action

- Default text: `"Saved — view in gallery"`.
- Action label: `"View"` (right-aligned, `NeonCoral` text).
- Action behavior: navigates to `Gallery` (currently routed via
  `OpenLoopUiState.Gallery`).
- Type: Compose Material 3 `Snackbar` hosted by a `SnackbarHostState` in the
  `Scaffold` of `MainActivity` (so it persists across state transitions, not
  scoped to the editor screen).

### Share sheet contents

- `Intent.EXTRA_STREAM`: FileProvider URI for the rendered boomerang MP4.
- `Intent.EXTRA_SUBJECT`: `"OpenLoop boomerang"`.
- `intent.type`: `"video/mp4"`.
- `intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)`.
- `Intent.createChooser(intent, "Share boomerang")`.

## Technical deltas

### `AndroidManifest.xml`

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

### `res/xml/file_paths.xml` (new)

```xml
<paths>
    <files-path name="boomerangs" path="boomerangs/" />
</paths>
```

Only `filesDir/boomerangs/` is exposed — raws and scratch files are not
shareable through this provider.

### `OpenLoopViewModel.kt`

- Replace the snackbar-emitting success path in `saveBoomerang()` with a
  `SharedFlow<UiEffect>` emit:
  ```kotlin
  sealed interface UiEffect {
      data class ShareBoomerang(val file: File) : UiEffect
      data object Saved : UiEffect              // collected by snackbar host
      data object SaveFailed : UiEffect
  }

  private val _uiEffects = MutableSharedFlow<UiEffect>(extraBufferCapacity = 4)
  val uiEffects: SharedFlow<UiEffect> = _uiEffects.asSharedFlow()
  ```
- On render success, emit `ShareBoomerang(file)` first, then post `ReadyToCapture`.
  The snackbar emit (`Saved`) is deferred until the share sheet returns control
  (see `MainActivity` wiring below).

### `MainActivity.kt`

- Collect `viewModel.uiEffects` in a `LaunchedEffect` keyed to the lifecycle
  (use `repeatOnLifecycle(Lifecycle.State.STARTED)`).
- On `ShareBoomerang(file)`:
  ```kotlin
  val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
  val intent = Intent(Intent.ACTION_SEND).apply {
      type = "video/mp4"
      putExtra(Intent.EXTRA_STREAM, uri)
      putExtra(Intent.EXTRA_SUBJECT, "OpenLoop boomerang")
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }
  val chooser = Intent.createChooser(intent, "Share boomerang")
  startActivity(chooser)
  // After startActivity returns, emit Saved so the snackbar appears once the user
  // is back on the camera. There is no callback for chooser dismissal — that's
  // fine; the snackbar is queued and displays as soon as the activity is resumed.
  lifecycleScope.launch {
      withResumed { viewModel.emitSavedSnackbar() }
  }
  ```
- The `Scaffold` in `MainActivity` hosts a `SnackbarHostState`; on `UiEffect.Saved`,
  call `snackbarHostState.showSnackbar(message = "Saved — view in gallery", actionLabel = "View")`
  and on `SnackbarResult.ActionPerformed`, post `OpenLoopUiState.Gallery`.

### Boomerang file location

No changes from slice 02–05 — files continue to live at
`filesDir/boomerangs/boom_<ts>_from_<rawTs>.mp4`. The FileProvider mapping
above grants share-time access without copying or moving them.

## Testing plan

### Unit tests

- `OpenLoopViewModelTest`:
  - `saveBoomerang()` emits `UiEffect.ShareBoomerang(file)` on render success,
    then transitions to `ReadyToCapture`.
  - `saveBoomerang()` emits `UiEffect.SaveFailed` on render error; stays in
    `BoomerangEditor`.
  - `UiEffect.Saved` is emitted only after `withResumed { ... }` resolves
    (verifiable by collecting `uiEffects` and asserting ordering).

### Instrumented tests

- `MainActivityTest` (or `ShareIntentTest`):
  - Intent capture using `Intents` (Espresso intents library):
    `intended(allOf(hasAction(ACTION_SEND), hasType("video/mp4"), hasExtraWithKey(EXTRA_STREAM)))`.
  - Snackbar text appears after returning from the chooser; tapping "View"
    routes to `Gallery`.
- End-to-end: capture → trim → editor → save → share sheet appears → cancel →
  snackbar visible on camera screen with "View" action.

### Manual QA

- Save a boomerang → Android share sheet appears with at least Drive, Messages,
  and any installed video-receiving apps.
- Share to Drive (or Telegram if installed): file uploads successfully and
  plays in the receiver app.
- Cancel the share sheet: snackbar "Saved — view in gallery" appears on the
  camera screen.
- Tap "View" → gallery opens; the new boomerang is visible.
- Repeat the flow 3× in a row without restarting the app — no FileProvider
  permission errors in logcat (`grep "FileUriExposedException\|SecurityException"`).
- Test on Pixel 10 Pro Fold + an emulator with the Android system share UI.
- Screenshot of the share sheet (top half) attached to the PR.

## Implementation notes (as built)

This doc was written before slices 04/05 merged; two prescriptions were superseded during build,
both deliberately:

- **Reused the existing `BoomerangEvent` `Channel` instead of a new `UiEffect` `SharedFlow`.** By
  slice 06 the one-shot event channel and the "Saved — view in gallery" snackbar (with the `View`
  action) already existed and worked. Adding a parallel `SharedFlow` would have been churn for no
  user-visible gain, and a `Channel` is the more robust choice for one-shot events (it buffers a
  miss; a `SharedFlow` can drop one if nobody is collecting at that instant). Added
  `BoomerangEvent.Share(file)` (render success hands the rendered MP4 to the sheet) and
  `OpenLoopViewModel.onShareSheetClosed()` (emits the deferred `Saved`).
- **Deferred the snackbar via the activity's next `onResume()`, not `lifecycle.withResumed { }`.**
  Called synchronously right after `startActivity(chooser)`, the activity is still `RESUMED`
  (the chooser hasn't taken over yet), so `withResumed` would run its block *immediately* — firing
  the snackbar before the chooser appears, the exact thing the deferral exists to prevent.
  `MainActivity` instead sets `awaitingShareReturn` when launching the sheet and consumes it on the
  next `onResume()`, so the snackbar shows when the user is genuinely back on the camera.
- **Snackbar duration set to `SnackbarDuration.Short`.** Material3 defaults a snackbar *with an
  action label* to `Indefinite`; the explicit `Short` honors the "auto-dismiss at 4 s" requirement
  while keeping the `View` action.
- **No new gradle dependency.** `FileProvider` comes from `androidx.core`; the share-intent shape
  and provider scope are covered by an instrumented `ShareBoomerangTest` using the real
  `FileProvider`/`Intent`, so `espresso-intents` was not needed.
- **`awaitingShareReturn` survives recreation.** The deferred-share flag is persisted in
  `onSaveInstanceState` and restored in `onCreate`, so a rotation (orientation is unlocked at target
  36 — see ANDROID_STANDARDS §11) or process death while the chooser is on top doesn't drop the
  "Saved" snackbar on the next `onResume()`.
- **User-facing strings extracted to `strings.xml`.** The chooser title (`share_chooser_title`),
  share subject (`share_subject`), and the three post-save snackbar strings (`snackbar_saved`,
  `snackbar_view_action`, `snackbar_save_failed`) are resources rather than literals, so they
  localize. `buildBoomerangShareIntent(uri, subject)` takes the subject as a parameter to stay
  Context-free (and unit-testable).

## Acceptance criteria

- [ ] `assembleDebug` + `assembleRelease`: BUILD SUCCESSFUL, exit 0, zero `e:`.
- [ ] `testDebugUnitTest`: 0 failures.
- [ ] `connectedDebugAndroidTest`: 0 failures.
- [ ] `zipalign -c -P 16 -v 4 …` on release APK shows `(OK)` (Lesson 011).
- [ ] App launched on emulator AND Pixel 10 Pro Fold; share to at least one
      real app verified; screenshot attached.
- [ ] No `SecurityException` / `FileUriExposedException` in logcat across the
      share flow.
- [ ] FileProvider scope verified — confirm via test that a raw file path is
      **NOT** shareable through `getUriForFile` (only `boomerangs/` is exposed).
- [ ] Snackbar "View" action routes to Gallery correctly.
- [ ] No `Color(0x…)` literal violates the 8-hex-digit rule (Lesson 001).
- [ ] All Flow collection uses `collectAsStateWithLifecycle()` (Lesson 002).
- [ ] No `Context` parameter on any `OpenLoopViewModel` method (Lesson 004).
- [ ] PR description states what was not verified (e.g., specific apps that
      crash on receipt of a video URI — Steven's call on how thorough to be).
