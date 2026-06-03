package io.github.stozo04.openloop.ui

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.stozo04.openloop.ui.components.EditorBottomToolbar
import io.github.stozo04.openloop.ui.components.EditorLoadingOverlay
import io.github.stozo04.openloop.ui.components.EditorToolbarSlot
import io.github.stozo04.openloop.ui.components.FilterTabPanel
import io.github.stozo04.openloop.ui.components.LoopTabPanel
import io.github.stozo04.openloop.ui.components.SpeedTabPanel
import io.github.stozo04.openloop.ui.components.toToolbarSlot
import io.github.stozo04.openloop.ui.components.PrimaryButtonPressedScale
import io.github.stozo04.openloop.ui.theme.ElectricLime
import io.github.stozo04.openloop.ui.theme.LimeInk
import io.github.stozo04.openloop.ui.theme.OpenLoopBackground
import io.github.stozo04.openloop.ui.theme.OverlayScrim
import io.github.stozo04.openloop.ui.theme.OverlayWhite
import io.github.stozo04.openloop.ui.theme.OverlayWhiteBorder
import io.github.stozo04.openloop.ui.theme.TimerTextStyle
import io.github.stozo04.openloop.media.BoomerangMode
import io.github.stozo04.openloop.media.ClipDirection
import io.github.stozo04.openloop.media.VideoFilter
import io.github.stozo04.openloop.media.boomerangOutputDurationMs
import io.github.stozo04.openloop.media.boomerangSequence
import io.github.stozo04.openloop.media.needsReverse
import io.github.stozo04.openloop.media.sourceSeamDurationMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Hit target ≥ 48 dp (Material / ANDROID_STANDARDS §7 minimum) for the top-bar buttons and chips. */
private val CONTROL_SIZE = 56.dp

/** Max width of the tab-content row so chips / slider stay centered (not edge-spread) on ≥ 600 dp displays. */
private val CONTENT_MAX_WIDTH = 520.dp

/** Tab-panel heights: fixed per tab so the bottom toolbar stays put; Speed is tallest (slider + pill). */
private fun editorPanelHeight(tab: EditorTab) = when (tab) {
    EditorTab.SPEED -> 240.dp
    EditorTab.LOOKS -> 188.dp
    EditorTab.DIRECTION -> 168.dp
}

/** Debounce before pushing a new speed to the player — coalesces a drag's stream into one apply. */
private const val SPEED_DEBOUNCE_MS = 50L

/** Debounce playlist rebinding — coalesces trim/mode/seam changes (editor-memory-oom WS-2). */
private val PLAYLIST_DEBOUNCE_MS = EditorPlaylistBind.PLAYLIST_DEBOUNCE_MS

/**
 * Tabbed boomerang editor. Opens from the Trim screen's NEXT with the trimmed clip already
 * boomeranged (`FORWARD_THEN_REVERSE` default) looping in the preview. Slices 03–04 expose two
 * interactive tabs — **Direction** (four chips) and **Speed** (a slider) — plus a Save checkmark; a
 * disabled **Reps** stub holds its slot for slice 05.
 *
 * Reps (1) is hard-wired this slice; [OpenLoopViewModel.saveBoomerang] renders with the selected
 * direction + speed. Flow collection uses [collectAsStateWithLifecycle] (Lesson 002); colors are the
 * shared `CameraScreen.kt` tokens, all 8-hex literals (Lesson 001).
 */
@Composable
fun BoomerangEditorScreen(
    viewModel: OpenLoopViewModel,
    modifier: Modifier = Modifier,
) {
    val trim by viewModel.editorState.collectAsStateWithLifecycle()
    val tab by viewModel.editorTabState.collectAsStateWithLifecycle()
    val sessionOverlay by viewModel.sessionOverlayLoading.collectAsStateWithLifecycle()
    val editor = trim ?: return // No active session (transient state); router keeps us here.

    BoomerangEditorContent(
        sourceFile = editor.sourceFile,
        trimStartMs = editor.trimStartMs,
        trimEndMs = editor.trimEndMs,
        mode = tab.mode,
        speed = tab.speed,
        filter = tab.filter,
        activeTab = tab.activeTab,
        reversedFile = tab.reversedFile,
        previewLoading = tab.previewLoading,
        sessionOverlayLoading = sessionOverlay,
        reverseFailed = tab.reverseFailed,
        reverseSupportReport = tab.reverseSupportReport,
        effectsPreviewEnabled = tab.effectsPreviewEnabled,
        onRetryReverse = viewModel::retryReverseSegment,
        onSelectMode = viewModel::updateMode,
        onSpeedChange = viewModel::updateSpeed,
        onFilterChange = viewModel::updateFilter,
        onFilterPreviewSettled = viewModel::onFilterPreviewSettled,
        onSwitchTab = viewModel::switchTab,
        onSave = viewModel::saveBoomerang,
        onGoToTrim = viewModel::backToTrim,
        onDiscard = viewModel::discardTrim,
        modifier = modifier,
    )
}

/**
 * Stateless editor UI, hoisted out of [BoomerangEditorScreen] so it can be exercised in a Compose
 * test without a ViewModel (mirrors `TrimScreenContent`). The direction-aware preview is built from
 * the trimmed source and the (cached) reversed file; while a reverse-containing mode is selected and
 * its reversed clip isn't ready yet, a "Loopifying…" shimmer covers the preview and Save is
 * disabled. The new slice-04 params ([speed], [activeTab], [onSpeedChange], [onSwitchTab]) default so
 * slice-03 tests that only drive Direction keep compiling.
 */
@OptIn(UnstableApi::class)
@Composable
fun BoomerangEditorContent(
    sourceFile: File,
    trimStartMs: Long,
    trimEndMs: Long,
    mode: BoomerangMode,
    reversedFile: File?,
    previewLoading: EditorLoadingKind? = null,
    sessionOverlayLoading: EditorLoadingKind? = null,
    onSelectMode: (BoomerangMode) -> Unit,
    onSave: () -> Unit,
    onGoToTrim: () -> Unit,
    modifier: Modifier = Modifier,
    speed: Float = OpenLoopViewModel.DEFAULT_SPEED,
    filter: VideoFilter = VideoFilter.ORIGINAL,
    activeTab: EditorTab = EditorTab.DIRECTION,
    reverseFailed: Boolean = false,
    reverseSupportReport: String? = null,
    effectsPreviewEnabled: Boolean = true,
    onRetryReverse: () -> Unit = {},
    onSpeedChange: (Float) -> Unit = {},
    onFilterChange: (VideoFilter) -> Unit = {},
    onFilterPreviewSettled: () -> Unit = {},
    onSwitchTab: (EditorTab) -> Unit = {},
    onDiscard: () -> Unit = {},
) {
    val context = LocalContext.current

    // One representative frame from the trim, decoded off the main thread and cached for the Looks
    // tab's filter thumbnails. Extracted at the content level (not inside the tab panel) so it survives
    // tab switches and is ready the instant the user opens Looks; null while loading / on failure.
    val thumbnailFrame by produceState<Bitmap?>(null, sourceFile, trimStartMs, trimEndMs) {
        value = withContext(Dispatchers.IO) { extractRepresentativeFrame(sourceFile, trimStartMs, trimEndMs) }
    }
    // Match export seam skip: read source fps off main thread; 33 ms placeholder until IO completes (~30 fps).
    val seamMs by produceState(33L, sourceFile) {
        value = withContext(Dispatchers.IO) { sourceSeamDurationMs(sourceFile) }
    }

    var showDeleteClipDialog by remember { mutableStateOf(false) }

    // Reverse still generating / failed, or any preview overlay (Trimming…, Hold Tight, etc.).
    val awaitingReverse = mode.needsReverse && !reverseFailed && reversedFile == null
    val reverseUnavailable = mode.needsReverse && reverseFailed && reversedFile == null
    // Never show reverse-prep copy once the reversed clip exists (guards stale ViewModel state).
    val effectivePreviewLoading =
        if (reversedFile != null) {
            when (previewLoading) {
                EditorLoadingKind.TRIMMING, EditorLoadingKind.LOOPIFYING -> null
                else -> previewLoading
            }
        } else {
            previewLoading
        }
    val activeOverlay = sessionOverlayLoading ?: effectivePreviewLoading
    val saveEnabled = activeOverlay == null && !awaitingReverse && !reverseUnavailable

    var playlistRebindCount by remember { mutableIntStateOf(0) }
    val editorEnteredAtMs = remember { System.currentTimeMillis() }

    // Player teardown epoch (PR #58 review). setVideoEffects is player-wide and survives every
    // stop/clearMediaItems/prepare rebind, and setVideoEffects(emptyList()) is forbidden (HDR-seam
    // comment below) — so the ONLY way to drop an already-applied look's DefaultVideoFrameProcessor
    // is to recreate the player. Bumping the epoch releases the old instance (DisposableEffect
    // below) and builds a fresh one with no frame processor attached.
    var playerEpoch by remember { mutableIntStateOf(0) }
    // True once setVideoEffects ran with a non-empty list on the CURRENT player instance; reset
    // before each epoch bump so a fresh player never inherits a stale "applied" flag.
    var playerHasAppliedEffects by remember { mutableStateOf(false) }

    val exoPlayer = remember(playerEpoch) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL // loop the concatenated boomerang cycle
            playWhenReady = true
            setPlaybackSpeed(speed) // start at the chosen speed so the preview never flashes 1× first
            // Mute: the exported boomerang is silent (parent doc D-3), and at non-1× speed the raw
            // forward clip's audio pitch-shifts (chipmunk/drone) while the reversed half is already
            // stripped — a jarring artifact with no payoff. Slice-04 kickoff §3 requirement.
            volume = 0f
        }
    }
    // Pause on ON_STOP so a backgrounded editor doesn't keep its decoder running while a reverse pass
    // may still need codec slots (this PR's contention theme); resume on ON_START when a playlist is
    // bound. Releasing still happens on dispose. (Media3 lifecycle-aware playback guidance.)
    val lifecycleOwner = LocalLifecycleOwner.current
    // Keyed on exoPlayer so an epoch bump releases the OLD instance before the fresh one binds —
    // release() is what actually drops the applied-effects GL pipeline and its decoders.
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> exoPlayer.pause()
                Lifecycle.Event.ON_START -> if (exoPlayer.mediaItemCount > 0) exoPlayer.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }
    // Editor-session telemetry logs once per real leave-composition (NOT per player epoch — keying
    // this on exoPlayer would skew playlist_rebind_count/editor_duration_sec on every teardown).
    DisposableEffect(Unit) {
        onDispose {
            val durationSec =
                ((System.currentTimeMillis() - editorEnteredAtMs) / 1_000L).coerceAtLeast(0L)
            io.github.stozo04.openloop.diagnostics.ReverseCrashlytics.logEditorDispose(
                playlistRebindCount,
                durationSec,
            )
        }
    }

    // While reverse preview is generating, do not prepare ExoPlayer — Samsung devices exhaust or
    // invalidate extra MediaCodec instances when ExoPlayer holds c2.exynos.h264.decoder and
    // VideoReverser pass 1 opens another decoder/encoder (IllegalStateException: Released state).
    val reversePreviewLoading = effectivePreviewLoading.isReversePreviewLoading()

    // Rebind the playlist whenever the direction, the reversed file, or the trim changes. setMediaItems
    // replaces the whole playlist (no in-place re-clip of a same-URI item, which ExoPlayer dedupes —
    // slice-02 HANDOFF), then prepare() restarts playback of the new cycle. Both the speed
    // (PlaybackParameters) and the look (setVideoEffects) are player-wide settings, not per-MediaItem,
    // so they survive this rebind — we don't re-apply either here. The LaunchedEffect(filter) below
    // owns applying the look.
    // exoPlayer is a key so a recreated (epoch-bumped) instance gets the playlist rebound — without
    // it the fresh player would sit empty and the preview would go black.
    LaunchedEffect(exoPlayer, mode, reversedFile, trimStartMs, trimEndMs, seamMs, reversePreviewLoading) {
        delay(PLAYLIST_DEBOUNCE_MS)
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        if (EditorPlaylistBind.shouldHoldPlaylist(reversePreviewLoading)) {
            return@LaunchedEffect
        }
        val items = previewPlaylist(sourceFile, trimStartMs, trimEndMs, mode, reversedFile, seamMs)
        if (EditorPlaylistBind.shouldClearPlaylist(items.isEmpty())) {
            return@LaunchedEffect
        }
        exoPlayer.setMediaItems(items)
        // playWhenReady = true (set on the builder), so prepare() starts playback — no explicit play().
        exoPlayer.prepare()
        playlistRebindCount++
    }

    // Apply the color look live (the Looks tab's whole point). setVideoEffects is ExoPlayer's preview
    // path for effects (same Effect objects as the render), so tapping a look re-tints the running
    // preview without a re-render. Independent of speed (a player setting) — they compose.
    //
    // Do NOT call setVideoEffects(emptyList()) for [VideoFilter.ORIGINAL]: even an empty list routes
    // through DefaultVideoFrameProcessor, which cannot hand off from an imported HDR forward clip to
    // the tone-mapped SDR reversed clip — playback freezes at the seam with checkColors /
    // ExoPlaybackException (LogCat: VideoFrameProcessingException / IllegalArgumentException).
    LaunchedEffect(exoPlayer, filter, effectsPreviewEnabled) {
        if (!shouldApplyVideoEffectsPreview(effectsPreviewEnabled, filter)) {
            // PR #58 review: closing the gate must also DROP effects already applied to the player
            // (they survive every rebind). Recreate the player — the only sanctioned teardown given
            // the no-empty-list rule above. The epoch bump re-runs this effect with the fresh
            // instance; playerHasAppliedEffects is false by then, so it settles in one pass.
            if (shouldTearDownEffectsPlayer(playerHasAppliedEffects, effectsPreviewEnabled, filter)) {
                playerHasAppliedEffects = false
                playerEpoch++
            }
            onFilterPreviewSettled()
            return@LaunchedEffect
        }
        exoPlayer.setVideoEffects(filter.toMediaEffects())
        playerHasAppliedEffects = true
        onFilterPreviewSettled()
    }

    // When a look *is* active, re-apply it at each playlist-item transition so the GL pipeline
    // reconfigures cleanly across the HDR forward → SDR reversed seam on imported clips.
    DisposableEffect(exoPlayer, filter, effectsPreviewEnabled) {
        if (!shouldApplyVideoEffectsPreview(effectsPreviewEnabled, filter)) {
            onDispose { }
        } else {
            val effects = filter.toMediaEffects()
            val listener = object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    exoPlayer.setVideoEffects(effects)
                }
            }
            exoPlayer.addListener(listener)
            onDispose { exoPlayer.removeListener(listener) }
        }
    }

    // Apply speed to the preview, debounced: re-keying on `speed` cancels the prior pending delay, so a
    // drag's stream of values collapses into a single setPlaybackSpeed once the user settles (~50 ms).
    // This is a player-side effect — free, no re-render, and independent of the cached reversed clip.
    LaunchedEffect(speed) {
        delay(SPEED_DEBOUNCE_MS)
        exoPlayer.setPlaybackSpeed(speed)
    }

    OpenLoopBackground(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("editor_screen"),
        ) {
        // ── Top bar: save checkmark (right) ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            SaveCheckmark(
                enabled = saveEnabled,
                onClick = onSave,
                modifier = Modifier.align(Alignment.CenterEnd).testTag("editor_save"),
            )
        }

        // ── Preview (fills the space above the fixed control panel + tab bar → ~75%) ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                // Bind in update (runs after factory and on every recomposition) so an epoch-bumped
                // player replaces the released one — a factory-only bind would leave the view on the
                // dead instance after an effects teardown.
                update = { view -> if (view.player !== exoPlayer) view.player = exoPlayer },
                modifier = Modifier.fillMaxSize().testTag("editor_preview"),
            )

            if (activeOverlay != null) {
                EditorLoadingOverlay(
                    message = activeOverlay.message,
                    modifier = Modifier.fillMaxSize(),
                    testTag = "reverse_loading",
                )
            }

            // Reverse generation failed for this clip (e.g. an HDR/codec the device can't tone-map).
            // Surface it with a retry instead of a permanent shimmer; the user can also pick the
            // Forward direction, which needs no reverse.
            if (reverseUnavailable) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .testTag("reverse_failed"),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.medium)
                            .background(OverlayScrim)
                            .border(1.dp, OverlayWhiteBorder, MaterialTheme.shapes.medium)
                            .padding(horizontal = 28.dp, vertical = 22.dp),
                    ) {
                        Text(
                            text = "Couldn't loop that clip",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Try again, or pick the Forward direction.",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Send debug info below, or reopen the app once to upload an automatic report.",
                            color = Color.White.copy(alpha = 0.55f),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "TRY AGAIN",
                            color = LimeInk,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.small)
                                .background(ElectricLime)
                                .clickable { onRetryReverse() }
                                .padding(horizontal = 24.dp, vertical = 10.dp)
                                .testTag("reverse_retry"),
                        )
                        if (!reverseSupportReport.isNullOrBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "SEND DEBUG INFO",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.small)
                                    .border(1.dp, OverlayWhiteBorder, MaterialTheme.shapes.small)
                                    .clickable {
                                        val share = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, reverseSupportReport)
                                            putExtra(Intent.EXTRA_SUBJECT, "OpenLoop loop debug")
                                        }
                                        context.startActivity(
                                            Intent.createChooser(share, "Send debug info"),
                                        )
                                    }
                                    .padding(horizontal = 20.dp, vertical = 10.dp)
                                    .testTag("reverse_send_debug"),
                            )
                        }
                    }
                }
            }

            // Hidden under the shimmer so the duration chip doesn't sit on top of the "Loopifying…"
            // scrim; it returns the moment the reversed clip is ready and the preview is live. With the
            // Speed tab live, this chip is the user's only *visual* speed feedback (the slider is
            // deliberately label-free), so it recomputes from the current `speed`.
            if (!awaitingReverse) {
                Text(
                    text = String.format(
                        Locale.US,
                        "%.1fs",
                        boomerangOutputDurationMs(
                            mode = mode,
                            trimStartMs = trimStartMs,
                            trimEndMs = trimEndMs,
                            speed = speed,
                            repetitions = OpenLoopViewModel.DEFAULT_REPS,
                        ) / 1000f,
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(OverlayScrim)
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .testTag("editor_duration_label"),
                    color = Color.White,
                    style = TimerTextStyle,
                )
            }
        }

        // ── Tab content panel: cross-fades between tabs (per-tab height → bottom bar stable, no clip) ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(editorPanelHeight(activeTab))
                .background(Brush.verticalGradient(listOf(Color.Transparent, OverlayScrim))),
            contentAlignment = Alignment.TopCenter,
        ) {
            AnimatedContent(
                targetState = activeTab,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                label = "editor_tab_content",
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) { tab ->
                when (tab) {
                    EditorTab.DIRECTION -> LoopTabPanel(mode = mode, onSelectMode = onSelectMode)
                    EditorTab.SPEED -> SpeedTabPanel(speed = speed, onSpeedChange = onSpeedChange)
                    EditorTab.LOOKS -> FilterTabPanel(
                        filter = filter,
                        thumbnailFrame = thumbnailFrame,
                        onFilterChange = onFilterChange,
                        filtersEnabled = effectsPreviewEnabled,
                        disabledHint = if (!effectsPreviewEnabled) {
                            // Cause-neutral: the gate closes on reverse failure OR memory pressure.
                            "Preview unavailable (low memory or reverse error)"
                        } else {
                            null
                        },
                    )
                }
            }
        }

        EditorBottomToolbar(
            activeSlot = activeTab.toToolbarSlot(),
            onTrimClick = onGoToTrim,
            onSpeedClick = { onSwitchTab(EditorTab.SPEED) },
            onLoopClick = { onSwitchTab(EditorTab.DIRECTION) },
            onFilterClick = { onSwitchTab(EditorTab.LOOKS) },
            onDeleteClick = { showDeleteClipDialog = true },
        )
        }
    }

    if (showDeleteClipDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteClipDialog = false },
            title = { Text("Discard this clip?") },
            text = { Text("Your captured clip will be deleted and you'll return to the camera.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteClipDialog = false
                        onDiscard()
                    },
                    modifier = Modifier.testTag("discard_confirm"),
                ) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteClipDialog = false }) { Text("Keep editing") }
            },
        )
    }
}

/** Build the looping preview playlist for [mode]; empty when a reversed clip is needed but absent. */
private fun previewPlaylist(
    sourceFile: File,
    trimStartMs: Long,
    trimEndMs: Long,
    mode: BoomerangMode,
    reversedFile: File?,
    seamMs: Long,
    repetitions: Int = 1,
): List<MediaItem> {
    fun trimmed(dropLeadingMs: Long): MediaItem = MediaItem.Builder()
        .setUri(sourceFile.toUri())
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(trimStartMs + dropLeadingMs)
                .setEndPositionMs(trimEndMs)
                .build(),
        )
        .build()

    fun reversed(dropLeadingMs: Long): MediaItem? = reversedFile?.let {
        MediaItem.Builder()
            .setUri(it.toUri())
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(dropLeadingMs)
                    .build(),
            )
            .build()
    }

    // Same clip plan + position-based seam drop as export (boomerangSequence + Lesson 018).
    return boomerangSequence(mode, repetitions).mapNotNull { spec ->
        val dropMs = if (spec.dropLeadingFrame) seamMs else 0L
        when (spec.direction) {
            ClipDirection.FORWARD -> trimmed(dropMs)
            ClipDirection.REVERSED -> reversed(dropMs)
        }
    }
}

/** Save checkmark: a filled [ElectricLime] circle; dimmed + non-clickable while the reverse isn't ready. */
@Composable
private fun SaveCheckmark(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && isPressed) PrimaryButtonPressedScale else 1f,
        label = "save_checkmark_scale",
    )
    Box(
        modifier = modifier
            .scale(scale)
            .size(CONTROL_SIZE)
            .clip(CircleShape)
            .background(if (enabled) ElectricLime else ElectricLime.copy(alpha = 0.3f))
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onClick()
            }
            .semantics { contentDescription = "Save boomerang" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = if (enabled) LimeInk else Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(26.dp),
        )
    }
}

/**
 * Decode one representative frame (the trim midpoint) from [file] for the filter chips. Best-effort:
 * returns `null` on a decode failure (the chips then show their glass placeholder). MUST run off the
 * main thread (ANDROID_STANDARDS §9) — callers wrap it in `Dispatchers.IO`.
 */
private fun extractRepresentativeFrame(file: File, trimStartMs: Long, trimEndMs: Long): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val midUs = ((trimStartMs + trimEndMs) / 2L) * 1000L
        retriever.getFrameAtTime(midUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (e: IllegalArgumentException) {
        null // unreadable / unsupported source path
    } catch (e: IllegalStateException) {
        null // retriever not configured (setDataSource failed)
    } finally {
        retriever.release()
    }
}
