package com.example.hawk_wallet.ui.screens.paymethods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.PaymentMethodCard
import com.example.hawk_wallet.ui.model.PaymentMethodUiModel

/**
 * BUY tab of Payment Methods: the member's PERSONAL accounts they pay for
 * orders from, in backend order (default first, then newest).
 */
@Composable
internal fun BuyMethodsList(
    methods: List<PaymentMethodUiModel>,
    isLoading: Boolean,
    loadError: String?,
    busyMethodId: Long?,
    actions: MethodListActions
) {
    var pendingDeactivateId by rememberSaveable { mutableStateOf<Long?>(null) }

    when {
        methods.isEmpty() && isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        methods.isEmpty() && loadError != null -> LoadError(message = loadError, onRetry = actions.onRetry)
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (loadError != null) {
                item {
                    AuthErrorBanner(message = loadError)
                    TextButton(onClick = actions.onRetry) { Text("Retry") }
                }
            }
            item {
                BuySectionHeader(
                    title = "Your personal UPI accounts",
                    description = "The accounts you pay for orders from. When you submit a payment's UTR, " +
                        "you choose which of these you paid from. Hawk Wallet never sends payouts here.",
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            if (methods.isEmpty()) {
                item { EmptyBuyMethods() }
            }
            items(methods, key = { it.id }) { method ->
                PaymentMethodCard(
                    method = method,
                    actionsEnabled = busyMethodId == null,
                    onMakeDefault = { actions.onMakeDefault(method.id) },
                    onDeactivate = { pendingDeactivateId = method.id }
                )
            }
            item {
                AddMethodButton(
                    text = "Add BUY Account",
                    onClick = actions.onAdd,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
                )
            }
        }
    }

    pendingDeactivateId?.let { id ->
        DeactivateDialog(
            title = "Deactivate this BUY account?",
            text = "You won't be able to choose it when you submit a payment. Payments you already " +
                "submitted keep showing it. You can add it again later.",
            onConfirm = {
                actions.onDeactivate(id)
                pendingDeactivateId = null
            },
            onDismiss = { pendingDeactivateId = null }
        )
    }
}

@Composable
private fun EmptyBuyMethods() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "No BUY accounts yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Add the personal UPI account you pay from. You need one to submit a payment for an order.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
