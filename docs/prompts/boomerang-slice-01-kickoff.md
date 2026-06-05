## Session Prompt — Implement Boomerang Slice 01

You are working on **OpenLoop** — an open-source Android camera app (Kotlin/Jetpack Compose) for creating speed-controlled video loops ("Boomerangs"). Repo: `stozo04/OpenLoop`. Owner: Steven Gates (@stozo04). Apache 2.0.

The app is in active development. Today's app surface is: 3-step Onboarding → live camera viewfinder with front/back lens toggle → 1.5 s auto-stop burst recording → captured clip lands in a Gallery view. There is no boomerang generation yet. The full boomerang feature is being rolled out in **7 thin vertical slices**. This session is for **slice 01** — the foundational slice that every later slice depends on.

## Critical Rule — Do Not Trust Your Training Data

Your knowledge cutoff could be a year old. **Do not assume** you know the current version of any Google standard, Android API behavior, Jetpack library pattern, testing framework convention, or Play Store requirement. Before making any claim about how something works or what Google recommends, **web-search `developer.android.com` first**. This applies to everything — Compose APIs, CameraX `Recording` lifecycle, coroutine testing, `Canvas.drawArc`, accessibility touch targets, anything. If you catch yourself writing "Google recommends X" without having searched for it in this session, stop and search.

## What slice 01 ships (one-paragraph summary)

Replace the existing 1.5 s self-stop with **user-controlled stop + 30 s auto-cap**. Shutter becomes **tap-to-start / tap-to-stop**, with a visible **progress ring** sweeping clockwise around the button and a `00:00 / 00:30` countdown chip top-center while recording. After this slice merges, the gallery starts holding clips long enough to be useful — every later slice in the boomerang rollout assumes captures up to 30 s.

The full slice spec — UX, technical deltas, testing plan, acceptance criteria — lives in `docs/active/boomerang-rollout/01-capture-variable-length.md`. **That doc is your PRD for this PR.** Treat it as authoritative.

## Before Writing Any Code — Read These Files (in this order)

These are your ground truth. They contain decisions, conventions, and constraints that override any assumptions you bring in.

1. **`CLAUDE.md`** — operating instructions, the "Critical Rule" above (full version), the architecture snapshot, and pointers to the reference docs.
2. **Every file in `docs/lessons_learned/`** — start with `README.md`, then read 001 through 011 in order. **This is non-negotiable** (see CLAUDE.md "Required Reading"). Lesson 002 (`collectAsStateWithLifecycle`) and Lesson 008 (JVM test patterns: `TemporaryFolder`, single `TestDispatcher`) are especially relevant to this slice.
3. **`docs/DEFINITION_OF_DONE.md`** — the verification gate every non-trivial change must clear before being called done. Baseline → debug + release green → unit + instrumented tests → **run the app + screenshot** → honest coverage statement. Not a nice-to-have; the bar.
4. **`docs/active/boomerang-editor/IMPLEMENTATION.md`** — the parent design doc. You don't need every section, but skim the "Status updates" at the top and §3 (UX flow), §5 (state machine), §7 (data layer) so you understand how slice 01 sits in the broader design.
5. **`docs/active/boomerang-rollout/README.md`** — the rollout map. Tells you what's around slice 01 so you don't over-build (don't ship slice 02's flow inside slice 01's PR).
6. **`docs/active/boomerang-rollout/01-capture-variable-length.md`** — your PRD. Read it end-to-end. Read it twice.
7. **`docs/PRD-mission-control.md`** §3 (Design System Tokens) — for the `NeonCoral`, `NeonPurple`, `GlassWhite`, `DeepCharcoal` token values and the gradient rules used by the shutter and countdown chip.
8. **`docs/ANDROID_STANDARDS.md`** — consult before introducing any new pattern. If the slice doc proposes something not in the standards doc, decide whether it's a *new* pattern or a clarification of an existing one.
9. **`docs/TEST_COVERAGE.md`** — test directory structure, frameworks, coroutine testing conventions. Follow this for the new unit and instrumented tests this slice adds.

**Read all of these before touching any code.** No exceptions.

## Phase 1: Cut the branch + capture a green baseline

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
git checkout main
git pull --rebase
git checkout -b feature/boomerang-slice-01-variable-length
.\gradlew.bat clean assembleDebug --console=plain
```

Confirm `BUILD SUCCESSFUL`, `$LASTEXITCODE -eq 0`, zero `e:` lines. **Do not pipe Gradle through `| tail`** — that gives you the tail's exit code, not Gradle's, and a failed build looks green (see CLAUDE.md and `HEY_CLAUDE_ITS_ME.md`). A green baseline now means any later failure is unambiguously yours.

## Phase 2: Web-verify the moving pieces (before writing any code)

For each of the following, **search developer.android.com this session** and confirm the API you intend to use exists as you remember it, in the versions pinned in `gradle/libs.versions.toml`:

- `androidx.camera.video.Recording.stop()` and `VideoRecordEvent.Finalize` behavior in CameraX **1.6.1**.
- `androidx.compose.foundation.Canvas` + `DrawScope.drawArc` for the progress ring (`startAngle = -90f`, `sweepAngle = elapsed / 30_000f * 360f`).
- `androidx.lifecycle.compose.collectAsStateWithLifecycle` import path (Lesson 002).
- `kotlinx.coroutines.test.runTest` + `TestDispatcher` virtual-time pattern as it works in `kotlinx.coroutines` 1.8+ (Lesson 008 calls out the failure mode).

If any signature has drifted from what the slice doc implies, **stop and surface the discrepancy** before coding. Don't silently adapt.

## Phase 3: Implement to the slice spec

Work the technical deltas listed in `docs/active/boomerang-rollout/01-capture-variable-length.md` §"Technical deltas":

- `OpenLoopViewModel.kt` — remove the 1.5 s `delay()` self-stop; add `stopBurstCapture(...)`; add `recordingElapsedMs: StateFlow<Long>`; add the 30 s auto-cap coroutine.
- `CameraScreen.kt` — shutter button toggles between idle and recording glyphs; progress ring overlay via `Canvas`; `00:00 / 00:30` countdown chip top-center while recording. All Flow collection via `collectAsStateWithLifecycle()` (Lesson 002).
- No changes to `OpenLoopUiState.kt`. No changes to `VideoStorageRepository` (those land in slice 02).

**Stay scoped to slice 01.** Do not introduce `ScratchCapture`, `BoomerangEditor`, `VideoProcessor`, `VideoReverser`, or any other slice-02+ surface. If you find yourself reaching for them, you've over-shot the slice.

## Phase 4: Test

- Add the unit tests from §"Testing plan / Unit tests" — `OpenLoopViewModelTest` cases for the new behavior. Use `TemporaryFolder` and a single shared `TestDispatcher` per Lesson 008. Do not mock `File`. Do not stack `MainDispatcherRule` with bare `runTest { }`.
- Add the instrumented tests from §"Testing plan / Instrumented tests" — `CameraScreenTest` for the shutter toggle, progress ring visibility, countdown text.
- Run both:
  ```powershell
  .\gradlew.bat testDebugUnitTest --console=plain; echo "EXIT=$LASTEXITCODE"
  $env:ANDROID_SERIAL = "<your-emulator-or-device-serial>"
  .\gradlew.bat connectedDebugAndroidTest --console=plain; echo "EXIT=$LASTEXITCODE"
  ```
- Open the XML results under `app/build/.../*-results/` and confirm **0 failures, 0 errors** — not just `BUILD SUCCESSFUL`.

## Phase 5: Run the app for real + screenshot (the DoD gate)

This is the step that separates "should work" from "works."

```powershell
.\gradlew.bat clean assembleDebug assembleRelease --console=plain; echo "EXIT=$LASTEXITCODE"
# Verify zipalign on release per Lesson 011 — `(OK)` not `(OK - compressed)` for every .so.
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\<ver>\zipalign.exe" -c -P 16 -v 4 `
  app\build\outputs\apk\release\app-release-unsigned.apk
```

Boot an emulator (or use Steven's Pixel 10 Pro Fold if available — see `HEY_CLAUDE_ITS_ME.md` for adb / multi-device gotchas), install the debug APK, and walk the slice 01 manual QA checklist end-to-end:

- Short capture (< 1 s) saves cleanly.
- 30 s untouched auto-stops and the clip plays back in `LoopingPreview` without freezing.
- Front and back camera both honor the new behavior.
- Background mid-recording does not crash and the partial clip is saved.

**Capture a screenshot of the recording state** (shutter mid-progress, countdown chip visible) and attach it to the PR description. Foldables and the multi-display screencap gotcha are documented in `HEY_CLAUDE_ITS_ME.md` — use `screencap -p /sdcard/x.png` + `pull` rather than piping through `exec-out screencap -p >`.

## Phase 6: Open the PR

- Push the branch to `stozo04/OpenLoop`.
- Open the PR. Title: `Slice 01 — Variable-length capture (≤30 s)`.
- Use the acceptance-criteria checklist from `docs/active/boomerang-rollout/01-capture-variable-length.md` §"Acceptance criteria" as the PR description's body, checking each box.
- Attach the screenshot from Phase 5.
- In a final paragraph, **honestly state what you could not verify** — anything skipped, anything you couldn't reproduce, anything that worked on the emulator, but you couldn't test on a real device. This is the spirit of Lesson 007 and the DoD doc.

After the PR is open, you're done with this session. The next session picks up either the PR review (`pr-reviewer` skill under `.claude/skills/`) or slice 02 (`docs/prompts/boomerang-slice-02-kickoff.md` once that exists).

## Behavioral Rules

- **PRD-first.** The slice doc is the spec. If you're tempted to add behavior not in the spec, stop and ask. If you think the spec is wrong, **push back** before coding around it — don't silently override (see CLAUDE.md "Pushback — required").
- **Web-search before every API claim.** You do not know what's current until you check this session.
- **Lessons compliance is checkable by grep.** Before pushing: confirm no `collectAsState(` calls (Lesson 002), no unwrapped DataStore writes (Lesson 003), no `Context` parameter on ViewModel methods (Lesson 004), every `Color(0x…)` literal has exactly 8 hex digits (Lesson 001). The slice doc's acceptance criteria list these.
- **`BUILD SUCCESSFUL` is not enough.** Read the verdict line itself, check `$LASTEXITCODE`, scan for `e:` lines. Never trust a piped exit code (CLAUDE.md "the `| tail` trap").
- **Done is not "it compiles."** Done is the DoD gate: build + tests + run + screenshot + honest coverage statement. Anything less and the PR isn't ready.
- **Reversibility protocol applies.** Before destructive operations (force-push, deleting branches, rewriting history), show the plan and wait for explicit "proceed."
- **Stay scoped.** Slice 01 only. No slice 02+ surface area in this PR.

## When to stop and come back to Steven

- If the slice doc and an existing lesson disagree.
- If web verification shows a CameraX / Compose / coroutine API has changed in a way that breaks the slice plan.
- If the green baseline in Phase 1 is *not* green (someone broke `main` and it needs fixing first).
- If `zipalign` on the release APK shows `(OK - compressed)` for any `.so` (Lesson 011 regression — flag immediately).
- If you discover the work is meaningfully larger than the slice doc implies (would be > ~600 LOC). Slices are PR-sized by design; if this one isn't, the slicing was wrong.
