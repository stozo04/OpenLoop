package com.openrang.app.ui

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
    object Processing : OpenRangUiState
    data class LoopingPreview(val videoPath: String, val playbackSpeed: Float) : OpenRangUiState
    object Gallery : OpenRangUiState
}
