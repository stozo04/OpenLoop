# Lesson 034 — A `pointerInput` key that never changes freezes everything the block captured

> **Applies to every gesture surface in the app, not just the speed graph.** Found there twice in one
> feature (2026-08-17), then found latent in the constant-speed slider by auditing for it. The trim
> handles were already immune — they are the pattern to copy.

## The rule

`Modifier.pointerInput(key)` restarts its block **only when `key` changes**. Everything the block
captured — callbacks, parameters, and whatever those close over — is pinned to the composition where
the block last started. Gesture keys are usually a measured size or a settled flag, so in practice
they change once and then never again, and the block runs for the rest of the node's life holding
first-composition values.

So: **for any `pointerInput`, ask what its key is, and when that key last changes. Everything captured
after that moment is stale for the rest of the node's life.**

Two independent consequences, and both have bitten this repo:

1. **A tap handler built from `pointerInput` calls a stale callback.** Which is also why a tap should
   almost never be a `pointerInput` at all — see "taps" below.
2. **A drag handler acts on stale state**, silently reverting edits made since the key settled.

## The fix, by case

**Taps — don't use `pointerInput`.** `Modifier.clickable` reads the current lambda on every tap by
construction, and brings the ripple, the accessibility click action, and `Role.Button` that a raw
`detectTapGestures` silently omits. Reach for `pointerInput` only for gestures `clickable` /
`toggleable` / `draggable` genuinely cannot express.

```kotlin
.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
```

**Real gestures — read everything through `rememberUpdatedState`.** Alias each captured callback and
each piece of mutable state, and use only the aliases inside the block:

```kotlin
val curStartMs by rememberUpdatedState(startMs)      // TrimFilmstripControls.kt — the reference
val startDrag  by rememberUpdatedState(onStartDrag)
```

Do **not** "fix" it by keying the `pointerInput` on the changing data instead: that re-installs the
detector mid-gesture and drops the drag.

## Symptom signature

An action that **works exactly once and then no-ops**, or that keeps producing the same result from a
growing state. In the failure that started this, ＋ Point went 2 → 3 keys and then stuck at 3 forever,
because every press recomputed from the same frozen 2-key curve. The give-away number is not "nothing
happened" (2) and not off-by-one (4) — it is *stuck at the first successful result*.

Nastier variant: the captured callback closes over an object that is later **recreated**. The speed
graph's `onScrubToFraction` closes over the editor's ExoPlayer, which is rebuilt on a `playerEpoch`
bump, so a frozen copy would seek a **released** player.

## Repo audit (2026-08-17)

Every `pointerInput` in `app/src/main`, and how each stands:

| Site | Key | Status |
|---|---|---|
| `SpeedCurvePanel` graph tap + drag | `sizePx` | Guarded — `latestCurve` / `latestOnCurveChange` / `latestOnScrub` |
| `SpeedCurvePanel` action buttons | *(was `enabled`)* | **Was the bug.** Now `Modifier.clickable` |
| `SpeedCurvePanel` readout / preset rows | *(was `current`, `preset`)* | **The audit called these "safe" and was wrong** — see below. Now `Modifier.clickable` |
| `SpeedTabPanel` speed slider | `widthPx` | **Was latent.** `latestSpeed` was aliased but `onSpeedChange` was not; harmless only because the call site is `viewModel::updateSpeed`. Now aliased |
| `TrimFilmstripControls` handle drag | `durationMs` | Already correct — the reference implementation |

The slider is the instructive one: it applied the pattern **halfway**. Aliasing the state you happened
to think about, and not the callback, leaves the same trap armed.

The readout is the other instructive one, because the audit **passed it**. `pointerInput(current)`
never freezes — the key *is* the value the lambda needs, so staleness was the only question asked, and
the answer was "safe." Wrong question. `current` is the speed under the playhead, which on a non-flat
curve changes on every ~50 ms poll; a key that changes **during a press** re-installs the detector and
cancels the tap in flight, so locking the loop to the live speed mostly failed while the preview was
playing (Cursor Bugbot, PR #136). That is the second sentence of "The fix, by case" — *don't key on
the changing data* — applied to a tap instead of a drag. Both failure modes come from the same
question, asked twice: **when does the key change?** "Never" freezes the lambda; "constantly" drops
the gesture. Only a key that changes exactly when the gesture surface itself is rebuilt (a measured
size) is safe, and only with `rememberUpdatedState` behind it. A tap should not be a `pointerInput` in
the first place. Regression test: `SpeedTabPanelCurveTest.tappingCurrentLocksTheLiveSpeedEvenWhenThePlayheadTicksMidPress`
— it lands a playhead tick between `down` and `up`, which a `performClick` never does.

## Detection checklist

- `rg 'pointerInput\(' app/src/main` — for each hit, name the key and say when it changes. If the
  answer is "at first layout, then never", every captured value needs `rememberUpdatedState`. If the
  answer is "whenever some live value moves", the detector re-installs mid-gesture and drops it — the
  key must be the *surface*, not the payload.
- `rg 'detectTapGestures' app/src/main` — a bare `detectTapGestures { onClick() }` with no long-press or
  double-tap is a `clickable` in disguise, and is silently inaccessible to TalkBack.
- Alias **callbacks as well as state**. Half-application is the common failure.
- **Test by driving the control more than once and asserting the state advances.** A single
  `performClick` + assert passes happily against a frozen lambda —
  `SpeedTabPanelCurveTest.addPointBecomesRemovePointAtTheCapAndBackAgain` only caught this because it
  drives the control to the cap and back.

## Reference

- `ui/components/TrimFilmstripControls.kt` — copy this one.
- `ui/components/SpeedCurvePanel.kt` (`clickable` buttons; `rememberUpdatedState` in the graph),
  `ui/components/SpeedTabPanel.kt` (`latestOnSpeedChange`).
- Sibling trap in the same detectors: [[035-drag-hit-test-belongs-on-the-down-position]] — that one is
  about *where* the gesture thinks the finger is, this one about *when* it was captured.
- Related: [[016-compose-defer-high-frequency-state-reads]] (the other "read it at the right
  moment" Compose trap).
