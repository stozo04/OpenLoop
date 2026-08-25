package io.github.stozo04.openloop.ui

import androidx.activity.compose.BackHandler
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.stozo04.openloop.R
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import io.github.stozo04.openloop.camera.CameraManager
import io.github.stozo04.openloop.camera.PinchZoomCallbacks
import io.github.stozo04.openloop.camera.PinchZoomLayout
import io.github.stozo04.openloop.camera.formatZoomRatioForChip
import io.github.stozo04.openloop.camera.lens.Lens
import io.github.stozo04.openloop.media.BOOTH_FRAME_COUNT
import io.github.stozo04.openloop.media.cropToBoothSquare
import io.github.stozo04.openloop.ui.components.LensCarousel
import io.github.stozo04.openloop.ui.components.PrimaryButtonPressedScale
import io.github.stozo04.openloop.ui.theme.CoralRed
import io.github.stozo04.openloop.ui.theme.ElectricLime
import io.github.stozo04.openloop.ui.theme.LimeInk
import io.github.stozo04.openloop.ui.theme.OverlayScrim
import io.github.stozo04.openloop.ui.theme.OverlayWhite
import io.github.stozo04.openloop.ui.theme.OverlayWhiteBorder
import io.github.stozo04.openloop.ui.theme.TimerTextStyle
import io.github.stozo04.openloop.ui.theme.shutterGradient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withContext

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
    val nudgeGalleryButton by viewModel.nudgeGalleryButton.collectAsStateWithLifecycle()
    val activeLens by viewModel.activeLens.collectAsStateWithLifecycle()
    val lensTrayOpen by viewModel.lensTrayOpen.collectAsStateWithLifecycle()
    val captureMode by viewModel.captureMode.collectAsStateWithLifecycle()
    val isRecording = uiState is OpenLoopUiState.Recording
    val isPhotoMode = captureMode == CaptureMode.PHOTO

    // Push the selection into the always-attached lens effect. This is a uniform swap inside the
    // running GL renderer, NOT a rebind — which is why it is safe to change lenses mid-recording
    // (PRD-camera-lenses §5.3; a rebind here would be the Lesson 012 failure).
    LaunchedEffect(activeLens) {
        cameraManager.setLens(activeLens)
    }

    // REC-1: keep the high-frequency elapsed flow as a raw State and DO NOT read `.value` here in
    // the screen root. Reading it at the top would re-subscribe this whole composable (AndroidView
    // viewfinder included) and recompose it ~30×/s. Instead, the read is deferred into the lambdas
    // below, so only the consumers (progress ring in the draw phase, countdown chip) react to ticks.
    val recordingElapsedState = viewModel.recordingElapsedMs.collectAsStateWithLifecycle()

    // ── Photo booth (docs/PRD-photo-booth.md) ───────────────────────────────────────────────
    // Booth bypasses CaptureMode and the state machine entirely (§5.1): the sequence is ephemeral
    // UI state here, uiState stays ReadyToCapture throughout, and the ViewModel only hears about
    // it when the finished frame set is handed over. An activity recreation mid-sequence resets
    // to idle and discards the frames — the accepted POC limitation.
    var boothActive by remember { mutableStateOf(false) }
    var boothMonochrome by remember { mutableStateOf(false) }
    // D2 (decided 2026-08-20): booth is ARMED from the drawer's Photo Booth tab, and the shutter
    // starts the strip while armed. The tab selection IS the armed state, so it survives closing
    // the drawer (the lime lens button carries the cue) and resets only with the screen — the
    // same activity-recreation limitation §5.1 already accepts. Flipping the slider back to
    // Lenses (any time, mid-sequence included) disarms; the running sequence keys on
    // [boothActive] alone, so a mid-sequence disarm just means the next strip needs re-arming.
    var boothArmed by remember { mutableStateOf(false) }
    // Written once per second by the sequence effect; read ONLY inside [BoothCountdownOverlay]'s
    // own scope (REC-1 / Lesson 016), so a countdown tick never recomposes the viewfinder tree.
    val boothShot = remember { mutableIntStateOf(0) }
    val boothDigit = remember { mutableIntStateOf(0) }
    val boothFlashAlpha = remember { Animatable(0f) }
    // The sequence effect below is keyed on boothActive, so everything it captures is pinned at
    // launch (Lesson 034). The alias keeps the D4 Color/B&W choice live when toggled mid-countdown.
    val currentBoothMonochrome by rememberUpdatedState(boothMonochrome)

    // Predictive back is default-on at targetSdk 36, so a mid-record back gesture would otherwise
    // finish the Activity → onDestroy → shutdown(), silently discarding the in-flight clip. Route it
    // through the state machine instead: while recording, backstops & finalizes (same as the stop
    // shutter). Disabled when neither recording nor showing the lens tray, so back exits the home
    // screen normally (WARNING-2).
    //
    // The tray takes priority when both are true: back dismisses transient UI first, and a second
    // back then aborts the booth sequence or stops the recording. One handler rather than two so
    // that ordering is explicit instead of depending on Compose's registration order. A booth
    // abort discards the captured frames — no save, no snackbar (PRD-photo-booth §5.4).
    BackHandler(enabled = isRecording || lensTrayOpen || boothActive) {
        when {
            lensTrayOpen -> viewModel.setLensTrayOpen(false)
            boothActive -> boothActive = false
            else -> viewModel.stopBurstCapture(cameraManager)
        }
    }

    // Static cap label ("00:30") for the countdown chip — independent of elapsed time.
    val capLabel = "%02d:%02d".format(
        OpenLoopViewModel.MAX_RECORDING.inWholeMinutes,
        OpenLoopViewModel.MAX_RECORDING.inWholeSeconds % 60
    )

    // Zoom state for the ratio chip. Same REC-1 shape as the elapsed flow above: raw State, no
    // `.value` read at the screen root — during a pinch the ratio updates every frame, and the read
    // is deferred into the chip's text lambda so per-tick recomposition stays confined to the chip.
    val zoomUiState = cameraManager.zoomUi.collectAsStateWithLifecycle()

    // Pinch gesture activity. Begin/end flips happen at gesture granularity (rare), so these are
    // ordinary low-frequency state; the ~60 Hz ratio stream itself never enters Compose — it flows
    // gesture → CameraManager.applyPinchZoom() → zoomUi → chip text lambda.
    var pinchInProgress by remember { mutableStateOf(false) }
    var pinchEndCount by remember { mutableIntStateOf(0) }

    // Chip-visibility flips wired into the PinchZoomCallbacks handed to [PinchZoomLayout] below.
    val onPinchBegin = rememberUpdatedState { pinchInProgress = true }
    val onPinchEnd = rememberUpdatedState {
        pinchInProgress = false
        pinchEndCount++
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // TextureView path: avoids SurfaceView touch/hole issues when embedded in Compose.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    // Native pinch host — intercepts multitouch before PreviewView/SurfaceView can consume it.
    val pinchHost = remember {
        PinchZoomLayout(context).also { host ->
            host.addView(
                previewView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
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

    // The self-driving booth capture sequence: 5-4-3-2-1 → grab + flash, ×3, auto-advancing.
    // Cancel (button or predictive back) flips [boothActive] off, which cancels this effect at its
    // next suspension point and discards the captured frames — no save, no snackbar (PRD §5.4).
    // Navigating away (gallery) disposes the screen, which is the same clean abort. All of that
    // holds only UP TO the last grab: the finished frame set is handed over before the final
    // flash, after which nothing in that 250 ms can discard the strip (§5.4).
    LaunchedEffect(boothActive) {
        if (!boothActive) return@LaunchedEffect
        // A cancel mid-flash freezes the Animatable mid-fade; without this reset the next run
        // would open under a semi-opaque white veil for its entire first countdown.
        boothFlashAlpha.snapTo(0f)
        val frames = mutableListOf<Bitmap>()
        // [OpenLoopViewModel.captureBoothStrip] takes ownership of the frames on every one of its
        // paths, so this effect recycles only when the sequence dies before a hand-over.
        var handedOver = false
        try {
            for (shot in 1..BOOTH_FRAME_COUNT) {
                boothShot.intValue = shot
                for (digit in BOOTH_COUNTDOWN_SECONDS downTo 1) {
                    boothDigit.intValue = digit
                    delay(1.seconds)
                }
                // Grab AFTER the `1` clears. Compose overlays can never contaminate the grab
                // (getBitmap() returns the camera preview content only), but this keeps the ritual
                // on the overlay and the timing honest.
                boothDigit.intValue = 0
                // Backgrounded mid-countdown (home, lock, phone call): delay() keeps ticking on
                // wall clock, and after started-then-stopped the COMPATIBLE-mode TextureView
                // retains its last frame — getBitmap() would return a stale non-null grab and
                // bake duplicate frames into the strip (Lesson 036). Treat ON_STOP like Cancel:
                // silent discard back to idle (§5.1 accepts only activity recreation).
                if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    boothActive = false
                    return@LaunchedEffect
                }
                val grab = previewView.bitmap // null until the preview has ever streamed
                if (grab == null) {
                    // Abort the whole sequence: the ViewModel rejects the short frame set and
                    // surfaces the capture-failed snackbar; no partial strip is ever saved.
                    handedOver = true
                    viewModel.captureBoothStrip(frames, currentBoothMonochrome)
                    boothActive = false
                    return@LaunchedEffect
                }
                // Crop at grab time — retains 3× ~1080² ARGB instead of 3× full-screen (§5.1).
                // Only the getBitmap() readback must stay on main; the ~4.7 MB copy hops off so
                // the flash animation starts on an unblocked frame.
                frames += withContext(Dispatchers.Default) { cropToBoothSquare(grab) }
                if (shot == BOOTH_FRAME_COUNT) {
                    // Point of no return: hand the strip over BEFORE the cosmetic final flash.
                    // A gallery tap (or Cancel/back/ON_STOP) during that 250 ms would otherwise
                    // cancel this effect at animateTo and recycle all three captured frames —
                    // ~18 s of posing lost to a race with an animation (Cursor, PR #138). After
                    // the last grab the strip is committed and the flash is just theater; this
                    // is also exactly D4's promise — the B&W chip applies "until the last grab".
                    handedOver = true
                    viewModel.captureBoothStrip(frames, currentBoothMonochrome)
                }
                boothFlashAlpha.snapTo(BOOTH_FLASH_PEAK_ALPHA)
                boothFlashAlpha.animateTo(0f, tween(BOOTH_FLASH_FADE_MS))
            }
            boothActive = false
        } finally {
            // Died before hand-over (Cancel, back, ON_STOP, navigation dispose): release the
            // ~14 MB of captured frames eagerly (PRD §8), matching the ViewModel's policy.
            if (!handedOver) frames.forEach { it.recycle() }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. Camera viewfinder inside [PinchZoomLayout] — View-layer pinch intercept (Fold-safe).
        AndroidView(
            factory = { pinchHost },
            modifier = Modifier.fillMaxSize(),
            update = { host ->
                host.callbacks = PinchZoomCallbacks(
                    isBound = { cameraManager.isCameraBound() },
                    onBegin = {
                        Log.i(PINCH_LOG_TAG, "Pinch gesture started (view)")
                        cameraManager.onPinchZoomBegin()
                        onPinchBegin.value()
                    },
                    onScale = { cameraManager.applyPinchZoom(it) },
                    onEnd = {
                        Log.i(PINCH_LOG_TAG, "Pinch gesture ended (view)")
                        cameraManager.onPinchZoomEnd()
                        onPinchEnd.value()
                    },
                )
            },
        )

        // Zoom ratio chip — centered in the viewfinder, visible while a pinch is active and for
        // ~1 s after it ends. Informational only (not a touch target), so it can't steal the
        // shutter/flip/home touches; `text` defers the ratio read (REC-1) into the chip's scope.
        ZoomRatioChip(
            visible = rememberZoomChipVisible(pinchInProgress, pinchEndCount),
            text = { formatZoomRatioForChip(zoomUiState.value?.ratio ?: 1f) },
            modifier = Modifier.align(Alignment.Center)
        )

        // 3. Translucent Glassmorphic Gradient Top Bar
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
            // Home / Gallery Button — top-left ghost glass circle, lime icon.
            HomeButton(
                onClick = { viewModel.navigateToGallery() },
                bounce = nudgeGalleryButton,
                onBounceFinished = viewModel::onGalleryButtonNudgeFinished,
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

            // D5 banner — top-center while the sequence runs (the slot the selector vacates):
            // tells the user what the countdown is FOR. The drawer below stays interactive the
            // whole ritual, so this is an invitation, not decoration. Same vertical geometry as
            // [HomeButton] (4.dp nudge + a 48.dp row, chip centered in it) so the banner and the
            // corner button share a center line instead of hanging at slightly different heights.
            BoothSwapHintChip(
                visible = boothActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(48.dp)
            )

            // Capture-mode selector — top-right. Hidden while recording: mid-capture the shutter
            // means "stop", so offering to turn it into a photo button would strand the clip
            // (the ViewModel refuses the switch too — belt and braces). Hidden mid-booth as well:
            // the sequence replaces the shutter, so mode is meaningless until it finishes (§5.1).
            if (!isRecording && !boothActive) {
                CaptureModeSelector(
                    photoMode = isPhotoMode,
                    onSelect = {
                        // Choosing a capture mode is an explicit "back to normal capture":
                        // disarm the booth so the selector's answer to "what does the shutter
                        // do" stays honest. Only fires on an actual change — tapping the
                        // already-selected segment is inert.
                        boothArmed = false
                        viewModel.setCaptureMode(it)
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 16.dp, top = 4.dp)
                )
            }
        }

        // 4. Glassmorphic Control Overlay & Shutter Button at bottom
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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Lens rail sits above the control row and pushes nothing — the shutter stays
                // reachable while browsing lenses, matching Snapchat and the reference UI.
                // The drawer (docs/PRD-photo-booth.md D2, decided 2026-08-20): one surface behind
                // the lens button holding TWO tabs — Photo Booth | Lenses, Lenses selected by
                // default since the thumbnails are what shows. Flipping to Photo Booth swaps the
                // carousel for the D4 Colored / Black & White choice and arms the shutter. The
                // drawer stays interactive mid-sequence — the countdown IS the swap window (D5).
                AnimatedVisibility(
                    visible = lensTrayOpen,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Column(
                        modifier = Modifier.padding(bottom = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        BoothLensToggle(
                            boothSelected = boothArmed,
                            onSelect = { boothArmed = it },
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                        if (boothArmed) {
                            BoothColorRow(
                                monochrome = boothMonochrome,
                                onSelectMonochrome = { boothMonochrome = it },
                                onClose = {
                                    // Same contract as the lens tab's ✕ (clear this tab's
                                    // effect, then close): abort a running sequence — this IS
                                    // the booth's Cancel button (owner, 2026-08-20; predictive
                                    // back is the other way out) — disarm, and shut the drawer.
                                    boothActive = false
                                    boothArmed = false
                                    viewModel.setLensTrayOpen(false)
                                },
                            )
                        } else {
                            LensCarousel(
                                lenses = Lens.entries,
                                activeLens = activeLens,
                                onSelect = viewModel::selectLens,
                                onClose = {
                                    viewModel.selectLens(null)
                                    viewModel.setLensTrayOpen(false)
                                },
                            )
                        }
                    }
                }

                // No separate mid-sequence control row (owner, 2026-08-20): the drawer above IS
                // the booth's control surface — its ✕ cancels + disarms, and the Color / B&W
                // pair stays live until the last grab (D4, via the sequence effect's
                // rememberUpdatedState alias). Predictive back remains the buttonless abort
                // when the drawer is closed.

                // Shutter stays centered; the flip toggle pins to the right edge and the lens
                // button mirrors it on the left. Width-capped so the controls stay
                // grouped/centered on large screens (≥600dp) rather than stretching out.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 520.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Drawer button — present in BOTH camera-bound states, recording included.
                    // Lime-filled while a lens is active OR booth is armed: the button is the
                    // one persistent cue that something behind the drawer is switched on.
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(
                                if (activeLens != null || boothArmed) ElectricLime else OverlayWhite
                            )
                            .border(1.dp, OverlayWhiteBorder, CircleShape)
                            .clickable { viewModel.setLensTrayOpen(!lensTrayOpen) }
                            .testTag("lens_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_lenses),
                            contentDescription = stringResource(
                                if (lensTrayOpen) R.string.camera_drawer_hide else R.string.camera_drawer_open
                            ),
                            modifier = Modifier.size(28.dp),
                            // Owner's call (2026-08-12): lime icon on glass for consistency with
                            // the gallery button and mode selector. The active state keeps
                            // the inverse scheme (lime fill + ink icon) so it still reads as "on".
                            tint = if (activeLens != null || boothArmed) LimeInk else ElectricLime
                        )
                    }

                    // Shutter Button: tap-to-start / tap-to-stop, with a progress ring.
                    // progressFraction is a lambda so the elapsed read (REC-1) happens in the
                    // ring's draw phase, not here.
                    ShutterButton(
                        isRecording = isRecording,
                        photoMode = isPhotoMode,
                        boothArmed = boothArmed,
                        // Genuinely disabled mid-booth (PRD §5.1) — the sequence replaces the
                        // shutter, and a real disable (vs. a no-op onClick) keeps TalkBack's
                        // announcement, the confirmation haptic, and the press animation honest.
                        enabled = !boothActive,
                        progressFraction = {
                            (recordingElapsedState.value.toFloat() / OpenLoopViewModel.MAX_RECORDING.inWholeMilliseconds)
                                .coerceIn(0f, 1f)
                        },
                        onClick = {
                            when {
                                // Stop always wins: arming booth mid-recording must never turn
                                // the stop button into a booth trigger and strand the clip.
                                isRecording -> viewModel.stopBurstCapture(cameraManager)
                                // Armed booth replaces both capture modes (§5.1 — booth bypasses
                                // CaptureMode). Prime the overlay before it mounts: it renders one
                                // frame before the sequence effect's first write, and a leftover
                                // "Shot 3 of 3" from the previous run would flash — and be
                                // announced by TalkBack — in that gap.
                                boothArmed -> {
                                    boothShot.intValue = 1
                                    boothDigit.intValue = BOOTH_COUNTDOWN_SECONDS
                                    boothActive = true
                                }
                                // Photo mode: grab the composited viewfinder (lens included) and
                                // hand it straight to the ViewModel — no recording, no editor.
                                // `bitmap` is null until the preview streams; the ViewModel
                                // null-guards and surfaces a snackbar.
                                isPhotoMode -> viewModel.capturePhoto(previewView.bitmap)
                                else -> viewModel.startBurstCapture(cameraManager)
                            }
                        }
                    )

                    // Switch Camera button (subtle glass), pinned to the right edge.
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(OverlayWhite)
                            .border(1.dp, OverlayWhiteBorder, CircleShape)
                            // Disabled mid-booth (PRD-photo-booth §5.1): a camera flip rebinds,
                            // blanking the preview mid-ritual; lens swaps are the sanctioned
                            // between-frames trick, camera flips are not.
                            .clickable(enabled = !boothActive) {
                                cameraManager.toggleCamera(lifecycleOwner, previewView)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_flip_camera),
                            contentDescription = stringResource(R.string.camera_flip),
                            modifier = Modifier.size(28.dp),
                            // Owner's call (2026-08-12): lime icon on glass — consistent with the
                            // gallery button, mode selector, and lens button.
                            tint = ElectricLime
                        )
                    }
                }
            }
        }

        // Booth countdown overlay + per-shot flash, above everything else. The flash Box carries
        // no click handling, so the lens tray and Cancel stay usable underneath it; its alpha is
        // read in the graphicsLayer block, so the fade animates without recomposing this screen.
        if (boothActive) {
            BoothCountdownOverlay(
                digit = { boothDigit.intValue },
                shot = { boothShot.intValue },
                modifier = Modifier.align(Alignment.Center)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = boothFlashAlpha.value }
                    .background(Color.White)
            )
        }
    }
}

/**
 * Top-left home / gallery button: a ghost-style glass circle holding the pictures-folder icon in
 * [ElectricLime].
 *
 * Owner's call (2026-08-12): the previous solid-lime fill was the most eye-catching element on the
 * viewfinder and competed with the shutter for attention. The chrome is now the same
 * [OverlayWhite]/[OverlayWhiteBorder] glass as the lens/flip buttons, with the lime moved into the
 * icon tint so the button still reads as OpenLoop without shouting.
 *
 * Stateless and hoisted (mirrors [ShutterButton]) so its touch target is testable without the
 * camera. Sized at 48.dp — the Material/accessibility minimum interactive target (WARNING-3); the
 * 44.dp it replaced was a pre-launch accessibility-scanner failure. The 20.dp icon is unchanged.
 */
@Composable
fun HomeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bounce: Boolean = false,
    onBounceFinished: () -> Unit = {},
) {
    val offset = remember { Animatable(0f) }
    val bounceDistance = with(LocalDensity.current) { 8.dp.toPx() }
    val currentOnBounceFinished = rememberUpdatedState(onBounceFinished)
    LaunchedEffect(bounce) {
        if (bounce) {
            repeat(5) {
                offset.animateTo(-bounceDistance, tween(durationMillis = 90))
                offset.animateTo(0f, tween(durationMillis = 90))
            }
            currentOnBounceFinished.value()
        }
    }

    Box(
        modifier = modifier
            .graphicsLayer { translationY = offset.value }
            .size(48.dp)
            .clip(CircleShape)
            .background(OverlayWhite)
            .border(1.dp, OverlayWhiteBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_pictures_folder),
            contentDescription = stringResource(R.string.camera_gallery),
            modifier = Modifier.size(20.dp),
            tint = ElectricLime
        )
    }
}

/**
 * The clear-glass pill chrome shared by [CaptureModeSelector] and the booth controls: 48.dp tall
 * (the Material/accessibility minimum interactive target, WARNING-3), fully-rounded clip,
 * [background] fill (the same clear glass as the gallery/lens/flip buttons — owner's call — not
 * the darker OverlayScrim the info chips use), and the standard [OverlayWhiteBorder] hairline.
 * One helper so a chrome tweak lands on every pill on this screen at once.
 */
private fun Modifier.glassPill(background: Color = OverlayWhite): Modifier = this
    .height(48.dp)
    .clip(RoundedCornerShape(percent = 50))
    .background(background)
    .border(1.dp, OverlayWhiteBorder, RoundedCornerShape(percent = 50))

/** Label style for the glass pills ([CaptureModeSegment] and the booth controls). */
private val PillTextStyle = TimerTextStyle.copy(fontSize = 12.sp, lineHeight = 16.sp)

/**
 * Top-right capture-mode selector: a two-segment CAMERA | VIDEO control that flips the shutter
 * between taking stills and recording clips.
 *
 * Supersedes the single toggling icon from docs/PRD-photo-capture.md §5.2 (owner's call,
 * 2026-08-12, while fixing issue #126): with one icon, the only state indicator sat directly under
 * the fingertip at the exact moment it changed, so every tap was a coin flip. A segmented control
 * removes the guessing — the current mode is always readable from which segment carries the lime
 * fill, and each tap names its target mode instead of meaning "the other one".
 *
 * Feedback on top of the visible selection (issue #126):
 * - **Haptic tick on every mode change** — [HapticFeedbackType.ToggleOn] entering photo mode (the
 *   non-default state), [HapticFeedbackType.ToggleOff] returning to video. Tapping the already
 *   selected segment changes nothing and fires nothing — no haptic lie.
 * - **Selection semantics** — each segment is `selectable` with [Role.RadioButton], so TalkBack
 *   reads "Camera, selected" / "Video, not selected" and announces the flip.
 *
 * The visible lime pill is inset inside a full-height touch target: the container is 48.dp tall
 * (the Material/accessibility minimum interactive target, WARNING-3) and each segment's tappable
 * area spans that full height even though the pill draws smaller.
 *
 * Stateless and hoisted (mirrors [HomeButton]) so it can be exercised in a Compose test without
 * binding the camera; the caller decides when it is shown.
 */
@Composable
fun CaptureModeSelector(
    photoMode: Boolean,
    onSelect: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .glassPill()
            .padding(horizontal = 4.dp)
            .selectableGroup()
            .testTag("capture_mode_selector"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CaptureModeSegment(
            label = stringResource(R.string.camera_mode_camera),
            selected = photoMode,
            iconSelected = Icons.Filled.PhotoCamera,
            iconUnselected = Icons.Outlined.PhotoCamera,
            onClick = {
                if (!photoMode) {
                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    onSelect(CaptureMode.PHOTO)
                }
            },
        )
        CaptureModeSegment(
            label = stringResource(R.string.camera_mode_video),
            selected = !photoMode,
            iconSelected = Icons.Filled.Videocam,
            iconUnselected = Icons.Outlined.Videocam,
            onClick = {
                if (photoMode) {
                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                    onSelect(CaptureMode.VIDEO)
                }
            },
        )
    }
}

/**
 * One segment of [CaptureModeSelector]: a lime pill with [LimeInk] content when selected, ghost
 * with white content otherwise. The icon swaps filled/outlined with selection (reference design).
 * The `selectable` (and its merged label text) lives on the full-height outer Box so the touch
 * target stays 48.dp tall while the pill draws inset.
 */
@Composable
private fun CaptureModeSegment(
    label: String,
    selected: Boolean,
    iconSelected: ImageVector,
    iconUnselected: ImageVector,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(if (selected) ElectricLime else Color.Transparent)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (selected) iconSelected else iconUnselected,
                contentDescription = null,
                tint = if (selected) LimeInk else Color.White,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = PillTextStyle,
                color = if (selected) LimeInk else Color.White,
            )
        }
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
 *
 * [enabled] wires straight into `clickable(enabled = …)`, which suppresses the tap, the confirmation
 * haptic, and the press interaction, and adds the disabled semantics TalkBack announces — a booth
 * sequence must not leave an announced-enabled shutter that silently does nothing (PRD-photo-booth
 * §5.1; the camera-flip button gates the same way).
 */
@Composable
fun ShutterButton(
    isRecording: Boolean,
    progressFraction: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    photoMode: Boolean = false,
    boothArmed: Boolean = false,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) PrimaryButtonPressedScale else 1f,
        label = "shutter_scale",
    )
    // Hoisted: stringResource needs a composable scope, and the semantics {} block below is not one.
    val photoLabel = stringResource(R.string.camera_take_photo)
    val stopLabel = stringResource(R.string.camera_stop_recording)
    val startLabel = stringResource(R.string.camera_start_recording)
    val boothLabel = stringResource(R.string.camera_booth_start)

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
                    enabled = enabled,
                    onClick = {
                        if (!isRecording) {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        }
                        onClick()
                    },
                )
                .semantics {
                    // Same precedence as the onClick `when` in [CameraScreen]: stop first, then
                    // the armed booth (it overrides both capture modes), then photo/video.
                    contentDescription = when {
                        isRecording -> stopLabel
                        boothArmed -> boothLabel
                        photoMode -> photoLabel
                        else -> startLabel
                    }
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
                // The neon gradient dot is shared by both idle modes — owner's call: the lime look
                // is the app's signature and stays even when the shutter takes a still. Photo mode
                // is signalled by the top-right selector and the spoken label, not by the fill.
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
 * The drawer's two-tab slider: Photo Booth | Lenses (docs/PRD-photo-booth.md D2, decided
 * 2026-08-20). Same anatomy as [CaptureModeSelector] — 48.dp glass container (the WARNING-3
 * accessibility floor), inset lime pill, radio-button semantics, haptic tick on change — so the
 * two segmented controls on this screen read as one species. Selecting Photo Booth is what ARMS
 * the booth (the caller's state); Lenses is the default tab. Stateless and hoisted (mirrors
 * [HomeButton]) so it is testable without binding the camera.
 */
@Composable
fun BoothLensToggle(
    boothSelected: Boolean,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .glassPill()
            .padding(horizontal = 4.dp)
            .selectableGroup()
            .testTag("booth_lens_toggle"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoothLensSegment(
            label = stringResource(R.string.camera_booth_tab),
            selected = boothSelected,
            onClick = {
                if (!boothSelected) {
                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    onSelect(true)
                }
            },
        )
        BoothLensSegment(
            label = stringResource(R.string.camera_lenses),
            selected = !boothSelected,
            onClick = {
                if (boothSelected) {
                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                    onSelect(false)
                }
            },
        )
    }
}

/**
 * One text-only segment of [BoothLensToggle] — [CaptureModeSegment] minus the icon (the tab
 * names carry the meaning; the drawer content below is the picture). Full-height touch target
 * with the visible pill drawn inset, same as the capture-mode selector.
 */
@Composable
private fun BoothLensSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(if (selected) ElectricLime else Color.Transparent)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(
                text = label,
                style = PillTextStyle,
                color = if (selected) LimeInk else Color.White,
            )
        }
    }
}

/**
 * The drawer's Photo Booth tab: clear-and-close plus the D4 color choice as a two-option radio
 * pair (Color is the default — D4). Mirrors the [LensCarousel] row silhouette (leading ✕, then
 * the content) so the drawer's two tabs feel like one surface. The ✕ follows the lens tab's
 * "clear this tab's effect, then close" contract — the caller wires it to cancel a running
 * sequence, disarm, and shut the drawer, making it the booth's Cancel button (predictive back
 * is the buttonless abort). The radio pair stays live mid-sequence: the D4 choice applies at
 * composite time, until the last grab. Stateless and hoisted for camera-free tests.
 */
@Composable
fun BoothColorRow(
    monochrome: Boolean,
    onSelectMonochrome: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val closeLabel = stringResource(R.string.camera_booth_close)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(OverlayWhite)
                .border(1.dp, OverlayWhiteBorder, CircleShape)
                .clickable(onClick = onClose)
                .semantics { contentDescription = closeLabel }
                .testTag("booth_tab_close"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_lens_close),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Color.White,
            )
        }
        Spacer(Modifier.width(12.dp))
        Row(
            modifier = Modifier.selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BoothColorChip(
                label = stringResource(R.string.camera_booth_colored),
                selected = !monochrome,
                onSelect = { onSelectMonochrome(false) },
                testTag = "booth_choice_colored",
            )
            Spacer(Modifier.width(12.dp))
            BoothColorChip(
                label = stringResource(R.string.camera_booth_black_white),
                selected = monochrome,
                onSelect = { onSelectMonochrome(true) },
                testTag = "booth_choice_bw",
            )
        }
    }
}

/** One option of [BoothColorRow]'s radio pair: lime when selected, glass otherwise. */
@Composable
private fun BoothColorChip(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    testTag: String,
) {
    Box(
        modifier = Modifier
            .glassPill(background = if (selected) ElectricLime else OverlayWhite)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                // Tapping the already-selected option is inert, like the mode selector's segments.
                onClick = { if (!selected) onSelect() },
            )
            .padding(horizontal = 16.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = PillTextStyle,
            color = if (selected) LimeInk else Color.White,
        )
    }
}

/**
 * Center-viewfinder countdown for the booth sequence: the 5-4-3-2-1 digit and "Shot n of 3".
 *
 * [digit] and [shot] are lambdas so the once-per-second reads happen inside this overlay's own
 * scope (REC-1 / Lesson 016) — a tick recomposes the overlay, never the camera screen above it.
 * Both texts are polite live regions, so TalkBack announces the ticks and each shot advance: a
 * timed, visual-only countdown would exclude non-sighted users, and this is in the
 * never-simplify-away bucket (docs/PRD-photo-booth.md §5.1). The digit node stays mounted (empty
 * text during the grab beat) so the live region persists across the whole sequence.
 */
@Composable
fun BoothCountdownOverlay(
    digit: () -> Int,
    shot: () -> Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val currentDigit = digit()
        Text(
            text = if (currentDigit > 0) "$currentDigit" else "",
            style = TimerTextStyle.copy(fontSize = 96.sp, lineHeight = 104.sp),
            color = Color.White,
            modifier = Modifier
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("booth_countdown_digit"),
        )
        Text(
            text = stringResource(R.string.camera_booth_shot_progress, shot(), BOOTH_FRAME_COUNT),
            style = TimerTextStyle,
            color = Color.White,
            modifier = Modifier
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("booth_shot_progress"),
        )
    }
}

/** Seconds counted down before each booth shot (PRD-photo-booth D5 — room to swap lenses). */
internal const val BOOTH_COUNTDOWN_SECONDS = 5

/** Booth flash flicker: snap to this alpha on the grab, then fade out over the fade duration. */
private const val BOOTH_FLASH_PEAK_ALPHA = 0.9f
private const val BOOTH_FLASH_FADE_MS = 250

/** Log tag for pinch gesture delivery diagnostics (distinct from [CameraManager]'s tag). */
private const val PINCH_LOG_TAG = "OpenLoopPinchZoom"

/** How long the zoom ratio chip lingers after the pinch gesture ends before fading out. */
internal const val ZOOM_CHIP_LINGER_MS = 1_000L

/**
 * Visibility rule for [ZoomRatioChip]: visible while a pinch is in progress, and for
 * [ZOOM_CHIP_LINGER_MS] after the last gesture ends. [pinchEndCount] (a monotonic per-gesture
 * counter) restarts the linger window when a new pinch ends before the previous window expired.
 * Extracted from [CameraScreen] so the linger behavior is testable without binding the camera.
 */
@Composable
fun rememberZoomChipVisible(pinchInProgress: Boolean, pinchEndCount: Int): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(pinchInProgress, pinchEndCount) {
        if (pinchInProgress) {
            visible = true
        } else if (visible) {
            delay(ZOOM_CHIP_LINGER_MS.milliseconds)
            visible = false
        }
    }
    return visible
}

/**
 * Center-viewfinder zoom ratio chip (`1.0x`, `2.3x`, `0.5x`) on the same glass surface as
 * [RecordingCountdownChip]. Stateless and hoisted; fades in/out with [visible] (the linger rule
 * lives in [rememberZoomChipVisible]). Informational only — never a touch target.
 *
 * [text] is a lambda, not a value: during a pinch the ratio updates every frame, so the read is
 * deferred into this chip's composition (REC-1 / Lesson 016) and each tick recomposes only the
 * chip, never the camera screen above it. The merged semantics include the live value
 * ("Zoom level, 2.3x") so TalkBack announces what the zoom actually is, not just that a zoom
 * control exists; that read is likewise confined to this node.
 */
@Composable
fun ZoomRatioChip(
    visible: Boolean,
    text: () -> String,
    modifier: Modifier = Modifier
) {
    // Resources, not stringResource: the label interpolates the live ratio, and that read must stay
    // inside the semantics lambda (Lesson 016) — which is not a composable scope. LocalResources (not
    // LocalContext.current.resources — lint LocalContextGetResourceValueCall) so the read is
    // invalidated on a Configuration change, and the format string still comes from strings.xml.
    val resources = LocalResources.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(OverlayWhite)
                .background(OverlayScrim)
                .border(1.dp, OverlayWhiteBorder, RoundedCornerShape(percent = 50))
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .testTag("zoom_chip")
                .semantics(mergeDescendants = true) {
                    contentDescription =
                        resources.getString(R.string.camera_zoom_level_content_description, text())
                }
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

/**
 * Top-center banner while a booth sequence runs (docs/PRD-photo-booth.md D5): the countdown is
 * the window to swap lenses, and this chip is the one place on screen that says so. Same glass
 * chip chrome as [RecordingCountdownChip] (the two are never visible together — booth and
 * recording are mutually exclusive). Informational only — never a touch target. Renders nothing
 * when [visible] is false, so the visibility rule itself is testable.
 */
@Composable
fun BoothSwapHintChip(
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    if (!visible) return
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(OverlayWhite)
                .background(OverlayScrim)
                // Owner's call (2026-08-20): a light lime trim instead of the usual white
                // hairline — the banner is the one booth element in the top half, and the
                // lime ties it to the armed lens button below without shouting.
                .border(
                    1.dp,
                    ElectricLime.copy(alpha = 0.55f),
                    RoundedCornerShape(percent = 50)
                )
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .testTag("booth_swap_hint")
        ) {
            // PillTextStyle (12sp), not the 14sp TimerTextStyle: the copy must clear the
            // 48.dp corner buttons on a 360dp-class screen instead of underlapping them.
            Text(
                text = stringResource(R.string.camera_booth_swap_hint),
                style = PillTextStyle,
                color = Color.White,
                textAlign = TextAlign.Center
            )
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
