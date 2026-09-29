package com.example.hawk_wallet.ui.screens.paymethods

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.remote.model.PayoutMethodListItemDto
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.data.repository.PayoutMethodRepository
import com.example.hawk_wallet.ui.model.PaymentMethodUiModel
import com.example.hawk_wallet.ui.model.PaymentProviderOption
import com.example.hawk_wallet.ui.model.ProviderSide
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val HTTP_UNAUTHORIZED = 401
private const val SESSION_EXPIRED = "Your session has expired. Please log in again."

/**
 * The member's payout methods, backed by /api/payout-methods/. The backend
 * owns every rule (which method is default, duplicates, UPI/QR validation);
 * after each change the list is reloaded rather than updated locally.
 */
class PaymentMethodsViewModel(private val repository: PayoutMethodRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(PaymentMethodsUiState())
    val uiState: StateFlow<PaymentMethodsUiState> = _uiState

    /** Loads the list; called whenever the Payment Methods screen opens. */
    fun refresh() {
        _uiState.update { it.copy(isLoading = true, loadError = null) }
        viewModelScope.launch {
            when (val result = repository.list()) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isLoading = false, methods = result.data.map { dto -> dto.toUi() })
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(isLoading = false, loadError = result.userMessage())
                }
            }
        }
    }

    /** Starts a fresh Add Payment Method flow. */
    fun startNewMethod() {
        _uiState.update { it.copy(form = PaymentMethodForm()) }
    }

    /** Only BUSINESS providers can be new payout (SELL) methods; anything else is ignored. */
    fun selectProvider(provider: PaymentProviderOption) {
        if (provider.side != ProviderSide.BUSINESS) return
        _uiState.update { it.copy(form = it.form.copy(provider = provider)) }
    }

    fun onUpiIdChange(value: String) {
        _uiState.update { it.copy(form = it.form.copy(upiId = value, upiError = null, generalError = null)) }
    }

    fun onQrSelected(uri: String) {
        _uiState.update { it.copy(form = it.form.copy(qrImageUri = uri, qrError = null, generalError = null)) }
    }

    /** The picked file could not be read as an image. */
    fun onQrUnreadable() {
        _uiState.update {
            it.copy(
                form = it.form.copy(
                    qrImageUri = null,
                    qrError = "We couldn't read that image. Please choose a clear photo of your QR code."
                )
            )
        }
    }

    fun removeQr() {
        _uiState.update { it.copy(form = it.form.copy(qrImageUri = null)) }
    }

    /**
     * Checks the required fields locally, then POSTs the method. Server
     * validation errors are shown under the matching field.
     */
    fun save() {
        val form = _uiState.value.form
        if (form.isSaving) return
        val provider = form.provider ?: return
        val upiId = form.upiId.trim()
        val qrUri = form.qrImageUri
        val upiError = if (upiId.isEmpty()) "Enter your UPI ID." else null
        val qrError = if (qrUri == null) "Upload your QR code." else null
        if (upiError != null || qrError != null || qrUri == null) {
            _uiState.update { it.copy(form = form.copy(upiError = upiError, qrError = qrError)) }
            return
        }

        _uiState.update { it.copy(form = form.copy(isSaving = true, generalError = null)) }
        viewModelScope.launch {
            when (val result = repository.add(provider.code, upiId, label = "", qrImageUri = qrUri)) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            form = it.form.copy(isSaving = false, saved = true),
                            message = "Payment method added successfully"
                        )
                    }
                    refresh()
                }
                is ApiResult.Error -> _uiState.update { it.copy(form = it.form.withSaveError(result)) }
            }
        }
    }

    /**
     * The screen has navigated back after a successful save. Only the flag
     * is cleared: the form is reset by startNewMethod on the next add, so the
     * details screen never sees a missing provider while it is leaving.
     */
    fun saveHandled() {
        _uiState.update { it.copy(form = it.form.copy(saved = false)) }
    }

    fun makeDefault(id: Long) = mutate(id, "Default payment method updated") { repository.setDefault(id) }

    fun deactivate(id: Long) = mutate(id, "Payment method deactivated") { repository.deactivate(id) }

    fun messageShown() {
        _uiState.update { it.copy(message = null) }
    }

    private fun mutate(id: Long, successMessage: String, call: suspend () -> ApiResult<Unit>) {
        if (_uiState.value.busyMethodId != null) return
        _uiState.update { it.copy(busyMethodId = id) }
        viewModelScope.launch {
            val result = call()
            _uiState.update {
                it.copy(
                    busyMethodId = null,
                    message = when (result) {
                        is ApiResult.Success -> successMessage
                        is ApiResult.Error -> result.userMessage()
                    }
                )
            }
            // Reload either way: the backend decides the resulting default.
            refresh()
        }
    }
}

private fun ApiResult.Error.userMessage(): String =
    if (statusCode == HTTP_UNAUTHORIZED) SESSION_EXPIRED else message

private fun PaymentMethodForm.withSaveError(error: ApiResult.Error): PaymentMethodForm {
    if (error.statusCode == HTTP_UNAUTHORIZED) {
        return copy(isSaving = false, generalError = SESSION_EXPIRED)
    }
    val fields = error.fieldErrors
    val upi = fields["upi_id"]?.firstOrNull()
        // 409 duplicate_upi_id carries its explanation in the message.
        ?: error.message.takeIf { fields["code"]?.contains("duplicate_upi_id") == true }
    val qr = fields["qr_image"]?.firstOrNull()
    val general = (fields["provider"] ?: fields["provider_code"] ?: fields["label"] ?: fields["non_field_errors"])
        ?.firstOrNull()
        ?: error.message.takeIf { upi == null && qr == null }
    return copy(isSaving = false, upiError = upi, qrError = qr, generalError = general)
}

/** Shared with the order claim flow's payout-method chooser. */
internal fun PayoutMethodListItemDto.toUi() = PaymentMethodUiModel(
    id = id,
    providerCode = provider.code,
    providerName = provider.display_name,
    label = label,
    upiId = upi_id,
    qrImageUri = null, // the backend never returns the QR
    isDefault = is_default,
    isActive = is_active
)
