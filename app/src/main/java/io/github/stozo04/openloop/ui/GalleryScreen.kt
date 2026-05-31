package io.github.stozo04.openloop.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.stozo04.openloop.R
import io.github.stozo04.openloop.data.RecordedVideo
import io.github.stozo04.openloop.ui.components.BackButton
import io.github.stozo04.openloop.ui.components.PrimaryButton
import io.github.stozo04.openloop.ui.theme.Canvas
import io.github.stozo04.openloop.ui.theme.CoralRed
import io.github.stozo04.openloop.ui.theme.ElectricLime
import io.github.stozo04.openloop.ui.theme.LimeInk
import io.github.stozo04.openloop.ui.theme.OutlineVariant
import io.github.stozo04.openloop.ui.theme.OverlayScrim
import io.github.stozo04.openloop.ui.theme.OverlayWhite
import io.github.stozo04.openloop.ui.theme.OverlayWhiteBorder
import io.github.stozo04.openloop.ui.theme.SurfaceContainer
import io.github.stozo04.openloop.ui.theme.SurfaceContainerHigh

/**
 * A [Set] of selected loop ids that survives configuration change. [Set] isn't `Bundle`-able on its
 * own, so the [Saver] round-trips it through a [LongArray] (a primitive array the saved-instance
 * state can persist). Keeps the long-press selection alive across rotation (Issue #35 edge-case).
 */
private val SelectedIdsSaver: Saver<Set<Long>, LongArray> = Saver(
    save = { it.toLongArray() },
    restore = { it.toSet() },
)

/**
 * Gallery screen (Issue #35). Stateful shell: collects [OpenLoopViewModel.visibleVideos] (NOT
 * `recordedVideos`) so a pending-delete tile vanishes instantly and reappears on Undo, owns the
 * tap-to-play [LoopingVideoOverlay], and routes Back. The grid/top-bar/selection live in the
 * stateless [GalleryContent] so they can be exercised in a Compose test without a ViewModel.
 */
@Composable
fun GalleryScreen(
    viewModel: OpenLoopViewModel,
    onBackClick: () -> Unit,
    onImportVideo: () -> Unit,
) {
    val videos by viewModel.visibleVideos.collectAsStateWithLifecycle()
    var selectedVideo by remember { mutableStateOf<RecordedVideo?>(null) }

    GalleryContent(
        videos = videos,
        onPlay = { selectedVideo = it },
        onRequestDelete = viewModel::requestDeleteVideos,
        onBackClick = onBackClick,
        onImportVideo = onImportVideo,
        // While the preview overlay is open its Dialog consumes Back to close itself, so the
        // gallery-level Back (exit selection / leave gallery) must stand down (Lesson 015).
        backEnabledWhenIdle = selectedVideo == null,
    )

    // ── Looping Video Playback Overlay ──
    selectedVideo?.let { video ->
        LoopingVideoOverlay(
            videoPath = video.videoPath,
            onDismiss = { selectedVideo = null }
        )
    }
}

/**
 * Stateless gallery UI: top bar (or contextual selection action bar), the adaptive thumbnail grid,
 * and the empty state. Selection is local UI state ([rememberSaveable], survives rotation); a single
 * long-press enters multi-select, an in-mode tap toggles, an out-of-mode tap plays via [onPlay].
 * Delete hands the selected loops to [onRequestDelete] (the ViewModel defers the real file delete and
 * shows an Undo snackbar) and clears the selection.
 *
 * Back handling (Lesson 015 — gate, don't always intercept): in selection mode Back clears the
 * selection; otherwise it falls through to [onBackClick] when [backEnabledWhenIdle] (the play overlay
 * isn't up).
 */
@Composable
fun GalleryContent(
    videos: List<RecordedVideo>,
    onPlay: (RecordedVideo) -> Unit,
    onRequestDelete: (List<RecordedVideo>) -> Unit,
    onBackClick: () -> Unit,
    onImportVideo: () -> Unit,
    backEnabledWhenIdle: Boolean = true,
) {
    var selectedIds by rememberSaveable(stateSaver = SelectedIdsSaver) {
        mutableStateOf(emptySet<Long>())
    }
    val inSelectionMode = selectedIds.isNotEmpty()

    // In selection mode Back exits selection (added after the idle handler below, so it wins while
    // enabled); when idle it's disabled and Back falls through to the gallery-exit handler.
    BackHandler(enabled = backEnabledWhenIdle) {
        if (inSelectionMode) selectedIds = emptySet() else onBackClick()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(SurfaceContainer, Canvas)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            if (inSelectionMode) {
                SelectionActionBar(
                    selectedCount = selectedIds.size,
                    onExitSelection = { selectedIds = emptySet() },
                    onDeleteSelected = {
                        onRequestDelete(videos.filter { it.id in selectedIds })
                        selectedIds = emptySet()
                    },
                )
            } else {
                GalleryTopBar(
                    onBackClick = onBackClick,
                    onImportVideo = onImportVideo,
                )
            }

            // ── Content: Grid or Empty State ──
            if (videos.isEmpty()) {
                EmptyGalleryState(onImportVideo = onImportVideo)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                ) {
                    items(videos, key = { it.id }) { video ->
                        VideoThumbnailCard(
                            video = video,
                            isSelected = video.id in selectedIds,
                            onClick = {
                                if (inSelectionMode) {
                                    selectedIds = selectedIds.toggle(video.id)
                                } else {
                                    onPlay(video)
                                }
                            },
                            onLongClick = { selectedIds = selectedIds + video.id },
                        )
                    }
                }
            }
        }
    }
}

/** Toggle [id] in/out of the receiver set (returns a new set). */
private fun Set<Long>.toggle(id: Long): Set<Long> =
    if (id in this) this - id else this + id

// ── Top bars ──

/** Resting top bar: back (left) + import (right). */
@Composable
private fun GalleryTopBar(
    onBackClick: () -> Unit,
    onImportVideo: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        BackButton(
            contentDescription = "Back to camera",
            onClick = onBackClick,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .testTag("gallery_back"),
        )

        // Import button — the primary action on this screen, so it gets the flat-lime accent.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(56.dp)
                .clip(CircleShape)
                .background(ElectricLime)
                .clickable(role = Role.Button) { onImportVideo() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.VideoLibrary,
                contentDescription = stringResource(R.string.gallery_import),
                modifier = Modifier.size(24.dp),
                tint = LimeInk
            )
        }
    }
}

/**
 * Contextual action bar shown while loops are selected (Issue #35): a leading ✕ that exits selection,
 * the live "N selected" count, and a trailing Delete. Both icon buttons are ≥48 dp and carry a
 * spoken label + button role (ANDROID_STANDARDS §7).
 */
@Composable
private fun SelectionActionBar(
    selectedCount: Int,
    onExitSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    val exitLabel = stringResource(R.string.gallery_exit_selection)
    val deleteLabel = stringResource(R.string.gallery_delete_selected)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("gallery_action_bar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(OverlayWhite)
                .border(1.dp, OverlayWhiteBorder, CircleShape)
                .clickable(role = Role.Button) { onExitSelection() }
                .semantics { contentDescription = exitLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = Color.White,
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = pluralStringResource(R.plurals.gallery_selection_count, selectedCount, selectedCount),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )

        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(OverlayWhite)
                .border(1.dp, OverlayWhiteBorder, CircleShape)
                .clickable(role = Role.Button) { onDeleteSelected() }
                .semantics { contentDescription = deleteLabel }
                .testTag("gallery_delete_selected"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = CoralRed,
            )
        }
    }
}

/** Empty state: no loops yet, with an import affordance (slice 07). */
@Composable
private fun EmptyGalleryState(onImportVideo: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "NO LOOPS YET",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White.copy(alpha = 0.6f),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Record your first loop to see it here!",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.35f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.gallery_import_empty_state),
                style = MaterialTheme.typography.labelLarge,
                color = ElectricLime,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable(role = Role.Button) { onImportVideo() }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

// ── Thumbnail Card ──

/**
 * One grid tile. A long-press enters/extends multi-select; a tap toggles (in selection mode) or plays
 * (out of it) — both routed through [combinedClickable] (a stable foundation API). When [selected] it
 * gets a lime ring, a dimming scrim, a check badge, and a slight scale-down; the tile also announces
 * its selected state to TalkBack via `semantics { selected = … }` (ANDROID_STANDARDS §7).
 */
@Composable
private fun VideoThumbnailCard(
    video: RecordedVideo,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val targetScale = when {
        isSelected -> 0.92f
        isPressed -> 0.93f
        else -> 1f
    }
    val scale by animateFloatAsState(targetScale, label = "card_scale")

    val thumbnail = remember(video.thumbnailPath) {
        try {
            BitmapFactory.decodeFile(video.thumbnailPath)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    val shape = MaterialTheme.shapes.small
    Box(
        modifier = Modifier
            .aspectRatio(9f / 16f)
            .scale(scale)
            .clip(shape)
            .background(SurfaceContainerHigh)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) ElectricLime else OutlineVariant,
                shape = shape,
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .testTag("gallery_tile_${video.id}")
            .semantics { selected = isSelected },
    ) {
        // Thumbnail image
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = "Video thumbnail",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Fallback if no thumbnail
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Movie,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        // Selected affordances: a dimming scrim + a lime check badge in the top-end corner.
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(OverlayScrim.copy(alpha = 0.35f))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(ElectricLime),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LimeInk,
                )
            }
        }
    }
}

// ── Full-Screen Looping Video Overlay ──

@OptIn(UnstableApi::class)
@Composable
private fun LoopingVideoOverlay(
    videoPath: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(videoPath)
            setMediaItem(mediaItem)
            repeatMode = Player.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
    }

    // Lifecycle-aware playback: pause on ON_STOP so a backgrounded app isn't decoding/playing this
    // preview (developer.android.com/media/implement/playback-app — stop playback in onStop on API
    // 24+); resume on ON_START. The player is released on leave-composition by the DisposableEffect.
    LifecycleStartEffect(Unit) {
        exoPlayer.playWhenReady = true
        onStopOrDispose { exoPlayer.playWhenReady = false }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Borderless looping video
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            PrimaryButton(
                text = "CLOSE PREVIEW",
                onClick = onDismiss,
                trailingIcon = Icons.Filled.Close,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp),
            )
        }
    }
}
