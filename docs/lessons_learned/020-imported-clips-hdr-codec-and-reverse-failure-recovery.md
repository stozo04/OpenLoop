# Lesson 020 — Imported clips exercise codecs the camera never produces: tone-map HDR, prefer a HW encoder, cap resolution — and a failed reverse must never wedge the editor

> Origin: Slice 07 (import-from-library). Found on the Pixel 10 Pro Fold (Android 16) the first time a
> real library video — a **10-bit HDR HEVC** clip — was imported: the editor stuck on "Loopifying…"
> forever with `system_server` codec spam. Confirmed fixed on-device.

## What went wrong

Every clip the app had ever processed came from its own camera: clean **8-bit SDR** H.264/HEVC at a
modest resolution. The moment an *imported* clip entered the same pipeline, three latent problems fired
at once on the very first real HDR video:

1. **10-bit HDR into an 8-bit encoder.** `VideoReverser` decodes onto an AVC encoder's input Surface.
   An imported HDR (HLG/PQ, 10-bit / P010) source decodes to 10-bit frames the H.264 encoder rejects:
   ```
   GC2_EncComp: AVC does not support 10-bit input
   CCodecBufferChannel: [c2.google.avc.encoder] work failed to complete: 22   (EINVAL)
   ```
   No tone-mapping happened, so the reverse threw `IllegalStateException: Pending dequeue output buffer
   request cancelled`.

2. **A caught failure still wedged the UI.** The ViewModel *did* catch it ("Reverse generation for
   preview failed") and cleared `isReversedFileLoading` — but the editor's shimmer was gated on
   `reversedFile == null` with **no failure state**, so a failed reverse left "Loopifying…" on screen
   permanently and Save disabled. A camera capture never reproduced this (its reverse essentially never
   fails), so imports were the first thing to expose it.

3. **Software encoder + high resolution = apparent hang.** `MediaCodec.createEncoderByType(MIME_AVC)`
   resolved to the *software* `c2.google.avc.encoder`. Fine for the camera's clips; for a 4K/high-fps
   import it would encode frame-by-frame (twice, every frame a keyframe) so slowly it looks hung, and
   can exceed the encoder's level limit outright.

## Pattern

Treat "imported media" as adversarial input that uses codecs/bit-depths/resolutions the camera path
never produced, and harden all three legs:

- **Tone-map HDR→SDR.** Raw `MediaCodec` reverse path: on API 31+ set
  `format.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)` on
  the **decoder** configure format (no-op on SDR sources / below API 31). Media3 render path:
  `Composition.Builder(...).setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)` — OpenGL
  mode is API 29+, widely supported, and falls back gracefully (the `…USING_MEDIACODEC` variant throws
  `ExportException`).
- **Prefer a hardware encoder.** Don't take `createEncoderByType`'s default. Query
  `MediaCodecList(REGULAR_CODECS)`, filter encoders whose `getCapabilitiesForType(AVC).isFormatSupported(format)`
  is true, and pick `isHardwareAccelerated` first (API 29+); fall back to the default.
- **Cap output resolution (downscale-only).** Share one `cappedToShortSide(w, h, 1080)` between the
  reverse pass (encoder Surface size — the decoder scales onto it) and the render
  (`Presentation.createForShortSide(1080)`), so the forward and reversed halves match and a 4K/8K clip
  becomes a fast, in-limits 1080p loop. Never upscale; force even dimensions.
- **A failed reverse must degrade, never hang.** Any UI gated on an artifact being present
  (`reversedFile == null`) needs an explicit *failure* state too, or a caught failure becomes a
  permanent spinner. Add `reverseFailed`, drop the shimmer when it's set, and surface a retry.

## Detection checklist

- Any decode→encode-Surface transcode that an imported clip can reach: confirm the decoder format
  requests SDR tone-mapping (`KEY_COLOR_TRANSFER_REQUEST`) and the encoder is chosen by capability, not
  `createEncoderByType` alone. Grep `createEncoderByType` in `media/` — each hit should have a
  capability-based selector or a comment explaining why the default is safe.
- Grep editor/preview gating for `reversedFile == null` / `isReversedFileLoading`: a loading overlay
  gated only on "artifact absent" with no failure branch is a wedge waiting to happen.
- On-device A/B is the only real proof (tone-mapping + HW-encoder support are device-dependent):
  import a **10-bit HDR** clip and a **4K** clip → reverse preview works, Save renders a watchable
  ≤1080p SDR loop; force a reverse failure (e.g. an undecodable clip) → editor shows a retry, never
  "Loopifying…" forever.

## Reference

- [Tone mapping (Media3 Transformer)](https://developer.android.com/media/media3/transformer/tone-mapping)
- [`Composition.HdrMode`](https://developer.android.com/reference/androidx/media3/transformer/Composition.HdrMode)
- [`MediaFormat.KEY_COLOR_TRANSFER_REQUEST` — added API 31](https://developer.android.com/sdk/api_diff/31/changes/android.media.MediaFormat)
- [`Presentation` (resolution effect)](https://developer.android.com/reference/androidx/media3/effect/Presentation)
- `media/VideoReverser.kt` (`requestSdrToneMapping`, `selectAvcEncoder`), `media/VideoProcessor.kt`
  (`cappedToShortSide`, `setHdrMode`), `ui/OpenLoopViewModel.kt` (`reverseFailed`), and builds on
  [[019-reverse-rotation-strip-decoder-restamp-muxer]].
