@file:OptIn(ExperimentalFoundationApi::class)

package io.github.stozo04.openloop.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stozo04.openloop.media.VideoFilter
import io.github.stozo04.openloop.ui.theme.ElectricLime
import io.github.stozo04.openloop.ui.theme.SurfaceContainer
import io.github.stozo04.openloop.ui.theme.SurfaceContainerHigh
import io.github.stozo04.openloop.ui.theme.TextSecondary
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Compact thumbs so ~4.5 look chips peek on a phone — scroll affordance without a carousel.
private val FILTER_PANEL_CORNER = 16.dp
private val FILTER_PANEL_MAX_WIDTH = 520.dp
private val THUMB_SIZE = 56.dp
private val THUMB_CORNER = 12.dp
private val THUMB_SELECTED_BORDER = 3.dp
private val CHIP_WIDTH = 64.dp
private val CHIP_SPACING = 10.dp

/**
 * Looks picker: horizontal scroll of live thumbnails (camera-app pattern for visual looks —
 * Material [androidx.compose.material3.FilterChip] is for text filters, not photo previews).
 * Selected chip is brought into view when the strip opens or the selection changes.
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
                .padding(vertical = 14.dp),
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
                Spacer(modifier = modifier.height(8.dp))
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
            Spacer(modifier = modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
                    .padding(horizontal = 12.dp)
                    .testTag("filter_look_strip"),
                horizontalArrangement = Arrangement.spacedBy(CHIP_SPACING),
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
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    LaunchedEffect(selected) {
        if (selected) bringIntoViewRequester.bringIntoView()
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(CHIP_WIDTH)
            .bringIntoViewRequester(bringIntoViewRequester),
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
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = look.label,
            color = if (selected) ElectricLime else TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Compose [ColorFilter] for chip thumbnails — same look parameters as [VideoFilter.toMediaEffects].
 */
internal fun VideoFilter.thumbnailColorFilter(): ColorFilter? {
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
            // Soft lift so the chip reads "washed" vs Punch.
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

private fun hueRotateMatrix(degrees: Float): ColorMatrix {
    val rad = degrees * PI.toFloat() / 180f
    val cos = cos(rad)
    val sin = sin(rad)
    val lumR = 0.213f
    val lumG = 0.715f
    val lumB = 0.072f
    return ColorMatrix(
        floatArrayOf(
            lumR + cos * (1 - lumR) + sin * (-lumR),
            lumG + cos * (-lumG) + sin * (-lumG),
            lumB + cos * (-lumB) + sin * (1 - lumB),
            0f, 0f,
            lumR + cos * (-lumR) + sin * 0.143f,
            lumG + cos * (1 - lumG) + sin * 0.140f,
            lumB + cos * (-lumB) + sin * (-0.283f),
            0f, 0f,
            lumR + cos * (-lumR) + sin * (-(1 - lumR)),
            lumG + cos * (-lumG) + sin * lumG,
            lumB + cos * (1 - lumB) + sin * lumB,
            0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}
