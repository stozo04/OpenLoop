package io.github.stozo04.openloop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import io.github.stozo04.openloop.camera.CameraManager
import io.github.stozo04.openloop.data.UserPreferencesRepositoryImpl
import io.github.stozo04.openloop.data.VideoImporterImpl
import io.github.stozo04.openloop.data.VideoStorageRepositoryImpl
import io.github.stozo04.openloop.data.dataStore
import io.github.stozo04.openloop.media.Media3VideoProcessor
import io.github.stozo04.openloop.media.VideoProcessor
import io.github.stozo04.openloop.media.VideoReverser
import io.github.stozo04.openloop.ui.BoomerangEditorScreen
import io.github.stozo04.openloop.ui.BoomerangEvent
import io.github.stozo04.openloop.ui.CameraScreen
import io.github.stozo04.openloop.ui.CameraScreenHost
import io.github.stozo04.openloop.ui.GalleryScreen
import io.github.stozo04.openloop.ui.OnboardingScreen
import io.github.stozo04.openloop.ui.OpenLoopUiState
import io.github.stozo04.openloop.ui.OpenLoopViewModel
import io.github.stozo04.openloop.ui.ProcessingScreen
import io.github.stozo04.openloop.ui.TrimScreen
import io.github.stozo04.openloop.ui.theme.Canvas
import io.github.stozo04.openloop.ui.theme.CoralRed
import io.github.stozo04.openloop.ui.theme.ElectricLime
import io.github.stozo04.openloop.ui.theme.LimeInk
import io.github.stozo04.openloop.ui.theme.OpenLoopTheme
import io.github.stozo04.openloop.ui.theme.Outline
import io.github.stozo04.openloop.ui.theme.OutlineVariant
import io.github.stozo04.openloop.ui.theme.SurfaceContainer
import io.github.stozo04.openloop.ui.theme.SurfaceContainerHigh
import io.github.stozo04.openloop.ui.theme.TextPrimary
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: OpenLoopViewModel by viewModels {
        // Bridge Context → repositories + media here, once. applicationContext is the long-lived,
        // safe Context to read dataStore / cacheDir / filesDir from; nothing downstream
        // (Factory, ViewModel) ever sees a Context.
        OpenLoopViewModel.Factory(
            UserPreferencesRepositoryImpl(applicationContext.dataStore),
            VideoStorageRepositoryImpl(
                cacheDir = applicationContext.cacheDir,
                filesDir = applicationContext.filesDir,
            ),
            buildVideoProcessor(),
            // ContentResolver lives in the Activity bridge (Lesson 004); the importer holds it, the
            // ViewModel never sees a Context. applicationContext's resolver is process-lived and safe.
            VideoImporterImpl(applicationContext.contentResolver),
        )
    }
    private lateinit var cameraManager: CameraManager

    /**
     * Set when a boomerang share sheet is launched (slice 06); consumed on the next [onResume]. The
     * "Saved — view in gallery" snackbar is deferred until then so it shows when the user is actually
     * back on the camera — not behind the chooser or the share target. (A `withResumed { }` right after
     * startActivity would fire immediately, because the activity is still RESUMED at that point.)
     *
     * Persisted across activity recreation (see [onSaveInstanceState] / [onCreate]) so a rotation or
     * process death while the chooser is on top doesn't drop the deferred "Saved" snackbar — the
     * boomerang is already saved, but the user would otherwise get no confirmation on return.
     */
    private var awaitingShareReturn = false

    // Constructing the @UnstableApi Media3VideoProcessor needs an opt-in; a function-level @OptIn
    // reliably covers its body (a property-delegate annotation doesn't propagate into the
    // `viewModels { … }` lambda where the construction would otherwise live).
    @OptIn(UnstableApi::class)
    private fun buildVideoProcessor(): VideoProcessor =
        Media3VideoProcessor(
            context = applicationContext,
            reverser = VideoReverser(
                scratchDir = File(applicationContext.cacheDir, "scratch/reversed"),
            ),
        )

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    // Permission request contract launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        viewModel.onPermissionsChecked(cameraGranted && audioGranted)
    }

    // Android Photo Picker (slice 07): single-select, VIDEO ONLY, no runtime storage permission.
    // Returns a single Uri? — non-null on pick, null when the user backs out.
    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        viewModel.onVideoPicked(uri)
    }

    /** Open the system photo picker filtered to videos (images are not selectable at the source). */
    private fun importVideo() {
        pickVideoLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate(): hands the system splash to core-splashscreen,
        // which then swaps to postSplashScreenTheme (Theme.OpenLoop) for the app window.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Restore the deferred-share flag after recreation (rotation / process death) so the "Saved"
        // snackbar still fires on the onResume that follows the chooser dismissing.
        awaitingShareReturn = savedInstanceState?.getBoolean(KEY_AWAITING_SHARE_RETURN) == true
        cameraManager = CameraManager(this)

        setContent {
            OpenLoopTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }

                // Drives the friendly "That clip's a bit long" dialog (slice 07); flipped true when an
                // ImportTooLong event arrives and false when the user dismisses it.
                var showTooLongDialog by remember { mutableStateOf(false) }

                // Hoisted out of the (non-composable) collect lambda below — stringResource can only
                // be read in a composable scope.
                val savedMessage = stringResource(R.string.snackbar_saved)
                val viewAction = stringResource(R.string.snackbar_view_action)
                val saveFailedMessage = stringResource(R.string.snackbar_save_failed)
                val importFailedMessage = stringResource(R.string.snackbar_import_failed)
                val undoAction = stringResource(R.string.undo)
                // The "N loops deleted" plural is count-dependent, so we capture resources here (in a
                // composable scope) and resolve the quantity string inside the collect lambda below.
                // LocalResources (not LocalContext.current.resources) so the read is invalidated on a
                // Configuration change (lint LocalContextResourcesRead).
                val resources = LocalResources.current

                // Collect one-shot boomerang events → share sheet + snackbars (the app's only
                // SnackbarHost). `when` stays exhaustive with no `else` (Lesson 014) so a new event
                // must be handled here to compile.
                LaunchedEffect(Unit) {
                    viewModel.events.collect { event ->
                        when (event) {
                            is BoomerangEvent.Share -> launchShareSheet(event.file)
                            BoomerangEvent.Saved -> {
                                // Explicit Short (~4 s) auto-dismiss: with a non-null actionLabel the
                                // Material3 default is Indefinite, which would never time out.
                                val result = snackbarHostState.showSnackbar(
                                    message = savedMessage,
                                    actionLabel = viewAction,
                                    duration = SnackbarDuration.Short,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    viewModel.navigateToGallery()
                                }
                            }
                            BoomerangEvent.Failed -> snackbarHostState.showSnackbar(
                                message = saveFailedMessage,
                            )
                            // Import failed for a non-length reason (slice 07): a light snackbar; the
                            // ViewModel has already returned the user to the gallery.
                            BoomerangEvent.ImportFailed -> snackbarHostState.showSnackbar(
                                message = importFailedMessage,
                            )
                            // Picked clip was too long (slice 07): a friendly dialog reads as guidance,
                            // not an error. The ViewModel has already returned the user to the gallery.
                            BoomerangEvent.ImportTooLong -> showTooLongDialog = true
                            // Loops marked for deletion (Issue #35): show an Undo snackbar. The real
                            // file delete is deferred — Undo restores the tiles, any other dismissal
                            // (timeout, swipe, or a superseding delete) commits the delete to disk.
                            is BoomerangEvent.LoopsDeleted -> {
                                val message = resources.getQuantityString(
                                    R.plurals.gallery_loops_deleted,
                                    event.count,
                                    event.count,
                                )
                                val result = snackbarHostState.showSnackbar(
                                    message = message,
                                    actionLabel = undoAction,
                                    duration = SnackbarDuration.Short,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    viewModel.undoPendingDeletion()
                                } else {
                                    viewModel.commitPendingDeletion()
                                }
                            }
                        }
                    }
                }

                // No Scaffold: every screen draws edge-to-edge and owns its system-bar insets, so a
                // Scaffold's content-padding contract doesn't apply. The SnackbarHost is overlaid
                // directly and floats above the navigation bar.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    OpenLoopNavHost(
                        uiState = uiState,
                        viewModel = viewModel,
                        cameraManager = cameraManager,
                        onCheckPermissions = ::checkPermissions,
                        onRationaleAcknowledged = ::onRationaleAcknowledged,
                        onOpenAppSettings = ::openAppSettings,
                        onImportVideo = ::importVideo,
                    )
                    // App-styled snackbar (single host for Saved / Undo / failures): a rounded
                    // SurfaceContainerHigh card with the Electric-Lime action accent, floating above
                    // the nav bar — matching the app's card + accent language instead of the stock
                    // Material slab. The data overload keeps the action button's a11y wiring intact.
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding(),
                    ) { data ->
                        Snackbar(
                            snackbarData = data,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(16.dp),
                            containerColor = SurfaceContainerHigh,
                            contentColor = TextPrimary,
                            actionColor = ElectricLime,
                            actionContentColor = ElectricLime,
                        )
                    }

                    // Friendly "too long" guidance over the gallery (slice 07).
                    if (showTooLongDialog) {
                        ImportTooLongDialog(onDismiss = { showTooLongDialog = false })
                    }
                }
            }
        }
    }

    private fun checkPermissions() {
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        when {
            allGranted -> viewModel.onPermissionsChecked(true)

            // Denied at least once but not permanently — explain before re-asking.
            requiredPermissions.any { shouldShowRequestPermissionRationale(it) } ->
                viewModel.showPermissionRationale()

            // First request, or permanently denied — the system handles both. A permanent
            // denial returns granted=false from the launcher, routing to PermissionDenied.
            else -> requestPermissionLauncher.launch(requiredPermissions)
        }
    }

    private fun onRationaleAcknowledged() {
        viewModel.onRationaleAcknowledged()
        // Launch the system dialog directly, bypassing checkPermissions(), so we don't
        // re-enter the rationale branch (shouldShowRequestPermissionRationale stays true
        // until the user actually responds to the dialog).
        requestPermissionLauncher.launch(requiredPermissions)
    }

    private fun openAppSettings() {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            startActivity(this)
        }
    }

    /**
     * Pop the Android share sheet for a just-rendered boomerang [file] (slice 06). The file lives in
     * `filesDir/boomerangs/`, exposed by the manifest's FileProvider; [FileProvider.getUriForFile]
     * mints a `content://` URI and the [Intent.FLAG_GRANT_READ_URI_PERMISSION] set in
     * [buildBoomerangShareIntent] grants the chosen receiver temporary read access. We flag
     * [awaitingShareReturn] so the "Saved" snackbar fires on the next [onResume] (when the user is
     * back on the camera), not now (while the chooser is about to cover the screen).
     */
    private fun launchShareSheet(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        awaitingShareReturn = true
        val shareIntent = buildBoomerangShareIntent(uri, getString(R.string.share_subject))
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_chooser_title)))
    }

    override fun onResume() {
        super.onResume()
        // Returned from a share chooser (shared, canceled, or backed out — all the same): now that the
        // user is looking at the camera again, ask the ViewModel to emit the deferred "Saved" snackbar.
        if (awaitingShareReturn) {
            awaitingShareReturn = false
            viewModel.onShareSheetClosed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Survive recreation while the chooser is on top — see [awaitingShareReturn].
        outState.putBoolean(KEY_AWAITING_SHARE_RETURN, awaitingShareReturn)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager.shutdown()
    }
}

/** Key under which [MainActivity.awaitingShareReturn] is persisted across recreation (slice 06). */
private const val KEY_AWAITING_SHARE_RETURN = "openloop.awaitingShareReturn"

/**
 * Build the `ACTION_SEND` intent that shares a rendered boomerang at content [uri] with the given
 * [subject] (slice 06). Extracted as a pure function so the intent's shape (action / MIME type /
 * extras / read-grant flag) is unit-testable without launching the chooser; [subject] is passed in
 * (rather than read from resources here) to keep it Context-free. The caller wraps it in
 * [Intent.createChooser].
 */
fun buildBoomerangShareIntent(uri: Uri, subject: String): Intent =
    Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

/**
 * Stateless navigation host: maps each [OpenLoopUiState] to the screen that renders it. Extracted
 * out of [MainActivity.onCreate]'s `setContent` so the routing can be exercised in a Compose test
 * in isolation (mirrors the project's extract-for-testability pattern, e.g. `OnboardingNavigation`).
 *
 * The `when` is deliberately EXHAUSTIVE with no `else` branch. [OpenLoopUiState] is a sealed
 * interface (PRD Decision Log #1) precisely so the compiler forces every state to be handled here;
 * an `else` would defeat that and let an unrouted state (e.g. [OpenLoopUiState.Processing]) silently
 * fall through to a bare [CameraScreen]. Adding a new state must fail to compile until it is routed —
 * do not reintroduce an `else`.
 *
 * Activity-bound side effects (launching the permission dialog, opening app settings) are passed in
 * as lambdas so this composable stays free of any [ComponentActivity] reference.
 */
@Composable
fun OpenLoopNavHost(
    uiState: OpenLoopUiState,
    viewModel: OpenLoopViewModel,
    cameraManager: CameraManager,
    onCheckPermissions: () -> Unit,
    onRationaleAcknowledged: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onImportVideo: () -> Unit,
) {
    // Auto-trigger permission check when state reaches CheckingPermissions (from either
    // Initializing→CheckingPermissions for returning users, or Onboarding→CheckingPermissions
    // for first-time users).
    LaunchedEffect(uiState) {
        if (uiState is OpenLoopUiState.CheckingPermissions) {
            onCheckPermissions()
        }
    }

    when (uiState) {
        is OpenLoopUiState.Initializing -> {
            InfinityLoadingScreen()
        }
        is OpenLoopUiState.Onboarding -> {
            OnboardingScreen(
                onGetStartedClick = { viewModel.onOnboardingCompleted() }
            )
        }
        is OpenLoopUiState.CheckingPermissions -> {
            InfinityLoadingScreen()
        }
        is OpenLoopUiState.PermissionRationale -> {
            PermissionExplanationScreen(
                title = "We need a quick permission",
                body = "OpenLoop needs Camera and Audio to capture your " +
                    "video loops. Tap Grant to continue.",
                primaryActionLabel = "Grant Permissions",
                onPrimaryAction = { onRationaleAcknowledged() },
                secondaryActionLabel = "Not now",
                onSecondaryAction = { viewModel.onRationaleDeclined() }
            )
        }
        is OpenLoopUiState.PermissionDenied -> {
            PermissionExplanationScreen(
                title = "Permissions Required",
                body = "OpenLoop needs Camera and Audio recording permissions " +
                    "to capture high-quality speed-controlled video loops.",
                primaryActionLabel = "Try Again",
                onPrimaryAction = { onCheckPermissions() },
                secondaryActionLabel = "Open Device Settings",
                onSecondaryAction = { onOpenAppSettings() }
            )
        }
        // ReadyToCapture and Recording MUST share this single call site (Lesson 012). Two separate
        // branches make Compose dispose+rebuild CameraScreen on the start/stop transition, which
        // re-runs its startCamera() effect, calls unbindAll(), and kills the in-flight recording
        // (ERROR_SOURCE_INACTIVE). CameraScreenHost keeps one CameraScreen instance alive across both.
        is OpenLoopUiState.ReadyToCapture,
        is OpenLoopUiState.Recording -> {
            CameraScreenHost(uiState) {
                CameraScreen(
                    viewModel = viewModel,
                    cameraManager = cameraManager
                )
            }
        }
        is OpenLoopUiState.Trim -> {
            TrimScreen(viewModel = viewModel)
        }
        is OpenLoopUiState.BoomerangEditor -> {
            BoomerangEditorScreen(viewModel = viewModel)
        }
        is OpenLoopUiState.Processing -> {
            // Swallow Back during the render. At target 36 predictive back is default-on and the
            // platform's fallback for an unhandled back is "finish the Activity" — which here would
            // tear down the in-flight Transformer encode, discarding the boomerang (and orphaning the
            // already-promoted raw) with no prompt (Lesson 015). There is no partial render to salvage
            // and no cancel-to-editor path wired, so the deliberate decision is to ignore Back for the
            // few seconds the encode runs; it routes itself onward (success → camera/gallery, failure →
            // editor) without user input.
            BackHandler { /* intentionally ignored: render in flight, don't finish the Activity */ }
            // Render progress drives the spinner caption; read via a lambda so only the percentage
            // text recomposes as progress ticks (Lesson 016).
            val progress = viewModel.renderProgress.collectAsStateWithLifecycle()
            ProcessingScreen(progress = { progress.value })
        }
        // Probing + copying a picked library video (slice 07): a neutral loader, never the
        // camera-bound screen (Lessons 012/014).
        is OpenLoopUiState.ImportingVideo -> {
            // Same rationale as Processing: swallow Back so a predictive-back gesture can't finish the
            // Activity mid-copy — that would cancel the viewModelScope copy and leave a partial scratch
            // file behind (reclaimed later by the D-8 prune, but still a needless orphan). The import
            // routes itself to Trim (success) or Gallery (too-long / failure) without user input.
            BackHandler { /* intentionally ignored: import copy in flight, don't finish the Activity */ }
            InfinityLoadingScreen()
        }
        is OpenLoopUiState.Gallery -> {
            GalleryScreen(
                viewModel = viewModel,
                onBackClick = { viewModel.navigateBackFromGallery() },
                onImportVideo = onImportVideo,
            )
        }
    }
}

/**
 * Loading screen shown during app init / permission checks. Renders the same neon infinity as
 * the launcher icon and system splash, on a matching black field, so the system splash hands off
 * to this screen with no visible seam. Static by design — no artificial hold, so the loader only
 * shows for the natural (sub-second) init window and the user gets straight into the app.
 */
@Composable
fun InfinityLoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "Loading",
            modifier = Modifier.size(200.dp)
        )
    }
}

/**
 * Educational permission screen reused for both the rationale step (before re-asking) and the
 * permanent-denial step. The optional secondary action is "Not now" (cancel) on the rationale
 * variant and "Open Device Settings" on the denial variant; omit both [secondaryActionLabel] and
 * [onSecondaryAction] to render only the primary button.
 */
@Composable
fun PermissionExplanationScreen(
    title: String,
    body: String,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(SurfaceContainer, Canvas)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(SurfaceContainerHigh)
                .border(1.dp, OutlineVariant, RoundedCornerShape(24.dp))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(CoralRed.copy(alpha = 0.12f))
                    .border(2.dp, CoralRed, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "!",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = CoralRed
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = body,
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onPrimaryAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricLime
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = primaryActionLabel,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = LimeInk
                )
            }

            if (secondaryActionLabel != null && onSecondaryAction != null) {
                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onSecondaryAction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .border(1.dp, Outline, RoundedCornerShape(12.dp))
                ) {
                    Text(
                        text = secondaryActionLabel,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
            }
        }
    }
}

/**
 * Friendly "That clip's a bit long" dialog shown when an imported library video exceeds the 30 s
 * limit (slice 07). Hand-rolled in the app's neon aesthetic (matching [PermissionExplanationScreen]
 * and the gallery overlay) rather than a stock Material3 `AlertDialog`, so it reads as warm guidance,
 * not a system error. Acknowledgment-only — the user is already back on the gallery and nothing was
 * copied; the single "Got it" button just dismisses.
 */
@Composable
fun ImportTooLongDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(SurfaceContainerHigh)
                .border(1.dp, OutlineVariant, RoundedCornerShape(24.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(ElectricLime.copy(alpha = 0.12f))
                    .border(2.dp, ElectricLime, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Timer,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    tint = ElectricLime
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.import_too_long_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.import_too_long_body),
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = ElectricLime),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = stringResource(R.string.import_too_long_button),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = LimeInk
                )
            }
        }
    }
}

