# Instrumented-suite triage — 38 failures → root causes → fixes (2026-06-04)

**Context.** Running the full `connectedDebugAndroidTest` suite (82 tests) on the reference
emulator (`Pixel_6` AVD, Android 17 / API 37 16k image, booted `-memory 4096`) as part of
PR #65's Definition-of-Done gate produced **38 failures**. None were caused by the PR branch:
`git diff main..HEAD` over every implicated source/test file was empty — the branch's only
production change is `pass1SampleAction` arithmetic in `VideoReverser.kt`, which appears in no
failing stack. The suite simply had not been run end-to-end on this image since several
latent problems accumulated on `main`. Branch with fixes: `fix/instrumented-editor-fixture`.

**Result after fixes: 82 tests, 0 failures, 1 skipped** (the Samsung-gated
`reverse_pass1SurvivesOnSamsung_afterPostTransformSettle`, correctly skipped on a Pixel image).

| # | Failures | Tests | Root cause | Fix |
|---|---|---|---|---|
| 1 | 35 | `BoomerangEditorScreenTest` (25), `TrimScreenTest` (10) | `MediaMetadataRetriever.setDataSource` throws a **bare `RuntimeException`** on unreadable input; two call sites only caught IAE/ISE | catch `RuntimeException` → placeholder (production fix) |
| 2 | 2 | `PermissionExplanationScreenTest` | test-internal label typo: sets "Grant Permission**s**", asserts "Grant Permission" | align the two `setContent` labels (test fix) |
| 3 | 1 | `BoomerangRenderWorkerTest` | asserts `progress` on a **terminal** `WorkInfo`, which WorkManager wipes | sample progress from in-flight polls (test fix) |
| 4 | 1* | `LoopifyingScreenshotTest` | fixed filename in `/sdcard/Download` orphaned by APK reinstall → `EACCES` | unique per-run filename (test fix) |
| 5 | 2* | `speedTab_showsCurrentSpeedInPill`, `rangePill_reflectsTheTrimmedWindow` | trailing space in UI string / **shadowed duplicate `testTag`** | string + tag hygiene (production fixes) |

\* #4 and the visibility form of #5 only became observable on the second run, after #1 stopped
masking them (a fixture crash in setup fails the test before its real assertions execute).

---

## 1. The big one: 35 tests — `RuntimeException: setDataSource failed: status = 0xFFFFFFEA`

**Why the tests failed.** Both UI test classes deliberately drive their screens with an empty
temp file (`File.createTempFile(...)` — "Lesson 017: plain lambdas + a temp File for the
(non-playing) source"). Composition then runs the screens' best-effort frame decoders:

- `BoomerangEditorScreen.kt` → `extractRepresentativeFrame` (the Looks-tab thumbnail,
  launched from a content-level `produceState` — i.e. on **every** editor composition,
  whatever tab a test exercises, which is why all 25 editor tests failed),
- `TrimFrameExtractor.extractTrimFilmstripFrames` (the Trim filmstrip).

`MediaMetadataRetriever._setDataSource` (native) rejects the empty file with **`RuntimeException:
setDataSource failed: status = 0xFFFFFFEA`** (−22, `EINVAL`). Both functions were written as
best-effort ("returns null on a decode failure") but caught only `IllegalArgumentException` and
`IllegalStateException` — the bare `RuntimeException` sailed through, killed the `produceState`
coroutine, and crashed the test. The codebase already knew this API quirk — `VideoImporter.kt`
and `VideoStorageRepositoryImpl.kt` both catch `RuntimeException` with the comment
"MediaMetadataRetriever surfaces decode failures as bare RuntimeExceptions" — and
`TrimFrameExtractor` itself caught it around `getFrameAtTime`, three lines below the
`setDataSource` call it didn't protect. The two fixed call sites were the only stragglers.

**Why this matters beyond tests:** this is a **production crash path**. A source file that
becomes unreadable behind the Trim/Editor screens (truncated scratch, cleared cache, bad
import) would have crashed the app instead of degrading to the documented placeholder UI. The
tests were doing exactly their job.

**The fix and why it works.** Append `catch (e: RuntimeException)` (returning the same
null/placeholder results) to both functions, mirroring the house pattern. The functions'
contract is explicitly best-effort; the catch converts the undocumented throw into the
already-designed degraded path (glass placeholder chips / empty filmstrip slots). The fix is
ordered after the existing IAE/ISE catches, so behavior for those documented throws is
unchanged. Verified: the 35 failures drop to 0 with no production-behavior change for valid
files (the full pixel-sweep E2E flow still passes on real video).

## 2. `PermissionExplanationScreenTest` — 2 tests, label typo inside the test

**Why they failed.** `noSecondaryAction_rendersPrimaryOnly` and
`primaryAndSecondaryClicks_invokeTheirCallbacks` pass `primaryActionLabel = "Grant Permissions"`
(plural) into the composable under test, then assert/click `onNodeWithText("Grant Permission")`
(singular). `onNodeWithText` is an exact matcher — no node carries the singular text, so the
assert fails ("is not displayed") and the click fails ("Failed to inject touch input"). The
test's other two methods already use the singular label (matching the production copy in
`MainActivity.kt:568`) — a label rename was applied to half the file.

**The fix and why it works.** Change the two `setContent` labels to the singular
"Grant Permission". Setter and assertion now refer to the same string, and the whole file is
consistent with production copy. (The component under test renders whatever label it is given,
so this is purely test-internal consistency.)

## 3. `BoomerangRenderWorkerTest` — progress asserted on a terminal `WorkInfo`

**Why it failed.** `enqueueTinyForwardClip_succeedsAndDiscardsScratch` waited for the worker to
finish, then read `finished.progress`. Per the official docs (developer.android.com, "Observe
intermediate worker progress"): *"Progress information can only be observed and updated while
the ListenableWorker is running"* — WorkManager wipes progress at a terminal state. So the
terminal read returns `Data.EMPTY` → `-1` → assert fails. Any past pass of this assertion was a
race against WorkManager's internal progress cleanup, i.e. the test was flaky by design.

**The fix and why it works.** `awaitTerminalWorkInfo` gained an `onInFlightPoll` callback; the
test records the maximum `PROGRESS_PERCENT` observed across polls **while the worker is
running**, and asserts on that. This matches the documented visibility window of the API. It is
reliable in practice because `BoomerangRenderWorker` publishes 0% immediately on start and
joins its progress publisher before returning success, and the poll interval was tightened
500 ms → 250 ms so even a fast tiny-clip render is sampled mid-run.

## 4. `LoopifyingScreenshotTest` — orphaned file in shared Downloads

**Why it failed (second run only).** The test writes its PR-proof screenshot to a **fixed**
filename in public `Download/`. Run 1 creates the file and passes. AGP then uninstalls the test
APK, orphaning the file's ownership. Run 2's fresh install cannot open another owner's file
under scoped storage → `EACCES` (the granted `WRITE_EXTERNAL_STORAGE` is a no-op at this API
level). The failure is invisible until you run the suite twice on one emulator — which the DoD
re-runs did.

**The fix and why it works.** Unique per-run filename
(`openloop_loopifying-processing-42pct_<epochMs>.png`): each install only ever creates files it
owns, so no collision with orphans is possible. The screenshot still lands in Downloads
(surviving APK teardown, which is the point of the test); the KDoc documents pulling the newest.

## 5. Two "pill" nodes unfindable — string and semantics hygiene

These surfaced only after #1 unmasked them (their setups previously crashed first).

**5a. `speedTab_showsCurrentSpeedInPill`.** Production rendered the label as
`Text("Current speed ")` — trailing space as ad-hoc spacing between the label and the value.
The test's exact matcher `onNodeWithText("Current speed")` therefore found nothing.
**Fix:** drop the trailing space and express the 4 dp gap as `padding(end = 4.dp)`. The node's
text now equals the asserted string; spacing is unchanged visually and the accessibility text
no longer carries a stray space.

**5b. `rangePill_reflectsTheTrimmedWindow`.** The pill had **two `testTag`s on one node**: the
call site passed `Modifier.testTag("trim_range_pill")` and `TrimRangePill` appended
`.testTag("trim_range_label")` to that same modifier chain. For duplicate semantics keys, the
first value in the chain wins — the node's effective tag was `trim_range_pill`, and the test's
`trim_range_label` lookup found nothing. `trim_range_pill` is referenced by zero tests.
**Fix:** remove the unused caller-side tag (with a comment explaining the shadowing) so the
component's own documented tag is the node's tag again.

---

## Verification

- Full `connectedDebugAndroidTest` on the Pixel 6 reference image: **82 tests / 0 failures /
  1 skipped** (Samsung-gated, expected) — see XML under `app/build/outputs/androidTest-results/`.
- JVM suite `:app:testDebugUnitTest`: 191 / 0 (unchanged — none of these fixes touch JVM-tested code).
- `:app:lintDebug`: zero new errors.
- Production-behavior sanity: the scripted pixel-sweep flow (import → … → save → quality gate)
  passes on the same build, confirming the two production fixes (RuntimeException catches, speed
  pill text, removed duplicate tag) changed no real-video behavior.

## Take-aways (candidate lessons)

1. **A "best-effort" media helper must catch what the API actually throws, not what it
   documents.** `MediaMetadataRetriever` throws bare `RuntimeException`s from native; the repo
   knew this in three places and missed it in two. Grep candidate:
   every `MediaMetadataRetriever` use must have a `RuntimeException` catch.
2. **WorkManager progress is only observable while RUNNING** — asserting it on a terminal
   `WorkInfo` is a race you sometimes win. Observe in-flight.
3. **One node, one `testTag`** — a second tag earlier in the modifier chain silently shadows
   the inner one.
4. **Don't encode layout spacing in string literals** — trailing spaces break exact-match
   test lookups and TalkBack phrasing.
5. **Fixed filenames in shared storage + APK reinstall cycles = EACCES on the orphan.**
   Unique names per run, or write app-owned storage.
