# Lesson 042 — Seeking to a sync sample buys you nothing if you then skip the samples it gave you: drop the preroll at *render* time, never at the compressed input

> Origin: Issue #170 — reverse preview wedged on `Trimming..` for 120 s whenever the trim started
> between H.264 keyframes. See lessons 019, 021, 023 and
> [`docs/guides/reverse-video-research.md`](../guides/reverse-video-research.md).

## What went wrong

`VideoReverser`'s pass 1 has to transcode the window `[trimStart, trimEnd]`. It did the first half
correctly and then undid it:

```kotlin
extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)  // correct: land on a keyframe
while (extractor.sampleTime in 0 until startUs) {                 // and then throw it away
    if (!extractor.advance()) break
}
```

`SEEK_TO_PREVIOUS_SYNC` exists *because* an arbitrary trim point is usually not independently
decodable. Advancing past every sample below `startUs` discards the IDR frame and the reference
chain built on it, so the first packet queued to `MediaCodec` was a bare P-frame. The decoder had
nothing to predict from, produced no output, the encoder never published a format, and
`runDecodeEncodeLoop` polled with 10 ms dequeue timeouts against a `MAX_DECODE_LOOP_ITERATIONS`
guard measured in hours. The first effective escape was `OpenLoopViewModel`'s 120-second preview
deadline, which silently degraded the user's loop direction to forward-only.

Three things made this expensive to find:

1. **The symptom named the wrong subsystem.** It looked like reverse cost, codec contention, or an
   undersized timeout. It was none of those: the *identical* 1,030,015-byte camera file reversed in
   ~5.2 s at `trimStart = 0` and produced zero pass-1 output at `trimStart = 241 ms`.
2. **The fix that caused it had fixed a real bug.** Commit `c2705ad` added the skip loop to stop an
   immediate-EOS/zero-frame wedge. It cured that symptom at the compressed boundary — the one place
   where dropping a sample is never safe.
3. **Every existing test passed `trimStartMs = 0L`.** The regression was structurally invisible to
   the suite, and the happy-path test going green was part of the *control* evidence, not a defence.

The same mistake sat one branch away in the same loop: the >30 fps `pass1SampleAction` subsampling
also dropped compressed samples, smearing moving regions on any source above the cap (fold-loop
BUG-2). One boundary, two callers, one fix.

## Pattern

- **Compressed samples are not independent. Decoded frames are.** Any selection — a trim boundary,
  a frame-rate cap, a "skip every other frame" preview path — belongs on the decoded side of the
  decoder. Feed the codec *everything* from the sync sample through the window's end.
- **Discard with `releaseOutputBuffer(index, render = false)`.** That is the API for "this frame
  did its job as reference state and must not reach the encoder." It costs a decode, which is
  exactly what correctness costs here.
- **Let the decoded PTS carry the decision.** Queue input with the *source* timestamp and gate on
  `bufferInfo.presentationTimeUs >= startUs`. Rebasing at input (`sampleUs - startUs`, clamped at
  zero) collapses every preroll frame onto `0` and destroys the only thing that can tell preroll
  from content. Pass 2 re-stamps each frame as `last - t`, so the window offset cancels and the
  reversed clip still starts at zero — the rebase was never needed.
- **Re-check "zero-frame" escape hatches when you add latency in front of the encoder.** With a
  preroll ahead of a short window, the encoder can legitimately still be empty when input EOS is
  queued. `if (inputDone && !started) bail` had to become
  `if (inputDone && !started && nothingWasEverRendered) bail`, or a pass about to succeed is
  thrown away.
- **A trim-boundary bug is not symmetric.** The *end* of the window has no equivalent defect:
  H.264 references point backwards, so stopping the feed past `trimEnd` loses nothing. Confirmed on
  device, not assumed — `reverse(fixture, 0L, nonKeyframeEnd)` passed on the broken build that
  `reverse(fixture, nonKeyframeStart, duration)` failed on.

## Detection checklist

- Grep for a seek immediately followed by a skip: `grep -n -A4 "SEEK_TO_PREVIOUS_SYNC" app/src/main/java`.
  A `while (… sampleTime < startUs) advance()` after the seek is this bug.
- Grep the pass-1 feed loop for anything that calls `extractor.advance()` **without** a matching
  `queueInputBuffer`. Every compressed sample inside the fed range must reach the decoder exactly once.
- Any new trim/rate/preview selection logic: ask whether it runs before or after `dequeueOutputBuffer`.
  Before is a bug even when it happens to work on the fixture you tested.
- Assert non-vacuity in tests and verifiers. A trim start that happens to land on a keyframe exercises
  none of this — `VideoReverserTest` skips via `assumeTrue` on `SAMPLE_FLAG_SYNC`, and
  `reverse_preview_trim_loop.py` fails the run unless the `pass1.preroll` receipt shows
  `sync < trimStart`.
- Logcat signal for the wedge family: `reverse.pass1.start` with no `pass1.loop.done`, then
  `viewModel.ensureReversed.timeout`.

## Reference

- [MediaExtractor — `SEEK_TO_PREVIOUS_SYNC`](https://developer.android.com/reference/android/media/MediaExtractor#SEEK_TO_PREVIOUS_SYNC)
- [MediaCodec — `releaseOutputBuffer(int, boolean)`](https://developer.android.com/reference/android/media/MediaCodec#releaseOutputBuffer(int,%20boolean))
- [`docs/guides/reverse-video-research.md`](../guides/reverse-video-research.md) — reverse pipeline reference
- Originating work: Issue #170; regression introduced in `c2705ad`.
