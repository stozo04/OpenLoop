# Slice 05 — Looks tab (filters)

> **Branch:** `feature/boomerang-slice-05-looks-tab`
> **Depends on:** slice 04 (Speed tab).
> **Unblocks:** slice 06 (Share sheet).
>
> **Scope pivot (decided 2026-05-29):** this slice *replaces* the originally-planned
> Repetitions tab with a **Looks (filters) tab**. Reps did nothing visible inside the app
> (the preview and gallery both loop forever, so 1 rep and 4 reps look identical — reps only
> changes the exported file length), and it dragged in a 60-second-cap UI for a rare edge case.
> Filters are visible *live* in the preview, are the single most "social media" feature, and
> ride the exact effects pipeline our speed control already proved. Reps is deferred to a
> possible v1.5 (or folded into the Share slice if "loop N times for play-once players" earns
> its keep). The folder README's "what ships" list is updated to match.

---

## ⚠️ Start here — cold-session reality check

*Verified against the slice-04 branch + `developer.android.com` (the no-trust-training-data
rule). Read this before the rest of the doc.*

**Feasibility is already proven green.** The headline risk — "can a filter show *live* in the
preview, not just in the saved file?" — is resolved:

1. **Live preview works via `ExoPlayer.setVideoEffects(List<Effect>)`.** The Media3 Transformer
   docs explicitly support effects "for preview in ExoPlayer," using the **same `Effect`
   objects** as the render: `player.setVideoEffects(listOf(effect)); player.prepare()`. ([Transformations guide](https://developer.android.com/media/media3/transformer/transformations))
2. **The filter effects exist in `androidx.media3.effect`** and attach through the **same
   `Effects(audioProcessors, videoEffects=[…])`** object our speed code already builds:
   `RgbFilter` (grayscale/invert), `RgbAdjustment` (per-channel scale → warm/cool),
   `HslAdjustment` (saturation → pop). ([effect package](https://developer.android.com/reference/androidx/media3/effect/package-summary))
3. **Speed + filter compose cleanly.** In the preview, `setPlaybackSpeed` (a Player setting)
   and `setVideoEffects` (the effect list) are independent. At render, both live in the
   `videoEffects` list — speed is already there; we append the filter.

**What's reused (don't rebuild):**
- The **third tab slot already exists** as a disabled stub (slice 04): `TabBarItem(Icons.Filled.Timer,
  "Repetitions tab, coming soon", enabled=false, testTag="tab_reps")`. Repurpose it: enable it,
  swap the icon to a sparkle/wand, retag, wire `onSwitchTab(EditorTab.LOOKS)`.
- `BoomerangEditorContent` is **stateless** (mirrors `TrimScreenContent`): add `filter` +
  `onFilterChange` **with defaults** so slice-03/04 tests keep compiling.
- The `when (activeTab)` in `AnimatedContent` has **no `else`** (Lesson 014). Adding `LOOKS`
  makes it non-exhaustive → the compiler forces the new branch. Good.
- Effects already flow through `Media3VideoProcessor.speedEffects(speed)` → extend it to
  build `[speedEffect] + filterEffect(s)`.

**Latent bug to fix while here (from slice 04):** the back-discard gate only fires on a changed
*direction* (`if (mode != FORWARD_THEN_REVERSE) showDiscardDialog`). A user who changes **speed**
or **filter** and hits back loses it **silently**. Extend the gate to "any non-default
selection" (`mode != F→R || speed != 2.0f || filter != ORIGINAL`).

**Unknowns to close at build time (not blockers):**
- **Exact Media3 builder method names** (`RgbFilter.createGrayscaleFilter()`,
  `RgbAdjustment.Builder().setRedScale(…)`, `HslAdjustment.Builder().adjustSaturation(…)`) —
  the JS reference pages don't render via fetch; confirm signatures against the 1.10.1 source
  when coding. (Compilation is the safety net — a wrong name fails the build.)
- **On-device live `setVideoEffects` behavior** on the Pixel with our `PlayerView` (it's
  `@UnstableApi` — we already opt in). The implementation re-applies effects on the same
  rebind path as the playlist so the look is guaranteed to show; verify smoothness on device.
- **Thumbnail-vs-effect color fidelity** (see Technical deltas) — the chip thumbnail uses a
  Compose `ColorMatrix`; the preview/render use the Media3 effect. Derive both from the *same*
  per-look parameters so they match (avoid a new "preview lies about export").

**Process reminders (from slice 04):** web-search `developer.android.com` before any API claim;
clear the full Definition-of-Done gate (debug **and** release `BUILD SUCCESSFUL` exit 0 zero
`e:`, unit + instrumented tests, `lintDebug` clean, `zipalign -c -P 16` `(OK)`, run app +
screenshot, honest "couldn't verify" list). Device reality: in the slice-04 session the emulator
sat `offline`; only the physical Pixel 10 Pro Fold (`adb` serial `58271FDCG000XC`) was usable and
the owner ran manual QA — expect to defer `connectedDebugAndroidTest` + screenshot to the owner.

---

## Problem

After slice 04 the editor has Direction and Speed tabs. The boomerang is fun but visually flat —
every clip looks like the raw camera feed. For a "make something fun to post" app, a one-tap
**color look** (B&W, warm, cool, vibrant) is the highest-impact, most-expected creative control,
and it shows instantly in the preview.

This slice introduces:

1. **Looks tab** as the third (and final) tab in the bottom bar (icon = sparkle / `AutoAwesome`).
2. **A horizontal strip of filter chips**, each a **live thumbnail** of the current clip frame in
   that look. Five looks: **Original, B&W / Noir, Warm / Vintage, Cool / Moody, Vibrant Pop.**
   Default = Original.
3. **Live preview** that applies the chosen look instantly via `ExoPlayer.setVideoEffects`.
4. **Render pipeline** that bakes the chosen look into the saved MP4 (same effect object).

## Scope

### In scope
- Tab bar: repurpose the disabled Reps stub into an enabled **Looks** tab (Direction, Speed, Looks).
- `LooksTabContent`: a horizontal, centered strip of 5 filter chips. Each chip = a still frame
  from the trimmed clip rendered in that look (Compose `Image` + per-look `ColorFilter`/`ColorMatrix`)
  + a short label + a selection ring (`NeonPurple`) on the active one.
- `EditorTabState.filter: VideoFilter` (default `ORIGINAL`) and `EditorTab.LOOKS` added.
- A single `VideoFilter` model where each look owns its parameters once, and both its **Media3
  effect** (preview + render) and its **Compose `ColorMatrix`** (thumbnail) derive from those
  parameters so the chip can't lie about the result.
- Live preview: the player applies `filter.toMediaEffects()` via `setVideoEffects` on the same
  rebind path as the playlist.
- Render: `saveBoomerang()` passes the filter; `renderBoomerang` appends the filter effect to its
  `videoEffects` list next to the speed effect.
- One representative frame extracted from the trim (`MediaMetadataRetriever`, off the main thread,
  cached) to drive the thumbnails.

### Out of scope
- **Repetitions** — pivoted out of v1 (see scope-pivot note). Deferred / possibly folded into slice 06.
- Per-filter **intensity slider** (e.g., 0–100% warmth). v1 ships fixed-strength presets; add
  intensity later if QA wants it.
- **Custom LUT / `.cube` file** import. Presets only.
- **Stickers, text, overlays, frames** (`OverlayEffect`) — a bigger, separate feature.
- **Per-clip filters** (e.g., forward warm, reverse cool). One look applies to the whole boomerang.
- **Animated / video-thumbnail chips** — chips show a *still* frame, not a playing loop.

## Looks (the five presets)

Each look is defined by a small parameter set; the Media3 effect and the thumbnail matrix are both
built from it (single source of truth → thumbnail matches export).

| Look | Feel | Media3 effect (verify exact API) | Thumbnail `ColorMatrix` |
|------|------|----------------------------------|--------------------------|
| **Original** | unfiltered | *(none — empty effect list)* | identity |
| **B&W / Noir** | timeless, high-contrast | `RgbFilter.createGrayscaleFilter()` | `setToSaturation(0f)` |
| **Warm / Vintage** | cozy, golden-hour | `RgbAdjustment.Builder().setRedScale(1.15f).setBlueScale(0.85f).build()` | scale R×1.15, B×0.85 |
| **Cool / Moody** | cinematic, calm | `RgbAdjustment.Builder().setRedScale(0.85f).setBlueScale(1.15f).build()` | scale R×0.85, B×1.15 |
| **Vibrant Pop** | bright, energetic | `HslAdjustment.Builder().adjustSaturation(+40f).build()` | `setToSaturation(1.4f)` |

> Exact builder method names (`setRedScale`, `adjustSaturation`, etc.) and value ranges are
> **to be verified against Media3 1.10.1** when coding. Tune the warm/cool/pop numbers on-device so
> the look is pleasant and the thumbnail visually matches the preview/render.

## UX deltas

### Editor screen layout (additions)

```
┌────────────────────────────────────────────────┐
│  ←                                  [ ✓ ]      │
├────────────────────────────────────────────────┤
│              ┌──────────────────┐              │
│              │     preview      │              │   ~75%
│              │ (filter applied  │              │
│              │     LIVE)        │              │
│              └──────────────────┘              │
│                       1.6s                     │
├────────────────────────────────────────────────┤
│                                                │
│                  Choose a look                  │   tab title
│  ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐            │
│  │▦Or │ │▦B&W│ │▦Wrm│ │▦Cool│ │▦Pop│           │   live-thumbnail chips
│  └────┘ └════┘ └────┘ └────┘ └────┘            │   (selected = NeonPurple ring)
├────────────────────────────────────────────────┤
│        [ ≫ ]    [ ⚡ ]    [ ✨ ]               │   tab bar (Looks enabled)
│        Dir.     Spd      Looks                 │
└────────────────────────────────────────────────┘
```

### Looks strip

- Horizontal row of 5 chips, centered, evenly spaced; horizontally scrollable so it never clips
  on a narrow width.
- Each chip: a ~64 dp rounded thumbnail of the trim's representative frame in that look, with a
  short label beneath (Original / B&W / Warm / Cool / Pop).
- Selected chip: 2 dp `NeonPurple` ring + label brightens. Unselected: subtle `GlassWhiteBorder`.
- Tap → immediate selection + the big preview re-binds its effect live. Optional: light
  `HapticFeedbackType.SegmentTick` on selection (consistency with slice-04 detents).

### Tab bar

- Three slots: Direction (`≫`/`FastForward`), Speed (`⚡`/`Bolt`), **Looks** (`✨`/`AutoAwesome`).
- The Looks slot is the slice-04 Reps stub, now `enabled = true`, with the active-pill behavior the
  other tabs already have. Retag `tab_reps` → `tab_looks`; drop the "coming soon" description.

## Technical deltas

### `OpenRangUiState.kt`
```kotlin
enum class EditorTab { DIRECTION, SPEED, LOOKS }   // add LOOKS

data class EditorTabState(
    val mode: BoomerangMode = BoomerangMode.FORWARD_THEN_REVERSE,
    val speed: Float = 2.0f,
    val filter: VideoFilter = VideoFilter.ORIGINAL,   // NEW
    val activeTab: EditorTab = EditorTab.DIRECTION,
    val reversedFile: File? = null,
    val isReversedFileLoading: Boolean = false,
)
```

### `VideoFilter` (new) — single source of truth per look
A `VideoFilter` enum (in `media/`, alongside `BoomerangMode`) carries each look's display label +
parameters (red/blue channel scale, saturation). Two derivations:
- **`toMediaEffects(): List<Effect>`** (Media3-aware, `@UnstableApi`) — for preview + render.
  `ORIGINAL` → `emptyList()`.
- **`colorMatrix(): ColorMatrix?`** (UI-side helper in the screen file, using
  `androidx.compose.ui.graphics.ColorMatrix`) — for the chip thumbnail. `ORIGINAL` → `null`.

Both built from the same per-look constants so they stay in sync (thumbnail ≈ preview ≈ export).

### `OpenRangViewModel.kt`
```kotlin
fun updateFilter(filter: VideoFilter) {     // mirror updateSpeed/updateMode
    val current = _editorTabState.value
    if (current.filter == filter) return
    _editorTabState.value = current.copy(filter = filter)
}
```
`saveBoomerang()` passes `filter = tab.filter` to `renderBoomerang(...)` (it already reads
`val tab = _editorTabState.value`). No 60 s cap, no warning chip — filters don't change duration.

### `media/VideoProcessor.kt`
- Add `filter: VideoFilter` to the `renderBoomerang(...)` interface signature (defaults to
  `VideoFilter.ORIGINAL` so callers/fakes stay simple).
- Extend the effects builder: `videoEffects(speed, filter)` returning
  `Effects(emptyList(), listOf(speedEffect) + filter.toMediaEffects())`. Order: speed then color is
  fine (color is per-pixel). Audio still stripped.
- This is the only render-side change — small and localized (contrast with reps, which needed none).

### `ui/BoomerangEditorScreen.kt`
- Enable the third `TabBarItem` as **Looks** (icon `Icons.Filled.AutoAwesome`, `testTag("tab_looks")`,
  `onClick = { onSwitchTab(EditorTab.LOOKS) }`).
- Add `LOOKS` branch to the `AnimatedContent` `when (activeTab)` → `LooksTabContent(...)`.
- `BoomerangEditorContent` gains `filter: VideoFilter = VideoFilter.ORIGINAL` and
  `onFilterChange: (VideoFilter) -> Unit = {}` (defaults keep older tests compiling).
- **Live preview:** apply `filter.toMediaEffects()` via `exoPlayer.setVideoEffects(...)` on the same
  `LaunchedEffect` that rebinds the playlist (so a direction/trim/filter change always re-applies
  the look and the effect is guaranteed visible after `prepare()`).
- **Thumbnails:** extract one representative `Bitmap` from the trim (midpoint) via
  `MediaMetadataRetriever` on `Dispatchers.IO`, remembered/cached per trim. Each chip is a Compose
  `Image(bitmap, colorFilter = look.colorMatrix()?.let { ColorFilter.colorMatrix(it) })`. Frame
  extraction must be off the main thread (ANDROID_STANDARDS §9) and cached so switching tabs doesn't
  re-decode. A graceful placeholder shows while the frame loads / if extraction fails.
- **Back-gate fix:** `handleBack` confirms when `mode != FORWARD_THEN_REVERSE || speed != 2.0f ||
  filter != VideoFilter.ORIGINAL`.

### `MainActivity.kt`
No new routes — same `BoomerangEditor` destination.

## Testing plan

### Unit tests (`OpenRangViewModelTest`)
- `updateFilter(WARM)` → `editorTabState.filter == WARM`.
- `updateFilter` is a no-op when already selected (no extra emission).
- `saveBoomerang()` passes the selected `filter` to the processor (add `lastRenderFilter` to
  `FakeVideoProcessor`, mirroring slice-04's `lastRenderSpeed`).

### Instrumented tests (`BoomerangEditorScreenTest`)
- Tab bar shows 3 slots; tapping **Looks** shows the chip strip (assert `tab_looks` + a chip tag).
- The strip shows all 5 looks; tapping a look calls `onFilterChange` with it and rings it selected.
- Selection persists when switching tabs (Direction ↔ Speed ↔ Looks) and back.
- (Thumbnail bitmaps may be absent with a dummy temp file — assert on chip presence/tags + the
  `onFilterChange` callback + selected semantics, not pixel content. Mirror slice-04's
  stateless-content approach.)

### `VideoProcessorTest`
- Rendering with each non-Original filter produces an output file without error (the fake/real
  processor accepts the filter param and the effect list builds).

### Manual QA
- Each look visibly changes the **live preview** instantly on tap (B&W obviously grayscale, warm
  warmer, cool cooler, pop more saturated).
- The selected chip's **thumbnail matches** what the big preview shows (no "thumbnail lies").
- Save with a non-Original look → the **saved file** in the gallery shows the same look.
- Speed + filter stack: set 0.5× + B&W → preview is slow *and* grayscale; saved file matches.
- Switching tabs preserves direction + speed + filter.
- Screenshot of the Looks tab (a non-Original look selected, ring visible) attached to the PR.

## Acceptance criteria
- [ ] `assembleDebug` + `assembleRelease`: BUILD SUCCESSFUL, exit 0, zero `e:`.
- [ ] `testDebugUnitTest`: 0 failures; new filter tests present.
- [ ] `connectedDebugAndroidTest`: 0 failures.
- [ ] `zipalign -c -P 16 -v 4 …` on release APK shows `(OK)` (Lesson 011).
- [ ] App launched on emulator AND Pixel 10 Pro Fold; manual QA walked; screenshot attached.
- [ ] Filter visibly applies **live** in the preview (not just in the saved file).
- [ ] Selected chip thumbnail matches the preview/exported look (fidelity check).
- [ ] Speed + filter verified stacking correctly in both preview and a saved file.
- [ ] No `Color(0x…)` literal violates the 8-hex-digit rule (Lesson 001).
- [ ] All Flow collection uses `collectAsStateWithLifecycle()` (Lesson 002).
- [ ] No `Context` parameter on any `OpenRangViewModel` method (Lesson 004) — frame extraction runs
      in the composable (it has a `Context`), not in the ViewModel.
- [ ] PR notes which Media3 effect classes/methods were used (with the verified 1.10.1 signatures).
