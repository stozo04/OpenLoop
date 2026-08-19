package io.github.stozo04.openloop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.stozo04.openloop.R
import io.github.stozo04.openloop.media.SpeedCurve
import io.github.stozo04.openloop.ui.theme.ElectricLime
import io.github.stozo04.openloop.ui.theme.Outline
import io.github.stozo04.openloop.ui.theme.OutlineVariant
import io.github.stozo04.openloop.ui.theme.OverlayScrim
import io.github.stozo04.openloop.ui.theme.SurfaceContainerHigh
import io.github.stozo04.openloop.ui.theme.TextSecondary
import kotlin.math.abs
import kotlin.math.roundToInt

private val GRAPH_HEIGHT = 120.dp
/** Internal so the Compose test can press exactly where the geometry puts a handle. */
internal val GRAPH_INSET = 12.dp
private val HANDLE_RADIUS = 7.dp
private val GRAPH_CORNER = 12.dp
private val AXIS_LABEL_WIDTH = 34.dp
private val TIME_AXIS_HEIGHT = 16.dp

/** Half a `labelSmall` line, so a marker's baseline centres on its guide line rather than hanging below it. */
private val AXIS_LABEL_HALF_HEIGHT = 7.dp

/** Approximate width of a `mm:ss` tick label, used to centre it on its tick. */
private val TIME_LABEL_WIDTH = 44.dp

/**
 * Speeds labelled down the Y axis. Spans the full usable range so the reader can see what the top and
 * bottom of the plot actually mean, not just the middle.
 */
private val AXIS_LABEL_SPEEDS = listOf(0.5f, 1.0f, 2.0f, 3.0f)

/**
 * Speeds that get a guide line and a haptic tick when a drag crosses them — same anchors as the
 * constant slider's detents. 3× is labelled but gets no line: it *is* the top of the plot, and the
 * clamp means a drag can never cross it.
 */
private val GUIDE_SPEEDS = listOf(0.5f, 1.0f, 2.0f)

/**
 * The Curve-mode editor: a speed graph over the whole loop, plus presets / reset / add-point /
 * flatten.
 *
 * X is the loop timeline (`0f` = first frame of the loop, `1f` = last), **not** the trim window —
 * that is what lets a ramp peak at the turn (PRD-speed-curves.md D-1). Y is speed on a *logarithmic*
 * axis so 0.5× and 2× sit symmetrically about 1× ([SpeedCurveMath]).
 *
 * The line is drawn as a **polyline**, not a spline, because the export interpolates linearly — a
 * smooth-looking preview of a shape the encoder will not reproduce is a preview that lies (D-7).
 *
 * @param playheadFraction deferred read (Lesson 016): invoked inside the `Canvas` draw scope so a
 *   ~20 Hz playback tick redraws the graph without recomposing the editor tree that hosts ExoPlayer.
 * @param seamFractions where the loop turns direction, drawn as faint dashed markers so the user can
 *   see which part of the curve lands on the reverse half.
 */
@Composable
fun SpeedCurvePanel(
    curve: SpeedCurve,
    playheadFraction: () -> Float,
    seamFractions: List<Float>,
    loopDurationMs: Long,
    onCurveChange: (SpeedCurve) -> Unit,
    onUseCurrentAsConstant: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onScrubToFraction: (Float?) -> Unit = {},
) {
    var showPresets by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            // Axis labels live OUTSIDE the plot area rather than over it: inside, they would sit
            // exactly where the curve's left endpoint is drawn and collide with it on any slow start.
            SpeedAxisLabels()
            SpeedCurveGraph(
                curve = curve,
                playheadFraction = playheadFraction,
                seamFractions = seamFractions,
                onCurveChange = onCurveChange,
                onScrubToFraction = onScrubToFraction,
                modifier = Modifier.weight(1f),
            )
        }
        Row {
            // Indent by the Y-label column so the time ticks line up under the plot, not under the axis.
            Spacer(Modifier.width(AXIS_LABEL_WIDTH))
            TimeAxisLabels(loopDurationMs = loopDurationMs, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        CurrentSpeedReadout(
            curve = curve,
            playheadFraction = playheadFraction,
            onUseCurrentAsConstant = onUseCurrentAsConstant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(10.dp))
        SpeedCurveActions(
            curve = curve,
            onShowPresets = { showPresets = true },
            onCurveChange = onCurveChange,
        )
    }

    if (showPresets) {
        SpeedPresetDialog(
            onDismiss = { showPresets = false },
            onPick = { preset ->
                showPresets = false
                onCurveChange(preset.toCurve())
            },
        )
    }
}

/**
 * `2x / 1x / 0.5x` markers down the left of the plot, positioned on the same logarithmic axis the
 * graph draws. Without them "higher is faster" is the only clue the user has about what a handle's
 * height actually means.
 */
@Composable
private fun SpeedAxisLabels(modifier: Modifier = Modifier) {
    val usableHeight = GRAPH_HEIGHT - GRAPH_INSET * 2
    Box(
        modifier = modifier
            .height(GRAPH_HEIGHT)
            .width(AXIS_LABEL_WIDTH),
    ) {
        AXIS_LABEL_SPEEDS.forEach { guide ->
            val fraction = SpeedCurveMath.speedToFraction(guide)
            Text(
                text = formatSpeedMultiplier(guide),
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp)
                    .offset(y = GRAPH_INSET + usableHeight * (1f - fraction) - AXIS_LABEL_HALF_HEIGHT),
            )
        }
    }
}

@Composable
private fun SpeedCurveGraph(
    curve: SpeedCurve,
    playheadFraction: () -> Float,
    seamFractions: List<Float>,
    onCurveChange: (SpeedCurve) -> Unit,
    onScrubToFraction: (Float?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val insetPx = with(density) { GRAPH_INSET.toPx() }
    val handleRadiusPx = with(density) { HANDLE_RADIUS.toPx() }

    var sizePx by remember { mutableStateOf(Offset.Zero) }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    /**
     * Where the finger actually went down, recorded by the tap detector's `onPress`.
     *
     * `detectDragGestures` reports `onDragStart` at the position *after* touch slop (~8 dp), not at
     * the down event. The handle hit test is a 0.09 tolerance in graph space, which on a 120 dp graph
     * is only ~8.6 dp vertically — so slop consumes nearly the whole grab radius and a **vertical**
     * drag, the entire point of this control, almost never finds its handle. Horizontal drags mostly
     * survive (the graph is far wider than it is tall), which is exactly why this passes a casual
     * manual check. Hit-test the down position instead.
     */
    var downPosition by remember { mutableStateOf(Offset.Zero) }
    // rememberUpdatedState so the long-lived pointerInput lambdas always act on the newest values
    // instead of the ones captured when the gesture detector was installed. The detectors are keyed
    // on `sizePx`, which settles at first layout and then never changes — so without this every
    // lambda would be frozen at that first composition.
    //
    // This matters most for [onScrubToFraction]: it closes over the editor's ExoPlayer instance and
    // its clip layout. The player is *recreated* on a `playerEpoch` bump (a memory-pressure Looks
    // teardown can fire one while the Speed tab is open), and the clip spans change once the reversed
    // clip's real duration is measured. A stale copy would seek a released player against the
    // pre-measurement layout.
    val latestCurve by rememberUpdatedState(curve)
    val latestOnCurveChange by rememberUpdatedState(onCurveChange)
    val latestOnScrub by rememberUpdatedState(onScrubToFraction)

    val geometry = SpeedGraphGeometry(sizePx.x, sizePx.y, insetPx)
    val graphLabel = stringResource(R.string.speed_curve_graph_content_description)
    val graphState = pluralStringResource(
        R.plurals.speed_curve_graph_state,
        curve.keys.size,
        curve.keys.size,
        formatSpeedMultiplier(curve.keys.minOfOrNull { it.speed } ?: 1f),
        formatSpeedMultiplier(curve.keys.maxOfOrNull { it.speed } ?: 1f),
    )

    Box(
        modifier = modifier
            .height(GRAPH_HEIGHT)
            .clip(RoundedCornerShape(GRAPH_CORNER))
            .background(OverlayScrim)
            .border(1.dp, OutlineVariant, RoundedCornerShape(GRAPH_CORNER))
            .testTag("speed_curve_graph")
            .onSizeChanged { sizePx = Offset(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(sizePx) {
                if (sizePx.x <= 0f) return@pointerInput
                detectTapGestures(
                    // The drag detector only learns a position *after* touch slop, which is too late
                    // to identify which handle the user grabbed — see [downPosition].
                    onPress = { downPosition = it },
                    onTap = { offset ->
                        val working = latestCurve
                        // A tap ON a handle is not an add — it would stack a second point under the
                        // user's finger. Only empty graph adds.
                        val hit = SpeedCurveMath.nearestKeyIndex(
                            working,
                            geometry.tAt(offset.x),
                            geometry.speedAt(offset.y),
                        )
                        if (hit != null) return@detectTapGestures
                        val updated = SpeedCurveMath.insertKeyAt(working, geometry.tAt(offset.x))
                        if (updated !== working) {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            latestOnCurveChange(updated)
                        } else {
                            // At the point cap, or too close to an existing handle.
                            haptics.performHapticFeedback(HapticFeedbackType.Reject)
                        }
                    },
                    onLongPress = { offset ->
                        val working = latestCurve
                        val index = SpeedCurveMath.nearestKeyIndex(
                            working,
                            geometry.tAt(offset.x),
                            geometry.speedAt(offset.y),
                        ) ?: return@detectTapGestures
                        val updated = SpeedCurveMath.removeKey(working, index)
                        if (updated !== working) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            latestOnCurveChange(updated)
                        } else {
                            // Endpoints are not removable — the curve must span the whole loop.
                            haptics.performHapticFeedback(HapticFeedbackType.Reject)
                        }
                    },
                )
            }
            .pointerInput(sizePx) {
                if (sizePx.x <= 0f) return@pointerInput
                detectDragGestures(
                    onDragStart = {
                        // `downPosition`, not this callback's offset: that one is already a slop's
                        // distance away from the handle the user meant to grab.
                        val index = SpeedCurveMath.nearestKeyIndex(
                            latestCurve,
                            geometry.tAt(downPosition.x),
                            geometry.speedAt(downPosition.y),
                        ) ?: -1
                        draggingIndex = index
                        // Park the preview on the moment being edited, so the frame on screen is the
                        // one this handle governs rather than wherever the loop happened to be.
                        latestCurve.keys.getOrNull(index)?.let { latestOnScrub(it.t) }
                    },
                    // Hand playback back to the loop on release.
                    onDragEnd = {
                        draggingIndex = -1
                        latestOnScrub(null)
                    },
                    onDragCancel = {
                        draggingIndex = -1
                        latestOnScrub(null)
                    },
                ) { change, _ ->
                    val index = draggingIndex
                    if (index < 0) return@detectDragGestures
                    change.consume()
                    val working = latestCurve
                    val previousSpeed = working.keys.getOrNull(index)?.speed ?: return@detectDragGestures
                    val targetSpeed = geometry.speedAt(change.position.y)
                    val updated = SpeedCurveMath.moveKey(
                        curve = working,
                        index = index,
                        targetT = geometry.tAt(change.position.x),
                        targetSpeed = targetSpeed,
                    )
                    val newSpeed = updated.keys.getOrNull(index)?.speed ?: previousSpeed
                    // Tick when the handle crosses a guide line — identical rule to the constant
                    // slider's detents, so both speed controls feel the same under the thumb.
                    if (GUIDE_SPEEDS.any { guide -> (previousSpeed < guide) != (newSpeed < guide) }) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                    latestOnCurveChange(updated)
                    // Follow the handle in time so the preview keeps showing the frame under it.
                    updated.keys.getOrNull(index)?.let { latestOnScrub(it.t) }
                }
            }
            // NOT mergeDescendants: merging collapses the per-handle nodes below into this one, which
            // is the exact opposite of what they are for. TalkBack would get a single node carrying
            // every point's custom actions at once, with no way to tell which point any of them moves.
            // Unmerged, the graph announces itself and each handle stays individually traversable.
            .semantics {
                contentDescription = graphLabel
                stateDescription = graphState
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val geo = SpeedGraphGeometry(size.width, size.height, insetPx)

            // Guide lines at 0.5x / 1x / 2x, brightest at 1x (the neutral reference).
            GUIDE_SPEEDS.forEach { guide ->
                val y = geo.yOf(guide)
                drawLine(
                    color = Color.White.copy(alpha = if (guide == 1.0f) 0.24f else 0.12f),
                    start = Offset(insetPx, y),
                    end = Offset(size.width - insetPx, y),
                    strokeWidth = 1f,
                )
            }

            // Direction-turn markers. The curve spans the WHOLE loop, so without these there is no
            // way to see which stretch of the line lands on the reversed half.
            seamFractions.forEach { fraction ->
                val x = geo.xOf(fraction)
                drawLine(
                    color = Outline,
                    start = Offset(x, insetPx),
                    end = Offset(x, size.height - insetPx),
                    strokeWidth = 1f,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                        floatArrayOf(4f, 6f),
                    ),
                )
            }

            // The curve itself: the linear-in-speed interpolation the export applies (D-7), sampled
            // between keys because Y is logarithmic — a straight key-to-key segment on this axis
            // would draw a geometric ramp the encoder never plays (SpeedCurveMath.drawPoints).
            // Rounded joins keep it from looking like a chart.
            val keys = curve.keys.sortedBy { it.t }
            if (keys.size >= 2) {
                val points = SpeedCurveMath.drawPoints(curve)
                val path = Path().apply {
                    moveTo(geo.xOf(points[0].first), geo.yOf(points[0].second))
                    points.drop(1).forEach { (t, speed) -> lineTo(geo.xOf(t), geo.yOf(speed)) }
                }
                drawPath(
                    path = path,
                    color = ElectricLime,
                    style = Stroke(width = 3f * density.density, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }

            // Playhead. Read in the DRAW scope (Lesson 016) — a tick redraws, never recomposes.
            val head = playheadFraction().coerceIn(0f, 1f)
            val headX = geo.xOf(head)
            drawLine(
                color = Color.White.copy(alpha = 0.85f),
                start = Offset(headX, insetPx * 0.5f),
                end = Offset(headX, size.height - insetPx * 0.5f),
                strokeWidth = 1.5f * density.density,
                cap = StrokeCap.Round,
            )

            keys.forEach { key ->
                val center = Offset(geo.xOf(key.t), geo.yOf(key.speed))
                drawCircle(ElectricLime.copy(alpha = 0.3f), radius = handleRadiusPx * 1.7f, center = center)
                drawCircle(ElectricLime, radius = handleRadiusPx, center = center)
                drawCircle(Color.Black.copy(alpha = 0.55f), radius = handleRadiusPx * 0.36f, center = center)
            }
        }

        // Screen-reader nodes for the handles. They are invisible (zero-size, drawn by the Canvas)
        // but focusable, which is what gives TalkBack a way to reach a point at all — a Canvas has
        // no children to traverse. Every action routes through the same SpeedCurveMath clamps the
        // drag path uses, so the two can never disagree (PRD §4.7).
        SpeedCurveAccessibilityNodes(curve = curve, onCurveChange = onCurveChange)
    }
}

@Composable
private fun SpeedCurveAccessibilityNodes(
    curve: SpeedCurve,
    onCurveChange: (SpeedCurve) -> Unit,
) {
    val faster = stringResource(R.string.speed_curve_action_faster)
    val slower = stringResource(R.string.speed_curve_action_slower)
    val earlier = stringResource(R.string.speed_curve_action_earlier)
    val later = stringResource(R.string.speed_curve_action_later)
    val delete = stringResource(R.string.speed_curve_action_delete)

    // Each node takes a real, non-zero slice of the graph. A zero-size Box is invisible to TalkBack
    // *and* to uiautomator — it reports no bounds, so the point is simply unreachable. Equal weights
    // rather than exact handle positions: traversal order is what matters, and the spoken state
    // carries the true position anyway.
    Row(modifier = Modifier.fillMaxSize()) {
        curve.keys.forEachIndexed { index, key ->
            val movable = SpeedCurveMath.isTimeMovable(curve, index)
            val state = if (movable) {
                stringResource(
                    R.string.speed_curve_point_state,
                    index + 1,
                    curve.keys.size,
                    (key.t * 100f).roundToInt(),
                    formatSpeedMultiplier(key.speed),
                )
            } else {
                // Endpoints are x-locked; announcing a position they cannot leave would send a
                // TalkBack user hunting for a "move" action that does nothing.
                stringResource(R.string.speed_curve_point_locked_state, formatSpeedMultiplier(key.speed))
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag("speed_curve_point_$index")
                    .semantics {
                        contentDescription = state
                        stateDescription = state
                        customActions = buildList {
                            add(
                                CustomAccessibilityAction(faster) {
                                    onCurveChange(
                                        SpeedCurveMath.moveKey(
                                            curve, index, key.t, key.speed + SpeedCurveMath.A11Y_SPEED_STEP,
                                        ),
                                    )
                                    true
                                },
                            )
                            add(
                                CustomAccessibilityAction(slower) {
                                    onCurveChange(
                                        SpeedCurveMath.moveKey(
                                            curve, index, key.t, key.speed - SpeedCurveMath.A11Y_SPEED_STEP,
                                        ),
                                    )
                                    true
                                },
                            )
                            if (movable) {
                                add(
                                    CustomAccessibilityAction(earlier) {
                                        onCurveChange(
                                            SpeedCurveMath.moveKey(
                                                curve, index, key.t - SpeedCurveMath.A11Y_TIME_STEP, key.speed,
                                            ),
                                        )
                                        true
                                    },
                                )
                                add(
                                    CustomAccessibilityAction(later) {
                                        onCurveChange(
                                            SpeedCurveMath.moveKey(
                                                curve, index, key.t + SpeedCurveMath.A11Y_TIME_STEP, key.speed,
                                            ),
                                        )
                                        true
                                    },
                                )
                                add(
                                    CustomAccessibilityAction(delete) {
                                        onCurveChange(SpeedCurveMath.removeKey(curve, index))
                                        true
                                    },
                                )
                            }
                        }
                    },
            )
        }
    }
}

/**
 * The live "Current: 1.7×" readout. Tapping it locks the whole loop to that value in Constant mode —
 * the "I want *this* speed" intent, as distinct from Flatten's average.
 *
 * Reads [playheadFraction] in composition (a `Text` needs a `String`), so this composable recomposes
 * per tick — which is exactly the point of isolating it: the graph and the rest of the panel do not.
 */
@Composable
private fun CurrentSpeedReadout(
    curve: SpeedCurve,
    playheadFraction: () -> Float,
    onUseCurrentAsConstant: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val current = curve.speedAt(playheadFraction())
    val label = formatSpeedMultiplier(current)
    val shape = RoundedCornerShape(percent = 50)
    val actionLabel = stringResource(R.string.speed_curve_use_current, label)

    Row(
        modifier = modifier
            .clip(shape)
            .background(OverlayScrim)
            .border(1.dp, OutlineVariant, shape)
            // `clickable`, not `pointerInput(current)`: `current` moves on every playhead tick of a
            // non-flat curve, and re-keying a pointerInput mid-press re-installs the detector and
            // cancels the tap (Lesson 034) — so locking the loop to the live speed mostly failed
            // while the preview was playing. `clickable` reads the newest lambda on every tap and
            // gives TalkBack a real click action.
            .clickable(role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onUseCurrentAsConstant(current)
            }
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = actionLabel }
            .testTag("speed_curve_current"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.speed_curve_current, label),
            color = ElectricLime,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Presets · Add point · Reset, each taking an equal third of the width.
 *
 * There is no "Flatten to Constant" button: tapping the **Constant** segment of the mode toggle does
 * exactly that (and flattens to the curve's duration-preserving average), so a second control for it
 * was one more thing to explain for no new capability.
 */
@Composable
private fun SpeedCurveActions(
    curve: SpeedCurve,
    onShowPresets: () -> Unit,
    onCurveChange: (SpeedCurve) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    // At the 3-point cap the middle button is a two-state control rather than a disabled "Add point":
    // once the single interior point exists, adding is impossible and removing is the only thing left
    // to offer. Long-press-to-delete still works, but a gesture nobody discovers is not a way to delete
    // a point — this is.
    val hasInteriorPoint = curve.keys.size > SpeedCurve.MIN_KEYS

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CurveActionButton(
            label = stringResource(R.string.speed_curve_presets),
            modifier = Modifier
                .weight(1f)
                .testTag("speed_curve_presets"),
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                onShowPresets()
            },
        )
        CurveActionButton(
            label = stringResource(
                if (hasInteriorPoint) R.string.speed_curve_remove_point else R.string.speed_curve_add_point,
            ),
            modifier = Modifier
                .weight(1f)
                .testTag(if (hasInteriorPoint) "speed_curve_remove_point" else "speed_curve_add_point"),
            onClick = {
                val updated = if (hasInteriorPoint) {
                    // The interior points are everything between the two locked ends; at the cap that
                    // is exactly one, so there is nothing to disambiguate.
                    SpeedCurveMath.removeKey(curve, curve.keys.size - 2)
                } else {
                    SpeedCurveMath.insertKeyInWidestGap(curve)
                }
                if (updated !== curve) {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onCurveChange(updated)
                } else {
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                }
            },
        )
        CurveActionButton(
            label = stringResource(R.string.speed_curve_reset),
            modifier = Modifier
                .weight(1f)
                .testTag("speed_curve_reset"),
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                // Reset means "flat at what this curve currently averages", so the loop's length
                // does not jump when the shape goes away.
                onCurveChange(SpeedCurve.flat(curve.flatten()))
            },
        )
    }
}

/**
 * Time ticks under the plot, reusing the Trim screen's ruler math so both timelines label themselves
 * the same way ([trimRulerLabelTimesMs] / [formatTrimRulerLabel]).
 *
 * The times are the loop's length at **1×** — the timeline the curve is actually defined over. It is
 * deliberately not the sped-up output length shown in the duration chip: once speed varies, output
 * time is a non-linear function of input time, so ticks drawn at even output intervals would sit at
 * uneven, and therefore wrong, places on this graph.
 */
@Composable
private fun TimeAxisLabels(
    loopDurationMs: Long,
    modifier: Modifier = Modifier,
) {
    if (loopDurationMs <= 0L) {
        Spacer(modifier.height(TIME_AXIS_HEIGHT))
        return
    }
    val times = remember(loopDurationMs) { trimRulerLabelTimesMs(loopDurationMs) }
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.height(TIME_AXIS_HEIGHT)) {
        val insetPx = with(density) { GRAPH_INSET.toPx() }
        val labelWidthPx = with(density) { TIME_LABEL_WIDTH.toPx() }
        val usableWidth = (constraints.maxWidth - insetPx * 2f).coerceAtLeast(1f)

        times.forEachIndexed { index, timeMs ->
            val fraction = (timeMs.toFloat() / loopDurationMs.toFloat()).coerceIn(0f, 1f)
            Text(
                // The last tick carries a decimal, matching the Trim ruler: an 8.6 s loop should not
                // round its own end to "00:09".
                text = if (index == times.lastIndex) {
                    formatTrimRulerEndLabel(timeMs)
                } else {
                    formatTrimRulerLabel(timeMs)
                },
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        val x = insetPx + fraction * usableWidth - labelWidthPx / 2f
                        IntOffset(
                            x.coerceIn(0f, (constraints.maxWidth - labelWidthPx).coerceAtLeast(0f))
                                .roundToInt(),
                            0,
                        )
                    },
            )
        }
    }
}

/**
 * Secondary (outlined) button. Deliberately not lime-filled: the SAVE checkmark stays the only
 * primary action on the editor screen.
 */
@Composable
private fun CurveActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(10.dp)
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(SurfaceContainerHigh.copy(alpha = alpha))
            .border(1.dp, OutlineVariant.copy(alpha = alpha), shape)
            // `clickable`, not `pointerInput { detectTapGestures { … } }`: a pointerInput keyed on
            // `enabled` freezes its lambda for as long as `enabled` holds, so every tap would re-run
            // the FIRST composition's `onClick` against a stale curve (+ Point stuck at 3 keys).
            // It also gives TalkBack a real click action and Role.Button, which the raw gesture had not.
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = alpha),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            // Without this, `maxLines = 1` wraps a two-word label at the space and silently drops the
            // second word — the screen read "Remove" while TalkBack still announced "Remove point".
            // A translation that outgrows a third of the row now shows an ellipsis instead of lying.
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SpeedPresetDialog(
    onDismiss: () -> Unit,
    onPick: (SpeedPreset) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.speed_curve_presets)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SpeedPreset.entries.forEach { preset ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            // A plain tap is a `clickable` — the raw gesture had no click action or
                            // Role.Button, so TalkBack could not activate a preset (Lesson 034).
                            .clickable(role = Role.Button) {
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                onPick(preset)
                            }
                            .padding(vertical = 12.dp)
                            .testTag("speed_preset_${preset.name}"),
                    ) {
                        Text(
                            text = stringResource(preset.labelResId),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = pluralStringResource(
                        R.plurals.speed_curve_max_points_hint,
                        SpeedCurve.MAX_KEYS,
                        SpeedCurve.MAX_KEYS,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_got_it)) }
        },
    )
}

/**
 * One-time explainer, shown on the first tap of "Curve" and reachable forever after via the panel's
 * "?" button. A single dialog gated on a DataStore flag — the app has no coach-mark framework and
 * this does not justify building one (D-9).
 */
@Composable
fun SpeedCurveIntroDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.speed_curve_intro_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.speed_curve_intro_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                listOf(
                    R.string.speed_curve_intro_tap,
                    R.string.speed_curve_intro_drag,
                    R.string.speed_curve_intro_hold,
                ).forEach { line ->
                    Text(
                        text = "·  ${stringResource(line)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
                Text(
                    text = stringResource(R.string.speed_curve_intro_axes),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.speed_curve_intro_escape),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_got_it)) }
        },
        modifier = Modifier.testTag("speed_curve_intro"),
    )
}

/**
 * Where the loop turns direction, as fractions of the loop — the dashed markers on the graph.
 *
 * Derived from the same post-seam-drop clip layout the render uses, so a marker sits exactly where
 * the encoder puts the turn rather than at a nominal half-way point (which would be wrong for every
 * mode except a single forward-then-reverse cycle).
 */
fun seamFractionsFor(clipDurationsUs: List<Long>): List<Float> {
    val total = clipDurationsUs.sum()
    if (total <= 0L || clipDurationsUs.size < 2) return emptyList()
    var cursor = 0L
    return clipDurationsUs.dropLast(1).map { duration ->
        cursor += duration
        cursor.toFloat() / total.toFloat()
    }
}

/** True when [a] and [b] differ enough to be worth re-applying to the player. */
internal fun speedDiffersMeaningfully(a: Float, b: Float, threshold: Float): Boolean =
    abs(a - b) >= threshold
