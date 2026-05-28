# Boomerang Slice 07 — Kickoff Prompt for a Fresh Claude Code Session

Copy everything below the line into a fresh Claude Code session with the OpenRang folder mounted. This kickoff is specific to **slice 07 (Gallery tap-to-edit + raw/boomerang distinction)**. Assumes slices 01–06 have shipped and are merged to `main`. **This is the final slice in the rollout** — after it merges, OpenRang v1 is feature-complete.

---

## Session Prompt — Implement Boomerang Slice 07

You are working on **OpenRang** — an open-source Android camera app (Kotlin/Jetpack Compose) for creating speed-controlled video loops ("Boomerangs"). Repo: `stozo04/OpenRang`. Owner: Steven Gates (@stozo04). Apache 2.0.

The boomerang flow works end-to-end for fresh captures: capture → trim → editor → save → share (slices 01–06 shipped). But every prior slice operates on a **fresh capture only** — there is no path to boomerang a clip that's already in the gallery. This slice closes that loop and ships the gallery distinction between raws and boomerangs. After this merges, **OpenRang v1 is done**.

## Critical Rule — Do Not Trust Your Training Data

Your knowledge cutoff could be a year old. **Do not assume** you know the current version of any Google standard, Android API behavior, Jetpack library pattern, testing framework convention, or Play Store requirement. Before making any claim about how something works or what Google recommends, **web-search `developer.android.com` first**. This applies to everything — Compose `combinedClickable`, `DropdownMenu`, large-screen (>= 600 dp) layout patterns for foldables, `File.setLastModified` semantics for the scratch-prune test. If you catch yourself writing "Google recommends X" without having searched for it in this session, stop and search.

## What slice 07 ships (one-paragraph summary)

Tapping a **raw** clip in the gallery routes into the same Trim → Editor flow as a fresh capture (via the new `EditorSource.GalleryClip(rawId)` variant added in this slice). Tapping a **boomerang** still plays it in `LoopingPreview` (unchanged). A 3-state filter chip row at the top of the gallery toggles All / Boomerangs / Raw. Raw thumbnails get a small "R" badge in the bottom-right. Long-press menus: raw → "Make a boomerang" / "Delete"; boomerang → "Re-edit from source" (grayed when source raw is missing) / "Delete". Deleting a raw that has linked boomerangs prompts with a count and leaves the boomerangs intact. Orphan scratch files older than 24 h are pruned at app start.

The full slice spec lives in `docs/active/boomerang-rollout/07-gallery-tap-to-edit.md`. **That doc is your PRD for this PR.** Treat it as authoritative.

## Before Writing Any Code — Read These Files (in this order)

1. **`CLAUDE.md`**.
2. **Every file in `docs/lessons_learned/`** — 002, 003, 004, 008 all apply.
3. **`docs/DEFINITION_OF_DONE.md`**.
4. **`docs/active/boomerang-editor/IMPLEMENTATION.md`** — §3.2 (Edit-from-gallery path), §7 (data layer — `RecordedVideo.kind` was added back in slice 02; you're surfacing it now).
5. **`docs/active/boomerang-rollout/README.md`**.
6. **`docs/active/boomerang-rollout/02-auto-route-trim-and-default-save.md`** — for the `EditorSource` sealed interface this slice adds the second variant to, and the storage model.
7. **`docs/active/boomerang-rollout/07-gallery-tap-to-edit.md`** — your PRD. End-to-end, twice.
8. **`docs/PRD-mission-control.md`** §3 (Design System Tokens) — for the badge `NeonPurple` 80%, filter chip gradient.
9. **`docs/ANDROID_STANDARDS.md`** — especially the accessibility section (touch targets for the long-press / context menus) and Section 11 (Android 16) for large-screen behavior on foldables.
10. **`docs/TEST_COVERAGE.md`**.

**Read all of these before touching any code.**

## Phase 1: Cut the branch + capture a green baseline

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
git checkout main
git pull --rebase
git checkout -b feature/boomerang-slice-07-gallery-tap-to-edit
.\gradlew.bat clean assembleDebug --console=plain
```

`BUILD SUCCESSFUL` + exit 0 + zero `e:`.

## Phase 2: Web-verify the moving pieces

- `androidx.compose.foundation.combinedClickable(onClick, onLongClick, ...)` — current parameter order and indication / interaction-source handling.
- `androidx.compose.material3.DropdownMenu` + `DropdownMenuItem` — current anchoring, dismissal behavior.
- `androidx.compose.material3.AssistChip` / `FilterChip` — current API for the All / Boomerangs / Raw filter row.
- Window-size-class guidance for foldables (large screen, ≥ 600 dp) — the gallery should breathe, not just stretch a 3-col grid.
- `File.setLastModified(time)` — confirm it's reliable on the device's filesystem for the scratch-prune unit test (sometimes returns false on certain Android FS configurations).

If anything has drifted, **stop and surface** before coding.

## Phase 3: Implement to the slice spec

- **`OpenRangUiState.kt`** — extend `EditorSource` with `data class GalleryClip(val rawId: Long) : EditorSource`. Add `GalleryFilter { ALL, BOOMERANGS, RAW }` enum.
- **`OpenRangViewModel.kt`** — add `startEditorFromGallery(rawId: Long)` (resolves the raw via `videoStorage.loadRecordedVideos().firstOrNull { it.id == rawId && it.kind == RAW }`, posts `Trim(GalleryClip(rawId))`); `playBoomerang(video)` (posts `LoopingPreview(...)`); `deleteVideoAndRefresh(video)` (calls `videoStorage.deleteVideo` wrapped in try/catch IOException per Lesson 003, re-emits `recordedVideos`); `setGalleryFilter(filter)`. On `init`, kick off `videoStorage.pruneStaleScratch(24.hours)` in `viewModelScope`.
- **`VideoStorageRepository`** — add `fun pruneStaleScratch(olderThanMs: Long)`. Implementation: list `cacheDir/scratch/` and `cacheDir/scratch/reversed/`, delete files with `lastModified < (now - olderThanMs)`. Log the count.
- **Trim + BoomerangEditor source resolution** — when source is `GalleryClip(rawId)`, resolve the file via `videoStorage.loadRecordedVideos().first { it.id == rawId }`. Saving from a `GalleryClip` source skips the scratch-promotion step (raw is already in the gallery).
- **`ui/GalleryScreen.kt`** — add filter chip row above the grid. Add raw badge overlay on `RAW` thumbnails (bottom-right, 24 dp, `NeonPurple` 80%, "R" glyph). Wire `combinedClickable` (tap + long-press) per slice doc. `DropdownMenu` for context menus.

**Stay scoped to slice 07.** This is the final slice — no slice-08+ surface exists. Do NOT add v1.5 stretch goals like drag-to-rearrange, multi-select, search, share-from-gallery, etc. Those are out-of-scope per the rollout README.

## Phase 4: Test

- Unit tests: `OpenRangViewModelTest` for `startEditorFromGallery` (existing raw → posts `Trim(GalleryClip)`; non-existent rawId → no-op); `playBoomerang` (posts `LoopingPreview`); `deleteVideoAndRefresh` (removes file via fake + re-emits list); `setGalleryFilter` (filters the exposed list).
- `VideoStorageRepositoryImplTest`: `pruneStaleScratch` deletes files older than threshold, leaves newer ones alone. Use `TemporaryFolder` + `File.setLastModified` (verify it works on your test FS first).
- Instrumented `GalleryScreenTest`: filter chips switch visible items; raw badge on raws not boomerangs; tap raw routes to `Trim(GalleryClip)`; tap boomerang routes to `LoopingPreview`; long-press menus show correct items per kind.
- End-to-end: capture 2 raws + render 1 boomerang → gallery shows 3 items, one badged → filter Raw shows 2 → tap a raw → Trim opens with raw loaded → long-press boomerang → "Re-edit from source" → editor opens on the **source raw** (not the rendered output).
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

Walk the slice 07 manual QA on emulator + **Pixel 10 Pro Fold (both folded AND unfolded)**:

- Mix of raws + boomerangs in gallery — filter chips work correctly.
- Raw badge readable at thumbnail size on both folded and unfolded screen.
- Delete a raw with 2 linked boomerangs → dialog says "2 boomerang(s)" correctly; after delete, the boomerangs' "Re-edit from source" menu item is grayed out.
- Process kill + relaunch: scratch files >24 h old are pruned. Verify via:
  ```powershell
  adb shell run-as com.openrang.app ls cache/scratch/
  adb shell run-as com.openrang.app ls cache/scratch/reversed/
  ```
- Re-edit from a boomerang opens its actual source raw — not the rendered boomerang.
- Tap a raw from the gallery → Trim opens with the raw loaded → save a new boomerang → both raws and the new boomerang appear in gallery.
- **Large-screen (unfolded) layout** sanity-checked — the grid should breathe (more columns, larger thumbnails), not just stretch the 3-col fixed layout to full width. If the layout looks wrong on unfolded, that may warrant a separate small slice; flag in the PR description.

**Capture screenshots** of:
1. Gallery with mixed raws + boomerangs + filter chip selected (folded screen).
2. Same view on the unfolded screen.
3. Long-press context menu for a boomerang with "Re-edit from source" visible.

All attached to the PR.

## Phase 6: Open the PR

- Push to `stozo04/OpenRang`.
- PR title: `Slice 07 — Gallery tap-to-edit + raw/boomerang distinction (v1 complete)`.
- PR description: acceptance-criteria checklist from `docs/active/boomerang-rollout/07-gallery-tap-to-edit.md`, each box checked.
- Attach the screenshots.
- State what was not verified, particularly around very large numbers of clips (>100) — performance was not exercised at scale.
- **Final note:** this PR closes the boomerang rollout. Once merged, move `docs/active/boomerang-editor/` and `docs/active/boomerang-rollout/` to `docs/completed/` per the `docs/active/README.md` convention. Update the "Current Active Features" table accordingly.

## Behavioral Rules

- **PRD-first.** Push back before coding around what seems wrong.
- **Web-search before every API claim.**
- **Lessons compliance.** No `collectAsState(` (Lesson 002), 8-digit Color literals (Lesson 001), all repository writes wrapped in `try / catch (IOException)` (Lesson 003 — `deleteVideoAndRefresh` is subject to this), no `Context` on VM methods (Lesson 004), zipalign `(OK)` (Lesson 011).
- **`BUILD SUCCESSFUL` is not enough.**
- **Done is the DoD gate.** This is the LAST slice — DoD matters most here.
- **Stay scoped.** Slice 07 only. No v1.5 stretch goals.

## When to stop and come back to Steven

- If the slice doc and a lesson disagree.
- If the unfolded (>=600 dp) layout is meaningfully broken and would require its own slice to fix properly — flag it; don't ship a hack.
- If `pruneStaleScratch` deletes things it shouldn't (e.g., catches a scratch file from an in-flight render in another lifecycle).
- If the green baseline isn't green.
- If `zipalign` regresses.
- If the work is meaningfully larger than the slice doc implies (would be > ~500 LOC).
- **After this slice merges**, check in before moving the doc folders to `docs/completed/` — that's a notable structural change to `docs/active/` and Steven should confirm.
