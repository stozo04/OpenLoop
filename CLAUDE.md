# CLAUDE.md — OpenRang Operating Instructions

## Owner

Steven Gates · gates.steven@gmail.com · GitHub: [stozo04](https://github.com/stozo04/OpenRang)
Solo developer. Android/Kotlin. Comfortable making architecture decisions, reviewing code, and shipping production-quality UI.

Tools: Android Studio, Git/GitHub, Supabase, Google services (Gmail, Calendar, Drive).

## What is OpenRang

Open-source Android camera app for creating custom, speed-controlled video loops ("Boomerangs"). Unlike proprietary apps with rigid speed configs, OpenRang gives users real-time playback speed control with 100% on-device processing.

Apache 2.0 licensed. Early-stage — concept spike through gallery feature complete, core "loop" generation still ahead.

## Critical Rule — Do Not Trust Your Training Data

Your knowledge cutoff could be a year old. **Do not assume** you know the current version of any Google standard, Android API behavior, Jetpack library pattern, testing framework convention, or Play Store requirement. Before making any claim about how something works or what Google recommends, **web-search `developer.android.com` first**. This applies to everything — architecture patterns, Compose APIs, DataStore usage, CameraX, coroutines, permissions, accessibility, Play Store requirements, and any external package or library. If you catch yourself writing "Google recommends X" without having searched for it in this session, stop and search.

## Required Reading — Every Session

Before doing any non-trivial work in this repo, read **every file in `docs/lessons_learned/`**. Each file captures a real mistake from a past PR review or bug and the pattern to apply going forward. Skipping these means re-making the same mistake — these were expensive to learn the first time.

Order of operations at session start:

1. Read this `CLAUDE.md` (already in context).
2. Read `docs/lessons_learned/README.md` for the index, then read every numbered lesson file (`001-*.md`, `002-*.md`, ...).
3. Proceed with the user's request.

When a PR review surfaces a new pattern worth preserving, add it to `docs/lessons_learned/` using the convention in that folder's README. Commit the lesson alongside the fix it documents.

## How to Work With Me

### PRD-first — always

Before building anything non-trivial: write a PRD covering problem statement, success criteria, scope, constraints, implementation plan, and open questions. Get sign-off before writing code. Check what already exists before proposing custom work.

**Always reference `docs/PRD-mission-control.md` at the project root for the authoritative architecture and component specs before making structural changes.**

### Pushback — required

Interrogate vague requests. Disagree when something is off. Flag contradictions before acting — never silently overwrite prior decisions. No sycophancy. If a request conflicts with the existing architecture or a prior decision, call it out and explain the tension before proceeding.

### Reversibility protocol

Before anything destructive (deleting files, overwriting code, sending communications in my name, financial actions, mass operations):

1. Show the plan.
2. Flag what is irreversible.
3. Wait for explicit "proceed."

### Definition of Done — required before "done" or "Ready for PR"

A change is **not done because it compiles.** Before calling any non-trivial change done or opening a PR, clear the verification gate in **[`docs/DEFINITION_OF_DONE.md`](docs/DEFINITION_OF_DONE.md)**: baseline → clean build (debug **and** release) genuinely green → requirement checks (e.g. 16 KB `zipalign`) → unit + instrumented tests with 0 failures → **static analysis clean** (Android Lint reports zero *new* errors via `./gradlew :app:lintDebug`, and IDE "Inspect Code" / `inspect.bat` run locally — see **[`docs/STATIC_ANALYSIS.md`](docs/STATIC_ANALYSIS.md)**) → **actually run the app on an emulator, launch it, and capture a screenshot as proof** → honestly state what could not be verified + a manual QA checklist → attach the screenshot to the PR. "Genuinely green" = `BUILD SUCCESSFUL` **and** exit code 0 **and** zero `e:` errors (never trust a `| tail`-masked exit code). This is the standard, not a nice-to-have.

### Note-taking

Capture context, decisions, and open threads continuously. Checkpoint before switching domains or when a conversation runs long. If I say "things changed," re-interview me — don't assume prior context still holds.

### Working style

Show reasoning, not just conclusions. I value breadth and rigor equally — cast a wide net, do it well. Skip filler. Default tone: rigorous, direct, no fluff. Cover things properly but don't pad responses.

### Subfolder rules

When operating in a specific subfolder that has its own CLAUDE.md, respect that folder's voice and approach. The root CLAUDE.md (this file) provides defaults; subfolder overrides take precedence.

All project documentation (`.md` files) belongs in the `docs/` directory — not the project root. The only exceptions are `CLAUDE.md` and `README.md` which live at the root by convention.

## Architecture Snapshot

### Tech Stack

| Layer | Technology | Version |
|-------|-----------|--------|
| Language | Kotlin | 2.3.21 |
| UI | Jetpack Compose | BOM 2026.05.01 |
| Camera | AndroidX CameraX | 1.6.1 |
| Media | AndroidX Media3 (ExoPlayer, Transformer) | 1.10.1 |
| Preferences | Jetpack DataStore (Preferences) | 1.0.0 |
| Build | Gradle 9.0.0, AGP 8.13.2 | — |
| Target | compileSdk 36, minSdk 26, targetSdk 36 | — |

> **SDK status (shipped via [Issue #7](https://github.com/stozo04/OpenRang/issues/7)):** the app targets **API 36 (Android 16)** — `compileSdk`/`targetSdk` 36, `minSdk` stays 26 — clearing Google Play's target-API floor (currently API 35). The upgrade also moved to Kotlin 2.3.21 + the Compose Compiler Gradle plugin (required by the latest CameraX/Media3), and the native libraries are 16 KB page-aligned (uncompressed packaging). Behavior-change detail: [`docs/android-16/`](docs/android-16/README.md). Play's requirement: [Target API Level Requirements](https://developer.android.com/google/play/requirements/target-sdk).

### Source Layout

```
com.openrang.app/
├── camera/
│   └── CameraManager.kt         # CameraX lifecycle, recording, lens toggle
├── data/
│   ├── UserPreferencesRepository.kt(+Impl)   # DataStore: onboarding flag (Flow reads + suspend writes)
│   └── VideoStorageRepository.kt(+Impl)       # scratch / raw / boomerang files + thumbnails
├── media/
│   ├── VideoReverser.kt         # Two-pass MediaCodec reverse (Media3 has no reverse effect)
│   ├── VideoProcessor.kt        # Media3 Transformer: Composition + SpeedChangeEffect; ensureReversed() (shared reverse cache)
│   ├── BoomerangSequence.kt     # Pure: clip order + position-based seam + output-duration math (JVM-tested)
│   └── MediaFormatUtils.kt      # Type-tolerant frame-rate / rotation reads
├── ui/
│   ├── OpenRangUiState.kt       # Sealed state machine + TrimState / EditorTabState
│   ├── OpenRangViewModel.kt     # MVVM hub: state, storage, editor, preferences
│   ├── CameraScreen.kt          # Live viewfinder, shutter (+ shared design tokens)
│   ├── OnboardingScreen.kt      # 3-page carousel
│   ├── TrimScreen.kt            # Post-capture trim (two-handle bar, NEXT)
│   ├── BoomerangEditorScreen.kt # Tabbed editor — Direction tab (slice 03); Speed/Reps tabs land 04/05
│   ├── ProcessingScreen.kt      # Render spinner
│   ├── PreviewScreen.kt         # Looping ExoPlayer playback (gallery target, slice 07)
│   └── GalleryScreen.kt         # 3-col grid, thumbnails, delete
└── MainActivity.kt              # Permissions, OpenRangNavHost routing, theme, ViewModel Factory
```

### State Machine

```
Initializing → Onboarding → CheckingPermissions → ReadyToCapture ↔ Recording
   (returning user ↗)         (PermissionRationale / PermissionDenied)   │ finalize
                                                                          ▼
                                                                        Trim ──NEXT──▶ BoomerangEditor
                                                                          ▲                  │  save ✓
                                                                          └──back────────────┤
                                                                                             ▼
                                                       ReadyToCapture ◀──success── Processing
                                                                                   (failure ▶ BoomerangEditor)

Gallery ↔ ReadyToCapture        LoopingPreview — retained as the gallery playback target (slice 07)
```

States are modeled as a sealed interface (`OpenRangUiState`) and driven by `MutableStateFlow<OpenRangUiState>` in the ViewModel. `Initializing` reads DataStore to decide the first real screen. Post-capture the app auto-routes `Recording → Trim → BoomerangEditor → Processing → ReadyToCapture` (no `LoopingPreview` landing pad). The routed states are slim discriminators; the trim window (`TrimState`) and editor selections (`EditorTabState`) live in sibling flows in the ViewModel. Navigation is the exhaustive `OpenRangNavHost` `when` in `MainActivity.kt` (no `else` — Lesson 014).

### Design System, Storage, Testing & Engineering Decisions

All design tokens, storage patterns, testing strategy, and engineering decisions are documented in `docs/PRD-mission-control.md`. All implementation patterns must comply with `docs/ANDROID_STANDARDS.md` — that document is the single source of truth for Google best practices across architecture, Compose, coroutines, DataStore, CameraX, testing, accessibility, Play Store readiness, and performance.

## Reference Documents

| Document | Purpose |
|----------|--------|
| `docs/DEFINITION_OF_DONE.md` | **The "Ready for PR" verification gate** — build + test + *run the app + screenshot* before anything is called done. Non-negotiable for non-trivial changes. |
| `docs/lessons_learned/` | **Distilled rules from past PR reviews and bugs. Read every file at session start — see "Required Reading" above.** |
| `docs/PRD-mission-control.md` | **Authoritative architecture and component specs.** Read before any structural change. |
| `docs/TEST_COVERAGE.md` | **Testing strategy and inventory.** Defines test directories, pyramid, frameworks, coroutine testing, current coverage, and gaps. Sourced from Google docs. |
| `docs/ANDROID_STANDARDS.md` | **Google Android best practices.** Non-negotiable standards with links to official specs. Consult before introducing new patterns or libraries. §11 covers Android-16 / target-36 rules (now in force — the app targets 36 as of Issue #7). |
| `docs/STATIC_ANALYSIS.md` | **The "Inspect Code" merge gate.** How OpenRang reproduces Android Studio's two inspection engines headlessly — Engine 1 (Android Lint, automated by the pr-reviewer skill) and Engine 2 (IDE inspections + proofreading, run locally). Exact commands, the `lint-baseline.xml` policy, and severity mapping. |
| `docs/android-16/` | **Android 16 (API 36) upgrade knowledge hub.** Per-page summaries of Google's Android 16 docs, each with an OpenRang impact verdict and the official source URL. Durable reference for the `targetSdk 36` upgrade (Issue #7) — does not move to `completed/`. |
| `docs/active/` | **Active feature folders.** Each feature gets a folder with at least one IMPLEMENTATION.md. See `docs/active/README.md` for the convention. |
| `docs/completed/` | **Shipped features.** Moved here from `docs/active/` after merge to main. |
| `docs/guides/` | **Plain-English how-to guides.** Beginner-friendly walkthroughs of project concepts (e.g. `jetpack-datastore-explained.md` — what DataStore is and how to inspect/reset it on a device). |
| `.github/` | PR template, branch naming (`feature/<short-description>`), and workflow conventions. |


Words of wisdom from a previous Claude `HEY_CLAUDE_ITS_ME.md`.