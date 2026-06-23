# LogCat signature catalog (OpenLoop E2E)

What `scan-logcat.ps1` greps for, what each means, and how to treat it. Classes:
**CRASH** = hard finding (report + quote the line), **TIMEOUT** = degraded UX bucket,
**CHURN** = advisory resource-pressure smell.

## CRASH — hard findings

| Signature | Meaning |
|-----------|---------|
| `FATAL EXCEPTION` | Uncaught exception — app crash. Always a finding; capture the full stack. |
| `ANR in <pkg>` | App-not-responding — main thread blocked. |
| `Process …openloop… died` | Process death (crash or low-memory kill). |
| `surface has been released` | `MediaCodec.configure` got a dead input surface — Crashlytics **b09e527**. The encoder/surface pair died (codec contention), not the decoder. |
| `native_dequeueOutputBuffer` (bare `IllegalStateException`) | Codec left the Executing state under the dequeue — Crashlytics **3a506c4e**. On Samsung the wedged codec *throws*; the emulator's software codec *hangs* instead (see TIMEOUT). |
| `CodecException` | A genuine `MediaCodec.CodecException` (distinct from the bare ISE above). |

## TIMEOUT — degraded UX (NOT a crash)

| Signature | Meaning |
|-----------|---------|
| `ensureReversed.timeout` / `Timed out after` | Reverse preview hit the **120 s** `select { onTimeout }` and was cancelled → the "Couldn't preview ping-pong — showing forward only" fallback. The emulator's software codec stalls here instead of throwing. Same root cause as the CRASH codecs, different symptom. |
| `reverse_preview_failure` | Non-fatal recorded to Crashlytics (`ReverseCrashlytics`). NOTE: `FirebaseCrashlytics.log()` does **not** echo to logcat, so a 0 count here does not prove no non-fatal was recorded — cross-check the timeout/fail terminal events. |

## CHURN — advisory (resource-pressure smell)

These rarely fail a run on their own, but a cluster of them around a reverse that stalled is the
fingerprint of the editor-codec-churn disease (see [`docs/lessons_learned/023-media-pipeline-stages-must-count-output-samples.md`](../../../../docs/lessons_learned/023-media-pipeline-stages-must-count-output-samples.md)).

| Signature | Meaning |
|-----------|---------|
| `keep callback message for reclaim` | The OS resource manager is reclaiming codecs — slot pressure. |
| `sending message to a Handler on a dead thread` | A `MediaCodec` was released while native still posts events — a lifecycle race from tearing down codecs mid-flight. |
| `received null buffer` / `discarded an unknown buffer` / `frameIndex not found` | Buffers landing on flushed/dead codec sessions — starvation. |
| `Created component [c2.*]` | Each line = one `MediaCodec` allocated. Compare the count to what the flow needs (~4–6 for a couple of reverses + one render). A much higher count = codecs recreated on nearly every editor interaction (the rebind storm). Inflated `Created component` counts can also come from *failed* trim-handle drags retriggering rebinds — distinguish your driving friction from a real defect. |

## Healthy reverse pipeline (what success looks like)

```
reverse.settle preReverseDelayMs=400
ExoPlayerImpl: Release <id>      # preview decoder freed before pass 1
reverse.pass1.start              # opens AFTER the release
reverse.pass1.done
reverse.pass2.done
reverse.complete attempts=1
viewModel.ensureReversed.ok gen=<n>
```
A fresh, undisturbed short clip reverses in a few seconds. A churned clip (many edits first) is the
one that stalls to the 120 s timeout — clip length is not the driver, editor churn is.

## Related repo context

- [`docs/lessons_learned/023-media-pipeline-stages-must-count-output-samples.md`](../../../../docs/lessons_learned/023-media-pipeline-stages-must-count-output-samples.md) — codec churn / output-sample counting
- [`docs/lessons_learned/020-imported-clips-hdr-codec-and-reverse-failure-recovery.md`](../../../../docs/lessons_learned/020-imported-clips-hdr-codec-and-reverse-failure-recovery.md) — Crashlytics issues `3a506c4e` / `b09e527`
- `ReverseCrashlytics.kt` — custom keys and non-fatal recording
- Memory `project-trim-handle-touch-sensitivity-bug` — why the trim handles fight you.
