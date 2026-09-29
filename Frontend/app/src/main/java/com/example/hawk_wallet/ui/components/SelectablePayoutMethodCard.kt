package com.example.hawk_wallet.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.model.PaymentMethodUiModel
import com.example.hawk_wallet.ui.model.PaymentProviderOption
import com.example.hawk_wallet.ui.theme.HawkEmerald
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

/**
 * A saved payout method as a single-choice option, in the [PaymentMethodCard] style.
 * [caption] is the same optional quiet line [PaymentMethodCard] shows under the UPI ID.
 */
@Composable
fun SelectablePayoutMethodCard(
    method: PaymentMethodUiModel,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    caption: String? = null
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 3.dp else 1.dp),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProviderLogo(provider = PaymentProviderOption.fromCode(method.providerCode))
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = method.providerName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = method.upiId,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (caption != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (method.isDefault) {
                    Text(
                        text = "Default",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = HawkEmerald
                    )
                }
            }
            // Selection is handled by the whole card.
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Spacer(modifier = Modifier.width(12.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SelectablePayoutMethodCardPreview() {
    Hawk_WalletTheme {
        Column(modifier = Modifier.padding(20.dp)) {
            SelectablePayoutMethodCard(
                method = PaymentMethodUiModel(1, "PHONEPE", "PhonePe", "", "lucky@ybl", null, isDefault = true, isActive = true),
                selected = true,
                onSelect = {}
            )
            Spacer(modifier = Modifier.height(12.dp))
            SelectablePayoutMethodCard(
                method = PaymentMethodUiModel(2, "BHIM", "BHIM", "", "lucky@upi", null, isDefault = false, isActive = true),
                selected = false,
                onSelect = {}
            )
        }
    }
}
