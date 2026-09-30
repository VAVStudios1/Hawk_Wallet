package com.example.hawk_wallet.ui.screens.auth.forgot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.data.repository.AuthRepository
import com.example.hawk_wallet.ui.screens.auth.otp.DEFAULT_RESEND_COOLDOWN_SECONDS
import com.example.hawk_wallet.ui.screens.auth.otp.OtpUiState
import com.example.hawk_wallet.ui.screens.auth.validateMobileNumber
import com.example.hawk_wallet.ui.screens.auth.validateNewPassword
import com.example.hawk_wallet.ui.screens.auth.validatePasswordConfirmation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val CODE_RESET_TOKEN_INVALID = "reset_token_invalid"
private const val RESET_EXPIRED_MESSAGE = "This password reset has expired. Please start again."
private const val DEFAULT_SUCCESS_MESSAGE = "Password reset. Please log in with your new password."

/**
 * Forgot password: mobile number -> reset OTP -> new password -> Login.
 *
 * Scoped to the flow's navigation graph, so all three screens share it and
 * it is cleared when the flow ends or is left. The reset token is held only
 * here, in memory - never in navigation arguments, saved state or storage -
 * and is dropped as soon as it is used, rejected or abandoned.
 *
 * The backend answers the request step the same way whether or not the
 * number has an account; so does this screen.
 */
class ForgotPasswordViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _mobileState = MutableStateFlow(ForgotPasswordMobileUiState())
    val mobileState: StateFlow<ForgotPasswordMobileUiState> = _mobileState

    private val _otpState = MutableStateFlow(ForgotPasswordOtpUiState())
    val otpState: StateFlow<ForgotPasswordOtpUiState> = _otpState

    private val _resetState = MutableStateFlow(ResetPasswordUiState())
    val resetState: StateFlow<ResetPasswordUiState> = _resetState

    @Volatile
    private var resetToken: String? = null

    private var cooldownJob: Job? = null

    // --- step 1: mobile number ---------------------------------------------

    fun onMobileNumberChange(value: String) {
        _mobileState.update { it.copy(mobileNumber = value, mobileError = null, generalError = null) }
    }

    fun sendOtp() {
        val state = _mobileState.value
        if (state.isSending) return
        val mobileError = validateMobileNumber(state.mobileNumber)
        if (mobileError != null) {
            _mobileState.update { it.copy(mobileError = mobileError) }
            return
        }

        _mobileState.update { it.copy(isSending = true, generalError = null) }
        viewModelScope.launch {
            when (val result = authRepository.requestPasswordReset(state.mobileNumber)) {
                is ApiResult.Success -> {
                    discardResetToken()
                    _otpState.value = ForgotPasswordOtpUiState(otp = OtpUiState(mobileNumber = state.mobileNumber))
                    startCooldown(DEFAULT_RESEND_COOLDOWN_SECONDS)
                    _mobileState.update { it.copy(isSending = false, otpRequested = true) }
                }
                is ApiResult.Error -> _mobileState.update {
                    it.copy(
                        isSending = false,
                        mobileError = result.fieldErrors["mobile_number"]?.firstOrNull(),
                        generalError = if (result.fieldErrors.containsKey("mobile_number")) null else result.message
                    )
                }
            }
        }
    }

    fun consumeOtpRequested() {
        _mobileState.update { it.copy(otpRequested = false) }
    }

    // --- step 2: OTP -------------------------------------------------------

    fun onOtpChange(value: String) {
        _otpState.update { it.copy(otp = it.otp.copy(otp = value, otpError = null, generalError = null)) }
    }

    fun verifyOtp() {
        val otp = _otpState.value.otp
        if (otp.isVerifying) return
        if (otp.otp.isBlank()) {
            _otpState.update { it.copy(otp = it.otp.copy(otpError = "Enter the OTP sent to your mobile number.")) }
            return
        }

        _otpState.update { it.copy(otp = it.otp.copy(isVerifying = true, otpError = null, generalError = null)) }
        viewModelScope.launch {
            when (val result = authRepository.verifyPasswordReset(otp.mobileNumber, otp.otp)) {
                is ApiResult.Success -> {
                    resetToken = result.data.reset_token
                    _resetState.value = ResetPasswordUiState()
                    _otpState.update { it.copy(otp = it.otp.copy(isVerifying = false, otp = ""), verified = true) }
                }
                is ApiResult.Error -> _otpState.update {
                    it.copy(otp = it.otp.copy(isVerifying = false, generalError = result.message))
                }
            }
        }
    }

    fun resendOtp() {
        val otp = _otpState.value.otp
        if (otp.resendCooldownSeconds > 0 || otp.isResending) return

        _otpState.update { it.copy(otp = it.otp.copy(isResending = true, generalError = null)) }
        viewModelScope.launch {
            val result = authRepository.requestPasswordReset(otp.mobileNumber)
            _otpState.update {
                it.copy(
                    otp = it.otp.copy(
                        isResending = false,
                        generalError = (result as? ApiResult.Error)?.message
                    )
                )
            }
            if (result is ApiResult.Success) startCooldown(DEFAULT_RESEND_COOLDOWN_SECONDS)
        }
    }

    fun consumeOtpVerified() {
        _otpState.update { it.copy(verified = false) }
    }

    // --- step 3: new password ----------------------------------------------

    fun onNewPasswordChange(value: String) {
        _resetState.update { it.copy(newPassword = value, newPasswordError = null, generalError = null) }
    }

    fun onConfirmPasswordChange(value: String) {
        _resetState.update { it.copy(confirmPassword = value, confirmPasswordError = null, generalError = null) }
    }

    fun resetPassword() {
        val state = _resetState.value
        if (state.isSaving) return
        val token = resetToken
        if (token == null) {
            _resetState.update { it.copy(mustRestart = true, generalError = RESET_EXPIRED_MESSAGE) }
            return
        }

        val newPasswordError = validateNewPassword(state.newPassword)
        val confirmPasswordError = validatePasswordConfirmation(state.newPassword, state.confirmPassword)
        if (newPasswordError != null || confirmPasswordError != null) {
            _resetState.update {
                it.copy(newPasswordError = newPasswordError, confirmPasswordError = confirmPasswordError)
            }
            return
        }

        _resetState.update { it.copy(isSaving = true, generalError = null) }
        viewModelScope.launch {
            when (val result = authRepository.confirmPasswordReset(token, state.newPassword, state.confirmPassword)) {
                is ApiResult.Success -> {
                    discardResetToken()
                    _resetState.value = ResetPasswordUiState(
                        completedMessage = result.message.ifBlank { DEFAULT_SUCCESS_MESSAGE }
                    )
                }
                is ApiResult.Error -> onResetError(result)
            }
        }
    }

    private fun onResetError(error: ApiResult.Error) {
        if (error.errorCode == CODE_RESET_TOKEN_INVALID) {
            discardResetToken()
            _resetState.update { it.copy(isSaving = false, mustRestart = true, generalError = error.message) }
            return
        }
        val newPasswordErrors = error.fieldErrors["new_password"].orEmpty()
        val confirmError = error.fieldErrors["confirm_password"]?.firstOrNull()
        _resetState.update {
            it.copy(
                isSaving = false,
                newPasswordError = newPasswordErrors.joinToString(" ").ifBlank { null },
                confirmPasswordError = confirmError,
                // Field errors (e.g. Django's invalid_new_password rules) sit on
                // their fields; anything else is shown as a banner.
                generalError = if (newPasswordErrors.isNotEmpty() || confirmError != null) null else error.message
            )
        }
    }

    fun consumeCompleted() {
        _resetState.update { it.copy(completedMessage = null) }
    }

    /** Leaving the new-password step without finishing: the token is dropped. */
    fun abandonReset() {
        discardResetToken()
        _resetState.value = ResetPasswordUiState()
        _otpState.value = ForgotPasswordOtpUiState()
    }

    override fun onCleared() {
        discardResetToken()
        super.onCleared()
    }

    private fun discardResetToken() {
        resetToken = null
    }

    private fun startCooldown(seconds: Int) {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            for (remaining in seconds downTo 0) {
                _otpState.update { it.copy(otp = it.otp.copy(resendCooldownSeconds = remaining)) }
                if (remaining > 0) delay(1000)
            }
        }
    }
}
