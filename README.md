# OpenLoop

**The open-source Boomerang camera app that should have existed years ago.**

No subscriptions. No ads. No accounts. Just point, tap, and loop.

[<img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="72">](https://play.google.com/store/apps/details?id=io.github.stozo04.openloop)

**[Website](https://stozo04.github.io/OpenLoop/)** · **[Google Play](https://play.google.com/store/apps/details?id=io.github.stozo04.openloop)** · **[Privacy policy](https://stozo04.github.io/OpenLoop/privacy-policy.html)**

OpenLoop is a free Android camera app for creating speed-controlled video loops — the kind of thing Big Tech locks behind paywalls and cluttered UIs. We're leveraging the power of AI and open-source tooling to bring the coolest creative toys to everyone, for free, forever.

Built with Google's latest Android libraries. All video processing runs on your device. Your videos never leave your phone.

## What It Does

- **Capture** — Hold the shutter for a clip (up to 30 s), or import one from your library
- **Seamless Loops** — Forward, reverse, or either bounce, generated entirely on-device via Media3 Transformer
- **Speed Control** — Real-time playback speed from 0.5x to 3.0x before you save: a constant slider, or a **custom speed curve** you draw over the whole loop (tap to add a point, drag to bend it, presets to start from) so a ramp can peak right on the direction turn — the preview plays the curve live and the export honors it exactly
- **Face Lenses** — seven of them: Broccoli, Shades, Pizza Face, Football, Dog, Twisted Tongue and Elvis. They render live on the viewfinder and record into the clip, tracked on-device with ML Kit — some react to you, like the tongue that hangs further out the wider you open your mouth. Two people in the shot? Both get the lens
- **Photo Mode** — Flip the shutter to stills, lenses included
- **Photo Booth** — a self-driving 5-4-3-2-1 countdown ×3 composited into a classic vertical strip (white borders, OpenLoop + date footer, color or B&W) — the countdown is your window to swap lenses between shots
- **Gallery** — Browse, replay, and manage all your loops in a slick grid
- **Private by design** — Your videos are processed 100% on-device and are never uploaded. No accounts, no ads, no advertising ID. The app does send limited, pseudonymous crash and usage diagnostics (Firebase Crashlytics + Analytics) — see the [privacy policy](docs/play-store/privacy-policy.md)

## Why OpenLoop?

Every boomerang/loop app on the Play Store either costs money, runs ads, or sends your videos to a server you don't control. OpenLoop is the alternative:

- **Open source** (Apache 2.0) — read every line, fork it, improve it
- **No accounts** — no sign-up, no login, no profile
- **No ads, ever** — not now, not later, not with a "premium tier"
- **AI-assisted development** — built faster and better by pairing human creativity with AI tooling
- **Google-first architecture** — follows every Jetpack best practice Google recommends

## Architecture & Tech Stack

| Layer             | Technology                          | What It Does                                                                          |
| ----------------- | ----------------------------------- | ------------------------------------------------------------------------------------- |
| **Language**      | Kotlin                              | Modern, concise, Google's preferred language for Android                              |
| **UI**            | Jetpack Compose                     | Declarative UI — no XML layouts, no fragments                                         |
| **Camera**        | AndroidX CameraX                    | Device-agnostic camera API that works across 1000+ Android devices                    |
| **Media**         | AndroidX Media3                     | ExoPlayer for looping playback, Transformer for video reversal & export               |
| **Preferences**   | Jetpack DataStore                   | Async, coroutine-based key-value storage (replaces SharedPreferences)                 |
| **State**         | MVVM + StateFlow                    | Single ViewModel, sealed-interface state machine, unidirectional data flow            |
| **Testing**       | JUnit 4 + MockK + Compose UI Test   | Unit tests for ViewModel logic, UI regression tests for layout-critical composables   |
| **Performance**   | Baseline Profiles                   | Pre-compiles "hot" code paths to eliminate Compose jank and speed up startup          |

**SDK levels:** `minSdk 26` (Android 8.0) · `compileSdk 37` · `targetSdk 36` — targeting **API 36 (Android 16)** for Google Play readiness, tracked in [Issue #7](https://github.com/stozo04/OpenLoop/issues/7). Behavior changes: [Android 16 behavior changes](https://developer.android.com/about/versions/16/behavior-changes-16) and [`docs/ANDROID_STANDARDS.md`](docs/ANDROID_STANDARDS.md) §11. Google Play's target-API rule: [Target API Level Requirements](https://developer.android.com/google/play/requirements/target-sdk).

### State Machine

```text
Initializing → Onboarding → CheckingPermissions → ReadyToCapture <-> Recording
   (returning user ↗)                                                  │ finalize
                                                                       ▼
                                                    Trim ──NEXT──▶ BoomerangEditor
                                                      ▲                  │ save
                                                      └──back────────────┤
                                                                         ▼
                                   ReadyToCapture ◀──success──────── Processing

Gallery <-> ReadyToCapture
```

All navigation is driven by a single `MutableStateFlow<OpenLoopUiState>` — no Jetpack Navigation needed at this scale. The `Initializing` state reads from DataStore to determine whether to show onboarding or skip straight to the camera.

### Project Structure

```text
io.github.stozo04.openloop/
├── camera/          CameraX lifecycle, recording, pinch-zoom
│   └── lens/        The lens catalogue, face tracking, and the GL renderer
├── data/            DataStore preferences, repository pattern
├── media/           Media3 Transformer pipeline + the two-pass reverse
├── ui/              Compose screens, ViewModel, state machine
├── work/            WorkManager render pipeline and MediaStore publish
└── MainActivity.kt  Permissions, routing, theme
```

> A fuller map — including `diagnostics/`, `review/` and `update/` — is in
> [`docs/OPENLOOP_INSTRUCTIONS.md`](docs/OPENLOOP_INSTRUCTIONS.md#source-layout).

## Getting Started

1. **Clone it:**

   ```bash
   git clone https://github.com/stozo04/OpenLoop.git
   ```

2. **Open in Android Studio** (a current 2026.x release — the build uses AGP 9.3.2, which needs a recent Studio; the IDE will prompt if yours is too old)

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

| Command                          | What it does                                                                   |
| -------------------------------- | ------------------------------------------------------------------------------ |
| `clean`                          | Deletes old build output — run it first if a build is acting weird             |
| `assembleDebug`                  | Builds the normal debug APK (everyday "does it compile and run?")              |
| `assembleRelease`                | Builds the optimized, shrunk release APK (the kind that goes to Google Play)   |
| `testDebugUnitTest`              | Runs the fast unit tests — no phone needed                                     |
| `connectedDebugAndroidTest`      | Runs the UI tests — needs a connected device or emulator                       |
| `generateReleaseBaselineProfile` | Generates a performance "cheat sheet" for the app — needs a device/emulator    |

You can chain them, e.g. `.\gradlew.bat clean assembleDebug`.

**How do I know it actually worked?** Don't trust "the command finished" — trust two signals:

1. The last line says **`BUILD SUCCESSFUL`** (a failure says `BUILD FAILED` and explains why).
2. The **exit code is `0`**. Check it right after the build — PowerShell: `echo $LASTEXITCODE`; macOS/Linux: `echo $?`. `0` means success; anything else means it failed.

Then skim the output for lines starting with `e:` (errors — these stop the build) or `w:` (warnings — these don't, but are worth a glance). A genuinely clean build prints `BUILD SUCCESSFUL` with no `e:` lines.

> **Gotcha:** if you pipe the build through something like `... | tail`, the exit code you see belongs to `tail`, not Gradle — so a failed build can look like it "passed." Check the `BUILD SUCCESSFUL`/`BUILD FAILED` line itself, not just whether the command returned cleanly.

### The pre-PR sweep (every gate, one command)

```powershell
.\scripts\pre-pr-sweep.ps1                                   # full: build + zipalign + lint + tests + text gates + Inspect Code export
.\scripts\pre-pr-sweep.ps1 -SkipConnected -SkipInspectCode   # no emulator / no Android Studio — the PR must say so
.\scripts\pre-pr-sweep.ps1 -DocsOnly                         # docs-only branch
```

It runs every check to **zero** — clean debug+release build with compiler warnings fatal, 16 KB
`zipalign`, Android Lint 0 errors / 0 warnings, unit + instrumented tests counted from XML,
markdownlint, IDE-faithful table alignment, relative links, tracked-file hygiene, `cspell` over every tracked text file,
JSON validity, the autonomous onboarding loop (gate 5b, overlapped), and the Android Studio Inspect Code export — and writes `build/sweep-receipt.json`
when all are green. A Claude Code hook refuses to create a PR without a receipt for the current
commit. Full policy: [`docs/DEFINITION_OF_DONE.md`](docs/DEFINITION_OF_DONE.md).

### Running the code inspections (Android Studio "Inspect Code")

OpenLoop reproduces Android Studio's **Code → Inspect Code** as a merge gate. It's **two
engines** — full design and severity rules in [`docs/STATIC_ANALYSIS.md`](docs/STATIC_ANALYSIS.md).
Set `JAVA_HOME` first (same as the build section above).

**Engine 1 — Android Lint** (fast, automated, the hard gate):

```powershell
.\gradlew.bat :app:lintDebug
```

Report lands at `app/build/reports/lint-results-debug.xml` (+ `.html`). A clean run has **zero
`severity="Error"` and zero `severity="Warning"` entries** (the version-freshness checks are
advisory). The
[`pr-reviewer`](.claude/skills/pr-reviewer/SKILL.md) skill runs this automatically and folds the
findings into its PR comment.

> There is deliberately **no `lint-baseline.xml`** — the 11 entries it used to hold were fixed
> rather than carried. Suppress a genuinely un-fixable finding at the source (`tools:ignore`,
> `@Suppress`) with the reason in a comment, instead of reintroducing a baseline that hides
> whatever else happens to be in the tree. See `docs/STATIC_ANALYSIS.md`.

**Engine 2 — IDE inspections + proofreading** (faithful Kotlin/Markdown/grammar pass) runs **in
Android Studio**: Code → Inspect Code → custom scope **OpenLoop Tracked** (committed at
`.idea/scopes/`, it skips git worktrees, build output and gitignored vendor files) → Export → HTML
into `build/inspect-export/`. Then:

```powershell
python scripts/inspect-report.py build/inspect-export/index.html   # 0 hard findings in tracked files, or it exits 1
```

The headless `inspect.bat` route is vacuous on this machine (it indexes nothing and reports zero) —
see `docs/STATIC_ANALYSIS.md`. Typos are dictionary-driven: add real terms to `cspell.json` and run
`python scripts/sync-ide-dictionary.py` so the IDE learns them too.

**Tier 3 — the headless text gates** (Node/Python; also run in CI as a hard backstop), whole repo,
zero findings, no baseline:

```bash
npx --yes markdownlint-cli2 $(git ls-files '*.md')                  # list numbering, spacing, fence languages
python scripts/md-table-align.py                                    # IDE-faithful table alignment (--fix rewrites)
for f in $(git ls-files '*.md'); do npx --yes markdown-link-check --config .markdown-link-check.json -q "$f"; done
git ls-files '*.md' '*.kt' '*.kts' '*.xml' '*.yml' '*.ps1' '*.py' '*.mjs' '*.json' '*.html' | npx --yes cspell --no-progress --file-list stdin
```

(detekt for Kotlin is deferred — stable detekt doesn't support this project's Kotlin version
(2.4.x) yet; see [`docs/STATIC_ANALYSIS.md`](docs/STATIC_ANALYSIS.md).)

## Performance (Baseline Profiles)

### What is a Baseline Profile? (Explained like a 5th grader)

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

> Note: this requires a connected device or emulator.

## Guides

Testing lanes, the reverse algorithm, and OEM workflows live in [`docs/guides/`](docs/guides/README.md).

Full documentation layout (where every `.md` and image belongs): [`docs/README.md`](docs/README.md).

## Brand Assets

The visual identity in one place — colors, the on-device launcher icon, and the assets Google Play hosts on the store listing.

| Asset                                     | Where it lives                                                                                                                                                                                                                  | Notes                                                                                                                                                                                                                                                                                                                                                                                                 |
| ----------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Color tokens**                          | [`app/src/main/java/io/github/stozo04/openloop/ui/theme/Color.kt`](app/src/main/java/io/github/stozo04/openloop/ui/theme/Color.kt)                                                                                              | Single source of truth — `ElectricLime` (`#CDFF4F`) primary, `Aqua` (`#34E1D5`) tertiary, coral semantic-only (recording + destructive). UI must read via `MaterialTheme.colorScheme`, never inline hex (Lesson 001).                                                                                                                                                                                 |
| **Launcher icon (adaptive)**              | [`app/src/main/res/mipmap-anydpi/ic_launcher.xml`](app/src/main/res/mipmap-anydpi/ic_launcher.xml) + [`app/src/main/res/drawable-nodpi/ic_launcher_foreground.png`](app/src/main/res/drawable-nodpi/ic_launcher_foreground.png) | Lime→aqua infinity on a transparent foreground over pure black. This is the **only** launcher icon the app ships — `minSdk 26` is exactly where adaptive icons landed, so `-anydpi` wins on every supported device and there is no legacy bitmap to keep in sync. The same foreground feeds the API 31+ splash screen via [`app/src/main/res/values/themes.xml`](app/src/main/res/values/themes.xml). |
| **Launcher icon (themed / monochrome)**   | [`app/src/main/res/drawable-nodpi/ic_launcher_monochrome.png`](app/src/main/res/drawable-nodpi/ic_launcher_monochrome.png)                                                                                                      | The `<monochrome>` layer the launcher tints from the user's wallpaper on Android 13+. **Derived** from `ic_launcher_foreground.png` (glow thresholded off, ribbon-crossing seam preserved) — regenerate it from the foreground rather than editing it by hand, so the two can't drift.                                                                                                                |
| **Play Store app icon (512×512)**         | [`docs/play-store/play_store_icon_512.png`](docs/play-store/play_store_icon_512.png)                                                                                                                                            | RGB (**no alpha**), solid Canvas-dark background, **no baked corners** — Play auto-applies a 30% corner radius at display time (active since 2026-03-31). Upload via **Play Console → Grow → Store presence → Main store listing → Graphics → App icon**.                                                                                                                                             |
| **Play Store feature graphic (1024×500)** | [`docs/play-store/main-image.png`](docs/play-store/main-image.png)                                                                                                                                                              | RGB (**no alpha**). Logo sits left-of-center so Play's promo-video play button (which lands dead-center if a promo video is attached) won't overlap it. Wordmark uses Space Grotesk Bold; tagline uses Inter Medium — matches the in-app type ramp in [`Type.kt`](app/src/main/java/io/github/stozo04/openloop/ui/theme/Type.kt). Upload via **Play Console → … → Graphics → Feature graphic**.       |

Store-listing updates (icon, feature graphic, screenshots, copy) are a **separate flow from publishing an APK/AAB** — save the store listing in Play Console and changes usually roll out within a few hours, no app release required.

For the full Play Store submission pack (copy, data safety, content rating, signing, screenshots), see [`docs/play-store/`](docs/play-store/README.md).

## Development Standards

This project follows Google's official Android development guidance. See [`docs/ANDROID_STANDARDS.md`](docs/ANDROID_STANDARDS.md) for the full standards reference with links to Google's specs. For Android 16 / `targetSdk 36`, see §11 and [Google's behavior changes](https://developer.android.com/about/versions/16/behavior-changes-16).

### PR Merge Policy

**No PR merges without passing the automated standards review.**

Every pull request is reviewed by an autonomous compliance agent ([`.claude/skills/pr-reviewer/`](.claude/skills/pr-reviewer/SKILL.md)) that audits code changes against 11 categories and 75+ checklist items sourced from Google's official Android documentation:

Architecture, DataStore, Permissions, Compose, CameraX, Media & Audio, Coroutines, Testing, Accessibility, Play Store Readiness, and Android Version Compatibility.

The reviewer web-searches `developer.android.com` for the latest guidance on every run — no stale rules. It posts a structured PASS/FAIL/WARNING report directly on the PR with file-level specifics, Google doc citations, and reasoning for every finding.

On top of the standards review, every PR must also pass **code inspection** — the same checks
Android Studio's *Code → Inspect Code* produces, run headlessly. There are two engines (see
[`docs/STATIC_ANALYSIS.md`](docs/STATIC_ANALYSIS.md) for the full design and the exact commands):

- **Engine 1 — Android Lint** (`./gradlew :app:lintDebug`): automated, run by the sweep and the
  reviewer skill, a hard gate — **zero lint errors and warnings** to merge. No baseline: findings
  are fixed, or suppressed at the source with a stated reason.
- **Engine 2 — IDE inspections + proofreading** (Android Studio Inspect Code, scope *OpenLoop
  Tracked*, HTML export parsed by `scripts/inspect-report.py`): the faithful Kotlin-redundancy /
  Markdown / grammar-and-typo pass. Run **before the PR**; the receipt and the reviewer both note
  whether it was run.

**To merge, a PR must:**

1. Have been opened from a **green pre-PR sweep** (`scripts/pre-pr-sweep.ps1` receipt for its final
   commit — the Claude Code hook enforces this; humans run the same script)
2. Receive an **APPROVE** verdict from the standards reviewer (zero FAILs)
3. Address all **WARNINGS** or document why they're accepted
4. Pass all unit tests (570+) and instrumented tests (100+)
5. Show **zero Android Lint errors and warnings** (Engine 1), and have the **IDE Inspect Code**
   export (Engine 2) parsed to zero hard findings — or state in the PR that Engine 2 was skipped

### Fixing Review Feedback

When a PR gets review feedback, open a new session with the OpenLoop folder mounted and ask the agent to address each review comment: verify findings against Google's latest docs, fix the code, push, post a response comment explaining what was fixed and why, then run a fresh standards review to confirm zero FAILs. Point it at the PR URL (e.g. `https://github.com/stozo04/OpenLoop/pull/XX`).

## Build Status

**What's shipping:**

- 3-page onboarding (with DataStore persistence — you only see it once)
- CameraX viewfinder, capture up to 30 s, plus import from your library
- Front/back toggle, pinch-to-zoom, and a stills photo mode
- Photo-booth strip: 5-4-3-2-1 ×3 capture composited into a printed strip (color or B&W)
- Loop generation — forward, reverse and both bounces — via Media3 Transformer
- Trim, speed control (0.5x–3.0x, constant or a custom curve) and Looks, all previewed before you save
- Seven face lenses, tracked with ML Kit and baked into the recording — on up to two faces at once
- Gallery with delete, full-screen playback and a share sheet
- In-app updates and a Play review prompt
- 574 unit tests + 122 instrumented tests

**What's next:**

- More lenses, including ones that react to your expression
- Custom capture duration

## Contributing

OpenLoop is early-stage and built by a solo developer with AI assistance. Contributions are welcome — check the issues tab or open a discussion if you want to help.

## License

**Apache License 2.0** — use it, fork it, ship it. See [LICENSE](LICENSE) for details.
