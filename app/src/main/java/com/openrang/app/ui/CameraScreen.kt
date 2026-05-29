package com.openrang.app.ui

import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.openrang.app.R



import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openrang.app.camera.CameraManager

// Let's define premium theme colors
val GlassWhite = Color(0x33FFFFFF)
val GlassWhiteBorder = Color(0x4DFFFFFF)
val NeonCoral = Color(0xFFFF5252)
val NeonPurple = Color(0xFF7C4DFF)
val DeepCharcoal = Color(0xCC1A1A1D)

/**
 * Single hosting call site for the two camera-bound states ([OpenRangUiState.ReadyToCapture] and
 * [OpenRangUiState.Recording]).
 *
 * WHY THIS EXISTS: if those two states are rendered from two *separate* `when` branches in the
 * navigation (each with its own `CameraScreen(...)` call), Compose disposes one and builds the
 * other on the start/stop transition. That remount re-runs [CameraScreen]'s
 * `LaunchedEffect { startCamera() }`, which calls `unbindAll()` and tears the camera out from under
 * the in-flight recording — finalizing it immediately with `ERROR_SOURCE_INACTIVE` (~25 ms after
 * the user taps record). Routing both states through this one composable keeps a single
 * [content] instance alive across the transition, so the camera stays bound and recording runs
 * until the user taps stop or the 30 s cap fires.
 *
 * Regression guard: `CameraScreenTest.cameraScreenHost_keepsContentMounted_acrossCaptureTransition`.
 */
@Composable
fun CameraScreenHost(
    uiState: OpenRangUiState,
    content: @Composable () -> Unit
) {
    val isCaptureState = uiState is OpenRangUiState.ReadyToCapture ||
        uiState is OpenRangUiState.Recording
    if (isCaptureState) {
        // One call site for BOTH capture states — do not split this into per-state branches.
        content()
    }
}

@Composable
fun CameraScreen(
    viewModel: OpenRangViewModel,
    cameraManager: CameraManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRecording = uiState is OpenRangUiState.Recording

    // REC-1: keep the high-frequency elapsed flow as a raw State and DO NOT read `.value` here in
    // the screen root. Reading it at the top would re-subscribe this whole composable (AndroidView
    // viewfinder included) and recompose it ~30×/s. Instead, the read is deferred into the lambdas
    // below, so only the consumers (progress ring in the draw phase, countdown chip) react to ticks.
    val recordingElapsedState = viewModel.recordingElapsedMs.collectAsStateWithLifecycle()

    // Predictive back is default-on at targetSdk 36, so a mid-record back gesture would otherwise
    // finish the Activity → onDestroy → shutdown(), silently discarding the in-flight clip. Route it
    // through the state machine instead: while recording, backstops & finalizes (same as the stop
    // shutter). Disabled when not recording so back exits the home screen normally (WARNING-2).
    BackHandler(enabled = isRecording) {
        viewModel.stopBurstCapture(cameraManager)
    }

    // Static cap label ("00:30") for the countdown chip — independent of elapsed time.
    val capLabel = "%02d:%02d".format(
        OpenRangViewModel.MAX_RECORDING_MS / 60_000,
        (OpenRangViewModel.MAX_RECORDING_MS / 1000) % 60
    )

    // Set up standard aspect-ratio responsive PreviewView
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Trigger Camera binding when LifecycleOwner changes
    LaunchedEffect(lifecycleOwner) {
        cameraManager.startCamera(lifecycleOwner, previewView)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. Camera Viewfinder
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Translucent Glassmorphic Gradient Top Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            DeepCharcoal,
                            Color.Transparent
                        )
                    )
                )
                .statusBarsPadding()
                .padding(top = 12.dp, bottom = 16.dp)
        ) {
            // Home / Gallery Button — top-left neon gradient circle.
            HomeButton(
                onClick = { viewModel.navigateToGallery() },
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )

            // Countdown chip — top-center, recording only. `text` is a lambda so the elapsed read
            // (REC-1) is deferred into the chip's own scope; only the chip recomposes on each tick.
            RecordingCountdownChip(
                visible = isRecording,
                text = {
                    val ms = recordingElapsedState.value
                    "%02d:%02d / %s".format(ms / 60_000, (ms / 1000) % 60, capLabel)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 3. Glassmorphic Control Overlay & Shutter Button at bottom
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
                .padding(bottom = 24.dp, start = 24.dp, end = 24.dp, top = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            // Cap the control row width so the shutter/lens controls stay grouped and centered
            // on large screens (≥600dp) rather than stretching to the display edges.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Placeholder (for future settings / gallery preview)
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(GlassWhite)
                        .border(1.dp, GlassWhiteBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    // Reflects the new 30 s hard cap (was a stale "1.5s" from the old self-stop).
                    Text(
                        text = "30s",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                // Shutter Button: tap-to-start / tap-to-stop, with a progress ring. progressFraction
                // is a lambda so the elapsed read (REC-1) happens in the ring's draw phase, not here.
                ShutterButton(
                    isRecording = isRecording,
                    progressFraction = {
                        (recordingElapsedState.value.toFloat() / OpenRangViewModel.MAX_RECORDING_MS)
                            .coerceIn(0f, 1f)
                    },
                    onClick = {
                        if (isRecording) {
                            viewModel.stopBurstCapture(cameraManager)
                        } else {
                            viewModel.startBurstCapture(cameraManager)
                        }
                    }
                )

                // Switch Camera / Lens Toggle Button (subtle glass to match 1.5s badge)
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(GlassWhite)
                        .border(1.dp, GlassWhiteBorder, CircleShape)
                        .clickable {
                            cameraManager.toggleCamera(lifecycleOwner, previewView)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_flip_camera),
                        contentDescription = "Flip Camera",
                        modifier = Modifier.size(28.dp),
                        tint = Color.White
                    )
                }
            }
        }
    }
}

/**
 * Top-left home / gallery button: a neon-gradient circle holding the pictures-folder icon.
 *
 * Stateless and hoisted (mirrors [ShutterButton]) so its touch target is testable without the
 * camera. Sized at 48.dp — the Material/accessibility minimum interactive target (WARNING-3); the
 * 44.dp it replaced was a pre-launch accessibility-scanner failure. The 20.dp icon is unchanged.
 */
@Composable
fun HomeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(NeonCoral, NeonPurple)
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_pictures_folder),
            contentDescription = "Gallery",
            modifier = Modifier.size(20.dp),
            tint = Color.White
        )
    }
}

/**
 * Tap-to-start / tap-to-stop shutter with a progress ring.
 *
 * Stateless and hoisted (mirrors [OnboardingNavigation]) so it can be exercised in Compose UI
 * tests without binding the camera. While [isRecording], a [NeonCoral] ring sweeps clockwise from
 * 12 o'clock proportional to [progressFraction] (0f..1f toward the 30 s cap), the interior dims,
 * and the dot is replaced by a square "stop" glyph.
 *
 * [progressFraction] is a lambda, not a value: it is read inside the [Canvas] draw scope (REC-1) so
 * an elapsed-time tick only triggers a redraw of the ring, never a recomposition of this button or
 * the screen above it.
 */
@Composable
fun ShutterButton(
    isRecording: Boolean,
    progressFraction: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Progress ring — drawn just outside the 86.dp button, recording only.
        if (isRecording) {
            Canvas(
                modifier = Modifier
                    .size(98.dp)
                    .testTag("progress_ring")
            ) {
                val strokeWidth = 4.dp.toPx()
                val inset = strokeWidth / 2f
                drawArc(
                    color = NeonCoral,
                    startAngle = -90f,
                    sweepAngle = progressFraction().coerceIn(0f, 1f) * 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - strokeWidth, size.height - strokeWidth),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }

        Box(
            modifier = Modifier
                .size(86.dp)
                .clip(CircleShape)
                .background(if (isRecording) NeonCoral.copy(alpha = 0.2f) else GlassWhite)
                .border(
                    width = if (isRecording) 5.dp else 3.dp,
                    color = if (isRecording) NeonCoral else Color.White,
                    shape = CircleShape
                )
                .padding(if (isRecording) 12.dp else 6.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
                .semantics {
                    contentDescription = if (isRecording) "Stop recording" else "Start recording"
                },
            contentAlignment = Alignment.Center
        ) {
            if (isRecording) {
                // Stop glyph: small rounded square over the dimmed interior (vs. the idle dot).
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(NeonCoral)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(NeonCoral, NeonPurple)
                            )
                        )
                )
            }
        }
    }
}

/**
 * Top-center countdown chip shown only while recording: monospaced `MM:SS / 00:30` on a glass
 * surface (DeepCharcoal 80% over a GlassWhite 20% base). Renders nothing when [visible] is false,
 * so the visibility rule itself is testable (mirrors [OnboardingNavigation]'s hoisted pattern).
 *
 * [text] is a lambda, not a value: it is read inside this chip's composition (REC-1) so an
 * elapsed-time tick recomposes only the chip, never the camera screen above it.
 */
@Composable
fun RecordingCountdownChip(
    visible: Boolean,
    text: () -> String,
    modifier: Modifier = Modifier
) {
    if (!visible) return
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(GlassWhite)
                .background(DeepCharcoal)
                .border(1.dp, GlassWhiteBorder, RoundedCornerShape(percent = 50))
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .testTag("countdown_chip")
        ) {
            Text(
                text = text(),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
