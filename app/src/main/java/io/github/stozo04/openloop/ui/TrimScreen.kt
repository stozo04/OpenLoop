package io.github.stozo04.openloop.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
import io.github.stozo04.openloop.ui.components.EditorBottomToolbar
import io.github.stozo04.openloop.ui.components.EditorLoadingOverlay
import io.github.stozo04.openloop.ui.components.EditorToolbarSlot
import io.github.stozo04.openloop.ui.components.PrimaryButtonPressedScale
import io.github.stozo04.openloop.ui.components.TrimFilmstripControls
import io.github.stozo04.openloop.ui.components.toEditorTab
import io.github.stozo04.openloop.ui.theme.ElectricLime
import io.github.stozo04.openloop.ui.theme.LimeInk
import io.github.stozo04.openloop.ui.theme.OpenLoopBackground
import io.github.stozo04.openloop.ui.theme.SurfaceContainer
import java.io.File

/**
 * Post-capture Trim screen: video preview on top, filmstrip trim controls + bottom toolbar below.
 * SAVE (or Speed / Loop / Filter on the toolbar) advances into the tabbed editor when the trim
 * window meets [OpenLoopViewModel.MIN_TRIM_MS].
 */
@OptIn(UnstableApi::class)
@Composable
fun TrimScreen(
    viewModel: OpenLoopViewModel,
    modifier: Modifier = Modifier,
) {
    val editor by viewModel.editorState.collectAsStateWithLifecycle()
    val sessionOverlay by viewModel.sessionOverlayLoading.collectAsStateWithLifecycle()
    val trim = editor ?: return

    TrimScreenContent(
        sourceFile = trim.sourceFile,
        sourceDurationMs = trim.sourceDurationMs,
        committedStartMs = trim.trimStartMs,
        committedEndMs = trim.trimEndMs,
        onCommitTrim = viewModel::updateTrim,
        onNext = viewModel::onNextFromTrim,
        onAdvanceToEditor = viewModel::onNextFromTrim,
        onDiscard = viewModel::discardTrim,
        sessionOverlayLoading = sessionOverlay,
        modifier = modifier,
    )
}

@OptIn(UnstableApi::class)
@Composable
fun TrimScreenContent(
    // `modifier` is the FIRST optional parameter (Compose API guideline, lint ModifierParameter —
    // PR #58 review). Every call site uses named arguments, so this reorder is source-compatible.
    sourceFile: File,
    sourceDurationMs: Long,
    committedStartMs: Long,
    committedEndMs: Long,
    onCommitTrim: (Long, Long) -> Unit,
    onNext: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
    onAdvanceToEditor: (EditorTab) -> Unit = { onNext() },
    sessionOverlayLoading: EditorLoadingKind? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var showDiscardDialog by remember { mutableStateOf(false) }
    BackHandler { showDiscardDialog = true }

    val duration = sourceDurationMs.coerceAtLeast(1L)

    var startMs by remember(sourceFile) { mutableLongStateOf(committedStartMs) }
    var endMs by remember(sourceFile) { mutableLongStateOf(committedEndMs) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }
    LaunchedEffect(sourceFile, committedStartMs, committedEndMs) {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
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

    val trimValid = (endMs - startMs) >= OpenLoopViewModel.MIN_TRIM_DURATION.inWholeMilliseconds

    OpenLoopBackground(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("trim_screen"),
        ) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                Text(
                    text = "Trim",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.Center),
                )
                TrimSaveButton(
                    enabled = trimValid,
                    onClick = onNext,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .testTag("trim_save"),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
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
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceContainer)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                TrimFilmstripControls(
                    sourceFile = sourceFile,
                    durationMs = duration,
                    startMs = startMs,
                    endMs = endMs,
                    onStartDrag = { startMs = it },
                    onEndDrag = { endMs = it },
                    onDragEnd = { onCommitTrim(startMs, endMs) },
                )
            }

            EditorBottomToolbar(
                activeSlot = EditorToolbarSlot.TRIM,
                onTrimClick = { /* already on Trim */ },
                onSpeedClick = {
                    if (trimValid) onAdvanceToEditor(EditorToolbarSlot.SPEED.toEditorTab())
                },
                onLoopClick = {
                    if (trimValid) onAdvanceToEditor(EditorToolbarSlot.LOOP.toEditorTab())
                },
                onFilterClick = {
                    if (trimValid) onAdvanceToEditor(EditorToolbarSlot.FILTER.toEditorTab())
                },
            onDeleteClick = { showDiscardDialog = true },
        )
        }

        if (sessionOverlayLoading != null) {
            EditorLoadingOverlay(
                message = sessionOverlayLoading.message,
                modifier = Modifier.fillMaxSize(),
                testTag = "session_loading_overlay",
            )
        }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard this clip?") },
            text = { Text("Your captured clip will be deleted and you'll return to the camera.") },
            confirmButton = {
                TextButton(
                    onClick = { onDiscard() },
                    modifier = Modifier.testTag("discard_confirm"),
                ) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("Keep") }
            },
        )
    }
}

/** Lime SAVE pill in the top bar (reference trim mock). */
@Composable
private fun TrimSaveButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (enabled && isPressed) PrimaryButtonPressedScale else 1f,
        label = "trim_save_scale",
    )
    val background = if (enabled) ElectricLime else ElectricLime.copy(alpha = 0.35f)
    val textColor = if (enabled) LimeInk else Color.White.copy(alpha = 0.5f)

    Text(
        text = "SAVE",
        color = textColor,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(percent = 50))
            .background(background)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onClick()
            }
            .padding(horizontal = 18.dp, vertical = 8.dp),
    )
}
