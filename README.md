# OpenRang

**The open-source Boomerang camera app that should have existed years ago.**

No subscriptions. No ads. No data harvesting. Just point, tap, and loop.

OpenRang is a free Android camera app for creating speed-controlled video loops — the kind of thing Big Tech locks behind paywalls and cluttered UIs. We're leveraging the power of AI and open-source tooling to bring the coolest creative toys to everyone, for free, forever.

Built with Google's latest Android libraries. Runs entirely on your device. Your videos never leave your phone.

## What It Does

- **Burst Capture** — Tap the shutter for a quick 1.5-second video burst (custom duration coming soon)
- **Seamless Loops** — Forward-backward loop generation entirely on-device via Media3 Transformer
- **Speed Control** — Real-time playback speed slider from 0.5x to 3.0x before you save
- **Gallery** — Browse, replay, and manage all your loops in a slick grid
- **100% Private** — Zero network calls. Zero tracking. Everything stays on your phone

## Why OpenRang?

Every boomerang/loop app on the Play Store either costs money, runs ads, or sends your videos to a server you don't control. OpenRang is the alternative:

- **Open source** (Apache 2.0) — read every line, fork it, improve it
- **No accounts** — no sign-up, no login, no profile
- **No ads, ever** — not now, not later, not with a "premium tier"
- **AI-assisted development** — built faster and better by pairing human creativity with AI tooling
- **Google-first architecture** — follows every Jetpack best practice Google recommends

## Architecture & Tech Stack

| Layer | Technology | What It Does |
|-------|-----------|-------------|
| **Language** | Kotlin | Modern, concise, Google's preferred language for Android |
| **UI** | Jetpack Compose | Declarative UI — no XML layouts, no fragments |
| **Camera** | AndroidX CameraX | Device-agnostic camera API that works across 1000+ Android devices |
| **Media** | AndroidX Media3 | ExoPlayer for looping playback, Transformer for video reversal & export |
| **Preferences** | Jetpack DataStore | Async, coroutine-based key-value storage (replaces SharedPreferences) |
| **State** | MVVM + StateFlow | Single ViewModel, sealed-interface state machine, unidirectional data flow |
| **Testing** | JUnit 4 + MockK + Compose UI Test | Unit tests for ViewModel logic, UI regression tests for layout-critical composables |

**SDK levels:** `minSdk 26` (Android 8.0) · `compileSdk 34` · `targetSdk 34` today. An upgrade to **API 36 (Android 16)** is in progress for Google Play readiness (`minSdk` stays 26) — tracked in [Issue #7](https://github.com/stozo04/OpenRang/issues/7), with the full behavior-change breakdown in [`docs/android-16/`](docs/android-16/README.md). Google Play's current target-API rule: [Target API Level Requirements](https://developer.android.com/google/play/requirements/target-sdk).

### State Machine

```
Initializing → Onboarding → CheckingPermissions → ReadyToCapture <-> Recording
           \                       |                      |
        CheckingPermissions     Gallery            LoopingPreview
         (returning user)          |
                            ReadyToCapture
```

All navigation is driven by a single `MutableStateFlow<OpenRangUiState>` — no Jetpack Navigation needed at this scale. The `Initializing` state reads from DataStore to determine whether to show onboarding or skip straight to the camera.

### Project Structure

```
com.openrang.app/
├── camera/          CameraX lifecycle, recording, lens toggle
├── data/            DataStore preferences, repository pattern
├── ui/              Compose screens, ViewModel, state machine
├── MainActivity.kt  Permissions, routing, theme
└── (planned)
    └── media/       Media3 Transformer pipeline
```

## Getting Started

1. **Clone it:**
   ```bash
   git clone https://github.com/stozo04/OpenRang.git
   ```

2. **Open in Android Studio** (Hedgehog or newer recommended)

3. **Sync Gradle and run** the `:app` module on a device or emulator running Android 8.0+ (API 26+)

That's it. No API keys, no backend, no environment variables.

## Guides

Plain-English, beginner-friendly walkthroughs live in [`docs/guides/`](docs/guides/):

- [**Jetpack DataStore — Explained Like You're Five**](docs/guides/jetpack-datastore-explained.md) — what the app's little "memory" is, where it lives on the phone, three ways to peek inside it, and how to reset onboarding for testing.

## Development Standards

This project follows Google's official Android development guidance. See [`docs/ANDROID_STANDARDS.md`](docs/ANDROID_STANDARDS.md) for the full standards reference with links to Google's specs. We treat these as non-negotiable — if Google recommends it, we follow it. For Android 16 / `targetSdk 36`-specific guidance behind the in-progress upgrade, see the [`docs/android-16/`](docs/android-16/README.md) knowledge hub.

### PR Merge Policy

**No PR merges without passing the automated standards review.**

Every pull request is reviewed by an autonomous compliance agent ([`.claude/skills/pr-reviewer/`](.claude/skills/pr-reviewer/)) that audits code changes against 11 categories and 75+ checklist items sourced from Google's official Android documentation:

Architecture, DataStore, Permissions, Compose, CameraX, Media & Audio, Coroutines, Testing, Accessibility, Play Store Readiness, and Android Version Compatibility.

The reviewer web-searches `developer.android.com` for the latest guidance on every run — no stale rules. It posts a structured PASS/FAIL/WARNING report directly on the PR with file-level specifics, Google doc citations, and reasoning for every finding.

**To merge, a PR must:**
1. Receive an **APPROVE** verdict from the standards reviewer (zero FAILs)
2. Address all **WARNINGS** or document why they're accepted
3. Pass all unit tests (19+) and UI regression tests (6+)

### Fixing Review Feedback

When a PR gets review feedback, open a new Cowork session with the OpenRang folder mounted and say:

> Start addressing PR feedback following `docs/prompts/PR-FEEDBACK-RESOLUTION.md` — here is the PR: https://github.com/stozo04/OpenRang/pull/XX

Replace `XX` with your PR number. The agent will read the review comments, web-search Google's latest standards to verify each finding, fix the code, push, post a response comment explaining what was fixed and why, then run a fresh review to confirm zero FAILs.

## Build Status

**What's shipping:**
- 3-page onboarding (with DataStore persistence — you only see it once)
- CameraX viewfinder with 1.5s burst capture + auto-stop
- Front/back camera toggle
- Persistent video storage + thumbnail caching
- Looping preview (ExoPlayer)
- Gallery with delete and full-screen playback
- 19 unit tests + 6 UI regression tests

**What's next:**
- Loop generation (Media3 Transformer reversal + concatenation)
- Speed slider on preview screen
- Export with speed burn-in to device gallery
- Custom capture duration
- Share flow

## Contributing

OpenRang is early-stage and built by a solo developer with AI assistance. Contributions are welcome — check the issues tab or open a discussion if you want to help.

## License

**Apache License 2.0** — use it, fork it, ship it. See [LICENSE](LICENSE) for details.
