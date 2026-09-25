package com.example.hawk_wallet.ui.screens.paymethods

import com.example.hawk_wallet.ui.model.PaymentMethodUiModel
import com.example.hawk_wallet.ui.model.PaymentProviderOption

data class PaymentMethodsUiState(
    val methods: List<PaymentMethodUiModel> = emptyList(),
    /** True while GET /api/payout-methods/ is in flight. */
    val isLoading: Boolean = false,
    /** Why the last list load failed, if it did. */
    val loadError: String? = null,
    /** Method whose Make Default / Deactivate request is in flight. */
    val busyMethodId: Long? = null,
    val form: PaymentMethodForm = PaymentMethodForm(),
    /** One-shot feedback for the list screen (e.g. after a save). */
    val message: String? = null
)

/** The in-progress Add Payment Method flow. */
data class PaymentMethodForm(
    val provider: PaymentProviderOption? = null,
    val upiId: String = "",
    val qrImageUri: String? = null,
    val upiError: String? = null,
    val qrError: String? = null,
    /** A save error that belongs to no single field. */
    val generalError: String? = null,
    val isSaving: Boolean = false,
    /** Set once the backend accepted the method; the screen then navigates back. */
    val saved: Boolean = false
)
