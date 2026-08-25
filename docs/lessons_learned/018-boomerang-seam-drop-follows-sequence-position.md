# Lesson 018 — The boomerang seam-frame drop follows SEQUENCE POSITION, not clip identity

> Origin: Slice 03 (Direction tab, `feature/boomerang-slice-03-direction-tab`). Promoted from the
> slice-03 KICKOFF after the device pass confirmed all four directions play seam-clean.

## What went wrong (latent)

A boomerang concatenates clips, and where one clip plays in the *opposite* temporal direction to the
clip before it (forward↔reversed), the two share a boundary frame — the first clip's last rendered
frame is pixel-identical to the second's first. Played back, that duplicate reads as a freeze/stutter
at the seam, so the trailing clip must drop its leading frame (parent IMPLEMENTATION §6.4).

Slice 02 only ever rendered `FORWARD_THEN_REVERSE`, where the reversed clip is *always* second. So it
hard-coded the drop onto **"the reversed clip"** (`reverseItem()` always did `setStartPositionMs(seamMs)`).
That was correct *by coincidence* — "the reversed clip" and "the second clip" were the same thing.

Slice 03 makes direction user-selectable, and the coincidence breaks two ways:

| mode                   | sequence     | needs drop on         | the identity-based code did…                             |
| ---------------------- | ------------ | --------------------- | -------------------------------------------------------- |
| `FORWARD`              | `[fwd]`      | nothing               | nothing ✓                                                |
| `REVERSE`              | `[rev]`      | **nothing (no seam)** | dropped the lone reversed clip → **lost a real frame ✗** |
| `FORWARD_THEN_REVERSE` | `[fwd, rev]` | rev (2nd)             | dropped rev ✓                                            |
| `REVERSE_THEN_FORWARD` | `[rev, fwd]` | **fwd (2nd)**         | dropped rev (1st) → **wrong clip; real seam left in ✗**  |

The same trap returns with repetitions (slice 05): `[fwd, rev, fwd, rev]` has a duplicate at *every*
internal turn — including the cycle boundary `rev→fwd` the slice-02 code never dropped.

## Pattern

Decide the drop by **position in the final sequence**, not by which clip it is. Extract it into a pure
function so the rule is unit-testable on the JVM without a codec (the `Transformer` needs a device —
see Lesson 008/017), mirroring how `MediaFormatUtils` was split out:

```kotlin
// media/BoomerangSequence.kt — drop a clip's leading frame iff it turns direction from the one before.
internal fun boomerangSequence(mode: BoomerangMode, repetitions: Int): List<ClipSpec> {
    val cycle = when (mode) {
        BoomerangMode.FORWARD -> listOf(ClipDirection.FORWARD)
        BoomerangMode.REVERSE -> listOf(ClipDirection.REVERSED)
        BoomerangMode.FORWARD_THEN_REVERSE -> listOf(ClipDirection.FORWARD, ClipDirection.REVERSED)
        BoomerangMode.REVERSE_THEN_FORWARD -> listOf(ClipDirection.REVERSED, ClipDirection.FORWARD)
    }
    val directions = (0 until repetitions.coerceAtLeast(1)).flatMap { cycle }
    return directions.mapIndexed { i, dir ->
        ClipSpec(dir, dropLeadingFrame = i > 0 && dir != directions[i - 1])
    }
}
```

The first clip never drops (nothing precedes it); same-direction neighbors (`FORWARD`×N, `REVERSE`×N)
never drop (no shared boundary frame); the two-part modes drop every clip after the first. The render
maps each `ClipSpec` to its `EditedMediaItem`, applying the one-frame `setStartPositionMs` offset only
when `dropLeadingFrame` is true. The preview applies the same rule.

## Detection checklist

- A seam/drop offset baked into a clip *builder* keyed on the clip's nature ("the reversed clip") is
  the smell — grep `VideoProcessor.kt` for `setStartPositionMs` and confirm the offset comes from a
  per-position decision, not a fixed property of `reverseItem`/`forwardItem`.
- `boomerangSequence(REVERSE, 1)` must yield **no** drop; `boomerangSequence(REVERSE_THEN_FORWARD, 1)`
  must drop the **forward** (second) clip. These two cases are the slice-02 regressions — keep them as
  explicit unit tests (`BoomerangSequenceTest`).
- On device, eyeball the seam on standalone `REVERSE` (no lost frame) and `REVERSE_THEN_FORWARD` (no
  freeze at the turn) — the emulator hides codec-level seam artifacts; trust the Fold.

## Reference

- Parent design: the seam-handling section of the old `PRD-mission-control.md` (restructured 2026-08-24 — see git history); the living spec is the code + test on the next line.
- `app/src/main/java/io/github/stozo04/openloop/media/BoomerangSequence.kt` + `BoomerangSequenceTest.kt`.
- Related: [[008-jvm-test-file-and-dispatcher-pitfalls]] / [[017-androidtest-no-mockk-and-sweep-meaningful-mock-returns]] (why the seam math is pure-JVM but the encoded is instrumented).
