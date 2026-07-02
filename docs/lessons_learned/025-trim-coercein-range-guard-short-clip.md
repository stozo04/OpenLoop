# 025 — Guard `coerceIn` bounds before calling when range can be inverted by a short clip

## What went wrong

`FilmstripTrimSelector` in `TrimFilmstripControls.kt` called `coerceIn` to clamp a drag position into the valid trim range:

```kotlin
// END handle drag
val clamped = targetMs.coerceIn(curStartMs + minGapMs, durationMs)
// START handle drag
val clamped = targetMs.coerceIn(0L, curEndMs - minGapMs)
```

When a clip shorter than `MIN_TRIM_DURATION` (400 ms) reached the Trim screen — e.g., a 335 ms recording — the minimum bound exceeded the maximum: `coerceIn(400, 335)`. Kotlin's `Long.coerceIn` throws an `IllegalArgumentException` on an inverted range instead of silently clamping.

Crashlytics issue `7169b499fee6554684dba69b1ae1a8f0` (v1.0.27): "Cannot coerce value to an empty range: maximum 335 is less than minimum 400."

## Pattern

Before calling `coerceIn(min, max)` where either bound is derived from a user-controlled value (not a compile-time constant), clamp the bound itself to keep the range valid:

```kotlin
// END handle: ensure min ≤ durationMs
val minMs = (curStartMs + minGapMs).coerceAtMost(durationMs)
val clamped = targetMs.coerceIn(minMs, durationMs)

// START handle: ensure max ≥ 0
val maxMs = (curEndMs - minGapMs).coerceAtLeast(0L)
val clamped = targetMs.coerceIn(0L, maxMs)
```

With a sub-400 ms clip both handles become immovable (start pinned to 0, end pinned to `durationMs`), which is the correct UX — the NEXT button is already disabled by `trimValid` in `TrimScreen.kt`.

## Detection checklist

- Grep for `coerceIn` where either argument is an arithmetic expression: `\.coerceIn\([^,)]*[+\-][^,)]*,` or `, .*[+\-].*\)`.
- Any call of the form `coerceIn(A + K, B)` or `coerceIn(A, B - K)` needs a pre-clamp when `A`, `B`, or `K` come from runtime state.

## Reference

- Kotlin stdlib `Long.coerceIn`: https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.ranges/coerce-in.html
- Originating crash: Crashlytics issue `7169b499fee6554684dba69b1ae1a8f0`, first seen v1.0.27
- Fix: PR resolving GitHub issue #95
