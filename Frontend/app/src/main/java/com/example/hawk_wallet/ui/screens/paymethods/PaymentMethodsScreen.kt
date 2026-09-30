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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.hawk_wallet.ui.components.HawkBottomNavigation
import com.example.hawk_wallet.ui.components.HawkNavDestination
import com.example.hawk_wallet.ui.components.PaymentMethodCard
import com.example.hawk_wallet.ui.model.PaymentMethodUiModel
import com.example.hawk_wallet.ui.model.PaymentProviderOption
import com.example.hawk_wallet.ui.model.ProviderSide
import com.example.hawk_wallet.ui.theme.HawkCoral
import com.example.hawk_wallet.ui.theme.HawkIndigo
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

private val ScreenHorizontalPadding = 20.dp

/** Tab indexes of the [ BUY ] [ SELL ] control, in display order. */
const val PAYMENT_METHODS_TAB_BUY = 0
const val PAYMENT_METHODS_TAB_SELL = 1

/**
 * One Payment Methods screen with a BUY / SELL tab row (same TabRow style as
 * Orders). BUY lists the member's PERSONAL accounts (/api/buy-methods/), SELL
 * the BUSINESS payout accounts (/api/payout-methods/). The two lists never mix.
 */
@Composable
fun PaymentMethodsScreen(
    sellViewModel: PaymentMethodsViewModel,
    buyViewModel: BuyMethodsViewModel,
    onBackClick: () -> Unit,
    onAddSellClick: () -> Unit,
    onAddBuyClick: () -> Unit,
    initialTab: Int = PAYMENT_METHODS_TAB_BUY,
    onBottomNavSelect: (HawkNavDestination) -> Unit = {}
) {
    val sellState by sellViewModel.uiState.collectAsState()
    val buyState by buyViewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Both lists load each time the screen opens (also after returning from an add).
    LaunchedEffect(Unit) {
        sellViewModel.refresh()
        buyViewModel.refresh()
    }

    LaunchedEffect(sellState.message) {
        sellState.message?.let {
            snackbarHostState.showSnackbar(it)
            sellViewModel.messageShown()
        }
    }
    LaunchedEffect(buyState.message) {
        buyState.message?.let {
            snackbarHostState.showSnackbar(it)
            buyViewModel.messageShown()
        }
    }

    PaymentMethodsContent(
        initialTab = initialTab,
        sellState = sellState,
        buyState = buyState,
        snackbarHostState = snackbarHostState,
        onBackClick = onBackClick,
        onBottomNavSelect = onBottomNavSelect,
        sellActions = MethodListActions(
            onAdd = onAddSellClick,
            onRetry = sellViewModel::refresh,
            onMakeDefault = sellViewModel::makeDefault,
            onDeactivate = sellViewModel::deactivate
        ),
        buyActions = MethodListActions(
            onAdd = onAddBuyClick,
            onRetry = buyViewModel::refresh,
            onMakeDefault = buyViewModel::makeDefault,
            onDeactivate = buyViewModel::deactivate
        )
    )
}

/** Callbacks for one tab's account list. */
internal data class MethodListActions(
    val onAdd: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onMakeDefault: (Long) -> Unit = {},
    val onDeactivate: (Long) -> Unit = {}
)

@Composable
private fun PaymentMethodsContent(
    initialTab: Int,
    sellState: PaymentMethodsUiState,
    buyState: BuyMethodsUiState,
    snackbarHostState: SnackbarHostState,
    onBackClick: () -> Unit,
    sellActions: MethodListActions,
    buyActions: MethodListActions,
    onBottomNavSelect: (HawkNavDestination) -> Unit = {}
) {
    // Survives recomposition, rotation and returning from an add screen.
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "Payment Methods", onBackClick = onBackClick) },
        bottomBar = { HawkBottomNavigation(selected = HawkNavDestination.WALLET, onSelect = onBottomNavSelect) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                Tab(selected = tab == PAYMENT_METHODS_TAB_BUY, onClick = { tab = PAYMENT_METHODS_TAB_BUY }, text = { Text("BUY") })
                Tab(selected = tab == PAYMENT_METHODS_TAB_SELL, onClick = { tab = PAYMENT_METHODS_TAB_SELL }, text = { Text("SELL") })
            }
            if (tab == PAYMENT_METHODS_TAB_BUY) {
                BuyMethodsList(
                    methods = buyState.methods,
                    isLoading = buyState.isLoading,
                    loadError = buyState.loadError,
                    busyMethodId = buyState.busyMethodId,
                    actions = buyActions
                )
            } else {
                SellMethodsList(
                    methods = sellState.methods,
                    isLoading = sellState.isLoading,
                    loadError = sellState.loadError,
                    busyMethodId = sellState.busyMethodId,
                    actions = sellActions
                )
            }
        }
    }
}

/** SELL tab: the member's BUSINESS payout accounts. */
@Composable
private fun SellMethodsList(
    methods: List<PaymentMethodUiModel>,
    isLoading: Boolean,
    loadError: String?,
    busyMethodId: Long?,
    actions: MethodListActions
) {
    // Same order the backend list uses: default first, then newest.
    val ordered = remember(methods) {
        methods.sortedWith(compareByDescending<PaymentMethodUiModel> { it.isDefault }.thenByDescending { it.id })
    }
    var pendingDeactivateId by rememberSaveable { mutableStateOf<Long?>(null) }

    when {
        ordered.isEmpty() && isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        ordered.isEmpty() && loadError != null -> LoadError(message = loadError, onRetry = actions.onRetry)
        ordered.isEmpty() -> EmptyPaymentMethods(onAddClick = actions.onAdd)
        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (loadError != null) {
                    item {
                        AuthErrorBanner(message = loadError)
                        TextButton(onClick = actions.onRetry) { Text("Retry") }
                    }
                }
                item {
                    SellSectionHeader(
                        title = "Your payout accounts",
                        description = "Hawk Wallet sends your order payouts to these UPI accounts. " +
                            "When you claim an order, the account you choose (or your default) is " +
                            "fixed to that order.",
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                items(ordered, key = { it.id }) { method ->
                    PaymentMethodCard(
                        method = method,
                        actionsEnabled = busyMethodId == null,
                        onMakeDefault = { actions.onMakeDefault(method.id) },
                        onDeactivate = { pendingDeactivateId = method.id },
                        caption = sellAccountCaption(method.providerCode)
                    )
                }
                item {
                    AddMethodButton(
                        text = "Add SELL Account",
                        onClick = actions.onAdd,
                        modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
                    )
                }
            }
        }
    }

    pendingDeactivateId?.let { id ->
        DeactivateDialog(
            title = "Deactivate this SELL account?",
            text = "You won't be able to choose it for new orders. Orders you've already claimed " +
                "keep paying out to it. You can add it again later.",
            onConfirm = {
                actions.onDeactivate(id)
                pendingDeactivateId = null
            },
            onDismiss = { pendingDeactivateId = null }
        )
    }
}

/**
 * Quiet caption for a SELL card. SELL accounts saved before the BUY / SELL
 * split may use a provider that is now PERSONAL-only; they stay valid, so
 * they are marked as existing accounts rather than hidden. Display only.
 */
internal fun sellAccountCaption(providerCode: String): String? =
    when (PaymentProviderOption.fromCode(providerCode)?.side) {
        ProviderSide.PERSONAL -> "Existing SELL account"
        ProviderSide.BUSINESS -> "Business account"
        null -> null
    }

@Composable
internal fun DeactivateDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Deactivate", color = HawkCoral) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
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
            SellTag()
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No SELL accounts yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Add the UPI account where Hawk Wallet should send your payouts. " +
                    "You need one before you can claim an order.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(28.dp))
            AddMethodButton(text = "Add SELL Account", onClick = onAddClick)
        }
    }
}

@Composable
internal fun LoadError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
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

/** The "+ Add … Account" button at the bottom of each tab's list. */
@Composable
internal fun AddMethodButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun PaymentMethodsBuyPreview() {
    Hawk_WalletTheme {
        PaymentMethodsContent(
            initialTab = PAYMENT_METHODS_TAB_BUY,
            sellState = PaymentMethodsUiState(),
            buyState = BuyMethodsUiState(
                methods = listOf(
                    PaymentMethodUiModel(1, "PHONEPE", "PhonePe", "", "lucky@ybl", null, isDefault = true, isActive = true),
                    PaymentMethodUiModel(2, "GOOGLE_PAY", "Google Pay", "", "lucky@okaxis", null, isDefault = false, isActive = true)
                )
            ),
            snackbarHostState = SnackbarHostState(),
            onBackClick = {},
            sellActions = MethodListActions(),
            buyActions = MethodListActions()
        )
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun PaymentMethodsSellPreview() {
    Hawk_WalletTheme {
        PaymentMethodsContent(
            initialTab = PAYMENT_METHODS_TAB_SELL,
            sellState = PaymentMethodsUiState(
                methods = listOf(
                    PaymentMethodUiModel(1, "PHONEPE_BUSINESS", "PhonePe Business", "", "shop@ybl", null, isDefault = true, isActive = true),
                    PaymentMethodUiModel(2, "PHONEPE", "PhonePe", "", "lucky@axl", null, isDefault = false, isActive = true),
                    PaymentMethodUiModel(3, "BHARATPE", "BharatPe", "", "shop@bharatpe", null, isDefault = false, isActive = false)
                )
            ),
            buyState = BuyMethodsUiState(),
            snackbarHostState = SnackbarHostState(),
            onBackClick = {},
            sellActions = MethodListActions(),
            buyActions = MethodListActions()
        )
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun PaymentMethodsEmptySellPreview() {
    Hawk_WalletTheme {
        PaymentMethodsContent(
            initialTab = PAYMENT_METHODS_TAB_SELL,
            sellState = PaymentMethodsUiState(),
            buyState = BuyMethodsUiState(),
            snackbarHostState = SnackbarHostState(),
            onBackClick = {},
            sellActions = MethodListActions(),
            buyActions = MethodListActions()
        )
    }
}
