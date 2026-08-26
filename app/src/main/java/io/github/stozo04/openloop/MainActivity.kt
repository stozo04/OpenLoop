package io.github.stozo04.openloop

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.annotation.StringRes
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import io.github.stozo04.openloop.camera.CameraManager
import io.github.stozo04.openloop.camera.lens.ViewFlick
import io.github.stozo04.openloop.data.UserPreferencesRepositoryImpl
import io.github.stozo04.openloop.data.VideoImporterImpl
import io.github.stozo04.openloop.data.VideoStorageRepositoryImpl
import io.github.stozo04.openloop.data.dataStore
import io.github.stozo04.openloop.diagnostics.FirebaseAnalyticsReporterImpl
import io.github.stozo04.openloop.diagnostics.shareDebugReport
import io.github.stozo04.openloop.media.MediaComponents
import io.github.stozo04.openloop.work.WorkManagerBoomerangRenderScheduler
import io.github.stozo04.openloop.work.publishImageToPhotos
import io.github.stozo04.openloop.ui.BoomerangEditorScreen
import io.github.stozo04.openloop.ui.BoomerangEvent
import io.github.stozo04.openloop.ui.CameraScreen
import io.github.stozo04.openloop.ui.CameraScreenHost
import io.github.stozo04.openloop.ui.GalleryScreen
import io.github.stozo04.openloop.ui.MemoryPressure
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
import io.github.stozo04.openloop.review.EXTRA_DEMO_REVIEW
import io.github.stozo04.openloop.review.launchInAppReview
import io.github.stozo04.openloop.update.AppUpdateController
import io.github.stozo04.openloop.update.EXTRA_DEMO_UPDATE
import io.github.stozo04.openloop.update.demoAppUpdateManager
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    @OptIn(UnstableApi::class)
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
            MediaComponents.buildVideoProcessor(applicationContext),
            // ContentResolver lives in the Activity bridge (Lesson 004); the importer holds it, the
            // ViewModel never sees a Context. applicationContext's resolver is process-lived and safe.
            VideoImporterImpl(applicationContext),
            WorkManagerBoomerangRenderScheduler(WorkManager.getInstance(applicationContext)),
            // Firebase Analytics reporter — falls back to NoOpAnalyticsReporter when
            // google-services.json is absent (CI / fresh clone). See
            // AnalyticsReporter wired here; see AnalyticsReporter.kt and docs/play-store/data-safety.md.
            FirebaseAnalyticsReporterImpl.create(applicationContext),
            // Proactive low-memory probe (ActivityManager.getMemoryInfo). Android 14+ delivers no
            // foreground onTrimMemory pressure levels, so the ViewModel polls this at editor entry
            // and before applying a non-Original look (editor-memory-oom WS-3, PR #58 review).
            isLowMemoryNow = MemoryPressure.lowMemoryProbe(applicationContext),
            // Photo-mode stills → the device's public image library. Bridged here (the same
            // Context-free seam as isLowMemoryNow) so the ViewModel never sees a Context —
            // Lesson 004 / docs/PRD-photo-capture.md §5.5.
            publishPhotoToLibrary = { file -> publishImageToPhotos(applicationContext, file) },
        )
    }
    private lateinit var cameraManager: CameraManager

    /**
     * Activity-level flick capture — the deepest of the three layers
     * (`docs/PRD-lens-interactions.md` §3.1). [dispatchTouchEvent] is the first code in the app
     * the framework hands ANY window touch to, before Compose and before any view. Seven
     * instrumented Fold logcats (2026-08-26) drove it down here: viewfinder touches reached
     * neither `PinchZoomLayout` nor the Compose pointer probe, and a `GestureDetector` at this
     * level still produced nothing while a plain button tap demonstrably arrived. So the
     * classification is now done by hand — a raw [android.view.VelocityTracker], no opaque
     * detector, no silent path: every DOWN and every UP logs with its measured velocity, and an
     * UP that was single-finger and faster than the platform's fling minimum IS a flick.
     * Observe-only (never consumes); `CameraManager.flickLens` no-ops when no camera is bound
     * (editor flings die there) and debounces duplicates when another capture layer also
     * delivers the same gesture.
     */
    private var flickVelocityTracker: android.view.VelocityTracker? = null

    /** A stream that ever grew a second finger is a pinch; the flick capture sits it out. */
    private var windowStreamSawMultiTouch = false

    private var windowDownX = 0f
    private var windowDownY = 0f

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                windowStreamSawMultiTouch = false
                windowDownX = ev.x
                windowDownY = ev.y
                flickVelocityTracker?.recycle()
                flickVelocityTracker = android.view.VelocityTracker.obtain().also { it.addMovement(ev) }
                Log.i(FLICK_TAG, "touch DOWN at (${ev.x}, ${ev.y})")
            }
            MotionEvent.ACTION_POINTER_DOWN -> windowStreamSawMultiTouch = true
            MotionEvent.ACTION_MOVE -> flickVelocityTracker?.addMovement(ev)
            MotionEvent.ACTION_UP -> {
                val tracker = flickVelocityTracker
                if (tracker != null) {
                    tracker.addMovement(ev)
                    tracker.computeCurrentVelocity(1000)
                    val velocityX = tracker.xVelocity
                    val velocityY = tracker.yVelocity
                    val speed = kotlin.math.hypot(velocityX, velocityY)
                    val minFling =
                        android.view.ViewConfiguration.get(this).scaledMinimumFlingVelocity.toFloat()
                    Log.i(
                        FLICK_TAG,
                        "touch UP at (${ev.x}, ${ev.y}) v=($velocityX, $velocityY) " +
                            "speed=$speed min=$minFling multi=$windowStreamSawMultiTouch",
                    )
                    if (!windowStreamSawMultiTouch && speed >= minFling &&
                        ::cameraManager.isInitialized
                    ) {
                        // The viewfinder fills the window edge-to-edge, so window coordinates
                        // ARE (to within insets the huge quad shrugs off) the layout's own.
                        val decor = window.decorView
                        cameraManager.flickLens(
                            ViewFlick(
                                downX = windowDownX,
                                downY = windowDownY,
                                velocityX = velocityX,
                                velocityY = velocityY,
                                viewWidth = decor.width.toFloat(),
                                viewHeight = decor.height.toFloat(),
                            ),
                        )
                    }
                    tracker.recycle()
                    flickVelocityTracker = null
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                flickVelocityTracker?.recycle()
                flickVelocityTracker = null
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Play in-app updates (FLEXIBLE). Built in [onCreate]; released in [onDestroy]. */
    private lateinit var appUpdateController: AppUpdateController

    /** Play in-app reviews. Lazy — `applicationContext` isn't ready at field init. */
    private val reviewManager: ReviewManager by lazy {
        ReviewManagerFactory.create(applicationContext)
    }

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

    /**
     * Share event received while the Activity was not in the foreground (Issue #40). Launched from
     * [onResume] once the app is visible — never from the Worker (BAL restrictions).
     */
    private var deferredShareFile: File? = null

    /** Whether [deferredShareFile] should trigger the post-save "Saved" snackbar on return (slice 06). */
    private var deferredShareShowSavedSnackbar = true

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        viewModel.onPermissionsChecked(grants.values.all { it })
    }

    /**
     * Play FLEXIBLE in-app update flow. Registered as a property so it's wired before `STARTED`,
     * per the Activity Result API contract. A non-OK result just means the user declined or Play
     * failed — nothing else in the app depends on it.
     */
    private val appUpdateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            Log.w(TAG, "In-app update flow declined or failed: resultCode=${result.resultCode}")
        }
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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Exact-match the legacy *foreground pressure* levels only (delivered on API <= 33; never
        // delivered on 34+ — see MemoryPressure). UI_HIDDEN/BACKGROUND are lifecycle signals that
        // fire on every routine backgrounding and must NOT degrade the editor (PR #58 review FAIL:
        // the previous `>=` comparison matched them). Android 14+ foreground pressure is covered by
        // the MemoryPressure.lowMemoryProbe injected into the ViewModel Factory below.
        if (MemoryPressure.isForegroundPressureLevel(level)) {
            viewModel.onTrimMemory()
        }
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
        deferredShareFile = savedInstanceState?.getString(KEY_DEFERRED_SHARE_PATH)?.let { File(it) }
        deferredShareShowSavedSnackbar =
            savedInstanceState?.getBoolean(KEY_DEFERRED_SHARE_SHOW_SAVED, true) != false
        cameraManager = CameraManager(this)
        appUpdateController = AppUpdateController(
            // Debug builds launched with --ez openloop.demoUpdate true drive a fake Play so the
            // "Update ready" snackbar is viewable on an emulator; R8 strips this from release.
            appUpdateManager = if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DEMO_UPDATE, false)) {
                demoAppUpdateManager(applicationContext, lifecycleScope)
            } else {
                AppUpdateManagerFactory.create(applicationContext)
            },
            launcher = appUpdateLauncher,
        )
        // Same escape hatch for reviews: --ez openloop.demoReview true makes every save ask, so the
        // cadence can be exercised on an install whose lifetime counter is long past it.
        viewModel.forceReviewAsk =
            BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DEMO_REVIEW, false)

        setContent {
            OpenLoopTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }

                // Friendly "That clip's a bit long" dialog (slice 07); held in the ViewModel so it
                // survives Activity recreation after the Photo Picker returns.
                val showTooLongDialog by viewModel.showImportTooLongDialog.collectAsStateWithLifecycle()
                // Its "a bit short" sibling (issue #95 follow-up) — same recreation rationale.
                val showTooShortDialog by viewModel.showImportTooShortDialog.collectAsStateWithLifecycle()

                // Hoisted out of the (non-composable) collect lambda below — stringResource can only
                // be read in a composable scope.
                val savedMessage = stringResource(R.string.snackbar_saved)
                val viewAction = stringResource(R.string.snackbar_view_action)
                val saveFailedMessage = stringResource(R.string.snackbar_save_failed)
                val saveFailedReportAction = stringResource(R.string.snackbar_save_failed_report_action)
                val reversePreviewForwardMessage = stringResource(R.string.snackbar_reverse_preview_forward)
                val reversePreviewReportAction = stringResource(R.string.snackbar_reverse_preview_report_action)
                val importFailedMessage = stringResource(R.string.snackbar_import_failed)
                val captureTooShortMessage = stringResource(R.string.snackbar_capture_too_short)
                val photoCaptureFailedMessage = stringResource(R.string.snackbar_photo_capture_failed)
                val undoAction = stringResource(R.string.undo)
                val updateReadyMessage = stringResource(R.string.update_ready_message)
                val updateReadyAction = stringResource(R.string.update_ready_action)
                // The "N loops deleted" plural is count-dependent, so we capture resources here (in a
                // composable scope) and resolve the quantity string inside the collect lambda below.
                // LocalResources (not LocalContext.current.resources) so the read is invalidated on a
                // Configuration change (lint LocalContextResourcesRead).
                val resources = LocalResources.current

                val lifecycleOwner = LocalLifecycleOwner.current

                // Play fires the "downloaded" callback off a listener, not a coroutine, so the
                // closure needs a scope to drive the suspending showSnackbar. Also re-checks here
                // because onResume runs before the first composition (callback still null then).
                val updateScope = rememberCoroutineScope()
                LaunchedEffect(Unit) {
                    appUpdateController.onUpdateDownloaded = {
                        updateScope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = updateReadyMessage,
                                actionLabel = updateReadyAction,
                                // Restart-required: never auto-dismiss.
                                duration = SnackbarDuration.Indefinite,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                appUpdateController.completeUpdate()
                            }
                        }
                    }
                    appUpdateController.check()
                }

                // Collect one-shot boomerang events → share sheet + snackbars (the app's only
                // SnackbarHost). `when` stays exhaustive with no `else` (Lesson 014) so a new event
                // must be handled here to compile.
                LaunchedEffect(Unit) {
                    viewModel.events.collect { event ->
                        when (event) {
                            is BoomerangEvent.Share -> deliverShareSheet(
                                file = event.file,
                                lifecycle = lifecycleOwner.lifecycle,
                                showSavedSnackbarAfterDismiss = event.showSavedSnackbarAfterDismiss,
                            )
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
                            // Save/render failure: mirror the preview-fallback pattern — friendly
                            // copy + "Send debug report" when a report is available (spec §5.6).
                            is BoomerangEvent.SaveFailed -> {
                                val report = event.supportReport
                                val result = snackbarHostState.showSnackbar(
                                    message = saveFailedMessage,
                                    actionLabel = if (!report.isNullOrBlank()) saveFailedReportAction else null,
                                    duration = SnackbarDuration.Long,
                                )
                                if (result == SnackbarResult.ActionPerformed && !report.isNullOrBlank()) {
                                    shareDebugReport(
                                        report = report,
                                        subject = "OpenLoop loop feedback",
                                        chooserTitle = saveFailedReportAction,
                                    )
                                }
                            }
                            is BoomerangEvent.ReversePreviewFallbackForward -> {
                                val report = event.supportReport
                                val result = snackbarHostState.showSnackbar(
                                    message = reversePreviewForwardMessage,
                                    // Offer the report action only when we actually have a report to send.
                                    actionLabel = if (!report.isNullOrBlank()) reversePreviewReportAction else null,
                                    duration = SnackbarDuration.Long,
                                )
                                if (result == SnackbarResult.ActionPerformed && !report.isNullOrBlank()) {
                                    shareDebugReport(
                                        report = report,
                                        subject = "OpenLoop loop feedback",
                                        chooserTitle = reversePreviewReportAction,
                                    )
                                }
                            }
                            // Import failed for a non-length reason (slice 07): a light snackbar; the
                            // ViewModel has already returned the user to the gallery.
                            BoomerangEvent.ImportFailed -> snackbarHostState.showSnackbar(
                                message = importFailedMessage,
                            )
                            // Picked clip was too long (slice 07): dialog is driven by
                            // [OpenLoopViewModel.showImportTooLongDialog] so it survives Activity
                            // recreation after the Photo Picker closes.
                            BoomerangEvent.ImportTooLong -> Unit
                            // Capture stopped before the minimum loopable length (issue #95
                            // follow-up): the ViewModel already discarded the scratch and returned
                            // to the viewfinder — nudge instead of failing silently.
                            BoomerangEvent.CaptureTooShort -> snackbarHostState.showSnackbar(
                                message = captureTooShortMessage,
                            )
                            // Photo-mode shutter tapped before the preview had a frame, or the JPEG
                            // write failed. Nothing was saved and the user is still on the
                            // viewfinder — nudge them to tap again.
                            BoomerangEvent.PhotoCaptureFailed -> snackbarHostState.showSnackbar(
                                message = photoCaptureFailedMessage,
                            )
                            // Third saved loop, chooser just dismissed. This suspends for the card's
                            // whole lifecycle, which is what keeps the Saved snackbar behind it —
                            // see [BoomerangEvent.RequestReview]. `isIdle` reads the state live, so
                            // a recording started during Play's round trip cancels the ask.
                            BoomerangEvent.RequestReview -> launchInAppReview(
                                manager = reviewManager,
                                activity = this@MainActivity,
                                isIdle = { viewModel.isIdleForReview },
                            )
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
                            shape = MaterialTheme.shapes.medium,
                            containerColor = SurfaceContainerHigh,
                            contentColor = TextPrimary,
                            actionColor = ElectricLime,
                            actionContentColor = ElectricLime,
                        )
                    }

                    // Friendly "too long" guidance over the gallery (slice 07).
                    if (showTooLongDialog) {
                        ImportTooLongDialog(onDismiss = { viewModel.dismissImportTooLongDialog() })
                    }
                    // Friendly "too short" guidance over the gallery (issue #95 follow-up).
                    if (showTooShortDialog) {
                        ImportTooShortDialog(onDismiss = { viewModel.dismissImportTooShortDialog() })
                    }
                }
            }
        }
    }

    private fun checkPermissions() {
        val missing = requiredCapturePermissions(Build.VERSION.SDK_INT).filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        when {
            missing.isEmpty() -> viewModel.onPermissionsChecked(true)

            // Denied at least once but not permanently — explain before re-asking.
            missing.any(::shouldShowRequestPermissionRationale) ->
                viewModel.showPermissionRationale()

            // First request, or permanently denied — the system handles both. A permanent
            // denial returns granted=false from the launcher, routing to PermissionDenied.
            else -> requestPermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    private fun onRationaleAcknowledged() {
        viewModel.onRationaleAcknowledged()
        // Launch the system dialog directly, bypassing checkPermissions(), so we don't
        // re-enter the rationale branch (shouldShowRequestPermissionRationale stays true
        // until the user actually responds to the dialog).
        val missing = requiredCapturePermissions(Build.VERSION.SDK_INT).filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            viewModel.onPermissionsChecked(true)
        } else {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    private fun openAppSettings() {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            startActivity(this)
        }
    }

    /**
     * Pop the Android share sheet for a rendered loop `file` (slice 06). The file lives in
     * `filesDir/videos/`, exposed by the manifest's FileProvider; [FileProvider.getUriForFile]
     * mints a `content://` URI and [buildBoomerangShareIntent] grants temporary read access via
     * [Intent.FLAG_GRANT_READ_URI_PERMISSION] **and** [ClipData] (so Samsung/system ChooserActivity
     * can peek a preview — EXTRA_STREAM alone is not copied onto the chooser Intent). We flag
     * [awaitingShareReturn] so the "Saved" snackbar fires on the next [onResume] (when the user is
     * back on the camera), not now (while the chooser is about to cover the screen).
     */
    /**
     * Launch the share sheet when the Activity is foregrounded; otherwise defer to [onResume]
     * (Google BAL — Issue #40).
     */
    private fun deliverShareSheet(
        file: File,
        lifecycle: Lifecycle,
        showSavedSnackbarAfterDismiss: Boolean = true,
    ) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            launchShareSheet(file, showSavedSnackbarAfterDismiss)
        } else {
            deferredShareFile = file
            deferredShareShowSavedSnackbar = showSavedSnackbarAfterDismiss
        }
    }

    private fun launchShareSheet(file: File, showSavedSnackbarAfterDismiss: Boolean = true) {
        // FileProvider exposes filesDir/videos/ (slice 06) — scratch/cache paths must never reach here.
        if (!file.path.contains("${File.separator}videos${File.separator}")) {
            Log.w(TAG, "Refusing to share file outside videos/: ${file.path}")
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        if (showSavedSnackbarAfterDismiss) {
            awaitingShareReturn = true
        }
        // Chooser copy is kind-neutral (like snackbar_saved) — only the MIME type has to know
        // whether this is a still or a loop (docs/PRD-photo-capture.md §5.6).
        val shareIntent = buildBoomerangShareIntent(
            uri = uri,
            subject = getString(R.string.share_subject),
            mimeType = shareMimeType(file),
        )
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_chooser_title)))
    }

    override fun onResume() {
        super.onResume()
        // Re-surface the "Update ready" prompt if a Play download finished while we were
        // backgrounded (Google's recommended stalled-update handling).
        appUpdateController.check()
        deferredShareFile?.let { file ->
            val showSaved = deferredShareShowSavedSnackbar
            deferredShareFile = null
            launchShareSheet(file, showSaved)
            return
        }
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
        deferredShareFile?.absolutePath?.let { outState.putString(KEY_DEFERRED_SHARE_PATH, it) }
        outState.putBoolean(KEY_DEFERRED_SHARE_SHOW_SAVED, deferredShareShowSavedSnackbar)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager.shutdown()
        appUpdateController.detach()
    }
}

/** Key under which [MainActivity.awaitingShareReturn] is persisted across recreation (slice 06). */
private const val KEY_AWAITING_SHARE_RETURN = "openloop.awaitingShareReturn"

/** Key under which [MainActivity.deferredShareFile] is persisted across recreation (Issue #40). */
private const val KEY_DEFERRED_SHARE_PATH = "openloop.deferredSharePath"

/** Key under which [MainActivity.deferredShareShowSavedSnackbar] is persisted across recreation. */
private const val KEY_DEFERRED_SHARE_SHOW_SAVED = "openloop.deferredShareShowSaved"

private const val TAG = "MainActivity"

/** The activity-level flick capture's own tag, so one logcat filter shows the whole chain. */
private const val FLICK_TAG = "OpenLoopFlick"

/** Storage permission is needed only on Android 9 and lower when publishing to MediaStore. */
internal fun requiredCapturePermissions(sdkInt: Int): List<String> = buildList {
    add(Manifest.permission.CAMERA)
    if (sdkInt <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
}

/**
 * MIME type to advertise when sharing [file] — `image/jpeg` for a photo-mode still (written as
 * `photo_<ts>.jpg` by `VideoStorageRepositoryImpl`), `video/mp4` for everything else. Pure and
 * JVM-testable: the previous hard-coded `video/mp4` would have offered a JPEG to video-only targets
 * and broken photo sharing outright (docs/PRD-photo-capture.md §5.6).
 */
internal fun shareMimeType(file: File): String =
    if (file.extension.equals("jpg", ignoreCase = true)) "image/jpeg" else "video/mp4"

/**
 * Build the `ACTION_SEND` intent that shares a rendered boomerang at content [uri] with the given
 * [subject] (slice 06). Extracted as a pure function so the intent's shape (action / MIME type /
 * extras / ClipData / read-grant flag) is unit-testable without launching the chooser; [subject] is
 * passed in (rather than read from resources here) to keep it Context-free. The caller wraps it in
 * [Intent.createChooser].
 *
 * [ClipData] is required for sharesheet previews: [Intent.createChooser] copies ClipData (and its
 * URI grants) onto the system chooser Intent, but does **not** copy [Intent.EXTRA_STREAM]. Without
 * ClipData, Samsung ChooserActivity (uid 1000) hits
 * `SecurityException: … grantUriPermission()` when peeking the FileProvider URI (SM-G985F log
 * 2026-08-07) even though the eventual receiver would still get the stream.
 *
 * @see <a href="https://developer.android.com/reference/androidx/core/content/FileProvider">FileProvider</a>
 */
fun buildBoomerangShareIntent(uri: Uri, subject: String, mimeType: String = "video/mp4"): Intent =
    Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        clipData = ClipData.newRawUri(subject, uri)
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
                title = stringResource(R.string.permission_rationale_title),
                body = stringResource(R.string.permission_rationale_body),
                primaryActionLabel = stringResource(R.string.permission_grant),
                onPrimaryAction = { onRationaleAcknowledged() },
                secondaryActionLabel = stringResource(R.string.permission_not_now),
                onSecondaryAction = { viewModel.onRationaleDeclined() }
            )
        }
        is OpenLoopUiState.PermissionDenied -> {
            PermissionExplanationScreen(
                title = stringResource(R.string.permission_denied_title),
                body = stringResource(R.string.permission_denied_body),
                primaryActionLabel = stringResource(R.string.permission_try_again),
                onPrimaryAction = { onCheckPermissions() },
                secondaryActionLabel = stringResource(R.string.permission_open_settings),
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
            contentDescription = stringResource(R.string.loading_content_description),
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
                .clip(MaterialTheme.shapes.large)
                .background(SurfaceContainerHigh)
                .border(1.dp, OutlineVariant, MaterialTheme.shapes.large)
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
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onPrimaryAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricLime
                ),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = primaryActionLabel,
                    style = MaterialTheme.typography.labelLarge,
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
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .border(1.dp, Outline, MaterialTheme.shapes.medium)
                ) {
                    Text(
                        text = secondaryActionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White
                    )
                }
            }
        }
    }
}

/**
 * Friendly "That clip's a bit long" dialog shown when an imported library video exceeds the 30 s
 * limit (slice 07). Acknowledgment-only — the user is already back on the gallery and nothing was
 * copied; the single "Got it" button just dismisses.
 */
@Composable
fun ImportTooLongDialog(onDismiss: () -> Unit) {
    ImportClipLengthDialog(
        titleRes = R.string.import_too_long_title,
        bodyRes = R.string.import_too_long_body,
        onDismiss = onDismiss,
    )
}

/**
 * The "a bit short" sibling: shown when a picked clip is under the minimum loopable window
 * ([io.github.stozo04.openloop.ui.OpenLoopViewModel.MIN_TRIM_DURATION] — issue #95 follow-up).
 * Same acknowledgment-only contract as [ImportTooLongDialog].
 */
@Composable
fun ImportTooShortDialog(onDismiss: () -> Unit) {
    ImportClipLengthDialog(
        titleRes = R.string.import_too_short_title,
        bodyRes = R.string.import_too_short_body,
        onDismiss = onDismiss,
    )
}

/**
 * Shared friendly clip-length guidance dialog (too long / too short). Hand-rolled in the app's neon
 * aesthetic (matching [PermissionExplanationScreen] and the gallery overlay) rather than a stock
 * Material3 `AlertDialog`, so it reads as warm guidance, not a system error.
 */
@Composable
private fun ImportClipLengthDialog(
    @StringRes titleRes: Int,
    @StringRes bodyRes: Int,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(SurfaceContainerHigh)
                .border(1.dp, OutlineVariant, MaterialTheme.shapes.large)
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
                text = stringResource(titleRes),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = ElectricLime),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = stringResource(R.string.dialog_got_it),
                    style = MaterialTheme.typography.labelLarge,
                    color = LimeInk
                )
            }
        }
    }
}
