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
| Language | Kotlin | 1.9.22 |
| UI | Jetpack Compose | BOM 2024.02.02 |
| Camera | AndroidX CameraX | 1.3.1 |
| Media | AndroidX Media3 (ExoPlayer, Transformer) | 1.3.0 |
| Preferences | Jetpack DataStore (Preferences) | 1.0.0 |
| Build | Gradle 8.3.2, AGP 8.3.2 | — |
| Target | compileSdk 34, minSdk 26, targetSdk 34 | — |

### Source Layout

```
com.openrang.app/
├── camera/
│   └── CameraManager.kt         # CameraX lifecycle, recording, lens toggle
├── data/
│   ├── UserPreferencesRepository.kt    # Interface: Flow-based preference reads + suspend writes
│   └── UserPreferencesRepositoryImpl.kt # DataStore wrapper + Context.dataStore singleton
├── ui/
│   ├── OpenRangUiState.kt       # Sealed interface: state machine (incl. Initializing)
│   ├── OpenRangViewModel.kt     # MVVM hub: state, video storage, navigation, preferences
│   ├── CameraScreen.kt          # Live viewfinder, shutter, home button
│   ├── OnboardingScreen.kt      # 3-page carousel, animations, ArrowIcons
│   ├── PreviewScreen.kt         # Full-screen looping ExoPlayer preview
│   └── GalleryScreen.kt         # 3-col grid, thumbnails, delete, video overlay
├── MainActivity.kt              # Permissions, state routing, theme, ViewModelFactory
└── (planned)
    └── media/
        └── VideoProcessor.kt    # Media3 Transformer: reversal, concat, speed burn-in
```

### State Machine

```
Initializing → Onboarding → CheckingPermissions → ReadyToCapture ↔ Recording
           ↘                 ↕                      ↓
        CheckingPermissions  Gallery            LoopingPreview
         (returning user)      ↕
                          ReadyToCapture
```

States are modeled as a sealed interface (`OpenRangUiState`) and driven by `MutableStateFlow<OpenRangUiState>` in the ViewModel. `Initializing` reads DataStore to decide the first real screen. Navigation is conditional composable routing in `MainActivity.kt`.

### Design System, Storage, Testing & Engineering Decisions

All design tokens, storage patterns, testing strategy, and engineering decisions are documented in `docs/PRD-mission-control.md`. All implementation patterns must comply with `docs/ANDROID_STANDARDS.md` — that document is the single source of truth for Google best practices across architecture, Compose, coroutines, DataStore, CameraX, testing, accessibility, Play Store readiness, and performance.

## Reference Documents

| Document | Purpose |
|----------|--------|
| `docs/lessons_learned/` | **Distilled rules from past PR reviews and bugs. Read every file at session start — see "Required Reading" above.** |
| `docs/PRD-mission-control.md` | **Authoritative architecture and component specs.** Read before any structural change. |
| `docs/TEST_COVERAGE.md` | **Testing strategy and inventory.** Defines test directories, pyramid, frameworks, coroutine testing, current coverage, and gaps. Sourced from Google docs. |
| `docs/ANDROID_STANDARDS.md` | **Google Android best practices.** Non-negotiable standards with links to official specs. Consult before introducing new patterns or libraries. |
| `docs/active/` | **Active feature folders.** Each feature gets a folder with at least one IMPLEMENTATION.md. See `docs/active/README.md` for the convention. |
| `docs/completed/` | **Shipped features.** Moved here from `docs/active/` after merge to main. |
| `docs/guides/` | **Plain-English how-to guides.** Beginner-friendly walkthroughs of project concepts (e.g. `jetpack-datastore-explained.md` — what DataStore is and how to inspect/reset it on a device). |
| `.github/` | PR template, branch naming (`feature/<short-description>`), and workflow conventions. |
