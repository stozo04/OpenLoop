package io.github.stozo04.openloop.media

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure layout math for the photo-booth strip (docs/PRD-photo-booth.md §5.2), kept free of
 * Android imports so it is JVM-testable ([io.github.stozo04.openloop.media.BoothStripLayoutTest])
 * — same split as [boomerangSequence] and `ui/components/TrimHandleMath.kt`. The thin Canvas pass
 * that consumes this lives in `BoothStripComposer.kt`.
 */

/** How many shots one booth sequence takes (PRD D1 — proof of concept; classic 4 can come later). */
const val BOOTH_FRAME_COUNT = 3

/**
 * Vertical crop bias on a tall source: the fraction of the cropped-away slack placed *above* the
 * square (PRD D3). 0.5 would be the geometric center — which clips the crown and any lens that
 * sits above the eyes (Elvis pompadour at anatomy +1.25, broccoli) on a 9:19.5 selfie. 0.25 keeps
 * three quarters of the slack below the window, so the face and its lens hair stay in frame.
 */
internal const val BOOTH_TOP_BIAS = 0.25f

/** Border and footer sized proportionally to the frame: ~32 px / ~160 px at a 1080² frame. */
private const val BORDER_FRACTION = 32f / 1080f
private const val FOOTER_FRACTION = 160f / 1080f

/** An axis-aligned pixel rectangle — `android.graphics.Rect` without the Android import. */
data class BoothRect(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * The square crop window for one booth frame: `min(w, h)` on a side, cropping the source's long
 * axis. On a tall source the origin is **top-biased** ([BOOTH_TOP_BIAS]), not centered (PRD D3);
 * on a wide source the horizontal crop is centered (there is no crown to protect sideways).
 */
fun boothCropRect(sourceWidth: Int, sourceHeight: Int): BoothRect {
    val square = minOf(sourceWidth, sourceHeight)
    return if (sourceHeight >= sourceWidth) {
        BoothRect(0, ((sourceHeight - square) * BOOTH_TOP_BIAS).roundToInt(), square, square)
    } else {
        BoothRect((sourceWidth - square) / 2, 0, square, square)
    }
}

/**
 * The strip's overall geometry: [frameCount] squares stacked vertically, a [borderPx] white band
 * on every edge and between frames, and a [footerHeightPx] band at the bottom for the
 * "OPENLOOP · date" print. At a 1080² frame this is the PRD's ≈1144×3528.
 */
data class BoothStripLayout(
    val width: Int,
    val height: Int,
    val borderPx: Int,
    val footerHeightPx: Int,
    val frameRects: List<BoothRect>,
)

fun boothStripLayout(frameSize: Int, frameCount: Int = BOOTH_FRAME_COUNT): BoothStripLayout {
    val border = (frameSize * BORDER_FRACTION).roundToInt().coerceAtLeast(1)
    val footer = (frameSize * FOOTER_FRACTION).roundToInt()
    val frameRects = List(frameCount) { index ->
        BoothRect(border, border + index * (frameSize + border), frameSize, frameSize)
    }
    return BoothStripLayout(
        width = frameSize + 2 * border,
        height = frameCount * frameSize + (frameCount + 1) * border + footer,
        borderPx = border,
        footerHeightPx = footer,
        frameRects = frameRects,
    )
}

/** The printed footer line, e.g. `OPENLOOP · AUG 20 2026` (PRD D6). */
fun boothFooterText(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    "OPENLOOP · " + date.format(DateTimeFormatter.ofPattern("MMM d yyyy", locale)).uppercase(locale)
