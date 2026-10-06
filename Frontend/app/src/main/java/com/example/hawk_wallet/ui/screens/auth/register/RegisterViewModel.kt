package com.example.hawk_wallet.ui.screens.auth.register

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

class RegisterViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(RegisterUiState())
    val uiState: StateFlow<RegisterUiState> = _uiState

    fun onNameChange(value: String) {
        _uiState.update { it.copy(name = value, nameError = null, generalError = null) }
    }

    fun onMobileNumberChange(value: String) {
        _uiState.update { it.copy(mobileNumber = value, mobileError = null, generalError = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, passwordError = null, generalError = null) }
    }

    fun onConfirmPasswordChange(value: String) {
        _uiState.update { it.copy(confirmPassword = value, confirmPasswordError = null, generalError = null) }
    }

    fun onReferralCodeChange(value: String) {
        _uiState.update { it.copy(referralCode = value, referralCodeError = null, generalError = null) }
    }

    fun register() {
        val state = _uiState.value

        val nameError = if (state.name.isBlank()) "Name is required." else null
        val mobileError = when {
            state.mobileNumber.isBlank() -> "Mobile number is required."
            !MOBILE_NUMBER_REGEX.matches(state.mobileNumber) -> "Enter a valid 10-digit mobile number."
            else -> null
        }
        val passwordError = when {
            state.password.isBlank() -> "Password is required."
            state.password.length < 8 -> "Password must be at least 8 characters."
            state.password.all { it.isDigit() } -> "Password cannot be entirely numeric."
            else -> null
        }
        val confirmPasswordError = if (state.confirmPassword != state.password) {
            "Passwords do not match."
        } else {
            null
        }

        if (listOf(nameError, mobileError, passwordError, confirmPasswordError).any { it != null }) {
            _uiState.update {
                it.copy(
                    nameError = nameError,
                    mobileError = mobileError,
                    passwordError = passwordError,
                    confirmPasswordError = confirmPasswordError
                )
            }
            return
        }

        _uiState.update { it.copy(isLoading = true, generalError = null) }

        viewModelScope.launch {
            when (
                val result = authRepository.register(
                    state.name, state.mobileNumber, state.password, state.confirmPassword, state.referralCode
                )
            ) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isLoading = false, registeredMobileNumber = result.data.mobile_number)
                }
                is ApiResult.Error -> {
                    val nameFieldError = result.fieldErrors["name"]?.firstOrNull()
                    val mobileFieldError = result.fieldErrors["mobile_number"]?.firstOrNull()
                    val passwordFieldError = result.fieldErrors["password"]?.firstOrNull()
                    val confirmPasswordFieldError = result.fieldErrors["confirm_password"]?.firstOrNull()
                    val referralCodeFieldError = result.fieldErrors["referral_code"]?.firstOrNull()
                    val hasFieldErrors = listOf(
                        nameFieldError, mobileFieldError, passwordFieldError, confirmPasswordFieldError,
                        referralCodeFieldError
                    ).any { it != null }

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            nameError = nameFieldError,
                            mobileError = mobileFieldError,
                            passwordError = passwordFieldError,
                            confirmPasswordError = confirmPasswordFieldError,
                            referralCodeError = referralCodeFieldError,
                            generalError = if (hasFieldErrors) null else result.message
                        )
                    }
                }
            }
        }
    }

    fun consumeRegistered() {
        _uiState.update { it.copy(registeredMobileNumber = null) }
    }
}
