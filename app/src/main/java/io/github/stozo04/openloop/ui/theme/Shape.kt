package io.github.stozo04.openloop.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * OpenLoop shape scale (Issue #35, section 1) — collapses the ad-hoc radii (4/6/8/12/14/16/20/28/32)
 * that were sprinkled across screens into one intentional ramp:
 *
 * - `extraSmall` 8  — chips, small tiles
 * - `small`      12 — cards, thumbnails
 * - `medium`     16 — primary buttons, dialogs
 * - `large`      24 — sheets, large surfaces
 * - `extraLarge` 32 — pill CTAs / hero rounding
 *
 * Use `MaterialTheme.shapes.*`. Fully-round elements (circles, pills) still use [RoundedCornerShape]
 * with `percent = 50` / [androidx.compose.foundation.shape.CircleShape] directly.
 */
val OpenLoopShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
