# PRD-mission-control.md — OpenLoop Architecture & Component Specs

**Status:** Living document — update as the system evolves.
**Owner:** Steven Gates
**Last updated from build session:** 2026-05-27

---

## 1. Executive Summary

OpenLoop is an open-source Android camera app for creating custom, speed-controlled video loops. The system captures 1.5-second video bursts, generates seamless forward-backward loops via Media3 Transformer, and gives users real-time speed control (0.5x–3.0x) before exporting. All processing is 100% on-device.

The architecture follows MVVM with Jetpack Compose, a sealed-interface state machine, and a local file-based storage pipeline for videos and thumbnails. The app is designed to be device-agnostic (CameraX) and privacy-first (zero network requirements).

### Current build state

Phases 1–2 are complete (camera viewfinder, burst capture, gallery, onboarding). Phases 3–5 (loop generation, speed slider, export) are next. This PRD is the authoritative reference for all structural decisions.

---

## 2. Goals and Non-Goals

### Goals

- Seamless 1.5-second video burst capture with automatic stop
- Forward-backward loop generation entirely on-device using Media3 Transformer
- Real-time speed adjustment (0.5x–3.0x) with instant preview feedback
- Export with speed burn-in to device gallery for sharing
- Persistent local gallery with thumbnail caching for performant browsing
- Beautiful, polished UI matching the glassmorphic vaporwave aesthetic
- 100% unit test coverage for ViewModel logic; UI regression tests for layout-critical composables
### Non-Goals (this build window)

- Cloud storage or sync — all data stays on-device
- Social features (sharing to specific platforms, comments, likes)
- Audio recording toggle UI — audio is captured if permission exists, no toggle yet
- Custom capture duration — fixed at 1.5s for now
- Filters or visual effects — raw capture only
- Tablet or foldable-specific layouts

---

## 3. Architecture Overview

### Three Layers

```
┌─────────────────────────────────────────────────┐
│  UI Layer (Jetpack Compose)                     │
│  CameraScreen · OnboardingScreen · PreviewScreen│
│  GalleryScreen · MainActivity                   │
├─────────────────────────────────────────────────┤
│  Domain Layer (ViewModel + Processor)           │
│  OpenLoopViewModel · VideoProcessor (planned)   │
│  OpenLoopUiState (sealed interface)             │
├─────────────────────────────────────────────────┤
│  Data Layer (Preferences + Storage)              │
│  UserPreferencesRepository · DataStore           │
│  VideoStorageRepository · filesDir / cacheDir    │
├─────────────────────────────────────────────────┤
│  Platform Layer (CameraX + Media3 + Storage)    │
│  CameraManager · MediaMetadataRetriever         │
│  ExoPlayer · Transformer · Local filesystem     │
└─────────────────────────────────────────────────┘
```

### State Machine

All navigation is driven by a single `MutableStateFlow<OpenLoopUiState>` in `OpenLoopViewModel`. States:

| State | Description | Transitions to |
|-------|-------------|---------------|
| `Initializing` | Loading preferences from DataStore | `Onboarding` (first launch) or `CheckingPermissions` (returning user) |
| `Onboarding` | 3-page carousel (first launch) | `CheckingPermissions` |
| `CheckingPermissions` | Runtime permission check (3-state: granted / rationale / request) | `ReadyToCapture`, `PermissionRationale`, or `PermissionDenied` |
| `PermissionRationale` | Educational UI before re-requesting a previously-denied permission (`shouldShowRequestPermissionRationale`). "Grant" acknowledges; "Not now" cancels. | `CheckingPermissions` (Grant → launches the system dialog) or `PermissionDenied` ("Not now") |
| `PermissionDenied` | Permanent-denial screen with settings link | `CheckingPermissions` (retry) |
| `ReadyToCapture` | Live camera viewfinder | `Recording`, `Gallery` |
| `Recording` | Active 1.5s burst capture | `LoopingPreview` (success) or `ReadyToCapture` (failure) |
| `Processing` | (Planned) Loop generation in progress | `LoopingPreview` |
| `LoopingPreview` | ExoPlayer infinite loop preview | `ReadyToCapture` |
| `Gallery` | Recorded bursts grid | `ReadyToCapture` |

### Design System Tokens

| Token | Hex | Alpha | Role |
|-------|-----|-------|------|
| `NeonCoral` | `#FF5252` | 100% | Primary accent |
| `NeonPurple` | `#7C4DFF` | 100% | Secondary accent |
| `GlassWhite` | `#FFFFFF` | 20% | Glassmorphic fill |
| `GlassWhiteBorder` | `#FFFFFF` | 30% | Glassmorphic border |
| `DeepCharcoal` | `#1A1A1D` | 80% | Overlay bars |

Gradients: `NeonCoral → NeonPurple` horizontal for primary actions. Theme: `darkColorScheme` only.

---

## 4. Component Specifications

### 4.1 CameraManager.kt

**Purpose:** Manages CameraX lifecycle — preview binding, video recording, lens toggling.

| Method | Behavior |
|--------|----------|
| `startCamera(lifecycleOwner, previewView)` | Binds Preview + VideoCapture use cases at HD quality |
| `toggleCamera(lifecycleOwner, previewView)` | Switches between `LENS_FACING_BACK` and `LENS_FACING_FRONT`, rebinds |
| `startRecording(outputFile, callback)` | Records to file with audio (if permitted), fires `VideoRecordEvent` callbacks |
| `stopRecording()` | Stops active recording |
| `shutdown()` | Releases executor resources |

### 4.2 OpenLoopViewModel.kt

**Purpose:** MVVM hub. Owns UI state, recording lifecycle, and gallery navigation. Delegates all filesystem work to `VideoStorageRepository` (see §4.8).

**Dependencies (constructor-injected, no `Context`):** `UserPreferencesRepository`, `VideoStorageRepository`. The `Factory` takes both already-constructed repositories; `MainActivity` is the single place that bridges `Context` → repositories. The ViewModel holds **zero** references to `Context` (lesson 004 — enforceable by `grep`).

**State:** `MutableStateFlow<OpenLoopUiState>` exposed as `StateFlow`.

**Key flows:**

| Flow | Trigger | Behavior |
|------|---------|----------|
| `startBurstCapture(cameraManager)` | Shutter tap (state must be `ReadyToCapture`) | Sets `Recording`, writes to `videoStorage.rawCaptureFile`, starts CameraManager, launches 1500ms auto-stop coroutine. On finalize success, calls `videoStorage.saveFinalizedVideo(...)` and transitions to `LoopingPreview` (falls back to the raw capture path if the save returns null) |
| `stopBurstCapture` | Auto-timer or manual | Cancels coroutine job, stops CameraManager |
| `loadRecordedVideos()` | Gallery entry | Publishes `videoStorage.loadRecordedVideos()` to the `recordedVideos` flow |
| `deleteVideo(video)` | Gallery delete tap | Calls `videoStorage.deleteVideo(video)`, then reloads the list |
| `navigateToGallery()` | Home button tap | Sets `Gallery` state, loads videos |
| `navigateBackFromGallery` | Gallery back button | Sets `ReadyToCapture` |

### 4.3 CameraScreen.kt

**Purpose:** Live camera viewfinder with recording controls.

**Layout (top to bottom):**
1. Full-screen CameraX `PreviewView` (AndroidView, `FILL_CENTER`)
2. Top gradient bar: home button (top-left, neon gradient, 44dp) + recording indicator
3. Bottom gradient bar: 1.5s badge (glass, 54dp) | shutter button (86dp, dual-ring glow) | flip camera (glass, 54dp)

**Interactions:**
- Shutter: `viewModel.startBurstCapture(cameraManager)` (disabled while recording)
- Home: `viewModel.navigateToGallery()`
- Flip: `cameraManager.toggleCamera()`

### 4.4 OnboardingScreen.kt

**Purpose:** 3-page horizontal carousel introducing the app.

**Architecture:**
- Page data modeled as `OnboardingPage(title, drawableRes, glowColor, videoRawRes?)` data class with a list of 3 instances. `videoRawRes` is optional: when set, the card autoplays a muted, looping `res/raw` clip via an ExoPlayer-in-`AndroidView` (`OnboardingVideoCard`) instead of the static `drawableRes`; `drawableRes` remains the inspection-mode (`@Preview`) fallback. See `docs/active/onboarding-video/IMPLEMENTATION.md`.
- Navigation extracted into `OnboardingNavigation` (internal composable) — **MUST remain extracted to avoid ColumnScope.AnimatedVisibility bug** (see decision log)
- Dot indicators use `animateFloatAsState` for smooth size transitions
- Navigation buttons use `AnimatedVisibility` with `fadeIn + scaleIn` / `fadeOut + scaleOut`
- `OnboardingVideoCard` playback is lifecycle-aware (`LifecycleStartEffect`: plays on `ON_START` only while it is the settled page, pauses on `ON_STOP`) and released on leave-composition

**Private color palette:** `DeepIndigo`, `DarkPlum`, `VoidBlack`, `FrostedGlass`, `FrostedGlassBorder`

**Pages:**
1. "No Subscriptions & No Ads" — autoplaying looping boomerang video (coral glow); `onboarding_skater` drawable is the `@Preview` fallback
2. "Built by Everyone, For Everyone" — bubbles visual, purple glow
3. "Just Point, Tap & Loop!" — confetti visual, cyan glow

### 4.5 PreviewScreen.kt

**Purpose:** Full-screen looping video playback after capture.

- ExoPlayer with `REPEAT_MODE_ALL`, no player controls visible
- Top banner: "OPENLOOP" title + "RAW BURST PREVIEW (1.5S)" subtitle
- Bottom: "RETAKE BURST" button → `viewModel.resetToCapture()`
- (Planned) Speed slider integration in Phase 4

### 4.6 GalleryScreen.kt

**Purpose:** Grid display of all recorded bursts with playback and deletion.

**Layout:**
- Header: back button (64dp, glass + NeonPurple border, ArrowLeftIcon) + "YOUR BURSTS" title
- Empty state: "NO BURSTS YET" message
- Grid: `LazyVerticalGrid(GridCells.Adaptive(minSize = 110.dp))`, 9:16 aspect ratio cards (Decision Log #14)
- Cards: `BitmapFactory.decodeFile` thumbnail, press-scale animation (0.93x), delete button (coral circle, top-right)
- Playback overlay: full-screen `Dialog` with ExoPlayer loop + "CLOSE PREVIEW" gradient capsule button

### 4.7 MainActivity.kt

**Purpose:** Entry point. Handles permissions, state-based routing, theme.

- Permission launcher: `CAMERA` + `RECORD_AUDIO` via `ActivityResultContracts.RequestMultiplePermissions`
- `checkPermissions()` is a 3-state `when`: all-granted → proceed; `shouldShowRequestPermissionRationale` → `PermissionRationale`; else → launch the system dialog. The rationale "Grant" action launches the dialog directly (bypassing `checkPermissions()`) to avoid re-entering the rationale branch.
- Routing: `when (uiState)` dispatches to the appropriate screen composable
- Theme: `OpenLoopTheme` wrapping `darkColorScheme(primary = NeonCoral, secondary = NeonPurple, background = #121212)`
- Includes `CheckingPermissionsScreen` and `PermissionExplanationScreen` inline composables. `PermissionExplanationScreen` is shared by `PermissionRationale` (secondary action "Not now" → `PermissionDenied`) and `PermissionDenied` (secondary action "Open Device Settings") via a parameterized `secondaryActionLabel` / `onSecondaryAction`.

### 4.8 VideoStorageRepository.kt (data layer)

**Purpose:** The repository seam for all recorded-burst filesystem work. Defined as an interface so the ViewModel depends on a contract (not `Context`) and tests use a fake instead of mocking `Context`/`File` (lesson 004; [Data Layer](https://developer.android.com/topic/architecture/data-layer)). Mirrors the `UserPreferencesRepository` (+ `Impl`) pattern from PR #5.

**Interface:**

| Member | Behavior |
|--------|----------|
| `val rawCaptureFile: File` | Stable scratch path `cacheDir/raw_capture.mp4`; overwritten each capture |
| `saveFinalizedVideo(rawCapture): File?` | Copies the raw capture to `filesDir/videos/clip_<ts>.mp4`, extracts a 0ms thumbnail via `MediaMetadataRetriever` → `filesDir/thumbnails/clip_<ts>.jpg` (JPEG 90%). Returns the persisted `File`, or `null` on failure |
| `loadRecordedVideos(): List<RecordedVideo>` | Scans `filesDir/videos/clip_*.mp4`, maps to `RecordedVideo` (newest first); lazily extracts a thumbnail if one is missing |
| `deleteVideo(video)` | Removes the `.mp4` + `.jpg` (no reload — the caller refreshes) |

**Implementation:** `VideoStorageRepositoryImpl(cacheDir: File, filesDir: File)` holds only raw `File` handles — never a `Context`. Constructed in `MainActivity` from `applicationContext.cacheDir` / `applicationContext.filesDir`.

**Data class** (lives in the data layer, consumed by the UI):
```kotlin
data class RecordedVideo(
    val id: Long,             // epoch millis parsed from filename
    val videoPath: String,    // absolute path to .mp4
    val thumbnailPath: String // absolute path to .jpg
)
```

---

## 5. Planned Components (Phases 3–5)

### 5.1 VideoProcessor.kt (Phase 3)

**Purpose:** Media3 Transformer pipeline for loop generation.

**Pipeline:**
1. Input: `raw_capture.mp4` (1.5s forward clip)
2. Reversal: Transformer with reversed frame timestamps → `reversed_capture.mp4`
3. Concatenation: `Composition` / `EditedMediaItem` joining forward + reversed → `openloop_output.mp4`
4. Output: seamless loop file ready for preview

**Constraints:**
- Processing must complete in <1 second on mid-range devices
- `Processing` UI state shown during transform
- Error handling falls back to `ReadyToCapture` with a log
- Filesystem I/O goes through `VideoStorageRepository` (§4.8) — the Context-free seam already in place from Issue #10; the processor reads `rawCaptureFile` and writes finalized output via the repository, no new `Context` parameters

### 5.2 Speed Slider (Phase 4)

**Integration point:** `PreviewScreen.kt`

- Compose `Slider` range: 0.5f to 3.0f, default 1.5f
- Connected to `ExoPlayer.setPlaybackSpeed()`
- Speed value stored in `LoopingPreview` state for export

### 5.3 Export Pipeline (Phase 5)

**Purpose:** Burn selected speed into final video and save to device gallery.

- Transformer with `SpeedChangeEffect` (Media3 1.10.1's constant-speed video effect; audio is stripped per the boomerang pipeline, Decision D-3)
- Output to `MediaStore.Video.Media` (public gallery)
- Filename: `OpenLoop_Capture_<timestamp>.mp4`
- Completion toast, return to viewfinder

---

## 6. Data Layer

### File Structure

```
context.filesDir/
├── videos/
│   ├── clip_1716825600000.mp4
│   ├── clip_1716825660000.mp4
│   └── ...
└── thumbnails/
    ├── clip_1716825600000.jpg
    ├── clip_1716825660000.jpg
    └── ...

context.cacheDir/
└── raw_capture.mp4              # temporary, overwritten each capture
```

### Schemas

**RecordedVideo (in-memory):**
```kotlin
data class RecordedVideo(
    val id: Long,              // e.g. 1716825600000 (epoch millis from filename)
    val videoPath: String,     // e.g. "/data/.../files/videos/clip_1716825600000.mp4"
    val thumbnailPath: String  // e.g. "/data/.../files/thumbnails/clip_1716825600000.jpg"
)
```

**Naming convention:** `clip_<System.currentTimeMillis()>.mp4` / `.jpg`

### Refresh Strategy

| File | Populated by | Trigger | Strategy |
|------|-------------|---------|----------|
| `videos/clip_*.mp4` | `saveFinalizedVideo()` | Successful capture finalize | Create new file (never overwrite) |
| `thumbnails/clip_*.jpg` | `saveFinalizedVideo()` or `loadRecordedVideos()` | Save-time extraction or on-demand fallback | Create if missing |
| `cacheDir/raw_capture.mp4` | `startBurstCapture()` | Each new capture | Overwrite previous |

---

## 7. Testing Architecture

### Unit Tests (app/src/test/)

| Test | What it covers |
|------|---------------|
| `initial state is Onboarding` | Default state |
| `onOnboardingCompleted transitions to CheckingPermissions` | Onboarding → permissions flow |
| `onPermissionsChecked when granted/denied` | Permission branching |
| `showPermissionRationale transitions to PermissionRationale` | Rationale step (Issue #11) |
| `onRationaleAcknowledged transitions to CheckingPermissions` | Rationale acknowledge (Issue #11) |
| `rationale flow ending in grant/denial` | Full rationale path to terminal state (Issue #11) |
| `resetToCapture transitions state` | State reset |
| `startBurstCapture when not ready` | Guard clause |
| `startBurstCapture starts recording and delays stop` | Full capture lifecycle with time advance |
| `startBurstCapture failures fallback` | Error recovery |
| `stopBurstCapture cancels job` | Clean shutdown |
| `Finalize → LoopingPreview using saved video path` | Happy path (uses `videoStorage.saveFinalizedVideo` result) |
| `Finalize falls back to raw capture path when save fails` | Save-failure fallback |
| `Finalize → ReadyToCapture on error` | Error path |
| `navigateToGallery transitions to Gallery` | Navigation |
| `navigateToGallery loads videos from storage` | Gallery entry loads list |
| `navigateBackFromGallery transitions to ReadyToCapture` | Navigation |
| `loadRecordedVideos with empty storage` | Empty state |
| `deleteVideo removes video from storage and reloads` | Deletion flow |
| `recordedVideos flow starts empty` | Initial state |

> These run against a `FakeVideoStorageRepository` (in-memory) — no `mockk<Context>` / `mockk<File>` (lesson 004).

### Repository Unit Tests (app/src/test/) — `VideoStorageRepositoryImplTest`

Real-filesystem tests via JUnit `TemporaryFolder` (lesson 008); the Android-only `MediaMetadataRetriever` is mocked via `mockkConstructor`.

| Test | What it covers |
|------|---------------|
| `rawCaptureFile points at cacheDir raw_capture mp4` | Scratch path |
| `loadRecordedVideos returns empty when videos dir is missing` | Empty state |
| `loadRecordedVideos returns clips newest first` | Scan + sort |
| `loadRecordedVideos ignores non-clip files` | Filename filter |
| `loadRecordedVideos still lists a clip whose thumbnail is missing` | Lazy-thumbnail resilience |
| `deleteVideo removes both the video and its thumbnail` | Deletion |
| `deleteVideo with already-missing files does not throw` | Defensive deletion |
| `saveFinalizedVideo copies the raw capture into persistent storage` | Persist copy |

### UI Tests (app/src/androidTest/)

| Test | What it guards |
|------|---------------|
| `page0_nextButton_isDisplayedAndCentered` | Button centering regression |
| `page1_backAndNextButtons_areDisplayedAndCentered` | Button centering regression |
| `page2_ctaButton_isDisplayedAndFillsWidth` | CTA layout regression |
| `page0_doesNotShowPage1OrPage2Controls` | Mutual exclusivity |
| `page1_doesNotShowPage0OrPage2Controls` | Mutual exclusivity |
| `page2_doesNotShowPage0OrPage1Controls` | Mutual exclusivity |

---

## 8. Decision Log

| # | Decision | Reasoning | Trade-off |
|---|----------|-----------|----------|
| 1 | **Sealed interface for UI state** | Exhaustive `when` matching at compile time prevents missing state handling | Slightly more boilerplate than enum, but safer with data-carrying states like `LoopingPreview` |
| 2 | **Single ViewModel, no nav library** | App has <10 screens, all state-driven; Compose Navigation adds complexity without value at this scale | Will need migration if screen count grows significantly |
| 3 | **`filesDir` for video storage, not `cacheDir`** | Gallery videos are user-created content that must persist across sessions | Uses more permanent storage; need manual cleanup via delete |
| 4 | **Eager + lazy thumbnail extraction** | Eager at save time for speed; lazy fallback for resilience if thumbnail is somehow lost | Slightly more code than eager-only, but prevents blank grid cards |
| 5 | **OnboardingNavigation extracted as internal composable** | ColumnScope.AnimatedVisibility resolves to slide animations that cause layout jumping; extraction breaks the scope chain | Function is `internal` (not `private`) to enable UI regression testing |
| 6 | **OnboardingPage data class model** | Eliminates 3 separate `when` branches for title/drawable/glow; adding pages is a one-line change | Minor: data class is private to file, not reusable outside onboarding |
| 7 | **animateFloatAsState for dot indicators** | Smooth size interpolation instead of snap; uses float → `.dp` conversion to avoid `animateDpAsState` import ambiguity | Negligible performance cost |
| 8 | **BitmapFactory.decodeFile for thumbnails** | No image-loading library dependency (Coil/Glide); thumbnails are small local JPEGs | Will need Coil if we ever load remote images or need cache management |
| 9 | **Home button gets neon gradient, flip camera gets glass** | Home/gallery is a primary navigation action; flip camera is secondary utility. Visual weight should match importance | Flip camera is less discoverable in glass style |
| 10 | **Dark-only theme** | Matches vaporwave aesthetic; camera apps benefit from dark UI (less screen glare on subjects) | No light mode for accessibility preferences |
| 11 | **Onboarding persistence via Jetpack DataStore** | Replaced the intentional deferral — DataStore now persists `has_completed_onboarding` flag. Returning users skip straight to permission check. Uses Preferences DataStore (not SharedPreferences) with repository pattern for testability. | Added `Initializing` state to sealed interface for async DataStore read at startup |
| 12 | **No skip button on onboarding (intentional)** | 3 pages is short enough; forced traversal ensures users see all value props | Mild friction for power users; revisit post-launch |
| 13 | **`VideoStorageRepository` extracted; ViewModel is Context-free** (Issue #10) | Filesystem work moved behind a repository interface so `OpenLoopViewModel` holds no `Context` (Google ViewModel rule, lesson 004). Mirrors `UserPreferencesRepository`; `MainActivity` is the single Context→repository bridge. Tests use a fake, not `mockk<Context>`. | Two constructor deps instead of one; `RecordedVideo` moved from the `ui` to the `data` package |
| 14 | **Gallery grid uses `GridCells.Adaptive(minSize = 110.dp)`, not `Fixed(3)`** (slice 07) | The app targets foldables / large screens (slice-07 device pass ran on a Pixel 10 Pro Fold). A fixed 3-column grid produces oversized cards on a wide/unfolded display; an adaptive grid keeps each thumbnail cell ~110dp and fills the available width — 3 columns on a phone, more when unfolded — which Google recommends for adaptive layouts. | Column count is no longer fixed/predictable, so any layout regression test must assert by cell size, not by a hard-coded column count. |

---

## 9. Build Plan — Remaining Phases

### Phase 3: Loop Generation (~3 hours)

| Block | What gets built | Who | Output | Done when |
|-------|----------------|-----|--------|----------|
| 3.1 | Create `VideoProcessor.kt` with Transformer reversal pipeline | Cowork | `reversed_capture.mp4` generated from any input | Reversed file plays backward correctly |
| 3.2 | Add concatenation (forward + reversed) | Cowork | `openloop_output.mp4` = seamless loop | Loop plays without stutter or black flash at inflection |
| 3.3 | Wire into ViewModel: `Recording` → `Processing` → `LoopingPreview` | Cowork | State transitions work end-to-end | Capture → loop preview in one tap |
| 3.4 | Unit tests for VideoProcessor + updated ViewModel tests | Cowork | Tests pass | Processing state, error handling, output file assertions |

### Phase 4: Speed Slider (~2 hours)

| Block | What gets built | Who | Output | Done when |
|-------|----------------|-----|--------|----------|
| 4.1 | Add Compose Slider to PreviewScreen (0.5x–3.0x, default 1.5x) | Cowork | Slider visible on preview | Slider renders, value changes |
| 4.2 | Connect slider to ExoPlayer.setPlaybackSpeed() | Cowork | Real-time speed changes | Dragging slider instantly changes playback speed |
| 4.3 | Persist speed in LoopingPreview state for export handoff | Cowork | Speed value available downstream | Speed survives state reads |

### Phase 5: Export (~2 hours)

| Block | What gets built | Who | Output | Done when |
|-------|----------------|-----|--------|----------|
| 5.1 | Transformer speed burn-in pipeline | Cowork | Final video at selected speed | Exported file plays at burned-in speed in any player |
| 5.2 | Save to MediaStore + completion UI | Cowork | File in device gallery + toast | Video appears in Photos/Gallery app |
| 5.3 | Return-to-viewfinder flow | Cowork | Clean state reset | App returns to ReadyToCapture after save |

### Cut order (if behind)

1. Drop Phase 5.2 toast polish — save still works, just no confirmation UI
2. Drop Phase 4 slider — hardcode speed at 1.5x, add slider later
3. **Never cut:** Phase 3 (loop generation is the core feature)

---

## 10. Out of Scope / Future Work

| Feature | Why deferred | Effort |
|---------|-------------|--------|
| Audio toggle UI | Capture works with audio if permission granted; toggle is UX polish | Small |
| Custom capture duration | 1.5s is the MVP constraint; slider adds complexity | Medium |
| Filters/effects (color grading, vignette) | Requires shader pipeline; not core to loop concept | Large |
| Share-to-platform flow | Export to gallery is sufficient; direct share is distribution polish | Medium |
| ~~Onboarding persistence (DataStore)~~ | **Implemented** — Jetpack DataStore with `Initializing` state, repository pattern, and ViewModel factory injection | ~~Small~~ Done |
| Light mode / dynamic theming | Dark-only matches aesthetic; accessibility concern for later |
