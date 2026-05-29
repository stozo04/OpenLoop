# HANDOFF — Boomerang Slice 02 (PR #24)

> **This is a temporary session handoff, NOT a numbered lesson.** It lives here because CLAUDE.md
> makes every session read this folder at startup, so it's the surest way to reach the next Claude.
> **Delete it once PR #24 merges** (or distill any durable rule into a real numbered lesson first).

You are picking up **PR #24** — `feature/boomerang-slice-02-trim-and-default-save` →
https://github.com/stozo04/OpenRang/pull/24. Assume you wrote none of it. Read this, then the slice
PRD at [`docs/active/boomerang-rollout/02-auto-route-trim-and-default-save.md`](../active/boomerang-rollout/02-auto-route-trim-and-default-save.md)
and its sibling [`RESEARCH-reverse-video.md`](../active/boomerang-rollout/RESEARCH-reverse-video.md).

## What this PR does (one paragraph)

After a capture finalizes, the app auto-routes to a new **Trim** screen instead of the old
`LoopingPreview`. The user drags two handles to pick a sub-range; **NEXT** renders a default
`FORWARD_THEN_REVERSE` / 2× / 1-rep boomerang and saves it (the raw is kept alongside the boomerang),
shows a "Saved — view in gallery" snackbar, and returns to the camera. Direction/speed/reps pickers,
the tabbed editor, share sheet, and gallery badges are all **out of scope** (slices 03–07).

## The map (where things live)

- **`media/VideoReverser.kt`** — two-pass MediaCodec reverser (no Media3 reverse effect exists). Pass 1
  re-encodes the trim window so every frame is a keyframe; pass 2 seeks last→first and re-stamps PTS.
  sha1 trim-keyed cache in `cacheDir/scratch/reversed/`; `finally` cleanup; cancellation-safe.
- **`media/VideoProcessor.kt`** — `Media3VideoProcessor`: builds a `Composition` of the forward
  `EditedMediaItem` + the already-reversed half (1-frame seam offset), `SpeedChangeEffect(2f)`, audio
  stripped; bridges the async `Transformer` into a suspend fn. Progress budget: reverse owns `0..0.8`,
  to encode owns `0.8..1.0`.
- **`ui/TrimScreen.kt`** — `TrimScreen(viewModel)` (thin) + stateless `TrimScreenContent` (testable).
- **`ui/ProcessingScreen.kt`** — spinner; back is consumed (no mid-render cancel this slice).
- **`ui/OpenRangViewModel.kt`** — `onNextFromTrim()` is the orchestrator: promote scratch→raw, allocate,
  render, register, discard scratch, emit `Saved`. Failure → back to `Trim`, emit `Failed`, selection
  preserved. One-shot events via `Channel.receiveAsFlow()`.
- **`data/VideoStorageRepository(Impl).kt`** — scratch/boomerang methods + `RecordedVideo.kind`/`sourceRawId`.

## State of play (read before touching anything)

- ✅ `assembleDebug` + `assembleRelease` green; `testDebugUnitTest` **47/0**; `lintDebug` no new issues.
- ❌ **Nothing has run on a device.** `connectedDebugAndroidTest` not run; the reverse pipeline is
  **compile-verified only**. Treat `VideoReverser`/`VideoProcessor` as a *first cut*, not proven.
- The trim UI was iterated twice on real-device feedback: (1) handles snapped back — fixed by moving
  input to a stationary full-width overlay using **absolute** pointer X (not deltas); (2) preview
  ignored the trim — fixed by dropping `ClippingConfiguration` (same-URI re-clip got deduped) for a
  **manual seek-loop** (`seekTo(start)` + ~40 ms poll). Don't "simplify" either back without testing.
- Media3 **1.10.1** API drift is real and saved in memory: it's `SpeedChangeEffect`, NOT
  `SpeedChangingVideoEffect`; listener is `onCompleted`/`onError`; `EditedMediaItemSequence.withVideoFrom(...)`;
  `setUri` needs `file.toUri()`; Media3 `@UnstableApi` needs **`androidx.annotation.OptIn`**, not `kotlin.OptIn`.
- `VideoReverserTest` **skips** unless you drop a fixture at `app/src/androidTest/assets/trim_fixture.mp4`.

## What's left for merge (the PR's unchecked boxes)

1. Run `connectedDebugAndroidTest` on a device.
2. **Validate the reverse on-device** — eyeball it: first frame of the rendered boomerang's reverse
   half ≈ last frame of the trimmed source. If reverse is broken, the pass-2 seek/decode pump is the
   first suspect (see below).
3. Manual QA on emulator **and** the Pixel 10 Pro Fold (`adb` serial in the slice doc) + screenshot.
4. **Reverse-latency timing in the PR description** — see next section; this is the part to do carefully.

## Reverse-latency timing — how to measure, and what to expect

The DoD asks for "reverse-generation latency for a 3 s trimmed source on Pixel 10 Pro Fold (target:
≤ 1.5 s end-to-end including Composition encode; investigate if higher)." My honest take:

**Measure on the real device, never the emulator.** The emulator's MediaCodec is often a software
codec that is dramatically slower (or faster in unrepresentative ways) than the Fold's hardware
encoder/decoder. Emulator numbers are not a valid proxy and must not go in the PR.

**How to instrument (temporary, remove before merge):** the work is already split by the progress
budget, so timing the phases is cheap. In `OpenRangViewModel.onNextFromTrim`, wrap the call:
```kotlin
val t0 = android.os.SystemClock.elapsedRealtime()
videoProcessor.renderBoomerang(...) { f -> _renderProgress.value = f }
android.util.Log.d("BoomPerf", "total=${android.os.SystemClock.elapsedRealtime() - t0}ms")
```
For the pass split, log timestamps in `Media3VideoProcessor.renderBoomerang` right after
`reverser.reverse(...)` returns (that's the reverse phase) and after `runTransformer(...)` returns
(the Composition encode). Report three numbers in the PR:
- **cache-miss total** (first render of a given trim),
- **cache-hit total** (render the *same* trim again — the reversed file is reused, so this is basically
  just the Composition encode; it's the cleanest measure of the encode cost),
- the **reverse vs encode** split.

**What to expect, and don't be surprised if it exceeds the 1.5 s target.** CameraX records at
`Quality.HD` (720p), which helps. But pass 2 does a `seekTo` + decode + surface-render **per frame** —
a 3 s clip at 30 fps is ~90 seeks. Per-frame seeking is the classic bottleneck of this algorithm and
can easily be 1–2 s on its own; add pass-1 transcode (~0.3–0.6 s) and the encode (~0.4–0.6 s) and a
realistic cache-miss total is **~2–3 s**, not 1.5 s. Cold codec instantiation
(`createEncoderByType`/`configure`) adds ~100–300 ms to the first render of a session.

**My advice:** report the true numbers. If it's over target, say so plainly and frame the seek-per-frame
pass 2 as the bottleneck and a v1.5 optimization (e.g. decode-forward-into-a-frame-buffer-then-emit-
backwards in windowed batches, a lower intermediate bitrate, or skipping pass 1 when the source is
already all-keyframe). Do **not** quietly tune the trim down to 1 s to hit the number, and do **not**
put an emulator figure in the PR. An honest "2.4 s cache-miss / 0.5 s cache-hit, investigate pass-2
seeking in v1.5" is worth far more than a green checkmark that doesn't reproduce on a user's phone.

## Gotchas that will bite you

- Don't re-introduce `ClippingConfiguration` for the trim preview (it gets deduped on same-URI re-clip).
- `pointerInput(durationMs)` in the trim bar does **not** restart per recomposition; the clamps read
  fresh start/end via `rememberUpdatedState` — keep that or the handles will clamp against stale bounds.
- `onNextFromTrim` promotes scratch→raw **before** render and caches it (`promotedRaw`), so a failed-render
  retry doesn't create a second raw. A failed render leaves the raw on disk by design ("raws kept").
- Process-kill on Trim/Processing orphans the scratch — pruning is slice-07, documented as expected.

## Related memory

`reference_media3-1-10-1-transformer-api` and `project_android-version-doc-architecture` (in the
auto-memory index) — read them; they'll save you re-verifying the Media3 surface for slices 03–05.
