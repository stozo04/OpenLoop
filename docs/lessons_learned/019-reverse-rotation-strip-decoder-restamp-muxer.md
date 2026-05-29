# Lesson 019 — Reversing through a decoder→encoder Surface: strip `KEY_ROTATION` on the decoder, re-stamp it on the muxer

> Origin: Slice 02 (`VideoReverser`, PR #24), distilled from the slice-02 SECOND-REVIEW before that
> temporary doc was deleted. Applied and confirmed working on the Pixel 10 Pro Fold during the slice-03
> device pass; revisit this lesson first if a portrait boomerang ever renders sideways or double-rotated.

## What went wrong (the device-dependent trap)

`VideoReverser` reverses video by decoding onto a `MediaCodec` encoder's input `Surface`
(decoder output Surface → encoder input Surface). On that path, two things both try to apply the
source's orientation:

1. **`MediaCodec` auto-applies rotation in Surface-output mode** — but whether the
   decoder→encoder-*input*-surface path *bakes* the rotation into pixels (vs. carrying it as a surface
   transform the encoder ignores) is **device-dependent**. Android's own guidance is "no way to know
   other than trying it."
2. **The muxer can carry an orientation hint** (`MediaMuxer.setOrientationHint`).

If you leave `KEY_ROTATION` on the decoder input format *and* also write the muxer hint, some devices
**double-rotate** (bake it once in pixels, then the player rotates again from the hint) — a portrait
clip comes out sideways or upside-down, and only on certain hardware, so the emulator won't show it.

## Pattern

Make the reversed file structurally identical to the raw source — **coded-orientation pixels + a
metadata-only hint** — regardless of device:

```kotlin
// In BOTH passes, after reading the source format: neutralize rotation on the decoder input format…
val rotation = sourceFormat.rotationDegreesOrZero()   // read the hint first (type-tolerant; Lesson on KEY_FRAME_RATE)
decoderInputFormat.setInteger(MediaFormat.KEY_ROTATION, 0)
// …then re-stamp the SAME hint on the muxer so the reversed file carries orientation as metadata only.
muxer.setOrientationHint(rotation)
```

The forward half (a plain clip of the source) already carries the source's hint, so both halves now
present orientation the same way — Media3 normalizes from the hint at composition time and the seam
isn't a rotation discontinuity.

## Detection checklist

- In any decode→encode-Surface transcode (`VideoReverser`, future effect passes), grep for
  `KEY_ROTATION` — it must be **zeroed on the decoder input format** and the original degrees
  **re-stamped via `setOrientationHint`**, in *every* pass, never left on the decoder format.
- Read the rotation hint with the type-tolerant helper (`MediaFormat.rotationDegreesOrZero()` in
  `MediaFormatUtils`), not `getInteger(KEY_ROTATION)` directly — same `ClassCastException`-on-non-Int
  trap as `KEY_FRAME_RATE`.
- On-device A/B (the only real test): record **portrait**, render a reverse-containing boomerang, and
  confirm the forward half and reversed half have the **same** orientation and the reverse half's first
  frame ≈ the trim's last frame, right way up. If still wrong after this, the bug is no longer the
  reverser — look at the Transformer/Composition output orientation (consider an explicit
  `ScaleAndRotateTransformation` or output orientation hint).

## Reference

- [`MediaMuxer.setOrientationHint`](https://developer.android.com/reference/android/media/MediaMuxer#setOrientationHint(int)) — orientation as container metadata.
- [`MediaCodec`](https://developer.android.com/reference/android/media/MediaCodec) — Surface-mode auto-rotate is device-dependent on the decoder→encoder-input path.
- `app/src/main/java/com/openrang/app/media/VideoReverser.kt` + `MediaFormatUtils.rotationDegreesOrZero()`.
