package com.example.hawk_wallet.ui.screens.paymethods

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.components.PaymentMethodCard
import com.example.hawk_wallet.ui.model.PaymentMethodUiModel
import com.example.hawk_wallet.ui.theme.HawkCoral
import com.example.hawk_wallet.ui.theme.HawkIndigo
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

private val ScreenHorizontalPadding = 20.dp

@Composable
fun PaymentMethodsScreen(
    viewModel: PaymentMethodsViewModel,
    onBackClick: () -> Unit,
    onAddClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // GET /api/payout-methods/ each time the screen opens.
    LaunchedEffect(Unit) { viewModel.refresh() }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    PaymentMethodsContent(
        methods = uiState.methods,
        isLoading = uiState.isLoading,
        loadError = uiState.loadError,
        busyMethodId = uiState.busyMethodId,
        snackbarHostState = snackbarHostState,
        onBackClick = onBackClick,
        onAddClick = onAddClick,
        onRetry = viewModel::refresh,
        onMakeDefault = viewModel::makeDefault,
        onDeactivate = viewModel::deactivate
    )
}

@Composable
private fun PaymentMethodsContent(
    methods: List<PaymentMethodUiModel>,
    isLoading: Boolean,
    loadError: String?,
    busyMethodId: Long?,
    snackbarHostState: SnackbarHostState,
    onBackClick: () -> Unit,
    onAddClick: () -> Unit,
    onRetry: () -> Unit,
    onMakeDefault: (Long) -> Unit,
    onDeactivate: (Long) -> Unit
) {
    // Same order the backend list uses: default first, then newest.
    val ordered = remember(methods) {
        methods.sortedWith(compareByDescending<PaymentMethodUiModel> { it.isDefault }.thenByDescending { it.id })
    }
    var pendingDeactivateId by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "My Payment Methods", onBackClick = onBackClick) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        when {
            ordered.isEmpty() && isLoading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            ordered.isEmpty() && loadError != null -> LoadError(
                message = loadError,
                onRetry = onRetry,
                modifier = Modifier.padding(innerPadding)
            )
            ordered.isEmpty() -> EmptyPaymentMethods(onAddClick = onAddClick, modifier = Modifier.padding(innerPadding))
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (loadError != null) {
                        item {
                            AuthErrorBanner(message = loadError)
                            TextButton(onClick = onRetry) { Text("Retry") }
                        }
                    }
                    item {
                        Text(
                            text = "Payouts are sent to your default active method.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(ordered, key = { it.id }) { method ->
                        PaymentMethodCard(
                            method = method,
                            actionsEnabled = busyMethodId == null,
                            onMakeDefault = { onMakeDefault(method.id) },
                            onDeactivate = { pendingDeactivateId = method.id }
                        )
                    }
                    item {
                        AddMethodButton(onClick = onAddClick, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
                    }
                }
            }
        }
    }

    pendingDeactivateId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeactivateId = null },
            title = { Text("Deactivate this payment method?") },
            text = { Text("It will no longer receive payouts. You can add it again later.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeactivate(id)
                    pendingDeactivateId = null
                }) { Text("Deactivate", color = HawkCoral) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeactivateId = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun EmptyPaymentMethods(onAddClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .background(HawkIndigo.copy(alpha = 0.08f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.AccountBalanceWallet,
                    contentDescription = null,
                    tint = HawkIndigo,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "No payment methods yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Add your UPI ID and QR code so we know where to send your payouts.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(28.dp))
            AddMethodButton(onClick = onAddClick)
        }
    }
}

@Composable
private fun LoadError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Couldn't load your payment methods",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRetry, shape = RoundedCornerShape(16.dp)) { Text("Retry") }
        }
    }
}

@Composable
private fun AddMethodButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("Add Payment Method", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun PaymentMethodsPreview() {
    Hawk_WalletTheme {
        PaymentMethodsContent(
            methods = listOf(
                PaymentMethodUiModel(1, "PHONEPE", "PhonePe", "", "lucky@ybl", null, isDefault = true, isActive = true),
                PaymentMethodUiModel(2, "GOOGLE_PAY", "Google Pay", "", "lucky@okaxis", null, isDefault = false, isActive = true),
                PaymentMethodUiModel(3, "BHIM", "BHIM", "", "lucky@upi", null, isDefault = false, isActive = false)
            ),
            isLoading = false,
            loadError = null,
            busyMethodId = null,
            snackbarHostState = SnackbarHostState(),
            onBackClick = {},
            onAddClick = {},
            onRetry = {},
            onMakeDefault = {},
            onDeactivate = {}
        )
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun PaymentMethodsEmptyPreview() {
    Hawk_WalletTheme {
        PaymentMethodsContent(
            methods = emptyList(),
            isLoading = false,
            loadError = null,
            busyMethodId = null,
            snackbarHostState = SnackbarHostState(),
            onBackClick = {},
            onAddClick = {},
            onRetry = {},
            onMakeDefault = {},
            onDeactivate = {}
        )
    }
}
