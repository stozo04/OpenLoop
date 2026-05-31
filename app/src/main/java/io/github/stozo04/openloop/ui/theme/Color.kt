package io.github.stozo04.openloop.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * OpenLoop design tokens — the single source of truth for color (Issue #35, section 1).
 *
 * Roles (decided in the color/type alignment): **Electric Lime is the one primary accent**, used flat
 * for primary actions and active states; **coral-red is semantic only** (recording + destructive);
 * **aqua is a rare tertiary** used as the shutter gradient's far end. Surfaces are a neutral near-black
 * ramp so the video content and the single accent are the only saturated things on screen.
 *
 * UI must read these via [io.github.stozo04.openloop.ui.theme.OpenLoopTheme] /
 * `MaterialTheme.colorScheme` rather than inlining hex (Lesson 001 — every literal here is 8 hex digits).
 */

// ── Brand accents ──
val ElectricLime = Color(0xFFCDFF4F)      // primary
val LimeInk = Color(0xFF15200A)           // onPrimary — near-black text on lime
val LimeContainer = Color(0xFF2A3A12)     // primaryContainer
val OnLimeContainer = Color(0xFFE6FFB0)   // onPrimaryContainer

val Aqua = Color(0xFF34E1D5)              // secondary / tertiary + shutter gradient end
val OnAqua = Color(0xFF00201D)
val AquaContainer = Color(0xFF0C3A36)
val OnAquaContainer = Color(0xFF9FF2EA)

// ── Semantic: recording + destructive ──
val CoralRed = Color(0xFFFF5A5F)          // error (delete) + recording indicator
val OnCoral = Color(0xFF1A0405)
val CoralContainer = Color(0xFF5C1A1C)
val OnCoralContainer = Color(0xFFFFD9DA)

// ── Neutral near-black surface ramp ──
val Canvas = Color(0xFF0A0A0C)            // background (replaces the old per-screen colored gradients)
val SurfaceBase = Color(0xFF101014)       // surface
val SurfaceContainerLow = Color(0xFF131318)
val SurfaceContainer = Color(0xFF17171D)
val SurfaceContainerHigh = Color(0xFF1F1F27)
val SurfaceContainerHighest = Color(0xFF26262E)

val Outline = Color(0xFF3A3A45)
val OutlineVariant = Color(0xFF26262E)

val TextPrimary = Color(0xFFF3F3F6)       // onSurface / onBackground
val TextSecondary = Color(0xFFADADB8)     // onSurfaceVariant — captions, secondary copy

// ── Overlays for controls drawn OVER live video / a video preview ──
// These intentionally stay translucent-white: a solid surface token can't read over arbitrary footage.
val OverlayWhite = Color(0x33FFFFFF)         // glassy control fill (was GlassWhite)
val OverlayWhiteBorder = Color(0x4DFFFFFF)   // glassy control border (was GlassWhiteBorder)
val OverlayScrim = Color(0xCC121216)         // chip/scrim behind labels over video (was DeepCharcoal)
