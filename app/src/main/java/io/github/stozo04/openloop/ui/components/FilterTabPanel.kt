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
    filtersEnabled: Boolean = true,
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
                    val chipEnabled = filtersEnabled || look == VideoFilter.ORIGINAL
                    FilterLookChip(
                        look = look,
                        thumbnailFrame = thumbnailFrame,
                        selected = look == filter,
                        enabled = chipEnabled,
                        onClick = {
                            if (!chipEnabled || look == filter) return@FilterLookChip
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
    enabled: Boolean = true,
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
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
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
            color = when {
                selected -> ElectricLime
                !enabled -> TextSecondary.copy(alpha = 0.45f)
                else -> TextSecondary
            },
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Compose [ColorFilter] for chip thumbnails — same parameters as [VideoFilter.toMediaEffects].
 */
private fun VideoFilter.thumbnailColorFilter(): ColorFilter? {
    val matrix = when {
        grayscale -> ColorMatrix().apply { setToSaturation(0f) }
        saturation != 0f -> ColorMatrix().apply { setToSaturation(1f + saturation / 100f) }
        redScale != 1f || blueScale != 1f -> ColorMatrix(
            floatArrayOf(
                redScale, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, blueScale, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        else -> return null
    }
    return ColorFilter.colorMatrix(matrix)
}
