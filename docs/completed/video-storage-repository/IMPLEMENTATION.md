# VideoStorageRepository — Remove Context from OpenLoopViewModel

**Issue:** [#10](https://github.com/stozo04/OpenLoop/issues/10) · **Branch:** `feature/video-storage-repository` · **Shipped via:** PR #17 · **Labels:** architecture, refactor, tech-debt

## Problem statement

`OpenLoopViewModel` accepted `android.content.Context` as a method parameter in four places (`startBurstCapture`, `loadRecordedVideos`, `deleteVideo`, `navigateToGallery`) to reach `cacheDir` / `filesDir`. This violates Google's explicit ViewModel rule — *"A ViewModel must never reference a view, Lifecycle, or any class that may hold a reference to the activity context"* ([ViewModel Overview](https://developer.android.com/topic/libraries/architecture/viewmodel)) — forces every ViewModel test to `mockk<Context>`, and would compound as Phase 3 (`VideoProcessor`) adds more filesystem work. Captured as [lesson 004](../../lessons_learned/004-viewmodel-no-context-parameters.md); flagged as FAIL #3 in the PR #5 review.

## Scope

**In:**
- New `VideoStorageRepository` interface + `VideoStorageRepositoryImpl` (data layer), mirroring `UserPreferencesRepository`.
- Move `RecordedVideo` from the `ui` package to the `data` package (it is a data-layer model).
- `OpenLoopViewModel` constructor takes `VideoStorageRepository`; every `Context` parameter removed.
- `Factory` takes both repositories (kept Context-free — consistent with how `UserPreferencesRepository` is already wired). `MainActivity` builds `VideoStorageRepositoryImpl(applicationContext.cacheDir, applicationContext.filesDir)`.
- Update call sites: `MainActivity`, `CameraScreen`, `GalleryScreen`.
- Replace `mockk<Context>` in `OpenLoopViewModelTest` with a `FakeVideoStorageRepository`; add `VideoStorageRepositoryImplTest` using `TemporaryFolder`.

**Out:**
- `CameraManager`'s Context-like construction (CameraX lifecycle binding — a separate, sanctioned concern).
- Phase 3 `VideoProcessor` itself. This refactor is the structural prerequisite; the repository is the seam it will plug into.

## Architecture

```
MainActivity (only Context bridge)
  └─ OpenLoopViewModel.Factory(userPrefs, videoStorage)   ← repositories, no Context
       └─ OpenLoopViewModel(userPrefs, videoStorage)      ← zero Context references
            └─ VideoStorageRepository (interface)
                 ├─ VideoStorageRepositoryImpl(cacheDir, filesDir)   [prod]
                 └─ FakeVideoStorageRepository (in-memory)            [test]
```

Deviation from the issue's Step 4: the issue showed `Factory(context: Context)`. The existing code already keeps `Factory` repo-only and bridges Context in `MainActivity` (for DataStore). We matched that established pattern instead of re-introducing Context into the Factory — confirmed with the owner. See PRD §4.2 / §4.8 and decision-log entry 13.

## Implementation steps (done)

1. `data/VideoStorageRepository.kt` — interface + `RecordedVideo` data class.
2. `data/VideoStorageRepositoryImpl.kt` — holds `cacheDir`/`filesDir`; `saveFinalizedVideo` / `loadRecordedVideos` / `deleteVideo` logic moved verbatim from the ViewModel.
3. `ui/OpenLoopViewModel.kt` — inject `videoStorage`; drop all `Context` params; `deleteVideo` now reloads in the ViewModel (repository no longer self-refreshes); `Factory` takes both repos.
4. `MainActivity.kt` — construct `VideoStorageRepositoryImpl` from `applicationContext` and pass to `Factory`.
5. `CameraScreen.kt` / `GalleryScreen.kt` — drop the `context` argument; import `RecordedVideo` from `data`.
6. Tests — `FakeVideoStorageRepository` + refactored `OpenLoopViewModelTest`; new `VideoStorageRepositoryImplTest`.
7. Docs — PRD §3/§4.2/§4.8/§6/§7 + decision log; this folder.

## Testing plan

- **Unit (JVM):** `OpenLoopViewModelTest` (26) with the fake — no Context/File mocks. `VideoStorageRepositoryImplTest` (8) against `TemporaryFolder`; `MediaMetadataRetriever` mocked via `mockkConstructor`.
- **Instrumented (UI):** the 10 onboarding/permission regression tests. On API 36 these initially crashed in Espresso (`InputManager.getInstance()` removed in Android 16) — a pre-existing tooling gap fixed by the `androidx.test` bump in **PR #16**. After rebasing this branch on the merged #16, all **10/10 instrumented tests pass** on the Pixel_10 AVD (API 36).
- **Manual QA:** see PR checklist — record a burst → preview → gallery → delete, on an emulator.

## Acceptance criteria

Tracks the Issue #10 checklist. Key gate: `grep "Context"` in `OpenLoopViewModel.kt` returns **only comment lines** (zero in any signature), and the ViewModel test compiles and passes without `mockk<Context>`.
