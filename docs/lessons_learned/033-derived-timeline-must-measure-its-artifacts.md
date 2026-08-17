# Lesson 033 — A derived timeline must **measure** the artifacts it spans, not assume they match their source window

> Origin: speed curves (`docs/PRD-speed-curves.md`), 2026-08-17. Caught on the emulator when a rendered
> loop came out 7.06 s after the editor had promised 9.2 s.

## What went wrong

A speed curve is defined over the whole boomerang loop, normalized `0f..1f`. To hand each clip its
slice of that curve, `loopClipSpans` lays the clips out on the loop's 1× timeline. The first version
assumed **every clip is one trim-window long**:

```kotlin
// WRONG — the reversed clip is not the trim window, it is a separately encoded file
val durationUs = (windowUs - dropUs).coerceAtLeast(0L)
```

For a forward clip that is true by construction — it *is* a `ClippingConfiguration` over the window.
For the **reversed** clip it is only *usually* true. `VideoReverser` produces a real MP4 through a
two-pass MediaCodec transcode; its duration is whatever the encoder stamped, and on a degenerate
source it can diverge badly. The emulator's virtual camera under `swiftshader` recorded ~5 fps, and
the reversed clip came back with **19 samples** — materially shorter than its 3.98 s window.

Two things then went wrong at once, and they compound:

1. **The curve mapped onto footage that was not there.** The reverse half of the loop was allotted a
   full window on the timeline, so the slice of the curve handed to the reversed clip ran off the end
   of the clip. The speeds that actually got applied were only the leading part of the intended slice.
2. **The editor lied about the output length.** The duration chip used
   `boomerangOutputDurationMs(...)` — `trimDuration × clipsPerCycle / speed` — computed *independently*
   of the spans the render sliced the curve over. Two formulas for one number means they can disagree,
   and they did: chip 9.2 s, encoder 7.06 s.

The forward-only path was exact the whole time (predicted 4.80 s, actual 4.80 s), which is precisely
what made this hard to see — the bug needed a reverse-containing mode *and* an artifact whose real
duration drifted from its window.

## Pattern

- **Measure a produced artifact before laying a derived timeline over it.** If a stage encodes a file,
  read that file's duration back; do not re-use the parameters you asked it to produce.
  ```kotlin
  val reversedMs = reversedFile?.let { withContext(Dispatchers.IO) { videoDurationMsOf(it) } }
  val spans = loopClipSpans(specs, windowMs, seamMs, reversedMs)   // measured, not assumed
  ```
  Keep the fallback explicit (`null` → use the window) so a not-yet-generated artifact still lays out.

- **A number shown to the user and the number the pipeline produces must come from one formula.** The
  duration chip now reads `loopOutputDurationMs(clipSpans, …)` — the *same* spans the render slices the
  curve over — so a divergence is impossible by construction rather than by luck. Two independent
  derivations of "how long will this be" is a bug waiting for an input that separates them.

- This generalises past speed: any future feature that maps a normalized position onto the concatenated
  loop (per-clip filters, keyframed crops, audio ducking) must build on the measured spans.

## Detection checklist

- Grep for layout math that multiplies a clip count by a nominal window:
  `rg 'clipsPerCycle|index \* window|windowUs \*' app/src/main` — each hit must justify why the real
  artifact cannot differ.
- Any duration/progress label near a media pipeline: confirm it derives from the same structure the
  encoder consumes. `boomerangOutputDurationMs` remains for callers that only know the trim window;
  anything holding a clip layout should use `loopOutputDurationMs`.
- On-device check that actually catches it — compare the editor's prediction to the muxed `mvhd`
  duration, for **all three** shapes (a forward-only pass will not reproduce it):
  | shape | predicted | actual |
  |---|---|---|
  | Constant 2×, FORWARD | 2.0 s | 2.04 s |
  | Ease-In curve, FORWARD | 4.80 s | 4.81 s |
  | Ease-In curve, FORWARD_THEN_REVERSE | 7.20 s | 7.26 s |
- Regression tests: `LoopClipSpanTest."a short reversed clip shortens only the reversed span"` and
  `"output duration follows the measured layout, so the chip cannot over-promise"`.

## Reference

- `media/KeyframeSpeedProvider.kt` (`loopClipSpans`, `videoDurationMsOf`, `loopOutputDurationMs`),
  `media/VideoProcessor.kt` (`renderBoomerang` measures the reversed clip),
  `ui/BoomerangEditorScreen.kt` (chip + graph read the same spans).
- Builds on [[018-boomerang-seam-drop-follows-sequence-position]] (the other reason a clip is not the
  length you assumed) and [[023-media-pipeline-stages-must-count-output-samples]] (a produced artifact
  is not trustworthy until you inspect it).
