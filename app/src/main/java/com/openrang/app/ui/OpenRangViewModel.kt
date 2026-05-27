package com.openrang.app.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OpenRangViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<OpenRangUiState>(OpenRangUiState.CheckingPermissions)
    val uiState: StateFlow<OpenRangUiState> = _uiState.asStateFlow()

    fun onPermissionsChecked(granted: Boolean) {
        _uiState.value = if (granted) {
            OpenRangUiState.ReadyToCapture
        } else {
            OpenRangUiState.PermissionDenied
        }
    }
}
