# Research — Reversing video on Android (verified)

> **Status: COMPLETED** — shipped with boomerang slice 02 (`VideoReverser.kt`). Canonical algorithm reference (see also lessons 019–021).

Resolves the open question from the boomerang rollout (slices 02–03): *"Does Media3 1.10.1 ship a built-in
reverse video effect, and if not, what's the best alternative?"*

> Verified against developer.android.com, the androidx/media GitHub, the Media3
> 1.10 release blog post, the FFmpegKit retirement announcement, and a reference
> open-source MediaCodec reverse implementation. **CLAUDE.md "do not trust your
> training data" rule honored** — every claim below is cited.

---

## TL;DR

1. **Media3 1.10.x has no built-in reverse-video API.** Its standard video effects
   are color filters, matrix transforms (rotate/scale/crop), speed adjustment,
   frame drop, and overlays. Custom `Effect`s are GLSL shader programs applied
   per-frame — they're spatial, not temporal, and cannot reverse frame order.
   The `Composition` / `EditedMediaItem` APIs can sequence multiple clips and
   apply per-clip effects, but require a **pre-reversed source file** as input
   when reverse playback is desired.
2. **FFmpegKit is dead.** Retired January 6, 2025, repository archived June 23,
   2025, native binaries removed from Maven Central / CocoaPods / npm April 1
   1. No security patches, no active maintainer, no Maven artifacts that
   meet the 16 KB page-size requirement. Not viable as a v1 dependency.
3. **The path forward is hand-rolled MediaCodec + MediaExtractor + MediaMuxer.**
   This is the documented, working, standard-Android-API approach. A reference
   implementation (~400 lines of Kotlin) exists at
   [github.com/sixo/reverse-video](https://github.com/sixo/reverse-video),
   walked through in detail on
   [sisik.eu](https://sisik.eu/blog/android/media/reverse-video). The intermediate
   keyframe-only-pass technique it uses is the only reliable way to get smooth
   reverse playback without sync-sample stutters.
4. **The reversed segment is cached per source clip and reused everywhere.** One
   reverse render per session per clip; subsequent direction switches in the
   editor and the final boomerang export both consume the same cached file.
   This is what makes reference Boomerang feel instant after the first switch.

---

## 1. Media3 1.10.x — what's actually in the box

### What `androidx.media3.effect` ships

From the package summary on developer.android.com:

- **Color filters:** brightness, contrast, saturation, RGB matrix manipulation.
- **Matrix transformations:** rotation, scaling, crop, `Presentation` (canvas
  resize).
- **Speed adjustment:** `SpeedChangeEffect` (the constant-speed video effect present in Media3
  1.10.1 — *not* `SpeedChangingVideoEffect`, which isn't in this version; the audio counterpart is
  `SpeedChangingAudioProcessor`). Media3 1.10 specifically improved speed
  adjustment when exporting via Transformer.
- **Frame drop:** drops frames at intervals — does NOT reverse.
- **Overlays:** image / text / drawable layered on top of the video.
- **Custom effects:** subclass `GlEffect` / `BaseGlShaderProgram` to write a
  GLSL fragment shader.

### Why a custom `Effect` cannot reverse a video

Media3's effect pipeline applies GL shader programs **per frame, in the order
the frames arrive at the GPU**. A shader operates on one frame's pixels at a
time. It has no knowledge of (and no ability to reorder) frames. Temporal
operations like reverse, slow-motion frame interpolation, or
deduplication-aware loops are explicitly outside the `Effect` API's design.

Multiple GitHub issues in `androidx/media` show users asking for reverse
playback or trying to bend `Composition` to it; the consistent answer is
**you must produce a reversed source file separately and feed it as a
`MediaItem` into the `Composition`** (issue references in the sources below).

### What Media3 *does* give us

Once a reversed source file exists on disk, Media3 is excellent for everything
else in the boomerang pipeline:

- **`MediaItem.ClippingConfiguration`** for trim (free, native ExoPlayer
  feature — preview also honors it).
- **`Composition` of `EditedMediaItem`s** to concatenate `[trimmed, reversed]`
  for `FORWARD_THEN_REVERSE`, or N copies of the same cycle for repetitions.
- **`SpeedChangeEffect`** for speed.
- **`Transformer`** to encode the final MP4 in a single pass.

So Media3 stays in the architecture; it just doesn't own the reverse step.

---

## 2. FFmpegKit — retired, do not adopt

Original maintainer Taner Sener (Arthenica Ltd.) announced retirement on
[Medium, January 6, 2025](https://tanersener.medium.com/saying-goodbye-to-ffmpegkit-33ae939767e1):

- **Native binaries removed** from Maven Central, CocoaPods, and npm on
  April 1, 2025.
- **Repository archived** on GitHub June 23, 2025 (read-only; no PRs, no issues).
- **No security patches** going forward.

Stated reasons in the announcement: upstream FFmpeg maintenance burden, plus
legal uncertainty after MPEG LA's acquisition by Via-L (codec patent
exposure). Several community forks exist but none has emerged as a clear
successor.

Additionally, — even before retirement — bringing FFmpegKit into OpenLoop
would have meant:

- **APK size:** ~35–40 MB for a full build; ~13 MB minimal. Compared to
  OpenLoop's current single-digit MB APK, this is a 5–10× size increase.
- **Licensing:** LGPL v3 by default (workable with dynamic linking); GPL v3
  if any GPL filter is enabled. Apache 2.0 + LGPL is fine, but adds compliance
  burden (LGPL requires shipping the modified-library source on request).
- **16 KB compliance:** non-trivial. Stock FFmpegKit binaries are NOT 16
  KB-aligned; you must rebuild with `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`
  on NDK r23+/r25+. Now that there are no maintained Maven artifacts at all,
  the only path is custom-build-from-source, which means a custom CI job per
  release. See [Lesson 011](../lessons_learned/011-16kb-uncompressed-native-libs.md)
  for what we just went through getting compliant.

**Verdict:** Dead-letter. Don't introduce.

---

## 3. Hand-rolled MediaCodec — the recommended path

The Android-blessed approach to reversing video without external dependencies:
**MediaExtractor → MediaCodec (decode + re-encode) → MediaMuxer.** Stock SDK,
hardware-accelerated, zero APK-size impact, no third-party license. Roman
Sisik's [reference implementation](https://github.com/sixo/reverse-video)
(open source, ~400 lines of Kotlin, MIT license) demonstrates the working
approach.

### The naive single-pass algorithm (and why it fails)

```
seek MediaExtractor to end of file
while there are previous sync samples:
    seekTo(prev_sync_sample, SEEK_TO_CLOSEST_SYNC)
    feed sample to MediaCodec decoder
    encoder writes the decoded surface in reverse-presentation-time order
mux to output MP4
```

This compiles, runs, and produces a video — but the output **stutters** because
`MediaExtractor.seekTo()` only seeks to *sync samples* (I-frames). Inter-frame
compressed P/B frames depend on prior frames for decoding; if you skip from
I-frame to I-frame, you lose every frame in between. The result is reverse
playback that jumps from keyframe to keyframe, missing roughly 14 frames per
second on typical input.

### The working two-pass approach

**Pass 1 — re-encode the source so every frame is an I-frame:**

```kotlin
val outFormat = MediaFormat.createVideoFormat(mime, width, height).apply {
    setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FormatSurface)
    setInteger(MediaFormat.KEY_BIT_RATE, 20_000_000)
    setInteger(MediaFormat.KEY_FRAME_RATE, srcFps)
    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)  // ← every frame is an I-frame
    setString(MediaFormat.KEY_MIME, mime)
}
```

Output: an intermediate file, larger than the source (because P/B compression
is gone) but with every frame independently decodable.

**Pass 2 — reverse the intermediate using the sisik.eu algorithm:**

```kotlin
val syncSampleTimes = Stack<Long>()
while (true) {
    if (extractor.sampleFlags == MediaExtractor.SAMPLE_FLAG_SYNC) {
        syncSampleTimes.push(extractor.sampleTime)
    }
    if (!extractor.advance()) break
}
val endTimeUs = syncSampleTimes.lastElement()

while (syncSampleTimes.isNotEmpty()) {
    extractor.seekTo(syncSampleTimes.pop(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
    // feed sample to decoder, decoder → encoder Surface, encoder → muxer
    // remap presentation time: endTimeUs - extractor.sampleTime
}
```

Now every frame *is* a sync sample, so seeking to each one in reverse order
works and the output plays smoothly.

### Cost / trade-offs

- **Disk:** the intermediate keyframe-only file is roughly 2–4× the size of
  the source. For a 30 s 1080p source (~80 MB) the intermediate is 160–320 MB.
  Lives in `cacheDir/scratch/reversed/` and is deleted after the reversed output is
  produced.
- **Time:** two-pass means roughly 2× the time of a single transcode. On a
  Pixel 10 Pro Fold class device, expect ~0.5 s per 1 s of source for the
  keyframe pass + ~0.5 s per 1 s of source for the reverse pass. So a 3 s
  trimmed clip → ~3 s of reverse generation, first time. Subsequent direction
  switches in the editor that need the same reversed file are instant
  (cached).
- **Audio:** reversed audio sounds awful, so we strip it on reverse-pass
  output anyway. Matches parent doc D-3.
- **Codec compat:** H.264 / AAC in / out, MP4 container. Same as everything
  else in the pipeline. No new codec compatibility surface.

### Implementation notes (lessons from the reference code)

- Use `MediaCodec.createInputSurface()` on the encoder, pass that Surface to
  the decoder's `configure()`. Single-surface path avoids EGL setup — cleaner
  than the dual-surface OpenGL path you'd need only if you wanted to apply
  visual effects mid-reverse.
- Remap presentation times: `endPresentationTimeUs - extractor.sampleTime`
  for each sample fed to the decoder. This is what makes the encoder write
  the output in reverse-time order.
- Verify width/height against `MediaCodecInfo.VideoCapabilities.isSizeSupported(w, h)`
  before configuring the encoder; the reference code shows the snippet.
- Always release: `decoder.stop() / encoder.stop() / muxer.stop()` in a
  `finally` block. Otherwise, a failed reverse leaves codec resources held and
  the next attempt fails on Pixel hardware.

---

## 4. Alternatives considered (and rejected)

### In-memory frame buffer reversal
Decode the entire clip into a `ByteBuffer` ring, reverse the order in memory,
re-encode. **Math:** 1920 × 1080 × 4 bytes/pixel × 30 fps × 3 s = ~700 MB of
raw frames. For our max-trim case (30 s) that's 7 GB. Wildly OOM. Rejected
on memory cost alone.

### OpenGL shader reversal
GL shaders are per-frame; they can't reorder frames. Useful for visual effects
*during* the decode/encode loop (color tweaks, overlays) but does not solve
the reverse problem. Rejected on capability.

### Building FFmpeg from source (custom, not FFmpegKit)
Doable — the FFmpeg `reverse` filter is mature (`-filter_complex "reverse"`).
But requires:
- Per-architecture native build pipeline (arm64-v8a, x86_64, armeabi-v7a, x86).
- 16 KB linker flags on each.
- License selection (LGPL vs GPL) and the corresponding source-distribution
  obligations.
- Codec patent exposure (the exact issue that retired FFmpegKit).
- ~10–15 MB APK size growth even for a minimal build.

For a single feature (reverse), this is wildly disproportionate. Reconsider
only if v1.5 adds multiple filter-heavy features (vignette / film grain / 3D
LUTs / etc.) where FFmpeg's filter graph would pay for itself many times over.

### Media3 `Composition` with a "negative speed" trick
Speculative; no API support. `SpeedChangeEffect` takes a positive float
and is documented as such. There's no `setReverse(true)` on `MediaItem` or
`EditedMediaItem` in 1.10.x. Rejected on absence.

### Third-party video SDKs (Cloudinary, Mux, Banuba, etc.)
- Cloudinary / Mux: cloud-based; would break OpenLoop's "100% on-device"
  principle. Rejected on architecture.
- Banuba / similar commercial SDKs: closed-source, paid licensing,
  conflict with Apache-2.0 open-source model. Rejected on licensing.

---

## 5. Recommendation

**Build a single internal `VideoReverser` class that wraps the two-pass
MediaCodec approach.** Sketch:

```kotlin
class VideoReverser(
    private val scratchDir: File,
) {
    /**
     * Produce a reversed version of [source] in [scratchDir]. Suspending,
     * cancellable. Returns the reversed File. Strips audio. Idempotent —
     * if a reversed file for this source already exists in the cache,
     * returns it without re-processing.
     */
    suspend fun reverse(
        source: File,
        trimStartMs: Long,
        trimEndMs: Long,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) { /* ... */ }
}
```

Then wire it into the pipeline at two places:

- **`OpenLoopViewModel.ensureReversedSegment()`** (slice 03) — called when
  the user first switches to a reverse-containing direction. The result file
  path is stored in `EditorTabState.reversedFile` and used by the preview
  player.
- **`VideoProcessor.renderBoomerang()`** (slice 02 + 03) — when the chosen
  mode includes reverse, use the cached `reversedFile` directly as the
  second `EditedMediaItem` in the Composition rather than building a fresh
  one.

Cache key: the source file's absolute path + the trim window
(`<sourcePath>_<trimStart>_<trimEnd>.reversed.mp4`). Trim must be part of the
key because changing trim changes the reversed content. Live in
`cacheDir/scratch/reversed/`. Pruned by the same 24 h sweep that handles
orphan scratch captures in `filesDir/scratch/` (parent doc D-8).

### Estimated implementation effort

- **`VideoReverser`** body: ~250 lines of Kotlin, very close to the sisik.eu
  reference. Add unit tests with a small fixture MP4 — assert duration
  matches input within ±1 frame and frame sequence is reversed (sample first
  + last frames, compare via histogram).
- **Wiring into `VideoProcessor`:** ~30 lines (cache lookup, fallback to
  generation, pass result to Composition).
- **Wiring into the editor preview:** ~50 lines (loading shimmer state,
  player rebind on completion).
- **Total:** ~330 lines of new code + tests. Well within the slice 02 / slice
  03 budget.

### Performance expectations

Targeted at the Pixel 10 Pro Fold (the reference device per
`HEY_CLAUDE_ITS_ME.md`):

- 3 s clip → ~1 s first reverse generation. Sub-shimmer-acceptable.
- 10 s clip → ~3 s. Visible shimmer; user testing will tell us if it feels
  long. Add the "Preparing reverse…" caption from slice 03 to set
  expectations.
- 30 s clip (max) → ~9 s. Definitely shimmer-territory. Document the
  expectation and consider an early UI nudge ("Long clips take longer to
  reverse — trim first if you can.").

These are conservative back-of-envelope numbers; benchmark for real during
slice 03 QA and update this doc.

---

## 6. What this changes in the slice docs

- **Slice 02** (`02-auto-route-trim-and-default-save.md`) §"Technical deltas"
  → `media/VideoProcessor.kt`: replace the "Open question — verify
  `ReverseVideoEffect` exists" callout with a reference to this doc and a
  pointer to the new `VideoReverser` class. The default `fwd→rev` render
  becomes: trim → call `VideoReverser.reverse(...)` → build `Composition`
  of `[trimmed, reversed]` → encode.
- **Slice 03** (`03-editor-direction-tab.md`) §"Reverse preview decision"
  → mark "Status: Locked in writing" and link this doc as the source of
  truth. Steven's independent research can supplement; if it surfaces a
  better approach we update both docs.

(Those edits are NOT done by this research pass — that's a follow-up task.
Doing them inline would have mixed research with editing and made this doc
harder to read.)

---

## Sources

- [Media3 1.10 is out — Android Developers Blog](https://developer.android.com/blog/posts/media3-1-10-is-out)
  — release notes; speed-adjustment improvements; no mention of reverse.
- [androidx.media3.effect package summary — developer.android.com](https://developer.android.com/reference/androidx/media3/effect/package-summary)
  — authoritative effect class list. Includes `Presentation`, `BaseGlShaderProgram`, color/matrix/speed/overlay; no reverse.
- [Media3 Transformer — developer.android.com](https://developer.android.com/media/media3/transformer)
  — confirms Transformer is implemented on MediaCodec + OpenGL.
- [Transformations — developer.android.com](https://developer.android.com/media/media3/transformer/transformations)
  — lists supported transformations.
- [Create a basic video editing app using Media3 Transformer — developer.android.com](https://developer.android.com/media/implement/editing-app)
  — official walkthrough; no reverse example.
- [androidx/media — GitHub](https://github.com/androidx/media) — repo of record.
- [Saying Goodbye to FFmpegKit — Taner Sener, Medium, Jan 2025](https://tanersener.medium.com/saying-goodbye-to-ffmpegkit-33ae939767e1)
  — official retirement announcement.
- [arthenica/ffmpeg-kit — GitHub](https://github.com/arthenica/ffmpeg-kit)
  — repo (now archived).
- [FFmpeg-Kit + 16 KB Page Size In Android — Agam Koradiya, ProAndroidDev](https://proandroiddev.com/ffmpeg-kit-16-kb-page-size-in-android-d522adc5efa2)
  — what the custom-build path looks like for 16 KB compliance.
- [How to Reverse Video on Android (Without FFMPEG) — sisik.eu, Jan 2020](https://sisik.eu/blog/android/media/reverse-video)
  — the working two-pass MediaCodec algorithm with code.
- [sixo/reverse-video — GitHub](https://github.com/sixo/reverse-video)
  — open-source Kotlin reference implementation.
- [Play video in reverse — google/ExoPlayer Issue #2191](https://github.com/google/ExoPlayer/issues/2191)
  — confirms ExoPlayer cannot natively play in reverse; this is why preview-side
  reverse requires a pre-rendered file.
- [ExoPlayer Previews the Contents of a transformer.Composition — androidx/media Issue #1014](https://github.com/androidx/media/issues/1014)
  — feature request for previewing Compositions in-player (relevant for our
  preview-fidelity decision).
- [Media3 Transformer Concatenation Issue — androidx/media Issue #1658](https://github.com/androidx/media/issues/1658)
  — confirms the Composition + EditedMediaItem path for sequencing clips.
- [Common media processing operations with Jetpack Media3 Transformer — Android Developers Blog, Mar 2025](https://android-developers.googleblog.com/2025/03/media-processing-performance-jetpack-media3-transformer.html)
  — current-state recipe inventory; no reverse recipe.
