package com.example.hawk_wallet.ui.screens.paymethods

import androidx.lifecycle.ViewModel
import com.example.hawk_wallet.ui.model.PaymentMethodUiModel
import com.example.hawk_wallet.ui.model.PaymentProviderOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Phase 2: local state only - nothing here calls the backend. The rules
 * mirror the backend's paymethods services so Phase 3 can swap in the API
 * without changing behaviour: the first method becomes default, one
 * default at a time, and deactivating the default promotes the newest
 * remaining active method (or leaves none).
 */
class PaymentMethodsViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(PaymentMethodsUiState())
    val uiState: StateFlow<PaymentMethodsUiState> = _uiState

    private var nextId = 1L

    /** Starts a fresh Add Payment Method flow. */
    fun startNewMethod() {
        _uiState.update { it.copy(form = PaymentMethodForm()) }
    }

    fun selectProvider(provider: PaymentProviderOption) {
        _uiState.update { it.copy(form = it.form.copy(provider = provider)) }
    }

    fun onUpiIdChange(value: String) {
        _uiState.update { it.copy(form = it.form.copy(upiId = value, upiError = null)) }
    }

    fun onQrSelected(uri: String) {
        _uiState.update { it.copy(form = it.form.copy(qrImageUri = uri, qrError = null)) }
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

    /** Validates the form and, if valid, adds the method locally. Returns true on success. */
    fun save(): Boolean {
        val form = _uiState.value.form
        val provider = form.provider ?: return false
        val upiId = form.upiId.trim()
        val upiError = if (upiId.isEmpty()) "Enter your UPI ID." else null
        val qrError = if (form.qrImageUri == null) "Upload your QR code." else null
        if (upiError != null || qrError != null) {
            _uiState.update { it.copy(form = form.copy(upiError = upiError, qrError = qrError)) }
            return false
        }

        val id = nextId++
        _uiState.update { state ->
            val method = PaymentMethodUiModel(
                id = id,
                providerCode = provider.code,
                providerName = provider.displayName,
                label = "",
                upiId = upiId,
                qrImageUri = form.qrImageUri,
                isDefault = state.methods.none { it.isActive && it.isDefault },
                isActive = true
            )
            state.copy(
                methods = listOf(method) + state.methods,
                form = PaymentMethodForm(),
                message = "Payment method added successfully"
            )
        }
        return true
    }

    fun makeDefault(id: Long) {
        _uiState.update { state ->
            val target = state.methods.firstOrNull { it.id == id && it.isActive } ?: return@update state
            state.copy(
                methods = state.methods.map { it.copy(isDefault = it.id == target.id) },
                message = "${target.providerName} is now your default"
            )
        }
    }

    fun deactivate(id: Long) {
        _uiState.update { state ->
            val target = state.methods.firstOrNull { it.id == id && it.isActive } ?: return@update state
            var methods = state.methods.map {
                if (it.id == id) it.copy(isActive = false, isDefault = false) else it
            }
            if (target.isDefault) {
                // Newest remaining active method takes over, as on the backend.
                val replacement = methods.filter { it.isActive }.maxByOrNull { it.id }
                if (replacement != null) {
                    methods = methods.map { it.copy(isDefault = it.id == replacement.id) }
                }
            }
            state.copy(methods = methods, message = "Payment method deactivated")
        }
    }

    fun messageShown() {
        _uiState.update { it.copy(message = null) }
    }
}
