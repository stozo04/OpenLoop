# Android 16 — Framework API Diff (API 35 → 36)

> **Source:** https://developer.android.com/sdk/api_diff/36/changes · fetched 2026-05-28
> **Part of:** [docs/android-16/ hub](./README.md) — Android 16 (API 36) upgrade knowledge for OpenLoop (Issue #7)
> This is a large auto-generated report. We link to it and surface only OpenLoop-relevant deltas rather than copying it.

## Impact on OpenLoop

**Verdict:** Reference only — no breaking framework deltas hit OpenLoop's surface; the 35→36 additions are net-new APIs (opt-in), not removals or signature changes to anything we call.

- **Bookmark for after the compileSdk bump.** Use this report to resolve any new deprecation warnings the compiler emits once OpenLoop compiles against API 36. Nothing here forces a code change today.
- **AndroidX libraries do NOT appear here.** CameraX, Media3 (ExoPlayer/Transformer), Compose, and DataStore are AndroidX, not framework. Their changes ship in their own release notes, never in this `android.*` framework diff. Track those separately when bumping library versions.
- **Framework deltas worth a glance** (all additive): `MediaStore` gained cancellation-aware file-open overloads and photo-picker pre-selection extras; predictive back gained new constants on `OnBackInvokedDispatcher`/`BackEvent`; `camera2` classes changed but at the framework level OpenLoop does not call directly (CameraX wraps them). Details below.

## How to use this report

The report is JDiff-generated and split into three buckets — **Added** (new packages/classes/members), **Removed** (deleted, the ones that can break a build), and **Changed** (modified signatures, return types, or docs). It is grouped first by package (e.g. `android.view`, `android.provider`), then by class, then by member (constructors / methods / fields). Start at the [changes summary](https://developer.android.com/sdk/api_diff/36/changes/changes-summary) to see which packages moved, then click into a package and class only when the compiler flags a symbol you actually use. Consult it reactively during the upgrade — after raising `compileSdk` to 36 — rather than reading it front to back.

## OpenLoop-relevant deltas

All entries below were confirmed in the fetched per-class diff pages. Everything found is an **addition**; no removals or signature changes touch OpenLoop's call surface.

### Window / WindowInsets / WindowManager (edge-to-edge)

**No changes.** `android.view.Window`, `android.view.WindowInsets`, `android.view.WindowManager`, `WindowInsetsController`, and `WindowMetrics` are **not** in the changed-class list for `android.view` in this diff. The classes that did change in `android.view` are `AttachedSurfaceControl`, `Display`, `FrameMetrics`, `KeyEvent`, `Surface`, `SurfaceControl.Transaction`, `SurfaceView`, `View`, `ViewGroup`, `ViewStructure` (plus added `SurfaceControl.JankData` and listeners) — none of which OpenLoop touches for its already-enabled edge-to-edge setup.

### Predictive back (`android.window`)

Additive only:

- `OnBackInvokedDispatcher` — added field `int PRIORITY_SYSTEM_NAVIGATION_OBSERVER`.
- `BackEvent` — added constructor `BackEvent(float, float, float, int, long)`, added method `long getFrameTimeMillis()`, added field `int EDGE_NONE`.
- New class `SystemOnBackInvokedCallbacks` added to the package.
- `OnBackInvokedCallback`, `OnBackAnimationCallback`, and `BackNavigationInfo` show **no** entries in this diff.

OpenLoop handles back through Compose/AndroidX, not these framework symbols directly. None of these additions require a change; they are optional capabilities.

### MediaStore (`android.provider`)

`MediaStore`, `MediaStore.MediaColumns`, and `MediaStore.Audio.AudioColumns` are listed as changed; `MediaStore.Images` and `MediaStore.Video` show no entries. Confirmed additions on `MediaStore`:

- Methods: `markIsFavoriteStatus(ContentResolver, Collection<Uri>, boolean)`, `openAssetFileDescriptor(ContentResolver, Uri, String, CancellationSignal)`, `openFileDescriptor(ContentResolver, Uri, String, CancellationSignal)`, `openTypedAssetFileDescriptor(ContentResolver, Uri, String, Bundle, CancellationSignal)`.
- Fields/constants: `ACCESS_OEM_METADATA_PERMISSION`, `ACTION_MOTION_PHOTO_CAPTURE`, `ACTION_MOTION_PHOTO_CAPTURE_SECURE`, `EXTRA_PICKER_PRE_SELECTION_URIS`, `QUERY_ARG_MEDIA_STANDARD_SORT_ORDER`.

The new `CancellationSignal` file-open overloads are the only items relevant to a save/scan path — they are opt-in conveniences, not replacements. The motion-photo and picker-preselection constants do not apply to OpenLoop's flow.

### Permissions (`android.Manifest.permission`)

`android.Manifest.permission` is listed as changed, but **none of OpenLoop's permissions are affected**. The added permissions are `APPLY_PICTURE_PROFILE`, `BIND_APP_FUNCTION_SERVICE`, `BIND_TV_AD_SERVICE`, `EXECUTE_APP_FUNCTIONS`, `MANAGE_DEVICE_POLICY_APP_FUNCTIONS`, `MANAGE_DEVICE_POLICY_THREAD_NETWORK`, `QUERY_ADVANCED_PROTECTION_MODE`, `RANGING`, `READ_COLOR_ZONES`, `READ_SYSTEM_PREFERENCES`, `REQUEST_OBSERVE_DEVICE_UUID_PRESENCE`, `TV_IMPLICIT_ENTER_PIP`, `WRITE_SYSTEM_PREFERENCES`. No change to `CAMERA`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED`, or any photo-picker / storage permission.

### Camera / media framework

- `android.hardware.camera2` changed classes: `CameraCharacteristics`, `CameraMetadata`, `CaptureRequest`, `CaptureResult`, `MultiResolutionImageReader`. These are **framework** camera2 symbols. OpenLoop uses **CameraX (AndroidX)**, which wraps camera2 — so these deltas reach OpenLoop only through a CameraX version bump, not directly. The summary-level read is additive (new keys/metadata), with no removals indicated; verify only if a future CameraX release surfaces a new capability.
- `android.media` is listed as a changed package; OpenLoop's media path is **Media3 (AndroidX)**, which does not appear in this framework diff.

> Reminder: CameraX, Media3, Compose, and DataStore are AndroidX libraries and will **not** appear in this framework API diff. Their breaking changes live in their own changelogs.

## See also
- Evergreen rules: [ANDROID_STANDARDS.md](../ANDROID_STANDARDS.md)
- Upgrade plan: [007 IMPLEMENTATION.md](../completed/007-target-sdk-upgrade/IMPLEMENTATION.md)
