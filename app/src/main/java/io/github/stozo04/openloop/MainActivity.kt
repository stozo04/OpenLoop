package io.github.stozo04.openloop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.OptIn
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
import androidx.compose.ui.platform.LocalContext
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
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import io.github.stozo04.openloop.camera.CameraManager
import io.github.stozo04.openloop.data.UserPreferencesRepositoryImpl
import io.github.stozo04.openloop.data.VideoImporterImpl
import io.github.stozo04.openloop.data.VideoStorageRepositoryImpl
import io.github.stozo04.openloop.data.dataStore
import io.github.stozo04.openloop.diagnostics.FirebaseAnalyticsReporterImpl
import io.github.stozo04.openloop.diagnostics.shareDebugReport
import io.github.stozo04.openloop.media.MediaComponents
import io.github.stozo04.openloop.work.WorkManagerBoomerangRenderScheduler
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
import io.github.stozo04.openloop.update.AppUpdateController
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
            // docs/active/firebase-analytics/IMPLEMENTATION.md for the staged rollout.
            FirebaseAnalyticsReporterImpl.create(applicationContext),
            // Proactive low-memory probe (ActivityManager.getMemoryInfo). Android 14+ delivers no
            // foreground onTrimMemory pressure levels, so the ViewModel polls this at editor entry
            // and before applying a non-Original look (editor-memory-oom WS-3, PR #58 review).
            isLowMemoryNow = MemoryPressure.lowMemoryProbe(applicationContext),
        )
    }
    private lateinit var cameraManager: CameraManager

    /**
     * Play In-App Updates controller (FLEXIBLE flow). Constructed in [onCreate] once the launcher
     * below is registered. See `docs/active/in-app-updates/IMPLEMENTATION.md` for the design and
     * the verification plan (T1 unit, T2 fake, T3 Internal App Sharing, T4 internal track).
     */
    private lateinit var appUpdateController: AppUpdateController

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

    private val requestPostNotificationsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted or denied — export continues either way; notification is best-effort */ }

    /**
     * Play In-App Updates flow launcher (FLEXIBLE — non-blocking). Registered here as a class
     * property so it's wired before `STARTED`, per the Activity Result API contract; the launched
     * intent is owned by Play and shows its own confirmation dialog. A non-OK result just means
     * the user declined or Play failed — nothing else in the app depends on it.
     */
    private val appUpdateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode != AppUpdateController.RESULT_OK) {
            Log.w(TAG, "In-app update flow declined or failed: resultCode=${result.resultCode}")
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onPermissionsChecked(granted)
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

        // Wire the Play In-App Updates controller (FLEXIBLE flow). The Activity owns the
        // ActivityResultLauncher above; the controller owns the AppUpdateManager + listener.
        // onUpdateDownloaded is set inside setContent below, after snackbarHostState exists.
        appUpdateController = AppUpdateController(
            appUpdateManager = AppUpdateManagerFactory.create(applicationContext),
        ).apply { attach(appUpdateLauncher) }

        setContent {
            OpenLoopTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }

                // Friendly "That clip's a bit long" dialog (slice 07); held in the ViewModel so it
                // survives Activity recreation after the Photo Picker returns.
                val showTooLongDialog by viewModel.showImportTooLongDialog.collectAsStateWithLifecycle()

                // Hoisted out of the (non-composable) collect lambda below — stringResource can only
                // be read in a composable scope.
                val savedMessage = stringResource(R.string.snackbar_saved)
                val viewAction = stringResource(R.string.snackbar_view_action)
                val saveFailedMessage = stringResource(R.string.snackbar_save_failed)
                val saveFailedReportAction = stringResource(R.string.snackbar_save_failed_report_action)
                val reversePreviewForwardMessage = stringResource(R.string.snackbar_reverse_preview_forward)
                val reversePreviewReportAction = stringResource(R.string.snackbar_reverse_preview_report_action)
                val importFailedMessage = stringResource(R.string.snackbar_import_failed)
                val undoAction = stringResource(R.string.undo)
                // In-app update prompt copy (read in composable scope so the resource lookup is
                // invalidated on configuration change).
                val updateReadyMessage = stringResource(R.string.update_ready_message)
                val updateReadyAction = stringResource(R.string.update_ready_action)
                // The "N loops deleted" plural is count-dependent, so we capture resources here (in a
                // composable scope) and resolve the quantity string inside the collect lambda below.
                // LocalResources (not LocalContext.current.resources) so the read is invalidated on a
                // Configuration change (lint LocalContextResourcesRead).
                val resources = LocalResources.current

                val lifecycleOwner = LocalLifecycleOwner.current

                // Coroutine scope for the in-app update snackbar — onUpdateDownloaded fires from a
                // Play listener callback (not a coroutine), so we need a scope to drive the
                // suspend showSnackbar. rememberCoroutineScope is tied to the composition's
                // lifetime; it cancels with the Activity.
                val updateSnackbarScope = rememberCoroutineScope()

                LaunchedEffect(Unit) {
                    viewModel.requestPostNotifications.collect {
                        maybeRequestPostNotificationsPermission()
                    }
                }

                // Wire the in-app update prompt and fire the cold-start check exactly once.
                // onUpdateDownloaded fires from a Play install-state callback (not a coroutine),
                // so the closure dispatches via updateSnackbarScope into the host's suspend queue.
                // Indefinite + action: restart-required, never auto-dismiss. The styled
                // SnackbarHost below renders this with the Electric-Lime accent.
                LaunchedEffect(Unit) {
                    appUpdateController.onUpdateDownloaded = {
                        updateSnackbarScope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = updateReadyMessage,
                                actionLabel = updateReadyAction,
                                duration = SnackbarDuration.Indefinite,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                appUpdateController.completeUpdate()
                            }
                        }
                    }
                    appUpdateController.checkOnStart()
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
                }
            }
        }
    }

    private fun checkPermissions() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED -> viewModel.onPermissionsChecked(true)

            // Denied at least once but not permanently — explain before re-asking.
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ->
                viewModel.showPermissionRationale()

            // First request, or permanently denied — the system handles both. A permanent
            // denial returns granted=false from the launcher, routing to PermissionDenied.
            else -> requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun onRationaleAcknowledged() {
        viewModel.onRationaleAcknowledged()
        // Launch the system dialog directly, bypassing checkPermissions(), so we don't
        // re-enter the rationale branch (shouldShowRequestPermissionRationale stays true
        // until the user actually responds to the dialog).
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
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
     * mints a `content://` URI and the [Intent.FLAG_GRANT_READ_URI_PERMISSION] set in
     * [buildBoomerangShareIntent] grants the chosen receiver temporary read access. We flag
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

    private fun maybeRequestPostNotificationsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestPostNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
        val shareIntent = buildBoomerangShareIntent(uri, getString(R.string.share_subject))
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_chooser_title)))
    }

    override fun onResume() {
        super.onResume()
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
        // Re-surface the "Update ready" prompt if a Play download completed while the app was
        // backgrounded (Google's recommended stalled-update handling).
        appUpdateController.checkOnResume()
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
        // Release the Play InstallStateUpdatedListener. The ActivityResultLauncher unregisters
        // automatically with the Activity.
        if (::appUpdateController.isInitialized) appUpdateController.detach()
    }
}

/** Key under which [MainActivity.awaitingShareReturn] is persisted across recreation (slice 06). */
private const val KEY_AWAITING_SHARE_RETURN = "openloop.awaitingShareReturn"

/** Key under which [MainActivity.deferredShareFile] is persisted across recreation (Issue #40). */
private const val KEY_DEFERRED_SHARE_PATH = "openloop.deferredSharePath"

/** Key under which [MainActivity.deferredShareShowSavedSnackbar] is persisted across recreation. */
private const val KEY_DEFERRED_SHARE_SHOW_SAVED = "openloop.deferredShareShowSaved"

private const val TAG = "MainActivity"

@Composable
private fun rememberNotificationExportHint(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val context = LocalContext.current
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
}

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
                body = "OpenLoop needs Camera access to record video for your " +
                    "loops. Tap Grant to continue.",
                primaryActionLabel = "Grant Permission",
                onPrimaryAction = { onRationaleAcknowledged() },
                secondaryActionLabel = "Not now",
                onSecondaryAction = { viewModel.onRationaleDeclined() }
            )
        }
        is OpenLoopUiState.PermissionDenied -> {
            PermissionExplanationScreen(
                title = "Permission Required",
                body = "OpenLoop needs Camera access to record video for your " +
                    "speed-controlled loops.",
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
            val notificationsDenied = rememberNotificationExportHint()
            ProcessingScreen(
                progress = { progress.value },
                showBackgroundExportHint = notificationsDenied,
            )
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
                text = stringResource(R.string.import_too_long_title),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.import_too_long_body),
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
                    text = stringResource(R.string.import_too_long_button),
                    style = MaterialTheme.typography.labelLarge,
                    color = LimeInk
                )
            }
        }
    }
}
