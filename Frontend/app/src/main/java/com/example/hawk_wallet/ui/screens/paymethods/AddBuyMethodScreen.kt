package com.example.hawk_wallet.ui.screens.paymethods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.components.PaymentProviderCard
import com.example.hawk_wallet.ui.model.PaymentProviderOption
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

/** Label length accepted by the backend (UserBuyMethod.label). */
private const val LABEL_MAX_LENGTH = 64

/**
 * Add one PERSONAL / BUY account: provider (personal UPI apps only), UPI ID
 * and an optional label, on one screen. No QR is needed.
 */
@Composable
fun AddBuyMethodScreen(
    viewModel: BuyMethodsViewModel,
    onBackClick: () -> Unit,
    onSaved: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val form = uiState.form

    LaunchedEffect(form.saved) {
        if (form.saved) {
            onSaved()
            viewModel.saveHandled()
        }
    }

    AddBuyMethodContent(
        form = form,
        onBackClick = onBackClick,
        onSelectProvider = viewModel::selectProvider,
        onUpiIdChange = viewModel::onUpiIdChange,
        onLabelChange = { viewModel.onLabelChange(it.take(LABEL_MAX_LENGTH)) },
        onSaveClick = viewModel::save
    )
}

@Composable
private fun AddBuyMethodContent(
    form: BuyMethodForm,
    onBackClick: () -> Unit,
    onSelectProvider: (PaymentProviderOption) -> Unit,
    onUpiIdChange: (String) -> Unit,
    onLabelChange: (String) -> Unit,
    onSaveClick: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "Add BUY Account", onBackClick = onBackClick) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                BuySectionHeader(
                    title = "Which account do you pay from?",
                    description = "Your own UPI account that you use to pay for orders. It is never used " +
                        "to send you payouts - those go to your SELL accounts.",
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            item { SectionLabel("Choose your UPI app") }
            // PERSONAL / BUY providers only; business accounts are SELL methods.
            items(PaymentProviderOption.personal, key = { it.code }) { provider ->
                PaymentProviderCard(
                    provider = provider,
                    selected = provider == form.provider,
                    onClick = { onSelectProvider(provider) }
                )
            }
            item {
                SectionLabel("UPI ID", modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(
                    value = form.upiId,
                    onValueChange = onUpiIdChange,
                    placeholder = { Text("Enter your UPI ID") },
                    isError = form.upiError != null,
                    supportingText = { Text(form.upiError ?: "The UPI ID you pay from, e.g. name@bank") },
                    leadingIcon = { Icon(Icons.Filled.AlternateEmail, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                SectionLabel("Label (optional)")
                OutlinedTextField(
                    value = form.label,
                    onValueChange = onLabelChange,
                    placeholder = { Text("e.g. My savings account") },
                    isError = form.labelError != null,
                    supportingText = { Text(form.labelError ?: "${form.label.length}/$LABEL_MAX_LENGTH") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            form.generalError?.let {
                item { AuthErrorBanner(message = it) }
            }
            item {
                Button(
                    onClick = onSaveClick,
                    enabled = !form.isSaving,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .padding(top = 8.dp, bottom = 24.dp)
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    if (form.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Save BUY Account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(bottom = 8.dp)
    )
}

@Preview(showBackground = true, heightDp = 1000)
@Composable
private fun AddBuyMethodPreview() {
    Hawk_WalletTheme {
        AddBuyMethodContent(
            form = BuyMethodForm(provider = PaymentProviderOption.PHONEPE, upiId = "lucky@ybl"),
            onBackClick = {},
            onSelectProvider = {},
            onUpiIdChange = {},
            onLabelChange = {},
            onSaveClick = {}
        )
    }
}
