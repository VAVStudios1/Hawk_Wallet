package com.example.hawk_wallet.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.local.SessionManager
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.data.repository.ProfileRepository
import com.example.hawk_wallet.ui.screens.orders.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * POST /api/profile/change-password/. The backend checks the current
 * password and applies the password rules; this only catches empty fields
 * and a mismatch before sending. On success the backend has signed out every
 * other device and returns a new token pair, which replaces this device's.
 * Passwords are never logged or kept after the screen closes.
 */
class ChangePasswordViewModel(
    private val repository: ProfileRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChangePasswordUiState())
    val uiState: StateFlow<ChangePasswordUiState> = _uiState

    fun onCurrentPasswordChange(value: String) {
        _uiState.update { it.copy(currentPassword = value, currentPasswordError = null, generalError = null) }
    }

    fun onNewPasswordChange(value: String) {
        _uiState.update { it.copy(newPassword = value, newPasswordError = null, generalError = null) }
    }

    fun onConfirmPasswordChange(value: String) {
        _uiState.update { it.copy(confirmPassword = value, confirmPasswordError = null, generalError = null) }
    }

    fun submit() {
        val state = _uiState.value
        if (state.isSubmitting || state.successMessage != null) return

        val currentError = if (state.currentPassword.isEmpty()) "Current password is required." else null
        val newError = if (state.newPassword.isEmpty()) "New password is required." else null
        val confirmError = when {
            state.confirmPassword.isEmpty() -> "Please confirm your new password."
            state.confirmPassword != state.newPassword -> "Passwords do not match."
            else -> null
        }
        if (currentError != null || newError != null || confirmError != null) {
            _uiState.update {
                it.copy(
                    currentPasswordError = currentError,
                    newPasswordError = newError,
                    confirmPasswordError = confirmError
                )
            }
            return
        }

        _uiState.update { it.copy(isSubmitting = true, generalError = null) }
        viewModelScope.launch {
            when (val result = repository.changePassword(state.currentPassword, state.newPassword, state.confirmPassword)) {
                is ApiResult.Success -> {
                    sessionManager.updateTokens(result.data.access, result.data.refresh)
                    _uiState.update {
                        ChangePasswordUiState(successMessage = result.message.ifBlank { "Password changed." })
                    }
                }
                is ApiResult.Error -> {
                    val current = result.fieldErrors["current_password"].joined()
                    val new = result.fieldErrors["new_password"].joined()
                    val confirm = result.fieldErrors["confirm_password"].joined()
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            currentPasswordError = current,
                            newPasswordError = new,
                            confirmPasswordError = confirm,
                            generalError = if (current == null && new == null && confirm == null) {
                                result.userMessage()
                            } else {
                                null
                            }
                        )
                    }
                }
            }
        }
    }

    // The password rules can fail several ways at once; show every reason.
    private fun List<String>?.joined(): String? = this?.takeIf { it.isNotEmpty() }?.joinToString("\n")
}
