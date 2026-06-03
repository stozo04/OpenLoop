package io.github.stozo04.openloop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.stozo04.openloop.media.BoomerangMode

/**
 * Loop-mode glyphs for the Loop tab: double chevrons (forward / reverse) and stacked ping-pong arrows
 * (forward-then-reverse vs. the inverse reverse-then-forward).
 */
@Composable
fun DirectionChipIcon(
    mode: BoomerangMode,
    tint: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 22.dp,
) {
    val chevronSize = if (iconSize >= 26.dp) 14.dp else 12.dp
    val doubleArrowOverlap = if (iconSize >= 26.dp) (-4).dp else (-3).dp
    val pingPongOverlap = (-2).dp

    when (mode) {
        BoomerangMode.FORWARD -> DoublePlayArrow(tint, forward = true, chevronSize, doubleArrowOverlap, modifier.size(iconSize))
        BoomerangMode.REVERSE -> DoublePlayArrow(tint, forward = false, chevronSize, doubleArrowOverlap, modifier.size(iconSize))
        BoomerangMode.FORWARD_THEN_REVERSE -> BoomerangPingPongIcon(tint, topForward = true, chevronSize, pingPongOverlap, modifier.size(iconSize))
        BoomerangMode.REVERSE_THEN_FORWARD -> BoomerangPingPongIcon(tint, topForward = false, chevronSize, pingPongOverlap, modifier.size(iconSize))
    }
}

@Composable
private fun DoublePlayArrow(
    tint: Color,
    forward: Boolean,
    chevronSize: Dp,
    overlap: Dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(overlap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayChevron(tint, forward, chevronSize)
        PlayChevron(tint, forward, chevronSize)
    }
}

/**
 * Stacked play arrows: [topForward] on top, opposite direction below (boomerang vs. reverse-boomerang).
 */
@Composable
private fun BoomerangPingPongIcon(
    tint: Color,
    topForward: Boolean,
    chevronSize: Dp,
    overlap: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(overlap, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PlayChevron(tint, forward = topForward, chevronSize)
        PlayChevron(tint, forward = !topForward, chevronSize)
    }
}

@Composable
private fun PlayChevron(
    tint: Color,
    forward: Boolean,
    size: Dp,
) {
    Icon(
        imageVector = Icons.Filled.PlayArrow,
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .size(size)
            .graphicsLayer { if (!forward) scaleX = -1f },
    )
}
