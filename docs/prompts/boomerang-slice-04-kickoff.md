# Boomerang Slice 04 — Kickoff Prompt for a Fresh Claude Code Session

Copy everything below the line into a fresh Claude Code session with the OpenRang folder mounted. This kickoff is specific to **slice 04 (Speed tab)**. Assumes slices 01–03 have shipped and are merged to `main`.

---

## Session Prompt — Implement Boomerang Slice 04

You are working on **OpenRang** — an open-source Android camera app (Kotlin/Jetpack Compose) for creating speed-controlled video loops ("Boomerangs"). Repo: `stozo04/OpenRang`. Owner: Steven Gates (@stozo04). Apache 2.0.

The editor has a Direction tab and saves boomerangs at a hard-coded 2.0× speed (slices 02–03 shipped). This slice adds the **Speed tab** as the second icon in the bottom tab bar and a horizontal slider that controls both preview playback speed (live) and render speed.

## Critical Rule — Do Not Trust Your Training Data

Your knowledge cutoff could be a year old. **Do not assume** you know the current version of any Google standard, Android API behavior, Jetpack library pattern, testing framework convention, or Play Store requirement. Before making any claim about how something works or what Google recommends, **web-search `developer.android.com` first**. This applies to everything — ExoPlayer `setPlaybackSpeed`, Compose `Slider`, `HapticFeedbackConstants`, `snapshotFlow` + `debounce`. If you catch yourself writing "Google recommends X" without having searched for it in this session, stop and search.

## What slice 04 ships (one-paragraph summary)

The bottom tab bar grows from 1 icon (Direction `>>`) to 2 icons (+ Speed `⚡`). Tapping the Speed icon swaps the content panel to a horizontal slider, range **0.25× – 3.0×**, default **2.0×**, with a floating value label above the thumb and a haptic tick at exactly 1.0×. Preview's `setPlaybackSpeed` is called on every slider change (debounced ~50 ms) so the preview reflects the new speed instantly — no re-render. `VideoProcessor.renderBoomerang()` now passes the chosen `speed` instead of the hard-coded 2.0×. The cached reversed file from slice 02 is reused across speed changes — speed is a player-side effect, not a render-side one.

The full slice spec lives in `docs/active/boomerang-rollout/04-editor-speed-tab.md`. **That doc is your PRD for this PR.** Treat it as authoritative.

## Before Writing Any Code — Read These Files (in this order)

1. **`CLAUDE.md`** — operating instructions.
2. **Every file in `docs/lessons_learned/`** — 001 (Color literals — slider thumb color), 002 (Flow collection), 008 (test patterns) most relevant.
3. **`docs/DEFINITION_OF_DONE.md`** — the verification gate.
4. **`docs/active/boomerang-editor/IMPLEMENTATION.md`** — parent design doc. §6 (Media3 pipeline) for the speed-effect semantics (it's `SpeedChangeEffect` in Media3 1.10.1 — see the slice-04 KICKOFF §0).
5. **`docs/lessons_learned/KICKOFF-boomerang-slice-04-speed-tab.md`** — the hard-won deltas this PRD gets wrong (the render side is already done; the real risk is #1658). Read it before coding.
5. **`docs/active/boomerang-rollout/README.md`** — rollout map.
6. **`docs/active/boomerang-rollout/03-editor-direction-tab.md`** — for the `EditorTabState` and tab bar surface this slice extends.
7. **`docs/active/boomerang-rollout/04-editor-speed-tab.md`** — your PRD. End-to-end, twice.
8. **`docs/PRD-mission-control.md`** §3 (Design System Tokens) — for slider track / thumb colors.
9. **`docs/ANDROID_STANDARDS.md`** — consult before adding new patterns.
10. **`docs/TEST_COVERAGE.md`** — test conventions.

**Read all of these before touching any code.**

## Phase 1: Cut the branch + capture a green baseline

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
git checkout main
git pull --rebase
git checkout -b feature/boomerang-slice-04-speed-tab
.\gradlew.bat clean assembleDebug --console=plain
```

`BUILD SUCCESSFUL` + exit 0 + zero `e:`.

## Phase 2: Web-verify the moving pieces

- `androidx.media3.exoplayer.ExoPlayer.setPlaybackSpeed(Float)` — current signature in Media3 1.10.1; range constraints if any.
- `androidx.compose.material3.Slider` — current parameters for custom thumb / track via `track` / `thumb` slot composables.
- `android.view.HapticFeedbackConstants.CLOCK_TICK` — confirm availability on minSdk 26.
- `androidx.compose.runtime.snapshotFlow { ... }.debounce(...)` — current import paths.
- `androidx.media3.effect.SpeedChangeEffect(Float)` — the constant-speed effect in Media3 1.10.1 (NOT `SpeedChangingVideoEffect`, which isn't in this version). It's already wired in `Media3VideoProcessor.speedEffects(speed)`; confirm it supports the full 0.25 – 3.0 range.

If anything has drifted, **stop and surface** before coding.

## Phase 3: Implement to the slice spec

- **`OpenRangViewModel.kt`** — extend `EditorTabState` with `speed: Float = 2.0f` and `activeTab: EditorTab = EditorTab.DIRECTION`. Add `enum class EditorTab { DIRECTION, SPEED }` (REPS lands in slice 05). Mutators: `updateSpeed(speed: Float)` with `coerceIn(0.25f, 3.0f)`, `switchTab(tab: EditorTab)`.
- **`media/VideoProcessor.kt`** — **no change needed.** `renderBoomerang(speed=…)` already applies `SpeedChangeEffect(speed)` per clip (the `speed` param has been threaded since slice 02). The only render-side change is in `OpenRangViewModel.saveBoomerang()`: pass `speed = _editorTabState.value.speed` instead of `DEFAULT_SPEED`.
- **`ui/BoomerangEditorScreen.kt`** — render the tab bar with 2 entries instead of 1, driven by `editorTabState.activeTab`. Add `SpeedTabContent` composable with Compose `Slider` + floating value label + haptic tick at 1.0×. Debounce slider emissions (~50 ms) before calling `player.setPlaybackSpeed(...)`. Animate tab content cross-fade with `AnimatedContent` (200 ms fade).
- **`MainActivity.kt`** — no route changes.

**Stay scoped to slice 04.** Do not add: reps tab (slice 05), share intent (slice 06), gallery filter / tap-to-edit (slice 07). The tab bar shows **two** icons in this slice; do not pre-render a Reps placeholder.

## Phase 4: Test

- Unit tests: `OpenRangViewModelTest` for `updateSpeed` clamping, `switchTab` state change, `saveBoomerang` passing current speed to `VideoProcessor`. `VideoProcessorTest` for each of `[0.25, 0.5, 1.0, 1.5, 2.0, 3.0]` producing output duration ≈ `cycle_ms / speed` (±1 frame).
- Instrumented `BoomerangEditorScreenTest`: tab bar shows 2 icons, Speed tab shows slider, slider drag updates state + preview `playbackSpeed`, output-duration indicator updates as speed changes.
- End-to-end: capture → trim → editor → switch to Speed tab → drag to 0.5× → preview slow → save → output duration ≈ `cycle_ms × reps / speed`.
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

Walk the slice 04 manual QA on emulator + Pixel 10 Pro Fold:

- Drag slider full range; preview speed changes smoothly with no jank.
- 0.25× = clearly slow-motion. 3.0× = clearly fast.
- Haptic tick felt at exactly 1.0× on Pixel 10 Pro Fold.
- Save at 3 speeds (0.5×, 1×, 2.5×) — verify each output plays at the expected speed in the gallery's `LoopingPreview`.
- Switch tabs (Direction ↔ Speed) several times — selections persist, cross-fade clean.
- **Note** the longest output duration produced (so we know we're under the 60 s hard cap that lands in slice 05).

**Capture a screenshot** of the Speed tab with the slider mid-drag (value label visible) attached to the PR.

## Phase 6: Open the PR

- Push the branch to `stozo04/OpenRang`.
- PR title: `Slice 04 — Speed tab`.
- PR description: acceptance-criteria checklist from `docs/active/boomerang-rollout/04-editor-speed-tab.md` §"Acceptance criteria", each box checked.
- Attach the screenshot.
- State what was not verified.

## Behavioral Rules

- **PRD-first.** Push back before coding around what seems wrong.
- **Web-search before every API claim.**
- **Lessons compliance.** No `collectAsState(` (Lesson 002), 8-digit Color literals (Lesson 001), no `Context` on VM methods (Lesson 004), zipalign `(OK)` (Lesson 011).
- **`BUILD SUCCESSFUL` is not enough.** Verdict + exit code + no `e:`.
- **Done is the DoD gate.**
- **Stay scoped.** Slice 04 only. Two tab icons. No Reps surface.

## When to stop and come back to Steven

- If the slice doc and a lesson disagree.
- If `setPlaybackSpeed` doesn't behave as expected on reverse-composition previews (e.g., the cached reversed `MediaItem` chain ignores the player-side speed). That's a real risk worth investigating before merging.
- If `SpeedChangeEffect` has different range constraints than 0.25 – 3.0×, or if #1658 (per-item effects only applying to the first clip) is live at a non-2× speed — see the slice-04 KICKOFF §1.
- If `HapticFeedbackConstants.CLOCK_TICK` requires a higher minSdk than the current 26.
- If the green baseline isn't green.
- If `zipalign` regresses.
- If the work is meaningfully larger than the slice doc implies (would be > ~300 LOC; this is one of the smaller slices).
