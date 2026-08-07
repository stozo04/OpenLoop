# Lesson 027 — Samsung reverse encoder try-order must never empty into `createEncoderByType` (Exynos `0x80001006`)

> Origin: Galaxy S20+ RTL (SM-G985F / API 33) while verifying the Looks-gate fix — reverse snackbar
> immediately after NEXT. Log: `encoder.try_order … →` (empty) then
> `encoder.selected | <createEncoderByType>` → `OMX.Exynos.AVC.Encoder ERROR(0x80001006)`.

## What went wrong

`avcEncoderTryOrderForReverse` on Samsung:

1. Prefers `c2.google.avc.encoder` when installed.
2. Runs `filterOmxcEncodersWhenCodec2Available` — drops all `OMX.*` whenever any `c2.*` exists.
3. Strips `isSamsungVendorAvcCodec` names (`exynos`, `c2.sec`, **`android.avc`**).

On S20-class API 33 the installed surface AVC encoders are often:

`OMX.Exynos.AVC.Encoder`, `c2.android.avc.encoder`, `OMX.google.h264.encoder`

— **no** `c2.google.avc.encoder`. Steps 2–3 then remove every candidate → **empty try-order**.
`openSurfaceAvcEncoder` falls through to `MediaCodec.createEncoderByType("video/avc")`, which picks
Exynos and fails in `drainToMuxer` with `ERROR(0x80001006)` / empty `IllegalStateException`.
The Samsung contention retry re-ran the same path (`preferSoftwareEncoder=false`).

## Pattern

- **Never ship an empty Samsung try-order when any Google/software encoder is installed.** If the
  preferred list is empty, append [samsungLastResortSoftwareAvcEncoders]:
  `c2.google.avc.encoder` → `OMX.google.h264.encoder` → `c2.android.avc.encoder`.
- When `c2.google` is missing, **promote `OMX.google.h264.encoder` ahead of ranked** — do not rely
  on it surviving the Codec2/OMX filter.
- On Samsung **contention** retry, set `softwareCodecFallback = true` so attempt 2 cannot repeat
  Exynos via `createEncoderByType`.
- Log `encoder.try_order … → <names>` — an empty list after `→` is a red alert.

## Detection checklist

- Grep RTL / Crashlytics for `encoder.selected | <createEncoderByType>` on `samsung=true` followed
  by `ERROR(0x80001006)` / `reverse.failed` in pass 1.
- Unit-test the S20+ installed set (no `c2.google`, has OMX Google + android.avc) → try-order must
  start with `OMX.google.h264.encoder`, never empty, never Exynos.
- `avcEncoderTryOrderForReverse` + `openSurfaceAvcEncoder` fallback: empty try-order on Samsung with
  software codecs present is a bug.

## Reference

- [MediaCodecList.findEncoderForFormat](https://developer.android.com/reference/android/media/MediaCodecList)
- `media/ReverseEncoderSelection.kt`, `media/VideoReverser.kt` (`openSurfaceAvcEncoder`)
- Builds on [[023-media-pipeline-stages-must-count-output-samples]] (Samsung reverse ≠ clean success)
  and [[026-looks-gate-not-reverse-failure]] (forward fallback must not brick Looks).
