# CLAUDE.md — OpenLoop Operating Instructions

## Owner

Steven Gates · <gates.steven@gmail.com> · GitHub: [stozo04](https://github.com/stozo04/OpenLoop)
Solo developer. Android/Kotlin. Comfortable making architecture decisions, reviewing code, and shipping production-quality UI.

Tools: Android Studio, Git/GitHub, Supabase, Google services (Gmail, Calendar, Drive).

## What is OpenLoop

Open-source Android camera app for creating custom, speed-controlled video loops ("Boomerangs"). Unlike proprietary apps with rigid speed configs, OpenLoop gives users real-time playback speed control with 100% on-device processing.

Apache 2.0 licensed. Early-stage — concept spike through gallery feature complete, core "loop" generation still ahead.

## Critical Rule — Do Not Trust Your Training Data

Your knowledge cutoff could be a year old. **Do not assume** you know the current version of any Google standard, Android API behavior, Jetpack library pattern, testing framework convention, or Play Store requirement. Before making any claim about how something works or what Google recommends, **web-search `developer.android.com` first**. This applies to everything — architecture patterns, Compose APIs, DataStore usage, CameraX, coroutines, permissions, accessibility, Play Store requirements, and any external package or library. If you catch yourself writing "Google recommends X" without having searched for it in this session, stop and search.

## Required Reading — Every Session

Before doing any non-trivial work in this repo, read the **core** lessons in `docs/lessons_learned/`. Each file captures a real mistake from a past PR review or bug and the pattern to apply going forward. Skipping these means re-making the same mistake — these were expensive to learn the first time.

Order of operations at session start:

1. Read this `CLAUDE.md` (already in context).
2. Read `docs/lessons_learned/README.md` — it explains the two tiers and carries the index.
3. Read **every core lesson in full: 008 and 011–040.** These are device- and repo-specific (Samsung encoder ordering, surface-size corruption, zero-sample muxes, FGS API gating, CameraX effect attachment) and exist nowhere else.
4. **Skim the index rows for the baseline lessons (001–007, 009, 010)** — generic Android/Compose hygiene now largely held by Lint, CI, and IDE inspections. Open one only when the work actually touches that area.
5. Proceed with the user's request.

When a lesson graduates from "hard-won" to "the tooling catches this now," move it to the baseline tier rather than deleting it — the history stays useful, the mandatory read stays short.

When a PR review surfaces a new pattern worth preserving, add it to `docs/lessons_learned/` using the convention in that folder's README. Commit the lesson alongside the fix it documents.

## Google Android Skills — Precedence

The official [`android/skills`](https://github.com/android/skills) plugin is enabled for this repo — all 21 skills, deliberately uncurated (owner decision, `docs/PRD-android-skills.md`). Skills auto-trigger by task relevance. **Where a Google skill conflicts with this repo's documented decisions, the repo wins**: `CLAUDE.md`, `docs/lessons_learned/`, and the PRDs override skill guidance. Known collision points:

- `navigation-3` recommends the Navigation library. OpenLoop deliberately uses the sealed `OpenLoopUiState` machine + exhaustive `OpenLoopNavHost` `when` (Lesson 014, `PRD-mission-control.md`). Do not migrate navigation because a skill suggested it.
- `camerax` prefers `MlKitAnalyzer`. `FaceTracker` is a deliberate manual `ImageAnalysis.Analyzer` on the ML Kit stable API. Keep it. (Its `SurfaceProcessor` guidance is compatible with `LensSurfaceProcessor` — that one is fine to follow.)
- `agp-9-upgrade` / lint may suggest newer AGP. The ceiling is the installed Android Studio pairing, not Maven-newest.

How to invoke one explicitly (`/android-skills:<name>`), keep the plugin scoped to this repo, and update it: [`docs/guides/android-skills.md`](docs/guides/android-skills.md).

## How to Work With Me

### PRD-first — always

Before building anything non-trivial: write a PRD covering problem statement, success criteria, scope, constraints, implementation plan, and open questions. Get sign-off before writing code. Check what already exists before proposing custom work.

**The Architecture Snapshot below (tech stack, source layout, state machine) is the authoritative structural reference. `docs/PRD-mission-control.md` is the durable design record — design tokens, storage layout, decision log; check its decision log before making structural changes.**

### Pushback — required

Interrogate vague requests. Disagree when something is off. Flag contradictions before acting — never silently overwrite prior decisions. No sycophancy. If a request conflicts with the existing architecture or a prior decision, call it out and explain the tension before proceeding.

### Reversibility protocol

Before anything destructive (deleting files, overwriting code, sending communications in my name, financial actions, mass operations):

1. Show the plan.
2. Flag what is irreversible.
3. Wait for explicit "proceed."

### Definition of Done — required before "done" or "Ready for PR"

**Production zero-error rule (non-negotiable):** OpenLoop is live in Production and reachable by billions of users. **Any error the agent encounters while working — a failing test, a compile error, a lint error, a crash — MUST be resolved before a PR is created, even pre-existing failures the agent did not introduce.** "Not my change" is never a reason to open a PR on a red baseline; fix it as part of the work, or stop and escalate to the owner for explicit direction. A PR is opened only from a fully green state (clean debug + release build, 0 test failures, 0 new lint errors). Full policy in **[`docs/DEFINITION_OF_DONE.md`](docs/DEFINITION_OF_DONE.md)**.

A change is **not done because it compiles.** Before calling any non-trivial change done or opening a PR, clear the verification gate in **[`docs/DEFINITION_OF_DONE.md`](docs/DEFINITION_OF_DONE.md)**: baseline → clean build (debug **and** release) genuinely green → requirement checks (e.g. 16 KB `zipalign`) → unit + instrumented tests with 0 failures → **static analysis at zero** (Android Lint 0 errors **and** 0 warnings, the Markdown/spelling/JSON text gates at 0, and the Android Studio "Inspect Code" export parsed to 0 hard findings — see **[`docs/STATIC_ANALYSIS.md`](docs/STATIC_ANALYSIS.md)**) → **actually run the app on an emulator, launch it, and capture a screenshot as proof** → honestly state what could not be verified + a manual QA checklist → attach the screenshot to the PR. "Genuinely green" = `BUILD SUCCESSFUL` **and** exit code 0 **and** zero `e:` **and** zero `w:` lines (never trust a `| tail`-masked exit code). This is the standard, not a nice-to-have.

**The pre-PR sweep is mandatory and mechanical (owner rule, 2026-08-25):** `.\scripts\pre-pr-sweep.ps1` runs every one of those checks to zero and writes `build/sweep-receipt.json`; the `PreToolUse` hook in `.claude/settings.json` refuses `gh pr create` / `create_pull_request` without a receipt for the current `HEAD` on a clean tree. Run it **after the final commit**. If Android Studio or an emulator is unavailable, pass `-SkipInspectCode` / `-SkipConnected` and say so in the PR — never work around the hook.

### Note-taking

Capture context, decisions, and open threads continuously. Checkpoint before switching domains or when a conversation runs long. If I say "things changed," re-interview me — don't assume prior context still holds.

### Working style

Show reasoning, not just conclusions. I value breadth and rigor equally — cast a wide net, do it well. Skip filler. Default tone: rigorous, direct, no fluff. Cover things properly but don't pad responses.

### Subfolder rules

When operating in a specific subfolder that has its own CLAUDE.md, respect that folder's voice and approach. The root CLAUDE.md (this file) provides defaults; subfolder overrides take precedence.

All project documentation (`.md` files) belongs in the `docs/` directory — not the project root. The only exceptions are `CLAUDE.md` and `README.md` which live at the root by convention. **Folder map and placement rules:** [`docs/README.md`](docs/README.md) (Markdown layout, image assets, gitignored `docs/local/` for private notes).

## Architecture Snapshot

### Tech Stack

| Layer       | Technology                               | Version        |
| ----------- | ---------------------------------------- | -------------- |
| Language    | Kotlin                                   | 2.4.10         |
| UI          | Jetpack Compose                          | BOM 2026.08.00 |
| Camera      | AndroidX CameraX                         | 1.6.1          |
| Media       | AndroidX Media3 (ExoPlayer, Transformer) | 1.11.0         |
| Preferences | Jetpack DataStore (Preferences)          | 1.2.1          |
| Build       | Gradle 9.5.0, AGP 9.3.2                  | —              |
| Target      | compileSdk 37, minSdk 26, targetSdk 36   | —              |

> **SDK status (shipped via [Issue #7](https://github.com/stozo04/OpenLoop/issues/7)):** the app targets **API 36 (Android 16)** — `targetSdk` 36, `compileSdk` 37, `minSdk` stays 26 — which is Google Play's target-API floor for new apps and updates from 2026-08-31 (`ANDROID_STANDARDS.md` §8 carries the dated citation). Behavior changes: [Android 16 behavior changes](https://developer.android.com/about/versions/16/behavior-changes-16) and `ANDROID_STANDARDS.md` §11. Play's requirement: [Target API Level Requirements](https://developer.android.com/google/play/requirements/target-sdk).

### Source Layout

```text
io.github.stozo04.openloop/
├── camera/
│   ├── CameraManager.kt         # CameraX bind/unbind, recording, lens toggle, pinch-zoom control
│   ├── PinchZoomLayout.kt       # FrameLayout that intercepts multitouch for pinch (Fold-safe)
│   ├── ZoomUi.kt                # Pure zoom snapshot + clamp/chip-format math (JVM-tested)
│   └── lens/                    # Live camera lenses — docs/PRD-camera-lenses.md
│       ├── Lens.kt              # The catalogue. Nothing outside this file names an individual lens
│       ├── LensAnchor.kt        # Pure face-frame placement math (JVM-tested: LensAnchorTest)
│       ├── LensSurfaceProcessor.kt  # The ONE CameraEffect: EGL + 3 GL programs, sticker/feature draw per face
│       ├── LensMotion.kt        # Per-face wobble springs + flick spins + eased mouth, stepped once per frame (JVM-tested)
│       ├── HandFlick.kt         # Pure "hand velocity near sticker" → spin impulse (JVM-tested) — docs/PRD-lens-hand-flick.md
│       ├── HandTracker.kt       # MediaPipe Hand Landmarker glue on the SAME ImageAnalysis stream; alive only for a flickable lens
│       ├── LensHitTest.kt       # Pure point-in-rotated-quad "did the hand land on a sticker" (JVM-tested)
│       ├── FaceRoster.kt        # Pure slot rule + per-face hold + id-churn adoption (JVM-tested) — docs/PRD-multi-face-lenses.md
│       └── FaceTracker.kt       # ML Kit (stable API) ImageAnalysis.Analyzer → List<FaceSnapshot> (up to 2)
├── data/                        # UserPreferencesRepository (DataStore), VideoStorageRepository, VideoImporter
├── diagnostics/                 # AnalyticsReporter + Crashlytics wrappers, debug-report share
├── media/                       # The pipeline
│   ├── VideoProcessor.kt        # Media3 Transformer: Composition + SpeedChangeEffect; ensureReversed() (shared reverse cache)
│   ├── VideoReverser.kt         # Two-pass MediaCodec reverse (Media3 has no reverse effect)
│   ├── Reverse*.kt              # Encoder selection, output validation, scratch janitor, logging
│   ├── BoothStrip*.kt           # Photo-booth strip: pure layout math (JVM-tested) + thin Canvas composer — docs/PRD-photo-booth.md
│   └── …Sequence/Filter/Format  # BoomerangSequence, VideoFilter, MediaFormatUtils — pure, JVM-tested
├── ui/
│   ├── OpenLoopUiState.kt       # Sealed state machine + TrimState / EditorTabState
│   ├── OpenLoopViewModel.kt     # MVVM hub: state, storage, editor, preferences
│   ├── *Screen.kt               # One per state: Camera / Onboarding / Trim / BoomerangEditor / Processing / Gallery
│   ├── components/              # Reusable Compose pieces (LensCarousel, tab panels, filmstrip) **and**
│   │                            # the pure math extracted out of composables so it is JVM-testable
│   │                            # (TrimHandleMath — Lesson 030, TrimRulerMath)
│   └── theme/                   # Color / Type / Shape / Background tokens
├── review/                      # Play in-app review: the ask cadence + the two-call launch
├── update/                      # In-app update controller
├── work/                        # WorkManager render pipeline: scheduler, worker, FGS notifications, MediaStore publish
└── MainActivity.kt              # Permissions, OpenLoopNavHost routing, theme, ViewModel Factory
```

> This is a **map, not an inventory** — packages plus the load-bearing files. Don't grow it back into
> a file-by-file listing: that is precisely what let it drift (it was missing `camera/lens/`,
> `ui/components/`, `diagnostics/`, `update/` and `work/` entirely). Add a line when a *package*
> appears or a file becomes load-bearing, not for every new file.

### State Machine

```text
Initializing → Onboarding → CheckingPermissions → ReadyToCapture ↔ Recording
   (returning user ↗)         (PermissionRationale / PermissionDenied)   │ finalize
                                                                          ▼
                                                                        Trim ──NEXT──▶ BoomerangEditor
                                                                          ▲                  │  save ✓
                                                                          └──back────────────┤
                                                                                             ▼
                                                       ReadyToCapture ◀──success── Processing
                                                                                   (failure ▶ BoomerangEditor)

Gallery ↔ ReadyToCapture        (gallery plays a tapped clip in an in-screen Dialog overlay)
```

States are modeled as a sealed interface (`OpenLoopUiState`) and driven by `MutableStateFlow<OpenLoopUiState>` in the ViewModel. `Initializing` reads DataStore to decide the first real screen. Post-capture the app auto-routes `Recording → Trim → BoomerangEditor → Processing → ReadyToCapture` (no preview landing pad). The routed states are slim discriminators; the trim window (`TrimState`) and editor selections (`EditorTabState`) live in sibling flows in the ViewModel. Navigation is the exhaustive `OpenLoopNavHost` `when` in `MainActivity.kt` (no `else` — Lesson 014).

### Design System, Storage, Testing & Engineering Decisions

Design tokens, storage layout, and the engineering decision log live in `docs/PRD-mission-control.md`; testing strategy lives in `docs/TEST_COVERAGE.md`. All implementation patterns must comply with `docs/ANDROID_STANDARDS.md` — that document is the single source of truth for Google best practices across architecture, Compose, coroutines, DataStore, CameraX, testing, accessibility, Play Store readiness, and performance.

## Reference Documents

| Document                           | Purpose                                                                                                                                                                                                                                                                                                                                    |
| ---------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| [`docs/README.md`](docs/README.md) | **Documentation layout** — where every `.md` and doc image belongs; enforcement rules                                                                                                                                                                                                                                                      |
| `docs/DEFINITION_OF_DONE.md`       | **The "Ready for PR" verification gate** — build + test + *run the app + screenshot* before anything is called done. Non-negotiable for non-trivial changes.                                                                                                                                                                               |
| `docs/lessons_learned/`            | **Distilled rules from past PR reviews and bugs. Read every file at session start — see "Required Reading" above.**                                                                                                                                                                                                                        |
| `docs/PRD-mission-control.md`      | **Durable design record** — design tokens, storage layout, decision log. Check the decision log before structural changes; the Architecture Snapshot above is the structural map.                                                                                                                                                          |
| `docs/TEST_COVERAGE.md`            | **Testing strategy and inventory.** Defines test directories, pyramid, frameworks, coroutine testing, current coverage, and gaps. Sourced from Google docs. **OEM lanes:** [`docs/guides/oem-regression-testing.md`](docs/guides/oem-regression-testing.md).                                                                               |
| `docs/ANDROID_STANDARDS.md`        | **Google Android best practices.** Non-negotiable standards with links to official specs. Consult before introducing new patterns or libraries. §11 covers Android-16 / target-36 rules (now in force — the app targets 36 as of Issue #7).                                                                                                |
| `docs/STATIC_ANALYSIS.md`          | **The "Inspect Code" merge gate.** How OpenLoop reproduces Android Studio's two inspection engines headlessly — Engine 1 (Android Lint, automated by the pr-reviewer skill) and Engine 2 (IDE inspections + proofreading, run locally). Exact commands, the no-baseline policy, and severity mapping.                                      |
| `docs/guides/`                     | **Plain-English how-tos and durable reference** (reverse algorithm, Robolectric, OEM lanes). Index: [`docs/guides/README.md`](docs/guides/README.md).                                                                                                                                                                                      |
| `docs/play-store/`                 | **Play Console submission pack** — privacy policy, data safety, store listing copy, signing. **Every public Play release gets a matching GitHub release** — §5 of `release-signing-and-aab.md`; the tag maps a Crashlytics `versionName` to code.                                                                                          |
| `.github/`                         | PR template, branch naming (`feature/<short-description>`), and workflow conventions.                                                                                                                                                                                                                                                      |
