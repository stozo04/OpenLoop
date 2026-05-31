# Lesson 021 — Don't downscale inside a decode→encode-Surface transcode by sizing the encoder Surface smaller than the decoder's output; the producer→consumer scale is device-dependent and corrupts to green on the software path

> Origin: onboarding-video work — found on the Pixel 10 Pro Fold (Android 16) the first time a
> **>1080p import** (1440×2560 H.264, 8-bit SDR) was reversed: the boomerang's forward half was clean
> but the **reverse half was solid green macroblock garbage**. Confirmed fixed on-device.

## What went wrong

`VideoReverser` reverses via a two-pass `MediaCodec` pipeline where the **decoder renders onto the
encoder's input `Surface`** (no GL stage). Pass 1 sized that encoder Surface with `cappedToShortSide()`
— i.e. **smaller** than the source — intending the Surface to downscale: source 1440×2560 → encoder
1080×1920. The decoder still outputs native **1440×2560** frames into a **1080×1920** encoder input
Surface.

That producer→consumer size mismatch is **device- and codec-dependent**. On this clip the HW AVC
encoder didn't accept the all-keyframe 1080×1920@60 format, so it fell back to the **software** codec
(`c2.google.avc.encoder` / `c2.google.avc.decoder`), and on that path the mismatched-size Surface
scale produced corrupt (green) frames — `CCodecBufferChannel` spam: `received 0-sized buffer`,
`MediaCodec discarded an unknown buffer`.

**Why it hid for so long:** every clip the app had ever reversed came from its own camera, which is
**already ≤ the 1080p short-side cap** — so `cappedToShortSide()` returned the *same* dimensions, the
encoder Surface matched the decoder output, and there was no scale to get wrong. A **>1080p imported
clip is the first input that actually exercises the downscale branch** (builds on the imported-media
hazards in [[020-imported-clips-hdr-codec-and-reverse-failure-recovery]]).

## Pattern

In a raw decode→encoder-input-`Surface` transcode, **make the encoder Surface the same size as the
decoder's output.** Do not lean on the Surface to resize — that scale path is not guaranteed and is a
silent corruption risk on software/vendor codecs.

- **Reverse at native resolution; downscale elsewhere.** `VideoReverser` now encodes at the source's
  native size (`evenDown(w)`/`evenDown(h)` only, for 4:2:0). The resolution cap lives **only** in the
  Media3 render (`Presentation.createForShortSide(MAX_OUTPUT_SHORT_SIDE)`), which scales through a
  correct GL pipeline and is applied to the forward **and** reversed clips alike — so the two halves
  still match and a 4K/8K import still becomes a ≤1080p loop.
- If you ever *must* downscale within a raw `MediaCodec` transcode, insert a GL stage
  (decoder → `SurfaceTexture` → GL render at target size → encoder input Surface) — the
  CTS/Grafika `DecodeEditEncode` pattern — never a bare size-mismatched Surface.
- **Trade-off accepted:** native-res reverse means the software encoder chews more pixels (slower
  "Loopifying…"). Correctness first; a GL-downscale reverse pass is the follow-up if the wait is too
  long on large imports.

## Detection checklist

- Grep `media/` for `createInputSurface()` paired with an encoder `MediaFormat` whose width/height
  come from `cappedToShortSide()` / any value **smaller than the decoder's input format** — that is the
  mismatch. The encoder Surface size must equal the decoder output size.
- A transcode that works on camera captures but corrupts (green/garbled) only on **imported** clips is
  a tell that an imported-only branch (here: the >cap downscale) is being exercised for the first time.
- `VideoReverser` logs `selectAvcEncoder: <name> for WxH` and `reverse pass2: WxH, frames=N, sync=M`.
  `sync < frames` would mean pass 1 didn't honor all-keyframe (a *different* failure — stutter, not
  green); a software encoder name on a large clip explains a slow "Loopifying…".

## Reference

- [`Presentation` (resolution effect)](https://developer.android.com/reference/androidx/media3/effect/Presentation) — the correct, GL-backed place to downscale.
- [`MediaCodec` Surface input/output](https://developer.android.com/reference/android/media/MediaCodec#data-processing) — Surface-to-Surface size handling is not specified to scale.
- `media/VideoReverser.kt` (`transcodeToAllKeyframes` native-size encoder), `media/VideoProcessor.kt`
  (`cappedToShortSide`, `Presentation` cap on every clip). Builds on
  [[020-imported-clips-hdr-codec-and-reverse-failure-recovery]] and [[019-reverse-rotation-strip-decoder-restamp-muxer]].
