package io.github.stozo04.openloop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush

/**
 * Dark-only Material 3 scheme for OpenLoop. Electric Lime is `primary` (flat, the one accent), coral-red
 * is `error` (recording + destructive), aqua is the tertiary used for the shutter's signature gradient,
 * and everything else is the neutral near-black ramp. Tokens live in [Color.kt]; type in [Type.kt];
 * shapes in [Shape.kt].
 */
private val OpenLoopDarkColorScheme = darkColorScheme(
    primary = ElectricLime,
    onPrimary = LimeInk,
    primaryContainer = LimeContainer,
    onPrimaryContainer = OnLimeContainer,

    secondary = Aqua,
    onSecondary = OnAqua,
    secondaryContainer = AquaContainer,
    onSecondaryContainer = OnAquaContainer,

    tertiary = Aqua,
    onTertiary = OnAqua,
    tertiaryContainer = AquaContainer,
    onTertiaryContainer = OnAquaContainer,

    background = Canvas,
    onBackground = TextPrimary,

    surface = SurfaceBase,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceContainerHigh,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = Canvas,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,

    error = CoralRed,
    onError = OnCoral,
    errorContainer = CoralContainer,
    onErrorContainer = OnCoralContainer,

    outline = Outline,
    outlineVariant = OutlineVariant,

    scrim = Canvas,
    surfaceTint = ElectricLime,
)

@Composable
fun OpenLoopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OpenLoopDarkColorScheme,
        typography = OpenLoopTypography,
        shapes = OpenLoopShapes,
        content = content,
    )
}

/**
 * The app's one signature gradient — Lime → Aqua — reserved for the camera shutter. Everything else
 * uses flat [ElectricLime]. A function (not a val) so callers can drop it straight into a `background`
 * / `Brush`-taking modifier; the two stops are brand tokens, so it stays in sync with the theme.
 */
fun shutterGradient(): Brush = Brush.linearGradient(listOf(ElectricLime, Aqua))
