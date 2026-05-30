package com.openrang.app.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val HANDLE_SIZE = 48.dp // ≥ 44 dp Material accessibility minimum touch target

/**
 * Post-capture Trim screen (slice 02): the captured clip loops at the top (~75% height), a two-handle
 * trim bar + full-width NEXT sit at the bottom (~25%).
 *
 * - The preview reuses the [PreviewScreen] ExoPlayer-in-[AndroidView] pattern: a single remembered
 *   player, released in [DisposableEffect], re-clipped + re-prepared whenever the trim window changes.
 * - Handle drags update local state for a responsive UI + live duration readout, and commit to the
 *   ViewModel on drag-end (which re-binds the preview to the new clip range).
 * - NEXT is enabled only when the trimmed window ≥ [OpenRangViewModel.MIN_TRIM_MS].
 * - The save checkmark is HIDDEN this slice (it comes online with the editor in slice 03).
 * - Back (gesture or arrow) opens a discard confirm dialog (Lesson 015: state-routed BackHandler).
 *
 * Flow collection uses [collectAsStateWithLifecycle] (Lesson 002). Colors come from the shared theme
 * tokens in `CameraScreen.kt`, all 8-hex `Color(0x…)` literals (Lesson 001).
 */
@OptIn(UnstableApi::class)
@Composable
fun TrimScreen(
    viewModel: OpenRangViewModel,
    modifier: Modifier = Modifier,
) {
    val editor by viewModel.editorState.collectAsStateWithLifecycle()
    val trim = editor ?: return // Nothing to edit (e.g. transient state); router keeps us on Trim.

    TrimScreenContent(
        sourceFile = trim.sourceFile,
        sourceDurationMs = trim.sourceDurationMs,
        committedStartMs = trim.trimStartMs,
        committedEndMs = trim.trimEndMs,
        onCommitTrim = viewModel::updateTrim,
        onNext = viewModel::onNextFromTrim,
        onDiscard = viewModel::discardTrim,
        modifier = modifier,
    )
}

/**
 * Stateless Trim UI, hoisted out of [TrimScreen] so it can be exercised in a Compose test without a
 * ViewModel or a real capture (mirrors the project's hoist-for-testability pattern, e.g. ShutterButton).
 * Handle positions are local for a responsive drag; they commit upward via [onCommitTrim] on drag-end.
 */
@OptIn(UnstableApi::class)
@Composable
fun TrimScreenContent(
    sourceFile: File,
    sourceDurationMs: Long,
    committedStartMs: Long,
    committedEndMs: Long,
    onCommitTrim: (Long, Long) -> Unit,
    onNext: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    var showDiscardDialog by remember { mutableStateOf(false) }
    BackHandler { showDiscardDialog = true }

    val duration = sourceDurationMs.coerceAtLeast(1L)

    // Local handle positions, seeded once per source clip. They equal the committed selection except
    // mid-drag; on a failed render the same clip re-enters Trim and these retain the selection.
    var startMs by remember(sourceFile) { mutableLongStateOf(committedStartMs) }
    var endMs by remember(sourceFile) { mutableLongStateOf(committedEndMs) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF // we loop the trimmed window manually, below
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }
    // Load the FULL clip once per source.
    LaunchedEffect(sourceFile) {
        exoPlayer.setMediaItem(MediaItem.fromUri(sourceFile.toUri()))
        exoPlayer.prepare()
    }
    // Loop the committed [start, end] window by seeking — robust against ExoPlayer treating a
    // re-clipped same-URI MediaItem as unchanged (which left the preview showing the full clip).
    // Re-keys on the committed bounds (they change on drag-end), so the preview reflects the trim.
    LaunchedEffect(committedStartMs, committedEndMs) {
        exoPlayer.seekTo(committedStartMs)
        while (isActive) {
            delay(40L)
            val pos = exoPlayer.currentPosition
            if (pos >= committedEndMs || pos + 50L < committedStartMs) {
                exoPlayer.seekTo(committedStartMs)
            }
        }
    }

    val nextEnabled = (endMs - startMs) >= OpenRangViewModel.MIN_TRIM_MS

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("trim_screen"),
    ) {
        // ── Top bar: back arrow + (hidden) save checkmark ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(HANDLE_SIZE)
                    .clip(CircleShape)
                    .background(GlassWhite)
                    .border(1.dp, GlassWhiteBorder, CircleShape)
                    .testTag("trim_back")
                    .clickable { showDiscardDialog = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Discard clip",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            // Save checkmark is HIDDEN in slice 02 — placeholder reserved for slice 03's editor.
        }

        // ── Preview (~75%) ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.75f),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                text = String.format(Locale.US, "%.1fs", (endMs - startMs) / 1000f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(DeepCharcoal)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .testTag("trim_duration_label"),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }

        // ── Controls (~25%): trim bar + NEXT ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.25f)
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, DeepCharcoal))
                )
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            TrimBar(
                durationMs = duration,
                startMs = startMs,
                endMs = endMs,
                onStartDrag = { startMs = it },
                onEndDrag = { endMs = it },
                onDragEnd = { onCommitTrim(startMs, endMs) },
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (nextEnabled) {
                            Brush.horizontalGradient(listOf(NeonCoral, NeonPurple))
                        } else {
                            Brush.horizontalGradient(listOf(GlassWhite, GlassWhite))
                        }
                    )
                    .clickable(
                        enabled = nextEnabled,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onNext() }
                    // Box+clickable carries no implicit role; tell TalkBack this is a button (the
                    // "NEXT" Text supplies the label).
                    .semantics { role = Role.Button }
                    .testTag("next_button"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "NEXT",
                    color = if (nextEnabled) Color.White else Color.White.copy(alpha = 0.4f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Discard this clip?") },
            text = { Text("Your captured clip will be deleted and you'll return to the camera.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDiscard()
                    },
                    modifier = Modifier.testTag("discard_confirm"),
                ) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { }) { Text("Keep") }
            },
        )
    }
}

private enum class TrimTarget { NONE, START, END }

/**
 * Two-handle trim bar. The thumbs (≥ 44 dp targets) are purely visual; all input is handled by a
 * full-width overlay that maps the ABSOLUTE pointer X to a millisecond value and drives the nearest
 * handle. Doing it this way — rather than putting the drag detector on each moving thumb — avoids the
 * feedback loop where offsetting a thumb under the finger shifts the gesture's own coordinate frame
 * (which made the handles snap back). Boundaries are clamped so the window can't shrink below
 * [OpenRangViewModel.MIN_TRIM_MS]; the selection commits on drag-end via [onDragEnd].
 */
@Composable
private fun TrimBar(
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    onStartDrag: (Long) -> Unit,
    onEndDrag: (Long) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(HANDLE_SIZE)
            .testTag("trim_bar"),
    ) {
        val handlePx = with(density) { HANDLE_SIZE.toPx() }
        val trackPx = constraints.maxWidth.toFloat()
        val available = (trackPx - handlePx).coerceAtLeast(1f)
        val minGapMs = OpenRangViewModel.MIN_TRIM_MS

        // Fresh views of the boundaries + callbacks for the pointerInput block: it is keyed on
        // durationMs and so does NOT restart on every recomposition, so reading the params directly
        // would clamp against stale start/end values. rememberUpdatedState keeps them current.
        val curStartMs by rememberUpdatedState(startMs)
        val curEndMs by rememberUpdatedState(endMs)
        val startDrag by rememberUpdatedState(onStartDrag)
        val endDrag by rememberUpdatedState(onEndDrag)
        val dragEnd by rememberUpdatedState(onDragEnd)

        // Left edge (px) of a handle for a given ms, and the inverse: a desired handle-center X → ms.
        fun leftPx(ms: Long): Float = (ms.toFloat() / durationMs) * available
        fun centerXToMs(centerX: Float): Long {
            val left = (centerX - handlePx / 2f).coerceIn(0f, available)
            return ((left / available) * durationMs).roundToLong()
        }

        var dragging by remember { mutableStateOf(TrimTarget.NONE) }

        // Selected-range fill between the two handle centers.
        Box(
            modifier = Modifier
                .offset { IntOffset((leftPx(startMs) + handlePx / 2f).roundToInt(), 0) }
                .width(with(density) { (leftPx(endMs) - leftPx(startMs)).toDp() })
                .fillMaxHeight()
                .padding(vertical = 18.dp)
                .background(
                    Brush.horizontalGradient(listOf(NeonCoral, NeonPurple)),
                    RoundedCornerShape(4.dp),
                ),
        )

        TrimThumb(
            offsetPx = { leftPx(startMs).roundToInt() },
            testTag = "trim_handle_start",
            label = "Trim start",
            valueMs = startMs,
            rangeMs = 0f..durationMs.toFloat(),
            onSetValueMs = { target ->
                val clamped = target.coerceIn(0L, endMs - minGapMs)
                onStartDrag(clamped)
                onDragEnd()
            },
        )
        TrimThumb(
            offsetPx = { leftPx(endMs).roundToInt() },
            testTag = "trim_handle_end",
            label = "Trim end",
            valueMs = endMs,
            rangeMs = 0f..durationMs.toFloat(),
            onSetValueMs = { target ->
                val clamped = target.coerceIn(startMs + minGapMs, durationMs)
                onEndDrag(clamped)
                onDragEnd()
            },
        )

        // Stable full-width input surface (never moves, so the gesture can't feed back on itself).
        // It sits on TOP of the handles in z-order purely to capture pointer drags, so hide it from
        // accessibility — otherwise this transparent overlay could intercept TalkBack focus and the
        // labeled, adjustable TrimThumb nodes beneath it would be unreachable. hideFromAccessibility
        // keeps its semantics for tests while making it invisible to TalkBack
        // (developer.android.com/develop/ui/compose/accessibility/merging-clearing).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics { hideFromAccessibility() }
                .pointerInput(durationMs) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val startCenter = leftPx(curStartMs) + handlePx / 2f
                            val endCenter = leftPx(curEndMs) + handlePx / 2f
                            dragging = if (abs(pos.x - startCenter) <= abs(pos.x - endCenter)) {
                                TrimTarget.START
                            } else {
                                TrimTarget.END
                            }
                        },
                        onDragEnd = { dragging = TrimTarget.NONE; dragEnd() },
                        onDragCancel = { dragging = TrimTarget.NONE },
                    ) { change, _ ->
                        change.consume()
                        when (dragging) {
                            TrimTarget.START ->
                                startDrag(centerXToMs(change.position.x).coerceIn(0L, curEndMs - minGapMs))
                            TrimTarget.END ->
                                endDrag(centerXToMs(change.position.x).coerceIn(curStartMs + minGapMs, durationMs))
                            TrimTarget.NONE -> {}
                        }
                    }
                },
        )
    }
}

/**
 * A trim handle. Visually a rounded white thumb; for accessibility it declares its own semantics —
 * "When adding custom low-level composables, you have to manually provide semantics"
 * (developer.android.com/develop/ui/compose/accessibility/semantics). It exposes a labeled,
 * adjustable value ([ProgressBarRangeInfo]) and a `setProgress` action so a TalkBack user can both
 * read the current handle position and move it (the visual drag lives on the overlay above, which is
 * pointer-only). The action clamps + commits via [onSetValueMs].
 */
@Composable
private fun TrimThumb(
    offsetPx: () -> Int,
    testTag: String,
    label: String,
    valueMs: Long,
    rangeMs: ClosedFloatingPointRange<Float>,
    onSetValueMs: (Long) -> Unit,
) {
    Box(
        modifier = Modifier
            .offset { IntOffset(offsetPx(), 0) }
            .size(HANDLE_SIZE)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .border(2.dp, NeonPurple, RoundedCornerShape(8.dp))
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = String.format(Locale.US, "%.1f seconds", valueMs / 1000f)
                progressBarRangeInfo = ProgressBarRangeInfo(valueMs.toFloat(), rangeMs)
                setProgress { target -> onSetValueMs(target.roundToLong()); true }
            }
            .testTag(testTag),
    )
}
