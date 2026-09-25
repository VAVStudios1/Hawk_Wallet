package com.example.hawk_wallet.ui.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.hawk_wallet.ui.theme.HawkAmberDark
import com.example.hawk_wallet.ui.theme.HawkEmerald
import com.example.hawk_wallet.ui.theme.HawkIndigo
import com.example.hawk_wallet.ui.theme.HawkVioletBlue

/**
 * UPI apps a member can save as a payout destination. Icons are generic
 * Material symbols in Hawk Wallet colours - no third-party logos.
 * [code] is the intended PaymentProvider code for Phase 3.
 */
enum class PaymentProviderOption(
    val code: String,
    val displayName: String,
    val description: String,
    val icon: ImageVector,
    val accent: Color
) {
    PHONEPE("PHONEPE", "PhonePe", "Receive payouts to your PhonePe UPI ID", Icons.Filled.PhoneAndroid, HawkVioletBlue),
    GOOGLE_PAY("GOOGLE_PAY", "Google Pay", "Receive payouts to your Google Pay UPI ID", Icons.Filled.Payments, HawkIndigo),
    BHIM("BHIM", "BHIM", "Receive payouts to your BHIM UPI ID", Icons.Filled.AccountBalance, HawkEmerald),
    MOBIKWIK("MOBIKWIK", "MobiKwik", "Receive payouts to your MobiKwik UPI ID", Icons.Filled.AccountBalanceWallet, HawkAmberDark);

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
