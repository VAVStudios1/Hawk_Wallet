package com.example.hawk_wallet.ui.screens.paymethods

import com.example.hawk_wallet.ui.model.PaymentMethodUiModel
import com.example.hawk_wallet.ui.model.PaymentProviderOption

data class PaymentMethodsUiState(
    val methods: List<PaymentMethodUiModel> = emptyList(),
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
    val qrError: String? = null
)
