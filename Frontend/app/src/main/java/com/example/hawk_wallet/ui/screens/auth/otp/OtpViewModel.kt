package com.example.hawk_wallet.ui.screens.auth.otp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.local.SessionManager
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.data.repository.AuthRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Matches the backend's default OTP_RESEND_COOLDOWN_SECONDS - purely a UI
// head start; the server remains authoritative and returns the exact
// retry_after_seconds on a 429 if this guess is ever wrong.
private const val DEFAULT_RESEND_COOLDOWN_SECONDS = 30
private const val PURPOSE_REGISTRATION = "registration"

class OtpViewModel(
    private val authRepository: AuthRepository,
    private val sessionManager: SessionManager,
    mobileNumber: String,
    purpose: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(OtpUiState(mobileNumber = mobileNumber, purpose = purpose))
    val uiState: StateFlow<OtpUiState> = _uiState

    private var cooldownJob: Job? = null

    init {
        startCooldown(DEFAULT_RESEND_COOLDOWN_SECONDS)
    }

    fun onOtpChange(value: String) {
        _uiState.update { it.copy(otp = value, otpError = null, generalError = null) }
    }

    fun verify() {
        val state = _uiState.value
        if (state.otp.isBlank()) {
            _uiState.update { it.copy(otpError = "Enter the OTP sent to your mobile number.") }
            return
        }

        _uiState.update { it.copy(isVerifying = true, generalError = null, otpError = null) }

        viewModelScope.launch {
            if (state.purpose == PURPOSE_REGISTRATION) {
                when (val result = authRepository.verifyRegistrationOtp(state.mobileNumber, state.otp)) {
                    is ApiResult.Success -> _uiState.update {
                        it.copy(isVerifying = false, registrationVerified = true)
                    }
                    is ApiResult.Error -> _uiState.update {
                        it.copy(isVerifying = false, generalError = result.message)
                    }
                }
            } else {
                when (val result = authRepository.verifyLoginOtp(state.mobileNumber, state.otp)) {
                    is ApiResult.Success -> {
                        val user = result.data.user
                        sessionManager.saveSession(result.data.access, result.data.refresh, user.name, user.role)
                        _uiState.update { it.copy(isVerifying = false, loggedInRole = user.role) }
                    }
                    is ApiResult.Error -> _uiState.update {
                        it.copy(isVerifying = false, generalError = result.message)
                    }
                }
            }
        }
    }

    fun resend() {
        val state = _uiState.value
        if (state.resendCooldownSeconds > 0 || state.isResending) return

        _uiState.update { it.copy(isResending = true, generalError = null) }

        viewModelScope.launch {
            when (val result = authRepository.resendOtp(state.mobileNumber, state.purpose)) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(isResending = false) }
                    startCooldown(DEFAULT_RESEND_COOLDOWN_SECONDS)
                }
                is ApiResult.Error -> {
                    val retryAfter = result.fieldErrors["retry_after_seconds"]?.firstOrNull()?.toIntOrNull()
                    _uiState.update { it.copy(isResending = false, generalError = result.message) }
                    if (retryAfter != null) startCooldown(retryAfter)
                }
            }
        }
    }

    private fun startCooldown(seconds: Int) {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            for (remaining in seconds downTo 0) {
                _uiState.update { it.copy(resendCooldownSeconds = remaining) }
                if (remaining > 0) delay(1000)
            }
        }
    }
}
