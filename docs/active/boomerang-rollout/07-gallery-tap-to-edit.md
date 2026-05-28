# Slice 07 — Gallery tap-to-edit + raw/boomerang distinction

> **Branch:** `feature/boomerang-slice-07-gallery-tap-to-edit`
> **Depends on:** slice 06 (share sheet).
> **Unblocks:** v1 ship.

---

## Problem

Through slice 06, the boomerang flow only works on **fresh captures** — the
user must record a new clip every time they want a new boomerang. But many
moments worth boomerang-ing are already in the user's gallery (clips captured
earlier in the session, or clips from a prior session). The gallery shows
these clips but offers no editing path; tapping one just plays it via
`LoopingPreview`.

Two missing pieces:

1. **Tap-to-edit from gallery** — tapping a *raw* clip routes to the same
   Trim → Editor flow used after a fresh capture. The user can re-edit any
   captured raw into a new boomerang at any time.
2. **Visual distinction** between raw clips and finished boomerangs — without
   it, the gallery feels noisy. Steven's "keep raw alongside boomerang"
   decision means both kinds coexist; this slice makes them legible.
3. **Re-edit from a boomerang** — long-pressing a boomerang opens the editor
   over its **source raw** (not the rendered boomerang), enabling
   "this boomerang is almost right, let me tweak it."

After this slice ships, OpenRang v1 is complete and ready for Play submission
(Issue #14 mechanics permitting).

## Scope

### In scope
- `RecordedVideo.kind` already added in slice 02 — now surfaced in the UI.
- Gallery thumbnail badge for `RAW` items (a small chip in the bottom-right
  corner). Boomerangs are unbadged (the default kind).
- Filter chip row at the top of the gallery: **All · Boomerangs · Raw**.
  Default `All`. Persists across navigation but not across app restarts
  (in-memory only).
- Tap behavior:
  - **Raw** → routes to `Trim(GalleryClip(rawId))` — same Trim screen, same
    editor flow as fresh capture.
  - **Boomerang** → routes to `LoopingPreview(boomerangPath)` — existing
    playback (unchanged behavior).
- Long-press behavior:
  - **Raw** → contextual menu: "Make a boomerang" (same as tap), "Delete".
  - **Boomerang** → contextual menu: "Re-edit from source" (enabled only if
    `sourceRawId` exists in `loadRecordedVideos()` results), "Delete".
- `EditorSource.GalleryClip(rawId)` plumbed through Trim and Editor.
- Scratch-resume on app launch: auto-prune `cacheDir/scratch/raw_*.mp4` files
  older than **24 hours** at app start (parent doc D-8).

### Out of scope
- Drag-to-rearrange in gallery. Out of v1.
- Multi-select for batch operations. Out of v1.
- Search / metadata-based filtering beyond the three-state chip. Out of v1.
- Share from gallery (long-press → Share). Possible v1.5 if requested — the
  FileProvider config from slice 06 supports it.

## UX deltas

### Gallery layout

```
┌────────────────────────────────────────────────┐
│  ← back          Gallery                       │   top bar
├────────────────────────────────────────────────┤
│  ( All )   ( Boomerangs )   ( Raw )            │   filter chip row
│   ●                                            │   "All" selected by default
├────────────────────────────────────────────────┤
│                                                │
│  ┌────┐  ┌────┐  ┌────┐                        │
│  │    │  │    │  │    │                        │   3-col grid (existing)
│  │  R │  │    │  │  R │                        │   "R" = raw badge bottom-right
│  └────┘  └────┘  └────┘                        │
│                                                │
│  ┌────┐  ┌────┐  ┌────┐                        │
│  │    │  │  R │  │    │                        │
│  └────┘  └────┘  └────┘                        │
│                                                │
└────────────────────────────────────────────────┘
```

### Raw badge

- 24 × 24 dp circular badge in the bottom-right corner of the thumbnail with
  4 dp inset from the edges.
- Background: `NeonPurple` 80%, 1 dp `GlassWhiteBorder` outline.
- Glyph: white capital "R" in caption typography, centered.
- Boomerangs intentionally have no badge — they're the default expected
  output, and unbadged thumbnails read as "ready to share."

### Filter chips

- Three chips horizontally aligned, evenly spaced, top of grid below the
  toolbar.
- Selected chip: `NeonCoral → NeonPurple` gradient fill, white text.
- Unselected: glass surface (`GlassWhite` 20% fill, `GlassWhiteBorder` 30%
  border), `NeonPurple` text.
- Tapping a chip filters the grid; the filter state lives in
  `GalleryUiState.activeFilter: GalleryFilter` (enum: `ALL`, `BOOMERANGS`, `RAW`).
- Smooth 200 ms cross-fade on chip switch.

### Tap behavior

The grid item's `Modifier.combinedClickable { ... }` handles:

```kotlin
combinedClickable(
    onClick = {
        when (video.kind) {
            RAW       -> viewModel.startEditorFromGallery(video.id)
            BOOMERANG -> viewModel.playBoomerang(video)
        }
    },
    onLongClick = {
        when (video.kind) {
            RAW       -> showRawContextMenu(video)
            BOOMERANG -> showBoomerangContextMenu(video)
        }
    },
)
```

### Long-press context menus

A `DropdownMenu` anchored to the long-pressed thumbnail.

**Raw menu:**
- "Make a boomerang" → same as tap.
- "Delete" → confirm dialog ("Delete this clip? This can't be undone."), then
  `videoStorage.deleteVideo(video)` (existing method).

**Boomerang menu:**
- "Re-edit from source" → if the source raw exists, route to
  `Trim(GalleryClip(sourceRawId))`. If the source has been deleted, item is
  grayed out with tooltip "Source clip no longer available."
- "Delete" → confirm dialog, delete just the boomerang (source raw is
  untouched).

### Deleting a raw with linked boomerangs

When the user deletes a raw that has boomerangs derived from it, the confirm
dialog reads:

> "Delete this clip? This will also break re-edit for **N boomerang(s)**
> derived from it. The boomerangs themselves will be kept."

After confirm: raw is deleted; the linked boomerangs lose their re-edit
affordance (the "Re-edit from source" menu item becomes grayed for them).

## Technical deltas

### `OpenRangUiState.kt`

`EditorSource` was added in slice 02 as a sealed interface with only
`ScratchClip`. Now add the second variant:

```kotlin
sealed interface EditorSource {
    data class ScratchClip(val uuid: String) : EditorSource
    data class GalleryClip(val rawId: Long) : EditorSource    // NEW
}
```

### `OpenRangViewModel.kt`

- Add `startEditorFromGallery(rawId: Long)`:
  - Resolves the raw via `videoStorage.loadRecordedVideos().firstOrNull { it.id == rawId && it.kind == RAW }`.
  - Posts `Trim(GalleryClip(rawId))`.
- Add `playBoomerang(video: RecordedVideo)` → posts `LoopingPreview(video.videoPath, 1.5f)`
  (preserving existing playback behavior).
- Add `deleteVideoAndRefresh(video: RecordedVideo)`:
  - Calls `videoStorage.deleteVideo(video)`.
  - Re-emits `recordedVideos` from `videoStorage.loadRecordedVideos()`.
  - Wrapped in `try / catch (IOException)` per Lesson 003.
- Add a `GalleryFilter` state field with mutator
  `setGalleryFilter(filter: GalleryFilter)`. Filter is applied as a UI-side
  derivation from `recordedVideos`.
- On app start, kick off `videoStorage.pruneStaleScratch(olderThanMs = 24.hours)`
  in `viewModelScope` — orphan cleanup per parent D-8.

### `VideoStorageRepository`

Add:

```kotlin
fun pruneStaleScratch(olderThanMs: Long)
```

Implementation: lists `cacheDir/scratch/`, deletes any file whose
`lastModified() < (now - olderThanMs)`. Logs the count of pruned files.

### `Trim` and `BoomerangEditor` source resolution

When the source is `GalleryClip(rawId)`, the Trim screen and editor resolve
the on-disk file via `videoStorage.loadRecordedVideos().first { it.id == rawId }`.
When source is `ScratchClip`, it uses `cacheDir/scratch/raw_<uuid>.mp4` (existing
behavior).

Saving from a `GalleryClip` source: the raw is already in the gallery, so the
"promote scratch to raw" step is **skipped**. Only the boomerang is registered.
The `boom_<ts>_from_<rawTs>.mp4` filename's `<rawTs>` is the existing raw's id.

### `ui/GalleryScreen.kt`

- Add filter chip row above the grid.
- Add raw badge overlay on `BOOMERANG` `false` items (i.e., on raws).
- Wire `combinedClickable` for tap + long-press as above.
- Add `DropdownMenu` for context menus.
- On chip selection, filter the `recordedVideos` list via the active filter.

### Performance note

`loadRecordedVideos()` already runs synchronously and is fast for hundreds of
files. If we ever exceed ~1000 clips it'll need pagination — track but don't
build for v1.

## Testing plan

### Unit tests

- `OpenRangViewModelTest`:
  - `startEditorFromGallery(rawId)` for an existing raw posts
    `Trim(GalleryClip(rawId))`.
  - `startEditorFromGallery(rawId)` for a non-existent raw is a no-op (does
    not crash, does not change state).
  - `playBoomerang(video)` posts `LoopingPreview(video.videoPath, 1.5f)`.
  - `deleteVideoAndRefresh(video)` removes the file (verifiable via the fake)
    and re-emits the list.
  - `setGalleryFilter(BOOMERANGS)` filters the exposed list to boomerangs only.
- `VideoStorageRepositoryImplTest`:
  - `pruneStaleScratch(olderThanMs)` deletes files older than the threshold
    and leaves newer ones alone. Use `TemporaryFolder` + `File.setLastModified`
    to control ages.

### Instrumented tests

- `GalleryScreenTest`:
  - Filter chips switch the visible items.
  - Raw badge renders on raws, not on boomerangs.
  - Tap on a raw routes to `Trim(GalleryClip(...))`.
  - Tap on a boomerang routes to `LoopingPreview`.
  - Long-press menu shows correct items per kind.
- End-to-end:
  - Capture 2 raws + render 1 boomerang → gallery shows 3 items, one badged.
  - Filter "Raw" → 2 items visible.
  - Tap a raw → Trim screen opens with the raw loaded.
  - Long-press the boomerang → "Re-edit from source" → editor opens on the
    source raw, not the rendered boomerang.

### Manual QA

- Mix of raws and boomerangs in gallery — chips filter correctly.
- Raw badge readable at thumbnail size on Pixel 10 Pro Fold (both folded and
  unfolded screen).
- Delete a raw that has 2 linked boomerangs → confirm dialog states "2
  boomerang(s)" correctly; after delete, the boomerangs' "Re-edit from
  source" menu items are grayed.
- Process kill, relaunch: any scratch files >24 h old are pruned (verify via
  `adb shell ls /data/data/com.openrang.app/cache/scratch/`).
- Screenshot of the gallery with mixed raws/boomerangs + filter chip selected
  attached to the PR.

## Acceptance criteria

- [ ] `assembleDebug` + `assembleRelease`: BUILD SUCCESSFUL, exit 0, zero `e:`.
- [ ] `testDebugUnitTest`: 0 failures.
- [ ] `connectedDebugAndroidTest`: 0 failures.
- [ ] `zipalign -c -P 16 -v 4 …` on release APK shows `(OK)` (Lesson 011).
- [ ] App launched on emulator AND Pixel 10 Pro Fold (both folded and
      unfolded states); gallery + tap-to-edit + long-press + filter chips
      all verified; screenshots attached.
- [ ] Re-edit-from-source verified: a boomerang's re-edit opens its actual
      source raw (not the rendered boomerang).
- [ ] Deleting a raw with linked boomerangs leaves the boomerangs intact.
- [ ] Scratch-prune at app start verified (logcat shows the prune count;
      manually populated old scratch files are removed).
- [ ] No `Color(0x…)` literal violates the 8-hex-digit rule (Lesson 001).
- [ ] All Flow collection uses `collectAsStateWithLifecycle()` (Lesson 002).
- [ ] All repository writes wrapped in `try / catch (IOException)` (Lesson 003).
- [ ] No `Context` parameter on any `OpenRangViewModel` method (Lesson 004).
- [ ] Unfolded large-screen (≥600 dp) layout sanity-checked on Pixel 10 Pro
      Fold — the grid should breathe, not just stretch to 3-col fixed.
      (May warrant a separate small slice if extensive; track as open
      question for ship.)
- [ ] PR description includes screenshots from folded + unfolded screens.
