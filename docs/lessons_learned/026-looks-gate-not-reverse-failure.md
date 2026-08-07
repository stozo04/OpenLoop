# Lesson 026 — Don't close the Looks (`effectsPreviewEnabled`) gate on reverse-preview failure; treat OEM reverse quirks and memory pressure as separate signals

> Origin: first 1★ Play review (Galaxy S20 FE 5G / Android 13 / 1.0.30) — "when it comes to
> choosing filters it says preview unavailable (low memory or reverse error) but my phone have lots
> of space & memory." Confirmed by code path, not on-device A/B of that handset.

## What went wrong

`markReversePreviewFailed` (timeout / codec throw during editor reverse) did three things:

1. Fell back to `BoomerangMode.FORWARD` (correct — Lesson 020 degrade path).
2. Set `effectsPreviewEnabled = false` and reset the look to `ORIGINAL`.
3. Surfaced a Looks banner: `"Preview unavailable (low memory or reverse error)"`.

Looks preview is a Media3 `setVideoEffects` call on the **forward** player. It does not need the
reversed artifact. Closing the gate conflated two unrelated failure modes:

- **Samsung / OEM reverse failures** (codec Surface starvation, timeouts, empty mux — Lessons 020 /
  023) — common on Exynos/Snapdragon Galaxy devices, **not** "the phone is out of RAM."
- **Real memory pressure** (`ActivityManager.MemoryInfo.lowMemory` / API ≤ 33
  `TRIM_MEMORY_RUNNING_LOW|CRITICAL`) — the legitimate WS-3 reason to skip
  `DefaultVideoFrameProcessor`.

The banner also said "low memory," so users with free storage + RAM blamed the app for lying and
uninstalled. A second foot-gun amplified this on API ≤ 33: reverse encode is the heaviest transient
allocator in the editor, so `onTrimMemory(RUNNING_LOW)` during "Trimming…/Loopifying…" permanently
disabled Looks for the rest of the session even when reverse later succeeded.

## Pattern

- **Reverse failure → forward fallback only.** Keep `effectsPreviewEnabled` and any selected look.
  Surface reverse trouble via the existing snackbar / Loop-tab retry, not by disabling Looks.
- **Looks gate = memory pressure only.** Close on `isLowMemoryNow()` / foreground trim levels; never
  as a side effect of `ensureReversed` failing.
- **Ignore trim-memory while reverse preview is loading.**
  `previewLoading.isReversePreviewLoading()` → no-op in `onTrimMemory()`. That window's
  `RUNNING_LOW` is expected OEM noise, not a Looks brick.
- **Gate must be reopenable.** Once pressure clears, `updateFilter` / switching to the Looks tab
  re-probes and reopens. Keep chips tappable while the hint is showing — a disabled strip makes
  "try again" unreachable.
- **Copy must not mention reverse or storage.** Users read "memory" as disk space. Prefer:
  "Looks preview paused — device is low on memory. Tap a look to retry."

## Detection checklist

- Grep `effectsPreviewEnabled = false` — every assignment must be a memory-pressure path
  (`onTrimMemory`, `isLowMemoryNow` at editor entry / `updateFilter`), never
  `markReversePreviewFailed` / reverse timeout / reverse catch.
- Grep the Looks `disabledHint` string — must not say "reverse."
- In `onTrimMemory()`, confirm an early return when `previewLoading.isReversePreviewLoading()`.
- Manual / review repro: force reverse timeout → snackbar + forward mode, **Looks chips still work**;
  force `lowMemory` → banner + look not applied; clear `lowMemory` → tap a look → preview applies.

## Reference

- [Manage your app's memory](https://developer.android.com/topic/performance/memory) —
  `MemoryInfo.lowMemory` / `onTrimMemory` (legacy foreground levels undelivered on API 34+).
- Builds on [[020-imported-clips-hdr-codec-and-reverse-failure-recovery]] (degrade, don't wedge) and
  [[023-media-pipeline-stages-must-count-output-samples]] (Samsung reverse quirks ≠ OOM).
- `ui/OpenLoopViewModel.kt` (`markReversePreviewFailed`, `onTrimMemory`, `updateFilter`, `switchTab`),
  `ui/BoomerangEditorScreen.kt` (Looks `disabledHint`), `ui/MemoryPressure.kt`.
