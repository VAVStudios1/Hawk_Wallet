package com.example.hawk_wallet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.model.PaymentMethodUiModel
import com.example.hawk_wallet.ui.model.PaymentProviderOption
import com.example.hawk_wallet.ui.theme.HawkCoral
import com.example.hawk_wallet.ui.theme.HawkEmerald
import com.example.hawk_wallet.ui.theme.HawkEmeraldSoft
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

/**
 * One saved payout method with its Make Default / Deactivate actions.
 * [caption] is an optional quiet line under the UPI ID (e.g. marking an
 * existing SELL account whose provider is no longer offered for new ones).
 */
@Composable
fun PaymentMethodCard(
    method: PaymentMethodUiModel,
    onMakeDefault: () -> Unit,
    onDeactivate: () -> Unit,
    modifier: Modifier = Modifier,
    actionsEnabled: Boolean = true,
    caption: String? = null
) {
    val provider = PaymentProviderOption.fromCode(method.providerCode)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (method.isActive) 1f else 0.6f),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProviderLogo(provider = provider)
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
            }
            when {
                method.isDefault -> Badge(text = "Default", container = HawkEmeraldSoft, content = HawkEmerald, showCheck = true)
                !method.isActive -> Badge(
                    text = "Inactive",
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (method.isActive) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!method.isDefault) {
                    OutlinedButton(
                        onClick = onMakeDefault,
                        enabled = actionsEnabled,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text("Make Default", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDeactivate, enabled = actionsEnabled) {
                    Text("Deactivate", color = HawkCoral, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, container: Color, content: Color, showCheck: Boolean = false) {
    Row(
        modifier = Modifier
            .background(container, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showCheck) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(text = text, color = content, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Preview(showBackground = true)
@Composable
private fun PaymentMethodCardPreview() {
    Hawk_WalletTheme {
        Column(modifier = Modifier.padding(20.dp)) {
            PaymentMethodCard(
                method = PaymentMethodUiModel(1, "PHONEPE", "PhonePe", "", "lucky@ybl", null, isDefault = true, isActive = true),
                onMakeDefault = {},
                onDeactivate = {}
            )
            Spacer(modifier = Modifier.height(12.dp))
            PaymentMethodCard(
                method = PaymentMethodUiModel(2, "GOOGLE_PAY", "Google Pay", "", "lucky@okaxis", null, isDefault = false, isActive = true),
                onMakeDefault = {},
                onDeactivate = {}
            )
        }
    }
}
