# Boomerang Slice 06 — Kickoff Prompt for a Fresh Claude Code Session

Copy everything below the line into a fresh Claude Code session with the OpenRang folder mounted. This kickoff is specific to **slice 06 (Share sheet + return-to-camera)**. Assumes slices 01–05 have shipped and are merged to `main`.

---

## Session Prompt — Implement Boomerang Slice 06

You are working on **OpenRang** — an open-source Android camera app (Kotlin/Jetpack Compose) for creating speed-controlled video loops ("Boomerangs"). Repo: `stozo04/OpenRang`. Owner: Steven Gates (@stozo04). Apache 2.0.

The editor is feature-complete (direction + speed + reps + trim, slices 02–05 shipped) and saves rendered boomerangs to internal storage. This slice adds the **single-tap distribution path** — Save now pops the Android share sheet on the new MP4, and the user lands back on the camera with a "View in gallery" snackbar.

## Critical Rule — Do Not Trust Your Training Data

Your knowledge cutoff could be a year old. **Do not assume** you know the current version of any Google standard, Android API behavior, Jetpack library pattern, testing framework convention, or Play Store requirement. Before making any claim about how something works or what Google recommends, **web-search `developer.android.com` first**. This applies to everything — `FileProvider` configuration, `Intent.ACTION_SEND` + `EXTRA_STREAM`, `Intent.createChooser`, `SnackbarHostState`, scoped storage rules. **Especially relevant:** Android 16 (API 36) brought changes to URI permission grants and share-target behavior — verify against developer.android.com before relying on training-data muscle memory. If you catch yourself writing "Google recommends X" without having searched for it in this session, stop and search.

## What slice 06 ships (one-paragraph summary)

Add a `FileProvider` configured for `filesDir/boomerangs/` only (raws and scratch stay private). On `saveBoomerang()` success, the ViewModel emits a `ShareBoomerang(file)` UI effect; `MainActivity` consumes it and launches `Intent.createChooser(ACTION_SEND, ...)` with the rendered MP4 as `EXTRA_STREAM` + `FLAG_GRANT_READ_URI_PERMISSION`. After the chooser dismisses (shared or canceled), the user lands on `ReadyToCapture` with a `Snackbar` ("Saved — view in gallery") hosted at the `Scaffold` level; tapping "View" routes to `Gallery`. No copy to public `DCIM` / `MediaStore` in this slice (parent doc D-6).

The full slice spec lives in `docs/active/boomerang-rollout/06-share-sheet-and-return.md`. **That doc is your PRD for this PR.** Treat it as authoritative.

## Before Writing Any Code — Read These Files (in this order)

1. **`CLAUDE.md`**.
2. **Every file in `docs/lessons_learned/`** — 003 (DataStore writes, also applies to repository IO), 005 (Play Store target API) most relevant. Also `docs/android-16/` — Android 16 brought share / URI changes worth checking.
3. **`docs/DEFINITION_OF_DONE.md`**.
4. **`docs/active/boomerang-editor/IMPLEMENTATION.md`** — §10 (Post-save flow) is the canonical reference for the snackbar + share-intent shape.
5. **`docs/active/boomerang-rollout/README.md`**.
6. **`docs/active/boomerang-rollout/05-editor-repetitions-tab.md`** — for the `saveBoomerang()` surface this slice extends.
7. **`docs/active/boomerang-rollout/06-share-sheet-and-return.md`** — your PRD. End-to-end, twice.
8. **`docs/PRD-mission-control.md`** §3 (Design System Tokens) — for snackbar text color (`NeonCoral` for the "View" action).
9. **`docs/ANDROID_STANDARDS.md`** — particularly the "Sharing / FileProvider" section if it exists, plus Section 11 (Android 16) for behavior changes.
10. **`docs/TEST_COVERAGE.md`** — for the Espresso `Intents` library conventions.

**Read all of these before touching any code.**

## Phase 1: Cut the branch + capture a green baseline

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
git checkout main
git pull --rebase
git checkout -b feature/boomerang-slice-06-share-sheet
.\gradlew.bat clean assembleDebug --console=plain
```

`BUILD SUCCESSFUL` + exit 0 + zero `e:`.

## Phase 2: Web-verify the moving pieces

- `androidx.core.content.FileProvider` — current manifest provider declaration; `<paths>` XML schema; `getUriForFile(context, authority, file)` signature.
- `android.content.Intent.ACTION_SEND` + `EXTRA_STREAM` + `EXTRA_SUBJECT` + `setType("video/mp4")` + `FLAG_GRANT_READ_URI_PERMISSION` — current best practice for sharing a private file via a system chooser.
- `android.content.Intent.createChooser(intent, title)` — current signature; Android 16 behavior changes if any.
- `androidx.compose.material3.SnackbarHostState.showSnackbar(message, actionLabel, ...)` — current return type (`SnackbarResult`) and action handling.
- `androidx.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED)` — current import and usage from a `LaunchedEffect`.
- Espresso `Intents` library (`androidx.test.espresso.intent.Intents`) — for intent capture in `MainActivityTest`.

If anything has drifted, **stop and surface** before coding.

## Phase 3: Implement to the slice spec

- **`AndroidManifest.xml`** — add `<provider>` for `androidx.core.content.FileProvider` with authority `${applicationId}.fileprovider`, `exported=false`, `grantUriPermissions=true`, meta-data pointing at `@xml/file_paths`.
- **`res/xml/file_paths.xml`** (new) — `<files-path name="boomerangs" path="boomerangs/" />` only. Raws and scratch are NOT exposed.
- **`OpenRangViewModel.kt`** — replace the snackbar-emitting success path with a `MutableSharedFlow<UiEffect>` (`extraBufferCapacity = 4`) exposing `uiEffects`. Add `UiEffect.ShareBoomerang(file)`, `UiEffect.Saved`, `UiEffect.SaveFailed`. On render success, emit `ShareBoomerang(file)` then post `ReadyToCapture`. `Saved` is emitted after `withResumed { ... }` resolves.
- **`MainActivity.kt`** — collect `uiEffects` with `repeatOnLifecycle(STARTED)`. On `ShareBoomerang`, build the `Intent.createChooser(...)` and `startActivity`. Host a `SnackbarHostState` in the `Scaffold`; on `Saved`, `showSnackbar(message, actionLabel = "View")`; on `ActionPerformed`, post `OpenRangUiState.Gallery`.

**Stay scoped to slice 06.** Do not add: gallery tap-to-edit / kind badge / filter chips (slice 07). Do not write to public `MediaStore` (parent doc D-6 explicitly defers this).

## Phase 4: Test

- Unit tests: `OpenRangViewModelTest` for `saveBoomerang` emitting `ShareBoomerang(file)` on success then transitioning to `ReadyToCapture`; emitting `SaveFailed` on error and staying in `BoomerangEditor`; `Saved` ordering verified via flow collection.
- Instrumented test (`MainActivityTest` or `ShareIntentTest`): use `androidx.test.espresso.intent.Intents` to assert `intended(allOf(hasAction(ACTION_SEND), hasType("video/mp4"), hasExtraWithKey(EXTRA_STREAM)))`. After returning from the chooser, snackbar text is visible; tapping "View" routes to `Gallery`.
- End-to-end: capture → trim → editor → save → share sheet appears → cancel → snackbar on camera screen with "View" action.
- Run:
  ```powershell
  .\gradlew.bat testDebugUnitTest --console=plain; echo "EXIT=$LASTEXITCODE"
  $env:ANDROID_SERIAL = "<your-emulator-or-device-serial>"
  .\gradlew.bat connectedDebugAndroidTest --console=plain; echo "EXIT=$LASTEXITCODE"
  ```

## Phase 5: Run the app for real + screenshot (the DoD gate)

```powershell
.\gradlew.bat clean assembleDebug assembleRelease --console=plain; echo "EXIT=$LASTEXITCODE"
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\<ver>\zipalign.exe" -c -P 16 -v 4 `
  app\build\outputs\apk\release\app-release-unsigned.apk
```

Walk the slice 06 manual QA on emulator + Pixel 10 Pro Fold:

- Save a boomerang → Android share sheet appears with at least Drive, Messages, and any installed video-receiving apps.
- Share to Drive (or Telegram if installed): file uploads + plays in the receiver.
- Cancel the share sheet: snackbar "Saved — view in gallery" appears on the camera screen.
- Tap "View" → gallery opens; new boomerang visible.
- Repeat the save+share flow 3× in a row — no FileProvider permission errors in logcat:
  ```powershell
  adb logcat -d | Select-String -Pattern "FileUriExposedException|SecurityException"
  ```
- Verify FileProvider scope — attempt to construct a URI for a `filesDir/videos/clip_<ts>.mp4` path (raw) via `getUriForFile`. Should throw `IllegalArgumentException` (not in the exposed paths).

**Capture a screenshot** of the Android share sheet (top half visible), attached to the PR.

## Phase 6: Open the PR

- Push to `stozo04/OpenRang`.
- PR title: `Slice 06 — Share sheet + return-to-camera`.
- PR description: acceptance-criteria checklist from `docs/active/boomerang-rollout/06-share-sheet-and-return.md`, each box checked.
- Attach the screenshot.
- State what was not verified — specifically the set of receiver apps you tested against (e.g., "verified on Drive + Telegram; did not test Snapchat / WhatsApp").

## Behavioral Rules

- **PRD-first.** Push back before coding around what seems wrong.
- **Web-search before every API claim.** Especially for Android 16 share / URI behavior.
- **Lessons compliance.** No `collectAsState(` (Lesson 002), 8-digit Color literals (Lesson 001), no `Context` on VM methods (Lesson 004 — note: `MainActivity` *is* the right place for the share intent, not the VM), zipalign `(OK)` (Lesson 011).
- **`BUILD SUCCESSFUL` is not enough.**
- **Done is the DoD gate.**
- **Stay scoped.** Slice 06 only. No gallery edits in this PR.

## When to stop and come back to Steven

- If the slice doc and a lesson disagree.
- If `FileProvider` URIs from internal storage fail to share to at least one common app (Drive, Messages, Telegram) — investigate before merging.
- If Android 16 (`targetSdk 36`) introduced share / URI changes that break the slice doc's plan — surface the deviation.
- If `SnackbarHostState` placement at the `Scaffold` level causes layout issues with the existing screens (camera viewfinder etc.).
- If the green baseline isn't green.
- If `zipalign` regresses.
- If the work is meaningfully larger than the slice doc implies (would be > ~250 LOC).
