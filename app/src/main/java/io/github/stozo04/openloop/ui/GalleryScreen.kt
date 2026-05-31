package io.github.stozo04.openloop.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@Composable
fun GalleryScreen(
    viewModel: OpenLoopViewModel,
    onBackClick: () -> Unit,
    onImportVideo: () -> Unit,
) {
    val videos by viewModel.recordedVideos.collectAsStateWithLifecycle()
    var selectedVideo by remember { mutableStateOf<RecordedVideo?>(null) }

    // Predictive-back (default-on at target 36): route the gallery's back through the
    // OpenLoopUiState state machine instead of exiting the app. While the preview overlay
    // is open its Dialog consumes back to close itself, so this handler is disabled then.
    BackHandler(enabled = selectedVideo == null) { onBackClick() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF1A1A2E), Color(0xFF0F0C1B))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // ── Top Bar: back (left) + import (right) ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Back button — matches onboarding navigation style
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(GlassWhite)
                        .border(2.dp, NeonPurple, CircleShape)
                        .clickable { onBackClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_film_slate),
                        contentDescription = "Back to camera",
                        modifier = Modifier.size(24.dp),
                        tint = NeonPurple
                    )
                }

                // Import button — mirrors the back button's style, anchored opposite it (slice 07).
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(GlassWhite)
                        .border(2.dp, NeonPurple, CircleShape)
                        .clickable { onImportVideo() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.VideoLibrary,
                        contentDescription = stringResource(R.string.gallery_import),
                        modifier = Modifier.size(24.dp),
                        tint = NeonPurple
                    )
                }
            }

            // ── Content: Grid or Empty State ──
            if (videos.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "NO LOOPS YET",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.6f),
                            letterSpacing = 2.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Record your first loop to see it here!",
                            fontSize = 14.sp,
                            color = Color.White.copy(alpha = 0.35f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        // Secondary affordance: import an existing clip instead of recording (slice 07).
                        Text(
                            text = stringResource(R.string.gallery_import_empty_state),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonPurple,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onImportVideo() }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            } else {
                // 3-column grid
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
                            onClick = { selectedVideo = video },
                            onDelete = { viewModel.deleteVideo(video) }
                        )
                    }
                }
            }
        }
    }

    // ── Looping Video Playback Overlay ──
    selectedVideo?.let { video ->
        LoopingVideoOverlay(
            videoPath = video.videoPath,
            onDismiss = { selectedVideo = null }
        )
    }
}

// ── Thumbnail Card ──

@Composable
private fun VideoThumbnailCard(
    video: RecordedVideo,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.93f else 1f, label = "card_scale")

    val thumbnail = remember(video.thumbnailPath) {
        try {
            BitmapFactory.decodeFile(video.thumbnailPath)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(9f / 16f)
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF2A2A3E))
            .border(1.dp, GlassWhiteBorder, RoundedCornerShape(12.dp))
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
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
                Text(
                    text = "🎬", // 🎬
                    fontSize = 28.sp
                )
            }
        }

        // Delete button — top-right corner. A 48dp touch target (Android's accessibility minimum)
        // wraps the 28dp coral circle: the visual stays small to suit the dense grid cell, but the
        // tappable/focusable area meets the guideline so it's usable for people with motor or
        // precision difficulties. The previous control was only 28dp — below the minimum. It also
        // had no spoken label; give it one (Role.Button + contentDescription) so TalkBack can
        // identify and activate it. developer.android.com/guide/topics/ui/accessibility/apps
        val deleteLabel = stringResource(R.string.gallery_delete)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(48.dp)
                .clickable(role = Role.Button) { onDelete() }
                .semantics { contentDescription = deleteLabel },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(NeonCoral.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "🗑", // 🗑
                    fontSize = 13.sp,
                    color = Color.White
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

            // Close button at the bottom
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(NeonCoral, NeonPurple)
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(28.dp))
                    .clickable { onDismiss() }
                    .padding(horizontal = 28.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "CLOSE PREVIEW ✕", // ✕
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}
