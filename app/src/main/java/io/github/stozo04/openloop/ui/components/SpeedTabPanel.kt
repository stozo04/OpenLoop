package io.github.stozo04.openloop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.stozo04.openloop.R
import io.github.stozo04.openloop.media.SpeedCurve
import io.github.stozo04.openloop.ui.OpenLoopViewModel
import io.github.stozo04.openloop.ui.theme.ElectricLime
import io.github.stozo04.openloop.ui.theme.LimeInk
import io.github.stozo04.openloop.ui.theme.Outline
import io.github.stozo04.openloop.ui.theme.OverlayScrim
import io.github.stozo04.openloop.ui.theme.OutlineVariant
import io.github.stozo04.openloop.ui.theme.SurfaceContainer
import io.github.stozo04.openloop.ui.theme.SurfaceContainerHigh
import io.github.stozo04.openloop.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.roundToInt

private val SPEED_PANEL_CORNER = 16.dp
private val SPEED_PANEL_MAX_WIDTH = 520.dp
private val SLIDER_THUMB_RADIUS = 12.dp
private val SLIDER_TRACK_HEIGHT = 4.dp
private val SLIDER_TOUCH_HEIGHT = 48.dp
private val SCALE_LABEL_MARKERS = listOf(0.5f, 1.0f, 2.0f)

/** Speeds that get a haptic tick when crossed (mock anchors at 0.5×, 1×, 2×). */
private val SPEED_DETENTS = listOf(0.5f, 1.0f, 2.0f)

/**
 * Speed tab panel: dark rounded card, caption, a `Constant | Curve` mode toggle, and then either the
 * lime slider (Constant — unchanged from slice 04) or the curve editor.
 *
 * [curve] is the mode discriminator as well as the data: `null` = Constant. Constant stays the
 * default so the 80% who want "1.5× and done" see exactly what shipped before, and Curve is one tap
 * away (PRD-speed-curves.md §4.1).
 *
 * @param hasSeenCurveIntro when false, the first switch to Curve opens the one-time explainer.
 */
@Composable
fun SpeedTabPanel(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    curve: SpeedCurve? = null,
    playheadFraction: () -> Float = { 0f },
    seamFractions: List<Float> = emptyList(),
    loopDurationMs: Long = 0L,
    hasSeenCurveIntro: Boolean = true,
    onEnterCurveMode: () -> Unit = {},
    onCurveChange: (SpeedCurve) -> Unit = {},
    onFlattenCurve: () -> Unit = {},
    onUseCurrentAsConstant: (Float) -> Unit = {},
    onScrubToFraction: (Float?) -> Unit = {},
    onCurveIntroSeen: () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    var showIntro by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("speed_tab_panel"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = SPEED_PANEL_MAX_WIDTH)
                .clip(RoundedCornerShape(SPEED_PANEL_CORNER))
                .background(SurfaceContainer)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.speed_title),
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                if (curve != null) {
                    IconButton(
                        onClick = { showIntro = true },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("speed_curve_help"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.speed_curve_help_content_description),
                            tint = TextSecondary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            SpeedModeToggle(
                curveActive = curve != null,
                onSelectConstant = {
                    if (curve != null) {
                        haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                        onFlattenCurve()
                    }
                },
                onSelectCurve = {
                    if (curve == null) {
                        haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                        onEnterCurveMode()
                        if (!hasSeenCurveIntro) showIntro = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))

            if (curve == null) {
                SpeedSlider(
                    speed = speed,
                    onSpeedChange = onSpeedChange,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                SpeedScaleLabels(
                    minSpeed = OpenLoopViewModel.MIN_SPEED,
                    maxSpeed = OpenLoopViewModel.MAX_SPEED,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                SpeedCurrentPill(
                    speed = speed,
                    modifier = Modifier.testTag("speed_current_pill"),
                )
                Spacer(Modifier.height(4.dp))
            } else {
                SpeedCurvePanel(
                    curve = curve,
                    playheadFraction = playheadFraction,
                    seamFractions = seamFractions,
                    loopDurationMs = loopDurationMs,
                    onCurveChange = onCurveChange,
                    onUseCurrentAsConstant = onUseCurrentAsConstant,
                    onScrubToFraction = onScrubToFraction,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showIntro) {
        SpeedCurveIntroDialog(
            onDismiss = {
                showIntro = false
                onCurveIntroSeen()
            },
        )
    }
}

/** `[ Constant ][ Curve ]` segmented control. Selected segment is lime-filled, matching the mock. */
@Composable
private fun SpeedModeToggle(
    curveActive: Boolean,
    onSelectConstant: () -> Unit,
    onSelectCurve: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(SurfaceContainerHigh)
            .border(1.dp, OutlineVariant, shape)
            .testTag("speed_mode_toggle"),
    ) {
        SpeedModeSegment(
            label = stringResource(R.string.speed_mode_constant),
            selected = !curveActive,
            onClick = onSelectConstant,
            modifier = Modifier
                .weight(1f)
                .testTag("speed_mode_constant"),
        )
        SpeedModeSegment(
            label = stringResource(R.string.speed_mode_curve),
            selected = curveActive,
            onClick = onSelectCurve,
            modifier = Modifier
                .weight(1f)
                .testTag("speed_mode_curve"),
        )
    }
}

@Composable
private fun SpeedModeSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(2.dp)
            .clip(shape)
            .background(if (selected) ElectricLime else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) LimeInk else Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

@Composable
private fun SpeedCurrentPill(
    speed: Float,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = modifier
            .clip(shape)
            .background(OverlayScrim)
            .border(1.dp, OutlineVariant, shape)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Gap via padding, not a trailing space in the string — a trailing space makes the node's
        // text "Current speed " which an exact-match onNodeWithText("Current speed") can't find
        // (BoomerangEditorScreenTest), and reads as a stray pause in TalkBack.
        Text(
            text = stringResource(R.string.speed_current_label),
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 4.dp),
        )
        Text(
            text = formatSpeedMultiplier(speed),
            color = ElectricLime,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("speed_current_label"),
        )
    }
}

/** Anchor labels at 0.5×, 1×, and 2× along the same scale as the slider. */
@Composable
private fun SpeedScaleLabels(
    minSpeed: Float,
    maxSpeed: Float,
    modifier: Modifier = Modifier,
) {
    val span = maxSpeed - minSpeed
    fun fractionFor(speed: Float) = ((speed - minSpeed) / span).coerceIn(0f, 1f)

    BoxWithConstraints(modifier = modifier.height(18.dp)) {
        val density = LocalDensity.current
        SCALE_LABEL_MARKERS.forEach { marker ->
            val fraction = fractionFor(marker)
            val label = formatSpeedMultiplier(marker)
            Text(
                text = label,
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        val textWidthPx = with(density) { 28.dp.toPx() } // approximate centering
                        val x = (constraints.maxWidth * fraction - textWidthPx / 2f)
                            .coerceIn(0f, (constraints.maxWidth - textWidthPx).coerceAtLeast(0f))
                        IntOffset(x.roundToInt(), 0)
                    },
            )
        }
    }
}

@Composable
private fun SpeedSlider(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val start = OpenLoopViewModel.MIN_SPEED
    val end = OpenLoopViewModel.MAX_SPEED
    val span = end - start
    val thumbRadiusPx = with(density) { SLIDER_THUMB_RADIUS.toPx() }
    val trackStrokePx = with(density) { SLIDER_TRACK_HEIGHT.toPx() }
    val inactiveTrackColor = Outline

    var widthPx by remember { mutableFloatStateOf(0f) }
    val latestSpeed by rememberUpdatedState(speed)
    // Hoisted: stringResource needs a composable scope, and the semantics {} block below is not one.
    val sliderLabel = stringResource(R.string.speed_slider_content_description)
    val sliderState = stringResource(R.string.speed_state_description, formatSpeedNumber(speed))

    val emit: (Float) -> Unit = { raw ->
        val clamped = raw.coerceIn(start, end)
        val prev = latestSpeed
        if (clamped != prev) {
            if (SPEED_DETENTS.any { t -> (prev < t) != (clamped < t) }) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
            onSpeedChange(clamped)
        }
    }

    fun fractionOf(value: Float) = ((value - start) / span).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SLIDER_TOUCH_HEIGHT)
            .testTag("speed_slider")
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(widthPx) {
                if (widthPx <= 0f) return@pointerInput
                val usable = (widthPx - thumbRadiusPx * 2f).coerceAtLeast(1f)
                fun xToValue(x: Float) = start + ((x - thumbRadiusPx) / usable).coerceIn(0f, 1f) * span
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    emit(xToValue(change.position.x))
                }
            }
            .pointerInput(widthPx) {
                if (widthPx <= 0f) return@pointerInput
                val usable = (widthPx - thumbRadiusPx * 2f).coerceAtLeast(1f)
                fun xToValue(x: Float) = start + ((x - thumbRadiusPx) / usable).coerceIn(0f, 1f) * span
                detectTapGestures { offset -> emit(xToValue(offset.x)) }
            }
            .semantics {
                contentDescription = sliderLabel
                stateDescription = sliderState
                progressBarRangeInfo = ProgressBarRangeInfo(speed, start..end)
                setProgress { target ->
                    emit(target)
                    true
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerY = size.height / 2f
            val usable = (size.width - thumbRadiusPx * 2f).coerceAtLeast(1f)
            val thumbX = thumbRadiusPx + fractionOf(speed) * usable

            // 1× reference tick (mock): thin vertical mark on the track.
            val oneX = thumbRadiusPx + fractionOf(1.0f) * usable
            drawLine(
                color = Color.White.copy(alpha = 0.55f),
                start = Offset(oneX, centerY - 10f),
                end = Offset(oneX, centerY + 10f),
                strokeWidth = 1.5f,
                cap = StrokeCap.Round,
            )

            if (thumbX < size.width - thumbRadiusPx) {
                drawLine(
                    color = inactiveTrackColor,
                    start = Offset(thumbX, centerY),
                    end = Offset(size.width - thumbRadiusPx, centerY),
                    strokeWidth = trackStrokePx,
                    cap = StrokeCap.Round,
                )
            }
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(ElectricLime.copy(alpha = 0.25f), ElectricLime),
                    startX = thumbRadiusPx,
                    endX = thumbX,
                ),
                start = Offset(thumbRadiusPx, centerY),
                end = Offset(thumbX, centerY),
                strokeWidth = trackStrokePx,
                cap = StrokeCap.Round,
            )
            drawCircle(
                ElectricLime.copy(alpha = 0.35f),
                radius = thumbRadiusPx * 1.55f,
                center = Offset(thumbX, centerY),
            )
            drawCircle(ElectricLime, radius = thumbRadiusPx, center = Offset(thumbX, centerY))
            drawCircle(Color.White, radius = thumbRadiusPx * 0.38f, center = Offset(thumbX, centerY))
        }
    }
}

/** Visible multiplier label, e.g. 1.5f → "1.5x". */
fun formatSpeedMultiplier(speed: Float): String {
    val number = String.format(Locale.US, "%.1f", speed).trimEnd('0').trimEnd('.')
    return "${number}x"
}

/**
 * The bare multiplier for the slider's spoken state ("1.5"). The surrounding phrasing lives in
 * `R.string.speed_state_description` so it can be translated; only the number is formatted here.
 */
private fun formatSpeedNumber(speed: Float): String =
    String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
