package com.example.hawk_wallet.ui.screens.paymethods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.components.PaymentProviderCard
import com.example.hawk_wallet.ui.model.PaymentProviderOption
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

@Composable
fun AddPaymentMethodScreen(
    viewModel: PaymentMethodsViewModel,
    onBackClick: () -> Unit,
    onContinue: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    AddPaymentMethodContent(
        selected = uiState.form.provider,
        onSelect = viewModel::selectProvider,
        onBackClick = onBackClick,
        onContinue = onContinue
    )
}

@Composable
private fun AddPaymentMethodContent(
    selected: PaymentProviderOption?,
    onSelect: (PaymentProviderOption) -> Unit,
    onBackClick: () -> Unit,
    onContinue: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "Add SELL Account", onBackClick = onBackClick) },
        bottomBar = {
            Button(
                onClick = onContinue,
                enabled = selected != null,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .height(52.dp)
            ) {
                Text("Continue", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SellSectionHeader(
                    title = "Where should we send your money?",
                    description = "This account receives money from Hawk Wallet: the payouts for orders " +
                        "you claim. It is never used to pay for an order - you pay on the order's Pay-In screen.",
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            item {
                Text(
                    text = "Choose your business account",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            // BUSINESS / SELL providers only; personal UPI apps are BUY methods.
            items(PaymentProviderOption.business, key = { it.code }) { provider ->
                PaymentProviderCard(
                    provider = provider,
                    selected = provider == selected,
                    onClick = { onSelect(provider) }
                )
            }
        }
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun AddPaymentMethodPreview() {
    Hawk_WalletTheme {
        AddPaymentMethodContent(
            selected = PaymentProviderOption.PHONEPE_BUSINESS,
            onSelect = {},
            onBackClick = {},
            onContinue = {}
        )
    }
}
