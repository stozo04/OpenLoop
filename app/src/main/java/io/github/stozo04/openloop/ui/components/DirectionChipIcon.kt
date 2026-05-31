package io.github.stozo04.openloop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.stozo04.openloop.media.BoomerangMode

private val ChipArrowSize = 16.dp
private val ChipArrowOverlap = (-3).dp

/**
 * Direction-chip glyph: pairs of play chevrons (forward / reverse / combined modes)
 * using Material vectors instead of Unicode arrows.
 */
@Composable
fun DirectionChipIcon(
    mode: BoomerangMode,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    when (mode) {
        BoomerangMode.FORWARD -> DoublePlayArrow(tint, forward = true, modifier)
        BoomerangMode.REVERSE -> DoublePlayArrow(tint, forward = false, modifier)
        BoomerangMode.FORWARD_THEN_REVERSE -> PlayPair(tint, firstForward = true, modifier)
        BoomerangMode.REVERSE_THEN_FORWARD -> PlayPair(tint, firstForward = false, modifier)
    }
}

@Composable
private fun DoublePlayArrow(
    tint: Color,
    forward: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ChipArrowOverlap),
    ) {
        PlayChevron(tint, forward)
        PlayChevron(tint, forward)
    }
}

@Composable
private fun PlayPair(
    tint: Color,
    firstForward: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ChipArrowOverlap),
    ) {
        PlayChevron(tint, firstForward)
        PlayChevron(tint, !firstForward)
    }
}

@Composable
private fun PlayChevron(tint: Color, forward: Boolean) {
    Icon(
        imageVector = Icons.Filled.PlayArrow,
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .size(ChipArrowSize)
            .graphicsLayer { if (!forward) scaleX = -1f },
    )
}
