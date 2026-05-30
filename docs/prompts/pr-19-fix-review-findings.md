# PR #19 — Fix Review Findings (Slice 01: variable-length capture)

Copy everything below the line into a **fresh Claude Code session** with the OpenRang folder mounted and the branch `feature/boomerang-slice-01-variable-length` checked out.

This prompt is a **finding-specific** companion to `docs/prompts/PR-FEEDBACK-RESOLUTION.md`. That file is the generic "address feedback + re-review" harness; **this** file enumerates the exact 7 findings from the automated review on PR #19 that are in scope (3 WARNINGs + 4 Recommendations), each with file:line, the fix, and the **covering test** that must prove it. Use both: the steps below for *what* to change, the generic doc's Phase 4/5 for the response comment + re-review.

> **Deliberately out of scope:** the review's REC about the "Loopify" no-op button (`PreviewScreen.kt`) is **intentional** — it's a placeholder that slice 02 repoints to the Trim screen (`docs/prompts/boomerang-slice-02-kickoff.md` → `02-auto-route-trim-and-default-save.md`, which routes `Finalize` success to `Trim(ScratchClip)`). **Do not change it.** Acknowledge it as intentional/deferred in the PR response comment; do not "fix" it.

> Source review comment: <https://github.com/stozo04/OpenRang/pull/19#issuecomment-4569900450>

---

## Session Prompt — Fix the PR #19 review findings (with tests)

You are working on **OpenRang** — an open-source Android camera app (Kotlin/Jetpack Compose) for creating speed-controlled video loops ("Boomerangs"). Repo: `stozo04/OpenRang`. Owner: Steven Gates (@stozo04). Apache 2.0. You are on branch `feature/boomerang-slice-01-variable-length` (PR #19).

### Critical Rule — Do Not Trust Your Training Data

Your knowledge cutoff could be a year old. **Do not assume** you know current Android/Compose/CameraX/coroutines behavior. Before any "Google recommends X" claim or any API-behavior assumption (what `Recording.start()` throws, how `BackHandler` resolves its dispatcher, the 48dp touch-target rule, deferred state reads), **web-search `developer.android.com` first** in this session. If you catch yourself asserting an API behavior you haven't searched for this session, stop and search.

### Phase 0 — Required reading (before touching code)

1. `CLAUDE.md` — operating instructions, architecture snapshot, Definition-of-Done pointer.
2. **Every file in `docs/lessons_learned/`** — especially `012-camera-bound-screen-single-call-site.md` (this PR added it; several findings below interact with it) and `008` (bounded coroutine loops in virtual-time tests).
3. `docs/PRD-mission-control.md` — **Decision Log #1** ("Sealed interface for UI state — exhaustive `when` matching **at compile time**") is the basis for WARNING-1, and the state table lists `Processing` as a planned state. The hand-off notes in Lesson 012 §"slice 02" record Steven's leanings on the Loopify button — read them.
4. `docs/ANDROID_STANDARDS.md` — §2 (defer state reads), §3 (catch specific exceptions), §7 (coroutines), §10 (exhaustive `when`, extract-for-testability), §11 (predictive back at target 36).
5. `docs/TEST_COVERAGE.md` — test directory split (`test/` JVM unit vs `androidTest/` instrumented), MockK + `kotlinx-coroutines-test` conventions, the `MainDispatcherRule`, and the backtick test-naming convention.
6. `docs/DEFINITION_OF_DONE.md` — the verification gate you must clear before calling this done.

Then read the files you'll touch in full: `app/src/main/java/com/openrang/app/MainActivity.kt`, `ui/CameraScreen.kt`, `ui/OpenRangViewModel.kt`, `ui/OpenRangUiState.kt`, `ui/PreviewScreen.kt`, `camera/CameraManager.kt`, and the two test files `test/.../ui/OpenRangViewModelTest.kt`, `androidTest/.../ui/CameraScreenTest.kt`.

### Phase 1 — Confirm owner decisions BEFORE building

Two findings cross into UX/behavior choices. Per OpenRang's pushback + reversibility rules, **surface these to Steven and get a one-word answer before writing the code for them.** Propose the recommended default in parentheses; don't silently pick:

- **D1 — Back while Recording (WARNING-2).** Should the system back gesture during `Recording` (a) **stop & finalize** the clip (same as tapping stop → `LoopingPreview`), or (b) **discard** the in-flight clip and return to `ReadyToCapture`? *(Recommended: (a) stop & finalize — least surprising, no silent data loss, reuses `stopBurstCapture`.)*
- **D2 — `Processing` routing (WARNING-1).** `Processing` is a defined-but-unrouted state (slice 02 will give it a real UI per `02-auto-route-trim-and-default-save.md`). For *this* cleanup, render it as (a) the existing **`InfinityLoadingScreen()`** placeholder, or (b) a dedicated minimal "processing" UI? *(Recommended: (a) — a safe placeholder that closes the exhaustiveness/unguarded-`CameraScreen` gap now; slice 02 replaces it with the real Processing surface.)*

If Steven is unavailable, build the recommended defaults and clearly flag the assumption in the PR response comment.

### Phase 2 — Verify each finding is still valid

For every item below, web-search the cited doc to confirm the standard before fixing. If a finding is stale, or you disagree after reading the code, **say so with reasoning** rather than making a change you can't justify.

---

## The findings — fix + covering test for each

Order them by risk: warnings first, then recommendations. Each must end green with its own test.

### WARNING-1 — `else ->` defeats sealed-interface exhaustiveness + reintroduces an unguarded `CameraScreen` call site
- **Where:** `MainActivity.kt:178-184` (`else -> { CameraScreen(...) }`); unhandled state `OpenRangUiState.Processing` at `OpenRangUiState.kt:17`.
- **Why it matters:** Decision Log #1 chose a sealed interface specifically so the compiler forces every state to be handled. The `else` defeats that, leaves `Processing` silently falling through to a **bare `CameraScreen`** (no `CameraScreenHost`), and makes Lesson 012's own detection check fail — `grep -n "CameraScreen(" MainActivity.kt` returns **2** call sites, but the lesson requires exactly **1** reachable during capture. That second call site is the exact seam the `ERROR_SOURCE_INACTIVE` fix closed.
- **Fix:** Remove the `else ->` branch. Add an explicit `is OpenRangUiState.Processing ->` branch rendering the agreed D2 placeholder UI. Let the `when` be exhaustive (no `else`) so the next new state won't compile until handled. (Slice 02 will swap the placeholder for the real Processing surface — leave a `// TODO(slice-02)` so that hand-off is obvious.)
- **Recommended structural move (enables the test):** Extract the routing `when` out of `setContent` into a testable, stateless `@Composable fun OpenRangNavHost(uiState, viewModel, cameraManager)` — mirrors the project's existing extract-for-testability pattern (`OnboardingNavigation`, PRD §UI). MainActivity then just calls `OpenRangNavHost(uiState, ...)`.
- **Covering tests:**
  - **Compile-time (the real guard):** after removing `else`, intentionally comment out one branch locally and confirm the build fails with a non-exhaustive-`when` error, then restore it. (Don't commit the broken state — this just proves the guard is live.)
  - **Instrumented** (`CameraScreenTest.kt` or a new `OpenRangNavHostTest.kt`): mount `OpenRangNavHost` with `uiState = OpenRangUiState.Processing` and assert the camera content is **not** mounted (e.g. reuse the `host_content`/`LaunchedEffect` counter trick from `cameraScreenHost_keepsContentMounted_acrossCaptureTransition`, or assert the loading marker shows and `progress_ring`/shutter `contentDescription` do not exist).
  - **Lesson-012 grep:** confirm `grep -n "CameraScreen(" app/src/main/java/com/openrang/app/MainActivity.kt` returns exactly **1** match after the fix.

### WARNING-2 — No `BackHandler` for the `Recording` state (predictive back is default-on at target 36)
- **Where:** `CameraScreen.kt` (no `BackHandler` anywhere; `PreviewScreen.kt:47` shows the correct pattern).
- **Why it matters:** At `targetSdk 36`, predictive back is on by default and `onBackPressed`/`KEYCODE_BACK` are no longer dispatched (ANDROID_STANDARDS §11). A back gesture mid-record finishes the Activity → `onDestroy` → `cameraManager.shutdown()`, silently discarding an up-to-30 s recording. §11 requires back to route through the state machine.
- **Fix (per D1):** In `CameraScreen`, add `BackHandler(enabled = isRecording) { viewModel.stopBurstCapture(cameraManager) }` (or the discard variant if D1 = (b)). Leave `ReadyToCapture` with **no** handler — exiting from the home screen via back is correct, so the handler must be gated on `isRecording`.
- **Covering test (instrumented):** Use `createAndroidComposeRule<ComponentActivity>()` (it provides an `OnBackPressedDispatcher`; the plain `createComposeRule` does not). Mount a small stateless host exposing `isRecording` + an `onBack` lambda wrapping `BackHandler(enabled = isRecording) { onBack() }`, then:
  - with `isRecording = false`, dispatch back (`activity.onBackPressedDispatcher.onBackPressed()` on the main thread) → assert `onBack` was **not** called (the handler is disabled, so the event passes through);
  - with `isRecording = true`, dispatch back → assert `onBack` **was** called exactly once.
  - If you'd rather test the real wiring, add a ViewModel-level assertion that the lambda invokes `stopBurstCapture` (already covered by existing ViewModel tests, so the new test only needs to prove the enabled-gating).

### WARNING-3 — Gallery/home button touch target is 44dp (< 48dp minimum)
- **Where:** `CameraScreen.kt:159` — the home/gallery `Box` is `.size(44.dp)`. (Pre-existing; the shutter at 86dp and lens toggle at 54dp are fine.)
- **Why it matters:** Material/accessibility requires ≥48×48dp interactive targets; 44dp is a standard pre-launch accessibility-scanner failure.
- **Fix:** Bump to `.size(48.dp)` (keep the 20dp icon). To make it testable, **extract** the button into a stateless `@Composable fun HomeButton(onClick: () -> Unit, modifier: Modifier = Modifier)` (same hoisting pattern as `ShutterButton`).
- **Covering test (instrumented, `CameraScreenTest.kt`):** mount `HomeButton {}` and assert
  `onNodeWithContentDescription("Gallery").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)`.

### REC-1 — Defer the `recordingElapsedMs` read out of `CameraScreen`'s root (recomposition scope)
- **Where:** `CameraScreen.kt:102` collects the flow at the top level; `:106-115` recompute `progress` + two `String.format(...)` allocations every ~33 ms tick (~30/s), recomposing the entire screen body.
- **Why it matters:** Compose best practices say defer state reads to the narrowest scope so only the consumers (`ShutterButton`, `RecordingCountdownChip`) recompose, not the whole viewfinder tree. The arc already draws in the draw phase via `Canvas`.
- **Fix (pick one, prefer A):**
  - **A.** Change the consumers to take **lambdas**: `ShutterButton(isRecording, progressFraction: () -> Float, onClick)` and `RecordingCountdownChip(visible, text: () -> String)`. Move the `recordingElapsedMs` collection + label formatting into a small child composable (e.g. `CaptureControlsRow`) so the read happens inside the lambda scope. Update the existing `CameraScreenTest` call sites to the lambda signatures.
  - **B.** If the lambda churn isn't worth it, extract a `@Composable fun RecordingOverlay(viewModel)` child that does the `collectAsStateWithLifecycle()` itself, so the parent `CameraScreen` no longer reads the flow.
- **Covering tests:**
  - Update the existing `ShutterButton`/`RecordingCountdownChip` tests to the new signatures and keep them green (they already assert ring/glyph/chip behavior).
  - **Optional but ideal — recomposition counter (instrumented):** mount the screen/overlay, increment a counter inside the parent composable body (a plain `var` captured + `SideEffect { parentRecompositions++ }`), drive several elapsed updates via a `MutableStateFlow`, and assert the **parent** recomposition count stays ~constant while the child updates. If a reliable counter proves too fiddly, document that the perf win was verified manually via Layout Inspector recomposition counts and skip the automated assert — don't ship a flaky test.

### REC-2 — Handle `startRecording(...) == null` (stuck-in-`Recording` hang)
- **Where:** `OpenRangViewModel.kt:110` ignores the `Recording?` return. `CameraManager.startRecording` returns `null` when `videoCapture` isn't bound — then no `Finalize` ever fires, the auto-cap's `stopRecording()` is a no-op, and the UI is stuck in `Recording` with a full ring for 30 s.
- **Fix:** Capture the return value; if `null`, `clearRecordingTimers()`, set `_uiState.value = ReadyToCapture`, and **return before launching the timer coroutine**.
- **Covering test (`OpenRangViewModelTest.kt`, JVM unit):**
  ```kotlin
  @Test
  fun `startBurstCapture reverts to ReadyToCapture when recording cannot start`() =
      runTest(mainDispatcherRule.testDispatcher) {
          viewModel.onPermissionsChecked(true)
          every { cameraManager.startRecording(any(), any()) } returns null
          viewModel.startBurstCapture(cameraManager)
          advanceUntilIdle()
          assertEquals(OpenRangUiState.ReadyToCapture, viewModel.uiState.value)
          assertEquals(0L, viewModel.recordingElapsedMs.value)
          verify(exactly = 0) { cameraManager.stopRecording() }
      }
  ```
  (Confirm there's no orphan timer coroutine left spinning — `advanceUntilIdle()` should settle immediately.)

### REC-3 — Narrow the broad `catch (e: Exception)` in `startBurstCapture`
- **Where:** `OpenRangViewModel.kt:150`.
- **Why it matters:** ANDROID_STANDARDS §3 — catch specific types; a bare `Exception` swallows programming errors as a silent state reset.
- **Fix:** **Web-search the exact throwables** of `PendingRecording.prepareRecording`/`Recording.start()` (and the audio-permission path) on `developer.android.com` before narrowing — likely `IllegalStateException`, `IllegalArgumentException`, `SecurityException`, `IOException`. Catch those explicitly; let anything unexpected propagate.
- **Covering test (`OpenRangViewModelTest.kt`, JVM unit):** `every { cameraManager.startRecording(any(), any()) } throws IllegalStateException("camera busy")`, call `startBurstCapture`, assert it recovers to `ReadyToCapture` with `recordingElapsedMs == 0L` and no leaked timer.

> **Not in this prompt — "Loopify" no-op button (`PreviewScreen.kt:105-106`).** The review flagged it, but it is an **intentional** slice-02 placeholder: slice 02 routes `Finalize` success to the Trim screen and repoints this button (see `boomerang-slice-02-kickoff.md` / `02-auto-route-trim-and-default-save.md`, plus the Lesson 012 §slice-02 hand-off). **Do not modify it.** In the PR response comment, mark this finding **ACKNOWLEDGED — intentional, deferred to slice 02.**

---

## Phase 3 — Verify (Definition of Done gate)

Do **not** call this done because it compiles. Clear `docs/DEFINITION_OF_DONE.md`:

1. **Baseline first:** confirm `main`/branch is green before your changes (so a pre-existing failure isn't blamed on you).
2. **Clean build, debug AND release:** `./gradlew assembleDebug assembleRelease` → `BUILD SUCCESSFUL`, **exit code 0**, **zero `e:` lines**. Do not trust a `| tail`-masked exit code (Lesson on masked exits).
3. **16 KB alignment:** `zipalign -c -P 16 -v 4` shows `(OK)` per native `.so` (Lesson 011 — must be the *uncompressed* pass, not "OK - compressed").
4. **Unit tests:** `./gradlew testDebugUnitTest` — 0 failures, including the new REC-2/REC-3 ViewModel tests.
5. **Instrumented tests:** `./gradlew connectedDebugAndroidTest` — 0 failures, including the new WARNING-1/2/3 + updated REC-1 Compose tests. (Boot the AVD with `-memory 4096`; a default-RAM AVD OOM-kills the instrumentation process and reports a false "Process crashed" — Lesson 012 hand-off note. Don't run `gradlew` while Android Studio is syncing the same project — Gradle build-lock deadlock.)
6. **Run the app on an emulator, launch it, capture a screenshot as proof.** Confirm via a visible marker that the running build is current (e.g. the `30s` shutter badge) so you're not debugging a stale APK. Manually walk: record → ring/chip update → **press back mid-record** (WARNING-2 behaves per D1) → tap the gallery button (WARNING-3 still opens gallery) → preview (Loopify stays the untouched slice-02 placeholder).
7. **Honest coverage:** state what could NOT be auto-verified (e.g. the on-Fold `ERROR_SOURCE_INACTIVE` path stays manual; REC-1's recomposition win if you skipped the counter test) and give a short manual-QA checklist. Attach the screenshot to the PR.

## Phase 4 — Commit, push, respond, re-review

- Commit on `feature/boomerang-slice-01-variable-length` with focused messages (one logical fix per commit is fine). End commit messages with the project's `Co-Authored-By` trailer per `CLAUDE.md`. Push to the PR branch.
- Post a **PR Review Response — Fixes Applied** comment using the template in `docs/prompts/PR-FEEDBACK-RESOLUTION.md` Phase 4: per finding, the action taken, `file:line`, the Google doc you verified against, the **test that now covers it**, and status RESOLVED / ACKNOWLEDGED / DISPUTED. Mark the Loopify REC as ACKNOWLEDGED (intentional slice-02 placeholder). Note any D1/D2 assumptions made without Steven.
- Then run the **`pr-reviewer` skill** against PR #19 again (its 5 phases) for a fresh report. Goal: **zero FAILs** and the prior WARNINGs cleared. Repeat until clean.

## Behavioral rules

- **Web-search before every API/standard claim** — especially `Recording.start()` throwables (REC-3), `BackHandler` dispatcher resolution (WARNING-2), and the 48dp rule (WARNING-3).
- **Respect the Decision Log + Lesson 012 hand-off** — WARNING-1 *is* Decision Log #1; the Loopify button is an intentional slice-02 placeholder, leave it alone.
- **Every behavior change ships with a test** — that's the point of this prompt. If a fix genuinely can't be unit/instrumented-tested (e.g. REC-1's recomposition count), say so explicitly and cover it with a manual-QA line rather than a flaky assert.
- **Don't reopen the Lesson 012 bug** — keep exactly one `CameraScreen(` call site reachable during capture; never remount a camera-bound composable mid-recording.
- **If any fix grows past its slice** (e.g. `Processing` UI balloons into real slice-02 work), stop and flag it — these are review-cleanup fixes, not new features.
