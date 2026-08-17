# PRD — Custom Speed Curves (Speed tab, "Constant | Curve")

**Status:** Implemented; owner review approved 2026-08-17. **Ready for PR** — full gate green, including the instrumented suite (§8b).
**Branch:** `feature/control-speed`
**Owner:** Steven Gates
**Parent:** [`docs/PRD-mission-control.md`](PRD-mission-control.md) (architecture), Speed tab shipped in slice 04

---

## 1. Problem statement

The Speed tab today is one linear slider producing a single `Float` (`EditorTabState.speed`, 0.25×–3.0×,
default 2.0×) applied uniformly to every clip in the boomerang. Every loop the app can make has exactly
one speed for its whole length.

The interesting motion in a boomerang is at the **turn** — the moment forward becomes reverse. A constant
multiplier cannot express "drift in slowly, snap through the turn, drift out", which is the shot that
makes a loop read as intentional rather than as a clip playing fast. Proprietary loop apps ship rigid
speed presets; none of the free Android ones give the user the ramp itself.

**Goal:** let the user draw the speed curve across the loop, keeping the existing slider as the default
path so nothing gets harder for the 80% who just want "1.5× and done".

---

## 2. Verified technical baseline

Everything in this section was checked this session against primary sources, not recalled.

### 2.1 The Media3 API is there — and the code comment saying otherwise is stale

`media/VideoProcessor.kt:425` asserted (now fixed as part of this work):

> `SpeedChangingVideoEffect` does not exist in Media3 1.10.1; the (deprecated) float-constructor
> `SpeedChangeEffect` is the only constant-speed video effect — there is no public constant
> `SpeedProvider` factory.

Read against the actual 1.10.1 sources jar in the Gradle cache, that is **half right and materially
misleading**. There is no public *factory*, but the interface is public and implementable:

| Fact | Source |
|---|---|
| `androidx.media3.common.audio.SpeedProvider` is a **public** `@UnstableApi` interface — `getSpeed(timeUs)` + `getNextSpeedChangeTimeUs(timeUs)` | `media3-common-1.10.1-sources.jar` |
| `SpeedChangeEffect(SpeedProvider)` constructor exists alongside the float one | `media3-effect-1.10.1-sources.jar` |
| The **whole class** is `@Deprecated`: *"Use `EditedMediaItem.Builder#setSpeed(SpeedProvider)` instead."* | same |
| `EditedMediaItem.Builder.setSpeed(SpeedProvider)` exists and is not deprecated | `media3-transformer-1.10.1-sources.jar` |
| **"If a `SpeedProvider` is set, speed changing effects are not allowed."** | `setSpeed` javadoc |
| `SegmentSpeedProvider` (package-private) is a working keyframe implementation: `ImmutableSortedMap<Long, Float>` + `floorEntry` / `higherKey` | `media3-transformer` sources |

Per CLAUDE.md I searched `developer.android.com` before relying on any of this. **Honest result: the
public docs are silent on speed.** The [Transformations guide](https://developer.android.com/media/media3/transformer/transformations)
does not mention `setSpeed`, `SpeedProvider`, or `SpeedChangeEffect` at all, and the
[`EditedMediaItem.Builder` reference](https://developer.android.com/reference/androidx/media3/transformer/EditedMediaItem.Builder)
renders as a nav shell. The [Media3 1.10 release notes](https://developer.android.com/jetpack/androidx/releases/media3)
confirm `setSpeed` exists and that `setFrameRate` is the documented companion for capping output size when
speed is raised. **The deprecation javadoc in the shipped artifact is therefore the authority here**, and
the PRD treats it as such rather than claiming a doc page that does not exist.

**Done:** the stale comment is gone — `videoEffects()` no longer builds a speed effect at all (D-2).

### 2.1a The export path was traced, not assumed

A signature existing is not the same as Transformer honouring it for **video**. Traced end to end
through the 1.10.1 sources:

```
Transformer.start(composition)
  → DefaultAssetLoaderFactory.createAssetLoader()        // OpenLoop wires this explicitly
  → ExoPlayerAssetLoader.createMediaSourceForEditedMediaItem()
       if (editedMediaItem.speedProvider != SpeedProvider.DEFAULT)
           mediaSource = new SpeedChangingMediaSource(
               mediaSource, speedProvider, mediaItem.clippingConfiguration)
  → SpeedProviderMapperSampleStream.readData(...)
       buffer.timeUs = getAdjustedPeriodTimeUs(buffer.timeUs, mapper, clipStartUs)
```

Three things this settles, all of which matter to us:

1. **It is track-agnostic.** The remap happens on `SampleStream.readData` for *every* track, so video
   is covered — this is not an audio-only feature.
2. **It runs at the sample level, before the decoder** — a timestamp remap, not a GL pass. That makes
   it *cheaper* than the `SpeedChangeEffect` shader stage it replaces, so D-2 is a small perf win as
   well as a deprecation fix.
3. **It is clipping-aware.** `SpeedChangingMediaSource` takes the item's `ClippingConfiguration` and
   threads `clipStartUs` through every conversion. This is the single most important detail for
   OpenLoop, because **every** clip we build is clipped (`setStartPositionMs`/`setEndPositionMs`,
   including the seam-drop offsets). A speed API that ignored clipping would have silently misaligned
   every curve.

Also confirmed: the mutual exclusion in §2.1 is **enforced, not merely documented** —
`EditedMediaItem`'s constructor runs `checkState(!containsSpeedChangingEffects(...))` when a provider
is set. Setting both throws at build time, so D-2 is not optional.

**Verified fallback, should any of this misbehave on a real device:** the deprecated
`SpeedChangeEffect(SpeedProvider)` constructor. Its `SpeedChangeShaderProgram` was read end to end and
correctly walks multi-segment providers (`while (nextSpeedChangeInputTimeUs <= presentationTimeUs)`),
so the curve survives either route. Only the wiring differs.

### 2.2 `SpeedProvider` is piecewise-constant, not interpolating

`getSpeed(t)` returns a value that holds until `getNextSpeedChangeTimeUs(t)`. There is no ramp primitive.
`SpeedChangeShaderProgram` consumes it by **re-timestamping frames** (`outputTime = lastOutputTime +
(inputTime − lastInputTime) / speed`) — it never drops or interpolates frames.

**Consequence:** a smooth curve must be *sampled* into constant steps. One step per source frame is the
natural granularity (a step finer than a frame is unobservable). A 7.6 s clip at 30 fps ≈ 228 steps —
trivial for a `TreeMap` lookup, and the shader only queries at frame boundaries anyway.

### 2.3 What the editor actually has today

- **Preview** is an `ExoPlayer` with a 1–2 item playlist (`previewPlaylist`) and `REPEAT_MODE_ALL`.
  Speed is applied as `exoPlayer.setPlaybackSpeed(speed)` behind a `SPEED_DEBOUNCE`.
- `useController = false` — **there is no scrubber and no transport row.** The play button, `00:02.1 /
  00:07.6` readout, and fullscreen icon in the reference mock are *new UI*, not existing UI (see §3.2).
- `EditorTabState.speed: Float` is read by the preview, by `renderBoomerang`, and by the Loopify worker.
- Speed never touches `reversedFile`; the reverse cache is keyed on the trim window only.
- Haptics precedent already in `SpeedTabPanel.kt`: `SPEED_DETENTS` + `HapticFeedbackType.SegmentTick`.
  Verified available in Compose UI 1.11.2: `Confirm`, `ContextClick`, `GestureEnd`,
  `GestureThresholdActivate`, `LongPress`, `Reject`, `SegmentTick`, `SegmentFrequentTick`, `ToggleOn/Off`.
- No tooltip/coach-mark framework exists anywhere in the app. The only "explain it once" precedent is the
  onboarding DataStore flag in `UserPreferencesRepository`.

---

## 3. Scope

### 3.1 In (v1)

1. `Constant | Curve` segmented toggle in the Speed tab.
2. Curve editor: graph, tap-to-add / drag / long-press-delete keyframes, max 6 points.
3. Presets as keyframe arrays (5 of them — see §4.4).
4. **Flatten to Constant** — collapses the curve to one multiplier and returns to slider mode.
5. Live preview honoring the curve (position poller → `setPlaybackSpeed`).
6. Export honoring the same curve, via `EditedMediaItem.Builder.setSpeed(SpeedProvider)`.
7. One-time explainer sheet on first Curve entry + a persistent `?` to reopen it.
8. Haptics on every new control (§4.6).

### 3.2 Out (v1) — with reasons

| Cut | Why |
|---|---|
| **Transport bar / scrubber** (play·pause, time readout, fullscreen from the mock) | Not in the editor today. The curve needs a *playhead*, which the position poller gives for free; a seekable transport is a separate feature with its own interaction with `REPEAT_MODE_ALL` and the reverse-preview hold. Ship the playhead, not the transport. |
| **Freehand draw → auto-simplify** | Staged as "later" in the original brief. Douglas–Peucker over a touch stream is a week of tuning for a gesture the point cap already covers. |
| **Elastic / Bounce preset** | Cannot read as elastic inside the point cap — needs 10+ oscillating keyframes. Ships as a lie or as a cap increase; do neither in v1. (Even further out of reach at the 3-point cap, R-9.) |
| **Asymmetric per-half curves** ("apply curve to reverse pass differently") | The global-timeline domain (§5.1) already lets a user draw an asymmetric curve by hand. A separate control for it is redundant surface. |
| **Cubic / spline interpolation** | v1 draws linear and exports linear. Do not draw a spline the export will not honor (§5.5). |
| **Audio** | The pipeline already does `setRemoveAudio(true)` and the preview is `volume = 0f`. Nothing to do. |

---

## 4. UX design

### 4.1 Mode toggle

A two-segment control directly above the existing slider area, inside the current `SurfaceContainer`
card, using `ElectricLime` for the selected segment (matching the mock and the SAVE pill):

```
┌──────────────────────────────────────┐
│  [  Constant  ][     Curve      ]    │   ← Curve selected = lime fill, dark text
└──────────────────────────────────────┘
```

- **Constant** (default): the panel is exactly what ships today. Existing users see no change.
- **Curve**: slider and scale labels cross-fade out, graph cross-fades in.

Switching to Curve seeds a **flat line at the current constant speed** — nothing jumps, and the first
thing the user sees is their own current setting expressed as a graph. That is the cheapest possible
explanation of what the graph means.

### 4.2 Curve editor

```
 2x ┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄
                    ╷        ╭──────●
                    ╷    ╭───╯
 1x ┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄●┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄   ← playhead (thin white line) at 1.7x
              ╭─────╯╷   Current: 1.7x
        ●─────╯      ╷
0.5x ┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄╵┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄
     └──────────── loop timeline ───────┘
```

- **Height** ~100 dp; **X** = the full loop timeline (§5.1); **Y** = speed, `MIN_SPEED`..`MAX_SPEED`
  (0.25×–3.0×), guide lines at 0.5× / 1× / 2× to match the existing scale labels.
- **Tap** empty graph → add a keyframe there (lime handle). **Drag** a handle → move in both axes;
  order is preserved by clamping x between its neighbours. **Long-press** a handle → delete.
- Endpoints at t=0 and t=1 always exist and are **x-locked** (y-draggable only), so the curve is always
  total. Only interior points can be added/deleted → the 3-point cap means one free point (R-9).
- The playhead is a thin white vertical line driven by the preview position, with a
  `Current: 1.7x` label pinned beside it. Tapping that label sets the whole loop to that value in
  Constant mode (the micro-interaction from the brief — free, since Flatten already does the work).

**Compose perf (Lesson 016):** the playhead position ticks at ~20 Hz. It is collected as a raw `State`
and read **inside the `Canvas` draw lambda** as `() -> Float`, never at the panel root — a tick must
redraw the graph, never recompose the editor tree that hosts the `AndroidView` player.

**Clamping (Lesson 030):** every drag clamp has runtime-derived bounds (`prevX + minGap`, `nextX −
minGap`). `coerceIn` throws on an inverted range. All of it goes in a pure `SpeedCurveMath.kt` with the
bounds themselves clamped (`coerceAtLeast` / `coerceAtMost`), JVM-tested — the same split as
`TrimHandleMath.kt`.

### 4.3 Bottom row

`[ ᯤ Presets ] [ ＋ Point ] [ ↺ Reset ] [ ⌁ Flatten to Constant ]` — secondary style (outlined, not lime
fill), so the lime SAVE pill stays the only primary action on screen.

**Reset** returns the curve to a flat line at the current speed. Note this makes a "Linear" preset
redundant — **the presets list drops Linear** (§4.4) rather than shipping the same action twice.

**＋ Point** adds a keyframe at the midpoint of the widest gap. It looks redundant next to
tap-to-add — it is not: it is the *only* way a TalkBack user can add a point, since tap-on-canvas
coordinates are unreachable by screen reader (§4.7). It earns its place as the accessible path.

**Flatten to Constant** computes a **duration-weighted average** of the curve (the integral under the
curve divided by its span), not the value under the playhead. Rationale: the playhead is wherever the
loop happens to be when you tap, which makes the button non-deterministic — tap it twice, get two
answers. The weighted average is the honest "what this curve amounts to overall". The tappable
`Current: 1.7x` label covers the "I want *this* value" intent explicitly.

### 4.4 Presets

Each is a `List<SpeedKey>` constant — no code, just data. (No "Linear" entry — **Reset** is that action,
§4.3.)

| Preset | Shape |
|---|---|
| Ease In | slow → fast |
| Ease Out | fast → slow |
| Slow–Fast–Slow | S-curve, the "breathing" loop |
| Accelerate into Reverse | ramps up across the forward half, peaks at the turn, eases out |

"Accelerate into Reverse" is the preset that justifies the global-timeline domain in §5.1 — it is
inexpressible if the curve only covers the trim window.

### 4.5 Tutorial / explainer

On the **first** tap of `Curve`, a bottom sheet:

> **Speed curves**
> Instead of one speed for the whole loop, draw how it changes.
> · **Tap** the graph to add a point · **Drag** to shape it · **Hold** a point to remove it
> Higher = faster. The line is your loop, left to right.
> *Presets* gets you started. *Flatten to Constant* goes back to the simple slider anytime.
>
> `[ Got it ]`

Gated on a new `hasSeenSpeedCurveIntro: Flow<Boolean>` in `UserPreferencesRepository` — the exact
pattern already proven by `hasCompletedOnboarding`, including the `try/catch (IOException)` on write
(Lesson 003). A small `?` button in the panel header reopens it forever after. **No coach-mark
framework** — one sheet, one flag.

### 4.6 Haptics

| Interaction | Type |
|---|---|
| Mode toggle tap | `ToggleOn` / `ToggleOff` |
| Add a keyframe | `ContextClick` |
| Drag crosses a 0.5× / 1× / 2× guide line | `SegmentTick` (reuses the existing `SPEED_DETENTS` logic verbatim) |
| Drag hits a clamp (endpoint, neighbour, min/max speed) | `Reject` |
| Long-press delete | `LongPress` |
| Preset applied · Flatten | `Confirm` |

**Deliberately not shipped: a tick when the playhead crosses a keyframe.** It was in the brief, but the
preview loops forever — a 7 s loop with 4 keyframes would buzz the phone every ~1.8 s indefinitely while
the tab is open. That is not feedback, it is a fault condition. If you want it, it should fire only
during an explicit scrub, which needs the transport bar that §3.2 cuts.

### 4.7 Accessibility — a requirement, not polish

The panel being replaced carries a full semantics contract: `contentDescription`, `stateDescription`,
`ProgressBarRangeInfo`, and a working `setProgress` so TalkBack can set the speed. A gesture-only
`Canvas` would be a **regression** for the same user. Not acceptable, and not something to defer.

| Element | Semantics |
|---|---|
| Mode toggle | Two `Tab`-role nodes, selected state announced |
| Graph container | `contentDescription` = "Speed curve"; `stateDescription` = "4 points, 0.5× to 2×, average 1.4×" |
| Each keyframe | Its own focusable node: "Point 2 of 4, 40% through the loop, 1.7×" |
| Keyframe actions | `CustomAccessibilityAction`s — *Increase speed* / *Decrease speed* (0.05× steps), *Move earlier* / *Move later* (2% steps, clamped to neighbours), *Delete point* (interior points only) |
| ＋ Point | The screen-reader path for adding (§4.3) |
| Presets · Reset · Flatten | Plain buttons, labelled; already accessible |

The endpoint keyframes announce as x-locked so a user is not left hunting for a *Move* action that
does nothing. Each custom action reuses the same clamped `SpeedCurveMath` functions the drag path
uses — one implementation, so the two input paths cannot drift apart (and TalkBack `setProgress` is
exactly how Lesson 030's inverted-range crash was reachable, so the clamps must be shared, not
duplicated).

---

## 5. Architecture & implementation plan

### 5.1 Decision: the curve's domain is the **full loop timeline**, stored normalized

Keyframes are `(t: Float in 0f..1f, speed: Float)` over the **concatenated, pre-speed** loop — i.e. the
sequence `boomerangSequence(mode, reps)` produces, after seam drops, at 1×.

Three reasons, in order of weight:

1. **"Accelerate into Reverse" is otherwise impossible.** On a trim-window domain the reverse half can
   only ever mirror the forward half; the turn cannot be the peak.
2. **The mock aligns the graph to the loop scrubber** (`00:00 → 00:07.6`), not to the trim window.
3. **Normalized survives everything.** Re-trim, change direction, change reps → the curve re-stretches
   instead of being invalidated. No migration, no clamping against a changing duration.

### 5.2 Decision: one speed path, not two

`setSpeed(SpeedProvider)` and speed-changing effects are **mutually exclusive** (§2.1). So constant mode
migrates onto `setSpeed` with a single-segment provider, and `SpeedChangeEffect` leaves
`videoEffects()` entirely. This is a net *deletion* — one code path instead of a constant path and a
curve path — and it lands on the non-deprecated API.

### 5.3 New / changed files

| File | Change |
|---|---|
| `media/SpeedCurve.kt` **(new)** | `SpeedKey(t, speed)`, `SpeedCurve(keys)`. Pure: `speedAt(t)` (linear interp), `flatten()` (duration-weighted average), `sample(durationUs, stepUs)` → `SortedMap<Long, Float>`, `sliceFor(startUs, endUs)` → per-clip provider. **No Android imports.** |
| `media/KeyframeSpeedProvider.kt` **(new)** | ~20 lines implementing `SpeedProvider` over a `TreeMap`, mirroring `SegmentSpeedProvider`'s `floorEntry`/`higherKey` shape (it is package-private in Media3, so mirror — do not try to reuse). |
| `media/VideoProcessor.kt` | `renderBoomerang` takes `curve: SpeedCurve` instead of `speed: Float`. `videoEffects()` drops `SpeedChangeEffect`. `buildEditedMediaItem` calls `.setSpeed(provider)`. Fix the stale §2.1 comment. |
| `ui/components/SpeedCurveMath.kt` **(new)** | Drag/clamp/hit-test/px↔value math, pure, JVM-tested (`TrimHandleMath` precedent — Lesson 030). |
| `ui/components/SpeedCurvePanel.kt` **(new)** | The `Canvas` graph + gestures + bottom row. |
| `ui/components/SpeedTabPanel.kt` | Add the toggle; branch to slider or curve panel. |
| `ui/OpenLoopUiState.kt` | `EditorTabState` gains `curve: SpeedCurve?` — **`null` means constant mode**. `speed: Float` is untouched. |
| `ui/OpenLoopViewModel.kt` | `updateCurve`, `enterCurveMode` (seeds flat at `speed`), `flattenCurve`, `applyPreset`, `markSpeedCurveIntroSeen`. |
| `ui/BoomerangEditorScreen.kt` | Position poller when `curve != null`; pass curve down. |
| `data/UserPreferencesRepository.kt` | `hasSeenSpeedCurveIntro` + setter. |
| `work/` render worker | Serialize the curve into `Data` (flat `FloatArray` of t/speed pairs). |

`curve: SpeedCurve?` with `null` = constant is deliberately lazier than a mode enum plus a parallel
value: every existing consumer of `tab.speed` keeps working unchanged, and Flatten is literally
`copy(curve = null, speed = curve.flatten())`.

### 5.4 Per-clip slicing (Lesson 018 lands directly here)

The sequence is N clips; `setSpeed` is per-`EditedMediaItem`. So each clip gets the slice of the global
curve spanning it, rebased to clip-local time:

```
clip i covers [startUs_i, endUs_i) of the global 1× timeline
provider_i(t) = curve.speedAt((startUs_i + t) / totalUs)
```

`startUs_i` **must be accumulated from the post-seam-drop clip durations**, not from `i × clipDuration`.
A clip that drops its leading frame is one frame shorter, and the drop is decided by *sequence position*,
not clip identity. Getting this wrong slides the curve by a frame per turn — invisible at 2 clips,
obvious at reps > 1. This is the single highest-risk line in the feature and it gets its own unit test.

### 5.5 Draw what you export

The mock shows a smooth spline. v1 interpolates linearly, so v1 **draws a polyline** (rounded joins, lime,
matching the slider gradient). Drawing a curve the encoder will not reproduce is a preview that lies.
Cubic is a later upgrade to both halves at once.

### 5.6 `setFrameRate` must key on the curve's maximum

Today: `if (speed > 1f) builder.setFrameRate((sourceFps / speed).toInt().coerceAtLeast(24))`. A curve has
no single `speed`. Change to `curve.maxSpeed()`, preserving the existing formula's shape.

> Flagging honestly: `sourceFps / speed` looks inverted for a *max output frame rate* — speeding up 2×
> makes frames arrive at 2× density, so the natural cap is `sourceFps`, not `sourceFps / 2`. It is
> shipped, tested, and produces watchable output, so **this PRD does not change it** — it only keys it on
> `maxSpeed`. Worth a separate look; not worth mixing into this change.

### 5.7 Preview

A `LaunchedEffect` polling every ~50 ms while `curve != null`:

```
globalFraction = (sum of durations of items before currentMediaItemIndex + currentPosition) / totalMs
targetSpeed = curve.speedAt(globalFraction)
if (abs(targetSpeed - lastApplied) > 0.02f) exoPlayer.setPlaybackSpeed(targetSpeed)
```

The threshold stops a `setPlaybackSpeed` storm on a steep ramp. Poller **must** stop when `curve == null`
and must not run while `reversePreviewLoading` (Lesson 026 — do not add load during the reverse pass).

**The two speed paths must not fight.** The existing `LaunchedEffect(speed) { delay(SPEED_DEBOUNCE);
exoPlayer.setPlaybackSpeed(speed) }` would keep stamping the constant value over the poller's output.
It gets gated on `curve == null`, so exactly one writer owns `setPlaybackSpeed` at any moment.

**Considered and rejected: `CompositionPlayer`.** It would preview the exact `Composition` the export
renders, which is genuinely more faithful. It also replaces ExoPlayer wiring that Lessons 020, 022, 023
and 026 were each paid for in production bugs — the epoch/teardown dance, the HDR seam workaround, the
memory gate. Not for a speed feature.

---

## 6. Constraints & risks

| Risk | Mitigation |
|---|---|
| **Per-clip offset drift at seams** (§5.4) | Dedicated unit test over all 4 modes × reps 1–2, asserting global-t continuity across clip boundaries. |
| **Variable output frame rate** — different curve regions emit frames at different densities, so the encoder sees VFR | Legal MP4, but rate-control behaviour is device-dependent. Must be checked on the Fold and a Samsung, not just the emulator (Lessons 020/021/027 are all "the emulator hid it"). |
| **Slow-mo judder** — the shader re-timestamps without interpolating, so a 0.25× region spreads the same frames over 4× the time | Pre-existing at constant 0.25×; a curve makes it reachable in a *portion* of the loop. Accept for v1; note in QA. |
| `@UnstableApi` on `SpeedProvider` | Already pervasive — the whole media package is `@UnstableApi`. No new exposure. |
| **Longer outputs** | A curve dipping to 0.25× lengthens the render. Same ceiling as the existing slider; no new bound needed. |
| **Discoverability** — power feature in a casual app | Constant stays the default and is untouched; Curve is one tap away and explains itself once (§4.5). |

---

## 7. Definition of Done

Per [`docs/DEFINITION_OF_DONE.md`](DEFINITION_OF_DONE.md) — the full gate, no exceptions, from a green
baseline:

- Clean **debug and release** builds, exit code 0, zero `e:` lines.
- JVM tests: `SpeedCurveTest` (interp, flatten weighting, sampling, degenerate 1-point and equal-t
  curves), `SpeedCurveMathTest` (every clamp, including the inverted-range cases Lesson 030 names),
  `KeyframeSpeedProviderTest` (`getSpeed`/`getNextSpeedChangeTimeUs` contract incl. `C.TIME_UNSET` tail),
  per-clip slicing across all 4 modes.
- Compose tests: toggle switches panels, curve survives tab switch, Flatten returns to the slider at the
  weighted-average value, intro sheet shows once.
- `:app:lintDebug` — zero new errors.
- Engine 2 "Inspect Code" — reported **not run** with substitutes (lint + compiler warnings + Tier 3),
  per `STATIC_ANALYSIS.md`'s own guidance for this machine.
- **Run on the emulator, save a curved loop, screenshot it, attach to the PR.**
- On-device pass on the Fold for the VFR risk in §6.
- Honest statement of what could not be verified + manual QA checklist.

---

## 8. Open questions — **resolved 2026-08-17**

| # | Question | Answer |
|---|---|---|
| 1 | Y-axis range | **Keep `MIN_SPEED..MAX_SPEED` = 0.25×–3.0×.** Graph and slider share one scale; Flatten round-trips losslessly. Guide lines still drawn at 0.5× / 1× / 2×. |
| 2 | Keyframe cap | **3 total** — 2 x-locked endpoints + 1 free interior point. Was 6 at sign-off; cut after on-device use (R-9). |
| 3 | Toggle placement | **Inside the Speed card**, above the graph/slider — the whole speed control stays one object. |
| 4 | Transport bar | **Out.** Playhead line + `Current: 1.7×` label only, driven by the position poller the curve needs regardless. A seekable scrubber gets its own PRD if wanted — it has to reckon with `REPEAT_MODE_ALL`, the playlist rebind debounce, and the reverse-preview hold. |

Nothing is blocking. Remaining risk is empirical, not decisional: the variable-frame-rate behaviour in
§6 has to be confirmed on real hardware, and the emulator is exactly the environment that hid Lessons
020, 021 and 027.

---

## 8a. Shipped deviations from this PRD

Changes made during implementation, at the owner's request or because the device run exposed a gap.
Recorded here so the document matches the code (Lesson 007).

| # | Change | Why |
|---|---|---|
| R-1 | **Both axes are labelled.** Y: `0.5× / 1× / 2× / 3×` down the left of the plot. X: time ticks under it, reusing the Trim ruler's math (`trimRulerLabelTimesMs` / `formatTrimRulerLabel`). | Owner request. "Higher is faster" was the only cue for what a handle's height meant. |
| R-2 | X ticks are the loop's length **at 1×**, not the sped-up output length. | The curve is defined on the input timeline. Output time is a *non-linear* function of input time once speed varies, so evenly-spaced output ticks would sit at uneven — i.e. wrong — places. It also keeps the axis stable while dragging instead of relabelling on every pointer move. |
| R-3 | **Y axis is logarithmic.** | Not in the original design. Speed is perceived multiplicatively; on a linear 0.25–3.0 axis, 1× sits at 27% of the height and the whole slow-motion range is crushed into the bottom tenth. On a log axis 0.5× and 2× straddle 1× symmetrically (asserted in `SpeedCurveMathTest`). |
| R-4 | **"Flatten to Constant" button removed**; Presets · Add point · Reset fill equal thirds. | Owner request. Tapping the **Constant** segment already flattens (to the duration-preserving average), so the button was a second name for one action. D-5's *semantics* are unchanged — only the redundant control is gone. |
| R-5 | **Drag scrubs the preview.** Dragging a handle seeks the preview to that keyframe's moment and pauses there; release resumes the loop. | Owner request ("see exactly how the clip will turn out… when to scale the speed up or down"). Live speed-as-you-adjust already worked via the poller; this adds *which frame* you are editing. Throttled at 1.5% of the loop per seek. |
| R-6 | **The reversed clip's duration is measured, not assumed** — and the duration chip derives from the same spans the render uses. | A real bug the device run caught: see [Lesson 033](lessons_learned/033-derived-timeline-must-measure-its-artifacts.md). The editor promised 9.2 s and the encoder produced 7.06 s. |

## 8b. Verification

Full gate per [`docs/DEFINITION_OF_DONE.md`](DEFINITION_OF_DONE.md).

| Check | Result |
|---|---|
| Debug + release build | `BUILD SUCCESSFUL`, exit 0, zero `e:`, zero `w:` |
| JVM unit tests | **519 tests, 0 failures** (up from 503) |
| Instrumented / Compose tests | **117 tests, 0 failures, 1 skipped** on Pixel_8 API 36 — the whole `connectedDebugAndroidTest` suite, not just the new class |
| Android Lint (`:app:lintDebug`) | 0 errors; 24 warnings, **identical to the pre-change baseline** (all dependency-version nags) |
| Engine 2 "Inspect Code" | **Not run** — cannot run on this machine; substituted per `STATIC_ANALYSIS.md` (lint + zero compiler warnings + Tier 3) |
| App run on emulator | Pixel_8 API 36, full capture → trim → editor → curve → save → share flow |
| Screenshots | [Custom mode after a drag](e2e/2026-08-17-speed-curve-editor.png), [before adding a point](e2e/2026-08-17-speed-curve-add-point.png), [one-time intro](e2e/2026-08-17-speed-curve-intro.png), [Constant mode](e2e/2026-08-17-speed-constant-mode.png) |

**Predicted vs. actual output duration** (editor chip vs. the muxed `mvhd` duration of the saved MP4):

| Shape | Predicted | Actual | Δ |
|---|---|---|---|
| Constant 2×, FORWARD *(the D-2 migration)* | 2.00 s | 2.04 s | 0.04 s |
| Ease-In curve, FORWARD | 4.80 s | 4.81 s | 0.01 s |
| Ease-In curve, FORWARD_THEN_REVERSE | 7.20 s | 7.26 s | 0.06 s |

Also verified on device, **after** the final round of fixes (the earlier on-device notes predated them
and are not carried forward): the mode toggle leaves Constant mode byte-identical and its "Current
speed" pill is fully visible; the one-time explainer fires on first Curve entry and not after; presets
apply; tapping empty canvas adds a handle, and the Add point button flips to Remove once the single
interior point exists (R-8);
dragging the Slow–Fast–Slow peak from 2× to ~0.55× reshapes the curve and moves the duration chip
7.8 s → 10.4 s live; the `Current: N×` readout sweeps the curve as the playhead moves. Screen-reader
reachability of every keyframe is covered by `SpeedTabPanelCurveTest` rather than by a manual check.

**Instrumented tests — the gate is met, and it caught two real bugs.** The earlier run that reported one
`FAILED` after 63 s with no retrievable stack trace was indeed device teardown — that test passes on a
stable emulator. But the completed run was not clean, and neither failure was a test artifact:

1. **＋ Point stuck at three keyframes** (`addPointInsertsAKeyframeUpToTheCap`, expected 6, got 3).
   `CurveActionButton` drove taps through `pointerInput(enabled) { detectTapGestures { onClick() } }`.
   `enabled` never changes while the button is usable, so the block never restarted and `onClick` — and
   the `curve` it closed over — stayed frozen at first composition. Every tap recomputed from the
   original 2-key curve. Reset had the same flaw (it would flatten to the *original* average), and
   neither button exposed a click action to TalkBack. Fixed by using `Modifier.clickable(enabled, role =
   Role.Button, onClick)`. Written up as **Lesson 034**; the graph's gesture handlers had the identical
   defect for a different reason (keyed on `sizePx`) and were already fixed with `rememberUpdatedState`.

2. **The per-handle screen-reader nodes were unreachable** (`everyKeyframeIsReachableByScreenReader`).
   The graph container carried `.semantics(mergeDescendants = true)`, which collapses every
   `speed_curve_point_N` node into the container — exactly what those nodes exist to prevent. TalkBack
   would have seen one node carrying every point's custom actions at once with no way to tell which
   point any of them moved. Dropping the merge restores per-handle traversal; the graph still announces
   itself. The earlier uiautomator spot-check that appeared to show per-point nodes is not evidence
   either way: nothing is committed, so the ordering cannot be checked, and merged semantics
   *concatenate* child descriptions — so a check matching the text "Point 3 of 5…" would have passed
   against the merged node too. Node reachability is now asserted by a test instead of by eye.

Running the **whole** `connectedDebugAndroidTest` suite rather than just the new class then surfaced a
third defect the new-class run could not see: adding the `Constant | Curve` toggle put 58 dp of new
chrome (40 dp toggle + 18 dp spacer) into a Speed card whose fixed height was still the pre-toggle
240 dp, clipping the "Current speed" pill off the bottom in Constant mode. Height raised to 298 dp;
[the constant-mode screenshot](e2e/2026-08-17-speed-constant-mode.png) is the proof.

A fourth bug surfaced only when a Compose test drove the **graph canvas** rather than its buttons:
**handles were nearly impossible to grab with a vertical drag.** `detectDragGestures` reports
`onDragStart` at the position *after* touch slop (~8 dp), not at the down event, and the handle hit test
is a `0.09` tolerance in normalized graph space — which on a 120 dp-tall graph is only ~8.6 dp
vertically. Slop therefore consumed essentially the whole grab radius for the one gesture the control
exists for. Horizontal drags kept ~17 dp of margin and worked, which is exactly why manual spot-checks
(including this feature's own earlier on-device check) reported "a drag moves a handle". Fixed by
recording the true down position in the tap detector's `onPress` and hit-testing that. Written up as
**Lesson 035**; verified on device — pulling the Slow–Fast–Slow peak from 2× down to ~0.55× reshaped the
curve and moved the duration chip 7.8 s → 10.4 s live
([screenshot](e2e/2026-08-17-speed-curve-drag.png)).

`BoomerangEditorScreenTest.durationLabel_reflectsTheSelectedSpeed` also needed its expectation moved
from `20.0s` to `19.9s`. That is not a test being bent to fit: F→R over a 5 s trim drops the reversed
clip's leading seam frame (Lesson 018), so the cycle is 9.967 s, and the label now derives from the same
clip spans the render slices (Lesson 033). The old `20.0s` was the independent formula over-promising by
exactly the seam frame.

**Not verified — needs real hardware:**
- The variable-frame-rate risk in §6. The emulator's virtual camera records ~5–11 fps under
  `swiftshader`, which is not a fair test of encoder rate-control. Needs the Fold and a Samsung.
- Reverse-preview generation repeatedly hit the 2-minute `REVERSE_PREVIEW_TIMEOUT` on longer emulator
  clips. That path is **untouched by this change** (`git diff` over `VideoReverser.kt` / `Reverse*.kt`
  is empty) and is the known emulator-perf degrade path from Lessons 020/026, but it means the
  reverse-mode flow was exercised on short clips only.

### 8c. Owner revisions after using it on device (2026-08-17)

Three changes requested while watching the build run on the emulator. All three are **reversals of
earlier decisions**, recorded here rather than silently overwritten:

| # | Change | Supersedes |
|---|---|---|
| R-7 | The mode is labelled **Custom**, not Curve | the original `[Constant] [Curve]` toggle copy |
| R-8 | A point must be **removable from a visible control**, not only by long-press | §4.3, which made long-press the sole delete |
| R-9 | **2 points minimum, 3 maximum** (`MAX_KEYS` 6 → 3) | the sign-off answer "6 total — 2 locked ends + 4 free" |

**R-9 is the load-bearing one.** One free interior point means one bend. Consequences carried through:

- **All four presets were re-authored** to fit 3 keys. `SLOW_FAST_SLOW` is unchanged in spirit (it *is*
  a 3-point shape). `EASE_IN` / `EASE_OUT` lose their multi-segment easing and become a single bend
  placed off-centre — still the right feel, less finely shaped. `ACCELERATE_INTO_REVERSE` was made
  deliberately **asymmetric** (out of the turn faster than into it); a symmetric version would have been
  indistinguishable from `SLOW_FAST_SLOW` at three points.
- **The "too close to an existing key" guard in `insertKeyAt` is now unreachable through the UI** — an
  interior key means the cap is already hit. The guard stays (it is what stops handles stacking if the
  cap is ever raised) and is covered by a synthetic-curve unit test rather than a UI path.
- Nothing in the render path cares: `SpeedProvider` sampling, the harmonic-mean flatten, and the
  measured clip spans are all independent of the key count.

**R-8's shape.** Because the cap is 3, there is at most one removable point at any time — so the middle
action button is a two-state control: **Add point** when the curve is a flat pair, **Remove** once the
interior point exists. No new chrome, the equal-thirds row (R-2) is preserved, and there is no "which
point?" ambiguity to resolve. Long-press-to-delete still works as a shortcut for anyone who finds it.

One defect found while verifying R-8: `maxLines = 1` on the action label wrapped "Remove point" at the
space and dropped the second word, so the screen read "Remove" while TalkBack announced "Remove point".
The label is now the single word "Remove", and `TextOverflow.Ellipsis` makes any future overflow (a
longer translation) visible instead of silent.

## 9. Decision log

| # | Decision | Rationale |
|---|---|---|
| D-1 | Curve domain = full loop timeline, normalized `0..1` | "Accelerate into Reverse" is inexpressible otherwise; matches the mock's axis; survives trim/mode/reps changes |
| D-2 | Migrate to `setSpeed(SpeedProvider)`, delete `SpeedChangeEffect` | Mutual exclusion is **enforced** by `EditedMediaItem`'s constructor, not just documented; non-deprecated; one path instead of two; and the traced path (§2.1a) is a pre-decode timestamp remap, so it also drops a GL stage |
| D-3 | Linear interpolation, sampled per source frame | `SpeedProvider` is piecewise-constant; per-frame is the finest observable step |
| D-4 | `curve: SpeedCurve?`, `null` = constant | Every existing `tab.speed` consumer keeps working; Flatten is a one-line `copy` |
| D-5 | Flatten = the **harmonic** mean (implemented; PRD draft said "weighted average") | It is the constant that plays the loop in the *same time*, so the length does not jump at the moment the user asks to simplify. A 0.5×→2× ramp arithmetic-averages to 1.25× but actually plays at 1.08×. Cross-checked against Media3's own `SpeedProviderUtil` in `SpeedCurveDurationTest`. The button is gone (R-4); the **Constant** tab carries the action |
| D-6 | Preview via position poller + `setPlaybackSpeed` | `CompositionPlayer` would replace wiring that four production lessons paid for |
| D-7 | Draw a polyline, not a spline | Do not render a curve the export will not reproduce |
| D-8 | No playhead-crossing haptic | Loops forever → buzzes every ~2 s; a fault condition, not feedback |
| D-9 | Tutorial = one sheet + one DataStore flag | Mirrors `hasCompletedOnboarding`; no coach-mark framework exists and none is worth building |
| D-10 | Curve never invalidates `reversedFile` | Same invariant as `speed` and `filter` today — it is an effect, not a re-trim |
| D-11 | Clip spans measure the reversed artifact; the duration chip derives from those same spans | Two independent derivations of "how long will this be" disagreed in practice — [Lesson 033](lessons_learned/033-derived-timeline-must-measure-its-artifacts.md) |
