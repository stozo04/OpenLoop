# PRD-mission-control.md — OpenLoop Design Record

**Status:** Living document — update as the system evolves.
**Owner:** Steven Gates
**Restructured:** 2026-08-24 (owner decision on [PR #144](https://github.com/stozo04/OpenLoop/pull/144)) — demoted from a full architecture spec to the **durable design record**. The 2026-05 architecture/component/build-plan sections described a build the app outgrew (a `PreviewScreen`/`LoopingPreview` state machine, 1.5 s fixed bursts, phases 3–5 "planned"); they live in git history, not here.

**What lives where now:**

| You need | Go to |
|---|---|
| Architecture: tech stack, source layout, state machine | `CLAUDE.md` → Architecture Snapshot |
| Per-feature design + verification record | `docs/PRD-<feature>.md` (capture-zoom, camera-lenses, photo-capture, speed-curves, photo-booth, aso-discoverability, crashlytics-autotriage, android-skills) |
| Google standards + OpenLoop-specific rules | [`ANDROID_STANDARDS.md`](ANDROID_STANDARDS.md) |
| Testing strategy + inventory | [`TEST_COVERAGE.md`](TEST_COVERAGE.md) |
| **Design tokens · storage layout · decision log** | **this file** |

---

## 1. Design System Tokens

Single source of truth is [`ui/theme/Color.kt`](../app/src/main/java/io/github/stozo04/openloop/ui/theme/Color.kt) (Issue #35); this table mirrors it. Roles: **Electric Lime is the one primary accent** (flat, primary actions, active states); **coral-red is semantic only** (recording + destructive); **aqua is a rare tertiary** (shutter-gradient far end). Surfaces are a neutral near-black ramp so footage and the single accent are the only saturated things on screen. Theme: `darkColorScheme` only, consumed via `MaterialTheme.colorScheme` — never inline hex (Lesson 001).

| Token | Hex | Role |
|-------|-----|------|
| `ElectricLime` | `#CDFF4F` | primary |
| `Aqua` | `#34E1D5` | secondary/tertiary + shutter gradient end |
| `CoralRed` | `#FF5A5F` | error (delete) + recording indicator |
| `Canvas` | `#0A0A0C` | background |
| `SurfaceBase` → `SurfaceContainerHighest` | `#101014` → `#26262E` | neutral surface ramp |
| `TextPrimary` / `TextSecondary` | `#F3F3F6` / `#ADADB8` | onSurface / captions |
| `OverlayWhite` / `OverlayWhiteBorder` | `#FFFFFF` @ 20% / 30% | glassy controls drawn **over live video** — a solid surface token can't read over arbitrary footage |
| `OverlayScrim` | `#121216` @ 80% | chip/scrim behind labels over video |
| `OverlayArtBackdrop` | `#ECECF2` @ 94% | deliberately **light** backdrop for lens-art thumbnails — dark art (Shades) vanishes on a dark chip |

Container/on-color variants and the full surface ramp: read `Color.kt` directly. Type and shape tokens: `ui/theme/`.

---

## 2. Data Layer — storage layout

Contract: [`data/VideoStorageRepository.kt`](../app/src/main/java/io/github/stozo04/openloop/data/VideoStorageRepository.kt) (interface + `Impl`; the ViewModel never touches `Context` — Lesson 004). Public publish: [`work/MediaStoreVideoPublisher.kt`](../app/src/main/java/io/github/stozo04/openloop/work/MediaStoreVideoPublisher.kt).

```text
filesDir/
├── scratch/raw_<uuid>.mp4          # per-capture scratch (one UUID per shutter press), pruned on abandon
├── videos/
│   ├── clip_<ts>.mp4               # persisted raw captures            (VideoKind.RAW)
│   ├── boom_<ts>_from_<rawTs>.mp4  # rendered loops                    (VideoKind.BOOMERANG)
│   └── photo_<ts>.jpg              # photo-mode stills + booth strips  (VideoKind.PHOTO)
└── thumbnails/<stem>.jpg           # thumbnails for all kinds

Public device media library (survives uninstall; user-managed in their Gallery app):
Movies/OpenLoop/OpenLoop_Capture_<ts>.mp4    # every finished loop the user saves
Pictures/OpenLoop/OpenLoop_Photo_<ts>.jpg    # every saved photo / booth strip
```

- **`VideoKind` is inferred from the filename prefix** at load time (`clip_` / `boom_` / `photo_`). Photos deliberately share `videos/` rather than getting their own directory: `file_paths.xml` scopes sharing to `/videos/`, and a separate directory would silently break the share sheet.
- **Per-UUID scratch files** (not a single fixed `raw_capture.mp4`) keep concurrent/back-to-back captures from clobbering each other and give each in-flight capture a stable identity for the Trim screen (Decision 16).
- No Room database unless relational queries become necessary (`ANDROID_STANDARDS.md` §10).

---

## 3. Decision Log

Dated, append-only. A superseded decision gets a new row, never an edit (see #14, #15).

| # | Decision | Reasoning | Trade-off |
|---|----------|-----------|----------|
| 1 | **Sealed interface for UI state** | Exhaustive `when` matching at compile time prevents missing state handling | Slightly more boilerplate than an enum, but safer with data-carrying states |
| 2 | **Single ViewModel, no nav library** | App has <10 screens, all state-driven; Compose Navigation adds complexity without value at this scale | Will need migration if screen count grows significantly |
| 3 | **`filesDir` for video storage, not `cacheDir`** | Gallery videos are user-created content that must persist across sessions | Uses more permanent storage; needs manual cleanup via delete |
| 4 | **Eager + lazy thumbnail extraction** | Eager at save time for speed; lazy fallback for resilience if a thumbnail is lost | Slightly more code than eager-only, but prevents blank grid cards |
| 5 | **OnboardingNavigation extracted as internal composable** | `ColumnScope.AnimatedVisibility` resolves to slide animations that cause layout jumping; extraction breaks the scope chain | Function is `internal` (not `private`) to enable UI regression testing |
| 6 | **OnboardingPage data class model** | Eliminates separate `when` branches for title/drawable/glow; adding pages is a one-line change | Data class is private to the file, not reusable outside onboarding |
| 7 | **animateFloatAsState for dot indicators** | Smooth size interpolation instead of snap; float → `.dp` conversion avoids `animateDpAsState` import ambiguity | Negligible performance cost |
| 8 | **BitmapFactory.decodeFile for thumbnails** | No image-loading library dependency (Coil/Glide); thumbnails are small local JPEGs | Will need Coil if we ever load remote images or need cache management |
| 9 | **Primary navigation gets the accent, secondary utility gets glass** | Visual weight should match importance | Glass-styled controls are less discoverable |
| 10 | **Dark-only theme** | Camera apps benefit from dark UI (less screen glare on subjects) | No light mode for accessibility preferences |
| 11 | **Onboarding persistence via Jetpack DataStore** | DataStore persists `has_completed_onboarding`; returning users skip to the permission check. Preferences DataStore + repository pattern for testability | Added `Initializing` state to the sealed interface for the async read at startup |
| 12 | **No skip button on onboarding (intentional)** | Short enough; forced traversal ensures users see all value props | Mild friction for power users; revisit post-launch |
| 13 | **`VideoStorageRepository` extracted; ViewModel is Context-free** (Issue #10) | Filesystem work behind a repository interface so `OpenLoopViewModel` holds no `Context` (Google ViewModel rule, Lesson 004); `MainActivity` is the single Context→repository bridge; tests use a fake | Two constructor deps instead of one; `RecordedVideo` moved from `ui` to `data` |
| 14 | **Gallery grid uses `GridCells.Adaptive(minSize = 110.dp)`, not `Fixed(3)`** (slice 07) | The app targets foldables/large screens; an adaptive grid keeps cells ~110dp and fills the width — 3 columns on a phone, more unfolded (Google adaptive-layout guidance) | Column count is no longer fixed; layout regression tests must assert by cell size, not column count |
| 15 | **Palette: a single Electric-Lime accent on a neutral near-black ramp** (Issue #35; shipped with the lenses work, 2026-08-08) | Supersedes the launch-era NeonCoral/NeonPurple gradient look (#9 and #10 survive: dark-only, weight-by-importance). One saturated accent keeps the user's footage the hero; coral demoted to semantic-only; per-screen colored gradients replaced by `Canvas` | A single accent carries all affordance weight — new interactive elements must use it consistently or they read as inert |
| 16 | **Per-UUID scratch captures under `filesDir/scratch/`** (slice 02) | Supersedes the single fixed `cacheDir/raw_capture.mp4`: unique names keep back-to-back captures from clobbering each other and give each in-flight capture a stable identity for Trim | Abandoned scratch files need pruning (the janitors handle scratch + the reverse cache) |
