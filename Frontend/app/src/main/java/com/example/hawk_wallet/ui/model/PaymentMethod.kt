package com.example.hawk_wallet.ui.model

import androidx.annotation.DrawableRes
import com.example.hawk_wallet.R

/**
 * Which side of Hawk Wallet a provider serves. Mirrors the backend's
 * PaymentProvider flags: enabled_for_buy_methods (PERSONAL) and
 * enabled_for_payout_methods (BUSINESS). A provider is never both.
 */
enum class ProviderSide {
    /** PERSONAL / BUY: the member's own UPI app they pay for orders from. */
    PERSONAL,

    /** BUSINESS / SELL: a merchant account Hawk Wallet sends payouts to. */
    BUSINESS
}

/**
 * UPI apps a member can save, each for exactly one [side]. [code] is the
 * backend PaymentProvider code; PHONEPE and PHONEPE_BUSINESS are different
 * providers. [logoRes] is the brand mark in res/drawable (see each file for
 * its source), or null to show the generic provider icon.
 */
enum class PaymentProviderOption(
    val code: String,
    val displayName: String,
    val description: String,
    @DrawableRes val logoRes: Int?,
    val side: ProviderSide
) {
    PHONEPE("PHONEPE", "PhonePe", "Pay for orders from your PhonePe UPI ID", R.drawable.logo_phonepe, ProviderSide.PERSONAL),
    GOOGLE_PAY("GOOGLE_PAY", "Google Pay", "Pay for orders from your Google Pay UPI ID", R.drawable.logo_google_pay, ProviderSide.PERSONAL),
    BHIM("BHIM", "BHIM", "Pay for orders from your BHIM UPI ID", R.drawable.logo_bhim, ProviderSide.PERSONAL),
    MOBIKWIK("MOBIKWIK", "MobiKwik", "Pay for orders from your MobiKwik UPI ID", R.drawable.logo_mobikwik, ProviderSide.PERSONAL),
    BHARATPE("BHARATPE", "BharatPe", "Receive payouts to your BharatPe business account", null, ProviderSide.BUSINESS),
    PAYTM_BUSINESS("PAYTM_BUSINESS", "Paytm Business", "Receive payouts to your Paytm Business account", null, ProviderSide.BUSINESS),
    PHONEPE_BUSINESS("PHONEPE_BUSINESS", "PhonePe Business", "Receive payouts to your PhonePe Business account", null, ProviderSide.BUSINESS);

    companion object {
        /** Any provider, either side: saved methods may predate the BUY / SELL split. */
        fun fromCode(code: String): PaymentProviderOption? = entries.firstOrNull { it.code == code }

        /** Providers offered when adding a PERSONAL / BUY method. */
        val personal: List<PaymentProviderOption> get() = entries.filter { it.side == ProviderSide.PERSONAL }

        /** Providers offered when adding a BUSINESS / SELL (payout) method. */
        val business: List<PaymentProviderOption> get() = entries.filter { it.side == ProviderSide.BUSINESS }
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
