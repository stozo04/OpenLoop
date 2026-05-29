# KICKOFF — Boomerang Slice 04 (Speed tab)

> **Temporary session doc, NOT a numbered lesson** — same convention as the slice-03
> [`KICKOFF`](./KICKOFF-boomerang-slice-03-direction-tab.md). It exists because CLAUDE.md makes every
> session read this whole folder at startup. **Delete it when slice 04 merges** — but first promote
> anything durable into a real numbered lesson (the #1658 per-item-effects finding is the strongest
> candidate if the device pass turns up anything).

You're starting [`docs/active/boomerang-rollout/04-editor-speed-tab.md`](../active/boomerang-rollout/04-editor-speed-tab.md).
Read **that** (it's the PRD), then this. This doc is only the things the slice-04 PRD does **not** tell
you, or tells you **wrong**, that I learned building slices 02–03. Read order matters.

---

## 0. The PRD is wrong about the render API — read this before you touch `VideoProcessor`

The slice-04 PRD (and several other docs, now mostly fixed) says: *"Replace `SpeedChangingVideoEffect(2.0f)`
with `SpeedChangingVideoEffect(speed)`."* **Two things are wrong with that:**

1. **`SpeedChangingVideoEffect` does not exist in Media3 1.10.1.** The constant-speed video effect in our
   version is **`SpeedChangeEffect`** (the deprecated float-constructor form — there's no public constant
   `SpeedProvider` factory). This is verified against the 1.10.1 source tag, recorded in the
   `reference-media3-1-10-1-transformer-api` memory, and — most conclusively — it's what the shipped code
   compiles and runs with. Do **not** go looking for `SpeedChangingVideoEffect`; you'll waste time.
2. **The speed is already fully threaded and applied.** `VideoProcessor.renderBoomerang(...)` has taken a
   `speed: Float` parameter since slice 02, and `Media3VideoProcessor` already builds
   `SpeedChangeEffect(speed)` per clip via `speedEffects(speed)`. **There is no `2.0f` literal in
   `VideoProcessor` to replace.** The render side is done.

So the **entire render-side change for slice 04** is one line: `OpenRangViewModel.saveBoomerang()`
currently passes `speed = DEFAULT_SPEED` — change it to `speed = _editorTabState.value.speed`. That's it.
Don't touch `Media3VideoProcessor`.

---

## 1. THE slice-04 landmine: per-item speed effects (#1658) — now with a *variable* speed

This is the highest-risk item and slice 04 is what makes it visible. Speed is applied **per
`EditedMediaItem`** (each clip in the sequence carries its own `SpeedChangeEffect`).
[androidx/media #1658](https://github.com/androidx/media/issues/1658) reported that in an
`EditedMediaItemSequence` **only the first item's effects are applied** (real in 1.4.1, marked Closed,
*unconfirmed in 1.10.1*).

Slice 03's device pass with the constant 2× *appeared* correct (Steven: "everything works"), but a
constant 2× across both halves is exactly the case where a single-item-only bug is **hardest to notice**
— both halves at 2× and both halves at "1× because the effect dropped" can look similar at a glance if
you're not measuring. Slice 04 makes speed **user-variable**, which is the real test:

**On device, before you trust it:** render `F→R` and `R→F` at a clearly non-2× speed (e.g. **0.5×**) and
confirm **both halves change speed together**. If the second half plays at 1× (or 2×) while the first
half is at 0.5×, #1658 is live: speed must move from a per-item effect to a **Composition-level** effect.
Verify that API exists in 1.10.1 **first** (cross-check the `reference-media3-1-10-1-transformer-api`
memory + the source tag — do **not** guess). This compounds in slice 05 when N repetitions mean N copies
of each clip, all relying on the per-item effect.

---

## 2. The Speed tab is NOT "just add a slider" — you're building the tab-switching scaffold the placeholder deferred

Slice 03 shipped a **single hard-coded tab icon**. In `ui/BoomerangEditorScreen.kt` today:
- the bottom bar is one `Text("≫")` centered in a reserved-height `Box` (height held for exactly this
  moment so the layout doesn't reflow); and
- the content panel above it is **hard-wired to the Direction chips** — there is no tab state, no
  switching, no `activeTab`.

So slice 04 introduces the real scaffold the placeholder stood in for:
- `enum class EditorTab { DIRECTION, SPEED }` and `activeTab` in `EditorTabState` (REPS lands in slice 05).
- a **two-entry** tab bar with active-pill styling (active = `DeepCharcoal` pill + `NeonPurple` icon;
  inactive = `GlassWhite` pill + white icon, per the PRD), driven by `activeTab`.
- content switching between the Direction panel and the new `SpeedTabContent`, ideally `AnimatedContent`
  with a ~200 ms cross-fade.

Budget for "wire up tab routing," not "drop one slider into the existing panel."

---

## 3. Preview speed is PLAYER-SIDE — do not put `speed` in the playlist rebind key

The editor preview is an ExoPlayer **playlist** (`setMediaItems(...) + REPEAT_MODE_ALL`), rebuilt in a
`LaunchedEffect(mode, reversedFile, trimStartMs, trimEndMs)` (see `BoomerangEditorContent`). Speed is a
**player-side** effect: `exoPlayer.setPlaybackSpeed(speed)`. It applies to the reversed clip too (that
clip plays *forward* with reversed frames), so **the cached reversed file is reused across all speed
changes — no re-render, no re-reverse.**

**Do this:** apply speed in a **separate** `LaunchedEffect`/`snapshotFlow { speed }.debounce(~50.ms)`
that calls `setPlaybackSpeed`. **Do NOT** add `speed` to the playlist `LaunchedEffect` key — that would
rebuild the entire playlist and `prepare()` on every slider tick (jank, and a flash). Keep the rebind
keyed on mode/file/trim only; let speed ride the player.

This is also a Lesson 016 situation: the slider value ticks fast; read it in the narrowest scope and
don't let the drag recompose the preview `AndroidView`.

---

## 4. The output-duration label already divides by speed — just pass the real value

`media/BoomerangSequence.kt` has `boomerangOutputDurationMs(mode, trimStartMs, trimEndMs, speed, repetitions)`
(pure, JVM-tested). `BoomerangEditorContent` already calls it — but currently passes
`OpenRangViewModel.DEFAULT_SPEED`. Slice 04: pass `state.speed` and the "Xs" label updates live. No new
math, no new function.

---

## 5. Where everything lives (so you don't re-learn the map)

- **State + `EditorTabState`:** `ui/OpenRangUiState.kt` (NOT inline in the ViewModel, despite the PRD
  sketch). Add `speed: Float = 2.0f` and `activeTab: EditorTab`. Default speed = `OpenRangViewModel.DEFAULT_SPEED` (2.0f).
- **VM mutators:** `ui/OpenRangViewModel.kt` — alongside `updateMode` / `ensureReversedSegment` /
  `saveBoomerang` / `backToTrim`, add `updateSpeed(speed)` (`coerceIn(0.25f, 3.0f)`) and `switchTab(tab)`.
  `saveBoomerang()` is where you swap `DEFAULT_SPEED` → `state.speed`.
- **Editor UI:** `ui/BoomerangEditorScreen.kt` — `BoomerangEditorScreen` (thin) + `BoomerangEditorContent`
  (hoisted, testable). Existing helpers: `DIRECTION_CHIPS`, `previewPlaylist`, `CircleIconButton`,
  `SaveCheckmark`, `DirectionChipButton`. Add `SpeedTabContent`, the `EditorTab` tab bar, the
  `setPlaybackSpeed` effect.
- **Pure math:** `media/BoomerangSequence.kt` (`boomerangSequence`, `boomerangOutputDurationMs`).
- **Render:** `media/VideoProcessor.kt` (`renderBoomerang` already applies `SpeedChangeEffect(speed)`;
  `ensureReversed` for the preview). **You don't edit this file.**
- **Tests:** `OpenRangViewModelTest` (JVM; `FakeVideoProcessor` — extend it if you add interface methods,
  and sweep `NoopVideoProcessor` in `OpenRangNavHostTest` too — Lesson 017), `BoomerangSequenceTest`
  (JVM), `BoomerangEditorScreenTest` (instrumented; no mockk — Lesson 017).

---

## 6. Lessons that specifically apply to slice 04

- **Lesson 001 (Color literals):** slider thumb (`NeonCoral`) + track (`NeonPurple` fill / `GlassWhite`
  right) are existing 8-hex tokens in `CameraScreen.kt`. Reuse them; don't inline new hex.
- **Lesson 002 (`collectAsStateWithLifecycle`):** the editor already collects `editorTabState` this way;
  keep it.
- **Lesson 016 (defer high-frequency reads):** the slider value, like the slice-01 record timer, ticks
  fast — narrowest scope, lambda the reads, debounce the player call.
- **Lesson 018 (seam by position):** in place and untouched by slice 04. Reps (slice 05) is what
  re-exercises the per-cycle seam — not you.
- **Web-verify (CLAUDE.md rule):** Material3 `Slider`'s custom `thumb`/`track` slot API has drifted
  across versions — confirm the current signature. Confirm `HapticFeedbackConstants.CLOCK_TICK` on
  minSdk 26 and the current `snapshotFlow`/`debounce` import paths before using them.
- **Stay scoped:** two tab icons. **Do NOT** pre-render a Reps placeholder (slice 05).

---

## 7. One-line summary for the impatient

The render is already done (speed is threaded + applied via `SpeedChangeEffect(speed)`; just stop
hard-coding `DEFAULT_SPEED` in `saveBoomerang`). The real work is **UI tab-switching scaffold + a slider
+ player-side `setPlaybackSpeed`**, and the one thing that can make it wrong is **#1658**: confirm both
halves of `F→R`/`R→F` change speed together at a non-2× speed on the Fold before you trust it.
