package io.github.stozo04.openloop.ui.components

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stozo04.openloop.media.extractTrimFilmstripFrames
import io.github.stozo04.openloop.ui.OpenLoopViewModel
import io.github.stozo04.openloop.ui.theme.ElectricLime
import io.github.stozo04.openloop.ui.theme.LimeInk
import io.github.stozo04.openloop.ui.theme.OverlayScrim
import io.github.stozo04.openloop.ui.theme.SurfaceContainerHigh
import io.github.stozo04.openloop.ui.theme.TextSecondary
import io.github.stozo04.openloop.ui.theme.TimerTextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val FILMSTRIP_HEIGHT = 56.dp
private val HANDLE_VISUAL_WIDTH = 22.dp
private val HANDLE_TOUCH_WIDTH = 48.dp
private val RULER_HEIGHT = 30.dp
private val SELECTION_RADIUS = 10.dp
private val FILMSTRIP_FRAME_MIN = 6
private val FILMSTRIP_FRAME_MAX = 14
private val RULER_LABEL_INTERVAL_MS = 5_000L
private val DIM_OUTSIDE_SELECTION = Color.Black.copy(alpha = 0.62f)

private enum class TrimDragTarget { NONE, START, END }

/**
 * Trim panel matching the reference mock: caption, time ruler, filmstrip with dimmed-outside
 * selection, lime handles, and a centered range pill (`00:02.3 — 00:10.8`).
 */
@Composable
fun TrimFilmstripControls(
    sourceFile: File,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    onStartDrag: (Long) -> Unit,
    onEndDrag: (Long) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("trim_filmstrip_controls"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "TRIM YOUR VIDEO",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("trim_section_title"),
        )

        TimelineRuler(
            durationMs = durationMs,
            modifier = Modifier
                .fillMaxWidth()
                .height(RULER_HEIGHT)
                .testTag("trim_timeline_ruler"),
        )

        FilmstripTrimSelector(
            sourceFile = sourceFile,
            durationMs = durationMs,
            startMs = startMs,
            endMs = endMs,
            onStartDrag = onStartDrag,
            onEndDrag = onEndDrag,
            onDragEnd = onDragEnd,
            modifier = Modifier.fillMaxWidth(),
        )

        // NOTE: no testTag here — TrimRangePill tags itself "trim_range_label" (TrimScreenTest), and
        // a second testTag earlier in the same modifier chain SHADOWS the inner one (first semantics
        // value for a key wins), making the inner tag unfindable.
        TrimRangePill(
            startMs = startMs,
            endMs = endMs,
        )
    }
}

/** `00:02.3` style clock for trim readouts (minutes + seconds with one decimal). */
fun formatTrimClock(ms: Long): String {
    val totalSeconds = ms / 1000.0
    val minutes = (totalSeconds / 60.0).toInt()
    val seconds = totalSeconds - minutes * 60.0
    return String.format(Locale.US, "%02d:%04.1f", minutes, seconds)
}

@Composable
private fun TimelineRuler(
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val labelColor = TextSecondary
    val minorTickColor = TextSecondary.copy(alpha = 0.5f)
    val majorTickColor = TextSecondary.copy(alpha = 0.85f)
    val labelTextSizePx = with(density) { 11.sp.toPx() }

    val lastLabelMs = ((ceil(durationMs / RULER_LABEL_INTERVAL_MS.toDouble()) * RULER_LABEL_INTERVAL_MS)
        .toLong())
        .coerceAtLeast(RULER_LABEL_INTERVAL_MS)

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val labelY = labelTextSizePx + 2f
        val tickTop = height * 0.55f
        val tickBottom = height * 0.95f

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor.toArgb()
            textSize = labelTextSizePx
            textAlign = Paint.Align.CENTER
        }

        val labelCount = (lastLabelMs / RULER_LABEL_INTERVAL_MS).toInt() + 1
        for (i in 0 until labelCount) {
            val timeMs = i * RULER_LABEL_INTERVAL_MS
            val fraction = (timeMs.toFloat() / lastLabelMs).coerceIn(0f, 1f)
            val x = fraction * width

            drawLine(
                color = majorTickColor,
                start = Offset(x, tickTop),
                end = Offset(x, tickBottom),
                strokeWidth = 2f,
            )
            val label = formatTrimClock(timeMs).substringBefore('.')
            drawContext.canvas.nativeCanvas.drawText(label, x, labelY, textPaint)
        }

        val minorStepMs = 500L
        val minorCount = (lastLabelMs / minorStepMs).toInt()
        for (i in 0..minorCount) {
            val timeMs = i * minorStepMs
            if (timeMs % RULER_LABEL_INTERVAL_MS == 0L) continue
            val fraction = (timeMs.toFloat() / lastLabelMs).coerceIn(0f, 1f)
            val x = fraction * width
            drawLine(
                color = minorTickColor,
                start = Offset(x, tickTop + (tickBottom - tickTop) * 0.4f),
                end = Offset(x, tickBottom),
                strokeWidth = 1f,
            )
        }

        drawLine(
            color = minorTickColor,
            start = Offset(0f, tickTop + 2f),
            end = Offset(width, tickTop + 2f),
            strokeWidth = 1f,
        )
    }
}

@Composable
private fun TrimRangePill(
    startMs: Long,
    endMs: Long,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = 50)
    Text(
        text = "${formatTrimClock(startMs)}  —  ${formatTrimClock(endMs)}",
        color = Color.White,
        style = TimerTextStyle,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(shape)
            .background(OverlayScrim)
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .testTag("trim_range_label"),
    )
}

@Composable
private fun FilmstripTrimSelector(
    sourceFile: File,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    onStartDrag: (Long) -> Unit,
    onEndDrag: (Long) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val handleVisualPx = with(density) { HANDLE_VISUAL_WIDTH.toPx() }
    val handleTouchPx = with(density) { HANDLE_TOUCH_WIDTH.toPx() }
    val minGapMs = OpenLoopViewModel.MIN_TRIM_MS

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(FILMSTRIP_HEIGHT + 8.dp)
            .testTag("trim_bar"),
    ) {
        val trackPx = constraints.maxWidth.toFloat()
        val frameCount = (trackPx / with(density) { 48.dp.toPx() })
            .roundToInt()
            .coerceIn(FILMSTRIP_FRAME_MIN, FILMSTRIP_FRAME_MAX)

        val frames by produceState<List<Bitmap?>>(emptyList(), sourceFile, durationMs, frameCount) {
            value = withContext(Dispatchers.IO) {
                extractTrimFilmstripFrames(sourceFile, durationMs, frameCount)
            }
        }

        val safeDuration = durationMs.coerceAtLeast(1L)

        fun positionPx(ms: Long): Float = (ms.toFloat() / safeDuration) * trackPx
        fun pxToMs(px: Float): Long = (px / trackPx * safeDuration).roundToLong()

        val curStartMs by rememberUpdatedState(startMs)
        val curEndMs by rememberUpdatedState(endMs)
        val startDrag by rememberUpdatedState(onStartDrag)
        val endDrag by rememberUpdatedState(onEndDrag)
        val dragEnd by rememberUpdatedState(onDragEnd)

        var dragging by remember { mutableStateOf(TrimDragTarget.NONE) }
        // Finger-down x and the grabbed handle's value at grab time. We move the handle by the drag
        // DELTA from this anchor — not by teleporting it to the absolute finger x — so a touch tracks
        // the finger 1:1 instead of yanking the handle to wherever the screen was first touched.
        var dragAnchorPx by remember { mutableFloatStateOf(0f) }
        var dragAnchorMs by remember { mutableLongStateOf(0L) }

        val selectionStart = positionPx(startMs)
        val selectionEnd = positionPx(endMs).coerceAtLeast(selectionStart + 1f)
        val stripTopPx = with(density) { 4.dp.toPx() }.roundToInt()
        val stripHeightPx = with(density) { FILMSTRIP_HEIGHT.toPx() }.roundToInt()

        // ── Thumbnail row ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(FILMSTRIP_HEIGHT)
                .offset(y = 4.dp)
                .clip(RoundedCornerShape(SELECTION_RADIUS)),
        ) {
            frames.forEach { frame ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(SurfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    val bitmap = frame?.asImageBitmap()
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        // ── Dim regions outside the selection ──
        Box(
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(0, stripTopPx) }
                .width(with(density) { selectionStart.toDp() })
                .height(FILMSTRIP_HEIGHT)
                .background(DIM_OUTSIDE_SELECTION),
        )
        Box(
            modifier = Modifier
                .offset {
                    androidx.compose.ui.unit.IntOffset(selectionEnd.roundToInt(), stripTopPx)
                }
                .width(with(density) { (trackPx - selectionEnd).coerceAtLeast(0f).toDp() })
                .height(FILMSTRIP_HEIGHT)
                .background(DIM_OUTSIDE_SELECTION),
        )

        // ── Selection stroke (lime rounded rect) ──
        Canvas(
            modifier = Modifier
                .offset {
                    androidx.compose.ui.unit.IntOffset(
                        (selectionStart - handleVisualPx / 2f).roundToInt().coerceAtLeast(0),
                        stripTopPx,
                    )
                }
                .width(with(density) { (selectionEnd - selectionStart + handleVisualPx).toDp() })
                .height(FILMSTRIP_HEIGHT),
        ) {
            val strokeWidth = 3f
            val half = strokeWidth / 2f
            val cornerPx = SELECTION_RADIUS.toPx()
            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = Rect(
                            offset = Offset(half, half),
                            size = Size(size.width - strokeWidth, size.height - strokeWidth),
                        ),
                        cornerRadius = CornerRadius(cornerPx, cornerPx),
                    ),
                )
            }
            drawPath(
                path = path,
                color = ElectricLime,
                style = Stroke(width = strokeWidth),
            )
        }

        // ── Drag handles (lime bars with pause glyphs) ──
        TrimDragHandle(
            offsetPx = { (positionPx(startMs) - handleVisualPx / 2f).roundToInt() },
            topOffsetPx = stripTopPx,
            testTag = "trim_handle_start",
            label = "Trim start",
            valueMs = startMs,
            rangeMs = 0f..durationMs.toFloat(),
            onSetValueMs = { target ->
                val clamped = target.coerceIn(0L, endMs - minGapMs)
                onStartDrag(clamped)
                onDragEnd()
            },
            visualWidthPx = handleVisualPx,
            touchWidthPx = handleTouchPx,
            stripHeightPx = stripHeightPx,
        )
        TrimDragHandle(
            offsetPx = { (positionPx(endMs) - handleVisualPx / 2f).roundToInt() },
            topOffsetPx = stripTopPx,
            testTag = "trim_handle_end",
            label = "Trim end",
            valueMs = endMs,
            rangeMs = 0f..durationMs.toFloat(),
            onSetValueMs = { target ->
                val clamped = target.coerceIn(startMs + minGapMs, durationMs)
                onEndDrag(clamped)
                onDragEnd()
            },
            visualWidthPx = handleVisualPx,
            touchWidthPx = handleTouchPx,
            stripHeightPx = stripHeightPx,
        )

        // ── Full-area drag capture ──
        // systemGestureExclusion keeps a drag that starts on an edge handle (start handle at x≈0,
        // end handle at x≈trackPx) from being stolen by the OS back-swipe gesture.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemGestureExclusion()
                .semantics { hideFromAccessibility() }
                .pointerInput(durationMs) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            // Grab a handle only when the touch lands within its touch zone. The old
                            // "nearest of the two across the whole bar" rule meant a touch anywhere in
                            // the left half snapped the start handle to the finger — the over-sensitive
                            // start-handle bug. Now a touch in the dead middle grabs nothing.
                            val distStart = abs(pos.x - positionPx(curStartMs))
                            val distEnd = abs(pos.x - positionPx(curEndMs))
                            dragging = when {
                                distStart > handleTouchPx && distEnd > handleTouchPx -> TrimDragTarget.NONE
                                distStart <= distEnd -> TrimDragTarget.START
                                else -> TrimDragTarget.END
                            }
                            if (dragging != TrimDragTarget.NONE) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            dragAnchorPx = pos.x
                            dragAnchorMs = when (dragging) {
                                TrimDragTarget.START -> curStartMs
                                TrimDragTarget.END -> curEndMs
                                TrimDragTarget.NONE -> 0L
                            }
                        },
                        onDragEnd = {
                            val wasDragging = dragging != TrimDragTarget.NONE
                            dragging = TrimDragTarget.NONE
                            if (wasDragging) dragEnd()
                        },
                        onDragCancel = { dragging = TrimDragTarget.NONE },
                    ) { change, _ ->
                        if (dragging == TrimDragTarget.NONE) return@detectDragGestures
                        change.consume()

                        // Anchored delta: handle value = (value at grab) + (how far the finger moved).
                        val targetMs = dragAnchorMs + pxToMs(change.position.x - dragAnchorPx)
                        when (dragging) {
                            TrimDragTarget.START -> {
                                val clamped = targetMs.coerceIn(0L, curEndMs - minGapMs)
                                if (clamped != curStartMs) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    startDrag(clamped)
                                }
                            }
                            TrimDragTarget.END -> {
                                val clamped = targetMs.coerceIn(curStartMs + minGapMs, durationMs)
                                if (clamped != curEndMs) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    endDrag(clamped)
                                }
                            }
                            TrimDragTarget.NONE -> {}
                        }
                    }
                },
        )
    }
}

@Composable
private fun TrimDragHandle(
    offsetPx: () -> Int,
    topOffsetPx: Int,
    testTag: String,
    label: String,
    valueMs: Long,
    rangeMs: ClosedFloatingPointRange<Float>,
    onSetValueMs: (Long) -> Unit,
    visualWidthPx: Float,
    touchWidthPx: Float,
    stripHeightPx: Int,
) {
    val density = LocalDensity.current
    val touchPadPx = ((touchWidthPx - visualWidthPx) / 2f).roundToInt()

    Box(
        modifier = Modifier
            .offset {
                androidx.compose.ui.unit.IntOffset(
                    offsetPx() - touchPadPx,
                    topOffsetPx - ((touchWidthPx - stripHeightPx) / 2f).roundToInt().coerceAtLeast(0),
                )
            }
            .width(with(density) { touchWidthPx.toDp() })
            .height(with(density) { touchWidthPx.toDp() })
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = formatTrimClock(valueMs)
                progressBarRangeInfo = ProgressBarRangeInfo(valueMs.toFloat(), rangeMs)
                setProgress { target -> onSetValueMs(target.roundToLong()); true }
            }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(with(density) { visualWidthPx.toDp() })
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(6.dp))
                .background(ElectricLime),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(
                    Modifier
                        .width(2.dp)
                        .height(18.dp)
                        .background(LimeInk),
                )
                Box(
                    Modifier
                        .width(2.dp)
                        .height(18.dp)
                        .background(LimeInk),
                )
            }
        }
    }
}
