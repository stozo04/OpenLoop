# Editor memory pressure & OOM hardening

> **Status:** Spec — sign-off pending. No code written yet.  
> **Owner:** Steven Gates  
> **Branch (when started):** `feature/editor-memory-oom` (suggested)  
> **Crashlytics:** Issue `ef2823cfeeb3f1f59ef7308c266a7110` · App **1.0.8 (8)** · Samsung **SM-S926B** · Android **16** · 2026-06-03

---

## 1. Problem statement

Production Crashlytics reports a fatal **`OutOfMemoryError`** on the **256 MB Java heap** after a long editor session. The fatal stack frame (`DisplayInfo.createFromParcel` on a system display callback) is a **last-straw allocation**, not the root defect. Session evidence points at **media pipeline retention** inside OpenLoop:

| Signal | Interpretation |
|--------|----------------|
| Breadcrumb `reverse_preview_failure: Timed out after 120s` | Preview reverse hit [`REVERSE_PREVIEW_TIMEOUT_MS`](../../../app/src/main/java/io/github/stozo04/openloop/ui/OpenLoopViewModel.kt) and was cancelled. |
| Crash ~14 minutes later | Slow heap growth after timeout, not an instant failure at reverse start. |
| Dozens of `ExoPlayer:Loader:ProgressiveMediaPeriod` threads | Far more loaders than one editor playlist (1–2 items) should need — suggests **stacked or leaked** media periods. |
| Main thread in `MediaMetricsListener.reportTrackChangeEvent` | ExoPlayer preview active under extreme memory pressure. |
| Device class | Samsung Exynos — already special-cased for reverse timeout, codec settle, and preview resolution cap ([`DeviceMediaHints.kt`](../../../app/src/main/java/io/github/stozo04/openloop/media/DeviceMediaHints.kt)). |

Today the editor can remain usable after reverse failure (Lesson 020), but **memory is not bounded** across:

1. **Cancelled / timed-out reverse jobs** — coroutine cancel does not guarantee prompt MediaCodec teardown on some devices; pass-1 `_intermediate_*.mp4` files may linger if cancel lands mid-pass.
2. **ExoPlayer playlist churn** — [`BoomerangEditorScreen`](../../../app/src/main/java/io/github/stozo04/openloop/ui/BoomerangEditorScreen.kt) calls `setMediaItems` + `prepare()` on many state changes (mode, trim, seam, reverse readiness, speed debounce is separate).
3. **Video effects preview** — non-`ORIGINAL` filters call `setVideoEffects`, routing through `DefaultVideoFrameProcessor` (extra GL/native retention; documented HDR seam constraints).
4. **Gallery thumbnails** — each grid tile decodes a full bitmap via `BitmapFactory.decodeFile` with no size cap ([`GalleryScreen.kt`](../../../app/src/main/java/io/github/stozo04/openloop/ui/GalleryScreen.kt)).

Generic Crashlytics guidance that blames **`DisplayInfo` parceling** or the package name `io.github.stozo04.openloop` as a third-party library is **incorrect** for this incident. Profiling should target ExoPlayer, MediaCodec, effects, and bitmaps.

---

## 2. Goals

1. **No OOM** during a realistic “bad day” editor session on a 256 MB–class heap device after reverse timeout (repro below).
2. **Deterministic teardown** when preview reverse is cancelled, times out, or the user leaves the editor.
3. **Bounded ExoPlayer churn** — at most one prepared playlist worth of loaders under normal editor use.
4. **Graceful degradation** — after reverse failure or on low-memory signals, preview stays usable without effects-heavy paths piling on.
5. **Observable regression guard** — tests and/or manual checklist so memory fixes do not rot.

---

## 3. Non-goals

- Raising `android:largeHeap` as the primary fix (masks leaks; may hurt Play vitals narrative).
- Rewriting reverse algorithm or replacing MediaCodec reverse with a cloud/off-device path.
- Full gallery redesign (Coil/Glide migration) — only **bounded thumbnail decode** in scope unless profiling proves gallery is the dominant leak in editor-only repro.
- Fixing unrelated system display bugs.

---

## 4. Success criteria (acceptance)

| # | Criterion | Verification |
|---|-----------|--------------|
| A1 | After **120s reverse timeout** on Samsung (or test harness), user can interact with editor (mode / speed / looks / trim) for **≥15 minutes** without process death | Manual on **SM-S926B** or equivalent; Memory Profiler heap stable or sawtooth without monotonic climb to cap |
| A2 | Leaving editor (`backToTrim`, discard, save → Processing) releases ExoPlayer; heap dump shows **no** retained `ExoPlayerImpl` from editor | LeakCanary (optional) or Android Studio heap diff after navigation |
| A3 | Cancelling reverse (timeout, mode → `FORWARD`, `cancelReverseJob`) deletes `_intermediate_*.mp4` under scratch within **30s** and does not leave >1 `_intermediate_` file | Instrumented or device test; `adb shell ls` on `cache/.../scratch/reversed/` |
| A4 | Rapid direction/trim changes do not create **>4** concurrent `ProgressiveMediaPeriod` threads per editor session (2-item playlist + margin) | Logcat thread dump or debugger during stress script |
| A5 | With `reverseFailed == true`, non-original looks do not enable `setVideoEffects` (forward preview only, or explicit user messaging) | UI test + manual |
| A6 | Gallery grid with **N ≥ 20** videos does not hold **N** full-resolution bitmaps in heap at once | Heap dump after scroll; optional `inSampleSize` / max dimension cap |

All shipping work must pass [`docs/DEFINITION_OF_DONE.md`](../../DEFINITION_OF_DONE.md) (build, lint, tests, on-device run).

---

## 5. Scope — four workstreams

Workstreams are ordered by **impact on the reported crash** (P0 → P2). They may ship as one PR or thin follow-ups.

### WS-1 (P0): Reverse cancel — codecs + scratch files

**Problem:** [`OpenLoopViewModel.ensureReversedSegment`](../../../app/src/main/java/io/github/stozo04/openloop/ui/OpenLoopViewModel.kt) uses `select { worker.onAwait; onTimeout(120s) { worker.cancel() } }` because wedged MediaCodec on Samsung may never return from `withTimeoutOrNull`. Cancellation propagates to [`VideoReverser.reverse`](../../../app/src/main/java/io/github/stozo04/openloop/media/VideoReverser.kt), which deletes `_intermediate_` in `finally` **only when the coroutine unwinds**. A wedged native call may delay or prevent unwind, leaving intermediates and codec buffers alive while ExoPlayer restarts.

**In scope:**

- **Cooperative cancellation checkpoints** in `VideoReverser` (pass 1 & 2 loops): `coroutineContext.ensureActive()` between frames / muxer steps; bounded wait on codec dequeue with cancel awareness where safe.
- **Explicit cleanup on timeout path** in ViewModel (not only `worker.cancel()`):
  - Increment `reverseGeneration` (already done in `cancelReverseJob`).
  - Invoke a small **`ReverseScratchJanitor`** API: delete `_intermediate_*.mp4` in `scratchDir` for the active session (glob), without deleting completed cache-key `.mp4` outputs.
- **Optional:** track active reverse in `VideoReverser` / processor with an `AtomicReference` to current intermediates so janitor can delete by path even if the job is wedged.
- **Samsung settle:** keep [`SAMSUNG_POST_TRANSFORM_CODEC_SETTLE_MS`](../../../app/src/main/java/io/github/stozo04/openloop/media/DeviceMediaHints.kt) but consider **releasing** any Transformer-held codec before reverse retry after timeout (audit `Media3VideoProcessor.ensureReversed`).
- **Logging:** Crashlytics breadcrumb `reverse_preview_cleanup` with bytes deleted / intermediate count (no PII).

**Out of scope:** Changing 120s timeout value (separate tuning issue).

**Primary files:**

- `app/src/main/java/.../media/VideoReverser.kt`
- `app/src/main/java/.../media/VideoProcessor.kt` / `Media3VideoProcessor`
- `app/src/main/java/.../ui/OpenLoopViewModel.kt`
- `app/src/androidTest/.../media/VideoReverserTest.kt` (extend intermediate cleanup assertions)

**Pattern references:** Lesson 013 (cancellation), Lesson 020 (reverse failure must not wedge editor).

---

### WS-2 (P0): ExoPlayer loader stacking — debounce, serial prepare, release

**Problem:** Editor `LaunchedEffect(mode, reversedFile, trimStartMs, trimEndMs, seamMs, reversePreviewLoading)` rebinding can overlap `prepare()` calls if inputs change faster than ExoPlayer tears down prior `ProgressiveMediaPeriod` instances. Crash session showed **many** loader threads.

**In scope:**

- **Single-flight playlist update:** guard with a `Mutex` or monotonic `playlistGeneration` — only the latest bind runs `setMediaItems` + `prepare()`; superseded jobs call `stop()` / `clearMediaItems()` without preparing stale playlists.
- **Debounce** rapid trim/mode/seam changes (~100–200 ms, similar to [`SPEED_DEBOUNCE_MS`](../../../app/src/main/java/io/github/stozo04/openloop/ui/BoomerangEditorScreen.kt)) so slider drags do not N× prepare.
- **Before `prepare()`:** `exoPlayer.stop(); exoPlayer.clearMediaItems()` when replacing playlist (already partially done when empty; make consistent on every rebind).
- **Lifecycle:** confirm `exoPlayer.release()` in `DisposableEffect` on leave composition (already present); add **`onDispose` before re-entering editor** from Trim if composition is preserved — verify Trim does not hold a second player concurrently.
- **Trim screen:** [`TrimScreen`](../../../app/src/main/java/io/github/stozo04/openloop/ui/TrimScreen.kt) has its own `remember { ExoPlayer }` — ensure navigating Trim → Editor disposes Trim player **before** Editor prepares (composition order / `DisposableEffect` ordering).

**Out of scope:** Replacing ExoPlayer with TextureView + single decoder (too large).

**Primary files:**

- `app/src/main/java/.../ui/BoomerangEditorScreen.kt`
- `app/src/main/java/.../ui/TrimScreen.kt` (audit concurrent players)

**Detection:** Thread count / heap diff while scrubbing trim handles and toggling Direction tab for 2 minutes post-timeout.

---

### WS-3 (P1): Video effects degradation after reverse failure / low memory

**Problem:** `setVideoEffects` allocates a frame-processing pipeline. After reverse timeout, user may still apply Looks while forward-only preview runs — compounding memory from WS-1/2.

**In scope:**

- When [`EditorTabState.reverseFailed`](../../../app/src/main/java/io/github/stozo04/openloop/ui/OpenLoopUiState.kt) and mode still `needsReverse`:
  - **Disable** non-`ORIGINAL` looks in UI **or** apply effects only at export (preview stays unfiltered) with one-line snackbar already used for forward fallback.
- **`ActivityManager.isLowMemory()` / `ComponentCallbacks2.onTrimMemory`:** optional hook in `MainActivity` or ViewModel to set `effectsPreviewEnabled = false` until editor reset.
- Do **not** call `setVideoEffects(emptyList())` for ORIGINAL (existing comment: empty list still spins `DefaultVideoFrameProcessor` — keep current “only call when non-empty” rule).

**Out of scope:** New looks engine; export-time effects remain required for saved boomerang.

**Primary files:**

- `app/src/main/java/.../ui/BoomerangEditorScreen.kt`
- `app/src/main/java/.../ui/OpenLoopViewModel.kt` (flag on `EditorTabState` or derived)
- `app/src/main/java/.../media/VideoFilter.kt`

**UX:** Looks tab shows dimmed presets + “Preview unavailable after reverse error” (copy TBD).

---

### WS-4 (P2): Gallery thumbnail decode bounds

**Problem:** `BitmapFactory.decodeFile` at full image resolution per visible tile; scrolling a large gallery retains many bitmaps in heap.

**In scope (minimal):**

- Decode with **`inSampleSize`** or `BitmapFactory.Options` targeting max edge **~220px** (2× 110dp grid cell) — compute sample size from intrinsic dimensions once per file.
- **`remember(video.thumbnailPath)`** already caches per tile; ensure bitmap is not re-decoded on recomposition beyond key change.
- **Optional:** small LRU (e.g. 24 entries) in `VideoStorageRepository` or composable-level — only if profiling shows repeated decode churn.

**Out of scope:** Coil dependency (unless owner approves manifest/size tradeoff).

**Primary files:**

- `app/src/main/java/.../ui/GalleryScreen.kt` (`VideoThumbnailCard`)
- Optional helper in `app/src/main/java/.../media/` or `ui/` (e.g. `ThumbnailDecoder.kt`)

**Note:** Lower priority for **editor-only** OOM repro; include if combined “gallery → import → editor stress” is in QA matrix.

---

## 6. Architecture fit

```
┌─────────────────────────────────────────────────────────────┐
│ OpenLoopViewModel                                           │
│  ensureReversedSegment ──timeout/cancel──► ReverseScratch   │
│       │                              Janitor + generation   │
│       ▼                                                     │
│  VideoProcessor.ensureReversed ──► VideoReverser (IO)       │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│ BoomerangEditorScreen                                       │
│  single ExoPlayer (remember)                                │
│  playlist bind: debounce + single-flight prepare            │
│  effects: gated on reverseFailed / lowMemory                │
└─────────────────────────────────────────────────────────────┘
```

Aligns with [`docs/PRD-mission-control.md`](../../PRD-mission-control.md) media layer and existing Samsung hints. Does not change the boomerang state machine — only resource discipline on `Trim`, `BoomerangEditor`, and `Gallery`.

---

## 7. Implementation plan (ordered)

| Step | Workstream | Task |
|------|------------|------|
| 1 | WS-1 | Add `ReverseScratchJanitor` + unit tests for glob delete semantics (preserve cache-key outputs). |
| 2 | WS-1 | Wire timeout and `cancelReverseJob()` to janitor; add `ensureActive` in `VideoReverser` hot loops. |
| 3 | WS-2 | Introduce playlist bind debounce + generation token in `BoomerangEditorScreen`. |
| 4 | WS-2 | Audit Trim ↔ Editor ExoPlayer overlap; fix if both can be alive. |
| 5 | WS-3 | Add `reverseFailed` / low-memory gate for `setVideoEffects` + Looks tab UX. |
| 6 | WS-4 | Bounded thumbnail decode in `VideoThumbnailCard`. |
| 7 | All | Manual repro + Memory Profiler validation; optional `androidTest` stress (rapid tab changes). |
| 8 | Docs | Add Lesson **023** on OOM / ExoPlayer loader stacking after ship. |

---

## 8. Testing plan

### Manual (required — matches Crashlytics device class)

1. Samsung or Exynos phone, **import** a 20–30s 1080p+ clip (or record).
2. Trim → Editor, mode **Forward then Reverse**; wait **120s** for timeout breadcrumb.
3. For **15+ minutes**: toggle direction, scrub speed, try looks, nudge trim handles, retry reverse once.
4. Memory Profiler: heap should not monotonically approach 256 MB; thread count should not grow `ProgressiveMediaPeriod` without bound.
5. Navigate **Editor → Trim → Editor** and **discard**; confirm heap drops editor/player retained size.

### Automated

| Test | Type | Asserts |
|------|------|---------|
| Reverse cancel deletes `_intermediate_` | `VideoReverserTest` / androidTest | Extend existing “no intermediate files” case to **cancel mid-pass-1** |
| Timeout triggers janitor | JVM ViewModel test with fake processor | Fake hangs; advance time; verify janitor called (inject interface) |
| `reverseFailed` disables effects | JVM or Compose UI test | `setVideoEffects` not invoked when flag set (spy listener / fake) |
| Thumbnail sample size | JVM pure function test | 4000×3000 JPEG path → decoded bitmap max edge ≤ cap |

### Crashlytics

- Custom keys on OOM-adjacent events: `reverse_failed`, `editor_duration_sec`, `playlist_rebind_count` (if instrumented).
- Watch issue `ef2823cfeeb3f1f59ef7308c266a7110` for 30 days post-release.

---

## 9. Risks & mitigations

| Risk | Mitigation |
|------|------------|
| Aggressive `clearMediaItems` causes preview flicker | Debounce + single-flight; only clear when URI/clip set actually changes |
| Janitor deletes wrong file | Only `_intermediate_*` prefix; never `sha1-*.mp4` cache outputs |
| Disabling looks after reverse failure feels punitive | Copy explains forward-only preview; export still applies look |
| Wedged codec still ignores cancel | Document as known Samsung limit; janitor + don’t start ExoPlayer until reverse loading clears (existing gate) |

---

## 10. Open questions

| ID | Question | Default if no answer |
|----|----------|-------------------|
| Q-1 | Ship WS-1+2 in one PR or split? | One PR if review size OK (~400 LOC) |
| Q-2 | Adopt LeakCanary in debug builds for this work? | Optional debug-only dependency |
| Q-3 | Exact thumbnail max edge — 220px vs 256px? | 256px long edge |
| Q-4 | Should retry reverse after timeout be blocked until user taps “Retry” (no auto re-entry on mode change)? | Keep current mode-change retry; monitor Crashlytics |
| Q-5 | Instrument `playlist_rebind_count` in production? | Yes via Crashlytics log on editor dispose |

---

## 11. References

- Crash session analysis (2026-06-03) — team chat / Crashlytics issue `ef2823cfeeb3f1f59ef7308c266a7110`
- [`docs/lessons_learned/020-imported-clips-hdr-codec-and-reverse-failure-recovery.md`](../../lessons_learned/020-imported-clips-hdr-codec-and-reverse-failure-recovery.md)
- [`docs/lessons_learned/013-media-start-failure-return-and-narrow-catch.md`](../../lessons_learned/013-media-start-failure-return-and-narrow-catch.md)
- [`docs/active/boomerang-rollout/RESEARCH-reverse-video.md`](../boomerang-rollout/RESEARCH-reverse-video.md)
- Android: [Manage memory](https://developer.android.com/topic/performance/memory-overview), [ExoPlayer debugging](https://developer.android.com/media/media3/exoplayer/debugging)

---

## 12. Sign-off

| Role | Name | Date | OK |
|------|------|------|-----|
| Owner | Steven Gates | | ☐ |

Once signed off, implementation follows §7 and [`docs/DEFINITION_OF_DONE.md`](../../DEFINITION_OF_DONE.md).
