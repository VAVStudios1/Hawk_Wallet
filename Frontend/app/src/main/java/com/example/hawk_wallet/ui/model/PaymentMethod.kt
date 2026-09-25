package com.example.hawk_wallet.ui.model

import androidx.annotation.DrawableRes
import com.example.hawk_wallet.R

/**
 * UPI apps a member can save as a payout destination. [logoRes] is the
 * provider's brand mark in res/drawable (see each file for its source).
 * [code] is the intended PaymentProvider code for Phase 3.
 */
enum class PaymentProviderOption(
    val code: String,
    val displayName: String,
    val description: String,
    @DrawableRes val logoRes: Int
) {
    PHONEPE("PHONEPE", "PhonePe", "Receive payouts to your PhonePe UPI ID", R.drawable.logo_phonepe),
    GOOGLE_PAY("GOOGLE_PAY", "Google Pay", "Receive payouts to your Google Pay UPI ID", R.drawable.logo_google_pay),
    BHIM("BHIM", "BHIM", "Receive payouts to your BHIM UPI ID", R.drawable.logo_bhim),
    MOBIKWIK("MOBIKWIK", "MobiKwik", "Receive payouts to your MobiKwik UPI ID", R.drawable.logo_mobikwik);

    companion object {
        fun fromCode(code: String): PaymentProviderOption? = entries.firstOrNull { it.code == code }
    }
}

/**
 * UI-only view of one saved payout method. Not an API model: Phase 3 maps
 * the backend's response DTOs into this. [qrImageUri] is a local content URI.
 */
data class PaymentMethodUiModel(
    val id: Long,
    val providerCode: String,
    val providerName: String,
    val label: String,
    val upiId: String,
    val qrImageUri: String?,
    val isDefault: Boolean,
    val isActive: Boolean
)
