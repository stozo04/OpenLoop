package io.github.stozo04.openloop.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import io.github.stozo04.openloop.R
import io.github.stozo04.openloop.ui.components.EditorBottomToolbar
import io.github.stozo04.openloop.ui.components.EditorLoadingOverlay
import io.github.stozo04.openloop.ui.components.FilterTabPanel
import io.github.stozo04.openloop.ui.components.LoopTabPanel
import io.github.stozo04.openloop.ui.components.SpeedTabPanel
import io.github.stozo04.openloop.ui.components.seamFractionsFor
import io.github.stozo04.openloop.ui.components.speedDiffersMeaningfully
import io.github.stozo04.openloop.ui.components.toToolbarSlot
import io.github.stozo04.openloop.ui.components.PrimaryButtonPressedScale
import io.github.stozo04.openloop.ui.theme.ElectricLime
import io.github.stozo04.openloop.ui.theme.LimeInk
import io.github.stozo04.openloop.ui.theme.OpenLoopBackground
import io.github.stozo04.openloop.ui.theme.OverlayScrim
import io.github.stozo04.openloop.ui.theme.OverlayWhiteBorder
import io.github.stozo04.openloop.ui.theme.TimerTextStyle
import io.github.stozo04.openloop.diagnostics.ReverseCrashlytics
import io.github.stozo04.openloop.diagnostics.shareDebugReport
import io.github.stozo04.openloop.media.BoomerangMode
import io.github.stozo04.openloop.media.ClipDirection
import io.github.stozo04.openloop.media.SpeedCurve
import io.github.stozo04.openloop.media.VideoFilter
import io.github.stozo04.openloop.media.boomerangSequence
import io.github.stozo04.openloop.media.loopClipSpans
import io.github.stozo04.openloop.media.loopOutputDurationMs
import io.github.stozo04.openloop.media.needsReverse
import io.github.stozo04.openloop.media.sourceSeamDurationMs
import io.github.stozo04.openloop.media.totalUs
import io.github.stozo04.openloop.media.videoDurationMsOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

/** Hit target ≥ 48 dp (Material / ANDROID_STANDARDS §7 minimum) for the top-bar buttons and chips. */
private val CONTROL_SIZE = 56.dp

/**
 * Tab-panel heights: fixed per tab so the bottom toolbar stays put; Speed is tallest.
 *
 * Curve mode is taller again — graph + readout + two rows of actions — so the panel is sized on the
 * mode as well as the tab. A single height for both would either clip the curve editor or leave the
 * slider floating in dead space.
 */
private fun editorPanelHeight(tab: EditorTab, curveMode: Boolean) = when (tab) {
    // Constant was 240.dp before the mode toggle existed; the toggle (40) plus its spacer (18) is
    // 58.dp of new chrome above the slider, and without it the "Current speed" pill is clipped off
    // the bottom of the card.
    EditorTab.SPEED -> if (curveMode) 376.dp else 298.dp
    EditorTab.LOOKS -> 188.dp
    EditorTab.DIRECTION -> 168.dp
}

/** Debounce before pushing a new speed to the player — coalesces a drag's stream into one apply. */
private val SPEED_DEBOUNCE = 50.milliseconds

/** How often Curve mode samples the player position to re-aim `setPlaybackSpeed` and the playhead. */
private val PLAYHEAD_POLL = 50.milliseconds

/**
 * Minimum speed delta before Curve mode re-applies `setPlaybackSpeed`.
 *
 * Hysteresis, not thrift: a steep ramp would otherwise push a new playback speed 20×/s, and each call
 * resets ExoPlayer's `PlaybackParameters`. On a flat stretch of the curve this keeps the player
 * completely untouched.
 */
private const val PLAYHEAD_SPEED_EPSILON = 0.02f

/**
 * How far a dragged keyframe must travel before the preview re-seeks to it. A decoder seek per
 * pointer event would stutter; ~1.5% of the loop is roughly a finger-width of graph.
 */
private const val SCRUB_EPSILON = 0.015f

/** Debounce playlist rebinding — coalesces trim/mode/seam changes (editor-memory-oom WS-2). */
private val PLAYLIST_DEBOUNCE = EditorPlaylistBind.PLAYLIST_DEBOUNCE

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
    val hasSeenCurveIntro by viewModel.hasSeenSpeedCurveIntro.collectAsStateWithLifecycle()
    val editor = trim ?: return // No active session (transient state); router keeps us here.

    BoomerangEditorContent(
        sourceFile = editor.sourceFile,
        trimStartMs = editor.trimStartMs,
        trimEndMs = editor.trimEndMs,
        mode = tab.mode,
        speed = tab.speed,
        curve = tab.curve,
        hasSeenCurveIntro = hasSeenCurveIntro,
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
        onEnterCurveMode = viewModel::enterCurveMode,
        onCurveChange = viewModel::updateCurve,
        onFlattenCurve = viewModel::flattenCurveToConstant,
        onUseCurrentAsConstant = viewModel::setConstantSpeedFromCurve,
        onCurveIntroSeen = viewModel::markSpeedCurveIntroSeen,
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
    // Required parameters first, then `modifier` as the FIRST optional parameter (Compose API
    // guideline, lint ModifierParameter — PR #58 review). Every call site uses named arguments,
    // so this reorder is source-compatible.
    sourceFile: File,
    trimStartMs: Long,
    trimEndMs: Long,
    mode: BoomerangMode,
    reversedFile: File?,
    onSelectMode: (BoomerangMode) -> Unit,
    onSave: () -> Unit,
    onGoToTrim: () -> Unit,
    modifier: Modifier = Modifier,
    previewLoading: EditorLoadingKind? = null,
    sessionOverlayLoading: EditorLoadingKind? = null,
    speed: Float = OpenLoopViewModel.DEFAULT_SPEED,
    curve: SpeedCurve? = null,
    hasSeenCurveIntro: Boolean = true,
    filter: VideoFilter = VideoFilter.ORIGINAL,
    activeTab: EditorTab = EditorTab.DIRECTION,
    reverseFailed: Boolean = false,
    reverseSupportReport: String? = null,
    effectsPreviewEnabled: Boolean = true,
    onRetryReverse: () -> Unit = {},
    onSpeedChange: (Float) -> Unit = {},
    onEnterCurveMode: () -> Unit = {},
    onCurveChange: (SpeedCurve) -> Unit = {},
    onFlattenCurve: () -> Unit = {},
    onUseCurrentAsConstant: (Float) -> Unit = {},
    onCurveIntroSeen: () -> Unit = {},
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
    // Monotonic clock for the editor_duration_sec breadcrumb (PR #58 review REC): wall-clock
    // (System.currentTimeMillis) jumps on NTP corrections / manual time changes, which would skew
    // the exact telemetry the 30-day Crashlytics watch reads. elapsedRealtime never goes backward.
    val editorEnteredAtElapsedMs = remember { SystemClock.elapsedRealtime() }

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
                ((SystemClock.elapsedRealtime() - editorEnteredAtElapsedMs) / 1_000L).coerceAtLeast(0L)
            ReverseCrashlytics.logEditorDispose(playlistRebindCount, durationSec)
        }
    }

    // While reverse preview is generating, do not prepare ExoPlayer — Samsung devices exhaust or
    // invalidate extra MediaCodec instances when ExoPlayer holds c2.exynos.h264.decoder and
    // VideoReverser pass 1 opens another decoder/encoder (IllegalStateException: Released state).
    val reversePreviewLoading = effectivePreviewLoading.isReversePreviewLoading()

    // Release preview decoders before pass 1: stop() alone keeps the slot (editor-codec-churn doc).
    // Epoch bump matches the effects-teardown path — DisposableEffect release() on the old player.
    LaunchedEffect(reversePreviewLoading) {
        if (!reversePreviewLoading) return@LaunchedEffect
        if (!EditorPlaylistBind.requiresPlayerEpochBumpForReversePreview()) return@LaunchedEffect
        // Skip the bump when the player holds no media — i.e. the first reverse on editor entry,
        // where the playlist is still held (shouldHoldPlaylist) so no preview decoder exists to free.
        // Recreating an empty player there is pure waste (editor-codec-churn finding 3). On later
        // reverses the prior preview is still bound (mediaItemCount > 0), so the release runs.
        if (exoPlayer.mediaItemCount == 0) return@LaunchedEffect
        playerHasAppliedEffects = false
        playerEpoch++
    }

    // Rebind the playlist whenever the direction, the reversed file, or the trim changes. setMediaItems
    // replaces the whole playlist (no in-place re-clip of a same-URI item, which ExoPlayer dedupes —
    // slice-02 HANDOFF), then prepare() restarts playback of the new cycle. Speed (PlaybackParameters)
    // survives this rebind. Looks must be primed via setVideoEffects *before* prepare when active —
    // Media3 only builds the effects VideoSink on first renderer enable if effects were set first.
    // exoPlayer is a key so a recreated (epoch-bumped) instance gets the playlist rebound — without
    // it the fresh player would sit empty and the preview would go black.
    LaunchedEffect(exoPlayer, mode, reversedFile, trimStartMs, trimEndMs, seamMs, reversePreviewLoading) {
        if (EditorPlaylistBind.shouldHoldPlaylist(reversePreviewLoading)) {
            return@LaunchedEffect
        }
        delay(PLAYLIST_DEBOUNCE)
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        val items = previewPlaylist(sourceFile, trimStartMs, trimEndMs, mode, reversedFile, seamMs)
        if (EditorPlaylistBind.shouldClearPlaylist(items.isEmpty())) {
            return@LaunchedEffect
        }
        if (shouldApplyVideoEffectsPreview(effectsPreviewEnabled, filter)) {
            exoPlayer.setVideoEffects(filter.toMediaEffects())
            playerHasAppliedEffects = true
        }
        exoPlayer.setMediaItems(items)
        // playWhenReady = true (set on the builder), so prepare() starts playback — no explicit play().
        exoPlayer.prepare()
        playlistRebindCount++
    }

    // Apply the color look live (the Looks tab's whole point). setVideoEffects is ExoPlayer's preview
    // path for effects (same Effect objects as the render), so tapping a look re-tints the running
    // preview without a re-render — but only after the effects VideoSink exists (see playlist bind /
    // first-apply recreate below). Independent of speed (a player setting) — they compose.
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
        // First look after a pipeline-less prepare: recreate so playlist bind can setVideoEffects
        // before prepare (Media3 contract). Flag true first so this effect doesn't loop on the
        // fresh instance; settle runs on the re-entry below once the new player is bound.
        if (shouldRecreatePlayerForFirstEffectsApply(
                playerHasAppliedEffects,
                effectsPreviewEnabled,
                filter,
            )
        ) {
            playerHasAppliedEffects = true
            playerEpoch++
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
    //
    // Gated on Constant mode so this and the curve poller below can never both drive
    // setPlaybackSpeed: exactly one writer owns it at any moment. `curve == null` is a key, not just a
    // guard, so leaving Curve mode re-applies the flattened constant even when the number is unchanged.
    LaunchedEffect(speed, curve == null) {
        if (curve != null) return@LaunchedEffect
        delay(SPEED_DEBOUNCE)
        exoPlayer.setPlaybackSpeed(speed)
    }

    // The loop laid out on its 1× input timeline — the domain a SpeedCurve is defined over. Built
    // from the SAME post-seam-drop clip plan the render uses (loopClipSpans), so the graph's turn
    // markers and the playhead sit exactly where the encoder puts them (Lesson 018).
    // The reversed clip's real length, measured off the main thread. The render measures it too, so
    // the graph, the playhead and the duration chip all describe the loop the encoder will build —
    // not an idealised one where the reversed half is assumed to match the trim window exactly.
    val reversedDurationMs by produceState<Long?>(null, reversedFile) {
        value = reversedFile?.let { withContext(Dispatchers.IO) { videoDurationMsOf(it) } }
    }
    val clipSpans = remember(mode, trimStartMs, trimEndMs, seamMs, reversedDurationMs) {
        loopClipSpans(
            specs = boomerangSequence(mode, OpenLoopViewModel.DEFAULT_REPS),
            windowMs = trimEndMs - trimStartMs,
            seamMs = seamMs,
            reversedMs = reversedDurationMs,
        )
    }
    val seamFractions = remember(clipSpans) { seamFractionsFor(clipSpans.map { it.durationUs }) }

    // Playhead position as a fraction of the whole loop. Held as a raw MutableFloatState and never
    // read in this composable's body — the curve panel reads it inside its Canvas draw scope, so a
    // 20 Hz tick redraws the graph without recomposing the tree that hosts the player (Lesson 016).
    val playheadFraction = remember { mutableFloatStateOf(0f) }
    // Newest curve without restarting the poller: keying the effect on `curve` itself would tear
    // down and rebuild the loop on every drag frame.
    val latestCurve by rememberUpdatedState(curve)

    // Scrub-to-handle: while a keyframe is being dragged, park the preview on the moment that
    // keyframe governs and pause there, so the frame on screen is the one the user is shaping rather
    // than wherever the loop happened to be. Release resumes the loop at the new curve — the user
    // sees the moment while editing and the result the instant they let go.
    var scrubbing by remember { mutableStateOf(false) }
    var lastScrubFraction by remember { mutableFloatStateOf(Float.NaN) }
    val onScrubToFraction: (Float?) -> Unit = handler@{ fraction ->
        val totalUs = clipSpans.totalUs()
        if (fraction == null) {
            lastScrubFraction = Float.NaN
            scrubbing = false
            exoPlayer.playWhenReady = true
            return@handler
        }
        if (totalUs <= 0L || clipSpans.isEmpty()) return@handler
        if (!lastScrubFraction.isNaN() && abs(fraction - lastScrubFraction) < SCRUB_EPSILON) return@handler
        lastScrubFraction = fraction
        scrubbing = true
        exoPlayer.playWhenReady = false

        // Resolve the global instant back to (clip index, clip-local ms) across the post-seam-drop
        // layout — the same spans the curve itself is sliced over, so the seek lands where the graph
        // says it will.
        val targetUs = (fraction.toDouble() * totalUs).toLong()
        var accumulatedUs = 0L
        var index = clipSpans.lastIndex
        for (i in clipSpans.indices) {
            if (targetUs < accumulatedUs + clipSpans[i].durationUs) {
                index = i
                break
            }
            if (i < clipSpans.lastIndex) accumulatedUs += clipSpans[i].durationUs
        }
        exoPlayer.seekTo(index, ((targetUs - accumulatedUs) / 1_000L).coerceAtLeast(0L))
        playheadFraction.floatValue = fraction
    }

    LaunchedEffect(exoPlayer, curve != null, clipSpans, reversePreviewLoading) {
        if (curve == null) return@LaunchedEffect
        // Never poll during the reverse pass — that window is already codec- and memory-starved, and
        // adding load there is exactly what Lesson 026 warns about.
        if (reversePreviewLoading) return@LaunchedEffect
        val totalUs = clipSpans.totalUs()
        if (totalUs <= 0L) return@LaunchedEffect

        var lastApplied = Float.NaN
        while (true) {
            val index = exoPlayer.currentMediaItemIndex.coerceIn(0, (clipSpans.size - 1).coerceAtLeast(0))
            // currentPosition is MEDIA time within the clipped window, i.e. already on the 1× input
            // timeline — setPlaybackSpeed changes how fast it advances against the wall clock, not
            // its scale. So it composes directly with the spans above.
            val priorUs = clipSpans.take(index).sumOf { it.durationUs }
            val positionUs = (exoPlayer.currentPosition.coerceAtLeast(0L)) * 1_000L
            val fraction = ((priorUs + positionUs).toFloat() / totalUs.toFloat()).coerceIn(0f, 1f)
            // While a handle is being dragged the scrub handler owns the playhead; letting the poller
            // also write it would fight the seek for the frames before it lands.
            if (!scrubbing) playheadFraction.floatValue = fraction

            val target = latestCurve?.speedAt(fraction)
            if (target != null &&
                (lastApplied.isNaN() || speedDiffersMeaningfully(target, lastApplied, PLAYHEAD_SPEED_EPSILON))
            ) {
                exoPlayer.setPlaybackSpeed(target)
                lastApplied = target
            }
            delay(PLAYHEAD_POLL)
        }
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
                    message = stringResource(activeOverlay.message),
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
                            text = stringResource(R.string.reverse_failed_title),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.reverse_failed_body),
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.reverse_failed_report_hint),
                            color = Color.White.copy(alpha = 0.55f),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.reverse_failed_retry),
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
                            val debugChooserTitle = stringResource(R.string.debug_report_chooser_title)
                            Text(
                                // Label unified with the snackbar actions ("Send debug report") —
                                // reverse-output-validation spec §4.
                                text = stringResource(R.string.reverse_failed_send_report),
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.small)
                                    .border(1.dp, OverlayWhiteBorder, MaterialTheme.shapes.small)
                                    .clickable {
                                        context.shareDebugReport(
                                            report = reverseSupportReport,
                                            // Subject stays English on purpose: it routes a support
                                            // mail to the maintainer, it is not app UI.
                                            subject = "OpenLoop loop debug",
                                            chooserTitle = debugChooserTitle,
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
                        // Derived from the SAME clip spans the render slices the curve over, so this
                        // number cannot promise a length the encoder will not produce. A curve's
                        // equivalent constant is its harmonic mean — the speed defined as yielding
                        // the same output length — so one formula covers both modes.
                        loopOutputDurationMs(clipSpans, curve?.flatten() ?: speed) / 1000f,
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
                .height(editorPanelHeight(activeTab, curve != null))
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
                    EditorTab.SPEED -> SpeedTabPanel(
                        speed = speed,
                        onSpeedChange = onSpeedChange,
                        curve = curve,
                        playheadFraction = { playheadFraction.floatValue },
                        seamFractions = seamFractions,
                        loopDurationMs = clipSpans.totalUs() / 1_000L,
                        hasSeenCurveIntro = hasSeenCurveIntro,
                        onEnterCurveMode = onEnterCurveMode,
                        onCurveChange = onCurveChange,
                        onFlattenCurve = onFlattenCurve,
                        onUseCurrentAsConstant = onUseCurrentAsConstant,
                        onScrubToFraction = onScrubToFraction,
                        onCurveIntroSeen = onCurveIntroSeen,
                    )
                    EditorTab.LOOKS -> FilterTabPanel(
                        filter = filter,
                        thumbnailFrame = thumbnailFrame,
                        onFilterChange = onFilterChange,
                        disabledHint = if (!effectsPreviewEnabled) {
                            // Gate is memory-pressure only (reverse failure no longer closes Looks).
                            stringResource(R.string.looks_disabled_hint)
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
            title = { Text(stringResource(R.string.discard_dialog_title)) },
            text = { Text(stringResource(R.string.discard_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteClipDialog = false
                        onDiscard()
                    },
                    modifier = Modifier.testTag("discard_confirm"),
                ) { Text(stringResource(R.string.discard_dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteClipDialog = false }) {
                    Text(stringResource(R.string.discard_dialog_keep_editing))
                }
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
    // Hoisted: stringResource needs a composable scope, and the semantics {} block below is not one.
    val saveLabel = stringResource(R.string.editor_save)
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
            .semantics { contentDescription = saveLabel },
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
        ReverseCrashlytics.reportFilterThumbnailExtractFailure(
            file, trimStartMs, trimEndMs, "illegal_argument", e,
        )
        null // unreadable / unsupported source path
    } catch (e: IllegalStateException) {
        ReverseCrashlytics.reportFilterThumbnailExtractFailure(
            file, trimStartMs, trimEndMs, "illegal_state", e,
        )
        null // retriever not configured (setDataSource failed)
    } catch (e: RuntimeException) {
        // MediaMetadataRetriever surfaces native decode/open failures as bare RuntimeExceptions
        // ("setDataSource failed: status = 0x..."), same as VideoImporter/VideoStorageRepositoryImpl.
        ReverseCrashlytics.reportFilterThumbnailExtractFailure(
            file, trimStartMs, trimEndMs, "runtime", e,
        )
        null
    } finally {
        retriever.release()
    }
}
