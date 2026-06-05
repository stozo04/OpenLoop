# OpenLoop

**The open-source Boomerang camera app that should have existed years ago.**

No subscriptions. No ads. No data harvesting. Just point, tap, and loop.

OpenLoop is a free Android camera app for creating speed-controlled video loops — the kind of thing Big Tech locks behind paywalls and cluttered UIs. We're leveraging the power of AI and open-source tooling to bring the coolest creative toys to everyone, for free, forever.

Built with Google's latest Android libraries. Runs entirely on your device. Your videos never leave your phone.

## What It Does

- **Burst Capture** — Tap the shutter for a quick 1.5-second video burst (custom duration coming soon)
- **Seamless Loops** — Forward-backward loop generation entirely on-device via Media3 Transformer
- **Speed Control** — Real-time playback speed slider from 0.5x to 3.0x before you save
- **Gallery** — Browse, replay, and manage all your loops in a slick grid
- **100% Private** — Zero network calls. Zero tracking. Everything stays on your phone

## Why OpenLoop?

Every boomerang/loop app on the Play Store either costs money, runs ads, or sends your videos to a server you don't control. OpenLoop is the alternative:

- **Open source** (Apache 2.0) — read every line, fork it, improve it
- **No accounts** — no sign-up, no login, no profile
- **No ads, ever** — not now, not later, not with a "premium tier"
- **AI-assisted development** — built faster and better by pairing human creativity with AI tooling
- **Google-first architecture** — follows every Jetpack best practice Google recommends

## Architecture & Tech Stack

| Layer           | Technology                        | What It Does                                                                        |
|-----------------|-----------------------------------|-------------------------------------------------------------------------------------|
| **Language**    | Kotlin                            | Modern, concise, Google's preferred language for Android                            |
| **UI**          | Jetpack Compose                   | Declarative UI — no XML layouts, no fragments                                       |
| **Camera**      | AndroidX CameraX                  | Device-agnostic camera API that works across 1000+ Android devices                  |
| **Media**       | AndroidX Media3                   | ExoPlayer for looping playback, Transformer for video reversal & export             |
| **Preferences** | Jetpack DataStore                 | Async, coroutine-based key-value storage (replaces SharedPreferences)               |
| **State**       | MVVM + StateFlow                  | Single ViewModel, sealed-interface state machine, unidirectional data flow          |
| **Testing**     | JUnit 4 + MockK + Compose UI Test | Unit tests for ViewModel logic, UI regression tests for layout-critical composables |
| **Performance** | Baseline Profiles                 | Pre-compiles "hot" code paths to eliminate Compose jank and speed up startup       |

**SDK levels:** `minSdk 26` (Android 8.0) · `compileSdk 36` · `targetSdk 36` — upgraded to **API 36 (Android 16)** for Google Play readiness (`minSdk` stays 26), tracked in [Issue #7](https://github.com/stozo04/OpenLoop/issues/7), with the full behavior-change breakdown in [`docs/android-16/`](docs/android-16/README.md). Google Play's target-API rule: [Target API Level Requirements](https://developer.android.com/google/play/requirements/target-sdk).

### State Machine

```
Initializing → Onboarding → CheckingPermissions → ReadyToCapture <-> Recording
           \                       |                      |
        CheckingPermissions     Gallery            LoopingPreview
         (returning user)          |
                            ReadyToCapture
```

All navigation is driven by a single `MutableStateFlow<OpenLoopUiState>` — no Jetpack Navigation needed at this scale. The `Initializing` state reads from DataStore to determine whether to show onboarding or skip straight to the camera.

### Project Structure

```
io.github.stozo04.openloop/
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
   git clone https://github.com/stozo04/OpenLoop.git
   ```

2. **Open in Android Studio** (Hedgehog or newer recommended)

3. **Sync Gradle and run** the `:app` module on a device or emulator running Android 8.0+ (API 26+)

That's it. No API keys, no backend, no environment variables.

### Building from the command line (no Android Studio UI)

Sometimes you just want to build from a terminal — to check it compiles or to produce an installable APK. The project ships with the **Gradle wrapper** (`gradlew`), so you don't need to install Gradle yourself.

**1. Point Java at a JDK.** Gradle needs a Java Development Kit to run. The easiest one to use is the JDK bundled *inside* Android Studio (the "JBR"). Tell your terminal where it lives:

- **Windows (PowerShell):**
  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
  ```
- **macOS:**
  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  ```

(If `java -version` already prints a version in your terminal, Java is set up, and you can skip this step.)

**2. Build the app.** Use `.\gradlew.bat` on Windows, or `./gradlew` on macOS/Linux:

```powershell
.\gradlew.bat assembleDebug
```

The finished app lands at `app/build/outputs/apk/debug/app-debug.apk`.

**Other handy commands** (drop them in place of `assembleDebug`):

| Command                     | What it does                                                                 |
|-----------------------------|------------------------------------------------------------------------------|
| `clean`                     | Deletes old build output — run it first if a build is acting weird           |
| `assembleDebug`             | Builds the normal debug APK (everyday "does it compile and run?")            |
| `assembleRelease`           | Builds the optimized, shrunk release APK (the kind that goes to Google Play) |
| `testDebugUnitTest`         | Runs the fast unit tests — no phone needed                                   |
| `connectedDebugAndroidTest` | Runs the UI tests — needs a connected device or emulator                     |
| `generateReleaseBaselineProfile` | Generates a performance "cheat sheet" for the app — needs a device/emulator  |

You can chain them, e.g. `.\gradlew.bat clean assembleDebug`.

**How do I know it actually worked?** Don't trust "the command finished" — trust two signals:

1. The last line says **`BUILD SUCCESSFUL`** (a failure says `BUILD FAILED` and explains why).
2. The **exit code is `0`**. Check it right after the build — PowerShell: `echo $LASTEXITCODE`; macOS/Linux: `echo $?`. `0` means success; anything else means it failed.

Then skim the output for lines starting with `e:` (errors — these stop the build) or `w:` (warnings — these don't, but are worth a glance). A genuinely clean build prints `BUILD SUCCESSFUL` with no `e:` lines.

> **Gotcha:** if you pipe the build through something like `... | tail`, the exit code you see belongs to `tail`, not Gradle — so a failed build can look like it "passed." Check the `BUILD SUCCESSFUL`/`BUILD FAILED` line itself, not just whether the command returned cleanly.

### Running the code inspections (Android Studio "Inspect Code", from a terminal)

OpenLoop reproduces Android Studio's **Analyze → Inspect Code** as a merge gate. It's **two
engines** — full design and severity rules in [`docs/STATIC_ANALYSIS.md`](docs/STATIC_ANALYSIS.md).
Set `JAVA_HOME` first (same as the build section above).

**Engine 1 — Android Lint** (fast, automated, the hard gate):

```powershell
.\gradlew.bat :app:lintDebug
```

Report lands at `app/build/reports/lint-results-debug.xml` (+ `.html`). A committed
`lint-baseline.xml` filters out the project's pre-existing items, so a clean run reports **only
issues your branch introduced** — the XML will contain just the informational `LintBaseline`
"Hint" line. The [`pr-reviewer`](.claude/skills/pr-reviewer/SKILL.md) skill runs this automatically
and folds the findings into its PR comment.

> Regenerating the baseline (`app/lint-baseline.xml`) silently swallows *all* current issues,
> including newly-introduced ones — only do it deliberately. See `docs/STATIC_ANALYSIS.md`.

**Engine 2 — IDE inspections + proofreading** (faithful Kotlin/Markdown/grammar pass; slow, local):

```powershell
& "C:\Program Files\Android\Android Studio\bin\inspect.bat" `
  "C:\Users\gates\Personal\OpenLoop" `
  ".idea\inspectionProfiles\Project_Default.xml" `
  "build\inspection-results" `
  -v2 -d "C:\Users\gates\Personal\OpenLoop"
```

This boots a headless Android Studio (takes minutes) and writes one XML per inspection into
`build\inspection-results`. **Close the project in Android Studio first** and don't run a Gradle
task at the same time — they deadlock on the Gradle build lock.

**Tier 3 — OSS fallback** (for CI / machines without Android Studio): fast Node-based
approximations of Engine 2's Markdown/typo/link checks, scoped to a PR's changed Markdown. Needs
Node (no `npm install` — `npx` fetches on demand). Advisory only; configs live at the repo root
(`.markdownlint-cli2.jsonc`, `cspell.json`, `.markdown-link-check.json`).

```bash
FILES=$(git diff --name-only --diff-filter=d main...HEAD -- '*.md')
npx --yes markdownlint-cli2 $FILES                                  # tables, list numbering, structure
npx --yes cspell --no-progress $FILES                               # typos (project dictionary in cspell.json)
for f in $FILES; do npx --yes markdown-link-check --config .markdown-link-check.json "$f"; done  # broken links
```

(detekt for Kotlin is deferred — stable detekt doesn't support Kotlin 2.3.x yet; see
[`docs/STATIC_ANALYSIS.md`](docs/STATIC_ANALYSIS.md).)

## Performance (Baseline Profiles)

**What is a Baseline Profile? (Explained like a 5th grader)**

Imagine you have a new board game. The first time you play, you have to stop and read the rules every few seconds. It feels slow and jerky. But after you've played it 10 times, you know the rules by heart and the game moves fast!

Baseline Profiles are like a **"Cheat Sheet"** for your phone. Usually, when someone downloads your app, the phone has to "read the rules" of how to run the code while the user is using it. This can make the app feel "laggy" or "jerky." 

By building a Baseline Profile, we "play the game" for the phone ahead of time and write down all the rules. When the user opens the app, the phone reads the Cheat Sheet first, so everything feels smooth and fast from the very first tap.

### How we use them

We use the `androidx.baselineprofile` plugin to automate this:
1. The `:baselineprofile` module "drives" the app through its most important parts (opening, taking a video).
2. It records which parts of the code were used.
3. It saves this as a file that gets bundled into the final app (`.aab`) sent to Google Play.

**Automation:** You don't need to run this manually! The project is set up so that whenever you build a "Release" version of the app, Gradle automatically runs the generator to make sure the "Cheat Sheet" is up to date for the latest version.

If you *do* want to run it manually to see it work:
```powershell
.\gradlew.bat :app:generateReleaseBaselineProfile
```
*(Note: This requires a connected device or emulator.)*

## Guides

Plain-English, beginner-friendly walkthroughs live in [`docs/guides/`](docs/guides/):

- [**Jetpack DataStore — Explained Like You're Five**](docs/guides/jetpack-datastore-explained.md) — what the app's little "memory" is, where it lives on the phone, three ways to peek inside it, and how to reset onboarding for testing.

## Brand Assets

The visual identity in one place — colors, the on-device launcher icon, and the assets Google Play hosts on the store listing.

| Asset | Where it lives | Notes |
|---|---|---|
| **Color tokens** | [`app/src/main/java/io/github/stozo04/openloop/ui/theme/Color.kt`](app/src/main/java/io/github/stozo04/openloop/ui/theme/Color.kt) | Single source of truth — `ElectricLime` (`#CDFF4F`) primary, `Aqua` (`#34E1D5`) tertiary, coral semantic-only (recording + destructive). UI must read via `MaterialTheme.colorScheme`, never inline hex (Lesson 001). |
| **Launcher icon (adaptive)** | [`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`](app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml) + [`app/src/main/res/drawable-nodpi/ic_launcher_foreground.png`](app/src/main/res/drawable-nodpi/ic_launcher_foreground.png) | Lime→aqua infinity on a transparent foreground over pure black. The same foreground feeds the API 31+ splash screen via [`app/src/main/res/values/themes.xml`](app/src/main/res/values/themes.xml). |
| **Launcher icon (legacy)** | `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` + `ic_launcher_round.png` | Pre-API-26 fallback. `minSdk 26` means these are effectively unused on real devices, but the build still requires them. Keep them in sync with the adaptive foreground. |
| **Play Store app icon (512×512)** | [`docs/play-store/play_store_icon_512.png`](docs/play-store/play_store_icon_512.png) | RGB (**no alpha**), solid Canvas-dark background, **no baked corners** — Play auto-applies a 30% corner radius at display time (active since 2026-03-31). Upload via **Play Console → Grow → Store presence → Main store listing → Graphics → App icon**. |
| **Play Store feature graphic (1024×500)** | [`docs/play-store/feature_graphic_1024x500.png`](docs/play-store/feature_graphic_1024x500.png) | RGB (**no alpha**). Logo sits left-of-center so Play's promo-video play button (which lands dead-center if a promo video is attached) won't overlap it. Wordmark uses Space Grotesk Bold; tagline uses Inter Medium — matches the in-app type ramp in [`Type.kt`](app/src/main/java/io/github/stozo04/openloop/ui/theme/Type.kt). Upload via **Play Console → … → Graphics → Feature graphic**. |

Store-listing updates (icon, feature graphic, screenshots, copy) are a **separate flow from publishing an APK/AAB** — save the store listing in Play Console and changes usually roll out within a few hours, no app release required.

For the full Play Store submission pack (copy, data safety, content rating, signing, screenshots), see [`docs/play-store/`](docs/play-store/).

## Development Standards

This project follows Google's official Android development guidance. See [`docs/ANDROID_STANDARDS.md`](docs/ANDROID_STANDARDS.md) for the full standards reference with links to Google's specs. We treat these as non-negotiable — if Google recommends it, we follow it. For Android 16 / `targetSdk 36`-specific guidance behind the in-progress upgrade, see the [`docs/android-16/`](docs/android-16/README.md) knowledge hub.

### PR Merge Policy

**No PR merges without passing the automated standards review.**

Every pull request is reviewed by an autonomous compliance agent ([`.claude/skills/pr-reviewer/`](.claude/skills/pr-reviewer/)) that audits code changes against 11 categories and 75+ checklist items sourced from Google's official Android documentation:

Architecture, DataStore, Permissions, Compose, CameraX, Media & Audio, Coroutines, Testing, Accessibility, Play Store Readiness, and Android Version Compatibility.

The reviewer web-searches `developer.android.com` for the latest guidance on every run — no stale rules. It posts a structured PASS/FAIL/WARNING report directly on the PR with file-level specifics, Google doc citations, and reasoning for every finding.

On top of the standards review, every PR must also pass **code inspection** — the same checks
Android Studio's *Analyze → Inspect Code* produces, run headlessly. There are two engines (see
[`docs/STATIC_ANALYSIS.md`](docs/STATIC_ANALYSIS.md) for the full design and the exact commands):

- **Engine 1 — Android Lint** (`./gradlew :app:lintDebug`): automated, run by the reviewer skill,
  a hard gate — **zero new lint errors** to merge. A committed `lint-baseline.xml` means only
  issues *introduced by the PR* are reported, not the project's pre-existing items.
- **Engine 2 — IDE inspections + proofreading** (`inspect.bat`): the faithful Kotlin-redundancy /
  Markdown / grammar-and-typo pass. Run **locally before merge** (it needs Android Studio and is
  slow); the reviewer notes whether it was run.

**To merge, a PR must:**
1. Receive an **APPROVE** verdict from the standards reviewer (zero FAILs)
2. Address all **WARNINGS** or document why they're accepted
3. Pass all unit tests (19+) and UI regression tests (6+)
4. Show **zero new Android Lint errors** (Engine 1), and have **IDE Code Inspection** (Engine 2)
   run locally with its findings addressed or accepted

### Fixing Review Feedback

When a PR gets review feedback, open a new Cowork session with the OpenLoop folder mounted and say:

> Start addressing PR feedback following `docs/prompts/PR-FEEDBACK-RESOLUTION.md` — here is the PR: https://github.com/stozo04/OpenLoop/pull/XX

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

OpenLoop is early-stage and built by a solo developer with AI assistance. Contributions are welcome — check the issues tab or open a discussion if you want to help.

## License

**Apache License 2.0** — use it, fork it, ship it. See [LICENSE](LICENSE) for details.
