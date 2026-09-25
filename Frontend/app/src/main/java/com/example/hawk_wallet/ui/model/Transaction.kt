package com.example.hawk_wallet.ui.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.ui.graphics.vector.ImageVector

enum class TransactionType(val icon: ImageVector) {
    ORDER_PAYMENT(Icons.Filled.ReceiptLong),
    COMMISSION(Icons.Filled.Percent),
    WITHDRAWAL(Icons.Filled.SwapHoriz),
    BONUS(Icons.Filled.CardGiftcard),
    PAYOUT(Icons.Filled.Payments),
    OTHER(Icons.Filled.ReceiptLong)
}

enum class TransactionStatus { SUCCESS, PENDING, FAILED, REJECTED, CANCELLED }

/**
 * [amount] is the plain formatted value (no sign); [TransactionItem] derives
 * the +/- prefix from [isCredit] so callers never bake presentation into data.
 */
data class TransactionUi(
    val id: String,
    val type: TransactionType,
    val title: String,
    val dateTime: String,
    val amount: String,
    val isCredit: Boolean,
    val status: TransactionStatus
)
