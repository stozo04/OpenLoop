package com.openrang.app.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OpenRangViewModel : ViewModel() {
    // Start with the beautiful Onboarding Carousel
    private val _uiState = MutableStateFlow<OpenRangUiState>(OpenRangUiState.Onboarding)
    val uiState: StateFlow<OpenRangUiState> = _uiState.asStateFlow()

    fun onOnboardingCompleted() {
        _uiState.value = OpenRangUiState.CheckingPermissions
    }

    fun onPermissionsChecked(granted: Boolean) {
        _uiState.value = if (granted) {
            OpenRangUiState.ReadyToCapture
        } else {
            OpenRangUiState.PermissionDenied
        }
    }
}
