# Lesson 035 — `onDragStart` fires a touch-slop away from the finger; hit-test the **down** position

> Origin: speed curves (`docs/PRD-speed-curves.md`), 2026-08-17. Found by a Compose test written to prove
> something else entirely — the graph's handles were nearly impossible to grab with a vertical drag, and
> every manual check had missed it.

## What went wrong

`detectDragGestures` does not call `onDragStart` at the down event. It waits until the pointer has
travelled past **touch slop** (~8 dp) and reports *that* position:

```kotlin
// WRONG — `offset` is already a slop's distance from where the user actually pressed
detectDragGestures(
    onDragStart = { offset ->
        val index = SpeedCurveMath.nearestKeyIndex(
            latestCurve, geometry.tAt(offset.x), geometry.speedAt(offset.y),
        ) ?: -1
        draggingIndex = index
    },
)
```

The handle hit test is a `TOUCH_TOLERANCE` of `0.09` in **normalized graph space**, not in pixels. Work
out what that is on a 120 dp-tall graph:

| axis | usable extent | 0.09 tolerance | slop eats |
|---|---|---|---|
| vertical (speed) | ~96 dp | **~8.6 dp** | ~8 dp → **~0.6 dp left** |
| horizontal (time) | ~276 dp | ~25 dp | ~8 dp → ~17 dp left |

So a **vertical** drag — dragging a handle up or down to change its speed, the entire purpose of the
control — spent essentially its whole grab budget on slop and the hit test missed. Horizontal drags kept
most of their margin and worked fine.

That asymmetry is why this survived review: every casual manual check that "a drag moves a handle"
happened to include horizontal movement, and a diagonal drag succeeds often enough to look fine. It took
a Compose test pressing *exactly* on the handle's computed position and still missing to expose it.

## Pattern

- **Record the down position and hit-test against that.** The tap detector already sees it:
  ```kotlin
  detectTapGestures(onPress = { downPosition = it }, onTap = …, onLongPress = …)
  // …then, in the drag detector on the same node:
  onDragStart = {
      val index = SpeedCurveMath.nearestKeyIndex(
          latestCurve, geometry.tAt(downPosition.x), geometry.speedAt(downPosition.y),
      ) ?: -1
  }
  ```
  `onPress` fires on the down event, always before slop is exceeded, so the value is there by the time
  `onDragStart` runs.
- **A tolerance in normalized space is not a tolerance in dp.** Whenever a hit test is expressed as a
  fraction of a control, convert it to dp on the *smallest* axis before believing it — and compare it to
  touch slop (~8 dp) and to the Material minimum target (48 dp). A 0.09 tolerance sounds generous and is
  8.6 dp.
- The same reasoning applies to any future canvas control (a trim scrubber, a keyframed crop): the axis
  that is short in dp is the axis where the hit test quietly stops working.

## Detection checklist

- `rg 'onDragStart = \{ (offset|it)' app/src/main` — any hit test, snap, or selection inside
  `onDragStart` that uses the callback's own position is suspect. Movement-only uses (deltas, anchors
  computed from an already-known index) are fine.
- For each normalized hit tolerance: `tolerance × usableHeightDp` and `tolerance × usableWidthDp`. If
  either is under ~16 dp, the control needs the down-position fix or a bigger tolerance.
- The test that catches it — press at the handle's *computed* position and assert the drag actually
  moved it, driving the geometry from production code rather than guessing a screen coordinate:
  ```kotlin
  val insetPx = with(composeTestRule.density) { GRAPH_INSET.toPx() }
  onNodeWithTag("speed_curve_graph").performTouchInput {
      val geo = SpeedGraphGeometry(width.toFloat(), height.toFloat(), insetPx)
      val handle = Offset(geo.xOf(0.5f), geo.yOf(1f))
      down(handle); repeat(6) { moveTo(…) }; up()
  }
  ```
  A test that presses at `center` and hopes is testing the coordinate guess, not the control.

## Reference

- `ui/components/SpeedCurvePanel.kt` (`downPosition`, recorded in `onPress`, consumed by `onDragStart`),
  `ui/components/SpeedCurveMath.kt` (`nearestKeyIndex`, `TOUCH_TOLERANCE`).
- `SpeedTabPanelCurveTest.draggingAHandleActsOnTheLatestCurveNotTheOneCapturedAtFirstComposition`.
- Sibling trap in the same file: [[034-pointerinput-key-freezes-its-lambda]] — that one is about *when*
  a gesture lambda was captured, this one about *where* it thinks the finger is.
