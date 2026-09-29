package com.example.hawk_wallet.ui.screens.orders

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.components.SelectablePayoutMethodCard
import com.example.hawk_wallet.ui.format.DisplayFormat
import com.example.hawk_wallet.ui.model.OrderPayoutMethodUiModel
import com.example.hawk_wallet.ui.model.OrderStatus
import com.example.hawk_wallet.ui.model.OrderUiModel
import com.example.hawk_wallet.ui.model.PayInIntentUiModel
import com.example.hawk_wallet.ui.model.PaymentMethodUiModel
import com.example.hawk_wallet.ui.theme.HawkEmerald
import com.example.hawk_wallet.ui.theme.HawkIndigo
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

/**
 * MEMBER -> PLATFORM. Shows the platform's receiving account from the pay-in
 * intent, opens the member's UPI app with the backend's upi:// link, then
 * takes the UTR and the member's PERSONAL / BUY account they paid from. The
 * member's own payout method (SELL) appears only as PAYOUT TO YOU.
 */
@Composable
fun PayInScreen(
    viewModel: PayInViewModel,
    onBackClick: () -> Unit,
    onViewStatus: () -> Unit,
    onSubmitted: () -> Unit,
    onAddBuyMethod: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Also re-runs when the member comes back from adding a BUY account.
    LaunchedEffect(Unit) { viewModel.load() }

    LaunchedEffect(uiState.submitted) {
        if (uiState.submitted) onSubmitted()
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    PayInContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBackClick = onBackClick,
        onRetry = viewModel::load,
        onViewStatus = onViewStatus,
        onOpenUpiApp = { uri ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
            } catch (e: ActivityNotFoundException) {
                viewModel.onUpiAppMissing()
            }
        },
        onCopyUpiId = { vpa ->
            clipboard.setText(AnnotatedString(vpa))
            viewModel.onUpiIdCopied()
        },
        onUtrChange = viewModel::onUtrChange,
        onSelectBuyMethod = viewModel::selectBuyMethod,
        onAddBuyMethod = onAddBuyMethod,
        onRetryBuyMethods = viewModel::refreshBuyMethods,
        onSubmit = viewModel::submit
    )
}

@Composable
private fun PayInContent(
    uiState: PayInUiState,
    snackbarHostState: SnackbarHostState,
    onBackClick: () -> Unit,
    onRetry: () -> Unit,
    onViewStatus: () -> Unit,
    onOpenUpiApp: (String) -> Unit,
    onCopyUpiId: (String) -> Unit,
    onUtrChange: (String) -> Unit,
    onSelectBuyMethod: (Long) -> Unit,
    onAddBuyMethod: () -> Unit,
    onRetryBuyMethods: () -> Unit,
    onSubmit: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "Pay for Order", onBackClick = onBackClick) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        val modifier = Modifier.padding(innerPadding)
        val intent = uiState.intent
        when {
            uiState.notPayableMessage != null -> CenteredMessage(
                title = "This order isn't awaiting payment",
                message = uiState.notPayableMessage,
                actionLabel = "View Order Status",
                onAction = onViewStatus,
                modifier = modifier
            )
            intent == null && uiState.loadError != null -> CenteredMessage(
                title = "Couldn't load payment details",
                message = uiState.loadError,
                actionLabel = "Retry",
                onAction = onRetry,
                modifier = modifier
            )
            intent == null -> CenteredLoading(modifier)
            else -> Column(
                modifier = modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ScreenHorizontalPadding, vertical = 8.dp)
            ) {
                PayToPlatformCard(intent = intent, onOpenUpiApp = onOpenUpiApp, onCopyUpiId = onCopyUpiId)
                uiState.order?.let { order ->
                    Spacer(modifier = Modifier.height(12.dp))
                    PayoutToYouCard(order = order)
                }
                Spacer(modifier = Modifier.height(12.dp))
                PaidFromCard(
                    uiState = uiState,
                    onSelect = onSelectBuyMethod,
                    onAddBuyMethod = onAddBuyMethod,
                    onRetry = onRetryBuyMethods
                )
                Spacer(modifier = Modifier.height(12.dp))
                SubmitUtrCard(
                    utr = uiState.utr,
                    utrError = uiState.utrError,
                    submitError = uiState.submitError,
                    isSubmitting = uiState.isSubmitting,
                    onUtrChange = onUtrChange,
                    onSubmit = onSubmit
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun PayToPlatformCard(
    intent: PayInIntentUiModel,
    onOpenUpiApp: (String) -> Unit,
    onCopyUpiId: (String) -> Unit
) {
    // UPI ID and amount are shown exactly as the backend's pay-in intent gives them.
    OrderCard(tag = "PAY TO") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = intent.payeeVpa,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { onCopyUpiId(intent.payeeVpa) }, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = "Copy UPI ID",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Amount",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = DisplayFormat.rupees(intent.amount),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        DetailRow(label = "Order reference", value = intent.orderReference)
        Spacer(modifier = Modifier.height(12.dp))
        PrimaryActionButton(text = "Pay with UPI App", onClick = { onOpenUpiApp(intent.upiUri) })
        Text(
            text = "Pay exactly this amount. After paying, come back here and enter the UTR.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun PayoutToYouCard(order: OrderUiModel) {
    OrderCard(tag = "PAYOUT TO YOU", accent = HawkEmerald) {
        Text(
            text = "After your payment is confirmed, Hawk Wallet pays you " +
                "${DisplayFormat.rupees(order.payoutTarget)} at:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        PayoutDestination(method = order.payoutMethod)
        Text(
            text = "Do not pay to this account - it is where you receive money.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/**
 * BUY side: which of the member's own PERSONAL accounts the payment came
 * from. Only BUY accounts are listed - never the order's payout (SELL) method.
 */
@Composable
private fun PaidFromCard(
    uiState: PayInUiState,
    onSelect: (Long) -> Unit,
    onAddBuyMethod: () -> Unit,
    onRetry: () -> Unit
) {
    OrderCard(tag = "PAID FROM · BUY", accent = HawkIndigo) {
        Text(
            text = "Which of your UPI accounts did you pay from?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        when {
            uiState.buyMethodsError != null && uiState.buyMethods.isEmpty() -> {
                AuthErrorBanner(message = uiState.buyMethodsError)
                OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) { Text("Retry") }
            }
            uiState.buyMethodsLoaded && uiState.buyMethods.isEmpty() -> Text(
                text = "You have no BUY accounts yet. Add the UPI account you paid from to submit this payment.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            else -> uiState.buyMethods.forEach { method ->
                SelectablePayoutMethodCard(
                    method = method,
                    selected = method.id == uiState.selectedBuyMethodId,
                    onSelect = { onSelect(method.id) },
                    enabled = !uiState.isSubmitting,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }
        uiState.buyMethodError?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        OutlinedButton(
            onClick = onAddBuyMethod,
            enabled = !uiState.isSubmitting,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            Text("Add BUY Account")
        }
    }
}

@Composable
private fun SubmitUtrCard(
    utr: String,
    utrError: String?,
    submitError: String?,
    isSubmitting: Boolean,
    onUtrChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    OrderCard {
        Text(
            text = "I have paid",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Enter the 12-digit UTR (UPI reference number) shown in your UPI app's payment details.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = utr,
            onValueChange = onUtrChange,
            label = { Text("UTR number") },
            singleLine = true,
            isError = utrError != null,
            supportingText = { Text(utrError ?: "${utr.length}/12 digits") },
            enabled = !isSubmitting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )
        if (submitError != null) {
            AuthErrorBanner(message = submitError, modifier = Modifier.padding(vertical = 8.dp))
        }
        Spacer(modifier = Modifier.height(4.dp))
        PrimaryActionButton(text = "Submit UTR", onClick = onSubmit, loading = isSubmitting)
    }
}

@Preview(showBackground = true, heightDp = 1100)
@Composable
private fun PayInPreview() {
    Hawk_WalletTheme {
        PayInContent(
            uiState = PayInUiState(
                order = OrderUiModel(
                    7, "HW-3KD9QX2M7P", OrderStatus.PAYMENT_REQUIRED, "255.00", "4.00", "10.00", "265.00",
                    OrderPayoutMethodUiModel("PHONEPE", "PhonePe", "lucky@ybl"),
                    "2026-09-26T10:00:00Z", null, null, null
                ),
                intent = PayInIntentUiModel(
                    "HW-3KD9QX2M7P", "255.00", "platform@ybl", "Hawk Wallet", "upi://pay?pa=platform@ybl", "token"
                ),
                utr = "0123",
                buyMethods = listOf(
                    PaymentMethodUiModel(3, "GOOGLE_PAY", "Google Pay", "", "lucky@okaxis", null, isDefault = true, isActive = true)
                ),
                buyMethodsLoaded = true,
                selectedBuyMethodId = 3
            ),
            snackbarHostState = SnackbarHostState(),
            onBackClick = {},
            onRetry = {},
            onViewStatus = {},
            onOpenUpiApp = {},
            onCopyUpiId = {},
            onUtrChange = {},
            onSelectBuyMethod = {},
            onAddBuyMethod = {},
            onRetryBuyMethods = {},
            onSubmit = {}
        )
    }
}
