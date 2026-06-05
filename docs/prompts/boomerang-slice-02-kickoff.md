# Boomerang Slice 02 — Kickoff Prompt for a Fresh Claude Code Session

Copy everything below the line into a fresh Claude Code session with the OpenLoop folder mounted. This kickoff is specific to **slice 02 (Auto-route to Trim screen + default-render Save)**. Assumes slice 01 has shipped and is merged to `main`.

---

## Session Prompt — Implement Boomerang Slice 02

You are working on **OpenLoop** — an open-source Android camera app (Kotlin/Jetpack Compose) for creating speed-controlled video loops ("Boomerangs"). Repo: `stozo04/OpenLoop`. Owner: Steven Gates (@stozo04). Apache 2.0.

The app currently captures variable-length clips up to 30 s (slice 01 shipped). There is still no boomerang generation. This slice is where boomerangs *start existing* — but with no editor tabs yet. It is the largest, riskiest slice in the rollout: it introduces the new `VideoReverser` class (hand-rolled two-pass MediaCodec), the new `VideoProcessor` class (Media3 Composition), the new `Trim` screen, the per-UUID scratch file model, and the `boomerangs/` directory + `RecordedVideo.kind`. Plan for it.

## Critical Rule — Do Not Trust Your Training Data

Your knowledge cutoff could be a year old. **Do not assume** you know the current version of any Google standard, Android API behavior, Jetpack library pattern, testing framework convention, or Play Store requirement. Before making any claim about how something works or what Google recommends, **web-search `developer.android.com` first**. This applies to everything — Media3 Transformer / Composition APIs, MediaCodec/MediaExtractor/MediaMuxer behavior, FileProvider, FileProvider URI behavior, coroutine cancellation semantics. If you catch yourself writing "Google recommends X" without having searched for it in this session, stop and search.

## What slice 02 ships (one-paragraph summary)

After a capture finalizes, the app **auto-routes** (no buttons) onto a new dedicated **Trim screen** with the just-captured clip loaded. The user drags start / end handles to crop, then taps `NEXT`. A default boomerang (`FORWARD_THEN_REVERSE`, 2× speed, 1 rep) is rendered using a new `media/VideoProcessor.kt` that depends on a new `media/VideoReverser.kt` (two-pass MediaCodec reverser per `RESEARCH-reverse-video.md`). Output saves to `filesDir/boomerangs/`, raw promotes from scratch to `filesDir/videos/`, a snackbar "Saved — view in gallery" appears, and the user lands back on the camera. **No tabbed editor exists yet** — slice 03 introduces it.

The full slice spec — UX, technical deltas, testing plan, acceptance criteria — lives in `docs/active/boomerang-rollout/02-auto-route-trim-and-default-save.md`. **That doc is your PRD for this PR.** Treat it as authoritative.

## What slice 01 already shipped (your starting point — build on it, don't regress it)

Slice 01 merged variable-length capture **plus a PR-review hardening pass (PR #19)** that changed structure you will touch. Concretely, already in `main`:

- **`OpenLoopUiState.Processing` already exists** and is routed to an `InfinityLoadingScreen()` **placeholder** with a `// TODO(slice-02)` marker. Slice 02 replaces that placeholder with the real `ProcessingScreen`.
- **Routing was extracted** out of `setContent` into a stateless, **exhaustive `@Composable fun OpenLoopNavHost(...)` with no `else`** (Lesson 014). Add `Trim`/replace `Processing` **there**.
- **`CameraScreen` has a `BackHandler(enabled = isRecording)`** that stops & finalizes mid-record (Lesson 015). The capture states still share **one** `CameraScreenHost` call site (Lesson 012) — `grep "CameraScreen(" MainActivity.kt` must stay at **1**.
- **High-frequency UI state is read via `() -> T` lambdas** (`ShutterButton(progressFraction = { … })`, `RecordingCountdownChip(text = { … })`) so the viewfinder doesn't recompose per tick (Lesson 016). Mirror this in the `Trim` scrubber.
- **`OpenLoopViewModel.startBurstCapture` now checks the `Recording?` return** (null → abort to `ReadyToCapture`) and **catches only `IllegalStateException` + `SecurityException`** (Lesson 013). Your new `VideoProcessor`/`VideoReverser` error handling should follow the same discipline: media APIs report runtime errors via callbacks, not throws.
- **The `LoopingPreview` "Loopify" button is still the intentional placeholder** (deferred to this slice). Slice 02 is where `Finalize(success)` stops going to `LoopingPreview` and instead routes to `Trim(ScratchClip)`.

Lessons **013–017** were written from that PR — they are the distilled, web-verified knowledge behind these changes. Read them; they're cheaper than re-deriving the CameraX throwable set or the predictive-back rules.

## Before Writing Any Code — Read These Files (in this order)

These are your ground truth. They contain decisions, conventions, and constraints that override any assumptions you bring in.

1. **`CLAUDE.md`** — operating instructions, the "Critical Rule" above (full version), architecture snapshot, reference doc pointers.
2. **Every file in `docs/lessons_learned/`** — start with `README.md`, then read **001 through 017** in order. **Non-negotiable** per CLAUDE.md "Required Reading." Especially relevant to slice 02:
   - Lesson 003 (DataStore IOException wrapping) and Lesson 004 (no `Context` on ViewModel methods — your new repository methods are subject to this).
   - Lesson 008 (JVM test patterns: `TemporaryFolder`, single `TestDispatcher`) **and Lesson 017** (instrumented tests can't use `mockk`; write inline fakes — your `VideoReverserTest`/`TrimScreenTest` are instrumented).
   - **Lesson 012 + 014** — the post-capture routing you're rewiring. Routing now lives in the extracted, exhaustive `OpenLoopNavHost` (no `else`); the `ReadyToCapture, Recording` pair shares one `CameraScreenHost` branch — don't split it, and don't add an `else`.
   - **Lesson 013** — Media3 `Transformer`/`MediaCodec` report runtime errors via listener callbacks (`Transformer.Listener.onError`), **not** synchronous throws; catch only documented throwables and check return/failure signals. This is the heart of `VideoReverser`/`VideoProcessor` error handling.
   - **Lesson 015** — `Trim` and `Processing` each need a deliberate `BackHandler` decision (predictive back is default-on at target 36; default behavior is "finish the Activity and lose the work").
   - **Lesson 016** — defer the `Trim` scrubber/preview-position reads behind `() -> T` lambdas; don't read tick-rate state at the `TrimScreen` root next to the ExoPlayer `AndroidView`.
3. **`docs/DEFINITION_OF_DONE.md`** — the verification gate. Baseline → debug + release green → unit + instrumented tests → **run the app + screenshot** → honest coverage statement. Not a nice-to-have; the bar.
4. **`docs/active/boomerang-editor/IMPLEMENTATION.md`** — parent design doc. Required sections: §3 (UX flow), §5 (state machine), §6 (Media3 pipeline), §7 (data layer). The pipeline math (cycle_ms, seam handling) is here.
5. **`docs/active/boomerang-rollout/README.md`** — rollout map. Confirms what slice 02 ships and what it does NOT.
6. **`docs/active/boomerang-rollout/RESEARCH-reverse-video.md`** — the locked decision on how reverse works. §3 (the two-pass algorithm) and §5 (the recommended `VideoReverser` surface) are the spec for the heaviest new class in this slice. Read this before opening any MediaCodec docs.
7. **`docs/active/boomerang-rollout/02-auto-route-trim-and-default-save.md`** — your PRD. Read it end-to-end. Read it twice. The `VideoReverser` and `VideoProcessor` surfaces, the scratch-file model, and the directory layout are all here.
8. **`docs/PRD-mission-control.md`** §3 (Design System Tokens) and §6 (Data Layer / File Structure) — for the Trim screen visual tokens and the existing storage conventions you're extending.
9. **`docs/ANDROID_STANDARDS.md`** — consult before introducing any new pattern. The Composition + EditedMediaItem path is a new pattern; verify it aligns.
10. **`docs/TEST_COVERAGE.md`** — test directory structure, frameworks, coroutine testing conventions.

**Read all of these before touching any code.** No exceptions.

## Phase 1: Cut the branch + capture a green baseline

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
git checkout main
git pull --rebase
git checkout -b feature/boomerang-slice-02-trim-and-default-save
.\gradlew.bat clean assembleDebug --console=plain
```

Confirm `BUILD SUCCESSFUL`, `$LASTEXITCODE -eq 0`, zero `e:` lines. **Do not pipe Gradle through `| tail`** — that gives you the tail's exit code, not Gradle's. A green baseline now means any later failure is unambiguously yours.

## Phase 2: Web-verify the moving pieces (before writing any code)

For each of the following, **search developer.android.com this session** and confirm the API matches your assumption, in the versions pinned in `gradle/libs.versions.toml` (Media3 **1.10.1**):

- `androidx.media3.transformer.Transformer`, `Composition`, `EditedMediaItem` — current builder signatures and listener callbacks for progress / completion / error.
- `androidx.media3.common.MediaItem.ClippingConfiguration` — `setStartPositionMs` / `setEndPositionMs`.
- `androidx.media3.effect.SpeedChangingVideoEffect(2.0f)` — confirmed available in Media3 1.10.x effect package.
- `android.media.MediaCodec.createInputSurface()` + decoder-output-Surface-as-encoder-input-Surface pattern (single-Surface path described in `RESEARCH-reverse-video.md` §3).
- `android.media.MediaExtractor.seekTo(timeUs, mode)` with `SEEK_TO_CLOSEST_SYNC`, and `SAMPLE_FLAG_SYNC` semantics.
- `androidx.core.content.FileProvider` — only the *exists* check; the `Intent.ACTION_SEND` wiring is **slice 06**, not this slice.

If any signature has drifted from what the slice doc / research doc implies, **stop and surface the discrepancy** before coding. Don't silently adapt.

## Phase 3: Implement to the slice spec

Work the technical deltas listed in `docs/active/boomerang-rollout/02-auto-route-trim-and-default-save.md` §"Technical deltas":

- **`OpenLoopUiState.kt`** — add `Trim(source: EditorSource)` state, the `EditorSource` sealed interface with `ScratchClip` (only — `GalleryClip` is slice 07).
- **`OpenLoopViewModel.kt`** — on `Finalize` success, build a `ScratchClip(uuid)` and post `Trim(ScratchClip)` instead of `LoopingPreview`. Add the `editorState: StateFlow<TrimState>` + `updateTrim` / `discardTrim` / `saveBoomerangDefault` mutators. Every repository write wrapped in `try / catch (IOException)` per Lesson 003. No `Context` parameter on any method per Lesson 004.
- **`VideoStorageRepository` (interface + impl)** — add `createScratchCapture`, `promoteScratchToRaw`, `discardScratch`, `allocateBoomerangFile`, `registerBoomerang`. Extend `RecordedVideo` with `kind: VideoKind` and `sourceRawId: Long?`. Infer `kind` from the file's parent directory.
- **`media/VideoReverser.kt`** (new) — implement the two-pass algorithm from `RESEARCH-reverse-video.md` §3 and §5. Cache key `<source-abs-path>_<trimStart>_<trimEnd>`. Decoder + encoder + muxer released in a `finally` block. Cancellable. Strips audio.
- **`media/VideoProcessor.kt`** (new) — single `renderBoomerang(...)` entry, hard-wired to `mode=FORWARD_THEN_REVERSE, speed=2.0f, reps=1`. Pipeline: trim → call `videoReverser.reverse(...)` → build `Composition` of `[trimmed, reversed]` (with seam offset) → `SpeedChangingVideoEffect(2.0f)` → strip audio → Transformer export.
- **`ui/TrimScreen.kt`** (new) — preview top ~75% (ExoPlayer with `ClippingConfiguration`, `repeatMode = REPEAT_MODE_ALL`), trim bar with two drag handles, `NEXT` button bottom. Save checkmark **hidden** in this slice. All Flow collection via `collectAsStateWithLifecycle()` (Lesson 002).
- **`ui/ProcessingScreen.kt`** (new or extended) — centered spinner + "Creating boomerang…" caption.
- **Routing — `OpenLoopNavHost` in `MainActivity.kt` (NOT inline `setContent`).** Slice 01 extracted the state router into a stateless, exhaustive `@Composable fun OpenLoopNavHost(...)` with **no `else`** (Lesson 014), and `OpenLoopUiState.Processing` **already exists** routed to an `InfinityLoadingScreen()` placeholder marked `// TODO(slice-02)`. So: **replace** the `Processing` placeholder branch with the real `ProcessingScreen`, and **add** the new `is OpenLoopUiState.Trim ->` branch — both inside `OpenLoopNavHost`. Keep `ReadyToCapture, Recording` on their single `CameraScreenHost` branch (Lesson 012) and keep the `when` exhaustive (do not add an `else`). Adding `Trim` to the sealed interface will fail to compile until it's routed here — that's the guard working.

**Stay scoped to slice 02.** Do not introduce: the tabbed `BoomerangEditor` screen (slice 03), direction picker (slice 03), speed slider (slice 04), reps tab (slice 05), `Intent.ACTION_SEND` / FileProvider wiring (slice 06), gallery filter / kind badge / `GalleryClip` source (slice 07). If you find yourself reaching for any of them, you've over-shot the slice.

## Phase 4: Test

- Unit tests from §"Testing plan / Unit tests": `OpenLoopViewModelTest` cases for the new transition, `VideoStorageRepositoryImplTest` cases for the new methods. Use `TemporaryFolder` and a single shared `TestDispatcher` per Lesson 008. Do not mock `File`. Do not stack `MainDispatcherRule` with bare `runTest { }`.
- Instrumented `VideoReverserTest` (needs real MediaCodec — JVM unit tests can't exercise it): cache idempotency, intermediate file cleanup on success/failure/cancellation, first-frame-of-reversed ≈ last-frame-of-source (histogram distance, not pixel equality).
- Instrumented `TrimScreenTest`: handles drag, `NEXT` disabled below 400 ms, preview rebinds on trim change.
- End-to-end: record 3 s → trim to 1.5 s → tap `NEXT` → boomerang file exists with expected duration; raw + boomerang both registered; scratch dir cleaned.
- Run both:
  ```powershell
  .\gradlew.bat testDebugUnitTest --console=plain; echo "EXIT=$LASTEXITCODE"
  $env:ANDROID_SERIAL = "<your-emulator-or-device-serial>"
  .\gradlew.bat connectedDebugAndroidTest --console=plain; echo "EXIT=$LASTEXITCODE"
  ```
- Confirm 0 failures, 0 errors via the XML results — not just `BUILD SUCCESSFUL`.

## Phase 5: Run the app for real + screenshot (the DoD gate)

```powershell
.\gradlew.bat clean assembleDebug assembleRelease --console=plain; echo "EXIT=$LASTEXITCODE"
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\<ver>\zipalign.exe" -c -P 16 -v 4 `
  app\build\outputs\apk\release\app-release-unsigned.apk
```

Boot an emulator (or Pixel 10 Pro Fold per `HEY_CLAUDE_ITS_ME.md`), install the debug APK, and walk the slice 02 manual QA checklist:

- Record 5 s → lands on Trim screen, preview loops the full 5 s, duration indicator reads "5.0s".
- Drag handles to a 3 s range → preview loops 3 s, indicator updates to "3.0s".
- Tap `NEXT` → "Creating boomerang…" spinner, then snackbar "Saved — view in gallery". **Record elapsed time** in the PR description. Target ≤ 1.5 s for a 3 s trim on Pixel 10 Pro Fold per `RESEARCH-reverse-video.md` §5 perf estimates.
- Inspect `cacheDir/scratch/reversed/` via `adb shell run-as com.OpenLoop.app ls` — exactly one reversed `.mp4`; no `_intermediate_*.mp4` lingering.
- Save a second boomerang from the *same* trim window → noticeably faster (cache hit; no new reversed file).
- Save a third from a *different* trim window of the same raw → second reversed file appears (cache miss).
- Discard from Trim screen → confirm dialog → "Yes" returns to camera, scratch cleaned.
- Force-stop *during* an in-flight render → on relaunch, no zombie intermediate or partial output files; codecs released (`adb shell dumpsys media.codec | grep com.OpenLoop.app` shows no held instances).

**Capture a screenshot of the Trim screen** (preview + trim bar + NEXT button visible) and attach it to the PR description. Foldable/multi-display screencap gotchas in `HEY_CLAUDE_ITS_ME.md`.

## Phase 6: Open the PR

- Push the branch to `stozo04/OpenLoop`.
- Open the PR. Title: `Slice 02 — Auto-route to Trim screen + default-render Save`.
- Use the acceptance-criteria checklist from `docs/active/boomerang-rollout/02-auto-route-trim-and-default-save.md` §"Acceptance criteria" as the PR description's body. Check each box, including the `VideoReverser` cache + cleanup verifications and the latency measurement.
- Attach the screenshot from Phase 5.
- Final paragraph: **honestly state what you could not verify** — process-kill resume of scratch is explicitly out-of-scope (slice 07), say so.

After the PR is open, you're done with this session.

## Behavioral Rules

- **PRD-first.** Slice doc + research doc are the spec. If tempted to add behavior not in spec, stop and ask. If you think the spec is wrong, **push back** before coding around it (CLAUDE.md "Pushback — required").
- **Web-search before every API claim.**
- **Lessons compliance is checkable by grep.** Before pushing: no `collectAsState(` (Lesson 002), no unwrapped DataStore writes (Lesson 003), no `Context` parameter on ViewModel methods (Lesson 004), every `Color(0x…)` has exactly 8 hex digits (Lesson 001), zipalign passes with real `(OK)` (Lesson 011).
- **`BUILD SUCCESSFUL` is not enough.** Read the verdict, check `$LASTEXITCODE`, scan for `e:` lines. Never trust a piped exit code.
- **Done is not "it compiles."** DoD gate: build + tests + run + screenshot + honest coverage statement.
- **Reversibility protocol applies** for destructive operations.
- **Stay scoped.** Slice 02 only.

## When to stop and come back to Steven

- If the slice doc / research doc and an existing lesson disagree.
- If web verification shows a Media3 / MediaCodec API has changed in a way that breaks the plan.
- If the green baseline in Phase 1 isn't green (someone broke `main`).
- If `zipalign` on the release APK shows `(OK - compressed)` for any `.so` (Lesson 011 regression).
- If `VideoReverser` first-time latency on a 3 s clip is significantly worse than the ≤ 1.5 s budget in `RESEARCH-reverse-video.md` §5 (≥ 3 s warrants investigation before merging).
- If the cache key strategy collides with anything else in `cacheDir/scratch/` from prior slices.
- If the work is meaningfully larger than the slice doc implies (would be > ~800 LOC; this slice's budget is the largest in the rollout but still finite).
