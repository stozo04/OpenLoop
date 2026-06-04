# Editor codec churn — root cause (reverse preview / ping-pong)

**Status:** Active — disease behind PR #62 symptoms  
**Related:** [`../crashlytics-reverse-preview/HANDOFF.md`](../crashlytics-reverse-preview/HANDOFF.md) (PR #62 treats Samsung throws), [`../editor-memory-oom/IMPLEMENTATION.md`](../editor-memory-oom/IMPLEMENTATION.md)

---

## Problem statement

Users see **ping-pong / reverse mode fail to apply**: the editor stays on forward playback, a **120 s timeout**, or (on Samsung) Crashlytics non-fatals `3a506c4e` / `b09e527`. The failure banner describes a setting failure; the underlying issue is **MediaCodec slot exhaustion** from editor preview churn competing with the 2-pass reverse transcode.

PR #62 correctly maps Samsung **symptoms** (ISE at dequeue, surface released at configure) to cooperative cancel + retry. It does **not** eliminate contention when the preview player still holds a decoder or the editor recreates codecs faster than they release.

---

## Architecture (who holds codecs)

| Phase | Codec sessions (typical) |
|-------|---------------------------|
| Editor preview (ExoPlayer) | 1× video decoder (+ optional `DefaultVideoFrameProcessor` GL path when a look is applied) |
| Reverse pass 1 (`transcodeToAllKeyframes`) | 1× decoder + 1× encoder (surface pipeline) |
| Reverse pass 2 | 1× decoder + 1× encoder |

Devices expose only a **small pool** of hardware/software codec instances. **Three sessions at once** (preview + pass 1) is enough to trigger reclaim pressure.

Reverse pumping: `VideoReverser.runDecodeEncodeLoop` calls `dequeueOutputBuffer(timeoutUs)` in a loop. If the codec is wedged under reclaim, the call returns `INFO_TRY_AGAIN_LATER` forever — **no exception** — until `OpenLoopViewModel` cancels at **120 s** (`select { onTimeout }`). That is the emulator timeout path.

Samsung hardware codecs often **throw** at dequeue or configure instead of spinning — same root cause, different symptom (`3a506c4e`, `b09e527`).

---

## Logcat evidence (repro: trim churn + tab/direction toggles)

**Confirmed 2026-06-03** via full UI E2E + `adb logcat` on build **1.0.18** (PR #62 branch),
**Android 17 (OS) emulator (API 37, x86_64, software `c2.*` codecs)**. Exact counts from a single
editor session that ended in a 120 s timeout:

| Marker | Count | Meaning |
|--------|------:|---------|
| `Created component [c2.*]` (19 avc-dec / 5 avc-enc / 7 goldfish-dec) | **31** | Codecs recreated on nearly every editor interaction — far more than ~4–6 needed for a couple of reverses |
| `keep callback message for reclaim` | **77** | Resource manager repeatedly reclaiming codecs — slot pressure |
| `sending message to a Handler on a dead thread` (`MediaCodec.postEventFromNative`) | **22** | Codec released while native still posts events — lifecycle race |
| `frameIndex not found` / `received null buffer` / `discarded an unknown buffer` | **71** | Buffers on flushed or dead sessions — starvation |

At pass-1 start on a failed run:

- `Codec2Client: setOutputSurface -- failed to set consumer usage (6/BAD_INDEX)`
- `Discard frames from previous generation`
- `MediaCodec: keep callback message for reclaim`

Pass 1 opens into a **churned, reclaim-pressured** subsystem and never gets serviced.

---

## Why it feels random

| Session (2026-06-03 E2E) | Clip | Behavior |
|---------|------|----------|
| `gen=7` — fresh clip, single Loop tap, left undisturbed | 2.4 s | `reverse.complete attempts=1` in **4.4 s** — one clean codec generation, slot available |
| `gen=2` — rapid direction toggles during overlay | 9.3 s | **120 s timeout** |
| `gen=10` — trim-handle drags + tab switches before reverse | 2.7 s | **120 s timeout** |

Success correlates with **low editor churn before reverse**, not clip length and not luck
(a 2.4 s clip succeeded; a churned 2.7 s clip timed out).

---

## Fix layers (ordered)

### Shipped / in flight

| Layer | Change |
|-------|--------|
| PR #62 | Map benign teardown to `CancellationException`; Samsung retry; `openSurfaceCodecPipeline` |
| **Fix #2 (this branch)** | Reverse loading bumps `playerEpoch` so `ExoPlayer.release()` (DisposableEffect `onDispose`) drops the preview decoder — replaces the old `stop()`-only `teardownPlayerForReversePreview`, which never freed the slot |
| **Fix #2 (this branch)** | `PRE_REVERSE_CODEC_SETTLE_MS` before pass 1 on **all** devices — covers the unobservable OS slot-reclaim window |
| **Finding 3 (this branch)** | Entry-bump guard: skip `playerEpoch++` when `exoPlayer.mediaItemCount == 0` (first reverse on entry holds no preview decoder) — removes one wasteful player recreation |
| **Finding 4 (this branch)** | Removed the unused deprecated `SAMSUNG_PRE_REVERSE_CODEC_SETTLE_MS` alias; fixed test import order |

### Review findings — resolution status (2026-06-03)

| # | Finding | Status |
|---|---------|--------|
| 1 | Doesn't address the broader rebind storm | **Partially.** Trim-drag rebinds already coalesce (the playlist `LaunchedEffect` re-keys on trim and `delay(PLAYLIST_DEBOUNCE_MS)` cancel-restarts, so a drag collapses to one rebind). The entry-bump guard (finding 3) removes another recreation. Much of the measured "31 codecs" was inflated by *failed* trim-handle drags (see Memory `project-trim-handle-touch-sensitivity-bug`). **Remaining:** serialized codec ownership (item A below) — larger, deferred. |
| 2 | 400 ms settle is heuristic, not a handshake | **Resolved as a documented tradeoff.** A true handshake can only observe `release()` returning, **not** OS slot reclaim (unobservable from app code), and would only help slow devices if its timeout were *longer* than today's settle — i.e. longer waits. Kept the settle (now all-devices) with explicit intent in `PRE_REVERSE_CODEC_SETTLE_MS`. See item B below for the opt-in handshake if longer slow-device waits are acceptable. |
| 3 | Double player-create on editor entry | **Fixed** — `mediaItemCount == 0` guard. |
| 4 | Unused deprecated alias + import order | **Fixed.** |

### Still required (larger, deferred — needs design sign-off)

- **A. Serialize codec ownership** — at most one of {preview, reverse pass 1, reverse pass 2} holding
  video codecs at a time (single owner/mutex). The structural cure; biggest change.
- **B. (Optional) Release handshake** — have the reverse await an explicit "preview player released"
  signal from the editor (with a generous bounded timeout) instead of the fixed settle. Only worth it
  if longer waits on slow devices are acceptable; adds Compose↔ViewModel coupling + deadlock risk
  (mitigate with timeout fallback).
- **C. User-facing copy + latency** — replace jargon (“ping-pong”) with actionable text (e.g.
  *“Couldn't build the reverse — tap to retry”*) and lower the 120 s time-to-fallback; should be rare
  once A lands.

---

## Key files

| File | Role |
|------|------|
| `BoomerangEditorScreen.kt` | `playerEpoch`, playlist `LaunchedEffect`, reverse-loading gate |
| `EditorPlaylistBind.kt` | Hold-playlist policy; reverse must use full player release |
| `VideoReverser.kt` | 2-pass reverse, `runDecodeEncodeLoop`, pre-reverse settle |
| `OpenLoopViewModel.kt` | `ensureReversedSegment`, 120 s timeout, fallback forward |
| `MediaCodecLifecycle.kt` | PR #62 symptom handling |

---

## Acceptance criteria (disease fixed)

- [ ] Logcat during reverse on emulator: **O(1)** `Created component` per reverse, not O(interactions)
- [ ] Reverse completes after moderate trim/tab churn without 120 s timeout
- [ ] Samsung: zero new `3a506c4e` / `b09e527` on fixed build line (see verification doc)
- [ ] No `reverse_preview_failure` for benign cancel after user abandons reverse mid-flight

---

## Testing

- Manual: repro table above on the Android 17 (OS) emulator (API 37) + Samsung S24-class (SM-S921B saw `3a506c4e`)
- Automated: `EditorPlaylistBindTest`, `MediaCodecLifecycleTest`, `VideoReverserTest` (device/emulator)
- Crashlytics: `/crashlytics-triage` on `1.0.18+` after ship
