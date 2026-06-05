# Lesson 001 — Compose `Color()` expects exactly 8 hex digits

## What went wrong

PR #5 introduced `Color(0xFFFF7C4DFF)` — a 10-hex-digit `ULong` literal — for the `secondary` color in `OpenLoopTheme`. The intended value was `Color(0xFF7C4DFF)` (NeonPurple). The extra `FF` inserted between the alpha and R channels would have either silently truncated to a wrong color or produced an entirely different one across every composable that reads `MaterialTheme.colorScheme.secondary`.

This was not caught by the compiler (literal is a valid `Long`) and not caught by any test (no UI regression test asserts the resolved color). It would have shipped a broken design system.

## Pattern

Compose's `Color(value: Long)` constructor expects exactly **8 hex digits** in `0xAARRGGBB` format (32 bits). When defining a color:

1. Count the hex digits after `0x` — there must be exactly 8. 6 means missing alpha; 10+ means a typo.
2. Prefer named constants in the design system file over inline hex literals:
   ```kotlin
   private val NeonPurple = Color(0xFF7C4DFF)
   private val NeonCoral  = Color(0xFFFF5252)
   ```
   Centralizing colors makes typos cheaper to catch and easier to fix in one place.
3. Cross-reference any new color against the design tokens table in `docs/PRD-mission-control.md`.

## Detection checklist

- Search for `Color(0x` in the diff; verify each match has exactly 8 digits between `0x` and `)`.
  ```
  grep -rn "Color(0x" app/src --include="*.kt"
  ```
- For any theme-level color change, confirm it matches the design tokens table.

## Reference

- [Compose Color API](https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/Color)
- PR #5 FAIL #1
