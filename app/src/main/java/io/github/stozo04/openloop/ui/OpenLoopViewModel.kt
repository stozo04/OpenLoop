package io.github.stozo04.openloop.ui

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.stozo04.openloop.camera.CameraManager
import io.github.stozo04.openloop.camera.lens.Lens
import io.github.stozo04.openloop.data.RecordedVideo
import io.github.stozo04.openloop.data.ScratchCapture
import io.github.stozo04.openloop.data.UserPreferencesRepository
import io.github.stozo04.openloop.data.VideoImporter
import io.github.stozo04.openloop.data.VideoStorageRepository
import io.github.stozo04.openloop.BuildConfig
import io.github.stozo04.openloop.media.BoomerangMode
import io.github.stozo04.openloop.media.SpeedCurve
import io.github.stozo04.openloop.media.VideoFilter
import io.github.stozo04.openloop.media.VideoProcessor
import io.github.stozo04.openloop.diagnostics.AnalyticsReporter
import io.github.stozo04.openloop.diagnostics.NoOpAnalyticsReporter
import io.github.stozo04.openloop.diagnostics.ReverseCrashlytics
import io.github.stozo04.openloop.media.ReversePreviewLog
import io.github.stozo04.openloop.media.previewReverseMaxShortSideOrNull
import io.github.stozo04.openloop.media.isSamsungDevice
import io.github.stozo04.openloop.media.needsReverse
import io.github.stozo04.openloop.review.shouldAskForReview
import io.github.stozo04.openloop.work.BoomerangRenderRequest
import io.github.stozo04.openloop.work.BoomerangRenderScheduler
import io.github.stozo04.openloop.work.BoomerangRenderWorkResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import androidx.camera.video.VideoRecordEvent

/**
 * One-shot events the [OpenLoopViewModel] emits for transient UI (snackbars). Delivered over a
 * [Channel] (not a StateFlow) so they fire exactly once and never replay on recomposition.
 */
sealed interface BoomerangEvent {
    /**
     * Boomerang rendered + saved; carries the rendered [file] (a `filesDir/videos/boom_*.mp4` loop) so the
     * UI can hand it to the Android share sheet (slice 06). The "Saved — view in gallery" snackbar is
     * deferred until the share sheet is dismissed (see [OpenLoopViewModel.onShareSheetClosed]).
     */
    data class Share(
        val file: File,
        /** When false (gallery re-share), the share sheet still opens but no "Saved" snackbar follows. */
        val showSavedSnackbarAfterDismiss: Boolean = true,
    ) : BoomerangEvent
    /**
     * Show the "Saved — view in gallery" snackbar (with a "View" action into the gallery). Emitted
     * *after* the share sheet returns control — see [OpenLoopViewModel.onShareSheetClosed] — so the snackbar isn't wasted
     * behind the chooser.
     */
    object Saved : BoomerangEvent
    /**
     * Boomerang render failed (Loopifying / save path). Snackbar invites a retry; the trim and
     * direction selections are preserved. Carries the shareable [supportReport] (null if
     * unavailable) so the snackbar can offer a "Send debug report" action — the same
     * tester-friendly path as [ReversePreviewFallbackForward] (reverse-output-validation spec §5.5).
     */
    data class SaveFailed(val supportReport: String?) : BoomerangEvent

    /**
     * A picked library video was longer than the import limit (slice 07). Drives the friendly
     * "That clip's a bit long" dialog; nothing was copied.
     */
    object ImportTooLong : BoomerangEvent

    /**
     * A camera capture finalized shorter than the minimum loopable window, or with no encoded
     * frames at all (`ERROR_NO_VALID_DATA`, a tap-and-release) — issue #95 follow-up. The scratch
     * is discarded and the user is back on the viewfinder; drives a "record a little longer"
     * snackbar instead of a silent return. (The shutter is tap-to-start / tap-to-stop, so the
     * copy says "record longer", not "hold".)
     */
    object CaptureTooShort : BoomerangEvent

    /**
     * Importing a picked library video failed for a non-length reason — unreadable/revoked URI, an
     * unreadable duration, or a copy I/O error (slice 07). Drives a "Couldn't import that video."
     * snackbar; the user is returned to the gallery, never wedged.
     */
    object ImportFailed : BoomerangEvent

    /**
     * Preview reverse failed or timed out; editor fell back to [BoomerangMode.FORWARD] so the user
     * can preview and save, and can retry the reverse from the Loop direction tab. Carries the
     * shareable [supportReport] (null if unavailable) so the snackbar can offer a "Send report"
     * action — the user-friendly feedback/crash-report path.
     */
    data class ReversePreviewFallbackForward(val supportReport: String?) : BoomerangEvent

    /**
     * One or more gallery loops were marked for deletion (Issue #35). Drives the Undo snackbar; the
     * real file delete is **deferred** until the snackbar is dismissed (see [OpenLoopViewModel.commitPendingDeletion]).
     * [count] is how many loops the user removed in this batch, so the snackbar can pluralize.
     */
    data class LoopsDeleted(val count: Int) : BoomerangEvent

    /**
     * A photo-mode capture could not be saved — either the viewfinder had no frame to grab yet
     * (`PreviewView.getBitmap()` returns null until the preview is streaming) or the JPEG write
     * failed. Drives a short "Couldn't take that photo." snackbar; the user stays on the viewfinder
     * and can simply tap again (docs/PRD-photo-capture.md §5.3).
     */
    object PhotoCaptureFailed : BoomerangEvent

    /**
     * The user just saved a loop on the cadence in
     * [io.github.stozo04.openloop.review.shouldAskForReview] and the share sheet has closed — show
     * Play's in-app review card (Issue #121).
     *
     * **Emitted before [Saved], and that ordering is the fix, not an accident.** Play requires the
     * card to be the topmost layer, so the obvious move is to queue the ask behind the "Saved"
     * snackbar. That was the original design and it was wrong: `showSnackbar` suspends the host's
     * event collector for the snackbar's full ~4 s, during which the user is already back on the
     * viewfinder — so the card fired on top of whatever they had started, including a live
     * recording. Emitting first inverts it. The host suspends on `launchInAppReview` for the card's
     * whole lifecycle, so [Saved] still cannot overlay the card, and the ask lands the instant the
     * chooser dismisses instead of on a four-second fuse.
     *
     * The residual window — Play's request round trip — is closed by `launchInAppReview`'s `isIdle`
     * re-check. The host runs it; the ViewModel never touches an Activity (Lesson 004).
     */
    object RequestReview : BoomerangEvent
}

class OpenLoopViewModel(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val videoStorage: VideoStorageRepository,
    private val videoProcessor: VideoProcessor,
    private val videoImporter: VideoImporter,
    private val renderScheduler: BoomerangRenderScheduler,
    // 6th param wired in for the staged Firebase Analytics rollout — see
    // AnalyticsReporter (option 1 — abstraction only; see AnalyticsReporter.kt KDoc).
    // options 2 (screen tracking) and 3 (custom events) populate call sites incrementally. The
    // production impl comes from FirebaseAnalyticsReporterImpl.create(applicationContext); tests and
    // CI builds without google-services.json fall back to NoOpAnalyticsReporter.
    private val analytics: AnalyticsReporter = NoOpAnalyticsReporter,
    /**
     * Proactive low-memory probe (production: [MemoryPressure.lowMemoryProbe] →
     * `ActivityManager.getMemoryInfo().lowMemory`). Polled at editor entry and before applying a
     * non-Original look, because Android 14+ no longer delivers the foreground `onTrimMemory`
     * pressure levels (editor-memory-oom WS-3, PR #58 review). Injected as a lambda so the
     * ViewModel stays Context-free (Lesson 004) and tests can flip it deterministically.
     */
    private val isLowMemoryNow: () -> Boolean = { false },
    /**
     * Dispatcher for the reverse-scratch janitor's disk I/O (PR #58 review WARNING: never run
     * file I/O on the main thread — worst on exactly the stressed devices this code serves).
     * Injected so JVM tests substitute the shared TestDispatcher and the cleanup stays
     * deterministic under virtual time (coroutines best practice: inject dispatchers).
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Copies a saved photo into the device's public image library (production:
     * `publishImageToPhotos(applicationContext, file)`). Injected as a suspending lambda — the same
     * Context-free seam [isLowMemoryNow] uses — so the ViewModel never sees a
     * [android.content.Context] (Lesson 004) and JVM tests can record or fail it deterministically.
     *
     * Defaults to a no-op so existing test call sites keep compiling.
     */
    private val publishPhotoToLibrary: suspend (File) -> Unit = {},
) : ViewModel() {

    // Start in Initializing — DataStore read decides Onboarding vs CheckingPermissions
    private val _uiState = MutableStateFlow<OpenLoopUiState>(OpenLoopUiState.Initializing)
    val uiState: StateFlow<OpenLoopUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val onboardingDone = userPreferencesRepository.hasCompletedOnboarding.first()
            _uiState.value = if (onboardingDone) {
                OpenLoopUiState.CheckingPermissions
            } else {
                OpenLoopUiState.Onboarding
            }
        }
        // Best-effort prune of orphaned scratch copies older than 24 h (parent D-8). Imports raise
        // scratch churn (an abandoned import can leave a whole library-video-sized copy), so reclaim
        // it deterministically at launch rather than waiting on Android's cache eviction. Fire-and-
        // forget on Dispatchers.IO inside the repo — never blocks startup or the UI thread.
        viewModelScope.launch {
            try {
                videoStorage.pruneStaleScratch(STALE_SCRATCH_MAX_AGE.inWholeMilliseconds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("OpenLoopViewModel", "Stale-scratch prune failed", e)
            }
        }
    }

    fun onOnboardingCompleted() {
        _uiState.value = OpenLoopUiState.CheckingPermissions
        viewModelScope.launch {
            try {
                userPreferencesRepository.setOnboardingCompleted(true)
            } catch (e: IOException) {
                Log.e("OpenLoopViewModel", "Failed to persist onboarding state", e)
                // Non-fatal: user will just see onboarding again next launch
            }
        }
    }

    /**
     * Whether the speed-curve explainer has already been shown. Backed by DataStore so it survives
     * reinstall-free app restarts; defaults to `false` (show it) if the read fails — Lesson 003.
     *
     * The `stateIn` seed is `false` for the same reason: seeded `true`, a first-timer who tapped
     * Custom before the DataStore emission landed would never see the intro; seeded `false`, the worst
     * case is a returning user seeing it once more.
     */
    val hasSeenSpeedCurveIntro: StateFlow<Boolean> = userPreferencesRepository.hasSeenSpeedCurveIntro
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Persist "explainer dismissed". Non-fatal on failure: worst case the sheet shows once more. */
    fun markSpeedCurveIntroSeen() {
        viewModelScope.launch {
            try {
                userPreferencesRepository.setSpeedCurveIntroSeen(true)
            } catch (e: IOException) {
                Log.e("OpenLoopViewModel", "Failed to persist speed-curve intro state", e)
            }
        }
    }

    fun onPermissionsChecked(granted: Boolean) {
        _uiState.value = if (granted) {
            OpenLoopUiState.ReadyToCapture
        } else {
            OpenLoopUiState.PermissionDenied
        }
    }

    /** User denied a required permission once; show the educational rationale screen. */
    fun showPermissionRationale() {
        _uiState.value = OpenLoopUiState.PermissionRationale
    }

    /**
     * User acknowledged the rationale. Return to [OpenLoopUiState.CheckingPermissions] so the
     * permission flow has a single source of truth; MainActivity then launches the system dialog
     * directly to avoid re-entering the rationale branch (see MainActivity.checkPermissions).
     */
    fun onRationaleAcknowledged() {
        _uiState.value = OpenLoopUiState.CheckingPermissions
    }

    /**
     * User dismissed the rationale ("Not now") instead of granting. Move to the blocked-but-
     * recoverable [OpenLoopUiState.PermissionDenied] screen rather than nagging — the user can
     * still retry or open Settings from there. Satisfies Google's "always provide the option to
     * cancel an educational UI flow" guidance.
     */
    fun onRationaleDeclined() {
        _uiState.value = OpenLoopUiState.PermissionDenied
    }

    private var recordingJob: Job? = null

    /**
     * Elapsed recording time in milliseconds, driven by the capture timer while in
     * [OpenLoopUiState.Recording]. The UI reads this to draw the shutter progress ring and the
     * `00:00 / 00:30` countdown chip. It re-emits roughly every [TICK_DURATION] and is reset to 0
     * whenever a capture stops. Value is clamped to [MAX_RECORDING].
     */
    private val _recordingElapsedMs = MutableStateFlow(0L)
    val recordingElapsedMs: StateFlow<Long> = _recordingElapsedMs.asStateFlow()

    private val _recordedVideos = MutableStateFlow<List<RecordedVideo>>(emptyList())
    val recordedVideos: StateFlow<List<RecordedVideo>> = _recordedVideos.asStateFlow()

    /**
     * Ids of loops marked for deletion but not yet committed to disk (Issue #35). They are hidden
     * from the gallery immediately (optimistic delete via [visibleVideos]) while the Undo snackbar is
     * up; an Undo clears this set (the tiles reappear), a dismiss commits the real delete.
     */
    private val _pendingDeletionIds = MutableStateFlow<Set<Long>>(emptySet())
    val pendingDeletionIds: StateFlow<Set<Long>> = _pendingDeletionIds.asStateFlow()

    /**
     * The batch backing the current pending deletion, held in memory (NOT on disk). Safe-by-design:
     * because the real `videoStorage.deleteVideo` is deferred to [OpenLoopViewModel.commitPendingDeletion], process
     * death before the commit leaves every file intact — an implicit Undo, never data loss.
     */
    private var pendingBatch: List<RecordedVideo> = emptyList()

    /**
     * The gallery's view of storage with any pending-deletion ids filtered out, so removed tiles
     * vanish instantly and reappear on Undo. Collected with [SharingStarted.WhileSubscribed] (Lesson
     * 002 — lifecycle-aware) so it stops combining when the gallery isn't on screen.
     */
    val visibleVideos: StateFlow<List<RecordedVideo>> =
        combine(recordedVideos, pendingDeletionIds) { videos, pending ->
            videos.filterNot { it.id in pending }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The Trim screen's working state (source file, duration, handle positions), or `null` when no
     * clip is being edited. Held alongside (not inside) [OpenLoopUiState.Trim] so the routed state
     * stays a slim discriminator and the trim selection survives a failed render.
     */
    private val _editorState = MutableStateFlow<TrimState?>(null)
    val editorState: StateFlow<TrimState?> = _editorState.asStateFlow()

    /**
     * The boomerang editor's tab selections (slice 03: direction only). Held alongside [editorState]
     * so [OpenLoopUiState.BoomerangEditor] stays a slim discriminator. Defaults apply on the first
     * [onNextFromTrim] for a clip; returning from [backToTrim] preserves the session (mode, speed,
     * look, cached reverse).
     */
    private val _editorTabState = MutableStateFlow(EditorTabState())
    val editorTabState: StateFlow<EditorTabState> = _editorTabState.asStateFlow()

    /**
     * True after the first [onNextFromTrim] for the active scratch. [backToTrim] keeps this set so
     * toolbar hops Trim ↔ editor do not wipe [EditorTabState] or restart reverse unnecessarily.
     */
    private var editorSessionActive = false

    /** In-flight reverse-generation for the preview; canceled when the editing session ends. */
    private var reverseJob: Job? = null

    /** Bumped when reverse work is canceled or superseded so stale completions are ignored. */
    private var reverseGeneration = 0

    /** Brief overlay for speed/filter tweaks (does not block reverse generation). */
    private var effectLoadingJob: Job? = null

    /**
     * Full-screen overlay on Trim/Editor during discard (and any future session-wide blocking work).
     */
    private val _sessionOverlayLoading = MutableStateFlow<EditorLoadingKind?>(null)
    val sessionOverlayLoading: StateFlow<EditorLoadingKind?> = _sessionOverlayLoading.asStateFlow()

    /** Observes WorkManager progress/completion for the active Loopifying export (Issue #40). */
    private var renderObserveJob: Job? = null

    /** Scratch UUID of the render currently enqueued — used by [cancelRenderWork] (P2 cancel). */
    private var activeRenderScratchUuid: String? = null

    /** Render progress (0f..1f) for the [OpenLoopUiState.Processing] spinner. */
    private val _renderProgress = MutableStateFlow(0f)
    val renderProgress: StateFlow<Float> = _renderProgress.asStateFlow()

    /** One brief gallery-button nudge after a newly captured loop returns from the share sheet. */
    private val _nudgeGalleryButton = MutableStateFlow(false)
    val nudgeGalleryButton: StateFlow<Boolean> = _nudgeGalleryButton.asStateFlow()

    /**
     * Live-camera lens selection (docs/PRD-camera-lenses.md §6.3).
     *
     * Sibling flows, not new [OpenLoopUiState] entries: the lens tray is an overlay on the two
     * camera-bound states, so the exhaustive router `when` in `MainActivity` stays untouched
     * (Lesson 014). Deliberately NOT persisted — the lens resets on every launch so nobody opens
     * the app to an unexplained broccoli.
     */
    private val _activeLens = MutableStateFlow<Lens?>(null)
    val activeLens: StateFlow<Lens?> = _activeLens.asStateFlow()

    private val _lensTrayOpen = MutableStateFlow(false)
    val lensTrayOpen: StateFlow<Boolean> = _lensTrayOpen.asStateFlow()

    /**
     * Whether the shutter records a clip or takes a still (docs/PRD-photo-capture.md §5.1).
     *
     * Sibling flow, not an [OpenLoopUiState] entry — same rationale as [activeLens] above, so the
     * exhaustive router `when` stays untouched (Lesson 014). Not persisted: always [CaptureMode.VIDEO]
     * on launch.
     */
    private val _captureMode = MutableStateFlow(CaptureMode.VIDEO)
    val captureMode: StateFlow<CaptureMode> = _captureMode.asStateFlow()

    /** Guards against repeated shutter taps while a photo save/publish is already running. */
    private var photoSaveInProgress = false

    private var nudgeGalleryAfterShare = false

    /** Armed by a save that hits the review cadence; consumed by [onShareSheetClosed]. */
    private var pendingReviewRequest = false

    /**
     * Debug-only override: makes every save ask. Set from `MainActivity` behind `BuildConfig.DEBUG`
     * + [io.github.stozo04.openloop.review.EXTRA_DEMO_REVIEW], so release builds can never reach it.
     */
    var forceReviewAsk: Boolean = false

    /**
     * True on the two resting screens — the only states a Play review card may cover. Read live by
     * the host immediately before the card goes up, so a recording started during Play's request
     * round trip cancels the ask instead of losing the take (Issue #121).
     */
    val isIdleForReview: Boolean
        get() = _uiState.value is OpenLoopUiState.ReadyToCapture ||
            _uiState.value is OpenLoopUiState.Gallery

    /** Guards against repeated Save taps while promotion/enqueue/render is already active. */
    private var saveInProgress = false

    /** One-shot snackbar events (see [BoomerangEvent]); collected once by MainActivity. */
    private val _events = Channel<BoomerangEvent>(Channel.BUFFERED)
    val events: Flow<BoomerangEvent> = _events.receiveAsFlow()

    /**
     * Friendly "That clip's a bit long" dialog (slice 07). Held in a [StateFlow] (not a one-shot
     * event) so the dialog survives Activity recreation after the Photo Picker returns — a Channel
     * event can be emitted before MainActivity's collector is subscribed and then never shown.
     */
    private val _showImportTooLongDialog = MutableStateFlow(false)
    val showImportTooLongDialog: StateFlow<Boolean> = _showImportTooLongDialog.asStateFlow()

    /** Dismisses the import-too-long guidance dialog after the user taps "Got it". */
    fun dismissImportTooLongDialog() {
        _showImportTooLongDialog.value = false
    }

    /**
     * Friendly "That clip's a bit short" dialog (issue #95 follow-up). Same [StateFlow]-not-event
     * rationale as [showImportTooLongDialog]: it must survive Activity recreation after the Photo
     * Picker returns.
     */
    private val _showImportTooShortDialog = MutableStateFlow(false)
    val showImportTooShortDialog: StateFlow<Boolean> = _showImportTooShortDialog.asStateFlow()

    /** Dismisses the import-too-short guidance dialog after the user taps "Got it". */
    fun dismissImportTooShortDialog() {
        _showImportTooShortDialog.value = false
    }

    /** The in-flight capture's scratch file; non-null between capture start and Trim discard/save. */
    private var activeScratch: ScratchCapture? = null

    /** The raw the active scratch was promoted to (cached so a failed-render retry doesn't re-promote). */
    private var promotedRaw: RecordedVideo? = null

    /**
     * Whether the active editing session began as a library import ([onVideoPicked]) rather than a
     * fresh camera capture (slice 07). The pipeline is otherwise reused byte-for-byte; this flag only
     * changes where the user lands when the session ends — saving or discarding an imported clip
     * returns to the [OpenLoopUiState.Gallery] they imported from, not the camera. Reset in
     * [clearEditorSession] and on every camera capture.
     */
    private var importedSession: Boolean = false

    fun startBurstCapture(cameraManager: CameraManager) {
        if (_uiState.value != OpenLoopUiState.ReadyToCapture) return

        _uiState.value = OpenLoopUiState.Recording

        // Per-capture scratch file (filesDir/scratch/raw_<uuid>.mp4) instead of a single fixed path,
        // so the captured clip has a stable identity for the Trim screen and back-to-back captures
        // can't clobber each other.
        val scratch = videoStorage.createScratchCapture()
        activeScratch = scratch
        promotedRaw = null
        importedSession = false // a fresh capture; this session ends back on the camera
        val outputFile = scratch.file
        if (outputFile.exists()) {
            outputFile.delete()
        }

        try {
            val recording = cameraManager.startRecording(outputFile) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        Log.d("OpenLoopViewModel", "Video burst recording started.")
                    }
                    is VideoRecordEvent.Finalize -> {
                        // Whichever path finalized us (user tap or 30 s auto-cap), the timer is done.
                        clearRecordingTimers()
                        if (event.hasError()) {
                            Log.e("OpenLoopViewModel", "Video burst recording failed: ${event.error}")
                            videoStorage.discardScratch(scratch)
                            activeScratch = null
                            _uiState.value = OpenLoopUiState.ReadyToCapture
                            // ERROR_NO_VALID_DATA = the stop landed before a single frame was
                            // encoded (a tap-and-release). To the user that's "too short", not a
                            // device failure — say so instead of returning silently (issue #95
                            // follow-up). Other error codes keep the log-only behavior.
                            if (event.error == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA) {
                                viewModelScope.launch { _events.send(BoomerangEvent.CaptureTooShort) }
                            }
                        } else {
                            // Auto-route straight to the Trim screen (no preview landing pad).
                            // The scratch stays in cache until the user saves (promote→raw) or discards.
                            // durationOf does a MediaMetadataRetriever decode and this callback runs on
                            // CameraX's main executor, so read it on a coroutine (Dispatchers.IO inside the
                            // repo) before routing — never block the main thread (ANDROID_STANDARDS §9).
                            viewModelScope.launch {
                                val durationMs = videoStorage.durationOf(outputFile)
                                // A clip below the minimum trim window would land on the Trim
                                // screen with pinned handles and a dead SAVE (Lesson 030) — a
                                // silent dead-end. Discard it and tell the user to record longer
                                // instead (issue #95 follow-up). Also swallows an unreadable
                                // (<= 0) duration, which could never be trimmed either.
                                if (durationMs < MIN_TRIM_DURATION.inWholeMilliseconds) {
                                    Log.w(
                                        "OpenLoopViewModel",
                                        "Capture finalized below the ${MIN_TRIM_DURATION.inWholeMilliseconds}ms minimum (${durationMs}ms); discarding",
                                    )
                                    videoStorage.discardScratch(scratch)
                                    activeScratch = null
                                    _events.send(BoomerangEvent.CaptureTooShort)
                                    _uiState.value = OpenLoopUiState.ReadyToCapture
                                    return@launch
                                }
                                Log.d("OpenLoopViewModel", "Capture finalized (${durationMs}ms): ${outputFile.absolutePath}")
                                resetEditorTabForNewClip()
                                _editorState.value = TrimState(
                                    sourceFile = outputFile,
                                    sourceDurationMs = durationMs,
                                    trimStartMs = 0L,
                                    trimEndMs = durationMs,
                                )
                                _uiState.value = OpenLoopUiState.Trim(EditorSource.ScratchClip(scratch.uuid))
                            }
                        }
                    }
                }
            }

            // startRecording returns null when the VideoCapture use case isn't bound yet (REC-2).
            // If we launched the timer anyway, no Finalize would ever fire, the auto-cap's
            // stopRecording() would be a no-op, and the UI would sit stuck in Recording with a full
            // ring for 30 s. Revert to ReadyToCapture and bail BEFORE starting the timer coroutine.
            if (recording == null) {
                Log.e("OpenLoopViewModel", "startRecording returned null (camera not bound); aborting capture")
                clearRecordingTimers()
                _uiState.value = OpenLoopUiState.ReadyToCapture
                return
            }

            // Drive the elapsed-time flow (for the progress ring + countdown chip) and enforce the
            // 30 s hard cap. When elapsed reaches MAX_RECORDING with no user tap, finalize via the
            // same stopBurstCapture() path as a tap. The loop is bounded by the cap, so a virtual-time
            // test can advanceUntilIdle() without spinning forever (Lesson 008).
            _recordingElapsedMs.value = 0L
            recordingJob = viewModelScope.launch {
                var elapsed = 0L
                val maxRecordingMs = MAX_RECORDING.inWholeMilliseconds
                val tickMs = TICK_DURATION.inWholeMilliseconds
                while (elapsed < maxRecordingMs) {
                    delay(TICK_DURATION)
                    elapsed = (elapsed + tickMs).coerceAtMost(maxRecordingMs)
                    _recordingElapsedMs.value = elapsed
                }
                stopBurstCapture(cameraManager)
            }
        } catch (e: IllegalStateException) {
            // prepareRecording/start: the Recorder already has an unfinished active recording
            // (PendingRecording.start docs). Recover to idle rather than wedging in Recording.
            recoverFromFailedStart(e)
        }
        // NOTE: deliberately NOT catching Exception broadly (REC-3 / ANDROID_STANDARDS §3). The
        // synchronous start path only declares IllegalStateException; CameraX
        // surfaces IO/encoder failures asynchronously via VideoRecordEvent.Finalize (handled above),
        // not as a throw. Letting any other throwable propagate keeps real programming errors visible.
    }

    /** Shared recovery for a synchronous start-recording failure: log, cancel timers, go idle. */
    private fun recoverFromFailedStart(e: Exception) {
        Log.e("OpenLoopViewModel", "Failed to start burst capture", e)
        clearRecordingTimers()
        _uiState.value = OpenLoopUiState.ReadyToCapture
    }

    fun loadRecordedVideos() {
        // Directory scan + lazy thumbnail decode runs on Dispatchers.IO inside the repo; launch so
        // the read never blocks the caller's (main) thread (ANDROID_STANDARDS §9).
        viewModelScope.launch {
            _recordedVideos.value = videoStorage.loadRecordedVideos()
        }
    }

    /**
     * Mark [videos] for deletion (Issue #35). Hides them from [visibleVideos] immediately and emits
     * [BoomerangEvent.LoopsDeleted] so the UI can offer Undo — but does NOT touch disk yet. If a prior
     * batch is still pending it is committed first (a new delete supersedes the old one's Undo window).
     */
    fun requestDeleteVideos(videos: List<RecordedVideo>) {
        if (videos.isEmpty()) return
        if (pendingBatch.isNotEmpty()) commitPendingDeletion() // supersede: commit the prior batch
        pendingBatch = videos
        _pendingDeletionIds.value = videos.map { it.id }.toSet()
        viewModelScope.launch { _events.send(BoomerangEvent.LoopsDeleted(videos.size)) }
    }

    /** Re-open the Android share sheet for an existing saved loop (gallery preview). */
    fun shareLoop(video: RecordedVideo) {
        viewModelScope.launch {
            _events.send(
                BoomerangEvent.Share(
                    file = File(video.videoPath),
                    showSavedSnackbarAfterDismiss = false,
                ),
            )
        }
    }

    /** Undo the pending deletion: forget the batch + restore the hidden tiles. Nothing was deleted. */
    fun undoPendingDeletion() {
        pendingBatch = emptyList()
        _pendingDeletionIds.value = emptySet()
    }

    /**
     * Commit the pending deletion: delete each file from storage off the main thread, then reload the
     * gallery. Clears the in-memory batch + hidden ids first so a racing [requestDeleteVideos] starts
     * clean. A no-op when nothing is pending (e.g. an Undo already cleared it, or the snackbar is
     * dismissed twice).
     */
    fun commitPendingDeletion() {
        val batch = pendingBatch
        if (batch.isEmpty()) return
        pendingBatch = emptyList()
        _pendingDeletionIds.value = emptySet()
        viewModelScope.launch {
            batch.forEach { videoStorage.deleteVideo(it) }
            _recordedVideos.value = videoStorage.loadRecordedVideos()
        }
    }

    fun navigateToGallery() {
        _uiState.value = OpenLoopUiState.Gallery
        loadRecordedVideos()
    }

    fun navigateBackFromGallery() {
        _uiState.value = OpenLoopUiState.ReadyToCapture
    }

    // ── Import from library (slice 07) ──────────────────────────────────────────────────────────

    /**
     * Result of the Android Photo Picker (launched `VideoOnly` from the gallery). [uri] is the picked
     * video, or `null` if the user backed out. On a valid pick we probe the duration *before* copying
     * (so a >30 s clip — or one under [MIN_TRIM_DURATION], issue #95 follow-up — is rejected with a
     * friendly dialog without ever being copied), then copy the bytes into a fresh scratch file and
     * enter the existing [OpenLoopUiState.Trim] flow exactly as a fresh capture would — the imported
     * clip is just "a scratch that came from the picker." Any I/O or unreadable-duration failure
     * routes back to the gallery with a snackbar; never a crash.
     */
    fun onVideoPicked(uri: Uri?) {
        if (uri == null) return // user backed out of the picker
        _uiState.value = OpenLoopUiState.ImportingVideo
        viewModelScope.launch {
            val durationMs = videoImporter.probeDurationMs(uri)
            when {
                // Unreadable duration → we can't enforce the ≤30 s rule, so don't import it.
                durationMs <= 0L -> failImport()
                // Below the minimum trim window the editor would open with pinned handles and a
                // dead SAVE (Lesson 030) — reject with friendly guidance instead, before any copy
                // (issue #95 follow-up).
                durationMs < MIN_TRIM_DURATION.inWholeMilliseconds -> warnTooShort()
                // Enforce the dialog's advertised "up to 30 s" cap LENIENTLY: the small grace
                // (IMPORT_DURATION_GRACE_MS) accepts a clip the user thinks is "30 s" but whose
                // container duration reads 30.2–30.5 s. The grace only ever makes us *more* permissive
                // than the promise, never stricter — so no user is surprised by a rejection, and a clip
                // comfortably past 30 s is still rejected, exactly matching the "up to 30 seconds" copy.
                exceedsImportDurationLimit(durationMs) -> warnTooLong()
                else -> {
                    val scratch = videoStorage.createScratchCapture()
                    if (!videoImporter.importToFile(uri, scratch.file)) {
                        videoStorage.discardScratch(scratch)
                        failImport()
                        return@launch
                    }
                    val dur = videoStorage.durationOf(scratch.file)
                    when {
                        dur <= 0L -> {
                            videoStorage.discardScratch(scratch)
                            failImport()
                            return@launch
                        }
                        dur < MIN_TRIM_DURATION.inWholeMilliseconds -> {
                            // Pre-copy probe can over-read; the local copy is authoritative.
                            videoStorage.discardScratch(scratch)
                            warnTooShort()
                            return@launch
                        }
                        exceedsImportDurationLimit(dur) -> {
                            // Pre-copy probe can under-read; enforce the cap again on the local copy.
                            videoStorage.discardScratch(scratch)
                            warnTooLong()
                            return@launch
                        }
                    }
                    // Defensive: replacing activeScratch must not orphan a previous session's scratch
                    // copy. In practice it's already null here — the import action lives only on the
                    // gallery, and you can't reach the gallery mid-edit (save/discard both run
                    // clearEditorSession) — but if one ever lingered we'd otherwise leak a whole
                    // library-video-sized file until the 24h prune. discardScratch is a no-op on a
                    // missing file, so this is safe even in the normal null case.
                    activeScratch?.let { videoStorage.discardScratch(it) }
                    activeScratch = scratch
                    promotedRaw = null
                    importedSession = true // saving/discarding returns to the gallery, not the camera
                    resetEditorTabForNewClip()
                    _editorState.value = TrimState(
                        sourceFile = scratch.file,
                        sourceDurationMs = dur,
                        trimStartMs = 0L,
                        trimEndMs = dur, // whole clip ≤30 s; no trim-window cap needed
                    )
                    _uiState.value = OpenLoopUiState.Trim(EditorSource.ScratchClip(scratch.uuid))
                }
            }
        }
    }

    /** Non-length import failure: snackbar + back to the gallery (nothing left in flight). */
    private suspend fun failImport() {
        _events.send(BoomerangEvent.ImportFailed)
        _uiState.value = OpenLoopUiState.Gallery
    }

    /** Picked clip exceeded the import limit: friendly dialog + back to the gallery (nothing copied). */
    private suspend fun warnTooLong() {
        _showImportTooLongDialog.value = true
        _events.send(BoomerangEvent.ImportTooLong)
        _uiState.value = OpenLoopUiState.Gallery
    }

    /**
     * Picked clip is shorter than [MIN_TRIM_DURATION]: friendly dialog + back to the gallery
     * (nothing left in flight — issue #95 follow-up). No `BoomerangEvent` sibling to
     * [BoomerangEvent.ImportTooLong] — [showImportTooShortDialog] is the only thing the UI reads.
     */
    private fun warnTooShort() {
        _showImportTooShortDialog.value = true
        _uiState.value = OpenLoopUiState.Gallery
    }

    /** True when [durationMs] is past the advertised "up to 30 s" import cap (including grace). */
    private fun exceedsImportDurationLimit(durationMs: Long): Boolean =
        durationMs > (IMPORT_MAX_DURATION + IMPORT_DURATION_GRACE).inWholeMilliseconds

    /**
     * Finalize the current burst. Called from both the user-tap path and the 30 s auto-cap path.
     *
     * Idempotent by design: [recordingJob] is non-null only between [startBurstCapture] and the
     * `Finalize` callback. The first call cancels the timer and stops the recording; any later call
     * (e.g. a user tap landing on the same scheduler tick as the auto-cap) finds a null job and
     * returns, so `cameraManager.stopRecording()` is invoked exactly once per capture.
     */
    fun stopBurstCapture(cameraManager: CameraManager) {
        if (recordingJob == null) return
        clearRecordingTimers()
        cameraManager.stopRecording()
    }

    /** Cancel the elapsed-time / auto-cap timer and reset the progress ring to empty. */
    private fun clearRecordingTimers() {
        recordingJob?.cancel()
        recordingJob = null
        _recordingElapsedMs.value = 0L
    }

    /** Return to the live camera ([OpenLoopUiState.ReadyToCapture]) — a generic "start over" reset. */
    fun resetToCapture() {
        _uiState.value = OpenLoopUiState.ReadyToCapture
    }

    // ── Trim screen (slice 02) ──────────────────────────────────────────────────────────────────

    /**
     * Update the trim handles. Positions are clamped to `[0, sourceDuration]`; an update that would
     * shrink the window below [MIN_TRIM_DURATION] is ignored (the handles can't cross within the minimum).
     */
    fun updateTrim(startMs: Long, endMs: Long) {
        val current = _editorState.value ?: return
        val start = startMs.coerceIn(0L, current.sourceDurationMs)
        val end = endMs.coerceIn(0L, current.sourceDurationMs)
        if (end - start < MIN_TRIM_DURATION.inWholeMilliseconds) return
        if (start == current.trimStartMs && end == current.trimEndMs) return
        _editorState.value = current.copy(trimStartMs = start, trimEndMs = end)

        // Trim changed while the editor is open — invalidate the cached reverse and rebuild it.
        if (_uiState.value is OpenLoopUiState.BoomerangEditor) {
            cancelReverseJob()
            val tab = _editorTabState.value
            _editorTabState.value = tab.copy(
                reversedFile = null,
                reverseFailed = false,
                previewLoading = null,
            )
            if (tab.mode.needsReverse) {
                ensureReversedSegment(EditorLoadingKind.TRIMMING)
            }
        }
    }

    /**
     * Discard the scratch clip and leave the editor (the Trim back-arrow / confirm-discard path). A
     * fresh capture returns to the camera; an imported clip returns to the [OpenLoopUiState.Gallery]
     * it was imported from (slice 07). The original library video is untouched — we only delete our
     * own scratch copy.
     */
    fun discardTrim() {
        viewModelScope.launch {
            val returnToGallery = importedSession
            _sessionOverlayLoading.value = EditorLoadingKind.DELETING
            try {
                activeScratch?.let { videoStorage.discardScratch(it) }
                clearEditorSession()
                _sessionOverlayLoading.value = null
                if (returnToGallery) {
                    navigateToGallery()
                } else {
                    _uiState.value = OpenLoopUiState.ReadyToCapture
                }
            } catch (e: CancellationException) {
                _sessionOverlayLoading.value = null
                throw e
            } catch (e: Exception) {
                Log.e("OpenLoopViewModel", "Discard clip failed", e)
                _sessionOverlayLoading.value = null
            }
        }
    }

    /**
     * NEXT on the Trim screen: open the tabbed boomerang editor over the current trim (slice 03).
     * Resets the editor tabs to defaults (`FORWARD_THEN_REVERSE`), routes to
     * [OpenLoopUiState.BoomerangEditor], and eagerly kicks off reverse generation so the default
     * direction's preview is ready ASAP. The actual save now happens from the editor's checkmark
     * ([saveBoomerang]); slice 02's default-render-on-NEXT is gone.
     */
    fun onNextFromTrim(initialTab: EditorTab = EditorTab.DIRECTION) {
        val scratch = activeScratch ?: return
        if (_editorState.value == null) return
        val enteringFresh = !editorSessionActive
        val priorTab = _editorTabState.value
        val reverseLoadingKind =
            if (enteringFresh) EditorLoadingKind.TRIMMING else EditorLoadingKind.LOOPIFYING
        val willNeedReverse =
            priorTab.mode.needsReverse &&
                priorTab.reversedFile == null &&
                !priorTab.reverseFailed
        if (enteringFresh) {
            // Set TRIMMING before the editor composes so ExoPlayer never grabs a decoder on frame 1
            // (BoomerangEditorScreen gates prepare() on isReversePreviewLoading()).
            // effectsPreviewEnabled: probe at entry — a session that begins under memory pressure
            // never opens the Looks preview gate (Android 14+ has no foreground trim callback to
            // close it later; see MemoryPressure).
            _editorTabState.value = EditorTabState(
                activeTab = initialTab,
                previewLoading = if (willNeedReverse) reverseLoadingKind else null,
                effectsPreviewEnabled = !isLowMemoryNow(),
            )
            editorSessionActive = true
        } else {
            _editorTabState.value = priorTab.copy(
                activeTab = initialTab,
                previewLoading = if (willNeedReverse) reverseLoadingKind else priorTab.previewLoading,
            )
        }
        _uiState.value = OpenLoopUiState.BoomerangEditor(EditorSource.ScratchClip(scratch.uuid))
        if (willNeedReverse) {
            ensureReversedSegment(reverseLoadingKind)
        }
    }

    /** Back arrow / back gesture from the editor: return to Trim, preserving the trim selection. */
    fun backToTrim() {
        val scratch = activeScratch ?: run {
            _uiState.value = OpenLoopUiState.ReadyToCapture
            return
        }
        cancelReverseJob()
        _uiState.value = OpenLoopUiState.Trim(EditorSource.ScratchClip(scratch.uuid))
    }

    /**
     * Select a boomerang direction in the editor's Direction tab. Updating to a reverse-containing
     * mode kicks off [ensureReversedSegment] (idempotent — a no-op if the reversed file is already
     * ready or in flight); `FORWARD` needs no reversed clip.
     */
    fun updateMode(mode: BoomerangMode) {
        val current = _editorTabState.value
        if (current.mode == mode) return
        _editorTabState.value = current.copy(mode = mode)
        if (mode.needsReverse) {
            ensureReversedSegment(EditorLoadingKind.LOOPIFYING)
        } else {
            cancelReverseJob()
        }
    }

    /**
     * Set the playback speed from the editor's Speed tab (slice 04). Clamped to [MIN_SPEED]..[MAX_SPEED]
     * so neither the player nor the renderer ever sees an out-of-range value, regardless of what the
     * slider emits. Speed is a player-side effect on the preview and a per-clip render effect at save —
     * it never touches the cached [EditorTabState.reversedFile], so no reverse regeneration is needed.
     */
    fun updateSpeed(speed: Float) {
        val clamped = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        val current = _editorTabState.value
        if (current.speed == clamped) return
        _editorTabState.value = current.copy(speed = clamped)
        showBriefPreviewLoading()
    }

    /**
     * Switch the Speed tab into Curve mode, seeded **flat at the current constant speed** so the
     * preview does not jump and the first thing the user sees is their own setting drawn as a graph.
     * No-op when already in Curve mode. See `docs/PRD-speed-curves.md` §4.1.
     */
    fun enterCurveMode() {
        val current = _editorTabState.value
        if (current.curve != null) return
        _editorTabState.value = current.copy(curve = SpeedCurve.flat(current.speed))
        showBriefPreviewLoading()
    }

    /**
     * Collapse the curve to a single multiplier and return to the slider.
     *
     * The constant is [SpeedCurve.flatten] — the speed that plays the loop in the *same time* the curve
     * did, so the output duration does not jump at the exact moment the user asked to simplify. No-op
     * in Constant mode.
     */
    fun flattenCurveToConstant() {
        val current = _editorTabState.value
        val curve = current.curve ?: return
        _editorTabState.value = current.copy(
            curve = null,
            speed = curve.flatten().coerceIn(MIN_SPEED, MAX_SPEED),
        )
        showBriefPreviewLoading()
    }

    /**
     * Set the whole loop to [speed] as a constant — the tappable "Current: N×" label's action, which
     * differs from [flattenCurveToConstant] in taking the value under the playhead rather than the
     * curve's average.
     */
    fun setConstantSpeedFromCurve(speed: Float) {
        val current = _editorTabState.value
        if (current.curve == null) return
        _editorTabState.value = current.copy(
            curve = null,
            speed = speed.coerceIn(MIN_SPEED, MAX_SPEED),
        )
        showBriefPreviewLoading()
    }

    /**
     * Replace the working curve — every in-mode edit: drag, add/delete point, preset, reset.
     *
     * Deliberately does **not** call [showBriefPreviewLoading]: a drag emits continuously, and flashing
     * the "applying…" overlay on every pointer move would strobe the preview. Discrete edits (presets,
     * reset) take the same path on purpose — in Curve mode the playhead poller re-aims the player's
     * speed within one tick, so there is nothing for an overlay to cover. Only mode *transitions*
     * ([enterCurveMode], [flattenCurveToConstant], [setConstantSpeedFromCurve]) show it.
     */
    fun updateCurve(curve: SpeedCurve) {
        val current = _editorTabState.value
        if (current.curve == null || current.curve == curve) return
        _editorTabState.value = current.copy(curve = curve)
    }

    /**
     * Set the color look from the editor's Looks tab (slice 05). Like [updateSpeed] it's a pure
     * effect selection — applied live in the preview via `setVideoEffects` and baked into the render;
     * it never touches the cached [EditorTabState.reversedFile] or the output duration.
     */
    fun updateFilter(filter: VideoFilter) {
        val current = _editorTabState.value
        if (current.filter == filter) return
        if (filter != VideoFilter.ORIGINAL) {
            // Proactive probe at the exact moment DefaultVideoFrameProcessor would spin up.
            // Android 14+ delivers no foreground onTrimMemory pressure levels (MemoryPressure),
            // so this poll is the only mid-session pressure signal on modern devices: under
            // pressure, close the gate instead of applying the look (WS-3, PR #58 review).
            // Also the reopen path: a prior trim/lowMemory close must not permanently brick Looks
            // after pressure clears (Play review: S20 FE "lots of memory" + disabled Looks).
            if (isLowMemoryNow()) {
                // No-op when already closed: StateFlow conflates a value equal to the current one.
                _editorTabState.value = current.copy(effectsPreviewEnabled = false)
                return
            }
        }
        val overlay = if (current.previewLoading.isReversePreviewLoading()) {
            current.previewLoading
        } else {
            EditorLoadingKind.FILTERING
        }
        _editorTabState.value = current.copy(
            filter = filter,
            previewLoading = overlay,
            effectsPreviewEnabled = true,
        )
    }

    /** Called after the preview player has applied the new filter (or cleared effects for Original). */
    fun onFilterPreviewSettled() {
        clearPreviewLoading(EditorLoadingKind.FILTERING)
    }

    /**
     * Switch the editor's active tab (Direction / Speed / Looks). Opening Looks while the effects
     * gate is closed re-probes memory so a cleared-pressure session drops the banner without an
     * extra chip tap.
     */
    fun switchTab(tab: EditorTab) {
        val current = _editorTabState.value
        if (current.activeTab == tab) return
        _editorTabState.value = current.copy(
            activeTab = tab,
            // `||` short-circuits, so an already-open gate never pays for the memory probe.
            effectsPreviewEnabled = current.effectsPreviewEnabled ||
                (tab == EditorTab.LOOKS && !isLowMemoryNow()),
        )
    }

    /**
     * Ensure the reversed clip for the current trim exists (for the preview, and reused by the render).
     * Serialized against fast chip-taps: once the reversed file is ready or a generation is already in
     * flight, further calls are ignored (KICKOFF §4 — the trim is fixed for the session, so one run
     * per session suffices). Failure clears the loading flag and leaves [EditorTabState.reversedFile]
     * null; the preview then falls back to forward playback and the user can retry by reelecting.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun ensureReversedSegment(loadingKind: EditorLoadingKind = EditorLoadingKind.LOOPIFYING) {
        val trim = _editorState.value ?: return
        var tab = _editorTabState.value
        if (!tab.mode.needsReverse) return
        if (tab.reversedFile != null) {
            clearReversePreviewLoadingIfSet()
            return
        }
        if (reverseJob?.isActive == true) {
            if (!tab.previewLoading.isReversePreviewLoading()) {
                _editorTabState.value = tab.copy(previewLoading = loadingKind, reverseFailed = false)
            }
            return
        }
        if (tab.previewLoading.isReversePreviewLoading() && reverseJob?.isActive != true) {
            _editorTabState.value = tab.copy(previewLoading = null)
            tab = _editorTabState.value
        }

        val generation = ++reverseGeneration
        effectLoadingJob?.cancel()
        _editorTabState.value = tab.copy(
            previewLoading = loadingKind,
            reverseFailed = false,
            reverseSupportReport = null,
        )
        ReversePreviewLog.i(
            "viewModel.ensureReversed.start",
            "gen=$generation loading=$loadingKind source=${trim.sourceFile.name} " +
                "trim=${trim.trimStartMs}..${trim.trimEndMs}ms samsung=${isSamsungDevice()}",
        )
        reverseJob = viewModelScope.launch {
            try {
                // withTimeoutOrNull waits for cancellation to finish; a wedged MediaCodec/Transformer
                // on some Samsung devices never returns, so the failure UI never appears. select +
                // onTimeout returns immediately; do not use coroutineScope here — it would wait for
                // the cancelled worker and can surface CancellationException without reverseFailed.
                // runCatching + Result in select so a failed async child does not cancel this
                // launch before we can set reverseFailed (Lesson 013 still applies to the job itself).
                val previewReverseCap = previewReverseMaxShortSideOrNull()
                val outcome = if (reversePreviewTimeoutDisabledForTests()) {
                    // JVM tests: avoid Main awaiting an IO [async] child (deadlocks with Unconfined).
                    withContext(Dispatchers.IO) {
                        runCatching {
                            videoProcessor.ensureReversed(
                                trim.sourceFile,
                                trim.trimStartMs,
                                trim.trimEndMs,
                                maxReverseShortSide = previewReverseCap,
                            )
                        }
                    }
                } else {
                    val worker = async(Dispatchers.IO) {
                        runCatching {
                            videoProcessor.ensureReversed(
                                trim.sourceFile,
                                trim.trimStartMs,
                                trim.trimEndMs,
                                maxReverseShortSide = previewReverseCap,
                            )
                        }
                    }
                    select {
                        worker.onAwait { it }
                        onTimeout(reversePreviewTimeout()) {
                            worker.cancel()
                            Result.failure(PreviewReverseTimeoutException())
                        }
                    }
                }
                if (generation != reverseGeneration) {
                    ReversePreviewLog.d(
                        "viewModel.ensureReversed.stale",
                        "gen=$generation current=$reverseGeneration",
                    )
                    return@launch
                }
                outcome.onSuccess { reversed ->
                    ReversePreviewLog.i(
                        "viewModel.ensureReversed.ok",
                        "gen=$generation file=${reversed.name} bytes=${reversed.length()}",
                    )
                    val latest = _editorTabState.value
                    _editorTabState.value = latest.copy(
                        reversedFile = reversed,
                        previewLoading = clearReversePreviewLoadingValue(latest.previewLoading),
                        reverseSupportReport = null,
                    )
                }.onFailure { error ->
                    if (error is CancellationException) {
                        ReversePreviewLog.d(
                            "viewModel.ensureReversed.cancelled",
                            "gen=$generation ${error.javaClass.simpleName}",
                        )
                        return@launch
                    }
                    if (error is PreviewReverseTimeoutException) {
                        ReversePreviewLog.e(
                            "viewModel.ensureReversed.timeout",
                            "gen=$generation after ${reversePreviewTimeout()} " +
                                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                                "source=${trim.sourceFile.name}",
                        )
                        Log.e(
                            "OpenLoopViewModel",
                            "Reverse generation for preview timed out after ${reversePreviewTimeout()} " +
                                "(${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                                "source=${trim.sourceFile.name}, ${trim.trimEndMs - trim.trimStartMs}ms trim)",
                        )
                        markReversePreviewFailed(
                            trim,
                            "Timed out after ${reversePreviewTimeout().inWholeSeconds}s",
                            PreviewReverseTimeoutException(),
                        )
                    } else {
                        ReversePreviewLog.e(
                            "viewModel.ensureReversed.fail",
                            "gen=$generation ${error.javaClass.simpleName}: ${error.message}",
                            error,
                        )
                        Log.e(
                            "OpenLoopViewModel",
                            "Reverse generation for preview failed: ${error.javaClass.simpleName}: ${error.message}",
                            error,
                        )
                        markReversePreviewFailed(
                            trim,
                            "${error.javaClass.simpleName}: ${error.message}",
                            error,
                        )
                    }
                }
            } catch (e: CancellationException) {
                if (generation == reverseGeneration) {
                    clearReversePreviewLoadingIfSet()
                }
                throw e // never swallow cancellation (Lesson 013)
            }
        }
    }

    /** Marker for [select] timeout — not shown to users. */
    private class PreviewReverseTimeoutException : Exception()

    private fun markReversePreviewFailed(trim: TrimState, outcome: String, cause: Throwable) {
        ReverseCrashlytics.reportPreviewFailure(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            source = trim.sourceFile,
            trimStartMs = trim.trimStartMs,
            trimEndMs = trim.trimEndMs,
            outcome = outcome,
            cause = cause,
        )
        val latest = _editorTabState.value
        val supportReport = ReverseCrashlytics.supportReportForShare(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            source = trim.sourceFile,
            trimStartMs = trim.trimStartMs,
            trimEndMs = trim.trimEndMs,
            outcome = outcome,
        )
        // Let Samsung (and other slow-reverse) users preview and save a forward loop instead of blocking
        // on ping-pong. They can pick a reverse mode again from the Loop tab (Try again / direction).
        // Looks stay available: filter preview is a Media3 effect on the forward player and does not
        // depend on the reversed artifact. Closing the gate here blamed "low memory" for OEM reverse
        // quirks and cost a 1★ Play review (Galaxy S20 FE / 1.0.30).
        _editorTabState.value = latest.copy(
            mode = BoomerangMode.FORWARD,
            previewLoading = clearReversePreviewLoadingValue(latest.previewLoading),
            reverseFailed = false,
            reverseSupportReport = supportReport,
        )
        reverseJob = null
        cleanupReverseScratchAfterCancel()
        viewModelScope.launch {
            _events.send(BoomerangEvent.ReversePreviewFallbackForward(supportReport))
        }
    }

    /** Retry reverse generation after [EditorTabState.reverseFailed] (Loop tab). */
    fun retryReverseSegment() {
        cancelReverseJob()
        val tab = _editorTabState.value
        val pingPongMode = when (tab.mode) {
            BoomerangMode.FORWARD -> BoomerangMode.FORWARD_THEN_REVERSE
            else -> tab.mode
        }
        _editorTabState.value = tab.copy(
            mode = pingPongMode,
            reversedFile = null,
            reverseFailed = false,
            previewLoading = null,
            reverseSupportReport = null,
        )
        if (pingPongMode.needsReverse) {
            ensureReversedSegment(EditorLoadingKind.LOOPIFYING)
        }
    }

    private fun showBriefPreviewLoading() {
        val tab = _editorTabState.value
        if (tab.previewLoading == EditorLoadingKind.TRIMMING ||
            tab.previewLoading == EditorLoadingKind.LOOPIFYING
        ) {
            return
        }
        _editorTabState.value = tab.copy(previewLoading = EditorLoadingKind.HOLD_TIGHT)
        effectLoadingJob?.cancel()
        effectLoadingJob = viewModelScope.launch {
            delay(EFFECT_LOADING_MIN_DURATION)
            clearPreviewLoading(EditorLoadingKind.HOLD_TIGHT)
        }
    }

    private fun clearPreviewLoading(kind: EditorLoadingKind) {
        val tab = _editorTabState.value
        if (tab.previewLoading == kind) {
            _editorTabState.value = tab.copy(previewLoading = null)
        }
    }

    /**
     * Save the boomerang in the editor's current direction + speed + look (reps stays hard-wired at 1
     * — the reps tab was dropped for the Looks tab). Flips to [OpenLoopUiState.Processing]; on success promotes the scratch to a persistent
     * raw, registers the boomerang, emits [BoomerangEvent.Share] (handing the rendered file to the
     * share sheet — slice 06) and returns to capture. The render
     * sources the **scratch** file — the same path the preview reversed — so a reverse-containing mode
     * hits the cached reversed clip instead of regenerating it (speed is applied per clip at render and
     * doesn't invalidate that cache). On failure, it emits [BoomerangEvent.SaveFailed] and routes back
     * to [OpenLoopUiState.BoomerangEditor] with the direction + speed selection intact.
     */
    fun saveBoomerang() {
        val editor = _editorState.value ?: return
        val scratch = activeScratch ?: return
        if (saveInProgress || _uiState.value is OpenLoopUiState.Processing) return
        saveInProgress = true
        val tab = _editorTabState.value
        val mode = tab.mode

        viewModelScope.launch {
            try {
                // Promote once and cache it, so a retry after a failed render doesn't create a 2nd raw.
                val raw = promotedRaw
                    ?: (videoStorage.promoteScratchToRaw(scratch)?.also { promotedRaw = it }
                        ?: throw IOException("Failed to promote scratch ${scratch.uuid} to a raw"))

                val output = videoStorage.allocateBoomerangFile(raw.id)
                val returnToGallery = importedSession // capture before clearEditorSession() resets it

                val request = BoomerangRenderRequest(
                    scratch = scratch,
                    trimStartMs = editor.trimStartMs,
                    trimEndMs = editor.trimEndMs,
                    mode = mode,
                    speed = tab.speed,
                    curve = tab.curve,
                    filter = tab.filter,
                    repetitions = DEFAULT_REPS,
                    rawId = raw.id,
                    outputFile = output,
                    returnToGallery = returnToGallery,
                )

                _uiState.value = OpenLoopUiState.Processing
                _renderProgress.value = 0f

                renderObserveJob?.cancel()
                activeRenderScratchUuid = scratch.uuid
                val workId = renderScheduler.enqueue(request)
                observeRenderWork(workId, scratch)
            } catch (e: CancellationException) {
                throw e // never swallow cancellation (Lesson 013)
            } catch (e: IOException) {
                Log.e("OpenLoopViewModel", "Boomerang save failed before render enqueue (IO)", e)
                failBackToEditor(scratch, "Save failed before render enqueue: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    /**
     * Cancel the in-flight Loopifying export for the active scratch (P2 cancel coordination).
     * No-op when nothing is rendering.
     */
    fun cancelRenderWork() {
        activeRenderScratchUuid?.let { renderScheduler.cancelRenderWork(it) }
    }

    private fun observeRenderWork(workId: UUID, scratch: ScratchCapture) {
        renderObserveJob = viewModelScope.launch {
            launch {
                renderScheduler.observeProgress(workId).collect { fraction ->
                    _renderProgress.value = fraction
                }
            }
            renderScheduler.observeResult(workId).collect { result ->
                when (result) {
                    is BoomerangRenderWorkResult.Success -> onRenderSucceeded(result)
                    is BoomerangRenderWorkResult.Failure -> failBackToEditor(
                        scratch,
                        outcome = result.reason
                            ?: "Render worker reported failure (details in BoomerangRenderWorker log)",
                        cause = null,
                        workerReportedCause = result.workerReportedCause,
                    )
                    BoomerangRenderWorkResult.Cancelled -> returnToEditorAfterCancel(scratch)
                }
            }
        }
    }

    private suspend fun onRenderSucceeded(result: BoomerangRenderWorkResult.Success) {
        // End the WorkManager observer without canceling this coroutine mid-collect.
        renderObserveJob = null
        activeRenderScratchUuid = null
        saveInProgress = false
        cancelReverseJob()
        activeScratch = null
        promotedRaw = null
        importedSession = false
        _editorState.value = null
        _editorTabState.value = EditorTabState()
        _renderProgress.value = 0f
        nudgeGalleryAfterShare = !result.returnToGallery
        loadRecordedVideos()
        // Only this branch counts a save — a failed or cancelled render never reaches here. Arm
        // BEFORE emitting Share: onShareSheetClosed reads the flag the moment the chooser dismisses.
        pendingReviewRequest = try {
            shouldAskForReview(userPreferencesRepository.incrementSavedLoopCount()) || forceReviewAsk
        } catch (e: IOException) {
            // Losing the tally just means no review ask — never fail a save the user already got.
            Log.e("OpenLoopViewModel", "Failed to record the saved-loop count", e)
            false
        }
        _events.send(BoomerangEvent.Share(result.outputFile))
        _uiState.value = if (result.returnToGallery) {
            OpenLoopUiState.Gallery
        } else {
            OpenLoopUiState.ReadyToCapture
        }
    }

    /**
     * The share sheet for a just-saved boomerang has returned control (the user shared, canceled, or
     * backed out — all the same to us). Emit [BoomerangEvent.Saved] so the "Saved — view in gallery"
     * snackbar shows now that the user is back on the camera. Called by MainActivity from its next
     * `onResume()` after the chooser dismisses — not `withResumed { }`, which would fire immediately
     * because the activity is still RESUMED at the moment the chooser is launched (slice 06).
     */
    fun onShareSheetClosed() {
        if (nudgeGalleryAfterShare) {
            nudgeGalleryAfterShare = false
            _nudgeGalleryButton.value = true
        }
        val askForReview = pendingReviewRequest
        pendingReviewRequest = false // one ask per arming, even if the sheet somehow closes twice
        viewModelScope.launch {
            // Ask BEFORE Saved — see [BoomerangEvent.RequestReview]. Queuing it behind the snackbar
            // put the card on a ~4 s fuse that could fire over a recording the user had started.
            if (askForReview) _events.send(BoomerangEvent.RequestReview)
            _events.send(BoomerangEvent.Saved)
        }
    }

    fun onGalleryButtonNudgeFinished() {
        _nudgeGalleryButton.value = false
    }

    /** Opens/closes the lens carousel. Selecting a lens leaves the tray open (Snapchat behaviour). */
    fun setLensTrayOpen(open: Boolean) {
        _lensTrayOpen.value = open
    }

    /**
     * Selects a lens, or clears it when [lens] is already active (tap-to-toggle) or `null`.
     *
     * Applying it to the camera is the caller's job — see `CameraScreen`. Safe at any time,
     * including mid-recording: the effect is permanently attached and only its uniforms change
     * (PRD-camera-lenses §5.3).
     */
    fun selectLens(lens: Lens?) {
        _activeLens.value = if (lens != null && lens == _activeLens.value) null else lens
    }

    /**
     * Switch the shutter between recording clips and taking stills.
     *
     * Refused while [OpenLoopUiState.Recording]: mid-capture the shutter means "stop recording", and
     * swapping it to a capture button would strand the in-flight clip. The UI already hides the
     * toggle while recording — this guard makes that a rule of the state machine rather than a
     * property of one composable.
     */
    fun setCaptureMode(mode: CaptureMode) {
        if (_uiState.value is OpenLoopUiState.Recording) return
        _captureMode.value = mode
    }

    /**
     * Photo-mode shutter: persist [bitmap] (a snapshot of the composited viewfinder, lens included),
     * copy it into the device's public library, and hand it to the share sheet — skipping the whole
     * boomerang pipeline (docs/PRD-photo-capture.md §5.3).
     *
     * [bitmap] is nullable because `PreviewView.getBitmap()` returns null until the preview reaches
     * `StreamState.STREAMING`; a tap in that window is a miss, not a crash.
     */
    fun capturePhoto(bitmap: Bitmap?) {
        if (_uiState.value != OpenLoopUiState.ReadyToCapture) return
        if (photoSaveInProgress) return
        photoSaveInProgress = true
        viewModelScope.launch {
            try {
                val photo = bitmap?.let { videoStorage.savePhoto(it) }
                if (photo == null) {
                    Log.w(
                        "OpenLoopViewModel",
                        "Photo capture produced no file (hadBitmap=${bitmap != null})",
                    )
                    _events.send(BoomerangEvent.PhotoCaptureFailed)
                    return@launch
                }
                val file = File(photo.videoPath)
                // Best-effort public copy: the in-app save already succeeded, and a MediaStore
                // failure must not cost the user their photo (PRD §5.5 — a deliberate deviation
                // from the render worker, where the same failure fails the whole loop).
                try {
                    publishPhotoToLibrary(file)
                } catch (e: CancellationException) {
                    throw e // never swallow cancellation (Lesson 013)
                } catch (e: Exception) {
                    Log.e("OpenLoopViewModel", "Publishing photo to the device library failed", e)
                }
                loadRecordedVideos()
                nudgeGalleryAfterShare = true
                _events.send(BoomerangEvent.Share(file))
            } finally {
                photoSaveInProgress = false
            }
        }
    }

    /**
     * Report the save failure (Crashlytics non-fatal + shareable report), emit
     * [BoomerangEvent.SaveFailed], and route back to the editor preserving the direction selection.
     * [cause] is null when the failure arrived as a [BoomerangRenderWorkResult.Failure] —
     * WorkManager does not carry the worker's *exception* across, only the reason string the worker
     * attached to its failure Data (already folded into [outcome] by the caller).
     * [workerReportedCause] is true when the worker already recorded the genuine exception as its
     * own non-fatal; reporting a second, synthetic-cause event here would just re-open the
     * catch-all beacon issue (Crashlytics 47233ad7) without adding signal, so it is skipped —
     * the user-facing SaveFailed event and editor routing always happen.
     */
    private suspend fun failBackToEditor(
        scratch: ScratchCapture,
        outcome: String,
        cause: Throwable?,
        workerReportedCause: Boolean = false,
    ) {
        resetActiveRenderState()
        val editor = _editorState.value
        val trimStartMs = editor?.trimStartMs ?: 0L
        val trimEndMs = editor?.trimEndMs ?: 0L
        if (!workerReportedCause) {
            ReverseCrashlytics.reportSaveFailure(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                source = scratch.file,
                trimStartMs = trimStartMs,
                trimEndMs = trimEndMs,
                outcome = outcome,
                cause = cause ?: SaveRenderFailedException(outcome),
            )
        }
        val supportReport = ReverseCrashlytics.supportReportForShare(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            source = scratch.file,
            trimStartMs = trimStartMs,
            trimEndMs = trimEndMs,
            outcome = outcome,
            phase = ReverseCrashlytics.PHASE_SAVE,
        )
        _events.send(BoomerangEvent.SaveFailed(supportReport))
        _uiState.value = OpenLoopUiState.BoomerangEditor(EditorSource.ScratchClip(scratch.uuid))
    }

    /**
     * Route back to the editor after a render was cancelled ([BoomerangRenderWorkResult.Cancelled]).
     * A cancel is user intent, not an error: unlike [failBackToEditor] it files **no** Crashlytics
     * non-fatal (that would re-open the catch-all beacon issue 47233ad7 with a signal-less event)
     * and emits **no** [BoomerangEvent.SaveFailed]. The scratch survives so the editor resumes.
     */
    private fun returnToEditorAfterCancel(scratch: ScratchCapture) {
        resetActiveRenderState()
        _uiState.value = OpenLoopUiState.BoomerangEditor(EditorSource.ScratchClip(scratch.uuid))
    }

    /**
     * Tear down the in-flight render observation and reset the progress ring. Shared by the failure
     * and cancellation exits; leaves the editor/scratch state intact so either can resume the editor.
     */
    private fun resetActiveRenderState() {
        renderObserveJob?.cancel()
        renderObserveJob = null
        activeRenderScratchUuid = null
        saveInProgress = false
        _renderProgress.value = 0f
    }

    /** Stand-in cause when a render failure arrives as a bare WorkManager [Result.failure]. */
    private class SaveRenderFailedException(message: String) : Exception(message)

    /** Cancel any in-flight reverse generation (editor left or session cleared). */
    private fun resetEditorTabForNewClip() {
        editorSessionActive = false
        cancelReverseJob()
        _editorTabState.value = EditorTabState()
    }

    private fun clearReversePreviewLoadingValue(kind: EditorLoadingKind?): EditorLoadingKind? =
        if (kind.isReversePreviewLoading()) null else kind

    private fun clearReversePreviewLoadingIfSet() {
        val tab = _editorTabState.value
        if (tab.previewLoading.isReversePreviewLoading()) {
            _editorTabState.value = tab.copy(previewLoading = null)
        }
    }

    private fun cancelReverseJob() {
        reverseGeneration++
        val job = reverseJob
        reverseJob = null
        job?.cancel()
        clearReversePreviewLoadingIfSet()
        if (job != null) cleanupReverseScratchAfterCancel()
    }

    private fun cleanupReverseScratchAfterCancel() {
        // The janitor lists and deletes files — disk I/O that must never run on the main thread
        // (PR #58 review WARNING; this path fires by definition when the device is unhealthy, so
        // flash contention makes main-thread I/O jank/ANR-adjacent). NonCancellable so an in-flight
        // cleanup completes even if the editor session tears viewModelScope work down around it;
        // anything missed across process death is reclaimed by the startup pruneStaleScratch pass.
        viewModelScope.launch(ioDispatcher) {
            withContext(NonCancellable) {
                val result = videoProcessor.cleanupReverseIntermediates()
                ReverseCrashlytics.logReversePreviewCleanup(result.deletedCount, result.bytesDeleted)
            }
        }
    }

    /**
     * Called from [android.app.Activity.onTrimMemory] while the editor is active — only for the
     * legacy *foreground pressure* levels ([MemoryPressure.isForegroundPressureLevel]; API <= 33).
     * Also resets the look to [VideoFilter.ORIGINAL]: the UI tears down an already-running effects
     * pipeline by recreating the player (`setVideoEffects(emptyList())` is forbidden — see the
     * HDR-seam comment in BoomerangEditorScreen), so chips, preview, and export must agree on
     * "no look" once the gate closes (PR #58 review).
     *
     * Skipped while reverse preview is generating: pass 1/2 is the heaviest transient allocator in
     * the editor, and OEM `RUNNING_LOW` during that window is expected — treating it as a permanent
     * Looks disable left users on API <= 33 (e.g. Galaxy S20 FE / Android 13) with a false
     * "low memory" banner after reverse finished.
     */
    fun onTrimMemory() {
        if (_editorState.value == null) return
        val tab = _editorTabState.value
        if (!tab.effectsPreviewEnabled) return
        if (tab.previewLoading.isReversePreviewLoading()) return
        _editorTabState.value = tab.copy(
            effectsPreviewEnabled = false,
            filter = VideoFilter.ORIGINAL,
        )
    }

    private fun cancelRenderObserveJob() {
        renderObserveJob?.cancel()
        renderObserveJob = null
        activeRenderScratchUuid = null
        saveInProgress = false
    }

    /** Clear the active editing session (after discard or navigation away). Does NOT touch on-disk files. */
    private fun clearEditorSession() {
        editorSessionActive = false
        cancelReverseJob()
        effectLoadingJob?.cancel()
        effectLoadingJob = null
        _sessionOverlayLoading.value = null
        cancelRenderObserveJob()
        activeScratch = null
        promotedRaw = null
        importedSession = false
        _editorState.value = null
        _editorTabState.value = EditorTabState()
        _renderProgress.value = 0f
    }

    /**
     * Factory for creating [OpenLoopViewModel] with its repository dependencies.
     * Used in MainActivity since we don't have a DI framework. Note it takes the
     * already-constructed repositories (not a Context) — MainActivity bridges
     * Context → repositories, keeping this Factory and the ViewModel Context-free.
     */
    companion object {
        /** Hard cap on a single burst capture; recording auto-finalizes at this elapsed time. */
        val MAX_RECORDING = 30.seconds

        /** Elapsed-time emit cadence (~30 fps) for a smooth progress ring without over-emitting. */
        val TICK_DURATION = 33.milliseconds

        /** Minimum trimmed duration; below this the NEXT action is disabled (slice 02). */
        val MIN_TRIM_DURATION = 400.milliseconds

        /** Default boomerang config. Direction picker shipped slice 03, speed slider slice 04, Looks
         *  (filters) slice 05 — the Reps tab was dropped in favor of Looks, so [DEFAULT_REPS] stays
         *  hard-wired at 1. [DEFAULT_SPEED] is the speed slider's starting value. */
        const val DEFAULT_SPEED = 2.0f
        const val DEFAULT_REPS = 1

        /**
         * Playback-speed bounds (slice 04); [updateSpeed] clamps to this range.
         *
         * Aliases [SpeedCurve]'s constants rather than repeating the literals, so the slider and the
         * curve graph can never end up on different scales — which is also what lets Flatten
         * round-trip losslessly between the two modes (PRD-speed-curves.md §8 Q1).
         */
        const val MIN_SPEED = SpeedCurve.MIN_SPEED

        /** Minimum time the speed/filter preview overlay stays visible so the caption is readable. */
        private val EFFECT_LOADING_MIN_DURATION = 400.milliseconds

        /**
         * Max wall time for editor preview reverse (library imports can be slow). On timeout the editor
         * surfaces [EditorTabState.reverseFailed] instead of infinite "Trimming..".
         */
        val REVERSE_PREVIEW_TIMEOUT = 120.seconds

        /**
         * When true, preview reverse has no [select] timeout (unit tests only). Virtual-time
         * `advanceUntilIdle()` (kotlinx-coroutines-test) otherwise elapses the 120s deadline before IO mocks finish.
         */
        @Volatile
        var reversePreviewTimeoutDisabledForTests: Boolean = false

        /** Non-null replaces [REVERSE_PREVIEW_TIMEOUT] for timeout-duration tests only. */
        @Volatile
        var reversePreviewTimeoutOverride: Duration? = null

        internal fun reversePreviewTimeoutDisabledForTests(): Boolean =
            reversePreviewTimeoutDisabledForTests

        internal fun reversePreviewTimeout(): Duration =
            reversePreviewTimeoutOverride ?: REVERSE_PREVIEW_TIMEOUT

        /** Upper playback-speed bound; aliases [SpeedCurve.MAX_SPEED] — see [MIN_SPEED]. */
        const val MAX_SPEED = SpeedCurve.MAX_SPEED

        /** Max duration of an imported library clip (slice 07); same 30 s ceiling as a capture. */
        val IMPORT_MAX_DURATION = MAX_RECORDING

        /**
         * Grace added to [IMPORT_MAX_DURATION] before rejecting an import, so a clip the user
         * thinks of as "30 seconds" (often 30.2–30.5 s of actual container duration) isn't rejected
         * for being a few hundred ms over (slice 07).
         */
        val IMPORT_DURATION_GRACE = 1.seconds

        /** Scratch files older than this are pruned at launch (parent D-8); 24 h. */
        val STALE_SCRATCH_MAX_AGE = 24.hours
    }

    class Factory(
        private val userPreferencesRepository: UserPreferencesRepository,
        private val videoStorage: VideoStorageRepository,
        private val videoProcessor: VideoProcessor,
        private val videoImporter: VideoImporter,
        private val renderScheduler: BoomerangRenderScheduler,
        private val analytics: AnalyticsReporter,
        /** See the constructor doc — MainActivity passes [MemoryPressure.lowMemoryProbe]. */
        private val isLowMemoryNow: () -> Boolean = { false },
        /** See the constructor doc — MainActivity passes `publishImageToPhotos(appContext, file)`. */
        private val publishPhotoToLibrary: suspend (File) -> Unit = {},
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(OpenLoopViewModel::class.java)) {
                return OpenLoopViewModel(
                    userPreferencesRepository,
                    videoStorage,
                    videoProcessor,
                    videoImporter,
                    renderScheduler,
                    analytics,
                    isLowMemoryNow,
                    // ioDispatcher keeps its default; the photo publisher is the 9th parameter.
                    publishPhotoToLibrary = publishPhotoToLibrary,
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
