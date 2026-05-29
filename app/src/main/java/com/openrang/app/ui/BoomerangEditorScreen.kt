package com.openrang.app.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
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
import com.openrang.app.media.BoomerangMode
import com.openrang.app.media.boomerangOutputDurationMs
import com.openrang.app.media.needsReverse
import java.io.File
import java.util.Locale

/** Hit target ≥ 48 dp (Material / ANDROID_STANDARDS §7 minimum) for the top-bar buttons and chips. */
private val CONTROL_SIZE = 56.dp

/** Reserved bottom tab-bar height (slice 03 shows one icon; slices 04/05 fill it without reflow). */
private val TAB_BAR_HEIGHT = 56.dp

/** Max width of the direction-chip row so it stays centered (not edge-spread) on ≥ 600 dp displays. */
private val CONTENT_MAX_WIDTH = 520.dp

/**
 * Approximate one-frame seam offset for the *preview* second clip (~30 fps). The exported render
 * computes the source's exact frame duration; the preview only needs to hide the duplicate frame, so
 * a constant is fine here (and avoids a `MediaExtractor` read on the UI thread).
 */
private const val PREVIEW_SEAM_MS = 33L

/** The four direction chips, in display order, with the reference-Boomerang glyph + short label. */
private data class DirectionChip(
    val mode: BoomerangMode,
    val glyph: String,
    val label: String,
    val accessibilityLabel: String,
)

private val DIRECTION_CHIPS = listOf(
    DirectionChip(BoomerangMode.FORWARD, "▶▶", "Fwd", "Forward"),
    DirectionChip(BoomerangMode.REVERSE, "◀◀", "Rev", "Reverse"),
    DirectionChip(BoomerangMode.FORWARD_THEN_REVERSE, "▶◀", "F→R", "Forward then reverse"),
    DirectionChip(BoomerangMode.REVERSE_THEN_FORWARD, "◀▶", "R→F", "Reverse then forward"),
)

/**
 * Tabbed boomerang editor (slice 03). Opens from the Trim screen's NEXT with the trimmed clip already
 * boomeranged (`FORWARD_THEN_REVERSE` default) looping in the preview. This slice exposes a single
 * tab — **Direction** (four chips) — and a Save checkmark; Speed and Reps arrive in slices 04/05, so
 * the bottom tab bar reserves its height with one centered icon to keep the layout stable.
 *
 * Speed (2×) and reps (1) are hard-wired this slice; [OpenRangViewModel.saveBoomerang] renders with
 * the selected direction. Flow collection uses [collectAsStateWithLifecycle] (Lesson 002); colors are
 * the shared `CameraScreen.kt` tokens, all 8-hex literals (Lesson 001).
 */
@Composable
fun BoomerangEditorScreen(
    viewModel: OpenRangViewModel,
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
        reversedFile = tab.reversedFile,
        isReversedFileLoading = tab.isReversedFileLoading,
        onSelectMode = viewModel::updateMode,
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
 * disabled.
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
) {
    val context = LocalContext.current

    var showDiscardDialog by remember { mutableStateOf(false) }
    // Single back path for both the gesture and the arrow: confirm only when the user changed the
    // direction off the default (work worth guarding); otherwise return silently to Trim (Lesson 015 —
    // gate, don't always-intercept).
    val handleBack = {
        if (mode != BoomerangMode.FORWARD_THEN_REVERSE) showDiscardDialog = true else onBack()
    }
    BackHandler { handleBack() }

    // The reversed clip is still missing for a mode that needs it → preview can't show the real
    // direction yet; cover with the shimmer and block Save until it lands.
    val awaitingReverse = mode.needsReverse && (reversedFile == null || isReversedFileLoading)
    val saveEnabled = !awaitingReverse

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL // loop the concatenated boomerang cycle
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    // Rebind the playlist whenever the direction, the reversed file, or the trim changes. setMediaItems
    // replaces the whole playlist (no in-place re-clip of a same-URI item, which ExoPlayer dedupes —
    // slice-02 HANDOFF), then prepare() restarts playback of the new cycle.
    LaunchedEffect(mode, reversedFile, trimStartMs, trimEndMs) {
        val items = previewPlaylist(sourceFile, trimStartMs, trimEndMs, mode, reversedFile)
        if (items.isEmpty()) {
            exoPlayer.clearMediaItems()
        } else {
            exoPlayer.setMediaItems(items)
            exoPlayer.prepare()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
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
                // rather than a flat colour wash — matches the app's DeepCharcoal/GlassWhite surfaces.
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
                            .clip(RoundedCornerShape(20.dp))
                            .background(DeepCharcoal)
                            .border(1.dp, GlassWhiteBorder, RoundedCornerShape(20.dp))
                            .padding(horizontal = 28.dp, vertical = 22.dp),
                    ) {
                        CircularProgressIndicator(
                            color = NeonPurple,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(34.dp),
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "Loopifying…",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.5.sp,
                        )
                    }
                }
            }

            // Hidden under the shimmer so the duration chip doesn't sit on top of the "Loopifying…"
            // scrim; it returns the moment the reversed clip is ready and the preview is live.
            if (!awaitingReverse) {
                Text(
                    text = String.format(
                        Locale.US,
                        "%.1fs",
                        boomerangOutputDurationMs(
                            mode = mode,
                            trimStartMs = trimStartMs,
                            trimEndMs = trimEndMs,
                            speed = OpenRangViewModel.DEFAULT_SPEED,
                            repetitions = OpenRangViewModel.DEFAULT_REPS,
                        ) / 1000f,
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(DeepCharcoal)
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .testTag("editor_duration_label"),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // ── Direction tab content panel ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, DeepCharcoal)))
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Select video direction",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
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

        // ── Tab bar: one Direction icon, height reserved for slices 04/05 ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(DeepCharcoal)
                .navigationBarsPadding()
                .height(TAB_BAR_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "≫",
                color = NeonPurple,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { contentDescription = "Direction tab" }.testTag("tab_direction"),
            )
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard changes?") },
            text = { Text("Your direction choice will be lost and you'll return to trimming.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onBack()
                    },
                    modifier = Modifier.testTag("discard_changes_confirm"),
                ) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("Keep editing") }
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(CONTROL_SIZE)
            .clip(CircleShape)
            .background(GlassWhite)
            .border(1.dp, GlassWhiteBorder, CircleShape)
            .clickable(role = Role.Button) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/** Save checkmark: a filled [NeonPurple] circle; dimmed + non-clickable while the reverse isn't ready. */
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
            .background(if (enabled) NeonPurple else NeonPurple.copy(alpha = 0.3f))
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
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(26.dp),
        )
    }
}

/**
 * A single direction chip: a glyph tile with a caption below. Selected → `NeonCoral → NeonPurple`
 * gradient with a white glyph; unselected → glassmorphic outline with a [NeonPurple] glyph.
 */
@Composable
private fun DirectionChipButton(
    chip: DirectionChip,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp),
    ) {
        Box(
            modifier = Modifier
                .size(CONTROL_SIZE)
                .clip(RoundedCornerShape(14.dp))
                .then(
                    if (selected) {
                        Modifier.background(Brush.horizontalGradient(listOf(NeonCoral, NeonPurple)))
                    } else {
                        Modifier
                            .background(GlassWhite)
                            .border(1.dp, GlassWhiteBorder, RoundedCornerShape(14.dp))
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
                color = if (selected) Color.White else NeonPurple,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = chip.label,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
    }
}
