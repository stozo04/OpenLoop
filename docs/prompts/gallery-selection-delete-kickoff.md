# Kickoff — Gallery: long-press selection + Undo delete

> Paste this whole file into a fresh agent session to implement the feature. It is self-contained.

## Your task

Replace the gallery's per-thumbnail trash-can delete with a **long-press multi-select model + a contextual action bar + an Undo snackbar** (the real file deletion is **deferred** until the snackbar is dismissed). This resolves the genuine bug in **GitHub issue #35** ("delete is instant and irreversible") and removes the emoji 🗑 clutter from every tile.

This was designed and approved in a prior session. The "why": the old design stamped a coral emoji trash can on every thumbnail, deleted instantly with no undo, and the icon clashed with the app's Material/vector icon language. Long-press selection (Google Photos–style) declutters the grid, makes delete safe via Undo, and scales to multi-select.

## First — required reading (do this before coding)

Per `CLAUDE.md` (repo root):
1. Re-read `CLAUDE.md`.
2. Read **every** file in `docs/lessons_learned/` (esp. 001 color literals, 002 lifecycle flow collection, 014 exhaustive `when`, 015 state-routed BackHandler, 016 defer high-frequency reads).
3. Read `docs/DEFINITION_OF_DONE.md`, `docs/ANDROID_STANDARDS.md` (§7 a11y, §9 threading), `docs/PRD-mission-control.md` (architecture + gallery spec).
4. **Do not trust training data on Android APIs — web-search `developer.android.com` first** (e.g. `Modifier.combinedClickable`, `SnackbarHostState.showSnackbar` result handling, `rememberSaveable` Savers).
5. Skim GitHub issue #35 (`gh issue view 35`) — this feature is its "Add a confirmation (or undo) to delete" checkbox.

## Design-system context (already shipped — USE these, don't reinvent)

A `ui/theme/` package now exists and is the single source of truth. Relevant tokens (in `io.github.stozo04.openloop.ui.theme`):
- `ElectricLime` `#CDFF4F` = primary accent (flat). `LimeInk` `#15200A` = text/icon on lime.
- `CoralRed` `#FF5A5F` = error/destructive/record.
- `OverlayWhite` / `OverlayWhiteBorder` = glassy controls over video. `SurfaceContainerHigh` = card fills. `OutlineVariant` = hairlines. `Canvas` = app background.
- Type: `MaterialTheme.typography` (Space Grotesk display / Inter body / JetBrains Mono via `TimerTextStyle`). Shapes: `MaterialTheme.shapes`.
- NOTE: most screens still hardcode `fontSize`/`RoundedCornerShape` (type/shape *adoption* is a separate pending task). For NEW code you write, prefer `MaterialTheme.typography.*`, `MaterialTheme.shapes.*`, and the named color tokens. Do not introduce inline hex (Lesson 001).

## Current code you will touch

**`app/src/main/java/io/github/stozo04/openloop/ui/GalleryScreen.kt`**
- `GalleryScreen(viewModel, onBackClick, onImportVideo)` collects `viewModel.recordedVideos` into `videos`, renders a top bar (back ✕ import) + a `LazyVerticalGrid` of `VideoThumbnailCard`, and a `BackHandler(enabled = selectedVideo == null) { onBackClick() }` plus a tap-to-play `LoopingVideoOverlay`.
- `VideoThumbnailCard(video, onClick, onDelete)` currently has a **top-end delete Box** with an emoji `🗑` on a `CoralRed` circle that calls `onDelete()` immediately. **Remove this delete control entirely.**

**`app/src/main/java/io/github/stozo04/openloop/ui/OpenLoopViewModel.kt`**
- `val recordedVideos: StateFlow<List<RecordedVideo>>` (backed by `_recordedVideos`).
- `fun deleteVideo(video: RecordedVideo)` → `videoStorage.deleteVideo(video)` then reloads. (Keep or supersede; see plan.)
- One-shot events: `private val _events = Channel<BoomerangEvent>(BUFFERED)`, `val events: Flow<BoomerangEvent>`. `sealed interface BoomerangEvent { Share, Saved, Failed, ImportTooLong, ImportFailed }`.
- `fun loadRecordedVideos()`, `fun navigateToGallery()`.

**`app/src/main/java/io/github/stozo04/openloop/MainActivity.kt`**
- Has an app-level `snackbarHostState` + a `LaunchedEffect` collecting `viewModel.events`. The `BoomerangEvent.Saved` branch shows a snackbar with a **"View" action** and handles `SnackbarResult.ActionPerformed`. Mirror this pattern for Undo. `SnackbarHost(hostState = snackbarHostState, ...)` is already wired into the scaffold.

**`app/src/main/java/io/github/stozo04/openloop/data/VideoStorageRepository.kt` / `Impl.kt`**
- `suspend fun deleteVideo(video: RecordedVideo)` (runs on `Dispatchers.IO`). `RecordedVideo.id: Long`.

**`app/src/main/res/values/strings.xml`** — add new strings here (there are existing `snackbar_*` and `gallery_*` strings to match style).

## Implementation plan

### 1) ViewModel — deferred deletion
- Add `private val _pendingDeletionIds = MutableStateFlow<Set<Long>>(emptySet())` + public `pendingDeletionIds: StateFlow<Set<Long>>`.
- Add an in-memory `private var pendingBatch: List<RecordedVideo> = emptyList()`.
- Add `val visibleVideos: StateFlow<List<RecordedVideo>>` = `combine(recordedVideos, pendingDeletionIds) { vids, pending -> vids.filterNot { it.id in pending } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())` (collect Lesson 002 patterns; import `combine`, `stateIn`, `SharingStarted`).
- `fun requestDeleteVideos(videos: List<RecordedVideo>)`:
  - if `pendingBatch.isNotEmpty()` → `commitPendingDeletion()` first (supersede),
  - set `pendingBatch = videos`, `_pendingDeletionIds.value = videos.map { it.id }.toSet()`,
  - `viewModelScope.launch { _events.send(BoomerangEvent.LoopsDeleted(videos.size)) }`.
- `fun undoPendingDeletion()` → `pendingBatch = emptyList(); _pendingDeletionIds.value = emptySet()`.
- `fun commitPendingDeletion()`:
  - capture `val batch = pendingBatch`; return if empty; clear `pendingBatch` + ids,
  - `viewModelScope.launch { batch.forEach { videoStorage.deleteVideo(it) }; _recordedVideos.value = videoStorage.loadRecordedVideos() }`.
- Safe-by-design: batch is in-memory, so process death before commit ⇒ files survive (implicit undo, never data loss). Note this in a comment.
- Keep the old `deleteVideo(video)` only if still referenced; otherwise remove it and any now-dead references.

### 2) Events + MainActivity
- Add `data class LoopsDeleted(val count: Int) : BoomerangEvent` to the sealed interface.
- In MainActivity's events collector, add a branch: show snackbar with message `resources.getQuantityString(R.plurals.gallery_loops_deleted, count, count)` and action label `getString(R.string.undo)`, `duration = SnackbarDuration.Short`. On result: `ActionPerformed -> viewModel.undoPendingDeletion()`, else `viewModel.commitPendingDeletion()`. (Strings must be resolved in composable scope like the existing ones.)

### 3) Strings (`res/values/strings.xml`)
- `<string name="undo">Undo</string>`
- `<plurals name="gallery_loops_deleted"> <item quantity="one">1 loop deleted</item> <item quantity="other">%d loops deleted</item> </plurals>`
- `gallery_selection_count` (e.g. `%d selected`), `gallery_delete_selected` (content desc "Delete selected"), `gallery_exit_selection` (content desc "Exit selection").

### 4) GalleryScreen — selection model
- Collect `viewModel.visibleVideos` (NOT `recordedVideos`) so deleted tiles vanish instantly and reappear on undo.
- `var selectedIds by rememberSaveable(stateSaver = <Saver for Set<Long>>) { mutableStateOf(emptySet<Long>()) }` — or store as `LongArray`/`List<Long>` via `rememberSaveable`. `val inSelectionMode = selectedIds.isNotEmpty()`.
- `VideoThumbnailCard`: replace `onClick` with `combinedClickable(onClick = { if (inSelectionMode) toggle(id) else play() }, onLongClick = { addToSelection(id) })`. Remove the `onDelete` param + the trash Box. Add a **selected overlay**: a lime ring (`border(2.dp, ElectricLime, shape)`) + a check badge (`Icons.Filled.Check` on a small `ElectricLime` circle with `LimeInk` tint) in a corner, plus a slight scrim/scale. Verify whether `combinedClickable` needs `@OptIn(ExperimentalFoundationApi::class)` in this Compose version (web-search/confirm) and add it if so.
- **Top bar**: when `inSelectionMode`, swap the back/import bar for a **contextual action bar**: a leading ✕ (`Icons.Filled.Close`, clears `selectedIds`), a `gallery_selection_count` label (`MaterialTheme.typography.titleMedium`), and a trailing **Delete** (`Icons.Outlined.Delete`). Delete → `viewModel.requestDeleteVideos(visibleVideos.filter { it.id in selectedIds })` then `selectedIds = emptySet()`. Otherwise show the existing back + import bar (back = neutral glass `OverlayWhite` + `Icons.AutoMirrored.Filled.ArrowBack`; import = flat `ElectricLime` + `LimeInk` `VideoLibrary` icon — already implemented, keep).
- **BackHandler** (Lesson 015 — gate, don't always intercept): if `selectedVideo != null` → let the dialog handle it (keep disabled); else if `inSelectionMode` → clear selection; else → `onBackClick()`.
- Accessibility (ANDROID_STANDARDS §7): ≥48 dp targets; selected tiles announce selected state via `semantics { selected = ... }`; action-bar buttons get `contentDescription`s + `Role.Button`.

### 5) Tidy
- The `ic_film_slate` drawable is already unused by the gallery; leave the file unless you confirm it's unused everywhere (then it's a separate cleanup).
- Remove any now-unused imports (`painterResource`, etc.) to keep lint clean.

## Edge-case decisions (already approved)
- A single long-press enters multi-select immediately (no separate single-selected state).
- Selection survives rotation (`rememberSaveable`).
- Starting a new delete while one is pending commits the previous batch first.
- Undo defers the real file delete; process death before commit = implicit undo (safe).

## Tests (add these)
- VM unit tests (`app/src/test/...`): `requestDeleteVideos` hides ids + emits `LoopsDeleted`; `undoPendingDeletion` restores; `commitPendingDeletion` calls `videoStorage.deleteVideo` per item + reloads; superseding commits the prior batch. Follow `docs/TEST_COVERAGE.md` (fake repo, `StandardTestDispatcher`, Lesson 008).
- Gallery Compose UI test (`app/src/androidTest/...`): long-press a tile → action bar appears with "1 selected"; tap Delete → tile removed from grid; assert `requestDeleteVideos` invoked (use a fake/recording VM or hoisted stateless content like `TrimScreenContent`/`BoomerangEditorContent` precedent). Prefer extracting a stateless `GalleryContent` if it makes testing cleaner.

## Definition of Done (non-negotiable — see docs/DEFINITION_OF_DONE.md)
1. Clean **debug AND release** build (`BUILD SUCCESSFUL`, exit 0, zero `e:` — never trust `| tail`-masked exit codes).
2. Unit + instrumented tests pass (0 failures).
3. `./gradlew :app:lintDebug` reports **no new** issues; run IDE inspections (`docs/STATIC_ANALYSIS.md`).
4. **Run the app on an emulator/device, exercise long-press → select → delete → undo, and capture a screenshot as proof.** Attach it to the PR.
5. Honestly state anything you could not verify + a manual QA checklist.

## Docs + tracking to update (REQUIRED)
1. Create `docs/active/gallery-selection-delete/IMPLEMENTATION.md` following `docs/active/CLAUDE.md` (problem / scope / architecture / steps / testing / acceptance). Reference `docs/PRD-mission-control.md`.
2. Update **GitHub issue #35**: check off the "Add a confirmation (or undo) to delete" box in section 3, and post a short progress comment (`gh issue comment 35 ...`) summarizing what shipped. The companion review canvas is `openloop-ui-ux-review.canvas.tsx`.
3. If a new lesson emerges during review, add it to `docs/lessons_learned/` per that folder's README.
4. Branch name: `feature/gallery-selection-delete` (per `.github` conventions). Only commit/open a PR when the user asks.

## Acceptance criteria
- [ ] No per-thumbnail delete control; grid is content-only at rest.
- [ ] Long-press enters multi-select; tap toggles in-mode, plays out-of-mode.
- [ ] Contextual action bar shows count + Delete + exit; system back exits selection first.
- [ ] Delete shows an Undo snackbar; Undo restores instantly; dismiss commits the real file delete.
- [ ] Deleted tiles disappear immediately (optimistic) via `visibleVideos`.
- [ ] No inline hex; uses theme tokens + `MaterialTheme` for any new text/shape.
- [ ] Accessibility + Compose-perf properties preserved; DoD cleared with a screenshot.
