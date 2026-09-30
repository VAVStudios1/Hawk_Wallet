package com.example.hawk_wallet.ui.screens.auth.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.local.SavedLogin
import com.example.hawk_wallet.data.local.SessionManager
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.data.repository.AuthRepository
import com.example.hawk_wallet.ui.screens.auth.minutesUntil
import com.example.hawk_wallet.ui.screens.auth.validateMobileNumber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val HTTP_FORBIDDEN = 403
private const val CODE_ACCOUNT_NOT_VERIFIED = "account_not_verified"
private const val CODE_ACCOUNT_LOCKED = "account_locked"

/**
 * Password login: mobile number + password -> session -> role routing. No
 * OTP. An unverified account is sent to registration OTP verification.
 */
class LoginViewModel(
    private val authRepository: AuthRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState

    // The Credential Manager picker is offered once per visit to Login.
    private var savedLoginOffered = false

    // The login last filled in from Credential Manager; no need to save it again.
    private var pickedLogin: SavedLogin? = null

    init {
        // Pre-fill the number used last time (never the password).
        viewModelScope.launch {
            val remembered = sessionManager.rememberedLoginMobile()
            if (!remembered.isNullOrBlank()) {
                _uiState.update { if (it.mobileNumber.isEmpty()) it.copy(mobileNumber = remembered) else it }
            }
        }
    }

    fun onMobileNumberChange(value: String) {
        _uiState.update { it.copy(mobileNumber = value, mobileError = null, generalError = null, notice = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, passwordError = null, generalError = null, notice = null) }
    }

    /** True once, if the password is still empty: offer the saved-login picker. */
    fun shouldOfferSavedLogin(): Boolean {
        if (savedLoginOffered || _uiState.value.password.isNotEmpty()) return false
        savedLoginOffered = true
        return true
    }

    /** The user chose a saved login in the Credential Manager sheet. */
    fun onSavedLoginPicked(login: SavedLogin) {
        pickedLogin = login
        _uiState.update {
            it.copy(
                mobileNumber = login.mobileNumber,
                password = login.password,
                mobileError = null,
                passwordError = null,
                generalError = null
            )
        }
    }

    fun showNotice(message: String) {
        _uiState.update { it.copy(notice = message) }
    }

    fun login() {
        val state = _uiState.value
        if (state.isLoading) return
        val mobileError = validateMobileNumber(state.mobileNumber)
        val passwordError = if (state.password.isBlank()) "Password is required." else null

        if (mobileError != null || passwordError != null) {
            _uiState.update { it.copy(mobileError = mobileError, passwordError = passwordError) }
            return
        }

        _uiState.update { it.copy(isLoading = true, generalError = null, notice = null) }

        viewModelScope.launch {
            when (val result = authRepository.login(state.mobileNumber, state.password)) {
                is ApiResult.Success -> {
                    val data = result.data
                    sessionManager.saveSession(data.access, data.refresh, data.user.name, data.user.role)
                    sessionManager.rememberLoginMobile(data.user.mobile_number)
                    val typed = SavedLogin(state.mobileNumber, state.password)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            password = "",
                            loggedInRole = data.user.role,
                            credentialToSave = typed.takeIf { login -> login != pickedLogin }
                        )
                    }
                }
                is ApiResult.Error -> onLoginError(result, state.mobileNumber)
            }
        }
    }

    private fun onLoginError(error: ApiResult.Error, mobileNumber: String) {
        if (error.statusCode == HTTP_FORBIDDEN && error.errorCode == CODE_ACCOUNT_NOT_VERIFIED) {
            _uiState.update { it.copy(isLoading = false, password = "", verificationRequiredFor = mobileNumber) }
            return
        }

        val mobileFieldError = error.fieldErrors["mobile_number"]?.firstOrNull()
        val passwordFieldError = error.fieldErrors["password"]?.firstOrNull()
        val generalError = when {
            mobileFieldError != null || passwordFieldError != null -> null
            error.errorCode == CODE_ACCOUNT_LOCKED -> lockedMessage(error)
            else -> error.message
        }
        _uiState.update {
            it.copy(
                isLoading = false,
                mobileError = mobileFieldError,
                passwordError = passwordFieldError,
                generalError = generalError
            )
        }
    }

    private fun lockedMessage(error: ApiResult.Error): String {
        val seconds = error.fieldErrors["retry_after_seconds"]?.firstOrNull()?.toIntOrNull()
            ?: return error.message
        val minutes = minutesUntil(seconds)
        return "Too many failed login attempts. Please try again in $minutes minute${if (minutes == 1) "" else "s"}."
    }

    fun consumeLoggedIn() {
        _uiState.update { it.copy(loggedInRole = null, credentialToSave = null) }
    }

    fun consumeVerificationRequired() {
        _uiState.update { it.copy(verificationRequiredFor = null) }
    }
}
