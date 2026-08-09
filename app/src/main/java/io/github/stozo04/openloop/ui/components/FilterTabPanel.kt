package io.github.stozo04.openloop.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.stozo04.openloop.media.VideoFilter
import io.github.stozo04.openloop.ui.theme.ElectricLime
import io.github.stozo04.openloop.ui.theme.SurfaceContainer
import io.github.stozo04.openloop.ui.theme.SurfaceContainerHigh
import io.github.stozo04.openloop.ui.theme.TextSecondary
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val FILTER_PANEL_CORNER = 16.dp
private val FILTER_PANEL_MAX_WIDTH = 520.dp
private val THUMB_SIZE = 64.dp
private val THUMB_CORNER = 14.dp
private val THUMB_SELECTED_BORDER = 3.dp
private val CHIP_WIDTH = 72.dp

/**
 * Filter tab matching the reference mock: dark rounded card, "Choose a look" title, and a horizontal
 * strip of look thumbnails with labels (lime border + label when selected).
 */
@Composable
fun FilterTabPanel(
    filter: VideoFilter,
    thumbnailFrame: Bitmap?,
    onFilterChange: (VideoFilter) -> Unit,
    modifier: Modifier = Modifier,
    disabledHint: String? = null,
) {
    val haptics = LocalHapticFeedback.current
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("filter_tab_panel"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = FILTER_PANEL_MAX_WIDTH)
                .clip(RoundedCornerShape(FILTER_PANEL_CORNER))
                .background(SurfaceContainer)
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Choose a look",
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .testTag("filter_tab_title"),
            )
            if (!disabledHint.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = disabledHint,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                        .testTag("filter_tab_disabled_hint"),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                VideoFilter.entries.forEach { look ->
                    // Chips stay tappable even while [disabledHint] shows: a tap re-probes memory in
                    // OpenLoopViewModel.updateFilter, which is the real gate. A disabled strip made
                    // "tap a look to retry" unreachable (lesson 026).
                    FilterLookChip(
                        look = look,
                        thumbnailFrame = thumbnailFrame,
                        selected = look == filter,
                        onClick = {
                            if (look == filter) return@FilterLookChip
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onFilterChange(look)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterLookChip(
    look: VideoFilter,
    thumbnailFrame: Bitmap?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val thumbShape = RoundedCornerShape(THUMB_CORNER)
    val imageBitmap = remember(thumbnailFrame) { thumbnailFrame?.asImageBitmap() }
    val colorFilter = remember(look) { look.thumbnailColorFilter() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(CHIP_WIDTH),
    ) {
        Box(
            modifier = Modifier
                .size(THUMB_SIZE)
                .clip(thumbShape)
                .background(SurfaceContainerHigh)
                .then(
                    if (selected) {
                        Modifier.border(THUMB_SELECTED_BORDER, ElectricLime, thumbShape)
                    } else {
                        Modifier
                    },
                )
                .clickable(role = Role.Button, onClick = onClick)
                .semantics {
                    contentDescription = look.label
                    this.selected = selected
                    role = Role.Button
                }
                .testTag("look_chip_${look.name}"),
            contentAlignment = Alignment.Center,
        ) {
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = colorFilter,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = look.label,
            color = if (selected) ElectricLime else TextSecondary,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Compose [ColorFilter] for chip thumbnails — same look parameters as [VideoFilter.toMediaEffects].
 * Approximations only (Compose ColorMatrix vs Media3 GL shaders); keep the numbers aligned.
 */
private fun VideoFilter.thumbnailColorFilter(): ColorFilter? {
    val matrix = when (this) {
        VideoFilter.ORIGINAL -> return null
        VideoFilter.NOIR -> ColorMatrix().apply { setToSaturation(0f) }
        VideoFilter.WARM -> rgbScaleMatrix(red = 1.15f, green = 1f, blue = 0.85f)
        VideoFilter.COOL -> rgbScaleMatrix(red = 0.85f, green = 1f, blue = 1.15f)
        VideoFilter.POP -> ColorMatrix().apply { setToSaturation(1.4f) }
        VideoFilter.INVERT -> ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        VideoFilter.SEPIA -> ColorMatrix(
            floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        VideoFilter.PARTY -> hueRotateMatrix(140f)
        VideoFilter.PUNCH -> contrastMatrix(0.45f)
        // Matches HslAdjustment.adjustLightness(18) as a simple RGB lift for the chip.
        VideoFilter.GLOW -> ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, 45f,
                0f, 1f, 0f, 0f, 45f,
                0f, 0f, 1f, 0f, 45f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        VideoFilter.FADE -> ColorMatrix().apply {
            setToSaturation(0.75f)
            // Soft lift so the chip reads "washed" vs Punch (Contrast -0.35 + lightness 8).
            values[4] += 20f
            values[9] += 20f
            values[14] += 20f
        }
        VideoFilter.MINT -> rgbScaleMatrix(red = 0.88f, green = 1.18f, blue = 1.05f)
        VideoFilter.CANDY -> rgbScaleMatrix(red = 1.22f, green = 0.88f, blue = 1.15f)
    }
    return ColorFilter.colorMatrix(matrix)
}

private fun rgbScaleMatrix(red: Float, green: Float, blue: Float): ColorMatrix = ColorMatrix(
    floatArrayOf(
        red, 0f, 0f, 0f, 0f,
        0f, green, 0f, 0f, 0f,
        0f, 0f, blue, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    ),
)

/** Same contrastFactor formula as Media3 [androidx.media3.effect.Contrast], translated to 0–255. */
private fun contrastMatrix(contrast: Float): ColorMatrix {
    val factor = (1f + contrast) / (1.0001f - contrast)
    val translate = (1f - factor) * 0.5f * 255f
    return ColorMatrix(
        floatArrayOf(
            factor, 0f, 0f, 0f, translate,
            0f, factor, 0f, 0f, translate,
            0f, 0f, factor, 0f, translate,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}

/**
 * Approximate HSL hue rotation for chip thumbs (luma-preserving rotation matrix).
 * Preview/export use Media3 [HslAdjustment]; this is the closest cheap Compose stand-in.
 */
private fun hueRotateMatrix(degrees: Float): ColorMatrix {
    val rad = degrees * PI.toFloat() / 180f
    val cosA = cos(rad)
    val sinA = sin(rad)
    val lumR = 0.213f
    val lumG = 0.715f
    val lumB = 0.072f
    return ColorMatrix(
        floatArrayOf(
            lumR + cosA * (1 - lumR) + sinA * (-lumR),
            lumG + cosA * (-lumG) + sinA * (-lumG),
            lumB + cosA * (-lumB) + sinA * (1 - lumB),
            0f, 0f,
            lumR + cosA * (-lumR) + sinA * 0.143f,
            lumG + cosA * (1 - lumG) + sinA * 0.140f,
            lumB + cosA * (-lumB) + sinA * (-0.283f),
            0f, 0f,
            lumR + cosA * (-lumR) + sinA * (-(1 - lumR)),
            lumG + cosA * (-lumG) + sinA * lumG,
            lumB + cosA * (1 - lumB) + sinA * lumB,
            0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}
