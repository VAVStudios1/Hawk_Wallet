package com.example.hawk_wallet.ui.screens.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.components.SelectablePayoutMethodCard
import com.example.hawk_wallet.ui.format.DisplayFormat
import com.example.hawk_wallet.ui.model.OfferUiModel
import com.example.hawk_wallet.ui.model.PaymentMethodUiModel
import com.example.hawk_wallet.ui.screens.paymethods.sellAccountCaption
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

/**
 * Picks the payout method sent with the claim: where the PLATFORM will pay
 * the member. The backend fixes it on the order, so it cannot change later.
 */
@Composable
fun ChoosePayoutMethodScreen(
    viewModel: ClaimViewModel,
    onBackClick: () -> Unit,
    onAddPaymentMethod: () -> Unit,
    onBackToOrders: () -> Unit,
    onClaimed: (orderId: Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    // Reloads when the screen opens, including after adding a method in Wallet.
    LaunchedEffect(Unit) { viewModel.loadMethods() }

    LaunchedEffect(uiState.claimedOrderId) {
        uiState.claimedOrderId?.let { orderId ->
            viewModel.claimHandled()
            onClaimed(orderId)
        }
    }

    ChoosePayoutMethodContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onSelect = viewModel::selectMethod,
        onRetry = viewModel::loadMethods,
        onClaim = viewModel::claim,
        onAddPaymentMethod = onAddPaymentMethod,
        onBackToOrders = onBackToOrders
    )
}

@Composable
private fun ChoosePayoutMethodContent(
    uiState: ClaimUiState,
    onBackClick: () -> Unit,
    onSelect: (Long) -> Unit,
    onRetry: () -> Unit,
    onClaim: () -> Unit,
    onAddPaymentMethod: () -> Unit,
    onBackToOrders: () -> Unit
) {
    val offer = uiState.offer
    val canClaim = offer != null && !uiState.offerUnavailable && uiState.methods.isNotEmpty()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "Choose Payout Method", onBackClick = onBackClick) },
        bottomBar = {
            Column(modifier = Modifier.padding(horizontal = ScreenHorizontalPadding, vertical = 16.dp)) {
                when {
                    uiState.offerUnavailable -> PrimaryActionButton(text = "Back to Orders", onClick = onBackToOrders)
                    canClaim -> PrimaryActionButton(
                        text = "Confirm & Claim",
                        onClick = onClaim,
                        enabled = uiState.selectedMethodId != null,
                        loading = uiState.isClaiming
                    )
                }
            }
        }
    ) { innerPadding ->
        val modifier = Modifier.padding(innerPadding)
        when {
            offer == null -> CenteredMessage(
                title = "This order is no longer open",
                message = "Go back to Orders to see what is available now.",
                actionLabel = "Back to Orders",
                onAction = onBackToOrders,
                modifier = modifier
            )
            uiState.methods.isEmpty() && (uiState.methodsLoading || !uiState.methodsLoaded) && uiState.methodsError == null ->
                CenteredLoading(modifier)
            uiState.methods.isEmpty() && uiState.methodsError != null -> CenteredMessage(
                title = "Couldn't load your payment methods",
                message = uiState.methodsError,
                actionLabel = "Retry",
                onAction = onRetry,
                modifier = modifier
            )
            uiState.methods.isEmpty() -> CenteredMessage(
                title = "Add a payment method first",
                message = "Your payout is sent to one of your saved UPI payment methods. Add one to claim this order.",
                actionLabel = "Add Payment Method",
                onAction = onAddPaymentMethod,
                modifier = modifier
            )
            else -> LazyColumn(
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = "Where should we send your payout of ${DisplayFormat.rupees(offer.payoutTarget)}?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "This method is fixed on the order once you claim it. You'll pay " +
                            "${DisplayFormat.rupees(offer.orderAmount)} to Hawk Wallet on the next screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                uiState.claimError?.let { error ->
                    item { AuthErrorBanner(message = error) }
                }
                items(uiState.methods, key = { it.id }) { method ->
                    SelectablePayoutMethodCard(
                        method = method,
                        selected = method.id == uiState.selectedMethodId,
                        enabled = !uiState.isClaiming && !uiState.offerUnavailable,
                        onSelect = { onSelect(method.id) },
                        caption = sellAccountCaption(method.providerCode)
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun ChoosePayoutMethodPreview() {
    Hawk_WalletTheme {
        ChoosePayoutMethodContent(
            uiState = ClaimUiState(
                offer = OfferUiModel(1, "255.00", "4.00", "10.00", "265.00", null),
                methods = listOf(
                    PaymentMethodUiModel(1, "PHONEPE", "PhonePe", "", "lucky@ybl", null, isDefault = true, isActive = true),
                    PaymentMethodUiModel(2, "GOOGLE_PAY", "Google Pay", "", "lucky@okaxis", null, isDefault = false, isActive = true)
                ),
                methodsLoaded = true,
                selectedMethodId = 1
            ),
            onBackClick = {},
            onSelect = {},
            onRetry = {},
            onClaim = {},
            onAddPaymentMethod = {},
            onBackToOrders = {}
        )
    }
}
