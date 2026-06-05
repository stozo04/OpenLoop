# Slice 07 — Import a video from the phone library

> **Branch:** `feature/boomerang-slice-07-import-from-library`
> **Depends on:** slice 06 (share sheet) — the full capture→trim→editor→render→share flow is shipped.
> **Unblocks:** v1 ship.
> **Resolves:** parent IMPLEMENTATION.md open question **Q-5** ("Make a boomerang from a raw
> imported via the system file picker: in-scope for v1, or v1.5?") → **in v1, this slice.**
> **Supersedes:** the original [`07-gallery-tap-to-edit.md`](./07-gallery-tap-to-edit.md) (internal
> re-edit / raw-vs-boomerang badges / filter chips). That feature is **deferred to v1.5** — see the
> banner on that file. This slice is a *different* feature: pulling an **external** video the app
> never recorded into the existing flow.

---

## Read first (for the implementing session)

1. Root `CLAUDE.md` (always). Then **every** file in `docs/lessons_learned/` — this slice touches the
   state router (**Lesson 014**), Flow collection in Compose (**Lesson 002**), repository writes
   (**Lesson 003**), the ViewModel/Context boundary (**Lesson 004**), color literals (**Lesson 001**),
   and — because imported clips have arbitrary rotation — the reverse-pipeline rotation handling
   (**Lesson 019**).
2. Parent design: [`../boomerang-editor/IMPLEMENTATION.md`](../boomerang-editor/IMPLEMENTATION.md)
   (storage model §, Media3 pipeline §, Decision Log D-3/D-6/D-8).
3. This doc is the PRD for the PR. Clear the **[`../../DEFINITION_OF_DONE.md`](../../DEFINITION_OF_DONE.md)**
   gate before "done": debug **and** release build genuinely green, unit + instrumented tests 0
   failures, lint 0 new issues, 16 KB `zipalign` OK, **run the app on a device + screenshot**.

---

## Problem

Through slice 06 the boomerang flow only works on **fresh captures** — the user must record a new
clip every time. But the moment they want to loop often already lives in their phone's photo
library (a clip they shot earlier, a download, an AirDrop). OpenLoop offers no way in.

We want: from the gallery, tap an **Import** button → the system **video** picker opens → pick a
video → the app drops the user on the **Trim screen exactly as if they had just finished recording**,
and the rest of the pipeline (Trim → Editor → Render → Share, slices 02–06) is **unchanged**.

After this slice ships, OpenLoop v1 is feature-complete for submission (Issue #14 mechanics
permitting).

## Owner decisions baked into this slice

- **Videos only — never images.** The picker is launched `VideoOnly`, so images are not selectable at
  the source. (Don't add an image branch "just in case"; there is no image path.)
- **Imports must be ≤ 30 s.** If the picked clip is **longer than 30 s**, show a **friendly warning**
  and do **not** proceed — never a silent or cryptic failure. (This refines the earlier "30 s max
  trim window" idea: rather than letting a long clip in and capping the window, we keep the whole
  clip ≤ 30 s, which also means *no* extra trim-window cap is needed — the existing Trim screen
  already can't select more than the clip's length.) We verify length **before** copying, so a long
  clip is never copied just to be rejected.

## Key design decision — ingest as a scratch capture (do NOT invent a new source type)

The entire pipeline downstream of capture is **`File`-based and keyed on a "scratch" clip**:

- Capture records into `cacheDir/scratch/raw_<uuid>.mp4` (`VideoStorageRepository.createScratchCapture()`).
- `OpenLoopViewModel` sets `editorState = TrimState(sourceFile = scratch.file, …)` and routes to
  `Trim(EditorSource.ScratchClip(uuid))`.
- On save, the scratch is **promoted** to a raw (`promoteScratchToRaw`), the boomerang is rendered
  from it, and slice 06 shares the output. `durationOf`, the two-pass reverse (`VideoReverser`), and
  the Media3 `Transformer` all consume **file paths** — none of them speak `content://`.

Therefore the cheapest correct way to import is: **copy the picked `content://` video's bytes into a
fresh scratch file, then enter the existing `ScratchClip` flow.** No `EditorSource.GalleryClip`, no
save-path special-casing, no Trim/Editor changes for source resolution. An imported clip is just a
scratch that came from the picker instead of the camera — "as if they just finished recording,"
verbatim to the owner's intent.

Consequence (intended, call it out in the PR): saving an imported boomerang leaves **both** a raw
copy (in `filesDir/videos/`) and the rendered boomerang in the gallery — identical to a capture. The
original library video is never touched (we copy, never move).

## Scope

### In scope
- **Import button in the gallery top bar**, top-**right**, opposite the existing back-to-camera
  button (the film-slate glyph, top-left). Also surfaced as a CTA in the empty state.
- **Android Photo Picker** (`ActivityResultContracts.PickVisualMedia`, **`VideoOnly`**) — **no
  runtime storage permission**. Single-select, returns one `Uri?`.
- **Pre-copy duration check**: probe the picked clip's length; **> 30 s → friendly warning + abort**
  (no copy, stay on gallery).
- **Copy the accepted (≤ 30 s) video into a scratch file** off the main thread, then route to
  `Trim(ScratchClip)` — reusing the entire existing flow.
- A brief **`ImportingVideo` loading state** while the probe + copy run.
- **Graceful failure** for the non-length cases (unreadable URI, unreadable duration, copy I/O
  error): a snackbar ("Couldn't import that video.") and return to the gallery; never a crash, never
  a wedged state.
- **(Recommended, small) Stale-scratch prune at launch** (parent D-8): imports increase scratch churn
  (abandoned large copies), so pruning `cacheDir/scratch/` files older than 24 h on app start is
  worth folding in. Mark optional if it inflates the PR.

### Out of scope (explicitly)
- The internal **re-edit** feature (tap an existing raw to edit, raw/boomerang badges, filter chips,
  re-edit-a-boomerang-from-its-source). Deferred to v1.5 — see `07-gallery-tap-to-edit.md`.
- **Images** of any kind (picker is `VideoOnly`).
- **Multi-select** import. v1 is single-video.
- Auto-**trimming a 30 s window out of a longer clip**. We reject > 30 s instead (owner decision).
- Writing the boomerang to **public storage / MediaStore** (parent D-6 stands).
- **Persistable URI permission** / re-import history. We copy immediately; the default picker grant
  (valid until process death) is plenty. Do **not** call `takePersistableUriPermission`.

## Verified API facts (Android Photo Picker — checked against developer.android.com this slice)

Source: <https://developer.android.com/training/data-storage/shared/photopicker>

- Contract: `androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()` (single
  item). `androidx.activity` is already on the classpath via `activity-compose 1.8.2`.
- Register: `registerForActivityResult(PickVisualMedia()) { uri: Uri? -> … }`.
- Launch **video-only** (this is also what excludes images):
  `launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))`.
- Result: a **single `Uri?`** — non-null on pick, `null` if the user backs out.
- **No runtime permission required.**
- SDK: native on API 33+; modular-system-component on 30–32; **backported via Google Play services on
  API 19–29** *if* the manifest declares the backport `<service>` (below). `minSdk` is **26**, so for
  the proper picker UI on 26–29 devices the `<service>` matters; without it the framework still
  resolves to an `ACTION_OPEN_DOCUMENT` fallback, so it degrades gracefully.
- Read the bytes with `ContentResolver.openInputStream(uri)`. The grant is valid until the app's
  process is restarted — ample, since we copy synchronously on pick.

## UX deltas

### Gallery top bar

```
┌────────────────────────────────────────────────┐
│  (←film-slate)        Gallery       (＋ import)  │   ← new import button, top-RIGHT
├────────────────────────────────────────────────┤
│  …existing 3-col grid (unchanged)…             │
└────────────────────────────────────────────────┘
```

- New circular button mirroring the existing back button's style (size 64 dp, `GlassWhite` fill,
  2 dp `NeonPurple` border, `NeonPurple`-tinted icon), anchored `Alignment.CenterEnd`.
- Icon: a video-add / library glyph from `material-icons-extended` (already a dep) — e.g.
  `Icons.Outlined.VideoLibrary` or `Icons.Rounded.Add` — rather than a new vector.
  `contentDescription = "Import a video"`.
- **Empty state:** alongside "NO LOOPS YET / Record your first loop…", add a secondary affordance
  "…or import one" that triggers the same import action.

### "Video too long" warning (the nice warning)

When the picked clip is longer than 30 s, show a friendly **dialog** (not an error toast) over the
gallery — acknowledgment-only, no destructive framing:

> **Title:** "That clip's a bit long"
> **Body:** "OpenLoop makes loops from videos up to 30 seconds. Pick a shorter clip and we'll loop
> it." *(adjust copy to taste — keep it warm, not an error)*
> **Button:** "Got it"

A snackbar is an acceptable lighter alternative, but a dialog reads as "guidance," which is the
intent. Either way the user stays on the gallery and nothing is copied.

### Flow

```
Gallery        tap Import              → system Photo Picker (VideoOnly; images not selectable)
Picker         back out (null)         → stay on Gallery (no-op)
Picker         pick a video            → ImportingVideo (loader): probe duration
ImportingVideo duration > 30 s         → Gallery + "That clip's a bit long" dialog (NO copy)
ImportingVideo duration ≤ 30 s         → copy bytes to scratch
ImportingVideo copy OK                 → Trim(ScratchClip)  ── identical to a fresh capture ──▶ …
ImportingVideo copy/probe failed       → Gallery + snackbar "Couldn't import that video."
```

The Trim screen, editor tabs, render, and share sheet are **byte-for-byte the existing flow** — the
only difference the user can perceive is the entry point.

## Technical deltas (file-level)

### `data/VideoImporter.kt` (new)

A `Context`-bearing helper kept **out** of the `Context`-free `VideoStorageRepository` (Lesson 004):
`ContentResolver` needs a `Context`, the repository must never hold one.

```kotlin
interface VideoImporter {
    /** Best-effort source duration in ms (MediaMetadataRetriever over the content URI), or 0 if it
     *  can't be read. Used to enforce the ≤30 s rule BEFORE copying. */
    suspend fun probeDurationMs(source: Uri): Long

    /** Copy the picked [source] content into [dest] off the main thread. Returns false on any I/O
     *  failure (unreadable URI, low storage) — never throws to the caller. */
    suspend fun importToFile(source: Uri, dest: File): Boolean
}

class VideoImporterImpl(private val contentResolver: ContentResolver) : VideoImporter {
    override suspend fun probeDurationMs(source: Uri): Long = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            // openAssetFileDescriptor keeps this class Context-free (vs setDataSource(context, uri)).
            contentResolver.openAssetFileDescriptor(source, "r")?.use { afd ->
                r.setDataSource(afd.fileDescriptor)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } ?: 0L
        } catch (e: IllegalArgumentException) { Log.e(TAG, "probe failed", e); 0L }
          catch (e: java.io.IOException) { Log.e(TAG, "probe open failed", e); 0L }
          catch (e: SecurityException) { Log.e(TAG, "probe not permitted", e); 0L }
          finally { r.release() }
    }

    override suspend fun importToFile(source: Uri, dest: File): Boolean = withContext(Dispatchers.IO) {
        try {
            contentResolver.openInputStream(source)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } != null
        } catch (e: java.io.IOException) { Log.e(TAG, "import copy failed", e); false }
          catch (e: SecurityException) { Log.e(TAG, "import URI not readable", e); false }
    }
}
```

> **Reviewer pre-empt:** `onVideoPicked(uri: Uri?)` passes an `android.net.Uri` into the ViewModel.
> That is **allowed** under Lesson 004 — `Uri` is a parcelable value type, not a `Context`/View/
> Lifecycle. The ban is on framework objects that retain an Activity; `Uri` retains nothing.

### `ui/OpenLoopUiState.kt`

```kotlin
object ImportingVideo : OpenLoopUiState   // loader while the picked clip is probed + copied
```

**No `TrimState` change** — because imports are ≤ 30 s, no max-trim-window field is needed; the Trim
screen already can't select more than the (≤30 s) clip length.

### `ui/OpenLoopViewModel.kt`

- Constructor gains `private val videoImporter: VideoImporter` (update the `Factory` too).
- Constant: `IMPORT_MAX_DURATION_MS = MAX_RECORDING_MS` (30 s). Apply a small grace so a clip the user
  thinks is "30 seconds" (often 30.2–30.5 s) isn't rejected — reject only when
  `durationMs > IMPORT_MAX_DURATION_MS + 1_000L`.
- `fun onVideoPicked(uri: Uri?)`:
  ```kotlin
  if (uri == null) return                                  // user backed out
  _uiState.value = OpenLoopUiState.ImportingVideo
  viewModelScope.launch {
      val durationMs = videoImporter.probeDurationMs(uri)
      when {
          durationMs <= 0L -> failImport()                 // unreadable → generic "couldn't import"
          durationMs > IMPORT_MAX_DURATION_MS + 1_000L -> warnTooLong()   // friendly dialog
          else -> {
              val scratch = videoStorage.createScratchCapture()
              if (!videoImporter.importToFile(uri, scratch.file)) { failImport(); return@launch }
              val dur = videoStorage.durationOf(scratch.file)
              if (dur <= 0L) { videoStorage.discardScratch(scratch); failImport(); return@launch }
              activeScratch = scratch
              promotedRaw = null
              _editorState.value = TrimState(
                  sourceFile = scratch.file,
                  sourceDurationMs = dur,
                  trimStartMs = 0L,
                  trimEndMs = dur,                          // whole clip ≤30 s; no window cap needed
              )
              _uiState.value = OpenLoopUiState.Trim(EditorSource.ScratchClip(scratch.uuid))
          }
      }
  }
  ```
  - `failImport()`: emit `BoomerangEvent.ImportFailed`; `_uiState.value = OpenLoopUiState.Gallery`.
  - `warnTooLong()`: emit `BoomerangEvent.ImportTooLong`; `_uiState.value = OpenLoopUiState.Gallery`.
- **Events:** add `ImportFailed` (snackbar) and `ImportTooLong` (the friendly dialog) to the sealed
  `BoomerangEvent`. Keep the MainActivity `when` exhaustive (no `else`).
- **No `updateTrim` change** (no window cap).
- **(Optional D-8)** on `init`, `viewModelScope.launch { videoStorage.pruneStaleScratch(24h) }` — add
  `pruneStaleScratch(olderThanMs: Long)` to the repository (list `cacheDir/scratch/`, delete files
  whose `lastModified() < now - olderThanMs`, log the count).

### `MainActivity.kt`

- Register the picker launcher next to `requestPermissionLauncher`:
  ```kotlin
  private val pickVideoLauncher = registerForActivityResult(PickVisualMedia()) { uri ->
      viewModel.onVideoPicked(uri)        // Uri? — null when the user backs out
  }
  private fun importVideo() = pickVideoLauncher.launch(PickVisualMediaRequest(PickVisualMedia.VideoOnly))
  ```
- Factory bridge: construct `VideoImporterImpl(applicationContext.contentResolver)` and pass it into
  `OpenLoopViewModel.Factory` (Context stays in the Activity, per Lesson 004).
- Thread an `onImportVideo = ::importVideo` lambda through `OpenLoopNavHost` to `GalleryScreen`.
- Route the new state in the exhaustive `when` (Lesson 014 — **no `else`**):
  `is OpenLoopUiState.ImportingVideo -> InfinityLoadingScreen()`.
- Events collector: `ImportFailed -> showSnackbar("Couldn't import that video.")`;
  `ImportTooLong -> ` show the friendly dialog (drive an `AlertDialog` from a small remembered
  state, e.g. `var showTooLongDialog by remember { mutableStateOf(false) }` flipped true on the
  event). Keep the existing `Share`/`Saved`/`Failed` branches (slice 06).

### `AndroidManifest.xml` (Photo Picker backport for API 26–29)

```xml
<!-- requires xmlns:tools on <manifest> -->
<service android:name="com.google.android.gms.metadata.ModuleDependencies"
         android:enabled="false" android:exported="false" tools:ignore="MissingClass">
    <intent-filter>
        <action android:name="com.google.android.gms.metadata.MODULE_DEPENDENCIES" />
    </intent-filter>
    <meta-data android:name="photopicker_activity:0:required" android:value="" />
</service>
```

Without it the picker degrades to `ACTION_OPEN_DOCUMENT` on old devices (still functional).

### `ui/GalleryScreen.kt`

- Add the top-right import button (style mirrors the existing back button) wired to `onImportVideo`.
- Add the empty-state import affordance.
- **No grid / badge / filter changes** — deferred (v1.5).

### Pipeline files — `media/VideoReverser.kt`, `media/VideoProcessor.kt`

**No changes.** They already consume the scratch `File`. But imports exercise codecs/rotations the
camera never produced (HEVC, HDR, portrait from other devices); the existing `BoomerangEvent.Failed`
render-failure path is the safety net, and **Lesson 019** rotation handling must hold for arbitrary
source rotation.

## Risks & edge cases (handle or consciously accept)

| Case | Handling |
|------|----------|
| User backs out of picker (`uri == null`) | `onVideoPicked` returns immediately; stay on Gallery. |
| Clip longer than 30 s | Caught by `probeDurationMs` **before copy** → friendly `ImportTooLong` dialog → Gallery. Nothing copied. |
| Unreadable / revoked URI, low storage during copy | `importToFile` false → `ImportFailed` snackbar → Gallery. |
| Duration unreadable (`probe` or `durationOf` == 0) | `ImportFailed` (we can't enforce the 30 s rule on a clip we can't read). |
| Unsupported codec / HDR | Reverse/transcode fails at render → existing `BoomerangEvent.Failed` → editor, selection preserved. |
| Portrait vs landscape rotation | Must round-trip correctly — **Lesson 019**. Test both orientations. |
| Audio in source | Stripped on render (D-3). No action. |
| Discard from Trim after import | `discardTrim()` deletes the scratch copy — no orphan; library original untouched. Already handled. |
| Images | Not selectable (`VideoOnly`). No image code path. |

## Testing plan

### Unit (`OpenLoopViewModelTest`, JVM — add a `FakeVideoImporter` with settable `probeMs` / `copyOk`)
- `onVideoPicked(null)` → no state change, no scratch created.
- `probeDurationMs` returns ≤ 30 s + `copyOk` → `ImportingVideo` → `Trim(ScratchClip)`; `editorState`
  set (`trimEndMs == duration`); `activeScratch` non-null.
- `probeDurationMs` returns **> 31 s** → emits `ImportTooLong`, state returns to `Gallery`, **no**
  `createScratchCapture` / no copy.
- `probeDurationMs` returns 0 → `ImportFailed` → `Gallery`.
- copy fails (`copyOk = false`) → `ImportFailed` → `Gallery`; scratch discarded.
- post-copy `durationOf == 0` → `ImportFailed`, scratch discarded.
- **Lesson 017**: sweep every ViewModel construction site for the new constructor param — the JVM
  fakes block, and `OpenLoopNavHostTest`'s `Noop…` fakes.

### `VideoStorageRepositoryImplTest` (if D-8 prune included)
- `pruneStaleScratch(olderThanMs)` deletes old files, keeps newer ones (`TemporaryFolder` +
  `File.setLastModified`).

### Instrumented
- `VideoImporterImplTest`: `ContentResolver.openInputStream`/`openAssetFileDescriptor` accept
  `file://` URIs — create a temp source, `Uri.fromFile`, assert `importToFile` copies bytes exactly
  and `probeDurationMs` reads a real bundled short test clip's length; a non-existent URI → copy
  false / probe 0.
- `OpenLoopNavHostTest`: `ImportingVideo` renders the loader and **not** the camera-bound screen
  (mirror the `Processing`/`Trim` guards — Lessons 012/014).
- `GalleryScreenTest`: import button exists (`onNodeWithContentDescription("Import a video")`) and
  invokes the `onImportVideo` lambda (capture a flag).
- **Not automatable:** the system Photo Picker selection — manual QA.

### Manual QA (real device — Pixel 10 Pro Fold, folded + unfolded)
- Tap Import → picker opens **VideoOnly** (confirm images are not shown/selectable), **no permission
  prompt** → pick a **landscape** clip ≤ 30 s → lands on Trim as if just recorded → save → boomerang
  + raw appear; share sheet pops (slice 06).
- Repeat with a **portrait** clip and an **HEVC/4K** clip — orientation correct; render succeeds or
  fails gracefully to the editor (never a crash).
- Pick a **> 30 s** clip → friendly "That clip's a bit long" dialog; nothing imported; still on
  gallery. Confirm no scratch file was written (`adb shell ls .../cache/scratch/`).
- Back out of the picker → no state change.
- Import then **discard** on Trim → returns to gallery; no orphan scratch; original library video
  still present on device.
- Screenshots: gallery with the Import button; the "too long" dialog; an imported clip on Trim.

## Acceptance criteria

- [ ] `assembleDebug` + `assembleRelease`: BUILD SUCCESSFUL, exit 0, zero `e:`.
- [ ] `testDebugUnitTest`: 0 failures (incl. ≤30 s success, >30 s warning, copy-fail, probe-0 cases).
- [ ] `connectedDebugAndroidTest`: 0 failures (incl. `VideoImporterImplTest`, NavHost `ImportingVideo`
      routing, Gallery import button).
- [ ] `zipalign -c -P 16 -v 4 …` on the release APK shows `(OK)` (Lesson 011).
- [ ] App run on a real device: import a **landscape** AND a **portrait** ≤30 s clip end-to-end; share
      verified; screenshots attached.
- [ ] **Images are not selectable** in the picker (verified on device).
- [ ] A **> 30 s** import shows the friendly dialog and is **not** copied or entered into the editor.
- [ ] Import requires **no runtime storage permission** (verified on device).
- [ ] Unreadable/copy-failed imports fall to a snackbar + gallery — never a crash or wedged state.
- [ ] State router `when` stays exhaustive with **no `else`** after adding `ImportingVideo`
      (Lesson 014).
- [ ] No `Context` parameter on any `OpenLoopViewModel` method; `VideoImporterImpl` is the only new
      Context holder, constructed in the `MainActivity` Factory bridge (Lesson 004).
- [ ] New Flow collection uses `collectAsStateWithLifecycle()` (Lesson 002); importer/repository I/O
      guarded for `IOException` (Lesson 003); no malformed `Color(0x…)` literal (Lesson 001).
- [ ] PR notes what could not be verified (e.g., API 26–29 backport picker on a real old device).
- [ ] On merge: move this folder's slice docs (active → completed) per convention and close parent Q-5.
