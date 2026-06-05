# Boomerang Slice 03 — Kickoff Prompt for a Fresh Claude Code Session

Copy everything below the line into a fresh Claude Code session with the OpenLoop folder mounted. This kickoff is specific to **slice 03 (Tabbed Editor + Direction tab)**. Assumes slices 01–02 have shipped and are merged to `main`.

---

## Session Prompt — Implement Boomerang Slice 03

You are working on **OpenLoop** — an open-source Android camera app (Kotlin/Jetpack Compose) for creating speed-controlled video loops ("Boomerangs"). Repo: `stozo04/OpenLoop`. Owner: Steven Gates (@stozo04). Apache 2.0.

The app currently captures variable-length clips up to 30 s (slice 01), auto-routes to a Trim screen, and renders default boomerangs (slice 02). This slice is where the user gets **expression** for the first time — they can pick how their boomerang plays. The tabbed editor screen lands in this slice, but with only the Direction tab populated. Speed (slice 04) and Reps (slice 05) layer on later.

## Critical Rule — Do Not Trust Your Training Data

Your knowledge cutoff could be a year old. **Do not assume** you know the current version of any Google standard, Android API behavior, Jetpack library pattern, testing framework convention, or Play Store requirement. Before making any claim about how something works or what Google recommends, **web-search `developer.android.com` first**. This applies to everything — ExoPlayer composition / preview APIs, `ConcatenatingMediaSource2`, Compose `AnimatedContent`, glassmorphic surface conventions. If you catch yourself writing "Google recommends X" without having searched for it in this session, stop and search.

## What slice 03 ships (one-paragraph summary)

Insert a new tabbed **`BoomerangEditorScreen`** between Trim's `NEXT` and the render. The editor's preview (top ~75%) reflects the chosen direction live — for the three reverse-containing modes this means the editor **eagerly** calls the slice-02 `VideoReverser` on entry, showing a "Preparing reverse…" shimmer until the cached file is ready. Bottom of the screen has a single-icon tab bar (Direction `>>`) and the Direction tab content: 4 chips (Forward / Reverse / Forward→Reverse / Reverse→Forward) with `FORWARD_THEN_REVERSE` selected by default. The save checkmark (top-right) now exists. `VideoProcessor.renderBoomerang()` honors the chosen mode; speed remains hard-coded at 2.0× and reps at 1 (those arrive in slices 04 and 05).

The full slice spec lives in `docs/active/boomerang-rollout/03-editor-direction-tab.md`. **That doc is your PRD for this PR.** Treat it as authoritative.

## Before Writing Any Code — Read These Files (in this order)

1. **`CLAUDE.md`** — operating instructions, "Critical Rule" full version, architecture snapshot.
2. **Every file in `docs/lessons_learned/`** — start with `README.md`, then 001–011. **Non-negotiable**. Lessons 001 (Color literals), 002 (Flow collection), and 008 (test patterns) are most relevant.
3. **`docs/DEFINITION_OF_DONE.md`** — the verification gate.
4. **`docs/active/boomerang-editor/IMPLEMENTATION.md`** — parent design doc. §3 (UX flow), §5 (state machine — `BoomerangEditor` state arrives in this slice), §6 (Media3 pipeline).
5. **`docs/active/boomerang-rollout/README.md`** — rollout map.
6. **`docs/active/boomerang-rollout/RESEARCH-reverse-video.md`** — the locked decision on reverse. This slice consumes the same `VideoReverser` that slice 02 introduced; you do **not** build a parallel preview-only reverser.
7. **`docs/active/boomerang-rollout/02-auto-route-trim-and-default-save.md`** — for the `VideoReverser` and `VideoProcessor` surfaces this slice extends.
8. **`docs/active/boomerang-rollout/03-editor-direction-tab.md`** — your PRD. Read it end-to-end. Read it twice. The §"Reverse preview — decision LOCKED" section is the source of truth on the preview-fidelity approach.
9. **`docs/PRD-mission-control.md`** §3 (Design System Tokens) — for the chip gradient, glass surface tokens, tab pill colors.
10. **`docs/ANDROID_STANDARDS.md`** — consult before introducing patterns. The `ConcatenatingMediaSource2` and `AnimatedContent` usages should align.
11. **`docs/TEST_COVERAGE.md`** — test conventions.

**Read all of these before touching any code.**

## Phase 1: Cut the branch + capture a green baseline

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
git checkout main
git pull --rebase
git checkout -b feature/boomerang-slice-03-direction-tab
.\gradlew.bat clean assembleDebug --console=plain
```

Confirm `BUILD SUCCESSFUL`, `$LASTEXITCODE -eq 0`, zero `e:` lines.

## Phase 2: Web-verify the moving pieces

Search developer.android.com this session for, at minimum:

- `androidx.media3.exoplayer.source.ConcatenatingMediaSource2` (or whatever the current Media3 1.10.1 name is) — for chaining `[trimmed, reversed]` in the preview player.
- ExoPlayer preview composition API as of Media3 1.10.1 — alternative to `ConcatenatingMediaSource2` for the preview.
- `androidx.compose.animation.AnimatedContent` — current import path, `with` infix function, fade-cross transition.
- Material 3 chip / segmented-button hit-target guidance (≥ 44 dp for the 4 direction chips).

If anything has drifted from what the slice doc implies, **stop and surface** before coding.

## Phase 3: Implement to the slice spec

Work the technical deltas in `docs/active/boomerang-rollout/03-editor-direction-tab.md` §"Technical deltas":

- **`OpenLoopUiState.kt`** — add `BoomerangEditor(source, trim)` state and `TrimWindow(startMs, endMs)` data class.
- **`OpenLoopViewModel.kt`** — add `editorTabState: StateFlow<EditorTabState>` with `mode`, `reversedFile`, `isReversedFileLoading`, plus mutators `updateMode`, `ensureReversedSegment`, `saveBoomerang`, `discardEditor`. Trim's `NEXT` now posts `BoomerangEditor(source, trim)` (was: `Processing`). **Delete** `saveBoomerangDefault()` from slice 02 — superseded.
- **`media/VideoProcessor.kt`** — honor the `mode` parameter (no longer hard-coded). For `FORWARD` mode, **skip** `videoReverser.reverse(...)` entirely (saves work and disk I/O). Apply seam offset for two-clip compositions.
- **`ui/BoomerangEditorScreen.kt`** (new) — layout per the ASCII mock in the slice doc. Direction chips selected styling per design tokens. Save checkmark wired. Preview eagerly calls `ensureReversedSegment()` on entry (per the locked decision). "Preparing reverse…" shimmer overlay while loading. All Flow collection via `collectAsStateWithLifecycle()` (Lesson 002).
- **`MainActivity.kt`** — route `BoomerangEditor` to `BoomerangEditorScreen(...)`.

**Stay scoped to slice 03.** Do not introduce: speed slider (slice 04), reps tab (slice 05), share intent / FileProvider (slice 06), gallery filter / `GalleryClip` / kind badge (slice 07). The tab bar shows **one** icon only in this slice; do not pre-render Speed or Reps placeholders.

## Phase 4: Test

- Unit tests from §"Testing plan / Unit tests": `OpenLoopViewModelTest` for Trim→Editor transition, `updateMode`, `saveBoomerang` parameter forwarding. `VideoProcessorTest` for each of the 4 modes' Composition layout + duration math + seam frame drop.
- Instrumented `BoomerangEditorScreenTest`: chip selection / deselection, save disabled while reversed-file loading, back returns to Trim with trim preserved.
- End-to-end: capture → trim → editor → pick `REVERSE` → shimmer → resolves → preview plays reversed → save → file exists with reversed playback.
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

Walk the slice 03 manual QA checklist on emulator AND Pixel 10 Pro Fold:

- All 4 directions produce visually correct previews.
- First switch to Reverse on a 3 s clip: shimmer < 2 s on Pixel 10 Pro Fold. **Record the times** for 3 s, 10 s, and 30 s trims — calibration data for the `RESEARCH-reverse-video.md` §5 estimates.
- Second switch to a different reverse-containing direction: instant (cached reversed file reused).
- Verify the editor and `VideoProcessor` share the **same** `VideoReverser` instance: snapshot the cache file `lastModified` across an editor-preview-then-save flow on the same trim — should not change. If a new reversed file appears, DI is wrong and the cache is doubled.
- Save → file in `filesDir/boomerangs/` plays the chosen direction correctly.
- Back from editor preserves trim window on Trim screen.

**Capture screenshots** of all 4 direction chips selected (collage or 4 individual shots) attached to the PR.

## Phase 6: Open the PR

- Push the branch to `stozo04/OpenLoop`.
- Open the PR. Title: `Slice 03 — Tabbed Editor + Direction tab`.
- PR description: acceptance-criteria checklist from `docs/active/boomerang-rollout/03-editor-direction-tab.md` §"Acceptance criteria", each box checked.
- Include the reverse-shimmer latency measurements from Phase 5 in the description.
- Attach the direction-chip screenshots.
- Final paragraph: state what was not verified.

## Behavioral Rules

- **PRD-first.** Slice doc + research doc are the spec. **Push back** before coding around what you think is wrong (CLAUDE.md).
- **Web-search before every API claim.**
- **Lessons compliance is checkable by grep.** No `collectAsState(` (Lesson 002), no unwrapped writes (Lesson 003), no `Context` on VM methods (Lesson 004), 8-digit Color literals (Lesson 001), zipalign `(OK)` not `(OK - compressed)` (Lesson 011).
- **`BUILD SUCCESSFUL` is not enough.** Verdict + exit code + no `e:`. Never trust piped exit codes.
- **Done is the DoD gate.** Build + tests + run + screenshot + honest coverage statement.
- **Stay scoped.** Slice 03 only. One tab icon visible. No Speed / Reps surface.

## When to stop and come back to Steven

- If the slice doc and an existing lesson disagree.
- If the `VideoReverser` from slice 02 is missing functionality slice 03 depends on (e.g., the cache isn't keyed correctly for shared use between preview + render).
- If web verification shows `ConcatenatingMediaSource2` (or the preview composition API) has been renamed or deprecated.
- If the green baseline in Phase 1 isn't green.
- If `zipalign` regresses.
- If preview-fidelity for reverse modes is meaningfully slower than the budget in `RESEARCH-reverse-video.md` §5 (e.g., a 3 s clip shimmering for > 3 s is investigation-worthy).
- If the work is meaningfully larger than the slice doc implies (would be > ~500 LOC).
