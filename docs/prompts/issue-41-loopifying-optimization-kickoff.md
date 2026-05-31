# Issue #41 — Loopifying Optimization Kickoff Prompt

Copy everything below the line into a fresh Claude Code / Cursor session with the OpenLoop repo mounted. This kickoff implements **[Issue #41 — Optimize Loopifying render pipeline](https://github.com/stozo04/OpenLoop/issues/41)** (quality-preserving performance).

---

## Session Prompt — Issue #41 Loopifying Performance

You are working on **OpenLoop** — an open-source Android camera app (Kotlin/Jetpack Compose) for speed-controlled video loops. Repo: `stozo04/OpenLoop`. Owner: Steven Gates (@stozo04).

**Goal:** Make **"Loopifying…"** faster without reducing share/upload quality. Owner explicitly rejected a user-facing Fast/720p toggle — **quality first**.

**Constraints:**

- 30 s max clip (`MAX_RECORDING_MS`)
- Primary test device: **Pixel Fold 10**
- Background survival is **Issue #40** — do not conflate; perf work applies inside `VideoProcessor` / `VideoReverser` regardless of caller (ViewModel or Worker)

**Blockers before Tier 1 code:**

- **Issue #29** — manual QA verifying per-item `SpeedChangeEffect` on export (correctness gate)
- **Baseline timings** — measure before optimizing (Issue #41 measurement matrix)

## Critical Rule — Do Not Trust Your Training Data

Web-search `developer.android.com` for: Media3 Transformer `DefaultEncoderFactory` CodecDB Lite, `EditedMediaItem.Builder.setFrameRate()`, Transformer rescaling, video sharing bitrate guidance, ADPF thermal API. Verify Media3 **1.10.1** APIs against `developer.android.com/jetpack/androidx/releases/media3` — OpenLoop uses BOM 2026.05.01 / Media3 1.10.1 per `CLAUDE.md`.

## Authoritative spec

**Issue #41 on GitHub** — tier list, forbidden regressions, acceptance criteria.

**Must-read lessons:**

- **018** — seam drops by sequence position (do not touch)
- **019** — rotation strip/re-stamp (verify on Fold portrait after changes)
- **020** — HDR import failure recovery
- **021** — **never downscale inside VideoReverser** (Surface mismatch → green macroblocks)

## Before Writing Any Code — Read These Files

1. **`CLAUDE.md`**
2. **Every file in `docs/lessons_learned/`**
3. **`docs/DEFINITION_OF_DONE.md`**
4. **`docs/active/boomerang-rollout/RESEARCH-reverse-video.md`**
5. **`app/src/main/java/.../media/VideoProcessor.kt`**
6. **`app/src/main/java/.../media/VideoReverser.kt`**
7. **`app/src/main/java/.../media/BoomerangSequence.kt`**
8. **`app/src/androidTest/.../media/VideoReverserTest.kt`**
9. **Issue #29**, **Issue #41**

## Phase 1 — Branch + baseline + timings

```powershell
git checkout main
git pull --rebase
git checkout -b feature/issue-41-loopifying-perf
.\gradlew.bat clean assembleDebug --console=plain; echo "EXIT=$LASTEXITCODE"
```

**Before any optimization**, record baseline wall times on **Pixel Fold 10** (post in PR / Issue #41):

| # | Source | Trim | Mode | Speed |
|---|--------|------|------|-------|
| 1 | Camera HD 10 s | full | F→R | 2× |
| 2 | Camera HD 30 s | full | F→R | 2× |
| 3 | 4K SDR import 10 s | full | F→R | 2× |
| 4 | 4K HDR import 10 s | full | F→R | 2× |
| 5 | Camera HD 10 s | full | F→R | 0.5× (#29 correctness) |

Run **#29 manual QA** first on row 5 — inspect **saved gallery MP4**, not preview.

## Phase 2 — Web-verify Media3 APIs

Search and confirm signatures for Media3 1.10.1:

- `DefaultEncoderFactory.Builder.setEnableCodecDbLite(true)`
- `EditedMediaItem.Builder.setFrameRate(int)`
- `Presentation.createForShortSide(int)`
- `Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL`

## Phase 3 — Implementation order (Issue #41 tiers)

### PR 1 (or commit 1): Tier 2A — Benchmark test

Add `@LargeTest` `LoopifyingBenchmarkTest` (androidTest):

- Synthetic 720p 3 s fixture → F→R @ 2× → assert output exists
- Log `System.currentTimeMillis()` delta — generous upper bound for CI emulator
- Does not gate CI on tight timing initially

### PR 2: Tier 1B — CodecDB Lite (low risk)

In `VideoProcessor.runTransformer()`:

```kotlin
Transformer.Builder(context)
    .setEncoderFactory(
        DefaultEncoderFactory.Builder(context)
            .setEnableCodecDbLite(true)
            .build()
    )
```

Measure composition-step time before/after on Fold row 1.

### PR 3: Tier 1C — Frame rate cap when speed > 1×

In `VideoProcessor` when building `EditedMediaItem`:

- If `speed > 1f`: `.setFrameRate((sourceFps / speed).toInt().coerceAtLeast(24))`
- If `speed <= 1f`: **do not set** (slow-mo needs frames)

Test at 3× — measurable win; 1× and 0.5× unchanged.

### PR 4: Tier 1A — Pre-scale before reverse ⭐ (largest win, most complex)

**Only when** `sourceShortSide > MAX_OUTPUT_SHORT_SIDE` (1080):

1. New package-private or internal function `scaleSourceForReverse()` using Media3 Transformer:
   - Trim clip (same window)
   - HDR tone-map
   - `Presentation.createForShortSide(1080)`
   - Output to `scratch/scaled_<uuid>.mp4`
2. Pass scaled file to `VideoReverser.reverse(scaled, 0, trimDurationMs)` — trim window now full file
3. Final composition export unchanged

**Forbidden:** downscaling inside `VideoReverser` (Lesson 021).

**Regression tests:**

- [ ] `VideoReverserTest` unchanged behavior on ≤1080p sources
- [ ] New test: >1080p synthetic → reverse input dimensions ≤1080p
- [ ] Portrait rotation Fold manual
- [ ] HDR import manual (Lesson 020)

**Success:** Rows 3–4 ≥ **50% faster** vs baseline; side-by-side share quality indistinguishable.

### Defer (owner quality preference)

- Lower pass-1 intermediate bitrate (Tier 3)
- Thermal 720p adaptive cap (needs owner sign-off for v2)
- HEVC output — rejected

## Phase 4 — Forbidden changes checklist

Before PR merge, confirm **none** of:

| Change | Lesson |
|--------|--------|
| Downscale in VideoReverser | 021 |
| Remove seam drops | 018 |
| Skip HDR tone-map | 020 |
| Remove hardware encoder selection | — |
| Move speed to Composition-level without #29 failure observed | #29 |

## Phase 5 — Tests + DoD

```powershell
.\gradlew.bat testDebugUnitTest connectedDebugAndroidTest :app:lintDebug assembleRelease --console=plain
```

- All existing media tests green
- New benchmark + pre-scale tests
- Manual matrix rows re-run with after timings in PR table
- Visual: no seam stutter, no green blocks, no rotation bug on Fold portrait
- `./gradlew :app:lintDebug` zero new errors
- Screenshot: Loopifying progress on long 4K import (before/after times in PR description)

## Phase 6 — PR strategy

Prefer **stacked PRs** or single PR with logical commits:

1. Benchmark only
2. CodecDB Lite
3. setFrameRate
4. Pre-scale (separate review — highest risk)

Link **Closes #41** on final merge PR or each sub-PR referencing issue.

## Behavioral Rules

- **Measure first** — no speculative optimization without baseline row
- **#29 before speed architecture change** — never move to Composition-level speed without observed failure
- **Minimal scope** per commit — easier bisect if regression
- Issue #40 merge: ensure Worker calls same `VideoProcessor` path (no duplicate logic)

## When to Stop and Ask Steven

- Pre-scale breaks HDR or rotation on Fold — needs design review
- CodecDB Lite changes visual output noticeably — revert and document device
- #29 fails (second half wrong speed) — fix correctness before any Tier 1 perf
- 4K baseline already fast enough on Fold — owner may deprioritize 1A for launch
- Thermal v2 adaptive 720p requested — needs explicit approval

## Out of Scope

- Issue #40 WorkManager / notifications
- FFmpegKit / native reverse
- User-facing quality/speed toggle
- Removing two-pass reverse (wait for Media3 upstream)
