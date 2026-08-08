# Lesson 030 — `coerceIn` with runtime-derived bounds throws on short clips; clamp the bound and JVM-test the math

## What went wrong

`FilmstripTrimSelector` in `ui/components/TrimFilmstripControls.kt` clamped a dragged handle
position into the valid trim window with bounds derived from runtime state:

```kotlin
// END handle drag
val clampedEnd = targetMs.coerceIn(curStartMs + minGapMs, durationMs)
// START handle drag
val clampedStart = targetMs.coerceIn(0L, curEndMs - minGapMs)
```

Kotlin's `coerceIn(min, max)` **throws `IllegalArgumentException` when `max < min`** instead of
silently clamping. Nothing floors a clip's duration before it reaches the Trim screen — both the
capture-finalize path and the gallery-import path in `OpenLoopViewModel` set
`trimEndMs = durationMs` as-is — so a tap-and-release capture shorter than
`MIN_TRIM_DURATION` (400 ms) inverted the range on the first handle drag or TalkBack
`setProgress`: `coerceIn(400, 335)`.

Crashlytics issue `7169b499fee6554684dba69b1ae1a8f0` (first seen v1.0.27):
"Cannot coerce value to an empty range: maximum 335 is less than minimum 400."

The same expression was duplicated inline at **four** call sites (drag + accessibility, per
handle), none of them reachable by a JVM test while inline in a composable lambda.

## Pattern

1. **Never call `coerceIn(min, max)` where either bound is an arithmetic expression over runtime
   state without clamping the bound itself first.** Keep the range valid by construction:

   ```kotlin
   // START handle: floor the upper bound at 0
   fun clampTrimStartMs(targetMs: Long, endMs: Long, minGapMs: Long): Long =
       targetMs.coerceIn(0L, (endMs - minGapMs).coerceAtLeast(0L))

   // END handle: cap the lower bound at the clip duration
   fun clampTrimEndMs(targetMs: Long, startMs: Long, durationMs: Long, minGapMs: Long): Long =
       targetMs.coerceIn((startMs + minGapMs).coerceAtMost(durationMs), durationMs)
   ```

   On a sub-400 ms clip both handles pin (start at 0, end at `durationMs`) — correct UX, since the
   NEXT button and all editor-advance actions are already disabled by `trimValid` in
   `TrimScreen.kt`, and `OpenLoopViewModel.updateTrimWindow` ignores sub-minimum windows anyway.

2. **Extract that math into a pure, import-free file** (`ui/components/TrimHandleMath.kt`) instead
   of fixing it inline in each lambda — the same pure-logic split as `media/BoomerangSequence.kt`.
   The regression then locks in via a plain JVM test (`TrimHandleMathTest`), including the exact
   crash values (335 ms vs 400 ms) and a duration sweep asserting the clamp is total.

## Detection checklist

- Grep for `coerceIn` calls whose arguments contain `+` or `-`:
  `rg 'coerceIn\([^)]*[+-]' app/src/main`. Every hit needs either a proof the range can't invert
  or a `coerceAtLeast`/`coerceAtMost` on the derived bound.
- Any screen that receives a media duration must be audited with a duration **below** every
  minimum constant it uses (and with 0).
- Clamp/geometry math living inline in a composable lambda is untestable on the JVM — extract it.
- A clamp that pins both handles is a crash guard, not UX. Gate at the **entry points** too:
  `onVideoPicked` and the capture `Finalize` handler both reject a sub-`MIN_TRIM_DURATION` clip
  with friendly copy, so a too-short clip never reaches Trim through a normal path.

## Reference

- Kotlin stdlib `coerceIn` (throws on empty ranges): https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.ranges/coerce-in.html
- Originating crash: Crashlytics `7169b499fee6554684dba69b1ae1a8f0`, GitHub issue #95
