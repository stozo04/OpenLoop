# Boomerang Slice 05 — Kickoff Prompt for a Fresh Claude Code Session

Copy everything below the line into a fresh Claude Code session with the OpenLoop folder mounted. This kickoff is specific to **slice 05 (Repetitions tab)**. Assumes slices 01–04 have shipped and are merged to `main`.

---

## Session Prompt — Implement Boomerang Slice 05

You are working on **OpenLoop** — an open-source Android camera app (Kotlin/Jetpack Compose) for creating speed-controlled video loops ("Boomerangs"). Repo: `stozo04/OpenLoop`. Owner: Steven Gates (@stozo04). Apache 2.0.

The editor has Direction and Speed tabs (slices 03–04 shipped). After this slice the editor is **feature-complete** — direction + speed + reps all dial-able, plus the long-boomerang warning that protects against accidental 5-minute renders.

## Critical Rule — Do Not Trust Your Training Data

Your knowledge cutoff could be a year old. **Do not assume** you know the current version of any Google standard, Android API behavior, Jetpack library pattern, testing framework convention, or Play Store requirement. Before making any claim about how something works or what Google recommends, **web-search `developer.android.com` first**. This applies to everything — Media3 Composition repeat handling, `ConcatenatingMediaSource2` item appending, Compose segmented buttons. If you catch yourself writing "Google recommends X" without having searched for it in this session, stop and search.

## What slice 05 ships (one-paragraph summary)

Tab bar grows from 2 icons to 3 (+ Reps `⏱`). The Reps tab content is 4 circular buttons labeled 1 / 2 / 3 / 4, default 1, single-select with gradient fill on the selected option. Live preview now loops the cycle N times before repeating. `VideoProcessor.renderBoomerang()` appends the cycle N times in the Composition (reusing the same `EditedMediaItem`s — no source re-reads). A soft warning chip ("Long boomerang — Ns") appears above the tab bar when projected output > 30 s; a hard error disables save when > 60 s.

The full slice spec lives in `docs/active/boomerang-rollout/05-editor-repetitions-tab.md`. **That doc is your PRD for this PR.** Treat it as authoritative.

## Before Writing Any Code — Read These Files (in this order)

1. **`CLAUDE.md`**.
2. **Every file in `docs/lessons_learned/`** — 001, 002, 008 most relevant.
3. **`docs/DEFINITION_OF_DONE.md`**.
4. **`docs/active/boomerang-editor/IMPLEMENTATION.md`** — §6.3 has the duration math (`cycle_ms × reps / speed`) and the 30 s / 60 s thresholds (D-5).
5. **`docs/active/boomerang-rollout/README.md`**.
6. **`docs/active/boomerang-rollout/04-editor-speed-tab.md`** — for the `EditorTabState`, tab bar pattern, and tab content cross-fade this slice extends.
7. **`docs/active/boomerang-rollout/05-editor-repetitions-tab.md`** — your PRD. End-to-end, twice.
8. **`docs/PRD-mission-control.md`** §3 (Design System Tokens) — for circle button gradient, warning chip `NeonCoral` tone.
9. **`docs/ANDROID_STANDARDS.md`**.
10. **`docs/TEST_COVERAGE.md`**.

**Read all of these before touching any code.**

## Phase 1: Cut the branch + capture a green baseline

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
git checkout main
git pull --rebase
git checkout -b feature/boomerang-slice-05-reps-tab
.\gradlew.bat clean assembleDebug --console=plain
```

`BUILD SUCCESSFUL` + exit 0 + zero `e:`.

## Phase 2: Web-verify the moving pieces

- `androidx.media3.transformer.Composition.Builder` — confirm appending the same `EditedMediaItem` multiple times is supported and doesn't re-read the source per item in 1.10.1.
- ExoPlayer preview: rebinding `ConcatenatingMediaSource2` with N copies of the cycle — interruption cost (slice doc estimates ~100 ms; verify against Media3 1.10.1 release notes).
- Compose `Touch Target` recommendation — confirm 56 dp is current Material guidance for the circle reps buttons (or 48 dp minimum).

If anything has drifted, **stop and surface** before coding.

## Phase 3: Implement to the slice spec

- **`OpenLoopViewModel.kt`** — extend `EditorTabState` with `repetitions: Int = 1` and add `REPETITIONS` to the `EditorTab` enum. Mutator `updateRepetitions(reps: Int)` with `coerceIn(1, 4)`. Add the derived `outputDurationMs` computed property (`cycle_ms × reps / speed`, where `cycle_ms` depends on mode). `saveBoomerang()` blocks (and emits a "too long" event) when `outputDurationMs > 60_000`.
- **`media/VideoProcessor.kt`** — append the cycle `repetitions` times to the `Composition`. Apply the 1-frame seam offset at cycle boundaries too (not just at the F→R seam).
- **`ui/BoomerangEditorScreen.kt`** — render 3-icon tab bar. Add `RepetitionsTabContent` composable: row of 4 circular buttons, 56 dp, single-select with gradient. Rebuild preview composition on `repetitions` change. Output-duration label updates live. Warning chip slides in/out at the 30 s threshold; save button disables at the 60 s threshold (with tab-icon pulse on long-press of save when blocked).
- **`MainActivity.kt`** — no route changes.

**Stay scoped to slice 05.** Do not add: share intent / FileProvider (slice 06), gallery filter / kind badge / tap-to-edit (slice 07). The Reps tab caps at 4; do not add 5–10 just because it's a small change.

## Phase 4: Test

- Unit tests: `OpenLoopViewModelTest` for `updateRepetitions` clamping, `outputDurationMs` math across all 4 modes × edge speeds × reps, `saveBoomerang` no-op + "too long" event at > 60 s. `VideoProcessorTest` for `mode=F→R, trim=2s, speed=1×, reps=3` ≈ 12 s output; `mode=FWD, trim=1s, speed=2×, reps=4` ≈ 2 s output.
- Instrumented `BoomerangEditorScreenTest`: 3-icon tab bar, reps button selection, warning chip at 30 s threshold, save disable at 60 s threshold, tab persistence across switches.
- End-to-end: capture → trim → editor → F→R, 1× speed, 3 reps → save → output duration ≈ 9 s.
- Run:
  ```powershell
  .\gradlew.bat testDebugUnitTest --console=plain; echo "EXIT=$LASTEXITCODE"
  $env:ANDROID_SERIAL = "<your-emulator-or-device-serial>"
  .\gradlew.bat connectedDebugAndroidTest --console=plain; echo "EXIT=$LASTEXITCODE"
  ```

## Phase 5: Run the app for real + screenshot (the DoD gate)

```powershell
.\gradlew.bat clean assembleDebug assembleRelease --console=plain; echo "EXIT=$LASTEXITCODE"
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\<ver>\zipalign.exe" -c -P 16 -v 4 `
  app\build\outputs\apk\release\app-release-unsigned.apk
```

Walk the slice 05 manual QA on emulator + Pixel 10 Pro Fold:

- Each rep value (1, 2, 3, 4): preview loops cycle N times before repeating; output file matches.
- Default settings (F→R, 2×, 5 s trim, 4 reps): output = 10 s, no warning chip.
- F→R, 0.5×, 4 s trim, 4 reps: output = 64 s, save disables, tooltip on long-press, tab icons pulse.
- Switch tabs (Dir ↔ Speed ↔ Reps): all selections persist.
- **Record the largest output file produced** (file size + duration) in the PR description.

**Capture a screenshot** of the Reps tab with `2` selected, attached to the PR.

## Phase 6: Open the PR

- Push to `stozo04/OpenLoop`.
- PR title: `Slice 05 — Repetitions tab`.
- PR description: acceptance-criteria checklist from `docs/active/boomerang-rollout/05-editor-repetitions-tab.md`, each box checked. Include the largest output file size + duration observed.
- Attach the screenshot.
- State what was not verified.

## Behavioral Rules

- **PRD-first.** Push back before coding around what seems wrong.
- **Web-search before every API claim.**
- **Lessons compliance.** No `collectAsState(` (Lesson 002), 8-digit Color literals (Lesson 001), no `Context` on VM methods (Lesson 004), zipalign `(OK)` (Lesson 011).
- **`BUILD SUCCESSFUL` is not enough.**
- **Done is the DoD gate.**
- **Stay scoped.** Slice 05 only. 3 tab icons. No share intent, no gallery surface.

## When to stop and come back to Steven

- If the slice doc and a lesson disagree.
- If Composition-with-N-cycle-copies is slow or causes encoder failures on Pixel 10 Pro Fold at the upper end (e.g., 4 reps at 0.25× of a 10 s trim).
- If Media3 1.10.1 doesn't let you append the same `EditedMediaItem` multiple times without re-reading the source — the slice doc assumes you can; verify before committing to the implementation.
- If the green baseline isn't green.
- If `zipalign` regresses.
- If the work is meaningfully larger than the slice doc implies (would be > ~300 LOC).
