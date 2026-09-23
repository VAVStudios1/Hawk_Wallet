package com.example.hawk_wallet.ui.screens.auth.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Assumes 10-digit Indian mobile numbers (matches the backend's validator).
private val MOBILE_NUMBER_REGEX = Regex("^[6-9]\\d{9}$")

class LoginViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState

    fun onMobileNumberChange(value: String) {
        _uiState.update { it.copy(mobileNumber = value, mobileError = null, generalError = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, passwordError = null, generalError = null) }
    }

    fun login() {
        val state = _uiState.value
        val mobileError = validateMobile(state.mobileNumber)
        val passwordError = if (state.password.isBlank()) "Password is required." else null

        if (mobileError != null || passwordError != null) {
            _uiState.update { it.copy(mobileError = mobileError, passwordError = passwordError) }
            return
        }

        _uiState.update { it.copy(isLoading = true, generalError = null) }

        viewModelScope.launch {
            when (val result = authRepository.login(state.mobileNumber, state.password)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isLoading = false, otpRequestedFor = result.data.mobile_number)
                }
                is ApiResult.Error -> {
                    val mobileFieldError = result.fieldErrors["mobile_number"]?.firstOrNull()
                    val passwordFieldError = result.fieldErrors["password"]?.firstOrNull()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            mobileError = mobileFieldError,
                            passwordError = passwordFieldError,
                            generalError = if (mobileFieldError == null && passwordFieldError == null) {
                                result.message
                            } else {
                                null
                            }
                        )
                    }
                }
            }
        }
    }

    fun consumeOtpRequested() {
        _uiState.update { it.copy(otpRequestedFor = null) }
    }

    private fun validateMobile(mobileNumber: String): String? = when {
        mobileNumber.isBlank() -> "Mobile number is required."
        !MOBILE_NUMBER_REGEX.matches(mobileNumber) -> "Enter a valid 10-digit mobile number."
        else -> null
    }
}
