package com.openrang.app.ui

import com.openrang.app.media.BoomerangMode
import com.openrang.app.media.VideoFilter
import java.io.File

sealed interface OpenRangUiState {
    /** App is loading user preferences from DataStore before deciding the first screen. */
    object Initializing : OpenRangUiState
    object Onboarding : OpenRangUiState
    object CheckingPermissions : OpenRangUiState

    /**
     * User has denied a required permission at least once (but not permanently).
     * Show an educational rationale before re-requesting, per Google's permission flow.
     */
    object PermissionRationale : OpenRangUiState
    object PermissionDenied : OpenRangUiState
    object ReadyToCapture : OpenRangUiState
    object Recording : OpenRangUiState

    /**
     * Post-capture editing entry point (slice 02): the just-captured clip is loaded into the Trim
     * screen. [source] identifies which clip — currently only a freshly-recorded [EditorSource.ScratchClip].
     * The resolved [File] + duration + trim handles live in [OpenRangViewModel]'s sibling
     * `editorState` flow, so this routed state stays a slim discriminator (no [File] in the router).
     */
    data class Trim(val source: EditorSource) : OpenRangUiState

    /**
     * The tabbed boomerang editor (slice 03), opened from the Trim screen's NEXT. Like [Trim] this is
     * a slim discriminator: the trim window stays in [OpenRangViewModel]'s `editorState` and the
     * editor's tab selections live in its sibling `editorTabState` flow, so the routed state carries
     * only the source identity. (The slice-03 PRD sketched `BoomerangEditor(source, trim)`; we keep
     * the established slim-discriminator-plus-sibling-flow shape that `Trim` already uses.)
     */
    data class BoomerangEditor(val source: EditorSource) : OpenRangUiState

    /** Full-screen spinner shown while [OpenRangViewModel] renders the boomerang (slice 02). */
    object Processing : OpenRangUiState

    /**
     * Loader shown while a video picked from the library is probed for duration and copied into a
     * scratch file (slice 07). On success the app routes to [Trim] exactly as a fresh capture would;
     * on a too-long clip or a copy failure it returns to [Gallery] with a dialog / snackbar.
     */
    object ImportingVideo : OpenRangUiState

    object Gallery : OpenRangUiState
}

/**
 * Identifies the clip an editor session ([OpenRangUiState.Trim]) is operating on.
 *
 * Slice 02 only mints [ScratchClip] (a freshly-captured clip). `GalleryClip` — re-editing an
 * existing raw tapped from the gallery — arrives in slice 07.
 */
sealed interface EditorSource {
    data class ScratchClip(val uuid: String) : EditorSource
}

/**
 * The Trim screen's working state, held by [OpenRangViewModel] in a sibling flow (not in the routed
 * [OpenRangUiState.Trim], which stays a slim discriminator). [trimStartMs]/[trimEndMs] are the user's
 * current handle positions; they survive a failed render so the selection is preserved when routing
 * back to Trim.
 */
data class TrimState(
    val sourceFile: File,
    val sourceDurationMs: Long,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = sourceDurationMs,
)

/** The interactive tabs in the boomerang editor's bottom tab bar. */
enum class EditorTab {
    /** Direction chips (slice 03). */
    DIRECTION,

    /** Speed slider (slice 04). */
    SPEED,

    /** Color looks / filters (slice 05) — the third tab, repurposed from the disabled Reps stub. */
    LOOKS,
}

/**
 * The boomerang editor's tab selections, held by [OpenRangViewModel] in a sibling flow to the routed
 * [OpenRangUiState.BoomerangEditor] (same slim-discriminator split as [TrimState] is to
 * [OpenRangUiState.Trim]). Slice 03 added the Direction tab ([mode]); slice 04 the Speed tab
 * ([speed]); slice 05 the Looks tab ([filter]); plus the [activeTab] selector that switches the
 * content panel between them.
 *
 * [speed] is the playback speed multiplier (0.25×–3.0×, default 2.0×): a player-side effect on the
 * preview (`setPlaybackSpeed`) and a per-clip render effect at save (`SpeedChangeEffect`). It is *not*
 * baked into [reversedFile], so changing speed never invalidates the cached reverse.
 *
 * [filter] is the color look (default [VideoFilter.ORIGINAL]): a Media3 video effect applied live in
 * the preview (`setVideoEffects`) and baked into the render. Like [speed] it's an effect, not a
 * re-trim, so it never touches [reversedFile] or the output duration.
 *
 * [reversedFile] caches the reversed clip the preview plays for any reverse-containing [mode]; it is
 * produced once per trim (shared with the render via the same `VideoProcessor`) and is `null` until
 * generated. [isReversedFileLoading] drives the "Loopifying…" shimmer over the preview.
 *
 * [reverseFailed] is set when reverse generation throws (e.g. an imported clip whose codec the device
 * can't tone-map/transcode). It exists so a failed reverse stops the shimmer and surfaces a retry
 * instead of leaving "Loopifying…" on screen forever — the shimmer is gated on `reversedFile == null`,
 * so without this flag a failure would hang the editor (the bug imports first exposed).
 */
data class EditorTabState(
    val mode: BoomerangMode = BoomerangMode.FORWARD_THEN_REVERSE,
    val speed: Float = 2.0f,
    val filter: VideoFilter = VideoFilter.ORIGINAL,
    val activeTab: EditorTab = EditorTab.DIRECTION,
    val reversedFile: File? = null,
    val isReversedFileLoading: Boolean = false,
    val reverseFailed: Boolean = false,
)
