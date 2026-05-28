package com.openrang.app.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
@Composable
fun PreviewScreen(
    videoPath: String,
    onBackToCaptureClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Predictive-back (default-on at target 36): back returns to capture via the state machine.
    BackHandler { onBackToCaptureClick() }

    // Initialize ExoPlayer
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(videoPath)
            setMediaItem(mediaItem)
            repeatMode = Player.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. Full-screen Video Player
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false // hide controls for clean looping look
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
            },
            modifier = Modifier.fillMaxSize()
        )


        // 3. Bottom controls (Retake Button)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            DeepCharcoal
                        )
                    )
                )
                .navigationBarsPadding()
                .padding(bottom = 24.dp, start = 28.dp, end = 28.dp, top = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = onBackToCaptureClick,
                colors = ButtonDefaults.buttonColors(containerColor = NeonCoral),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .height(56.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
            ) {
                Text(
                    text = "RETAKE LOOP",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}
