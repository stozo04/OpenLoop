package io.github.stozo04.openloop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

private val ChipArrowSize = 14.dp
private val ChipArrowOverlap = (-4).dp

/**
 * Loop-mode glyphs for the Loop tab: double chevrons (forward / reverse) and stacked ping-pong arrows
 * (forward-then-reverse vs. the inverse reverse-then-forward).
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
        BoomerangMode.FORWARD_THEN_REVERSE -> BoomerangPingPongIcon(tint, topForward = true, modifier)
        BoomerangMode.REVERSE_THEN_FORWARD -> BoomerangPingPongIcon(tint, topForward = false, modifier)
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

/**
 * Stacked play arrows: [topForward] on top, opposite direction below (boomerang vs. reverse-boomerang).
 */
@Composable
private fun BoomerangPingPongIcon(
    tint: Color,
    topForward: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.size(24.dp),
        verticalArrangement = Arrangement.spacedBy((-2).dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        PlayChevron(tint, forward = topForward)
        PlayChevron(tint, forward = !topForward)
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
