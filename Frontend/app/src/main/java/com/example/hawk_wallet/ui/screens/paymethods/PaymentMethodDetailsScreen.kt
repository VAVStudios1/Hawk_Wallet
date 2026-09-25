package com.example.hawk_wallet.ui.screens.paymethods

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material3.Button
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
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.components.QrUploadSection
import com.example.hawk_wallet.ui.components.rememberQrImagePicker
import com.example.hawk_wallet.ui.model.PaymentProviderOption
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

@Composable
fun PaymentMethodDetailsScreen(
    viewModel: PaymentMethodsViewModel,
    onBackClick: () -> Unit,
    onSaved: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val form = uiState.form
    val provider = form.provider

    // Reached without a provider (e.g. state lost): go back to choose one.
    if (provider == null) {
        LaunchedEffect(Unit) { onBackClick() }
        return
    }

    val pickQr = rememberQrImagePicker(onPicked = viewModel::onQrSelected)
    PaymentMethodDetailsContent(
        provider = provider,
        form = form,
        onBackClick = onBackClick,
        onUpiIdChange = viewModel::onUpiIdChange,
        onPickQr = pickQr,
        onRemoveQr = viewModel::removeQr,
        onQrUnreadable = viewModel::onQrUnreadable,
        onSaveClick = { if (viewModel.save()) onSaved() }
    )
}

@Composable
private fun PaymentMethodDetailsContent(
    provider: PaymentProviderOption,
    form: PaymentMethodForm,
    onBackClick: () -> Unit,
    onUpiIdChange: (String) -> Unit,
    onPickQr: () -> Unit,
    onRemoveQr: () -> Unit,
    onQrUnreadable: () -> Unit,
    onSaveClick: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "Add ${provider.displayName}", onBackClick = onBackClick) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            SectionLabel("UPI ID")
            OutlinedTextField(
                value = form.upiId,
                onValueChange = onUpiIdChange,
                placeholder = { Text("Enter your UPI ID") },
                isError = form.upiError != null,
                supportingText = {
                    Text(form.upiError ?: "Your ${provider.displayName} UPI ID, e.g. name@bank")
                },
                leadingIcon = { Icon(Icons.Filled.AlternateEmail, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))
            SectionLabel("QR Code")
            QrUploadSection(
                qrImageUri = form.qrImageUri,
                onPickClick = onPickQr,
                onRemoveClick = onRemoveQr,
                onUnreadable = onQrUnreadable,
                errorText = form.qrError
            )

            Spacer(modifier = Modifier.height(28.dp))
            Button(
                onClick = onSaveClick,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("Save Payment Method", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun PaymentMethodDetailsPreview() {
    Hawk_WalletTheme {
        PaymentMethodDetailsContent(
            provider = PaymentProviderOption.PHONEPE,
            form = PaymentMethodForm(
                provider = PaymentProviderOption.PHONEPE,
                upiId = "",
                upiError = "Enter your UPI ID.",
                qrError = "Upload your QR code."
            ),
            onBackClick = {},
            onUpiIdChange = {},
            onPickQr = {},
            onRemoveQr = {},
            onQrUnreadable = {},
            onSaveClick = {}
        )
    }
}
