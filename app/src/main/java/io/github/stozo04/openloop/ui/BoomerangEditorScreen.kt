package io.github.stozo04.openloop.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.AutoAwesome
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import io.github.stozo04.openloop.ui.theme.ElectricLime
import io.github.stozo04.openloop.ui.theme.LimeInk
import io.github.stozo04.openloop.ui.theme.OpenLoopBackground
import io.github.stozo04.openloop.ui.theme.OverlayScrim
import io.github.stozo04.openloop.ui.theme.OverlayWhite
import io.github.stozo04.openloop.ui.theme.OverlayWhiteBorder
import io.github.stozo04.openloop.ui.theme.TimerTextStyle
import io.github.stozo04.openloop.media.BoomerangMode
import io.github.stozo04.openloop.media.VideoFilter
import io.github.stozo04.openloop.media.boomerangOutputDurationMs
import io.github.stozo04.openloop.media.needsReverse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Hit target ≥ 48 dp (Material / ANDROID_STANDARDS §7 minimum) for the top-bar buttons and chips. */
private val CONTROL_SIZE = 56.dp

/** Reserved bottom tab-bar height (steady from slice 03; slice 05 enables the Reps stub without reflow). */
private val TAB_BAR_HEIGHT = 56.dp

/** Tab-bar icon pill size and the gap between pills ("icons … spaced 32 dp apart", slice-04 doc). */
private val TAB_PILL_SIZE = 44.dp
private val TAB_PILL_SPACING = 32.dp

/** Max width of the tab-content row so chips / slider stay centered (not edge-spread) on ≥ 600 dp displays. */
private val CONTENT_MAX_WIDTH = 520.dp

/**
 * Fixed height of the tab-content panel so switching Direction ↔ Speed (different intrinsic heights)
 * never shifts the tab bar below it. Sized to the taller content (the direction-chip column).
 */
private val PANEL_CONTENT_HEIGHT = 150.dp

/** Speed slider geometry. Thumb ≈ 24 dp diameter (doc); 4 dp track; 48 dp touch target (§7). */
private val SLIDER_THUMB_RADIUS = 12.dp
private val SLIDER_TRACK_HEIGHT = 4.dp
private val SLIDER_TOUCH_HEIGHT = 48.dp

/** Debounce before pushing a new speed to the player — coalesces a drag's stream into one apply. */
private const val SPEED_DEBOUNCE_MS = 50L

/** Speeds that get a haptic detent so the user can find "normal" (1.0×) and the default (2.0×) by feel. */
private val SPEED_DETENTS = listOf(1.0f, 2.0f)

/**
 * Approximate one-frame seam offset for the *preview* second clip (~30 fps). The exported render
 * computes the source's exact frame duration; the preview only needs to hide the duplicate frame, so
 * a constant is fine here (and avoids a `MediaExtractor` read on the UI thread).
 */
private const val PREVIEW_SEAM_MS = 33L

/**
 * The four direction chips, in display order, with the reference-Boomerang glyph. No visible caption —
 * the glyph carries the meaning; [accessibilityLabel] is the spoken label for TalkBack.
 */
private data class DirectionChip(
    val mode: BoomerangMode,
    val glyph: String,
    val accessibilityLabel: String,
)

private val DIRECTION_CHIPS = listOf(
    DirectionChip(BoomerangMode.FORWARD, "▶▶", "Forward"),
    DirectionChip(BoomerangMode.REVERSE, "◀◀", "Reverse"),
    DirectionChip(BoomerangMode.FORWARD_THEN_REVERSE, "▶◀", "Forward then reverse"),
    DirectionChip(BoomerangMode.REVERSE_THEN_FORWARD, "◀▶", "Reverse then forward"),
)

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
        isReversedFileLoading = tab.isReversedFileLoading,
        reverseFailed = tab.reverseFailed,
        onRetryReverse = viewModel::ensureReversedSegment,
        onSelectMode = viewModel::updateMode,
        onSpeedChange = viewModel::updateSpeed,
        onFilterChange = viewModel::updateFilter,
        onSwitchTab = viewModel::switchTab,
        onSave = viewModel::saveBoomerang,
        onBack = viewModel::backToTrim,
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
    isReversedFileLoading: Boolean,
    onSelectMode: (BoomerangMode) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    speed: Float = OpenLoopViewModel.DEFAULT_SPEED,
    filter: VideoFilter = VideoFilter.ORIGINAL,
    activeTab: EditorTab = EditorTab.DIRECTION,
    reverseFailed: Boolean = false,
    onRetryReverse: () -> Unit = {},
    onSpeedChange: (Float) -> Unit = {},
    onFilterChange: (VideoFilter) -> Unit = {},
    onSwitchTab: (EditorTab) -> Unit = {},
) {
    val context = LocalContext.current

    // One representative frame from the trim, decoded off the main thread and cached for the Looks
    // tab's filter thumbnails. Extracted at the content level (not inside the tab panel) so it survives
    // tab switches and is ready the instant the user opens Looks; null while loading / on failure.
    val thumbnailFrame by produceState<Bitmap?>(null, sourceFile, trimStartMs, trimEndMs) {
        value = withContext(Dispatchers.IO) { extractRepresentativeFrame(sourceFile, trimStartMs, trimEndMs) }
    }

    var showDiscardDialog by remember { mutableStateOf(false) }
    // Single back path for both the gesture and the arrow: confirm only when the user changed *any*
    // selection off its default (direction, speed, or look — all worth guarding); otherwise return
    // silently to Trim (Lesson 015 — gate, don't always-intercept).
    val hasEdits = mode != BoomerangMode.FORWARD_THEN_REVERSE ||
        speed != OpenLoopViewModel.DEFAULT_SPEED ||
        filter != VideoFilter.ORIGINAL
    val handleBack = {
        if (hasEdits) showDiscardDialog = true else onBack()
    }
    BackHandler { handleBack() }

    // The reversed clip is still missing for a mode that needs it → preview can't show the real
    // direction yet; cover with the shimmer and block Save until it lands. Once generation has FAILED
    // (reverseFailed), stop the shimmer and show a retry card instead — a failed reverse must never
    // leave "Loopifying…" on screen forever (the bug HDR imports exposed).
    val awaitingReverse = mode.needsReverse && !reverseFailed && (reversedFile == null || isReversedFileLoading)
    val reverseUnavailable = mode.needsReverse && reverseFailed && reversedFile == null
    val saveEnabled = !awaitingReverse && !reverseUnavailable

    val exoPlayer = remember {
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
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    // Rebind the playlist whenever the direction, the reversed file, or the trim changes. setMediaItems
    // replaces the whole playlist (no in-place re-clip of a same-URI item, which ExoPlayer dedupes —
    // slice-02 HANDOFF), then prepare() restarts playback of the new cycle. Both the speed
    // (PlaybackParameters) and the look (setVideoEffects) are player-wide settings, not per-MediaItem,
    // so they survive this rebind — we don't re-apply either here. The LaunchedEffect(filter) below
    // owns applying the look.
    LaunchedEffect(mode, reversedFile, trimStartMs, trimEndMs) {
        val items = previewPlaylist(sourceFile, trimStartMs, trimEndMs, mode, reversedFile)
        if (items.isEmpty()) {
            exoPlayer.clearMediaItems()
        } else {
            exoPlayer.setMediaItems(items)
            exoPlayer.prepare()
        }
    }

    // Apply the color look live (the Looks tab's whole point). setVideoEffects is ExoPlayer's preview
    // path for effects (same Effect objects as the render), so tapping a look re-tints the running
    // preview without a re-render. Independent of speed (a player setting) — they compose.
    LaunchedEffect(filter) {
        exoPlayer.setVideoEffects(filter.toMediaEffects())
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
        // ── Top bar: back (left) + save checkmark (right) ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            CircleIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to trim",
                onClick = handleBack,
                modifier = Modifier.align(Alignment.CenterStart).testTag("editor_back"),
            )
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
                        player = exoPlayer
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                modifier = Modifier.fillMaxSize().testTag("editor_preview"),
            )

            if (awaitingReverse) {
                // Soft dark scrim (the dimmed preview frame still shows through) + a glassmorphic card,
                // rather than a flat color wash — matches the app's OverlayScrim/OverlayWhite surfaces.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .testTag("reverse_loading"),
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
                        CircularProgressIndicator(
                            color = ElectricLime,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(34.dp),
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "Loopifying…",
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
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

        // ── Tab content panel: cross-fades between Direction and Speed (fixed height → tab bar stable) ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, OverlayScrim))),
        ) {
            AnimatedContent(
                targetState = activeTab,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                label = "editor_tab_content",
                modifier = Modifier.fillMaxWidth().height(PANEL_CONTENT_HEIGHT),
            ) { tab ->
                when (tab) {
                    EditorTab.DIRECTION -> DirectionTabContent(mode = mode, onSelectMode = onSelectMode)
                    EditorTab.SPEED -> SpeedTabContent(speed = speed, onSpeedChange = onSpeedChange)
                    EditorTab.LOOKS -> LooksTabContent(
                        filter = filter,
                        thumbnailFrame = thumbnailFrame,
                        onFilterChange = onFilterChange,
                    )
                }
            }
        }

        // ── Tab bar: Direction + Speed + Looks, all interactive (slice 05 lit up the third slot) ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(OverlayScrim)
                .navigationBarsPadding()
                .height(TAB_BAR_HEIGHT),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabBarItem(
                icon = Icons.Filled.FastForward,
                contentDescription = "Direction tab",
                active = activeTab == EditorTab.DIRECTION,
                enabled = true,
                onClick = { onSwitchTab(EditorTab.DIRECTION) },
                modifier = Modifier.testTag("tab_direction"),
            )
            Spacer(Modifier.width(TAB_PILL_SPACING))
            TabBarItem(
                icon = Icons.Filled.Bolt,
                contentDescription = "Speed tab",
                active = activeTab == EditorTab.SPEED,
                enabled = true,
                onClick = { onSwitchTab(EditorTab.SPEED) },
                modifier = Modifier.testTag("tab_speed"),
            )
            Spacer(Modifier.width(TAB_PILL_SPACING))
            TabBarItem(
                icon = Icons.Filled.AutoAwesome,
                contentDescription = "Looks tab",
                active = activeTab == EditorTab.LOOKS,
                enabled = true,
                onClick = { onSwitchTab(EditorTab.LOOKS) },
                modifier = Modifier.testTag("tab_looks"),
            )
        }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            // Back press / scrim tap == "Keep editing" (the safe choice): just close the dialog.
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits will be lost and you'll return to trimming.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onBack()
                    },
                    modifier = Modifier.testTag("discard_changes_confirm"),
                ) { Text("Discard") }
            },
            dismissButton = {
                // "Keep editing" must dismiss the dialog so the user stays in the editor — previously a
                // no-op, which trapped them in the dialog with no way to continue.
                TextButton(onClick = { showDiscardDialog = false }) { Text("Keep editing") }
            },
        )
    }
}

/** Direction tab content: the four boomerang-direction chips with a caption. */
@Composable
private fun DirectionTabContent(
    mode: BoomerangMode,
    onSelectMode: (BoomerangMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Select video direction",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = CONTENT_MAX_WIDTH),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            DIRECTION_CHIPS.forEach { chip ->
                DirectionChipButton(
                    chip = chip,
                    selected = chip.mode == mode,
                    onClick = { onSelectMode(chip.mode) },
                )
            }
        }
    }
}

/** Speed tab content: a caption over the label-free comet [SpeedSlider]. */
@Composable
private fun SpeedTabContent(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Slow down or speed up the video",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        SpeedSlider(
            speed = speed,
            onSpeedChange = onSpeedChange,
            modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH),
        )
    }
}

/**
 * Custom comet-style speed slider (drawn, not a Material [androidx.compose.material3.Slider]) so the
 * thumb reads as a glowing comet head on a [ElectricLime] trail per the reference screenshot — no value
 * label, no end labels. The numeric speed is exposed to TalkBack via [stateDescription] (an unlabeled
 * continuous slider must announce its value — ANDROID_STANDARDS §7), invisible to sighted users.
 *
 * Drag or tap to set the value; a [HapticFeedbackType.SegmentTick] fires when the value crosses a
 * [SPEED_DETENTS] threshold (1.0× / 2.0×) so the user can return to those speeds by feel.
 */
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

    var widthPx by remember { mutableFloatStateOf(0f) }
    // The gesture closures below are keyed only on widthPx (re-keying on speed would cancel an active
    // drag), so they'd capture a stale `speed`. rememberUpdatedState keeps the detent comparison honest.
    val latestSpeed by rememberUpdatedState(speed)

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
                contentDescription = "Playback speed"
                stateDescription = formatSpeedLabel(speed)
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

            // Inactive track (right of the thumb): white @30%.
            if (thumbX < size.width - thumbRadiusPx) {
                drawLine(
                    color = OverlayWhiteBorder,
                    start = Offset(thumbX, centerY),
                    end = Offset(size.width - thumbRadiusPx, centerY),
                    strokeWidth = trackStrokePx,
                    cap = StrokeCap.Round,
                )
            }
            // Comet trail (left of the thumb): a ElectricLime gradient brightening toward the head.
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(ElectricLime.copy(alpha = 0.2f), ElectricLime),
                    startX = thumbRadiusPx,
                    endX = thumbX,
                ),
                start = Offset(thumbRadiusPx, centerY),
                end = Offset(thumbX, centerY),
                strokeWidth = trackStrokePx,
                cap = StrokeCap.Round,
            )
            // Comet head: soft glow halo, solid ElectricLime body, bright white core.
            drawCircle(ElectricLime.copy(alpha = 0.3f), radius = thumbRadiusPx * 1.5f, center = Offset(thumbX, centerY))
            drawCircle(ElectricLime, radius = thumbRadiusPx, center = Offset(thumbX, centerY))
            drawCircle(Color.White, radius = thumbRadiusPx * 0.42f, center = Offset(thumbX, centerY))
        }
    }
}

/** Build the looping preview playlist for [mode]; empty when a reversed clip is needed but absent. */
private fun previewPlaylist(
    sourceFile: File,
    trimStartMs: Long,
    trimEndMs: Long,
    mode: BoomerangMode,
    reversedFile: File?,
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

    // Seam drop mirrors the render (boomerangSequence): the SECOND clip of a two-part mode drops its
    // duplicated leading frame; lone clips don't.
    return when (mode) {
        BoomerangMode.FORWARD -> listOf(trimmed(0L))
        BoomerangMode.REVERSE -> listOfNotNull(reversed(0L))
        BoomerangMode.FORWARD_THEN_REVERSE -> listOfNotNull(trimmed(0L), reversed(PREVIEW_SEAM_MS))
        BoomerangMode.REVERSE_THEN_FORWARD -> {
            val head = reversed(0L) ?: return emptyList()
            listOf(head, trimmed(PREVIEW_SEAM_MS))
        }
    }
}

@Composable
private fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(CONTROL_SIZE)
            .clip(CircleShape)
            .background(OverlayWhite)
            .border(1.dp, OverlayWhiteBorder, CircleShape)
            .clickable(role = Role.Button) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/** Save checkmark: a filled [ElectricLime] circle; dimmed + non-clickable while the reverse isn't ready. */
@Composable
private fun SaveCheckmark(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(CONTROL_SIZE)
            .clip(CircleShape)
            .background(if (enabled) ElectricLime else ElectricLime.copy(alpha = 0.3f))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
            ) { onClick() }
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
 * A single bottom-tab-bar slot. Active → darker pill + [ElectricLime] icon; inactive interactive →
 * [OverlayWhite] pill + white icon; disabled stub → no pill, dimmed icon, non-clickable + `disabled()`
 * semantics (the Reps tab until slice 05). Carries `Role.Tab` + `selected` for selection a11y/tests.
 */
@Composable
private fun TabBarItem(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconTint = when {
        !enabled -> Color.White.copy(alpha = 0.4f)
        active -> ElectricLime
        else -> Color.White
    }
    Box(
        modifier = modifier
            .size(TAB_PILL_SIZE)
            .clip(CircleShape)
            .then(
                when {
                    !enabled -> Modifier
                    active -> Modifier.background(Color.Black.copy(alpha = 0.35f))
                    else -> Modifier.background(OverlayWhite)
                },
            )
            .then(if (enabled) Modifier.clickable(role = Role.Tab) { onClick() } else Modifier)
            .semantics {
                this.contentDescription = contentDescription
                this.selected = active
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(26.dp))
    }
}

/**
 * A single direction chip: a glyph tile (no caption — the glyph speaks for itself, and the spoken
 * label rides in `contentDescription`). Selected → flat [ElectricLime] fill with a dark glyph;
 * unselected → glassmorphic outline with a white glyph.
 */
@Composable
private fun DirectionChipButton(
    chip: DirectionChip,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(CONTROL_SIZE)
            .clip(MaterialTheme.shapes.small)
            .then(
                if (selected) {
                    Modifier.background(ElectricLime)
                } else {
                    Modifier
                        .background(OverlayWhite)
                        .border(1.dp, OverlayWhiteBorder, MaterialTheme.shapes.small)
                },
            )
            .clickable(role = Role.Button) { onClick() }
            .semantics {
                contentDescription = chip.accessibilityLabel
                this.selected = selected
            }
            .testTag("direction_chip_${chip.mode.name}"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = chip.glyph,
            color = if (selected) LimeInk else Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Speech label for the slider's [stateDescription], e.g. 1.75f → "1.75 times speed" (trailing zeros trimmed). */
private fun formatSpeedLabel(speed: Float): String {
    val number = String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
    return "$number times speed"
}

/** Looks tab content: a caption over a horizontally-scrolling strip of live-preview filter chips. */
@Composable
private fun LooksTabContent(
    filter: VideoFilter,
    thumbnailFrame: Bitmap?,
    onFilterChange: (VideoFilter) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Choose a look",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            VideoFilter.entries.forEach { look ->
                LookChip(
                    look = look,
                    thumbnailFrame = thumbnailFrame,
                    selected = look == filter,
                    onClick = {
                        if (look != filter) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onFilterChange(look)
                    },
                )
            }
        }
    }
}

/**
 * One filter chip: the trim's representative frame ([thumbnailFrame]) rendered in [look] via a
 * [ColorFilter], with a label below and a [ElectricLime] ring when selected. While the frame is still
 * decoding (or failed), the chip shows its glass tile so the strip never looks broken.
 */
@Composable
private fun LookChip(
    look: VideoFilter,
    thumbnailFrame: Bitmap?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // Cache the per-frame ImageBitmap wrapper and the look's ColorMatrix/ColorFilter so they aren't
    // re-allocated on every recomposition (Compose best practice: remember expensive calculations).
    val imageBitmap = remember(thumbnailFrame) { thumbnailFrame?.asImageBitmap() }
    val colorFilter = remember(look) { look.thumbnailColorFilter() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(72.dp),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(MaterialTheme.shapes.small)
                .background(OverlayWhite)
                .then(
                    if (selected) {
                        Modifier.border(2.dp, ElectricLime, MaterialTheme.shapes.small)
                    } else {
                        Modifier.border(1.dp, OverlayWhiteBorder, MaterialTheme.shapes.small)
                    },
                )
                .clickable(role = Role.Button) { onClick() }
                .semantics {
                    contentDescription = look.label
                    this.selected = selected
                }
                .testTag("look_chip_${look.name}"),
            contentAlignment = Alignment.Center,
        ) {
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = colorFilter,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = look.label,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Compose [ColorFilter] for [VideoFilter]'s chip thumbnail, derived from the SAME per-look params as
 * [VideoFilter.toMediaEffects] so the chip matches the live preview / export (no "preview lies about
 * export"). An all-default look ([VideoFilter.ORIGINAL]) → `null` (draw the frame untouched).
 */
private fun VideoFilter.thumbnailColorFilter(): ColorFilter? {
    val matrix = when {
        grayscale -> ColorMatrix().apply { setToSaturation(0f) }
        saturation != 0f -> ColorMatrix().apply { setToSaturation(1f + saturation / 100f) }
        redScale != 1f || blueScale != 1f -> ColorMatrix(
            floatArrayOf(
                redScale, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, blueScale, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        else -> return null
    }
    return ColorFilter.colorMatrix(matrix)
}

/**
 * Decode one representative frame (the trim midpoint) from [file] for the Looks chips. Best-effort:
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
