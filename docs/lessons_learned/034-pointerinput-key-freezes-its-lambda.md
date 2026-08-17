# Lesson 034 — A `pointerInput` key that never changes freezes the lambda; prefer `clickable`/`toggleable` for taps

> Origin: speed curves (`docs/PRD-speed-curves.md`), 2026-08-17. The same defect appeared **twice in one
> feature** — once in the graph gesture handlers, once in the action buttons — and the second one
> shipped a visible bug: ＋ Point could never take the curve past three keyframes.

## What went wrong

`Modifier.pointerInput(key)` restarts its block only when `key` changes. Everything the block captures
— including `onClick` and whatever state that lambda closes over — is frozen at the composition where
the block last started. Pick a key that never changes and you have pinned the *first* composition's
values for the lifetime of the node.

```kotlin
// WRONG — `enabled` stays true, so this block never restarts and `onClick` is the FIRST one forever.
.pointerInput(enabled) {
    if (!enabled) return@pointerInput
    detectTapGestures { onClick() }
}
```

`CurveActionButton`'s `onClick` closed over the `curve` parameter. Every tap of ＋ Point therefore ran
`insertKeyInWidestGap(<the 2-key curve from first composition>)` and produced a 3-key curve — over and
over. The count went 2 → 3 and then stuck at 3 no matter how many times the user tapped. Reset had the
same flaw: it would flatten to the *original* curve's average, not the current one.

The graph's gesture handlers had the identical bug for a different reason: they are keyed on `sizePx`,
which settles at first layout and then never changes again. That one was fixed with
`rememberUpdatedState`; the buttons were missed because a button "obviously" doesn't have gesture state.

A `pointerInput` tap handler also emits **no click semantics**, so both buttons were invisible to
TalkBack as activatable targets — a second defect hiding behind the first.

## Pattern

- **A tap is not a gesture problem.** `Modifier.clickable(enabled = …, role = Role.Button, onClick = …)`
  reads the current lambda on every tap by construction, and brings ripple, the a11y click action, and
  `Role.Button` along with it. Reach for `pointerInput` only for gestures `clickable` / `toggleable` /
  `draggable` genuinely cannot express (drag, long-press-plus-drag, multi-touch).
  ```kotlin
  .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
  ```
- **When `pointerInput` *is* required, treat its key as a correctness decision.** If the key is stable
  by design (a size, a flag that only ever goes one way), every captured lambda and value must go
  through `rememberUpdatedState` — see `SpeedCurveGraph`'s `latestCurve` / `latestOnCurveChange` /
  `latestOnScrub`. Keying on the changing data instead re-installs the detector mid-gesture and drops it.
- **Symptom signature:** an action that works exactly once and then no-ops, or that keeps producing the
  same result from a growing state. "Expected 6 but was 3" is the shape — not 2 (nothing happened) and
  not 4 (off-by-one), but *stuck at the first successful result*.

## Detection checklist

- `rg 'pointerInput\((true|Unit|enabled|[a-zA-Z]*[Ss]ize[a-zA-Z]*)\)' app/src/main` — every hit needs
  either a `rememberUpdatedState` for each captured lambda, or a rewrite to `clickable`.
- `rg 'detectTapGestures' app/src/main` — a bare `detectTapGestures { onClick() }` with no long-press or
  double-tap is almost always a `clickable` in disguise, and is silently inaccessible.
- Compose test that catches it: drive the control **more than once** and assert the state *advances*.
  A single `performClick` + assert passes happily against a frozen lambda —
  `SpeedTabPanelCurveTest.addPointInsertsAKeyframeUpToTheCap` only caught this because it clicks to the cap.

## Reference

- `ui/components/SpeedCurvePanel.kt` (`CurveActionButton` uses `clickable`; `SpeedCurveGraph` keeps
  `pointerInput` but reads through `rememberUpdatedState`).
- Related: [[016-defer-high-frequency-state-reads-into-draw-scope]] (the other "read it at the right
  moment" Compose trap) and [[030-trim-coercein-range-guard-short-clip]] (pure math extracted so the
  JVM can test what a gesture would otherwise hide).
