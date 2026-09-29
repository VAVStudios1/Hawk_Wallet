package com.example.hawk_wallet.ui.screens.paymethods

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.remote.model.BuyMethodListItemDto
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.data.repository.BuyMethodRepository
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
 * The member's PERSONAL / BUY methods, backed by /api/buy-methods/: the
 * accounts they pay for orders from. Kept apart from [PaymentMethodsViewModel]
 * (BUSINESS / SELL). The backend owns every rule; after each change the list
 * is reloaded rather than updated locally.
 */
class BuyMethodsViewModel(private val repository: BuyMethodRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(BuyMethodsUiState())
    val uiState: StateFlow<BuyMethodsUiState> = _uiState

    /** Loads the list; called whenever the BUY accounts screen opens. */
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

    /** Starts a fresh Add BUY account flow. */
    fun startNewMethod() {
        _uiState.update { it.copy(form = BuyMethodForm()) }
    }

    /** Only PERSONAL providers can be BUY methods; anything else is ignored. */
    fun selectProvider(provider: PaymentProviderOption) {
        if (provider.side != ProviderSide.PERSONAL) return
        _uiState.update { it.copy(form = it.form.copy(provider = provider, generalError = null)) }
    }

    fun onUpiIdChange(value: String) {
        _uiState.update { it.copy(form = it.form.copy(upiId = value, upiError = null, generalError = null)) }
    }

    fun onLabelChange(value: String) {
        _uiState.update { it.copy(form = it.form.copy(label = value, labelError = null, generalError = null)) }
    }

    /**
     * Checks the required fields locally, then POSTs the method. Server
     * validation errors are shown under the matching field.
     */
    fun save() {
        val form = _uiState.value.form
        if (form.isSaving) return
        val provider = form.provider
        val upiId = form.upiId.trim()
        val upiError = if (upiId.isEmpty()) "Enter your UPI ID." else null
        val providerError = if (provider == null) "Choose your UPI app." else null
        if (upiError != null || provider == null) {
            _uiState.update { it.copy(form = form.copy(upiError = upiError, generalError = providerError)) }
            return
        }

        _uiState.update { it.copy(form = form.copy(isSaving = true, generalError = null)) }
        viewModelScope.launch {
            when (val result = repository.add(provider.code, upiId, form.label.trim())) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            form = it.form.copy(isSaving = false, saved = true),
                            message = "BUY account added successfully"
                        )
                    }
                    refresh()
                }
                is ApiResult.Error -> _uiState.update { it.copy(form = it.form.withSaveError(result)) }
            }
        }
    }

    /** The screen has navigated back after a successful save. */
    fun saveHandled() {
        _uiState.update { it.copy(form = it.form.copy(saved = false)) }
    }

    fun makeDefault(id: Long) = mutate(id, "Default BUY account updated") { repository.setDefault(id) }

    fun deactivate(id: Long) = mutate(id, "BUY account deactivated") { repository.deactivate(id) }

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

data class BuyMethodsUiState(
    val methods: List<PaymentMethodUiModel> = emptyList(),
    /** True while GET /api/buy-methods/ is in flight. */
    val isLoading: Boolean = false,
    val loadError: String? = null,
    /** Method whose Make Default / Deactivate request is in flight. */
    val busyMethodId: Long? = null,
    val form: BuyMethodForm = BuyMethodForm(),
    /** One-shot feedback for the list screen (e.g. after a save). */
    val message: String? = null
)

/** The in-progress Add BUY account flow. No QR: a BUY method is just provider + UPI ID + label. */
data class BuyMethodForm(
    val provider: PaymentProviderOption? = null,
    val upiId: String = "",
    val label: String = "",
    val upiError: String? = null,
    val labelError: String? = null,
    /** A save error that belongs to no single field. */
    val generalError: String? = null,
    val isSaving: Boolean = false,
    /** Set once the backend accepted the method; the screen then navigates back. */
    val saved: Boolean = false
)

private fun ApiResult.Error.userMessage(): String =
    if (statusCode == HTTP_UNAUTHORIZED) SESSION_EXPIRED else message

private fun BuyMethodForm.withSaveError(error: ApiResult.Error): BuyMethodForm {
    if (error.statusCode == HTTP_UNAUTHORIZED) {
        return copy(isSaving = false, generalError = SESSION_EXPIRED)
    }
    val fields = error.fieldErrors
    val upi = fields["upi_id"]?.firstOrNull()
        // 409 duplicate_upi_id carries its explanation in the message.
        ?: error.message.takeIf { fields["code"]?.contains("duplicate_upi_id") == true }
    val label = fields["label"]?.firstOrNull()
    val general = (fields["provider"] ?: fields["provider_code"] ?: fields["non_field_errors"])
        ?.firstOrNull()
        ?: error.message.takeIf { upi == null && label == null }
    return copy(isSaving = false, upiError = upi, labelError = label, generalError = general)
}

/** Shared with the Pay-In screen's "paid from" chooser. */
internal fun BuyMethodListItemDto.toUi() = PaymentMethodUiModel(
    id = id,
    providerCode = provider.code,
    providerName = provider.display_name,
    label = label,
    upiId = upi_id,
    qrImageUri = null, // BUY methods have no QR
    isDefault = is_default,
    isActive = is_active
)
