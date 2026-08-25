# Lesson 035 — `onDragStart` fires a touch-slop away from the finger; hit-test the **down** position

> **Applies to any drag that has to work out *what* the user grabbed** — curve handles, trim handles,
> a filmstrip scrubber, a future keyframed crop. Found on the speed graph 2026-08-17, where it made
> vertical drags almost impossible; the trim handles survive it for a reason worth copying.

## The rule

`detectDragGestures` does **not** call `onDragStart` at the down event. It waits until the pointer has
traveled past **touch slop** (~8 dp) and reports *that* position. So the offset handed to
`onDragStart` is already a slop's distance from where the finger actually landed, **in the direction of
the drag**.

That matters for two different things, and they fail differently:

| What `onDragStart`'s offset is used for | Effect of the slop                                        | Verdict                       |
| --------------------------------------- | --------------------------------------------------------- | ----------------------------- |
| **Picking** which element was grabbed   | Shifts the grab zone by ~8 dp *in the drag direction*     | Broken when the zone is small |
| **Anchoring** the value being dragged   | Cancels out, if the delta is measured from the same frame | Safe — see below              |

## Why the speed graph broke and the trim handles did not

The graph's hit tolerance is `0.09` in **normalized graph space**, not pixels. Converted:

| axis              | usable extent | 0.09 tolerance | after ~8 dp slop |
| ----------------- | ------------- | -------------- | ---------------- |
| vertical (speed)  | ~96 dp        | **~8.6 dp**    | **~0.6 dp left** |
| horizontal (time) | ~276 dp       | ~25 dp         | ~17 dp left      |

A **vertical** drag — the entire purpose of the control — spent essentially its whole grab budget on
slop. Horizontal drags kept most of theirs. That asymmetry is why it survived review: every casual
check that "a drag moves a handle" happened to include horizontal movement.

The trim handles do the same post-slop hit test, but against `HANDLE_TOUCH_WIDTH = 48.dp`, so slop
costs ~17% of the zone rather than all of it. They also dodge the second failure entirely by using an
**anchored delta**:

```text
dragAnchorPx = pos.x            // post-slop position
dragAnchorMs = curStartMs       // value at that moment
// …then:
val targetMs = dragAnchorMs + pxToMs(change.position.x - dragAnchorPx)
```

Anchor and cursor come from the same frame, so the slop offset cancels and the handle never jumps.
**Copy this.** The alternative — mapping the absolute finger position straight to a value — teleports
the handle by a slop on every grab.

## The fix for picking

Record the true down position and hit-test *that*. The tap detector on the same node already sees it:

```text
detectTapGestures(onPress = { downPosition = it }, onTap = …, onLongPress = …)
// …then, in the drag detector:
onDragStart = {
    val index = SpeedCurveMath.nearestKeyIndex(
        latestCurve, geometry.tAt(downPosition.x), geometry.speedAt(downPosition.y),
    ) ?: -1
}
```

`onPress` fires on the down event, always before slop is exceeded, so the value is there in time.

## The generalizable trap

**A hit tolerance expressed as a fraction of a control is not a tolerance in dp.** Convert it on the
*shortest* axis before believing it, and compare against touch slop (~8 dp) and the Material minimum
target (48 dp). "0.09 of the graph" sounds generous and is 8.6 dp. Any future normalized-coordinate
control inherits this arithmetic.

## Detection checklist

- `rg 'onDragStart = \{ (offset|it|pos)' app/src/main` — any hit test, snap, or selection using the
  callback's own position is suspect. Anchoring uses are fine *if* the delta is measured from the same
  frame; absolute-position mapping is not.
- For each normalized hit tolerance compute `tolerance × usableDp` on **both** axes. Under ~16 dp on
  either axis → needs the down-position fix or a bigger tolerance.
- The test that catches it — press at the element's *computed* position and assert the drag moved it,
  driving the geometry from production code rather than guessing a screen coordinate:

  ```text
  val insetPx = with(composeTestRule.density) { GRAPH_INSET.toPx() }
  onNodeWithTag("speed_curve_graph").performTouchInput {
      val geo = SpeedGraphGeometry(width.toFloat(), height.toFloat(), insetPx)
      val handle = Offset(geo.xOf(0.5f), geo.yOf(1.5f))
      down(handle); repeat(6) { moveTo(…) }; up()
  }
  ```

  A test that presses at `center` and hopes is testing the coordinate guess, not the control. Note also
  that **`adb input swipe` cannot distinguish this bug from a working control** — both look like
  "nothing happened" — so the proof has to be an instrumented test.

## Reference

- `ui/components/SpeedCurvePanel.kt` (`downPosition`, recorded in `onPress`, consumed by `onDragStart`),
  `ui/components/SpeedCurveMath.kt` (`nearestKeyIndex`, `TOUCH_TOLERANCE`).
- `ui/components/TrimFilmstripControls.kt` — the anchored-delta pattern, and a hit zone big enough to
  absorb slop.
- `SpeedTabPanelCurveTest.draggingAHandleActsOnTheLatestCurveNotTheOneCapturedAtFirstComposition`.
- Sibling trap in the same detectors: [[034-pointerinput-key-freezes-its-lambda]].
