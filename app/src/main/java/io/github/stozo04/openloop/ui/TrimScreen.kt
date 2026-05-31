package io.github.stozo04.openloop.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.stozo04.openloop.ui.components.BackButton
import io.github.stozo04.openloop.ui.components.PrimaryButton
import io.github.stozo04.openloop.ui.theme.ElectricLime
import io.github.stozo04.openloop.ui.theme.OpenLoopBackground
import io.github.stozo04.openloop.ui.theme.OutlineVariant
import io.github.stozo04.openloop.ui.theme.OverlayScrim
import io.github.stozo04.openloop.ui.theme.SurfaceContainerHigh
import io.github.stozo04.openloop.ui.theme.TextSecondary
import io.github.stozo04.openloop.ui.theme.TimerTextStyle
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val HANDLE_SIZE = 48.dp // ≥ 44 dp Material accessibility minimum touch target
private val HANDLE_LABEL_STRIP = 46.dp // strip above the thumb row holding each handle's value bubble

/**
 * Post-capture Trim screen (slice 02): the captured clip loops at the top (~75% height), a two-handle
 * trim bar + full-width NEXT sit at the bottom (~25%).
 *
 * - The preview uses the ExoPlayer-in-[AndroidView] pattern: a single remembered
 *   player, released in [DisposableEffect], re-clipped + re-prepared whenever the trim window changes.
 * - Handle drags update local state for a responsive UI + live duration readout, and commit to the
 *   ViewModel on drag-end (which re-binds the preview to the new clip range).
 * - NEXT is enabled only when the trimmed window ≥ [OpenLoopViewModel.MIN_TRIM_MS].
 * - The save checkmark is HIDDEN this slice (it comes online with the editor in slice 03).
 * - Back (gesture or arrow) opens a discard confirm dialog (Lesson 015: state-routed BackHandler).
 *
 * Flow collection uses [collectAsStateWithLifecycle] (Lesson 002). Colors come from the shared theme
 * tokens in `CameraScreen.kt`, all 8-hex `Color(0x…)` literals (Lesson 001).
 */
@OptIn(UnstableApi::class)
@Composable
fun TrimScreen(
    viewModel: OpenLoopViewModel,
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
            repeatMode = Player.REPEAT_MODE_OFF // looped via the STATE_ENDED listener below
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }
    // Frame-accurate window: hand ExoPlayer a ClippingConfiguration so the *player itself* enforces
    // [start, end] (developer.android.com/media/media3/exoplayer/media-items). Rebuilding the
    // MediaItem + prepare() on every committed-bound change forces a fresh ClippingMediaSource (the
    // earlier same-URI re-clip looked "unchanged" because the timeline wasn't rebuilt). This replaces
    // the previous manual seek-loop, which polled position every 40 ms and so always overshot the end
    // before snapping back — visibly so on heavy clips. Re-keys on the committed bounds (drag-end).
    LaunchedEffect(sourceFile, committedStartMs, committedEndMs) {
        exoPlayer.setMediaItem(
            MediaItem.Builder()
                .setUri(sourceFile.toUri())
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(committedStartMs)
                        .setEndPositionMs(committedEndMs)
                        .build()
                )
                .build()
        )
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }
    // Loop the clipped window without overshoot: the clip ends exactly at [end], firing STATE_ENDED;
    // restart from the clip's start (position 0 of the clipped timeline). Event-driven (no polling),
    // so the preview never runs past the end handle. REPEAT_MODE_ALL is deliberately avoided — it has
    // known stalls looping a single clipped item (github.com/androidx/media/issues/845).
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    exoPlayer.seekTo(0)
                    exoPlayer.play()
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    val nextEnabled = (endMs - startMs) >= OpenLoopViewModel.MIN_TRIM_MS

    OpenLoopBackground(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("trim_screen"),
        ) {
        // ── Top bar: back arrow + (hidden) save checkmark ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            BackButton(
                contentDescription = "Discard clip",
                onClick = { showDiscardDialog = true },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .testTag("trim_back"),
            )
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
                    .background(OverlayScrim)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .testTag("trim_duration_label"),
                color = Color.White,
                style = TimerTextStyle,
            )
        }

        // ── Controls (~25%): trim bar + NEXT ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.25f)
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, OverlayScrim))
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

            PrimaryButton(
                text = "NEXT",
                onClick = onNext,
                enabled = nextEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp),
                testTag = "next_button",
            )
        }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            // Back press / scrim tap == "Keep" (the safe, non-destructive choice): just close the dialog.
            onDismissRequest = { showDiscardDialog = false },
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
                // "Keep" must dismiss the dialog so the user returns to trimming — previously a no-op,
                // which trapped them in the dialog with no way to continue.
                TextButton(onClick = { showDiscardDialog = false }) { Text("Keep") }
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
 * [OpenLoopViewModel.MIN_TRIM_MS]; the selection commits on drag-end via [onDragEnd].
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
            .height(HANDLE_LABEL_STRIP + HANDLE_SIZE)
            .testTag("trim_bar"),
    ) {
        val handlePx = with(density) { HANDLE_SIZE.toPx() }
        val trackPx = constraints.maxWidth.toFloat()
        val available = (trackPx - handlePx).coerceAtLeast(1f)
        val minGapMs = OpenLoopViewModel.MIN_TRIM_MS
        // The thumb row sits below a strip that holds each handle's live time readout.
        val rowTopPx = with(density) { HANDLE_LABEL_STRIP.toPx() }.roundToInt()

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

        // Live per-handle value bubbles: a labelled "START" / "END" tooltip floating above each
        // thumb, showing the exact timestamp the handle currently points at. They read startMs/endMs
        // in composition so the time tracks the handle as it drags (the value the user is selecting),
        // distinct from the centre duration pill, which shows the resulting window length. The bubble
        // sizes to its content and clamps inside the track, so the readout can never clip or run off
        // the edge (the failure mode of the old fixed-width pill).
        HandleValueBubble(
            caption = "START",
            timeMs = startMs,
            handleLeftPx = { leftPx(startMs).roundToInt() },
            handlePx = handlePx,
            trackPx = trackPx,
            active = dragging == TrimTarget.START,
            testTag = "trim_label_start",
        )
        HandleValueBubble(
            caption = "END",
            timeMs = endMs,
            handleLeftPx = { leftPx(endMs).roundToInt() },
            handlePx = handlePx,
            trackPx = trackPx,
            active = dragging == TrimTarget.END,
            testTag = "trim_label_end",
        )

        // Selected-range fill between the two handle centers (in the thumb row, below the labels).
        Box(
            modifier = Modifier
                .offset { IntOffset((leftPx(startMs) + handlePx / 2f).roundToInt(), rowTopPx) }
                .width(with(density) { (leftPx(endMs) - leftPx(startMs)).toDp() })
                .height(HANDLE_SIZE)
                .padding(vertical = 18.dp)
                .background(
                    ElectricLime,
                    RoundedCornerShape(4.dp),
                ),
        )

        TrimThumb(
            offsetPx = { leftPx(startMs).roundToInt() },
            topOffsetPx = rowTopPx,
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
            topOffsetPx = rowTopPx,
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
    topOffsetPx: Int,
    testTag: String,
    label: String,
    valueMs: Long,
    rangeMs: ClosedFloatingPointRange<Float>,
    onSetValueMs: (Long) -> Unit,
) {
    Box(
        modifier = Modifier
            .offset { IntOffset(offsetPx(), topOffsetPx) }
            .size(HANDLE_SIZE)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(Color.White)
            .border(2.dp, ElectricLime, MaterialTheme.shapes.extraSmall)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = String.format(Locale.US, "%.1f seconds", valueMs / 1000f)
                progressBarRangeInfo = ProgressBarRangeInfo(valueMs.toFloat(), rangeMs)
                setProgress { target -> onSetValueMs(target.roundToLong()); true }
            }
            .testTag(testTag),
    )
}

/**
 * Live value tooltip that floats in the label strip above its thumb (the dual-thumb range-slider
 * pattern). A muted [caption] ("START" / "END") tells the user which frame the handle controls, with
 * the exact timestamp below it in the mono [TimerTextStyle], so the readout is unambiguous as either
 * handle drags. Reads [timeMs] in composition so the value tracks the thumb live.
 *
 * Sizing/position rules that fix the old fixed-width pill (which clipped everything after the
 * decimal): the bubble WRAPS its content — no fixed width — and its x-offset centres it over the
 * handle but is clamped into `[0, trackPx - bubbleWidth]`, so the pill can never clip its own text
 * nor slide off either edge of the track. The active handle's bubble is accented in [ElectricLime].
 * Purely visual — the pointer overlay above owns all input, so it carries no semantics of its own.
 */
@Composable
private fun HandleValueBubble(
    caption: String,
    timeMs: Long,
    handleLeftPx: () -> Int,
    handlePx: Float,
    trackPx: Float,
    active: Boolean,
    testTag: String,
) {
    var bubbleWidthPx by remember { mutableIntStateOf(0) }
    val shape = MaterialTheme.shapes.small
    Column(
        modifier = Modifier
            // Centre over the handle, then clamp so the (content-sized) bubble stays fully on-track.
            // Read in the offset lambda so a drag only re-lays-out this bubble, not the whole bar.
            .offset {
                val center = handleLeftPx() + handlePx / 2f
                val maxX = (trackPx - bubbleWidthPx).coerceAtLeast(0f)
                IntOffset((center - bubbleWidthPx / 2f).coerceIn(0f, maxX).roundToInt(), 0)
            }
            .onSizeChanged { bubbleWidthPx = it.width }
            .clip(shape)
            .background(SurfaceContainerHigh)
            .border(1.dp, if (active) ElectricLime else OutlineVariant, shape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
        Text(
            text = String.format(Locale.US, "%.1fs", timeMs / 1000f),
            style = TimerTextStyle,
            color = if (active) ElectricLime else Color.White,
        )
    }
}
