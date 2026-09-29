package com.example.hawk_wallet.ui.screens.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.components.QrUploadSection
import com.example.hawk_wallet.ui.components.rememberQrImagePicker
import com.example.hawk_wallet.ui.format.DisplayFormat
import com.example.hawk_wallet.ui.screens.orders.AmountRow
import com.example.hawk_wallet.ui.screens.orders.DetailRow
import com.example.hawk_wallet.ui.screens.orders.OrderCard
import com.example.hawk_wallet.ui.screens.orders.PrimaryActionButton
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.theme.HawkEmerald
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

/** Admin form for a new Order Offer; income and payout target come from the backend. */
@Composable
fun CreateOfferScreen(
    viewModel: CreateOfferViewModel,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    CreateOfferContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onOrderAmountChange = viewModel::onOrderAmountChange,
        onIncomeRateChange = viewModel::onIncomeRateChange,
        onQuantityChange = viewModel::onQuantityChange,
        onActiveChange = viewModel::onActiveChange,
        onPickPayInQr = rememberQrImagePicker(onPicked = viewModel::onPayInQrSelected),
        onRemovePayInQr = viewModel::onPayInQrRemoved,
        onPayInQrUnreadable = viewModel::onPayInQrUnreadable,
        onSubmit = viewModel::submit,
        onStartNew = viewModel::startNew
    )
}

@Composable
private fun CreateOfferContent(
    uiState: CreateOfferUiState,
    onBackClick: () -> Unit,
    onOrderAmountChange: (String) -> Unit,
    onIncomeRateChange: (String) -> Unit,
    onQuantityChange: (String) -> Unit,
    onActiveChange: (Boolean) -> Unit,
    onPickPayInQr: () -> Unit,
    onRemovePayInQr: () -> Unit,
    onPayInQrUnreadable: () -> Unit,
    onSubmit: () -> Unit,
    onStartNew: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "Create Offer", onBackClick = onBackClick) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenHorizontalPadding, vertical = 8.dp)
        ) {
            val created = uiState.created
            if (created != null) {
                OrderCard(tag = "OFFER CREATED", accent = HawkEmerald) {
                    DetailRow(label = "Offer ID", value = created.id.toString())
                    AmountRow(label = "Member pays", amount = created.orderAmount)
                    AmountRow(
                        label = "Income (${DisplayFormat.percent(created.incomeRate)})",
                        amount = created.incomeAmount
                    )
                    AmountRow(label = "Member receives", amount = created.payoutTarget, emphasized = true)
                    DetailRow(label = "Quantity", value = created.quantityAvailable?.toString() ?: "No limit")
                    DetailRow(label = "Status", value = if (created.isActive) "Active" else "Inactive")
                    DetailRow(label = "Pay-In UPI ID", value = created.payInUpiId)
                }
                Spacer(modifier = Modifier.height(20.dp))
                PrimaryActionButton(text = "Create Another Offer", onClick = onStartNew)
                TextButton(onClick = onBackClick, modifier = Modifier.fillMaxWidth()) { Text("Back to Dashboard") }
                return@Column
            }

            uiState.submitError?.let {
                AuthErrorBanner(message = it, modifier = Modifier.padding(bottom = 12.dp))
            }
            OrderCard(tag = "OFFER") {
                FormField(
                    value = uiState.orderAmount,
                    onValueChange = onOrderAmountChange,
                    label = "Order amount (₹, whole rupees)",
                    error = uiState.orderAmountError,
                    keyboardType = KeyboardType.Number,
                    enabled = !uiState.isSubmitting
                )
                FormField(
                    value = uiState.incomeRate,
                    onValueChange = onIncomeRateChange,
                    label = "Income rate (%)",
                    error = uiState.incomeRateError,
                    helper = "0-100, up to 2 decimals. The server calculates the income.",
                    keyboardType = KeyboardType.Decimal,
                    enabled = !uiState.isSubmitting
                )
                FormField(
                    value = uiState.quantity,
                    onValueChange = onQuantityChange,
                    label = "Quantity (optional)",
                    error = uiState.quantityError,
                    helper = "How many members can claim it. Leave empty for no limit.",
                    keyboardType = KeyboardType.Number,
                    enabled = !uiState.isSubmitting
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Active",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Active offers are visible to members right away.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = uiState.isActive, onCheckedChange = onActiveChange, enabled = !uiState.isSubmitting)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Pay-In QR",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Members pay for this offer's orders using this QR.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                QrUploadSection(
                    qrImageUri = uiState.payInQrUri,
                    onPickClick = { if (!uiState.isSubmitting) onPickPayInQr() },
                    onRemoveClick = { if (!uiState.isSubmitting) onRemovePayInQr() },
                    onUnreadable = onPayInQrUnreadable,
                    errorText = uiState.payInQrError
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            PrimaryActionButton(text = "Create Offer", onClick = onSubmit, loading = uiState.isSubmitting)
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    keyboardType: KeyboardType,
    enabled: Boolean,
    helper: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = (error ?: helper)?.let { text -> { Text(text) } },
        singleLine = true,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    )
}

@Preview(showBackground = true)
@Composable
private fun CreateOfferPreview() {
    Hawk_WalletTheme {
        CreateOfferContent(
            uiState = CreateOfferUiState(orderAmount = "255", incomeRate = "4", isActive = true),
            onBackClick = {}, onOrderAmountChange = {}, onIncomeRateChange = {}, onQuantityChange = {},
            onActiveChange = {}, onPickPayInQr = {}, onRemovePayInQr = {}, onPayInQrUnreadable = {},
            onSubmit = {}, onStartNew = {}
        )
    }
}
