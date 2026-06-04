# Reverse output validation + friendly save failure with debug report

> **Status:** Spec — ready for implementation (amended 2026-06-04 after full S23 logcat forensics)  
> **Owner:** Steven Gates  
> **Branch (suggested):** `fix/reverse-output-validation`  
> **Root-cause evidence:** [`RESEARCH.md`](./RESEARCH.md) — line-level proof of the failure chain (read before implementing)  
> **Related:** [`../editor-codec-churn/IMPLEMENTATION.md`](../editor-codec-churn/IMPLEMENTATION.md) (codec slot pressure), [`../crashlytics-reverse-preview/HANDOFF.md`](../crashlytics-reverse-preview/HANDOFF.md) (PR #62 Samsung throws), [`../boomerang-rollout/RESEARCH-reverse-video.md`](../boomerang-rollout/RESEARCH-reverse-video.md) (why reverse is hand-rolled)

---

## 0. Agent bootstrap (read this first)

You are implementing **loud failure** when the reverse pipeline produces an invalid file, plus **user-friendly messaging** and **Send debug report** on both preview and save paths, plus a **gated decoder-fallback retry** (§5.8) that makes the S23 Save actually succeed. You are **not** implementing the GL pass-2 rewrite in this slice — that remains a follow-up ([`../editor-codec-churn/IMPLEMENTATION.md`](../editor-codec-churn/IMPLEMENTATION.md)). Read [`RESEARCH.md`](./RESEARCH.md) first — it is the line-level evidence for every claim in this spec.

### Read these files before editing code

| File | Why |
|------|-----|
| [`app/src/main/java/io/github/stozo04/openloop/media/VideoReverser.kt`](../../../app/src/main/java/io/github/stozo04/openloop/media/VideoReverser.kt) | Pass 1/2 pipeline; `emitted` counter in pass 2; cache hit gate |
| [`app/src/main/java/io/github/stozo04/openloop/media/ReverseDiagnostics.kt`](../../../app/src/main/java/io/github/stozo04/openloop/media/ReverseDiagnostics.kt) | `buildReverseSupportReport`, `probeReversePreviewDiagnostics` |
| [`app/src/main/java/io/github/stozo04/openloop/diagnostics/ReverseCrashlytics.kt`](../../../app/src/main/java/io/github/stozo04/openloop/diagnostics/ReverseCrashlytics.kt) | Non-fatal Crashlytics + share report builder |
| [`app/src/main/java/io/github/stozo04/openloop/ui/OpenLoopViewModel.kt`](../../../app/src/main/java/io/github/stozo04/openloop/ui/OpenLoopViewModel.kt) | `ensureReversedSegment`, `markReversePreviewFailed`, `saveBoomerang`, `failBackToEditor` |
| [`app/src/main/java/io/github/stozo04/openloop/ui/BoomerangEditorScreen.kt`](../../../app/src/main/java/io/github/stozo04/openloop/ui/BoomerangEditorScreen.kt) | Reverse failure overlay + **SEND DEBUG INFO** button (copy this pattern) |
| [`app/src/main/java/io/github/stozo04/openloop/MainActivity.kt`](../../../app/src/main/java/io/github/stozo04/openloop/MainActivity.kt) | Snackbar handler for `ReversePreviewFallbackForward` with **Send report** action |
| [`app/src/main/res/values/strings.xml`](../../../app/src/main/res/values/strings.xml) | Existing user-facing strings |
| [`app/src/main/java/io/github/stozo04/openloop/work/BoomerangRenderWorker.kt`](../../../app/src/main/java/io/github/stozo04/openloop/work/BoomerangRenderWorker.kt) | Save/render worker — failures become `Result.failure()` |
| [`app/src/test/java/io/github/stozo04/openloop/ui/OpenLoopViewModelTest.kt`](../../../app/src/test/java/io/github/stozo04/openloop/ui/OpenLoopViewModelTest.kt) | Preview timeout/failure tests — extend for empty-reverse |

### Repro reference (Samsung S23 Save bug)

Log file: user-provided `openloop-reverse.txt` (SM-S911U, Android 13). **Full forensic chain with line
numbers: [`RESEARCH.md`](./RESEARCH.md).** Summary:

| Step | Log evidence | Meaning |
|------|--------------|---------|
| Pass 1 | `reverse pass1: 1280x720, encoded=120` | Stream transcode OK |
| Pass 2 | `MPEG4Writer: encoded 0 frames`, file ~598 bytes | Empty moov-only MP4 — encoder's input surface never received a single frame (`GraphicBufferSource` never logs `got buffer`; decoder output-format registration never completes) |
| Save | `ExportException: The asset loader has no audio or video track to output` | Symptom, not root cause |

**Root defect:** `VideoReverser.reverseAllKeyframeVideo()` completes without throwing when `emitted == 0`. Cache treats any `length() > 0` as valid. Preview treats return as success. Save feeds bad file to Media3 Transformer.

**Also confirmed (RESEARCH.md §2/§6):**

- The **preview** reverse on the S23 failed identically (118-frame pass 1, 0-frame pass 2) — the user's
  preview "worked" only because the empty reverse clip plays as ~0-duration. 2-for-2 deterministic.
- `ExportException` **escapes the worker's catch blocks** (it extends `Exception`, not
  `IOException`/`RuntimeException`) — see §5.5, now a required fix.
- The failing codec pairing is the Samsung carve-out's forced **software decoder**
  (`c2.android.avc.decoder`) feeding the QTI HW encoder surface; the platform-default
  `c2.qti.avc.decoder` demonstrably works on this device. HEAD's selection logic reproduces the pairing
  (traced in RESEARCH.md §5) — see §5.8 for the gated fallback.
- The S23 was running a build **older than HEAD's `OpenLoopReverse` instrumentation** — rebuild from
  HEAD before re-capturing logs.

---

## 1. Problem statement

OpenLoop’s boomerang flow depends on a **cached reversed MP4** produced by [`VideoReverser`](../../../app/src/main/java/io/github/stozo04/openloop/media/VideoReverser.kt). On some devices (notably **Samsung Galaxy S23 / Snapdragon**), pass 2 can finish with **zero encoded frames** while still writing a tiny (~598 byte) file. Today:

1. **No exception** is thrown → preview thinks reverse succeeded.
2. **Cache poisons** → subsequent attempts may hit bad cache.
3. **Save reaches Transformer** with an empty reversed clip → cryptic `ExportException`.
4. **Save failure** shows only `Couldn't save loop. Try again.` with **no debug report** (unlike preview failure).

Users need:

- Plain-language explanation (not codec jargon).
- **Try again** without losing trim/direction.
- **Send debug report** (same capability as preview reverse failure).
- Automatic Crashlytics non-fatal for aggregate triage (already exists for preview — extend to save/validation).

---

## 2. Goals

| # | Goal |
|---|------|
| G1 | **Never treat an invalid reversed file as success** (preview or save). |
| G2 | **Never cache** a reversed file that fails validation. |
| G3 | **User-friendly copy** on preview and save failure; no stack traces in UI. |
| G4 | **Send debug report** on save failure — reuse the preview share-sheet pattern. |
| G5 | **Crashlytics non-fatal** with the same custom keys as preview failures. |
| G6 | **Zero regression** on Pixel emulators where pass 2 already works (validation is additive). |

---

## 3. Non-goals (this slice)

- GL-bridge pass 2 (definitive Samsung surface fix) — separate doc/PR.
- ~~Software encoder retry for pass 2~~ **Corrected by RESEARCH.md §5:** the S23 evidence points at the
  forced software **decoder** pairing, not the encoder. A *decoder*-fallback retry is now **in scope
  but gated** — see §5.8. (Validation alone leaves S23 users with a loud failure and still no working
  Save.)
- Serialized codec ownership (editor-codec-churn item A).
- Changing reverse algorithm or replacing with FFmpeg.
- New analytics events beyond existing Crashlytics non-fatals.

---

## 4. User-facing copy spec

All strings live in [`strings.xml`](../../../app/src/main/res/values/strings.xml). **Do not hardcode** in Composables except via `stringResource`.

### Tone rules

- Second person, short sentences, **no** MediaCodec / Transformer / pass-2 terminology.
- Offer **Try again** (primary) and **Send debug report** (secondary).
- Mention automatic report upload once: *“Reopening the app also sends an automatic report if diagnostics are enabled.”*

### Preview reverse failure (existing overlay — align copy)

Already implemented in [`BoomerangEditorScreen.kt`](../../../app/src/main/java/io/github/stozo04/openloop/ui/BoomerangEditorScreen.kt) (~lines 480–533). **Keep structure;** optionally move hardcoded strings to `strings.xml` for consistency.

| Element | Current text | Target (strings.xml) |
|---------|--------------|----------------------|
| Title | `Couldn't loop that clip` | `reverse_failure_title` |
| Body | `Try again, or pick the Forward direction.` | `reverse_failure_body` |
| Hint | `Send debug info below, or reopen…` | `reverse_failure_crashlytics_hint` |
| Primary CTA | `TRY AGAIN` | `reverse_failure_retry` |
| Secondary CTA | `SEND DEBUG INFO` | `reverse_failure_send_debug_report` |

**Unify label:** Use **“Send debug report”** everywhere (overlay + snackbar action). Replace `snackbar_reverse_preview_report_action` value `"Send report"` → `"Send debug report"`.

### Preview snackbar (forward fallback)

When validation fails during preview, keep existing forward fallback via [`markReversePreviewFailed`](../../../app/src/main/java/io/github/stozo04/openloop/ui/OpenLoopViewModel.kt) + [`BoomerangEvent.ReversePreviewFallbackForward`](../../../app/src/main/java/io/github/stozo04/openloop/ui/OpenLoopViewModel.kt).

| String key | Text |
|------------|------|
| `snackbar_reverse_preview_forward` | (keep) *We couldn't build the reverse for this clip, so it's playing forward only. You can retry from the Loop tab.* |
| `snackbar_reverse_preview_report_action` | **Send debug report** |

Handler: [`MainActivity.kt`](../../../app/src/main/java/io/github/stozo04/openloop/MainActivity.kt) ~lines 263–280 — **copy this Intent.ACTION_SEND block** for save failure.

### Save failure (new / extended)

Replace bare [`BoomerangEvent.Failed`](../../../app/src/main/java/io/github/stozo04/openloop/ui/OpenLoopViewModel.kt) snackbar with report-capable event.

| String key | Text |
|------------|------|
| `snackbar_save_failed` | *Couldn't save your loop. Try again, or send a debug report so we can fix device issues.* |
| `snackbar_save_failed_report_action` | **Send debug report** |

Snackbar: `duration = SnackbarDuration.Long`, `actionLabel = …` when report non-null (same as preview).

---

## 5. Technical design

### 5.1 Validation primitive (single source of truth)

Add **`ReverseOutputValidator`** (suggested path: `app/src/main/java/io/github/stozo04/openloop/media/ReverseOutputValidator.kt`).

```kotlin
/** Result of validating a candidate reversed MP4. */
data class ReverseOutputValidation(
    val valid: Boolean,
    val reason: String,           // machine-readable, e.g. "zero_frames_pass2"
    val fileBytes: Long,
    val videoTrackCount: Int,
    val estimatedFrameCount: Int, // from sample count or duration × fps — document choice
)

fun validateReversedOutput(file: File): ReverseOutputValidation
```

**Validation rules (all must pass):**

| Check | Rationale |
|-------|-----------|
| `file.exists()` | Missing file → invalid |
| `MediaExtractor` finds ≥1 `video/` track | Transformer requires a video track |
| ≥1 readable sample on that track (iteration capped at 500) | Catches zero-frame muxer success — the S23 shells have a parseable moov with zero samples |
| File parses at all | Unreadable → invalid, never a crash |

> **Size-gate correction (2026-06-04, found by the instrumented test):** the originally suggested
> `MIN_REVERSED_BYTES = 4096` validity gate was **dropped** — a Pixel 10 Pro Fold encoded a
> legitimate 12-frame 320x240 clip to under 4 KiB, so size false-positives on small real clips
> while the zero-sample probe already rejects the 598 B shells. `fileBytes` is reported for
> diagnostics only. Validity is content-based.

**Do not** use `length() > 0` alone anywhere for cache or success.

Expose as `internal` + unit tests in `ReverseOutputValidatorTest.kt` (pure JVM, temp files or fixtures).

### 5.2 VideoReverser changes

File: [`VideoReverser.kt`](../../../app/src/main/java/io/github/stozo04/openloop/media/VideoReverser.kt)

1. **Pass 2 — throw on zero output**

   At end of `reverseAllKeyframeVideo`, after loop completes:

   ```kotlin
   require(emitted > 0) {
       "Pass 2 encoded 0 frames (intermediate=${source.name}, dest=${dest.name})"
   }
   ```

   Also log: `ReversePreviewLog.e("reverse.pass2.empty", "emitted=0 frames=$total …")`

2. **Cache hit — validate before return**

   Replace:

   ```kotlin
   if (output.exists() && output.length() > 0L) { … return }
   ```

   With:

   ```kotlin
   if (output.exists()) {
       val v = validateReversedOutput(output)
       if (v.valid) { cache hit; return }
       output.delete() // stale/poisoned cache
   }
   ```

3. **After pass 2 success — validate before return**

   ```kotlin
   val validation = validateReversedOutput(output)
   if (!validation.valid) {
       output.delete()
       throw IOException("Invalid reversed output: ${validation.reason}")
   }
   ```

4. **Include pass metadata in exception message** (for logs only): `pass1Encoder`, `emitted`, `outBytes` — already partially logged.

5. **Custom exception (optional but helpful):** `ReverseOutputInvalidException(reason, validation)` — lets ViewModel map to friendly outcome strings without parsing `IOException` messages.

### 5.3 Diagnostics + debug report extension

File: [`ReverseDiagnostics.kt`](../../../app/src/main/java/io/github/stozo04/openloop/media/ReverseDiagnostics.kt)

Extend `buildReverseSupportReport` **or** add `buildBoomerangSupportReport` with optional fields:

| Field | Source |
|-------|--------|
| `phase` | `"preview_reverse"` \| `"save_render"` |
| `outcome` | Human + machine reason |
| `validation` | `ReverseOutputValidation.reason`, bytes, frame estimate |
| `pass1_encoder` | `VideoReverser.lastSurfaceEncoderName` (already `@Volatile`) |
| `mode` | `BoomerangMode` name at failure |
| Existing | app version, device, trim window, source probe |

Footer line (update):

```
What you saw: [preview stuck / couldn't save / loop wouldn't play]
How to help: send this entire message to the developer.
Crashlytics: a non-fatal report is queued on next app open (if Firebase is configured).
```

File: [`ReverseCrashlytics.kt`](../../../app/src/main/java/io/github/stozo04/openloop/diagnostics/ReverseCrashlytics.kt)

- Rename or generalize: `reportPreviewFailure` → keep; add **`reportReverseFailure(phase, …)`** or **`reportSaveFailure`** calling same `customKeys` + new keys:
  - `reverse_phase` = `preview` | `save`
  - `reverse_validation_reason`
  - `reversed_file_bytes`
  - `pass1_encoder`

Use **`recordException(nonFatal)`** — never crash the app for user-visible failures.

### 5.4 Preview path (ViewModel)

File: [`OpenLoopViewModel.kt`](../../../app/src/main/java/io/github/stozo04/openloop/ui/OpenLoopViewModel.kt)

`ensureReversedSegment` already calls `markReversePreviewFailed` on exception/timeout. **Ensure** `ReverseOutputInvalidException` / `IOException` from new validation routes to the same path:

```kotlin
markReversePreviewFailed(
    trim,
    outcome = "Reverse file invalid (${validation.reason})",
    cause = e,
)
```

Outcome strings for report (user-visible summary line inside report, not snackbar):

| Failure | `outcome` field |
|---------|-----------------|
| Pass 2 zero frames | `Reverse export produced no video (device codec issue)` |
| Invalid cache deleted | `Cached reverse file was invalid; retrying` |
| Timeout (existing) | `Timed out after 120s` |

No change to forward-fallback behavior: mode → `FORWARD`, `reverseSupportReport` populated, snackbar with **Send debug report**.

### 5.5 Save path (ViewModel + Worker)

**Worker** ([`BoomerangRenderWorker.kt`](../../../app/src/main/java/io/github/stozo04/openloop/work/BoomerangRenderWorker.kt)): no UI changes, but **one required fix** — the S23 log proves `androidx.media3.transformer.ExportException` currently **escapes** `doWork`'s catch list (`CancellationException` / `IOException` / `RuntimeException`): `ExportException` extends `Exception` directly, so the render failure bypassed `deletePartialOutput` and surfaced via WorkManager's generic *"failed because it threw an exception/error"* path (RESEARCH.md §6.2). Add an explicit catch:

```kotlin
} catch (e: ExportException) {            // before the RuntimeException branch
    progressPublisher.cancel()
    Log.e(TAG, "Boomerang render failed (export)", e)
    deletePartialOutput(parsed.outputFile)
    Result.failure()
}
```

(Per Lesson 013, keep catches narrow and documented — `ExportException` is the documented async failure type delivered by `Transformer.Listener.onError`, rethrown out of `runTransformer`.)

**ViewModel** — extend failure event:

```kotlin
// Replace object Failed OR extend:
data class SaveFailed(val supportReport: String?) : BoomerangEvent
```

Update [`failBackToEditor`](../../../app/src/main/java/io/github/stozo04/openloop/ui/OpenLoopViewModel.kt):

1. Build report via `ReverseCrashlytics.supportReportForShare(…)` with `phase = save`, capture `mode`, trim, source file, outcome from worker/render exception.
2. Call `ReverseCrashlytics.reportSaveFailure(…)`.
3. Emit `BoomerangEvent.SaveFailed(supportReport)`.
4. Route to editor (unchanged).

**Pre-Transformer guard** in [`Media3VideoProcessor.renderBoomerang`](../../../app/src/main/java/io/github/stozo04/openloop/media/VideoProcessor.kt):

After `reverser.reverse(…)` when `needsReverse`:

```kotlin
val v = validateReversedOutput(reversedFile)
if (!v.valid) throw IOException("Reversed clip invalid: ${v.reason}")
```

Defense in depth if cache/worker paths change later.

### 5.6 MainActivity snackbar

File: [`MainActivity.kt`](../../../app/src/main/java/io/github/stozo04/openloop/MainActivity.kt)

Replace:

```kotlin
BoomerangEvent.Failed -> snackbarHostState.showSnackbar(message = saveFailedMessage)
```

With handler mirroring `ReversePreviewFallbackForward`:

```kotlin
is BoomerangEvent.SaveFailed -> {
    val report = event.supportReport
    val result = snackbarHostState.showSnackbar(
        message = saveFailedMessage,
        actionLabel = if (!report.isNullOrBlank()) saveFailedReportAction else null,
        duration = SnackbarDuration.Long,
    )
    if (result == SnackbarResult.ActionPerformed && !report.isNullOrBlank()) {
        // Same Intent.ACTION_SEND as preview — extract to shared helper (see 5.7)
    }
}
```

Remove or deprecate bare `BoomerangEvent.Failed` if fully replaced; update all `when` branches and tests.

### 5.7 DRY — share debug report helper

Both [`BoomerangEditorScreen`](../../../app/src/main/java/io/github/stozo04/openloop/ui/BoomerangEditorScreen.kt) and [`MainActivity`](../../../app/src/main/java/io/github/stozo04/openloop/MainActivity.kt) duplicate `Intent.ACTION_SEND`. Extract:

```kotlin
// e.g. app/src/main/java/io/github/stozo04/openloop/diagnostics/DebugReportShare.kt
fun Context.shareDebugReport(text: String, subject: String, chooserTitle: String)
```

Use from overlay button, preview snackbar action, save snackbar action.

### 5.8 Software-ENCODER fallback retry on zero-frame output (REVISED + verified on-device 2026-06-04)

File: [`VideoReverser.kt`](../../../app/src/main/java/io/github/stozo04/openloop/media/VideoReverser.kt)

> **Revision:** the original decoder-fallback hypothesis was **falsified on the S23 itself**
> (RESEARCH.md §7d): the platform HW decoder feeding the HW encoder surface dies with
> `CodecException 0xe` at `queueInputBuffer`. The pairing that works is keeping the carve-out's SW
> decoder and flipping the **encoder** to software (`c2.android.avc.encoder`).

Shipped behavior:

1. A `ReverseOutputInvalidException` (zero-frame pass, 5.2) makes the retry loop run one more
   attempt with `preferSoftwareEncoder = true` — `openSurfaceAvcEncoder` fronts the installed SW
   AVC encoders (`c2.android.avc.encoder`, `c2.google.avc.encoder`, `OMX.google.h264.encoder`)
   ahead of the normal try-order. Decoder selection is unchanged.
2. A process-scoped sticky (`zeroFrameEncoderWedgeSticky`) starts subsequent reverses directly on
   the software encoder, so a wedged device pays the doomed HW attempt + retry delay only once per
   process. Not persisted (reboot/codec updates may fix the device).
3. Telemetry: `reverse.zero_frame_retry | preferSoftwareEncoder=true`,
   `encoder.software_fallback_order`, and `reverse.complete | attempts=N`.
4. Never loops past `SAMSUNG_REVERSE_PASS_MAX_ATTEMPTS`; a second zero-frame run fails loudly
   through the validation path (5.2) — the loud-failure floor holds.

**Verification (gate cleared):** `ReverseOutputValidatorAndroidTest` + `VideoReverserTest` =
**11/11 on the S23 (SM-S911U / API 33, Samsung RTL)** — previously 6/11 failing with the
zero-frame exception — including cold-cache regenerate, warm-cache hit, poisoned-cache delete, and
luma-ramp reversal correctness. JVM 189/189. **A10 cleared manually (2026-06-04 15:15, RTL):**
cold-start capture → trim → loop preview → Save on the S23 UI succeeded end-to-end — preview reverse
hit the wedge and recovered (`attempts=2`), the save-path reverse used the sticky (`attempts=1`),
`BoomerangRenderWorker` returned SUCCESS, zero `ExportException` (log: `openloop-s23-savetest.txt`).
Exynos behavior is still an open question (RESEARCH.md §8) — do not widen the carve-out gating
(`Build.SOC_MANUFACTURER`) without an Exynos capture.

---

## 6. Implementation steps (ordered)

| Step | Task | Files |
|------|------|-------|
| 1 | Add `ReverseOutputValidator` + unit tests | `ReverseOutputValidator.kt`, `ReverseOutputValidatorTest.kt` |
| 2 | Wire validation into `VideoReverser` (pass 2 require, cache, post-write) | `VideoReverser.kt` |
| 3 | Extend diagnostics report + Crashlytics keys for save/validation | `ReverseDiagnostics.kt`, `ReverseCrashlytics.kt` |
| 4 | Add `strings.xml` entries; unify **Send debug report** label | `strings.xml` |
| 5 | Extract `shareDebugReport()` helper | new `DebugReportShare.kt`; refactor Editor + MainActivity |
| 6 | Map validation errors in `ensureReversedSegment` (should already flow to `markReversePreviewFailed`) | `OpenLoopViewModel.kt` |
| 7 | Pre-Transformer validate in `renderBoomerang` | `VideoProcessor.kt` |
| 8 | Replace `BoomerangEvent.Failed` with `SaveFailed(supportReport)`; update `failBackToEditor` | `OpenLoopViewModel.kt`, `MainActivity.kt` |
| 9 | Move overlay strings to resources (optional polish) | `BoomerangEditorScreen.kt`, `strings.xml` |
| 10 | **Catch `ExportException` in worker** (escapes current catch list — see §5.5) | `BoomerangRenderWorker.kt` |
| 11 | **Decoder-fallback retry on zero frames** (§5.8 — gated on physical-S23 verification) | `VideoReverser.kt` |
| 12 | Update tests (below) | `OpenLoopViewModelTest.kt`, new validator tests |

---

## 7. Testing plan

### Unit tests (required)

| Test | Assert |
|------|--------|
| `validateReversedOutput_emptyFile` | invalid, reason documents zero bytes |
| `validateReversedOutput_moovOnly` | invalid — use minimal fixture or mock extractor if needed |
| `validateReversedOutput_validMinimalMp4` | valid — reuse test asset if present, or generate via MediaMuxer in test |
| `VideoReverser pass2 emitted=0` | throws (mock/delegate test via fake intermediate — may need extractor test double) |
| Cache poison | write 598-byte file at cache path → reverse deletes and re-encodes (integration-style with temp dir) |

### ViewModel tests (extend existing)

File: [`OpenLoopViewModelTest.kt`](../../../app/src/test/java/io/github/stozo04/openloop/ui/OpenLoopViewModelTest.kt)

| Test | Setup | Assert |
|------|-------|--------|
| `reverse invalid output flags forward fallback` | `FakeVideoProcessor` throws or returns invalid file | `mode == FORWARD`, `reverseSupportReport != null` |
| `save failure emits SaveFailed with report` | `FakeBoomerangRenderScheduler` returns `Failure` or fake processor throws on invalid reverse | Event is `SaveFailed`, report non-blank |
| `save failure preserves editor` | same | Still `BoomerangEditor`, trim intact |

Update `FakeVideoProcessor` to simulate `ReverseOutputInvalidException` if needed.

### Manual device matrix

| Device | Scenario | Expected |
|--------|----------|----------|
| Pixel emulator | Capture → Loop → Save | No change (success) |
| Galaxy S23 | **Rebuild from HEAD first** (installed APK predates `OpenLoopReverse` logging — RESEARCH.md §6.3), clear app storage (poisoned cache), then Capture → Loop → Save | **Validation only:** preview shows forward fallback OR save snackbar with **Send debug report**; never a silent empty file. **With §5.8 fallback:** Save succeeds; logcat shows `reverse.zero_frame_retry` + platform decoder name |
| Galaxy S23 | Repeat Save on the same clip (warm cache) | Cache hit only on a validated file; never replays the empty-file failure |
| Any | Force invalid cache (adb push 598B file to cache key path) | App deletes cache, retries or fails loudly with report |

Logcat filters: `VideoReverser`, `ReversePreviewLog`, `MPEG4Writer`, `OpenLoopViewModel`, `BoomerangRenderWorker`.

---

## 8. Acceptance criteria

| # | Criterion |
|---|-----------|
| A1 | Pass 2 with `emitted == 0` **never** returns a file without throwing |
| A2 | Cache never returns a file failing `validateReversedOutput` |
| A3 | Preview validation failure → forward fallback + overlay/snackbar + **Send debug report** (existing UX, now triggered for empty output too) |
| A4 | Save validation/render failure → snackbar with friendly copy + **Send debug report** action |
| A5 | Share sheet report includes phase, validation reason, device, trim, encoder name |
| A6 | Crashlytics non-fatal recorded for preview **and** save validation failures |
| A7 | All unit + ViewModel tests pass; `:app:lintDebug` clean |
| A8 | Pixel 6/8/10 emulator E2E save still passes (no regression) |
| A9 | `ExportException` from the Transformer is caught in `BoomerangRenderWorker` → `deletePartialOutput` runs → `Result.failure()` (not WorkManager's generic thrown-exception path) |
| A10 | *(gated, §5.8)* Galaxy S23: Save succeeds via decoder-fallback retry, verified on device, cold + warm cache |

Ship gate: [`docs/DEFINITION_OF_DONE.md`](../../DEFINITION_OF_DONE.md).

---

## 9. Reference — existing debug report flow (copy this)

### Preview overlay (editor)

```511:532:app/src/main/java/io/github/stozo04/openloop/ui/BoomerangEditorScreen.kt
                        if (!reverseSupportReport.isNullOrBlank()) {
                            ...
                            Text(text = "SEND DEBUG INFO", ...)
                            .clickable {
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, reverseSupportReport)
                                    putExtra(Intent.EXTRA_SUBJECT, "OpenLoop loop debug")
                                }
                                context.startActivity(Intent.createChooser(share, "Send debug info"))
                            }
                        }
```

### Preview snackbar (activity)

```263:280:app/src/main/java/io/github/stozo04/openloop/MainActivity.kt
                            is BoomerangEvent.ReversePreviewFallbackForward -> {
                                val report = event.supportReport
                                val result = snackbarHostState.showSnackbar(
                                    message = reversePreviewForwardMessage,
                                    actionLabel = if (!report.isNullOrBlank()) reversePreviewReportAction else null,
                                    duration = SnackbarDuration.Long,
                                )
                                if (result == SnackbarResult.ActionPerformed && !report.isNullOrBlank()) {
                                    val share = Intent(Intent.ACTION_SEND).apply { ... }
                                    startActivity(Intent.createChooser(share, reversePreviewReportAction))
                                }
                            }
```

### Report builder

```46:70:app/src/main/java/io/github/stozo04/openloop/media/ReverseDiagnostics.kt
internal fun buildReverseSupportReport(...): String = buildString {
    appendLine("OpenLoop — loop preview diagnostic")
    appendLine("Outcome: $outcome")
    ...
}
```

### State fields

```135:137:app/src/main/java/io/github/stozo04/openloop/ui/OpenLoopUiState.kt
    val reverseFailed: Boolean = false,
    /** Plain-text diagnostic for the "Send debug info" action when [reverseFailed] is true. */
    val reverseSupportReport: String? = null,
```

Save failure should **not** require new Compose overlay state unless you want a Loop-tab banner — snackbar + optional editor re-entry is enough for v1.

---

## 10. Follow-up work (explicitly out of scope here)

| Follow-up | Why separate |
|-----------|--------------|
| GL-bridge pass 2 | Fixes Samsung root cause; large change |
| ~~Software encoder retry on pass 2~~ → superseded by §5.8 decoder-fallback (in scope, gated) — RESEARCH.md §5 shows the *decoder* pairing is the defect, not the encoder | Corrected 2026-06-04 |
| Gate the Samsung SW-decoder carve-out on actual silicon (`Build.SOC_MANUFACTURER`, API 31+; fallback heuristic for 26–30) instead of brand | Needs an Exynos capture first (RESEARCH.md §8) — don't fix what we can't verify |
| `BoomerangEvent.Failed` → rich inline editor error on Save | Nice-to-have; snackbar sufficient for v1 |
| Serialize codec ownership | [`editor-codec-churn`](../editor-codec-churn/IMPLEMENTATION.md) item A |

---

## 11. PR checklist (for reviewer)

- [ ] No user-facing string mentions MediaCodec, Transformer, pass 1/2, or ExportException
- [ ] **Send debug report** label consistent in overlay + both snackbars
- [ ] Invalid cache file deleted, not reused
- [ ] `BoomerangEvent.Failed` usages migrated or documented
- [ ] Tests added for validator + save failure event
- [ ] Manual note in PR description: tested on Pixel emulator + (if available) Samsung S23
