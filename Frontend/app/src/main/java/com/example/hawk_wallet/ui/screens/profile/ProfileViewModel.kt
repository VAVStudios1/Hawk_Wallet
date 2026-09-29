package com.example.hawk_wallet.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.local.SessionManager
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.data.repository.AuthRepository
import com.example.hawk_wallet.data.repository.ProfileRepository
import com.example.hawk_wallet.ui.screens.orders.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Profile tab, backed by GET /api/profile/. A failed refresh keeps the
 * last good data. Logout asks the backend to revoke this device's refresh
 * token, then always clears the local session.
 */
class ProfileViewModel(
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState

    /** Called whenever the Profile screen opens and on pull-to-refresh. */
    fun refresh() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            when (val result = profileRepository.getProfile()) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isLoading = false, profile = result.data.toUi(), errorMessage = null)
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(isLoading = false, errorMessage = result.userMessage())
                }
            }
        }
    }

    fun logout() {
        if (_uiState.value.isLoggingOut || _uiState.value.loggedOut) return
        _uiState.update { it.copy(isLoggingOut = true) }
        viewModelScope.launch {
            val refreshToken = sessionManager.getRefreshToken()
            if (!refreshToken.isNullOrBlank()) {
                // Best effort: an already revoked or expired token, an expired
                // access token or no network must not keep the user signed in
                // on this device, so the result is not checked.
                authRepository.logout(refreshToken)
            }
            sessionManager.clearSession()
            _uiState.update { it.copy(isLoggingOut = false, loggedOut = true) }
        }
    }
}
