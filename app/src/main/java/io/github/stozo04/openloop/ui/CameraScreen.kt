package io.github.stozo04.openloop.ui

import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import io.github.stozo04.openloop.R
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.stozo04.openloop.camera.CameraManager
import io.github.stozo04.openloop.ui.components.PrimaryButtonPressedScale
import io.github.stozo04.openloop.ui.theme.CoralRed
import io.github.stozo04.openloop.ui.theme.ElectricLime
import io.github.stozo04.openloop.ui.theme.LimeInk
import io.github.stozo04.openloop.ui.theme.OverlayScrim
import io.github.stozo04.openloop.ui.theme.OverlayWhite
import io.github.stozo04.openloop.ui.theme.OverlayWhiteBorder
import io.github.stozo04.openloop.ui.theme.TimerTextStyle
import io.github.stozo04.openloop.ui.theme.shutterGradient

/**
 * Single hosting call site for the two camera-bound states ([OpenLoopUiState.ReadyToCapture] and
 * [OpenLoopUiState.Recording]).
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
    uiState: OpenLoopUiState,
    content: @Composable () -> Unit
) {
    val isCaptureState = uiState is OpenLoopUiState.ReadyToCapture ||
        uiState is OpenLoopUiState.Recording
    if (isCaptureState) {
        // One call site for BOTH capture states — do not split this into per-state branches.
        content()
    }
}

@Composable
fun CameraScreen(
    viewModel: OpenLoopViewModel,
    cameraManager: CameraManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRecording = uiState is OpenLoopUiState.Recording

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
        OpenLoopViewModel.MAX_RECORDING.inWholeMinutes,
        OpenLoopViewModel.MAX_RECORDING.inWholeSeconds % 60
    )

    // Set up standard aspect-ratio responsive PreviewView
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Bind when the screen enters composition; [releaseCamera] on dispose (below) pairs teardown
    // with PreviewView removal so CameraX does not keep queuing into an abandoned surface (Issue #36).
    LaunchedEffect(lifecycleOwner) {
        cameraManager.startCamera(lifecycleOwner, previewView)
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            cameraManager.releaseCamera()
        }
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
                            OverlayScrim,
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
                            OverlayScrim
                        )
                    )
                )
                .navigationBarsPadding()
                .padding(bottom = 24.dp, start = 24.dp, end = 24.dp, top = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            // Shutter stays centered; the lens toggle pins to the right edge. Width-capped so the
            // controls stay grouped/centered on large screens (≥600dp) rather than stretching out.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp),
                contentAlignment = Alignment.Center
            ) {
                // Shutter Button: tap-to-start / tap-to-stop, with a progress ring. progressFraction
                // is a lambda so the elapsed read (REC-1) happens in the ring's draw phase, not here.
                ShutterButton(
                    isRecording = isRecording,
                    progressFraction = {
                        (recordingElapsedState.value.toFloat() / OpenLoopViewModel.MAX_RECORDING.inWholeMilliseconds)
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

                // Switch Camera / Lens Toggle Button (subtle glass), pinned to the right edge.
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(OverlayWhite)
                        .border(1.dp, OverlayWhiteBorder, CircleShape)
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
            .background(ElectricLime)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_pictures_folder),
            contentDescription = "Gallery",
            modifier = Modifier.size(20.dp),
            tint = LimeInk
        )
    }
}

/**
 * Tap-to-start / tap-to-stop shutter with a progress ring.
 *
 * Stateless and hoisted (mirrors [OpenLoopUiState.Onboarding]) so it can be exercised in Compose UI
 * tests without binding the camera. While [isRecording], a [CoralRed] ring sweeps clockwise from
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
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) PrimaryButtonPressedScale else 1f,
        label = "shutter_scale",
    )

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
                    color = CoralRed,
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
                .scale(scale)
                .size(86.dp)
                .clip(CircleShape)
                .background(if (isRecording) CoralRed.copy(alpha = 0.2f) else OverlayWhite)
                .border(
                    width = if (isRecording) 5.dp else 3.dp,
                    color = if (isRecording) CoralRed else Color.White,
                    shape = CircleShape
                )
                .padding(if (isRecording) 12.dp else 6.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        if (!isRecording) {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        }
                        onClick()
                    },
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
                        .background(CoralRed)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(shutterGradient())
                )
            }
        }
    }
}

/**
 * Top-center countdown chip shown only while recording: monospaced `MM:SS / 00:30` on a glass
 * surface (OverlayScrim 80% over a OverlayWhite 20% base). Renders nothing when [visible] is false,
 * so the visibility rule itself is testable (mirrors [OpenLoopUiState.Onboarding]'s hoisted pattern).
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
                .background(OverlayWhite)
                .background(OverlayScrim)
                .border(1.dp, OverlayWhiteBorder, RoundedCornerShape(percent = 50))
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .testTag("countdown_chip")
        ) {
            Text(
                text = text(),
                style = TimerTextStyle,
                color = Color.White,
                textAlign = TextAlign.Center
            )
        }
    }
}
