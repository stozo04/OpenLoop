# Gallery — long-press multi-select + Undo delete

> Branch: `feature/gallery-selection-delete` · Issue: [#35](https://github.com/stozo04/OpenLoop/issues/35) ("delete is instant and irreversible")
> Architecture reference: `docs/PRD-mission-control.md` (Gallery spec + ViewModel API table).

## Problem statement

The gallery stamped a coral 🗑 emoji on every thumbnail. Tapping it deleted the loop **instantly
and irreversibly** — no confirmation, no Undo — and the emoji clashed with the app's Material/vector
icon language, cluttering every tile. This is the genuine bug behind Issue #35.

## Scope

**In:**
- Remove the per-thumbnail delete control entirely (grid is content-only at rest).
- Google-Photos-style **long-press multi-select**: long-press enters selection, tap toggles in-mode
  / plays out-of-mode.
- A **contextual action bar** (exit ✕ · "N selected" · Delete) that replaces the back/import bar
  while selecting.
- **Deferred deletion + Undo snackbar**: a delete optimistically hides the tiles and shows an Undo
  snackbar; the real file delete is committed only when the snackbar is dismissed.
- Selection survives rotation; system Back exits selection before leaving the gallery.

**Out (explicitly):**
- Type/shape token adoption across the gallery (separate pending task).
- Deleting the now-unused `ic_film_slate` drawable (separate cleanup — see Lint note below).
- A trash/recently-deleted bin (Undo is the only recovery affordance).

## Architecture

Fits the existing MVVM + sealed-state machine (`docs/PRD-mission-control.md`). No new layers.

- **`OpenLoopViewModel`** — deferred-deletion model:
  - `pendingDeletionIds: StateFlow<Set<Long>>` + an in-memory `pendingBatch: List<RecordedVideo>`.
  - `visibleVideos: StateFlow<List<RecordedVideo>>` = `combine(recordedVideos, pendingDeletionIds)`
    filtering out pending ids, shared with `WhileSubscribed(5_000)` (Lesson 002).
  - `requestDeleteVideos(videos)` → sets the batch + ids, emits `BoomerangEvent.LoopsDeleted(count)`;
    supersedes any prior pending batch by committing it first.
  - `undoPendingDeletion()` → clears the batch + ids (tiles reappear; nothing deleted).
  - `commitPendingDeletion()` → `videoStorage.deleteVideo` per item off the main thread, then reload.
  - **Safe-by-design:** the batch is in-memory only, so process death before commit leaves every file
    intact (implicit Undo, never data loss).
- **`BoomerangEvent.LoopsDeleted(count: Int)`** — new one-shot event on the existing `Channel`.
- **`MainActivity`** — the events collector shows the Undo snackbar
  (`R.plurals.gallery_loops_deleted`, action `R.string.undo`, `SnackbarDuration.Short`): on
  `ActionPerformed → undoPendingDeletion()`, else `commitPendingDeletion()`. Mirrors the existing
  "Saved → View" pattern. The single app `SnackbarHost` is now app-styled (see UI polish below).
- **`GalleryScreen`** — split into a stateful shell + a stateless **`GalleryContent`**:
  - Shell collects `visibleVideos` (NOT `recordedVideos`), owns the tap-to-play `LoopingVideoOverlay`,
    and passes `backEnabledWhenIdle = selectedVideo == null` down (the overlay's Dialog owns Back
    while open — Lesson 015).
  - `GalleryContent` holds selection as local UI state (`rememberSaveable` via a `Set<Long>`↔`LongArray`
    `Saver`, surviving rotation), renders the resting top bar or the contextual action bar, and the
    grid. Tiles use `Modifier.combinedClickable` (stable foundation API — verified on
    developer.android.com) for tap + long-press. A selected tile gets a lime ring, dimming scrim, a
    check badge, a slight scale-down, and announces `semantics { selected = … }` (ANDROID_STANDARDS §7).
  - `BackHandler(enabled = backEnabledWhenIdle)`: in selection mode → clear selection; else → back out.

### UI polish (folded in during review)

- Post-save snackbar copy tightened: "Saved — view in gallery" + a "View" button was redundant →
  message is now **"Loop saved"** with the **View** action; "Couldn't save boomerang" → "Couldn't
  save loop" (consistent "loop" vocabulary).
- The single `SnackbarHost` is themed to the app's language: a rounded (16 dp) `SurfaceContainerHigh`
  card, `TextPrimary` content, `ElectricLime` action accent, floating above the nav bar — replacing
  the stock Material slab. Uses the `Snackbar(snackbarData = …)` overload so the action button's
  accessibility wiring is preserved.

## Implementation steps (as built)

1. ViewModel: `pendingDeletionIds`, `pendingBatch`, `visibleVideos`, `requestDeleteVideos`,
   `undoPendingDeletion`, `commitPendingDeletion`; `BoomerangEvent.LoopsDeleted`. Removed the old
   instant `deleteVideo(video)` (superseded; the repository-level `videoStorage.deleteVideo` stays).
2. MainActivity: Undo branch in the events collector; app-styled `SnackbarHost`.
3. `res/values/strings.xml`: `undo`, `gallery_selection_count` (plural), `gallery_delete_selected`,
   `gallery_exit_selection`, `gallery_loops_deleted` (plural); removed the dead `gallery_delete`;
   tightened `snackbar_saved` / `snackbar_save_failed`.
4. `GalleryScreen`: stateless `GalleryContent` + selection model + contextual action bar; removed the
   trash control, the `onDelete` param, and the now-unused `painterResource`/`CoralRed`-trash code.

## Testing

- **Unit (`OpenLoopViewModelTest`)** — added: `requestDeleteVideos` hides ids + emits `LoopsDeleted`;
  `undoPendingDeletion` restores without deleting; `commitPendingDeletion` deletes per item + reloads;
  a new delete supersedes (commits) the prior batch. Fake repo + `MainDispatcherRule` (Lesson 008).
  Removed the obsolete instant-`deleteVideo` test.
- **Instrumented (`GalleryScreenTest`)** — drives the stateless `GalleryContent` (Lesson 017, no
  mockk): long-press → action bar + "1 selected"; tap a second tile → "2 selected"; out-of-mode tap
  plays (not selects); Delete → `onRequestDelete` invoked + tile removed optimistically + selection
  exits; exit ✕ clears selection without deleting.

## Acceptance criteria

- [x] No per-thumbnail delete control; grid content-only at rest.
- [x] Long-press enters multi-select; tap toggles in-mode, plays out-of-mode.
- [x] Contextual action bar: count + Delete + exit; system Back exits selection first.
- [x] Delete shows an Undo snackbar; Undo restores instantly; dismiss commits the real delete.
- [x] Deleted tiles disappear immediately (optimistic) via `visibleVideos`.
- [x] No inline hex; theme tokens + `MaterialTheme` for new text/shape (Lesson 001).
- [x] A11y + Compose-perf preserved (≥48 dp targets, `selected` semantics, deferred reads).

## Definition of Done — status

- Debug **and** release builds: `BUILD SUCCESSFUL`, exit 0, zero `e:`.
- `./gradlew :app:lintDebug`: green; only remaining warning is the pre-existing unused
  `ic_film_slate` drawable (out of scope — separate cleanup per the kickoff).
- Unit tests: pass.
- Instrumented tests (`GalleryScreenTest`) + on-device long-press → select → delete → undo
  screenshot: **owner manual QA** (owner opted to test manually). Attach the screenshot to the PR.

## Manual QA checklist

- [ ] Long-press a loop → action bar shows "1 selected"; tap more tiles → count climbs.
- [ ] Tap a tile out of selection mode → it plays (looping overlay).
- [ ] Delete → tiles vanish + "N loops deleted" snackbar with Undo (app-styled, lime action).
- [ ] Undo → tiles reappear; file still on disk.
- [ ] Let the snackbar dismiss → files actually deleted; survive app restart.
- [ ] Rotate mid-selection → selection survives.
- [ ] System Back: exits selection first, then leaves the gallery.
- [ ] Record + process a loop → "Loop saved" snackbar reads tight + matches the app card/accent style.
