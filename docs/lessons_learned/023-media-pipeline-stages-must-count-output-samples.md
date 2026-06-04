# Lesson 023 — A media pipeline stage must count its own output samples; a zero-frame stage can exit "cleanly"

> Origin: the Samsung S23 "Could not save loop" bug (2026-06-04) — full forensics in
> `docs/active/reverse-output-validation/RESEARCH.md`, plain-English version in
> `EXPLAINED-SIMPLY.md` in the same folder.

## What went wrong

On the Galaxy S23 (SM-S911U, API 33), `VideoReverser`'s pass 2 (decode → encoder-input-`Surface` →
encode) **starved**: the decoder's rendered frames never reached the hardware encoder's
`GraphicBufferSource`. The encoder, having received only `signalEndOfInputStream()`, emitted a
format-change + an empty EOS buffer. That satisfied the drain loop's exit condition
(`EOS && muxerStarted`) **without any error**, so `MediaMuxer.stop()` finalized a syntactically
valid, 598-byte, zero-sample MP4 — and `reverse()` returned it as success.

Three downstream defects amplified one device quirk into an undebuggable failure:

1. **No success criterion.** Nothing checked that any sample was muxed. "The loop exited and a file
   exists" was treated as "the work happened."
2. **`length() > 0` as a cache gate.** The empty shell passed it, poisoning the trim-window cache —
   every retry replayed the failure instantly.
3. **The visible error pointed at the wrong subsystem.** Media3's Transformer rejected the empty
   file 3 seconds later with `IllegalStateException: The asset loader has no audio or video track
   to output` — days were spent debugging the Transformer when the corpse was made upstream. (And
   `ExportException` extends `Exception` directly, so it also slipped past the worker's
   `IOException`/`RuntimeException` catches — see the catch-list sweep below.)

Bonus failure of the same shape: the **editor preview** had been "succeeding" with the same empty
file all along — the reverse half of the preview was a ~0-duration clip nobody noticed.

## Pattern

- **Count what you mux.** Every decode/encode loop that writes via `MediaMuxer.writeSampleData`
  threads an `onSampleWritten` counter; on loop exit, `samples == 0` throws
  (`ReverseOutputInvalidException`) — never return the file.
- **Validate artifacts at stage boundaries, by content.** `ReverseOutputValidator` probes with
  `MediaExtractor`: ≥1 `video/` track with ≥1 readable sample; unparseable = invalid, never a
  crash. Run it on cache hits (delete + regenerate on failure) and before handing the file to the
  next stage (Transformer).
- **Size is diagnostics, not a gate.** The spec's first draft used `MIN_REVERSED_BYTES = 4096`; the
  first instrumented run falsified it — a Pixel encoded a legitimate 12-frame 320x240 clip under
  4 KiB. Sample presence is the only honest check.
- **When a checked exception type joins a flow, sweep the catch lists.**
  `ExportException extends Exception` matched none of the worker's catches and bypassed cleanup.
  New domain exceptions should extend a type the existing paths already handle
  (`ReverseOutputInvalidException extends IOException`) or every `catch` chain must be re-audited.
- **Device-misbehavior fixes must be hypothesis-tested on the device.** Two plausible fixes were
  wrong: the "platform decoder" swap failed harder (`CodecException 0xe`), and only the on-device
  pairing matrix found the working one (software **encoder** fallback + process-sticky). An
  isolated instrumented repro (`am instrument` + a tiny synthetic clip) turned each hypothesis test
  into a 2-minute loop.

## Detection checklist

- Grep media write loops for `writeSampleData` — each enclosing stage must have a sample counter
  checked at exit. `grep -n "writeSampleData" app/src/main/java`
- Grep for size-only artifact checks: `length() > 0` / `length() >= ` used as a success/cache gate
  on produced media files.
- For every `Transformer`/`MediaCodec` callback exception type used in a module, confirm a worker
  or coroutine `catch` actually matches its static type (watch for `Exception`-direct subclasses).
- Crashlytics signal for the wedge family: `reverse_validation_reason=no_video_samples` /
  `no_video_track` custom keys; logcat signal: `MPEG4Writer: Received total/0-length (0/0) buffers
  and encoded 0 frames` followed by a "successful" stage.

## Reference

- [MediaCodec — surface input, EOS semantics](https://developer.android.com/reference/android/media/MediaCodec)
- [Media3 Transformer troubleshooting](https://developer.android.com/media/media3/transformer/troubleshooting)
- `docs/active/reverse-output-validation/` (IMPLEMENTATION.md spec, RESEARCH.md forensics + on-device pairing matrix)
- Originating work: PR #62 (branch `fix/crashlytics-3a506c4e-pass1-codec-lifecycle`); fixture-matrix follow-up: Issue #64.
